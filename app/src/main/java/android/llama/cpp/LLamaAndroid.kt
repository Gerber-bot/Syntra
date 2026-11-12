package android.llama.cpp

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Internal seam over the JNI entry points. Production uses [LLamaAndroid.DefaultNativeBridge],
 * which calls the existing external methods unchanged. Tests can inject an in-memory boundary
 * without loading the Android native library.
 */
internal interface LlamaNativeBridge {
    fun isAvailable(): Boolean
    fun benchModel(context: Long, model: Long, batch: Long, pp: Int, tg: Int, pl: Int, nr: Int): String
    fun loadModel(path: String): Long
    fun freeModel(model: Long)
    fun newContext(model: Long): Long
    fun freeContext(context: Long)
    fun newBatch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    fun freeBatch(batch: Long)
    fun newSampler(): Long
    fun freeSampler(sampler: Long)
    fun completionInit(
        context: Long,
        batch: Long,
        sampler: Long,
        text: String,
        formatChat: Boolean,
        nLen: Int,
        genId: Long
    ): Int
    fun completionLoop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int,
        nCur: Int,
        genId: Long
    ): String?
    fun kvCacheClear(context: Long)
    fun cancelGeneration(genId: Long)
}

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
    private var activeLoadDeferred: CompletableDeferred<Unit>? = null

    /**
     * Test seam. Production keeps the default bridge; JVM tests may replace it before first use.
     */
    @Volatile
    internal var nativeBridge: LlamaNativeBridge = DefaultNativeBridge()

    private val runLoopExecutor = Executors.newSingleThreadExecutor {
        thread(start = false, name = "Llm-RunLoop") {
            if (ensureNativeLoaded()) {
                try {
                    log_to_android()
                    backend_init()
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
    }
    private val runLoop: CoroutineDispatcher = runLoopExecutor.asCoroutineDispatcher()

    /**
     * Owns teardown. It is deliberately independent from whoever calls [unload] so that
     * cancelling that caller can never abort cleanup or drop resource ownership.
     */
    private val unloadScope = CoroutineScope(SupervisorJob() + runLoop)

    private inner class DefaultNativeBridge : LlamaNativeBridge {
        override fun isAvailable(): Boolean = isLibraryLoaded
        override fun benchModel(context: Long, model: Long, batch: Long, pp: Int, tg: Int, pl: Int, nr: Int): String =
            bench_model(context, model, batch, pp, tg, pl, nr)
        override fun loadModel(path: String): Long = load_model(path)
        override fun freeModel(model: Long) = free_model(model)
        override fun newContext(model: Long): Long = new_context(model)
        override fun freeContext(context: Long) = free_context(context)
        override fun newBatch(nTokens: Int, embd: Int, nSeqMax: Int): Long = new_batch(nTokens, embd, nSeqMax)
        override fun freeBatch(batch: Long) = free_batch(batch)
        override fun newSampler(): Long = new_sampler()
        override fun freeSampler(sampler: Long) = free_sampler(sampler)
        override fun completionInit(
            context: Long,
            batch: Long,
            sampler: Long,
            text: String,
            formatChat: Boolean,
            nLen: Int,
            genId: Long
        ): Int = completion_init(context, batch, sampler, text, formatChat, nLen, genId)
        override fun completionLoop(
            context: Long,
            batch: Long,
            sampler: Long,
            nLen: Int,
            nCur: Int,
            genId: Long
        ): String? = completion_loop(context, batch, sampler, nLen, nCur, genId)
        override fun kvCacheClear(context: Long) = kv_cache_clear(context)
        override fun cancelGeneration(genId: Long) = cancel_generation(genId)
    }

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

    /** Stops background workers. Intended for isolated JVM tests only. */
    internal fun shutdownForTest() {
        unloadScope.cancel()
        runLoopExecutor.shutdownNow()
    }

    var nlen: Int = 256

    private external fun log_to_android()
    private external fun load_model(filename: String): Long
    private external fun free_model(model: Long)
    private external fun new_context(model: Long): Long
    private external fun free_context(context: Long)
    private external fun backend_init()
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
        val bridge = nativeBridge
        val genId = activeGenerationId
        if (bridge.isAvailable() && genId > 0) {
            try {
                bridge.cancelGeneration(genId)
            } catch (t: Throwable) {
                Log.w(tag, "Failed to cancel generation: ${t.message}")
            }
        }
    }

    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String {
        val bridge = nativeBridge
        return withContext(runLoop) {
            val state = synchronized(stateLock) { nativeState }
            when (state) {
                is State.Loaded -> {
                    Log.d(tag, "bench(): $state")
                    bridge.benchModel(state.context, state.model, state.batch, pp, tg, pl, nr)
                }
                else -> throw IllegalStateException("No model loaded")
            }
        }
    }

    suspend fun load(pathToModel: String) {
        val currentLoadId = loadIdCounter.incrementAndGet()
        val loadDeferred = CompletableDeferred<Unit>()
        val bridge = nativeBridge
        withContext(runLoop) {
            if (!bridge.isAvailable()) {
                throw UnsatisfiedLinkError("Librería nativa llama-android no cargada")
            }

            synchronized(stateLock) {
                if (nativeState !is State.Idle) {
                    throw IllegalStateException("Cannot load model: engine is not Idle (current state: $nativeState)")
                }
                isLoadCancelled = false
                activeLoadId = currentLoadId
                nativeState = State.Loading(currentLoadId, pathToModel)
                activeLoadDeferred = loadDeferred
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
                model = bridge.loadModel(pathToModel)
                if (model == 0L) throw IllegalStateException("load_model() returned null")
                checkNotCancelled()

                context = bridge.newContext(model)
                if (context == 0L) throw IllegalStateException("new_context() returned null")
                checkNotCancelled()

                batch = bridge.newBatch(2048, 0, 1)
                if (batch == 0L) throw IllegalStateException("new_batch() returned null")
                checkNotCancelled()

                sampler = bridge.newSampler()
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
                // Release only what was acquired. If any free fails, ownership is kept in
                // Unloading so a later unload() can retry instead of leaking silently.
                val acquired = State.Loaded(model, context, batch, sampler, pathToModel)
                val remaining = releaseResourcesOnCurrentThread(acquired)
                synchronized(stateLock) {
                    if (remaining == null) {
                        if (nativeState is State.Loading && (nativeState as State.Loading).loadId == currentLoadId) {
                            nativeState = State.Idle
                        }
                    } else if (nativeState is State.Loading || nativeState is State.Unloading) {
                        nativeState = State.Unloading(remaining)
                    }
                }
                throw t
            } finally {
                synchronized(stateLock) {
                    if (activeLoadDeferred === loadDeferred) {
                        activeLoadDeferred = null
                    }
                }
                loadDeferred.complete(Unit)
            }
        }
    }

    fun send(message: String, maxTokens: Int = nlen, formatChat: Boolean = true): Flow<String> = flow {
        val genDeferred = CompletableDeferred<Unit>()
        val genId = generationIdCounter.incrementAndGet()
        val bridge = nativeBridge
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
            val promptTokens = bridge.completionInit(
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
                val str = bridge.completionLoop(
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
                bridge.kvCacheClear(loadedState.context)
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

    private data class UnloadTicket(
        val isOwner: Boolean,
        val completion: CompletableDeferred<Unit>?,
        val generationJob: Job?,
        val generationDone: CompletableDeferred<Unit>?,
        val loadDone: CompletableDeferred<Unit>?
    )

    /**
     * Releases resources on the thread that already owns the native session.
     * Every resource is attempted independently. If any free fails, the remaining pointers
     * are returned so ownership is preserved and a later unload() can retry.
     * The model is only released once its context is gone.
     */
    private fun releaseResourcesOnCurrentThread(loaded: State.Loaded): State.Loaded? {
        val bridge = nativeBridge
        var sampler = loaded.sampler
        var batch = loaded.batch
        var context = loaded.context
        var model = loaded.model

        fun freeOne(name: String, pointer: Long, free: (Long) -> Unit): Boolean {
            if (pointer == 0L) return true
            return try {
                free(pointer)
                true
            } catch (t: Throwable) {
                Log.w(tag, "Error freeing native $name: ${t.message}")
                false
            }
        }

        if (freeOne("sampler", sampler) { bridge.freeSampler(it) }) sampler = 0L
        if (freeOne("batch", batch) { bridge.freeBatch(it) }) batch = 0L
        val contextFreed = freeOne("context", context) { bridge.freeContext(it) }
        if (contextFreed) context = 0L
        if (contextFreed && freeOne("model", model) { bridge.freeModel(it) }) model = 0L

        return if (sampler == 0L && batch == 0L && context == 0L && model == 0L) {
            Log.i(tag, "Unloaded model and freed native resources: ${loaded.modelPath}")
            null
        } else {
            State.Loaded(model, context, batch, sampler, loaded.modelPath)
        }
    }

    /**
     * Sole owner of one teardown attempt. Runs in [unloadScope], therefore caller cancellation
     * never aborts it. Resources are never released before the active generation and any
     * in-progress load have completed their own cleanup.
     */
    private suspend fun runUnload(ticket: UnloadTicket) {
        val completion = ticket.completion ?: return
        try {
            val bridge = nativeBridge

            if (ticket.generationJob != null) {
                val genId = activeGenerationId
                if (genId > 0L) {
                    try {
                        bridge.cancelGeneration(genId)
                    } catch (t: Throwable) {
                        Log.w(tag, "Failed to request native generation cancellation: ${t.message}")
                    }
                }
                ticket.generationJob.cancel(CancellationException("Modelo en proceso de descarga"))
            }

            ticket.generationDone?.let { done ->
                val finished = withTimeoutOrNull(UNLOAD_DIAGNOSTIC_TIMEOUT_MS) {
                    done.await()
                    true
                }
                if (finished == null) {
                    Log.w(
                        tag,
                        "La generación activa sigue en curso tras ${UNLOAD_DIAGNOSTIC_TIMEOUT_MS} ms; " +
                            "se continúa esperando sin liberar recursos"
                    )
                    done.await()
                }
            }

            ticket.loadDone?.await()

            val snapshot = synchronized(stateLock) {
                (nativeState as? State.Unloading)?.loadedState
            }
            val remaining = if (snapshot != null) {
                withContext(NonCancellable) {
                    withContext(runLoop) {
                        releaseResourcesOnCurrentThread(snapshot)
                    }
                }
            } else {
                null
            }

            synchronized(stateLock) {
                if (remaining == null) {
                    nativeState = State.Idle
                    isGenerating = false
                    activeGenerationJob = null
                    activeGenerationDeferred = null
                    activeLoadDeferred = null
                } else {
                    nativeState = State.Unloading(remaining)
                }
            }

            if (remaining != null) {
                completion.completeExceptionally(
                    IllegalStateException(
                        "No se pudieron liberar todos los recursos nativos; se conserva Unloading para reintentar"
                    )
                )
            } else {
                completion.complete(Unit)
            }
        } catch (t: Throwable) {
            completion.completeExceptionally(t)
        } finally {
            synchronized(stateLock) {
                if (activeUnloadDeferred === completion) {
                    activeUnloadDeferred = null
                }
            }
        }
    }

    suspend fun unload() {
        val ticket = synchronized(stateLock) {
            when (val current = nativeState) {
                is State.Idle -> UnloadTicket(false, null, null, null, null)
                is State.Unloading -> {
                    val inFlight = activeUnloadDeferred
                    if (current.loadedState != null && (inFlight == null || inFlight.isCompleted)) {
                        // A previous attempt kept ownership of remaining pointers: retry them.
                        val completion = CompletableDeferred<Unit>()
                        activeUnloadDeferred = completion
                        UnloadTicket(true, completion, null, null, null)
                    } else {
                        UnloadTicket(false, inFlight, null, null, null)
                    }
                }
                is State.Loading -> {
                    // Abort the pending load and wait for its own rollback before publishing Idle.
                    isLoadCancelled = true
                    val completion = CompletableDeferred<Unit>()
                    activeUnloadDeferred = completion
                    nativeState = State.Unloading(null)
                    UnloadTicket(true, completion, null, null, activeLoadDeferred)
                }
                is State.Loaded -> {
                    val completion = CompletableDeferred<Unit>()
                    activeUnloadDeferred = completion
                    nativeState = State.Unloading(current)
                    UnloadTicket(
                        isOwner = true,
                        completion = completion,
                        generationJob = if (isGenerating) activeGenerationJob else null,
                        generationDone = if (isGenerating) activeGenerationDeferred else null,
                        loadDone = activeLoadDeferred
                    )
                }
            }
        }

        if (ticket.isOwner) {
            unloadScope.launch { runUnload(ticket) }
        }
        ticket.completion?.await()
    }

    companion object {
        private const val UNLOAD_DIAGNOSTIC_TIMEOUT_MS = 5000L

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
