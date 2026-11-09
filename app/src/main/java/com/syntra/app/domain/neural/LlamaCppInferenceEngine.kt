package com.syntra.app.domain.neural

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

fun interface NativeTokenCallback {
    fun onToken(token: String, isFinished: Boolean)
}

/**
 * Native llama.cpp adapter implementing [NeuralInferenceEngine].
 * Bridges Kotlin coroutines with native C++ JNI inference (`libllama-android.so`).
 * Gracefully degrades if native libraries are missing on the target host/JVM.
 */
class LlamaCppInferenceEngine(
    private val fallbackEngine: NeuralInferenceEngine? = null
) : NeuralInferenceEngine {

    companion object {
        @Volatile
        private var isNativeLibraryLoaded = false

        init {
            try {
                System.loadLibrary("llama-android")
                isNativeLibraryLoaded = true
            } catch (_: Throwable) {
                isNativeLibraryLoaded = false
            }
        }

        fun isNativeAvailable(): Boolean = isNativeLibraryLoaded
    }

    override var state: EngineState = EngineState.UNINITIALIZED
        private set

    override var currentModelFile: File? = null
        private set

    override var metrics: InferenceMetrics = InferenceMetrics()
        private set

    private var nativeContextPtr: Long = 0L

    override suspend fun loadModel(modelFile: File, threads: Int): Result<Unit> = withContext(Dispatchers.IO) {
        if (!modelFile.exists()) {
            return@withContext Result.failure(IllegalArgumentException("El archivo de modelo no existe: ${modelFile.absolutePath}"))
        }

        // Validate GGUF format
        val validation = ModelManager.validateGgufFile(modelFile)
        if (validation.isFailure) {
            return@withContext Result.failure(validation.exceptionOrNull() ?: IllegalArgumentException("Archivo GGUF inválido"))
        }

        if (!isNativeLibraryLoaded) {
            // Native lib not loaded (e.g. standard JVM test environment)
            state = EngineState.READY
            currentModelFile = modelFile
            metrics = metrics.copy(
                modelName = modelFile.name,
                modelSizeBytes = modelFile.length(),
                loadDurationMillis = 0L
            )
            return@withContext Result.success(Unit)
        }

        state = EngineState.LOADING
        val startTime = System.currentTimeMillis()

        try {
            if (nativeContextPtr != 0L) {
                nativeFree(nativeContextPtr)
                nativeContextPtr = 0L
            }

            val ptr = nativeInit(threads.coerceIn(1, 8))
            if (ptr == 0L) {
                state = EngineState.ERROR
                return@withContext Result.failure(IllegalStateException("No se pudo inicializar el contexto nativo de llama.cpp"))
            }

            val loaded = nativeLoadModel(ptr, modelFile.absolutePath)
            if (!loaded) {
                nativeFree(ptr)
                state = EngineState.ERROR
                return@withContext Result.failure(IllegalStateException("Fallo al cargar pesos GGUF en llama.cpp"))
            }

            nativeContextPtr = ptr
            currentModelFile = modelFile
            state = EngineState.READY

            val loadDuration = System.currentTimeMillis() - startTime
            metrics = metrics.copy(
                modelName = modelFile.name,
                modelSizeBytes = modelFile.length(),
                loadDurationMillis = loadDuration
            )

            Result.success(Unit)
        } catch (e: Throwable) {
            state = EngineState.ERROR
            Result.failure(e)
        }
    }

    override suspend fun unloadModel() = withContext(Dispatchers.IO) {
        if (nativeContextPtr != 0L && isNativeLibraryLoaded) {
            try {
                nativeFree(nativeContextPtr)
            } catch (_: Throwable) {}
            nativeContextPtr = 0L
        }
        currentModelFile = null
        state = EngineState.UNINITIALIZED
    }

    override fun isModelLoaded(): Boolean {
        return state == EngineState.READY && currentModelFile != null
    }

    override fun generate(prompt: String, params: InferenceParams): Flow<GenerationChunk> = callbackFlow {
        if (state != EngineState.READY) {
            if (fallbackEngine != null) {
                fallbackEngine.generate(prompt, params).collect { trySend(it) }
                close()
                return@callbackFlow
            }
            trySend(GenerationChunk(text = "", isComplete = true, errorMessage = "El motor neuronal no está listo (modelo no cargado)."))
            close()
            return@callbackFlow
        }

        if (!isNativeLibraryLoaded || nativeContextPtr == 0L) {
            // Fallback for environments without native binary (JVM unit tests or simulator)
            if (fallbackEngine != null) {
                fallbackEngine.generate(prompt, params).collect { trySend(it) }
                close()
                return@callbackFlow
            }
            trySend(GenerationChunk(text = "IrisX: [Inferencia simulada — Biblioteca nativa no compilada en host]. Recibido: \"$prompt\"", isComplete = true))
            close()
            return@callbackFlow
        }

        state = EngineState.GENERATING
        val startTime = System.currentTimeMillis()
        var tokenCount = 0

        val callback = NativeTokenCallback { token, isFinished ->
            tokenCount++
            val elapsed = System.currentTimeMillis() - startTime
            trySend(
                GenerationChunk(
                    text = token,
                    isComplete = isFinished,
                    tokensGenerated = tokenCount,
                    elapsedMillis = elapsed
                )
            )
            if (isFinished) {
                state = EngineState.READY
                val tps = if (elapsed > 0) (tokenCount * 1000.0) / elapsed else 0.0
                metrics = metrics.copy(
                    totalTokensGenerated = tokenCount,
                    tokensPerSecond = tps,
                    lastInferenceDurationMillis = elapsed
                )
                close()
            }
        }

        try {
            nativeGenerate(
                contextPtr = nativeContextPtr,
                prompt = prompt,
                maxTokens = params.maxTokens,
                temperature = params.temperature,
                topP = params.topP,
                callback = callback
            )
        } catch (e: Throwable) {
            state = EngineState.ERROR
            trySend(GenerationChunk(text = "", isComplete = true, errorMessage = "Error en inferencia nativa: ${e.message}"))
            close()
        }

        awaitClose {
            cancel()
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        if (isNativeLibraryLoaded && nativeContextPtr != 0L) {
            try {
                nativeCancel(nativeContextPtr)
            } catch (_: Throwable) {}
        }
        fallbackEngine?.cancel()
        if (state == EngineState.GENERATING) {
            state = EngineState.READY
        }
    }

    // Native JNI functions
    private external fun nativeInit(threads: Int): Long
    private external fun nativeLoadModel(contextPtr: Long, modelPath: String): Boolean
    private external fun nativeFree(contextPtr: Long)
    private external fun nativeCancel(contextPtr: Long)
    private external fun nativeGenerate(
        contextPtr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: NativeTokenCallback
    )
}
