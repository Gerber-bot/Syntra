package com.syntra.app.domain.neural

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class ModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun modelDownloadSpec_hasCorrectDefaults() {
        val spec = ModelDownloadSpec()
        assertEquals("Qwen3-0.6B-Q4_K_M.gguf", spec.modelName)
        assertTrue(spec.url.startsWith("https://"))
        assertTrue(spec.url.contains("qwen2.5-0.5b-instruct-q4_k_m.gguf"))
        assertTrue(spec.minExpectedBytes > 100_000_000L)
    }

    @Test
    fun validateGgufFile_acceptsValidGgufHeader() {
        val file = tempFolder.newFile("valid_test.gguf")
        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(0x46554747) // GGUF magic
            header.putInt(3) // Version 3
            out.write(header.array())
            out.write(ByteArray(1024))
        }

        val result = ModelManager.validateGgufFile(file)
        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrNull())
    }

    @Test
    fun validateGgufFile_rejectsInvalidMagic() {
        val file = tempFolder.newFile("invalid_magic.gguf")
        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(0x12345678) // Corrupt magic
            header.putInt(3)
            out.write(header.array())
        }

        val result = ModelManager.validateGgufFile(file)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("magic") == true)
    }

    @Test
    fun validateGgufFile_rejectsTooSmallFile() {
        val file = tempFolder.newFile("empty.gguf")
        val result = ModelManager.validateGgufFile(file)
        assertTrue(result.isFailure)
    }

    @Test
    fun sha256Checksum_computesCorrectly() {
        val content = "Syntra offline-first Qwen3 verification".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content)
        val hashHex = hashBytes.joinToString("") { "%02x".format(it) }

        assertEquals(64, hashHex.length)
        // Verify deterministic reproducibility
        val digest2 = MessageDigest.getInstance("SHA-256")
        val hashHex2 = digest2.digest(content).joinToString("") { "%02x".format(it) }
        assertEquals(hashHex, hashHex2)
    }

    @Test
    fun downloadStates_representCompleteLifecycle() {
        val idle: DownloadState = DownloadState.Idle
        val checking: DownloadState = DownloadState.CheckingNetwork(isMetered = true)
        val downloading: DownloadState = DownloadState.Downloading(bytesDownloaded = 1000L, totalBytes = 2000L, progressPercent = 50)
        val validating: DownloadState = DownloadState.ValidatingIntegrity
        val success: DownloadState = DownloadState.Success(File("dummy.gguf"), sizeBytes = 2000L, ggufVersion = 3)
        val error: DownloadState = DownloadState.Error("Conexión perdida", canRetry = true)
        val cancelled: DownloadState = DownloadState.Cancelled

        assertTrue(idle is DownloadState.Idle)
        assertTrue((checking as DownloadState.CheckingNetwork).isMetered)
        assertEquals(50, (downloading as DownloadState.Downloading).progressPercent)
        assertTrue(validating is DownloadState.ValidatingIntegrity)
        assertEquals(3, (success as DownloadState.Success).ggufVersion)
        assertTrue((error as DownloadState.Error).canRetry)
        assertTrue(cancelled is DownloadState.Cancelled)
    }
}
