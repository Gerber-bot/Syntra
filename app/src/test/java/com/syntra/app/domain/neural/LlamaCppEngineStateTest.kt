package com.syntra.app.domain.neural

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Regression tests for the failure semantics of [LlamaCppInferenceEngine].
 *
 * They run on the host JVM against [NeuralNativeAccess], which mirrors the real
 * [android.llama.cpp.LLamaAndroid] contract (a load over a live session is rejected).
 * No native library and no device are needed.
 */
class LlamaCppEngineStateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeNative(
        var available: Boolean = true,
        var modelLoaded: Boolean = false
    ) : NeuralNativeAccess {

        var loadCalls = 0
        var unloadCalls = 0
        var cancelCalls = 0
        var sendFailure: Throwable? = null
        var sessionLostOnFailure = false
        var pieces: List<String> = listOf("Hola")

        override fun isAvailable(): Boolean = available
        override fun isModelLoaded(): Boolean = modelLoaded

        override suspend fun load(path: String) {
            // Mirrors LLamaAndroid: loading on top of a live session is an error.
            if (modelLoaded) throw IllegalStateException("Cannot load model: engine is not Idle")
            loadCalls++
            modelLoaded = true
        }

        override suspend fun unload() {
            unloadCalls++
            modelLoaded = false
        }

        override fun send(prompt: String, maxTokens: Int): Flow<String> = flow {
            sendFailure?.let { failure ->
                if (sessionLostOnFailure) modelLoaded = false
                throw failure
            }
            pieces.forEach { emit(it) }
        }

        override fun cancel() {
            cancelCalls++
        }
    }

    private fun validGguf(name: String = "model.gguf"): File {
        val file = tempFolder.newFile(name)
        FileOutputStream(file).use { out ->
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0x46554747)
            buffer.putInt(3)
            out.write(buffer.array())
        }
        return file
    }

    private suspend fun readyEngine(native: FakeNative): LlamaCppInferenceEngine {
        val engine = LlamaCppInferenceEngine()
        engine.nativeAccess = native
        val result = engine.loadModel(validGguf())
        assertTrue("loadModel failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(EngineState.READY, engine.state)
        return engine
    }

    @Test
    fun `a failed native turn keeps the engine usable instead of poisoning it`() = runBlocking {
        val native = FakeNative()
        val engine = readyEngine(native)

        native.sendFailure = IllegalStateException("completion_init failed with error code -1")
        val failed = engine.generate("hola").toList()

        assertNotNull("The failure must reach Kotlin as an error chunk", failed.last().errorMessage)
        assertEquals("A failed turn must not disable a healthy session", EngineState.READY, engine.state)
        assertTrue("The engine must still report the model as loaded", engine.isModelLoaded())

        // Next turn must still go through the native session.
        native.sendFailure = null
        val recovered = engine.generate("hola").toList()
        assertNull(recovered.last().errorMessage)
        assertEquals("Hola", recovered.joinToString("") { it.text })
    }

    @Test
    fun `a lost native session is reported as a fatal engine error`() = runBlocking {
        val native = FakeNative()
        val engine = readyEngine(native)

        native.sessionLostOnFailure = true
        native.sendFailure = IllegalStateException("No model loaded (current state: Idle)")
        val failed = engine.generate("hola").toList()

        assertNotNull(failed.last().errorMessage)
        assertEquals(EngineState.ERROR, engine.state)
        assertFalse(engine.isModelLoaded())
    }

    @Test
    fun `a fatal error is recovered by reloading the same model`() = runBlocking {
        // This is the contract the console relies on to self-heal after losing the session.
        val native = FakeNative()
        val engine = readyEngine(native)

        native.sessionLostOnFailure = true
        native.sendFailure = IllegalStateException("No model loaded (current state: Idle)")
        engine.generate("hola").toList()
        assertEquals(EngineState.ERROR, engine.state)

        native.sessionLostOnFailure = false
        native.sendFailure = null
        val modelFile = requireNotNull(engine.currentModelFile)
        val reload = engine.loadModel(modelFile)

        assertTrue("Recovery must reload: ${reload.exceptionOrNull()?.message}", reload.isSuccess)
        assertEquals(EngineState.READY, engine.state)
        assertTrue(engine.isModelLoaded())

        val recovered = engine.generate("hola").toList()
        assertNull(recovered.last().errorMessage)
        assertEquals("Hola", recovered.joinToString("") { it.text })
    }

    @Test
    fun `loadModel releases a stale native session before loading`() = runBlocking {
        // A leftover session exists but this engine instance does not own it as READY.
        val native = FakeNative(modelLoaded = true)
        val engine = LlamaCppInferenceEngine()
        engine.nativeAccess = native

        val result = engine.loadModel(validGguf())

        assertTrue("A stale session must not block the load: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals("The stale session must be released first", 1, native.unloadCalls)
        assertEquals(1, native.loadCalls)
        assertEquals(EngineState.READY, engine.state)
    }

    @Test
    fun `rejecting an invalid model does not destroy the loaded session`() = runBlocking {
        val native = FakeNative()
        val engine = readyEngine(native)

        val invalid = tempFolder.newFile("invalid.gguf")
        invalid.writeBytes("NOT_GGUF".toByteArray())

        val result = engine.loadModel(invalid)

        assertTrue(result.isFailure)
        assertEquals("A rejected file must not unload the working model", EngineState.READY, engine.state)
        assertTrue(engine.isModelLoaded())
        assertEquals(0, native.unloadCalls)
    }

    @Test
    fun `missing native library reports an explicit error instead of simulated text`() = runBlocking {
        val native = FakeNative(available = false)
        val engine = LlamaCppInferenceEngine()
        engine.nativeAccess = native

        assertTrue(engine.loadModel(validGguf()).isSuccess)
        val chunks = engine.generate("hola").toList()

        val text = chunks.joinToString("") { it.text }
        assertTrue("No simulated answer may be shown to the user: $text", text.isBlank())
        assertTrue(
            "The reason must be explicit: ${chunks.last().errorMessage}",
            chunks.last().errorMessage?.contains("Biblioteca nativa") == true
        )
    }

    @Test
    fun `deterministic fallback still streams when the native library is unavailable`() = runBlocking {
        val fallback = DeterministicInferenceEngine(db = null)
        val engine = LlamaCppInferenceEngine(fallbackEngine = fallback)
        engine.nativeAccess = FakeNative(available = false)

        assertTrue(engine.loadModel(validGguf()).isSuccess)
        val chunks = engine.generate("¿Cómo planifico mi press de banca?").toList()

        assertTrue(chunks.joinToString("") { it.text }.isNotBlank())
        assertTrue(chunks.last().isComplete)
    }

    @Test
    fun `cancel is forwarded to the native session`() = runBlocking {
        val native = FakeNative()
        val engine = readyEngine(native)

        engine.cancel()

        assertEquals(1, native.cancelCalls)
    }

    @Test
    fun `generate without a loaded model fails cleanly`() = runBlocking {
        val engine = LlamaCppInferenceEngine()
        engine.nativeAccess = FakeNative()

        val chunks = engine.generate("hola").toList()

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().isComplete)
        assertNotNull(chunks.single().errorMessage)
    }
}
