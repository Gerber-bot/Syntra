package com.syntra.app.domain.neural

import kotlinx.coroutines.flow.Flow
import java.io.File

data class InferenceParams(
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val threads: Int = 4,
    val stopTokens: List<String> = listOf("<|im_end|>", "<|endoftext|>", "</s>")
)

data class GenerationChunk(
    val text: String,
    val isComplete: Boolean = false,
    val tokensGenerated: Int = 0,
    val elapsedMillis: Long = 0L,
    val errorMessage: String? = null
)

data class InferenceMetrics(
    val modelName: String = "",
    val modelSizeBytes: Long = 0L,
    val loadDurationMillis: Long = 0L,
    val totalTokensGenerated: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val lastInferenceDurationMillis: Long = 0L,
    val peakMemoryBytes: Long = 0L
)

enum class EngineState {
    UNINITIALIZED,
    LOADING,
    READY,
    GENERATING,
    ERROR
}

/**
 * Clean architectural abstraction for local on-device neural inference.
 * Keeps domain logic and IrisX Core strictly decoupled from specific runtimes (llama.cpp, ONNX, etc.).
 */
interface NeuralInferenceEngine {
    val state: EngineState
    val currentModelFile: File?
    val metrics: InferenceMetrics

    suspend fun loadModel(modelFile: File, threads: Int = 4): Result<Unit>
    suspend fun unloadModel()
    fun isModelLoaded(): Boolean

    /**
     * Streams generated tokens progressively via Kotlin Flow.
     * Canceling the collecting coroutine or calling [cancel] aborts generation cleanly.
     */
    fun generate(prompt: String, params: InferenceParams = InferenceParams()): Flow<GenerationChunk>

    fun cancel()
}
