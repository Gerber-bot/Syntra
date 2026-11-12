package com.syntra.app.domain.neural

import android.llama.cpp.LLamaAndroid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Internal seam over the native engine. Production uses [LlamaAndroidNativeAccess], which
 * delegates to the existing JNI wrapper unchanged. JVM tests inject an in-memory boundary
 * so the engine state machine can be exercised without the ARM64 library.
 */
internal interface NeuralNativeAccess {
    fun isAvailable(): Boolean
    fun isModelLoaded(): Boolean
    suspend fun load(path: String)
    suspend fun unload()
    fun send(prompt: String, maxTokens: Int): Flow<String>
    fun cancel()
}

internal object LlamaAndroidNativeAccess : NeuralNativeAccess {
    override fun isAvailable(): Boolean = LLamaAndroid.ensureNativeLoaded()
    override fun isModelLoaded(): Boolean = LLamaAndroid.instance().hasModelLoaded()
    override suspend fun load(path: String) = LLamaAndroid.instance().load(path)
    override suspend fun unload() = LLamaAndroid.instance().unload()
    override fun send(prompt: String, maxTokens: Int): Flow<String> =
        LLamaAndroid.instance().send(prompt, maxTokens = maxTokens, formatChat = true)
    override fun cancel() = LLamaAndroid.instance().cancel()
}

/**
 * Native llama.cpp adapter implementing [NeuralInferenceEngine].
 * Bridges Kotlin coroutines with official [LLamaAndroid] JNI inference.
 * Gracefully degrades if native libraries are missing on the target host/JVM.
 */
