package android.llama.cpp

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException
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

    private var activeGenerationJob: Job? = null
    private var activeGenerationDeferred: CompletableDeferred<Unit>? = null
    private val generationIdCounter = AtomicLong(0L)
    @Volatile
    private var activeGenerationId: Long = 0L

    private val loadIdCounter = AtomicLong(0L)
    @Volatile
    private var activeLoadId: Long = 0L
    @Volatile
    private var isLoadCancelled: Boolean = false

    private var activeUnloadDeferred: CompletableDeferred<Unit>? = null

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
        val currentLoadId = loadIdCounter.incrementAndGet()
        withContext(runLoop) {
            if (!isLibraryLoaded) {
                throw UnsatisfiedLinkError("Librería nativa llama-android no cargada")
            }

            synchronized(stateLock) {
                if (nativeState !is State.Idle) {
                    throw IllegalStateException("Cannot load model: engine is not Idle (current state: $nativeState)")
                }
                isLoadCancelled = false
                activeLoadId = currentLoadId
                nativeState = State.Loading(currentLoadId, pathToModel)
            }

            fun checkNotCancelled() {
                if (isLoadCancelled || activeLoadId != currentLoadId) {
                    throw CancellationException("Carga abortada por solicitud de descarga")
                }
            }

            var model: Long = 0L
            var context: Long = 0L
            var batch: Long = 0L
            var sampler: Long = 0L

            try {
                model = load_model(pathToModel)
                if (model == 0L) throw IllegalStateException("load_model() returned null")
                checkNotCancelled()

                context = new_context(model)
                if (context == 0L) throw IllegalStateException("new_context() returned null")
                checkNotCancelled()

                batch = new_batch(2048, 0, 1)
                if (batch == 0L) throw IllegalStateException("new_batch() returned null")
                checkNotCancelled()

                sampler = new_sampler()
                if (sampler == 0L) throw IllegalStateException("new_sampler() returned null")
                checkNotCancelled()

                synchronized(stateLock) {
                    if (isLoadCancelled || activeLoadId != currentLoadId || nativeState !is State.Loading) {
                        throw CancellationException("Carga abortada antes de publicar Loaded")
                    }
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
                    if (nativeState is State.Loading && (nativeState as State.Loading).loadId == currentLoadId) {
                        nativeState = State.Idle
                    }
                }
                throw t
            }
        }
    }

    fun send(message: String, maxTokens: Int = nlen, formatChat: Boolean = true): Flow<String> = flow {
        val genDeferred = CompletableDeferred<Unit>()
        val genId = generationIdCounter.incrementAndGet()
        val loadedState: State.Loaded
        val job = currentCoroutineContext()[Job]

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
            activeGenerationJob = job
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
            try {
                kv_cache_clear(loadedState.context)
            } catch (_: Throwable) {}

            synchronized(stateLock) {
                isGenerating = false
                if (activeGenerationId == genId) {
                    activeGenerationJob = null
                    activeGenerationDeferred = null
                }
            }
            genDeferred.complete(Unit)
        }
    }.flowOn(runLoop)

    private data class UnloadPlan(
        val isOwner: Boolean,
        val unloadDeferred: CompletableDeferred<Unit>?,
        val stateToFree: State.Loaded?,
        val genJobToCancel: Job?,
        val genDeferredToAwait: CompletableDeferred<Unit>?
    )

    suspend fun unload() {
        val plan = synchronized(stateLock) {
            when (val current = nativeState) {
                is State.Idle -> {
                    UnloadPlan(
                        isOwner = false,
                        unloadDeferred = null,
                        stateToFree = null,
                        genJobToCancel = null,
                        genDeferredToAwait = null
                    )
                }
                is State.Unloading -> {
                    // Secondary concurrent caller: simply await ongoing single-flight unload
                    UnloadPlan(
                        isOwner = false,
                        unloadDeferred = activeUnloadDeferred,
                        stateToFree = null,
                        genJobToCancel = null,
                        genDeferredToAwait = null
                    )
                }
                is State.Loading -> {
                    // Cancel pending load so it aborts and rolls back
                    isLoadCancelled = true
                    val deferred = CompletableDeferred<Unit>()
                    activeUnloadDeferred = deferred
                    nativeState = State.Unloading(null)
                    UnloadPlan(
                        isOwner = true,
                        unloadDeferred = deferred,
                        stateToFree = null,
                        genJobToCancel = null,
                        genDeferredToAwait = null
                    )
                }
                is State.Loaded -> {
                    val deferred = CompletableDeferred<Unit>()
                    activeUnloadDeferred = deferred
                    nativeState = State.Unloading(current)
                    val job = activeGenerationJob
                    val genDef = if (isGenerating) activeGenerationDeferred else null
                    UnloadPlan(
                        isOwner = true,
                        unloadDeferred = deferred,
                        stateToFree = current,
                        genJobToCancel = job,
                        genDeferredToAwait = genDef
                    )
                }
            }
        }

        if (!plan.isOwner) {
            plan.unloadDeferred?.await()
            return
        }

        // --- SOLE OWNER OF UNLOAD ---
        try {
            // 1. Cooperative cancellation request to native bridge
            val genId = activeGenerationId
            if (genId > 0) {
                try {
                    cancel_generation(genId)
                } catch (_: Throwable) {}
            }

            // 2. Cancel the coroutine job that may be suspended in emit()
            plan.genJobToCancel?.cancel(CancellationException("Modelo en proceso de descarga"))

            // 3. Await generation cleanup with timeout to prevent deadlock
            if (plan.genDeferredToAwait != null) {
                withTimeoutOrNull(5000L) {
                    plan.genDeferredToAwait.await()
                } ?: run {
                    Log.w(tag, "La generación activa no finalizó su limpieza dentro del tiempo límite durante unload")
                }
            }

            // 4. Free native resources under NonCancellable on runLoop so caller cancellation CANNOT abort cleanup
            withContext(NonCancellable) {
                withContext(runLoop) {
                    val stateToFree = plan.stateToFree
                    if (stateToFree != null) {
                        try {
                            free_sampler(stateToFree.sampler)
                            free_batch(stateToFree.batch)
                            free_context(stateToFree.context)
                            free_model(stateToFree.model)
                            Log.i(tag, "Unloaded model and freed native resources: ${stateToFree.modelPath}")
                        } catch (t: Throwable) {
                            Log.w(tag, "Error freeing native resources: ${t.message}")
                        }
                    }
                    synchronized(stateLock) {
                        nativeState = State.Idle
                        isGenerating = false
                        activeGenerationJob = null
                        activeGenerationDeferred = null
                    }
                }
            }
        } finally {
            synchronized(stateLock) {
                if (nativeState is State.Unloading) {
                    nativeState = State.Idle
                }
                activeUnloadDeferred?.complete(Unit)
                activeUnloadDeferred = null
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
            data class Loading(val loadId: Long, val modelPath: String): State
            data class Loaded(
                val model: Long,
                val context: Long,
                val batch: Long,
                val sampler: Long,
                val modelPath: String
            ): State
            data class Unloading(val loadedState: Loaded?): State
        }

        private val _instance: LLamaAndroid = LLamaAndroid()

        fun instance(): LLamaAndroid = _instance
    }
}
