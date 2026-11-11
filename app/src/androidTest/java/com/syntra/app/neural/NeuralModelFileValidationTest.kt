package com.syntra.app.neural

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syntra.app.domain.neural.EngineState
import com.syntra.app.domain.neural.LlamaCppInferenceEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * File-level validation of the neural model before any native call.
 * These scenarios do not need the ARM64 JNI library and never touch personal data:
 * every artifact is created inside the lab application's cache directory.
 */
@RunWith(AndroidJUnit4::class)
class NeuralModelFileValidationTest {

    private lateinit var context: Context
    private lateinit var engine: LlamaCppInferenceEngine
    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = LlamaCppInferenceEngine()
        NeuralLabReport.record(context, "model_file_preconditions", "INFO")
    }

    @After
    fun tearDown() {
        createdFiles.forEach { it.delete() }
    }

    private fun tempFile(name: String): File =
        File(context.cacheDir, "lab-$name-${System.nanoTime()}").also { createdFiles += it }

    private fun ggufHeader(magic: Int, version: Int): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(magic)
            .putInt(version)
            .putLong(0L)
            .array()

    @Test
    fun missing_model_file_is_rejected_without_changing_engine_state() = runBlocking {
        val missing = tempFile("missing.gguf")
        val startedAt = android.os.SystemClock.elapsedRealtime()

        val result = engine.loadModel(missing, threads = 4)

        val detail = result.exceptionOrNull()?.javaClass?.simpleName
        NeuralLabReport.record(
            context,
            "missing_model_file",
            if (result.isFailure && engine.state == EngineState.UNINITIALIZED) "PASS" else "FAIL",
            mapOf(
                "durationMillis" to NeuralLabReport.elapsedMillisSince(startedAt),
                "engineState" to engine.state.name,
                "isModelLoaded" to engine.isModelLoaded()
            ),
            detail
        )

        assertTrue("A missing file must fail", result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertEquals(EngineState.UNINITIALIZED, engine.state)
        assertFalse(engine.isModelLoaded())
    }

    @Test
    fun invalid_gguf_magic_is_rejected() = runBlocking {
        val invalid = tempFile("magic.gguf")
        invalid.writeBytes(ggufHeader(magic = 0x12345678, version = 3))

        val result = engine.loadModel(invalid, threads = 4)

        NeuralLabReport.record(
            context,
            "invalid_gguf_magic",
            if (result.isFailure) "PASS" else "FAIL",
            mapOf("engineState" to engine.state.name),
            result.exceptionOrNull()?.message
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("GGUF") == true)
        assertFalse(engine.isModelLoaded())
    }

    @Test
    fun unsupported_gguf_version_is_rejected() = runBlocking {
        val invalid = tempFile("version.gguf")
        invalid.writeBytes(ggufHeader(magic = 0x46554747, version = 99))

        val result = engine.loadModel(invalid, threads = 4)

        NeuralLabReport.record(
            context,
            "unsupported_gguf_version",
            if (result.isFailure) "PASS" else "FAIL",
            mapOf("engineState" to engine.state.name),
            result.exceptionOrNull()?.message
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Versión GGUF") == true)
    }

    @Test
    fun truncated_header_is_rejected() = runBlocking {
        val truncated = tempFile("truncated-header.gguf")
        truncated.writeBytes(byteArrayOf(0x47, 0x47, 0x55))

        val result = engine.loadModel(truncated, threads = 4)

        NeuralLabReport.record(
            context,
            "truncated_header",
            if (result.isFailure) "PASS" else "FAIL",
            mapOf("engineState" to engine.state.name),
            result.exceptionOrNull()?.message
        )

        assertTrue(result.isFailure)
        assertFalse(engine.isModelLoaded())
    }
}
