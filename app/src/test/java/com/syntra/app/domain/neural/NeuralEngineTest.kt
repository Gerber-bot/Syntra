package com.syntra.app.domain.neural

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NeuralEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `validateGgufFile accepts valid GGUF v3 header`() {
        val validFile = tempFolder.newFile("test_qwen.gguf")
        FileOutputStream(validFile).use { out ->
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0x46554747) // 'G' 'G' 'U' 'F'
            buffer.putInt(3)          // version 3
            out.write(buffer.array())
            out.write(ByteArray(64))  // dummy payload
        }

        val result = ModelManager.validateGgufFile(validFile)
        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrNull())
    }

    @Test
    fun `validateGgufFile rejects invalid magic header`() {
        val corruptFile = tempFolder.newFile("corrupt.gguf")
        FileOutputStream(corruptFile).use { out ->
            out.write("NOT_GGUF_HEADER".toByteArray())
        }

        val result = ModelManager.validateGgufFile(corruptFile)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("GGUF") == true)
    }

    @Test
    fun `validateGgufFile rejects truncated files under 8 bytes`() {
        val tinyFile = tempFolder.newFile("tiny.gguf")
        FileOutputStream(tinyFile).use { out ->
            out.write(byteArrayOf(0x47, 0x47))
        }

        val result = ModelManager.validateGgufFile(tinyFile)
        assertTrue(result.isFailure)
    }

    @Test
    fun `deterministic engine streams progressive chunks and completes`() = runBlocking {
        val engine = DeterministicInferenceEngine(db = null)
        val chunks = engine.generate("¿Cómo planifico mi press de banca?").toList()

        assertTrue(chunks.isNotEmpty())
        val lastChunk = chunks.last()
        assertTrue(lastChunk.isComplete)
        assertTrue(lastChunk.tokensGenerated > 0)
        assertTrue(engine.metrics.totalTokensGenerated > 0)
        assertTrue(engine.metrics.tokensPerSecond > 0.0)
    }

    @Test
    fun `deterministic engine cancels cleanly when requested`() = runBlocking {
        val engine = DeterministicInferenceEngine(db = null)
        val collected = mutableListOf<GenerationChunk>()
        launch {
            engine.generate("Texto largo con varias palabras para probar cancelacion en flujo continuo").collect { chunk ->
                collected.add(chunk)
                engine.cancel()
            }
        }.join()

        assertTrue(collected.isNotEmpty())
        assertTrue(collected.any { it.errorMessage?.contains("cancelada") == true })
    }

    @Test
    fun `guardian constants and thresholds are within safe battery and memory margins`() {
        assertTrue(IrisXGuardian.MIN_FREE_MEMORY_BYTES >= 500L * 1024L * 1024L)
        assertTrue(IrisXGuardian.MIN_BATTERY_PERCENTAGE >= 10)
    }

    @Test
    fun `llamaCpp engine degrades gracefully to fallback when native lib is absent on host JVM`() = runBlocking {
        val fallback = DeterministicInferenceEngine(db = null)
        val engine = LlamaCppInferenceEngine(fallbackEngine = fallback)

        // Native lib is not present on host JVM
        assertFalse(LlamaCppInferenceEngine.isNativeAvailable())

        // Create a valid GGUF dummy
        val validFile = tempFolder.newFile("dummy_model.gguf")
        FileOutputStream(validFile).use { out ->
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0x46554747)
            buffer.putInt(3)
            out.write(buffer.array())
        }

        val loadResult = engine.loadModel(validFile)
        assertTrue(loadResult.isSuccess)
        assertTrue(engine.isModelLoaded())

        val chunks = engine.generate("¿Cuánto descansé?").toList()
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.last().isComplete)
    }

    @Test
    fun `llamaCpp engine rejects nonexistent model file`() = runBlocking {
        val engine = LlamaCppInferenceEngine()
        val nonExistent = tempFolder.root.resolve("missing.gguf")

        val result = engine.loadModel(nonExistent)
        assertTrue(result.isFailure)
        assertFalse(engine.isModelLoaded())
    }
}
