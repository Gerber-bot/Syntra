package com.syntra.app.domain.neural

import android.llama.cpp.LLamaAndroid
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IrisXNeuralStageB1Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `stripChatMlTokens with trim false preserves leading and trailing spaces for streaming tokens`() {
        // Individual streaming token pieces with leading whitespace must NOT be trimmed
        val tokenWithLeadingSpace = " hola"
        val clean1 = IrisXContextBuilder.stripChatMlTokens(tokenWithLeadingSpace, trim = false)
        assertEquals(" hola", clean1)

        val tokenWithTrailingSpace = "mundo "
        val clean2 = IrisXContextBuilder.stripChatMlTokens(tokenWithTrailingSpace, trim = false)
        assertEquals("mundo ", clean2)

        // Tokens with ChatML special tags should still strip the tags while preserving whitespace around tokens
        val rawTag = "<|im_start|>assistant\n ¡Vamos con todo! <|im_end|>"
        val clean3 = IrisXContextBuilder.stripChatMlTokens(rawTag, trim = false)
        assertEquals("assistant\n ¡Vamos con todo! ", clean3)

        // When trim is true (for full blocks), whitespace at ends is removed
        val cleanTrimmed = IrisXContextBuilder.stripChatMlTokens(rawTag, trim = true)
        assertEquals("assistant\n ¡Vamos con todo!", cleanTrimmed)
    }

    @Test
    fun `llama android instance throws IllegalStateException when sending without loaded model`() = runBlocking {
        val llama = LLamaAndroid.instance()
        assertFalse(llama.hasModelLoaded())

        var caughtException: Throwable? = null
        try {
            llama.send("Hola IrisX").toList()
        } catch (t: Throwable) {
            caughtException = t
        }

        assertNotNull("Debe lanzar excepción si se invoca send() sin modelo cargado", caughtException)
        assertTrue(caughtException is IllegalStateException)
        assertEquals("No model loaded", caughtException?.message)
    }

    @Test
    fun `llama cpp inference engine reports uninitialized state when model is missing`() = runBlocking {
        val engine = LlamaCppInferenceEngine()
        val nonExistent = File(tempFolder.root, "inexistente.gguf")

        val result = engine.loadModel(nonExistent)
        assertTrue(result.isFailure)
        assertEquals(EngineState.UNINITIALIZED, engine.state)
        assertFalse(engine.isModelLoaded())

        val chunks = engine.generate("Hola").toList()
        assertTrue(chunks.isNotEmpty())
        val last = chunks.last()
        assertTrue(last.isComplete)
        assertTrue(last.errorMessage?.contains("no está listo") == true)
    }

    @Test
    fun `cancellation on generating engine does not emit duplicate completion chunks`() = runBlocking {
        val fallback = DeterministicInferenceEngine(db = null)
        val engine = LlamaCppInferenceEngine(fallbackEngine = fallback)

        val dummyModel = tempFolder.newFile("test_qwen.gguf")
        FileOutputStream(dummyModel).use { out ->
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0x46554747)
            buffer.putInt(3)
            out.write(buffer.array())
        }

        val loadResult = engine.loadModel(dummyModel)
        assertTrue(loadResult.isSuccess)
        assertTrue(engine.isModelLoaded())

        val collected = mutableListOf<GenerationChunk>()
        launch {
            engine.generate("Texto largo para cancelación en caliente").collect { chunk ->
                collected.add(chunk)
                engine.cancel()
            }
        }.join()

        // Verify only 1 complete chunk was emitted
        val completeChunks = collected.filter { it.isComplete }
        assertEquals(1, completeChunks.size)
        assertTrue(completeChunks.first().errorMessage?.contains("cancelada") == true)
        assertEquals(EngineState.READY, engine.state)
    }

    @Test
    fun `engine can recover and generate again after cancellation`() = runBlocking {
        val fallback = DeterministicInferenceEngine(db = null)
        val engine = LlamaCppInferenceEngine(fallbackEngine = fallback)

        val dummyModel = tempFolder.newFile("test_recover.gguf")
        FileOutputStream(dummyModel).use { out ->
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0x46554747)
            buffer.putInt(3)
            out.write(buffer.array())
        }
        engine.loadModel(dummyModel)

        // Cancel first generation
        launch {
            engine.generate("Primera consulta").collect {
                engine.cancel()
            }
        }.join()

        assertEquals(EngineState.READY, engine.state)

        // Second generation must succeed cleanly
        val secondRun = engine.generate("Segunda consulta limpia").toList()
        assertTrue(secondRun.isNotEmpty())
        val lastChunk = secondRun.last()
        assertTrue(lastChunk.isComplete)
        assertNull(lastChunk.errorMessage)
        assertEquals(EngineState.READY, engine.state)
    }

    @Test
    fun `utf8 character reconstruction retains multi-byte symbols like spanish accents and emojis`() {
        // Test that text with accents and symbols is preserved without being mangled by token filters
        val sportsText = "¡Excelente serie en sentadillas con 100 kg! 🏋️‍♂️ RPE 8.5 — Seguí así."
        val cleaned = IrisXContextBuilder.stripChatMlTokens(sportsText, trim = false)
        assertEquals(sportsText, cleaned)
    }
}
