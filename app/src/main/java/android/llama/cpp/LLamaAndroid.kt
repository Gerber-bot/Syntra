package android.llama.cpp

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class LLamaAndroid {
    private val tag: String? = this::class.simpleName

    private val stateLock = Any()

    @Volatile
    private var nativeState: State = State.Idle

    @Volatile
    private var isGenerating: Boolean = false

    private val runLoop: CoroutineDispatcher = Executors.newSingleThreadExecutor {
        thread(start = false, name = "Llm-RunLoop") {
            if (ensureNativeLoaded()) {
                try {
                    log_to_android()
                    backend_init(false)
                    Log.d(tag, system_info())
                } catch (e: Throwable) {
                    Log.w(tag, "Failed to initialize native backend: ${e.message}")
                }
            }
            it.run()
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, exception: Throwable ->
                Log.e(tag, "Unhandled exception in Llm-RunLoop", exception)
            }
        }
    }.asCoroutineDispatcher()

    fun isNativeLibraryLoaded(): Boolean = ensureNativeLoaded()

    fun getNativeLoadError(): String? = libraryLoadError

    fun hasModelLoaded(): Boolean = synchronized(stateLock) {
        nativeState is State.Loaded
    }

    fun getCurrentModelPath(): String? = synchronized(stateLock) {
        (nativeState as? State.Loaded)?.modelPath
    }

    var nlen: Int = 256

    private external fun log_to_android()
    private external fun load_model(filename: String): Long
    private external fun free_model(model: Long)
    private external fun new_context(model: Long): Long
    private external fun free_context(context: Long)
    private external fun backend_init(numa: Boolean)
    private external fun backend_free()
    private external fun new_batch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    private external fun free_batch(batch: Long)
    private external fun new_sampler(): Long
    private external fun free_sampler(sampler: Long)
    private external fun bench_model(
        context: Long,
        model: Long,
        batch: Long,
        pp: Int,
        tg: Int,
        pl: Int,
        nr: Int
    ): String

    private external fun system_info(): String

    private external fun completion_init(
        context: Long,
        batch: Long,
        text: String,
        formatChat: Boolean,
        nLen: Int
    ): Int

    private external fun completion_loop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int,
        ncur: Int
    ): String?

    private external fun kv_cache_clear(context: Long)
    private external fun cancel_generation()

    fun cancel() {
        if (isLibraryLoaded) {
            try {
                cancel_generation()
            } catch (t: Throwable) {
                Log.w(tag, "Failed to cancel generation: ${t.message}")
            }
        }
    }

    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String {
        return withContext(runLoop) {
            val state = synchronized(stateLock) { nativeState }
            when (state) {
                is State.Loaded -> {
                    Log.d(tag, "bench(): $state")
                    bench_model(state.context, state.model, state.batch, pp, tg, pl, nr)
                }
                else -> throw IllegalStateException("No model loaded")
            }
        }
    }

    suspend fun load(pathToModel: String) {
        withContext(runLoop) {
            if (!isLibraryLoaded) {
                throw UnsatisfiedLinkError("Librería nativa llama-android no cargada")
            }

            synchronized(stateLock) {
                if (nativeState is State.Loaded || nativeState is State.Loading) {
                    throw IllegalStateException("Model already loaded or currently loading")
                }
                nativeState = State.Loading
            }

            try {
                val model = load_model(pathToModel)
                if (model == 0L) throw IllegalStateException("load_model() returned null")

                val context = new_context(model)
                if (context == 0L) {
                    free_model(model)
                    throw IllegalStateException("new_context() returned null")
                }

                val batch = new_batch(2048, 0, 1)
                if (batch == 0L) {
                    free_context(context)
                    free_model(model)
                    throw IllegalStateException("new_batch() returned null")
                }

                val sampler = new_sampler()
                if (sampler == 0L) {
                    free_batch(batch)
                    free_context(context)
                    free_model(model)
                    throw IllegalStateException("new_sampler() returned null")
                }

                synchronized(stateLock) {
                    nativeState = State.Loaded(model, context, batch, sampler, pathToModel)
                }
                Log.i(tag, "Loaded model successfully: $pathToModel")
            } catch (t: Throwable) {
                synchronized(stateLock) {
                    nativeState = State.Idle
                }
                throw t
            }
        }
    }

    fun send(message: String, maxTokens: Int = nlen, formatChat: Boolean = true): Flow<String> = flow {
        val state = synchronized(stateLock) {
            val current = nativeState
            if (current !is State.Loaded) {
                throw IllegalStateException("No model loaded")
            }
            if (isGenerating) {
                throw IllegalStateException("Inference already in progress")
            }
            isGenerating = true
            current
        }

        try {
            val promptTokens = completion_init(state.context, state.batch, message, formatChat, maxTokens)
            if (promptTokens < 0) {
                throw IllegalStateException("completion_init failed with error code $promptTokens (prompt exceeds context or decode failed)")
            }
            val targetEnd = promptTokens + maxTokens
            var ncur = promptTokens

            while (ncur < targetEnd) {
                val str = completion_loop(state.context, state.batch, state.sampler, targetEnd, ncur)
                if (str == null) {
                    break
                }
                ncur++
                if (str.isNotEmpty()) {
                    emit(str)
                }
            }
        } finally {
            try {
                kv_cache_clear(state.context)
            } catch (_: Throwable) {}
            synchronized(stateLock) {
                isGenerating = false
            }
        }
    }.flowOn(runLoop)

    suspend fun unload() {
        withContext(runLoop) {
            val stateToFree: State.Loaded? = synchronized(stateLock) {
                val current = nativeState
                if (current is State.Loaded) {
                    nativeState = State.Idle
                    current
                } else {
                    nativeState = State.Idle
                    null
                }
            }

            if (stateToFree != null) {
                try {
                    cancel_generation()
                    free_sampler(stateToFree.sampler)
                    free_batch(stateToFree.batch)
                    free_context(stateToFree.context)
                    free_model(stateToFree.model)
                    Log.i(tag, "Unloaded model and freed native resources: ${stateToFree.modelPath}")
                } catch (t: Throwable) {
                    Log.w(tag, "Error freeing native resources: ${t.message}")
                }
            }
        }
    }

    companion object {
        @Volatile
        private var libraryLoadAttempted = false
        @Volatile
        private var libraryLoadError: String? = null
        @Volatile
        private var isLibraryLoaded = false

        fun ensureNativeLoaded(): Boolean {
            if (libraryLoadAttempted) return isLibraryLoaded
            synchronized(this) {
                if (libraryLoadAttempted) return isLibraryLoaded
                libraryLoadAttempted = true
                try {
                    val deps = listOf("ggml-base", "ggml", "ggml-cpu", "llama", "llama-android")
                    for (dep in deps) {
                        try {
                            System.loadLibrary(dep)
                        } catch (t: Throwable) {
                            if (dep == "llama-android") throw t
                        }
                    }
                    isLibraryLoaded = true
                    libraryLoadError = null
                } catch (t: Throwable) {
                    isLibraryLoaded = false
                    libraryLoadError = "${t.javaClass.simpleName}: ${t.message}"
                    Log.w("LLamaAndroid", "Native library llama-android not available: $libraryLoadError", t)
                }
                return isLibraryLoaded
            }
        }

        fun getNativeLoadError(): String? = libraryLoadError

        sealed interface State {
            data object Idle: State
            data object Loading: State
            data class Loaded(
                val model: Long,
                val context: Long,
                val batch: Long,
                val sampler: Long,
                val modelPath: String
            ): State
        }

        private val _instance: LLamaAndroid = LLamaAndroid()

        fun instance(): LLamaAndroid = _instance
    }
}
