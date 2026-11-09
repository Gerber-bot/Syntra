package com.syntra.app.domain.neural

import android.llama.cpp.LLamaAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

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
            return try {
                LLamaAndroid.instance().isNativeLibraryLoaded()
            } catch (_: Throwable) {
                false
            }
        }
    }

    override var state: EngineState = EngineState.UNINITIALIZED
        private set

    override var currentModelFile: File? = null
        private set

    override var metrics: InferenceMetrics = InferenceMetrics()
        private set

    private var isCancelled = false

    override suspend fun loadModel(modelFile: File, threads: Int): Result<Unit> = withContext(Dispatchers.IO) {
        if (!modelFile.exists()) {
            return@withContext Result.failure(IllegalArgumentException("El archivo de modelo no existe: ${modelFile.absolutePath}"))
        }

        // Validate GGUF format
        val validation = ModelManager.validateGgufFile(modelFile)
        if (validation.isFailure) {
            return@withContext Result.failure(validation.exceptionOrNull() ?: IllegalArgumentException("Archivo GGUF inválido"))
        }

        val startTime = System.currentTimeMillis()
        state = EngineState.LOADING

        if (!isNativeAvailable()) {
            // Native lib not loaded (e.g. host JVM test environment)
            state = EngineState.READY
            currentModelFile = modelFile
            metrics = metrics.copy(
                modelName = modelFile.name,
                modelSizeBytes = modelFile.length(),
                loadDurationMillis = System.currentTimeMillis() - startTime
            )
            return@withContext Result.success(Unit)
        }

        try {
            LLamaAndroid.instance().nlen = 256
            LLamaAndroid.instance().load(modelFile.absolutePath)
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
        if (isNativeAvailable()) {
            try {
                LLamaAndroid.instance().unload()
            } catch (_: Throwable) {}
        }
        currentModelFile = null
        state = EngineState.UNINITIALIZED
    }

    override fun isModelLoaded(): Boolean {
        val ready = state == EngineState.READY && currentModelFile != null
        return if (isNativeAvailable()) {
            ready && LLamaAndroid.instance().hasModelLoaded()
        } else {
            ready
        }
    }

    override fun generate(prompt: String, params: InferenceParams): Flow<GenerationChunk> = flow {
        isCancelled = false

        if (state != EngineState.READY) {
            if (fallbackEngine != null) {
                val clean = IrisXContextBuilder.extractUserMessage(prompt)
                fallbackEngine.generate(clean, params).collect { emit(it) }
                return@flow
            }
            emit(GenerationChunk(text = "", isComplete = true, errorMessage = "El motor neuronal no está listo (modelo no cargado)."))
            return@flow
        }

        if (!isNativeAvailable()) {
            // Fallback for JVM unit tests without native binary
            if (fallbackEngine != null) {
                val clean = IrisXContextBuilder.extractUserMessage(prompt)
                fallbackEngine.generate(clean, params).collect { emit(it) }
                return@flow
            }
            emit(GenerationChunk(text = "IrisX: [Simulación JVM host — Biblioteca nativa no compilada]. Consulta: ${IrisXContextBuilder.extractUserMessage(prompt)}", isComplete = true))
            return@flow
        }

        state = EngineState.GENERATING
        val startTime = System.currentTimeMillis()
        var tokenCount = 0

        try {
            LLamaAndroid.instance().nlen = params.maxTokens.coerceIn(16, 512)
            LLamaAndroid.instance().send(prompt, formatChat = true).collect { piece ->
                if (isCancelled) {
                    emit(GenerationChunk(text = "", isComplete = true, errorMessage = "Inferencia cancelada por el usuario."))
                    state = EngineState.READY
                    return@collect
                }

                val cleanPiece = IrisXContextBuilder.stripChatMlTokens(piece)
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

            val totalElapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            val tps = (tokenCount * 1000.0) / totalElapsed
            metrics = metrics.copy(
                totalTokensGenerated = tokenCount,
                tokensPerSecond = tps,
                lastInferenceDurationMillis = totalElapsed
            )
            emit(GenerationChunk(text = "", isComplete = true, tokensGenerated = tokenCount, elapsedMillis = totalElapsed))
            state = EngineState.READY
        } catch (e: Throwable) {
            state = EngineState.ERROR
            emit(GenerationChunk(text = "", isComplete = true, errorMessage = "Error en inferencia nativa llama.cpp: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        isCancelled = true
        fallbackEngine?.cancel()
        if (state == EngineState.GENERATING) {
            state = EngineState.READY
        }
    }
}
