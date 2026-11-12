package com.syntra.app.domain.neural

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Targeted regression suite for IrisXGuardian memory enforcement and LlamaCppInferenceEngine
 * state transitions across GENERATING, concurrent unload, and cancellation.
 */
class NeuralAuditRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeConcurrentNative(
        var loaded: Boolean = false
    ) : NeuralNativeAccess {
        val firstTokenEmitted = CompletableDeferred<Unit>()
        val proceedAfterUnload = CompletableDeferred<Unit>()

        override fun isAvailable(): Boolean = true
        override fun isModelLoaded(): Boolean = loaded
        override suspend fun load(path: String) {
            loaded = true
        }
        override suspend fun unload() {
            loaded = false
        }
        override fun send(prompt: String, maxTokens: Int): Flow<String> = flow {
            emit("hola")
            firstTokenEmitted.complete(Unit)
            proceedAfterUnload.await()
            throw CancellationException("unloaded mid-stream")
        }
        override fun cancel() {}
    }

    private fun createDummyGgufFile(name: String): File {
        val file = tempFolder.newFile(name)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        header.putInt(3)
        FileOutputStream(file).use { it.write(header.array()) }
        return file
    }

    @Test
    fun `guardian rejects inference as critical when available ram is smaller than model file`() {
        val mb = 1024L * 1024L
        val critical = IrisXGuardian.evaluateMemoryValues(
            avail = 700L * mb, // > MIN_FREE_MEMORY_BYTES (600 MB), but < model (800 MB)
            lowMemory = false,
            modelSizeBytes = 800L * mb
        )
        assertEquals(GuardianSafetyLevel.CRITICAL, critical.safetyLevel)
        assertFalse(critical.canProceed)

        val warning = IrisXGuardian.evaluateMemoryValues(
            avail = 900L * mb, // >= 700 MB model, < 700 + 600 MB needed
            lowMemory = false,
            modelSizeBytes = 700L * mb
        )
        assertEquals(GuardianSafetyLevel.WARNING, warning.safetyLevel)
        assertTrue(warning.canProceed)

        val safe = IrisXGuardian.evaluateMemoryValues(
            avail = 1600L * mb,
            lowMemory = false,
            modelSizeBytes = 700L * mb
        )
        assertEquals(GuardianSafetyLevel.SAFE, safe.safetyLevel)
        assertTrue(safe.canProceed)
    }

    @Test
    fun `engine reports loaded while generating and never becomes ready if unloaded before cancellation`() = runBlocking {
        val native = FakeConcurrentNative()
        val engine = LlamaCppInferenceEngine(fallbackEngine = null).apply {
            nativeAccess = native
        }
        val model = createDummyGgufFile("qwen3-audit.gguf")
        assertTrue(engine.loadModel(model).isSuccess)
        assertTrue(engine.isModelLoaded())

        var loadedDuringGeneration = false
        val chunks = mutableListOf<GenerationChunk>()

        val genJob = launch {
            engine.generate("Hola", InferenceParams()).toList(chunks)
        }

        native.firstTokenEmitted.await()
        assertEquals(EngineState.GENERATING, engine.state)
        loadedDuringGeneration = engine.isModelLoaded()

        engine.unloadModel()
        native.proceedAfterUnload.complete(Unit)
        genJob.join()

        assertTrue("isModelLoaded() must remain true while actively generating", loadedDuringGeneration)
        assertFalse("State must not become READY after native unload during cancellation", engine.state == EngineState.READY)
        assertEquals(EngineState.UNINITIALIZED, engine.state)
        assertFalse(engine.isModelLoaded())
        assertTrue(chunks.any { it.isComplete })
    }
}