class LlamaCppInferenceEngine(
    private val fallbackEngine: NeuralInferenceEngine? = null
) : NeuralInferenceEngine {

    companion object {
        fun isNativeAvailable(): Boolean {
            return LLamaAndroid.ensureNativeLoaded()
        }

        fun getNativeLoadError(): String? {
            return LLamaAndroid.getNativeLoadError()
        }
    }

    /** Test seam. Production keeps the default access bound to [LLamaAndroid]. */
    @Volatile
    internal var nativeAccess: NeuralNativeAccess = LlamaAndroidNativeAccess

    @Volatile
    override var state: EngineState = EngineState.UNINITIALIZED
        private set

    @Volatile
    override var currentModelFile: File? = null
        private set

    @Volatile
    override var metrics: InferenceMetrics = InferenceMetrics()
        private set

    @Volatile
    private var isCancelled = false

    override suspend fun loadModel(modelFile: File, threads: Int): Result<Unit> = withContext(Dispatchers.IO) {
        if (!modelFile.exists()) {
            return@withContext Result.failure(IllegalArgumentException("El archivo de modelo no existe: ${modelFile.absolutePath}"))
        }

        // Avoid duplicate loading if the same file is already in memory
        if (isModelLoaded() && currentModelFile?.absolutePath == modelFile.absolutePath) {
            return@withContext Result.success(Unit)
        }

        // Validate before tearing anything down: an invalid file must not cost a working session.
        val validation = ModelManager.validateGgufFile(modelFile)
        if (validation.isFailure) {
            return@withContext Result.failure(validation.exceptionOrNull() ?: IllegalArgumentException("Archivo GGUF inválido"))
        }

        val native = nativeAccess

        if (!native.isAvailable()) {
            // Native lib not loaded (e.g. host JVM test environment)
            state = EngineState.READY
            currentModelFile = modelFile
            metrics = metrics.copy(
                modelName = modelFile.name,
                modelSizeBytes = modelFile.length(),
                loadDurationMillis = 0L
            )
            return@withContext Result.success(Unit)
        }

        // The engine may not consider itself READY while llama.cpp still owns a session:
        // a failed generation, an aborted load or a leftover session from a previous owner.
        // Loading on top of it is rejected by the native layer ("engine is not Idle"), so the
        // session must be released first or the engine stays stuck in ERROR forever.
        if (native.isModelLoaded()) {
            try {
                unloadModel()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A failed release is reported by the load attempt below.
            }
        }

        val startTime = System.currentTimeMillis()
        state = EngineState.LOADING

        try {
            native.load(modelFile.absolutePath)
            currentModelFile = modelFile
            state = EngineState.READY

            val loadDuration = System.currentTimeMillis() - startTime
            metrics = metrics.copy(
                modelName = modelFile.name,
                modelSizeBytes = modelFile.length(),
                loadDurationMillis = loadDuration
            )
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            state = EngineState.ERROR
            Result.failure(e)
        }
    }

    override suspend fun unloadModel() = withContext(Dispatchers.IO) {
        val native = nativeAccess
        if (!native.isAvailable()) {
            currentModelFile = null
            state = EngineState.UNINITIALIZED
            return@withContext
        }
        try {
            native.unload()
            currentModelFile = null
            state = EngineState.UNINITIALIZED
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Caller cancellation must not be reported as a cleanup failure; native teardown
            // continues in its own scope and keeps ownership until it finishes.
            throw e
        } catch (e: Throwable) {
            // Cleanup did not complete: keep the model reference and surface the failure
            // instead of pretending the engine is uninitialized.
            state = EngineState.ERROR
            metrics = metrics.copy(modelName = currentModelFile?.name ?: metrics.modelName)
        }
    }

    override fun isModelLoaded(): Boolean {
        val ready = (state == EngineState.READY || state == EngineState.GENERATING) && currentModelFile != null
        return if (nativeAccess.isAvailable()) {
            ready && nativeAccess.isModelLoaded()
        } else {
            ready
        }
    }

    override fun generate(prompt: String, params: InferenceParams): Flow<GenerationChunk> = flow {
        isCancelled = false
        val native = nativeAccess

        if (state != EngineState.READY) {
            if (fallbackEngine != null) {
                val clean = IrisXContextBuilder.extractUserMessage(prompt)
                fallbackEngine.generate(clean, params).collect { emit(it) }
                return@flow
            }
            emit(GenerationChunk(text = "", isComplete = true, errorMessage = "El motor neuronal no está listo (modelo no cargado)."))
            return@flow
        }

        if (!native.isAvailable()) {
            // Fallback for JVM unit tests without native binary
            if (fallbackEngine != null) {
                val clean = IrisXContextBuilder.extractUserMessage(prompt)
                fallbackEngine.generate(clean, params).collect { emit(it) }
                return@flow
            }
            // Never present a simulated answer as if it came from the model.
            emit(
                GenerationChunk(
                    text = "",
                    isComplete = true,
                    errorMessage = "Biblioteca nativa no disponible: ${getNativeLoadError() ?: "sin detalle"}"
                )
            )
            return@flow
        }

        state = EngineState.GENERATING
        val startTime = System.currentTimeMillis()
        var tokenCount = 0

        try {
            val maxTok = params.maxTokens.coerceIn(16, 512)
            native.send(prompt, maxTok).collect { piece ->
                if (isCancelled) {
                    throw CancellationException("Inferencia cancelada por el usuario.")
                }

                val cleanPiece = IrisXContextBuilder.stripChatMlTokens(piece, trim = false)
                if (cleanPiece.isNotEmpty()) {
                    tokenCount++
                    val elapsed = System.currentTimeMillis() - startTime
                    emit(
                        GenerationChunk(
                            text = cleanPiece,
                            isComplete = false,
                            tokensGenerated = tokenCount,
                            elapsedMillis = elapsed
                        )
                    )
                }
            }

            if (isCancelled) {
                state = when {
                    native.isModelLoaded() -> EngineState.READY
                    currentModelFile == null -> EngineState.UNINITIALIZED
                    else -> EngineState.ERROR
                }
                emit(GenerationChunk(text = "", isComplete = true, errorMessage = "Inferencia cancelada por el usuario."))
                return@flow
            }

            val totalElapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            val tps = (tokenCount * 1000.0) / totalElapsed
            metrics = metrics.copy(
                totalTokensGenerated = tokenCount,
                tokensPerSecond = tps,
                lastInferenceDurationMillis = totalElapsed
            )
            state = when {
                native.isModelLoaded() -> EngineState.READY
                currentModelFile == null -> EngineState.UNINITIALIZED
                else -> EngineState.ERROR
            }
            emit(GenerationChunk(text = "", isComplete = true, tokensGenerated = tokenCount, elapsedMillis = totalElapsed))
        } catch (cancelled: CancellationException) {
            state = when {
                native.isModelLoaded() -> EngineState.READY
                currentModelFile == null -> EngineState.UNINITIALIZED
                else -> EngineState.ERROR
            }
            emit(GenerationChunk(text = "", isComplete = true, errorMessage = "Inferencia cancelada por el usuario."))
        } catch (e: Throwable) {
            // One failed turn must not disable a healthy native session: context overflow, a
            // rejected concurrent call or a tokenizer error leave llama.cpp fully usable.
            state = if (native.isModelLoaded()) EngineState.READY else EngineState.ERROR
            emit(GenerationChunk(text = "", isComplete = true, errorMessage = "Error en inferencia nativa llama.cpp: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        isCancelled = true
        val native = nativeAccess
        if (native.isAvailable()) {
            try {
                native.cancel()
            } catch (_: Throwable) {}
        }
        fallbackEngine?.cancel()
        // Do not publish READY here: the generation flow publishes its terminal state
        // only after native cleanup has actually completed.
    }
}
