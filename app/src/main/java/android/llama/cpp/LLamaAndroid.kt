package android.llama.cpp

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class LLamaAndroid {
    private val tag: String? = this::class.simpleName

    private val stateLock = Any()

    @Volatile
    private var nativeState: State = State.Idle

    @Volatile
    private var isGenerating: Boolean = false

    private var activeGenerationDeferred: CompletableDeferred<Unit>? = null
    private val generationIdCounter = AtomicLong(0L)
    @Volatile
    private var activeGenerationId: Long = 0L

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

    fun isGenerationActive(): Boolean = synchronized(stateLock) {
        isGenerating
    }

    fun getCurrentState(): State = synchronized(stateLock) {
        nativeState
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
        sampler: Long,
        text: String,
        formatChat: Boolean,
        nLen: Int,
        genId: Long
    ): Int

    private external fun completion_loop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int,
        ncur: Int,
        genId: Long
    ): String?

    private external fun kv_cache_clear(context: Long)
    private external fun cancel_generation(genId: Long)

    fun cancel() {
        val genId = activeGenerationId
        if (isLibraryLoaded && genId > 0) {
            try {
                cancel_generation(genId)
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
                if (nativeState !is State.Idle) {
                    throw IllegalStateException("Cannot load model: engine is not Idle (current state: $nativeState)")
                }
                nativeState = State.Loading(pathToModel)
            }

            var model: Long = 0L
            var context: Long = 0L
            var batch: Long = 0L
            var sampler: Long = 0L

            try {
                model = load_model(pathToModel)
                if (model == 0L) throw IllegalStateException("load_model() returned null")

                context = new_context(model)
                if (context == 0L) throw IllegalStateException("new_context() returned null")

                batch = new_batch(2048, 0, 1)
                if (batch == 0L) throw IllegalStateException("new_batch() returned null")

                sampler = new_sampler()
                if (sampler == 0L) throw IllegalStateException("new_sampler() returned null")

                synchronized(stateLock) {
                    nativeState = State.Loaded(model, context, batch, sampler, pathToModel)
                }
                Log.i(tag, "Loaded model successfully: $pathToModel")
            } catch (t: Throwable) {
                // Structured acquisition rollback: release acquired pointers in reverse order
                if (sampler != 0L) try { free_sampler(sampler) } catch (_: Throwable) {}
                if (batch != 0L) try { free_batch(batch) } catch (_: Throwable) {}
                if (context != 0L) try { free_context(context) } catch (_: Throwable) {}
                if (model != 0L) try { free_model(model) } catch (_: Throwable) {}
                synchronized(stateLock) {
                    nativeState = State.Idle
                }
                throw t
            }
        }
    }

    fun send(message: String, maxTokens: Int = nlen, formatChat: Boolean = true): Flow<String> = flow {
        val genDeferred = CompletableDeferred<Unit>()
        val genId = generationIdCounter.incrementAndGet()
        val loadedState: State.Loaded

        synchronized(stateLock) {
            val current = nativeState
            if (current !is State.Loaded) {
                throw IllegalStateException("No model loaded (current state: $current)")
            }
            if (isGenerating) {
                throw IllegalStateException("Inference already in progress")
            }
            isGenerating = true
            activeGenerationId = genId
            activeGenerationDeferred = genDeferred
            loadedState = current
        }

        try {
            val promptTokens = completion_init(
                loadedState.context,
                loadedState.batch,
                loadedState.sampler,
                message,
                formatChat,
                maxTokens,
                genId
            )
            if (promptTokens < 0) {
                throw IllegalStateException("completion_init failed with error code $promptTokens")
            }
            val targetEnd = promptTokens + maxTokens
            var ncur = promptTokens

            while (ncur < targetEnd) {
                val str = completion_loop(
                    loadedState.context,
                    loadedState.batch,
                    loadedState.sampler,
                    targetEnd,
                    ncur,
                    genId
                )
                if (str == null) {
                    // Normal completion (EOG reached)
                    break
                }
                ncur++
                if (str.isNotEmpty()) {
                    emit(str)
                }
            }
        } finally {
            // Context cleanup MUST happen while the context is 100% still valid!
            // Notice: unload() awaits genDeferred, so loadedState.context cannot be freed during this call.
            try {
                kv_cache_clear(loadedState.context)
            } catch (_: Throwable) {}

            synchronized(stateLock) {
                isGenerating = false
                if (activeGenerationId == genId) {
                    activeGenerationDeferred = null
                }
            }
            // Signal to unload() or any waiting operation that generation cleanup has completely finished
            genDeferred.complete(Unit)
        }
    }.flowOn(runLoop)

    suspend fun unload() {
        val (deferredToAwait, stateToFree) = synchronized(stateLock) {
            when (val current = nativeState) {
                is State.Loaded -> {
                    val deferred = if (isGenerating) activeGenerationDeferred else null
                    nativeState = State.Unloading(current)
                    Pair(deferred, current)
                }
                is State.Unloading -> {
                    val deferred = if (isGenerating) activeGenerationDeferred else null
                    Pair(deferred, current.loadedState)
                }
                else -> {
                    nativeState = State.Idle
                    Pair(null, null)
                }
            }
        }

        if (stateToFree != null) {
            // 1. Cooperative cancellation request to native bridge
            try {
                cancel_generation(activeGenerationId)
            } catch (_: Throwable) {}

            // 2. Await full completion of active generation's finally block
            try {
                deferredToAwait?.await()
            } catch (_: Throwable) {}

            // 3. Now generation has fully terminated and run kv_cache_clear().
            // We can safely free native resources on runLoop.
            withContext(runLoop) {
                try {
                    free_sampler(stateToFree.sampler)
                    free_batch(stateToFree.batch)
                    free_context(stateToFree.context)
                    free_model(stateToFree.model)
                    Log.i(tag, "Unloaded model and freed native resources: ${stateToFree.modelPath}")
                } catch (t: Throwable) {
                    Log.w(tag, "Error freeing native resources: ${t.message}")
                } finally {
                    synchronized(stateLock) {
                        nativeState = State.Idle
                    }
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
            data class Loading(val modelPath: String): State
            data class Loaded(
                val model: Long,
                val context: Long,
                val batch: Long,
                val sampler: Long,
                val modelPath: String
            ): State
            data class Unloading(val loadedState: Loaded): State
        }

        private val _instance: LLamaAndroid = LLamaAndroid()

        fun instance(): LLamaAndroid = _instance
    }
}
