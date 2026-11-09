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

    @Test
    fun `llamaCpp engine unloads model and resets state cleanly`() = runBlocking {
        val engine = LlamaCppInferenceEngine()
        val validFile = tempFolder.newFile("Qwen3-0.6B-Instruct-Q4_K_M.gguf")
        FileOutputStream(validFile).use { out ->
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0x46554747)
            buffer.putInt(3)
            out.write(buffer.array())
        }

        val loadResult = engine.loadModel(validFile)
        assertTrue(loadResult.isSuccess)
        assertTrue(engine.isModelLoaded())
        assertEquals(EngineState.READY, engine.state)
        assertEquals("Qwen3-0.6B-Instruct-Q4_K_M.gguf", engine.currentModelFile?.name)

        engine.unloadModel()
        assertFalse(engine.isModelLoaded())
        assertEquals(EngineState.UNINITIALIZED, engine.state)
        assertNull(engine.currentModelFile)
    }

    @Test
    fun `qwen3 unsloth gguf header validation and model identification`() {
        val qwenFile = tempFolder.newFile("unsloth_qwen3_0.6b_q4_k_m.gguf")
        FileOutputStream(qwenFile).use { out ->
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0x46554747) // GGUF
            buffer.putInt(3)          // v3
            out.write(buffer.array())
            out.write(ByteArray(1024))
        }

        val validation = ModelManager.validateGgufFile(qwenFile)
        assertTrue(validation.isSuccess)
        assertEquals(3, validation.getOrNull())
        assertTrue(qwenFile.name.contains("qwen3", ignoreCase = true))
        assertTrue(qwenFile.name.contains("0.6b", ignoreCase = true))
    }

    @Test
    fun `extractUserMessage strips chatml delimiters and isolates human input`() {
        val chatMl = """
            <|im_start|>system
            Sos IrisX, la entrenadora deportiva...
            <|im_end|>
            <|im_start|>user
            ¿Cuál es mi récord en press militar?
            <|im_end|>
            <|im_start|>assistant
        """.trimIndent()

        val extracted = IrisXContextBuilder.extractUserMessage(chatMl)
        assertEquals("¿Cuál es mi récord en press militar?", extracted)
    }

    @Test
    fun `stripChatMlTokens removes special markers cleanly`() {
        val raw = "<|im_start|>assistant\n¡Excelente progreso!<|im_end|><|endoftext|>"
        val clean = IrisXContextBuilder.stripChatMlTokens(raw)
        assertEquals("assistant\n¡Excelente progreso!", clean)
    }

    @Test
    fun `deterministic engine sanitizes chatml formatted prompts gracefully`() = runBlocking {
        val engine = DeterministicInferenceEngine(db = null)
        val promptWithChatMl = "<|im_start|>system\nSos IrisX\n<|im_end|>\n<|im_start|>user\n¿Cómo descansé?\n<|im_end|>\n<|im_start|>assistant"
        val chunks = engine.generate(promptWithChatMl).toList()

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.last().isComplete)
        assertFalse(chunks.any { it.text.contains("<|im_start|>") })
    }
}
