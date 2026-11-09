package com.syntra.app.domain.neural

import com.syntra.app.data.AppDatabase
import com.syntra.app.domain.irisx.IrisXCore
import com.syntra.app.domain.irisx.IrisXResponse
import com.syntra.app.domain.irisx.RoomIrisXRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Deterministic fallback engine implementing [NeuralInferenceEngine].
 * Used when no GGUF model is loaded or when device resources are restricted.
 * Keeps IrisX 100% operational without regression.
 */
class DeterministicInferenceEngine(
    private val db: AppDatabase? = null
) : NeuralInferenceEngine {

    override var state: EngineState = EngineState.READY
        private set

    override val currentModelFile: File? = null

    override var metrics: InferenceMetrics = InferenceMetrics(
        modelName = "IrisX Deterministic Rule-Engine",
        modelSizeBytes = 0L
    )
        private set

    private var isCancelled = false

    override suspend fun loadModel(modelFile: File, threads: Int): Result<Unit> {
        // Deterministic engine is always ready; accepts no-op model load
        state = EngineState.READY
        return Result.success(Unit)
    }

    override suspend fun unloadModel() {
        state = EngineState.READY
    }

    override fun isModelLoaded(): Boolean = false

    override fun generate(prompt: String, params: InferenceParams): Flow<GenerationChunk> = flow {
        isCancelled = false
        state = EngineState.GENERATING
        val startTime = System.currentTimeMillis()

        // 1. Evaluate prompt via IrisXCore using clean user query (strip any ChatML template wrappers)
        val cleanQuery = IrisXContextBuilder.extractUserMessage(prompt)
        val responseText = if (db != null) {
            try {
                val core = IrisXCore(RoomIrisXRepository(db))
                val eval = core.ask(cleanQuery)
                when (eval) {
                    is IrisXResponse.Success -> eval.message
                    is IrisXResponse.RequiresConfirmation -> "${eval.question}\nConfirmar con: ${eval.confirmCommand}"
                    is IrisXResponse.Unrecognized -> eval.message
                }
            } catch (e: Exception) {
                "IrisX [Motor determinista]: Error al procesar consulta: ${e.message}"
            }
        } else {
            "IrisX [Motor determinista]: Recibí tu consulta. Para respuestas neuronales conversacionales avanzadas, podés cargar un modelo GGUF (ej. Qwen3-0.6B) desde la configuración."
        }

        // 2. Stream tokens progressively to mimic streaming UX
        val words = responseText.split(" ")
        val totalWords = words.size
        var emittedWords = 0
        val buffer = StringBuilder()

        for (word in words) {
            if (isCancelled) {
                emit(GenerationChunk(text = "", isComplete = true, errorMessage = "Inferencia cancelada por el usuario."))
                state = EngineState.READY
                return@flow
            }

            buffer.append(word).append(" ")
            emittedWords++
            val elapsed = System.currentTimeMillis() - startTime

            emit(
                GenerationChunk(
                    text = word + " ",
                    isComplete = emittedWords == totalWords,
                    tokensGenerated = emittedWords,
                    elapsedMillis = elapsed
                )
            )

            // Small progressive typing delay (35ms per word)
            delay(35L)
        }

        val totalDuration = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
        val tps = (emittedWords * 1000.0) / totalDuration

        metrics = metrics.copy(
            totalTokensGenerated = emittedWords,
            tokensPerSecond = tps,
            lastInferenceDurationMillis = totalDuration
        )

        state = EngineState.READY
    }

    override fun cancel() {
        isCancelled = true
        state = EngineState.READY
    }
}
