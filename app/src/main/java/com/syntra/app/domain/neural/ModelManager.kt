package com.syntra.app.domain.neural

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GgufModelInfo(
    val fileName: String,
    val file: File,
    val sizeBytes: Long,
    val ggufVersion: Int,
    val isActive: Boolean = false
)

sealed class ModelImportResult {
    data class Success(val modelInfo: GgufModelInfo) : ModelImportResult()
    data class Error(val message: String) : ModelImportResult()
}

object ModelManager {

    // Magic number for GGUF format in little endian ('G' 'G' 'U' 'F' -> 0x46554747)
    private const val GGUF_MAGIC = 0x46554747
    private const val PREFS_NAME = "syntra_preferences"
    private const val KEY_ACTIVE_MODEL = "active_neural_model_file"

    fun getActiveModelPreference(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_MODEL, null)
    }

    fun setActiveModelPreference(context: Context, modelFileName: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (modelFileName != null) {
                putString(KEY_ACTIVE_MODEL, modelFileName)
            } else {
                remove(KEY_ACTIVE_MODEL)
            }
        }.apply()
    }

    fun getModelsDirectory(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Inspects the first 8 bytes of a file to check GGUF magic and format version.
     */
    fun validateGgufFile(file: File): Result<Int> {
        if (!file.exists() || file.length() < 8) {
            return Result.failure(IllegalArgumentException("El archivo no existe o es demasiado pequeño para ser GGUF."))
        }

        FileInputStream(file).use { input ->
            val header = ByteArray(8)
            val read = input.read(header)
            if (read < 8) {
                return Result.failure(IllegalArgumentException("No se pudieron leer los primeros 8 bytes de cabecera."))
            }

            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.int
            val version = buffer.int

            if (magic != GGUF_MAGIC) {
                return Result.failure(IllegalArgumentException("Formato inválido: cabecera no coincide con GGUF (magic=0x${Integer.toHexString(magic)})."))
            }

            if (version < 1 || version > 4) {
                return Result.failure(IllegalArgumentException("Versión GGUF no soportada: v$version."))
            }

            return Result.success(version)
        }
    }

    suspend fun importModelFromUri(
        context: Context,
        uri: Uri,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ModelImportResult = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val cursor = resolver.query(uri, null, null, null, null)
            val displayName = cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) it.getString(nameIndex) else null
                } else null
            } ?: "model_${System.currentTimeMillis()}.gguf"

            val cleanName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetDir = getModelsDirectory(context)
            val targetFile = File(targetDir, cleanName)

            // Temporary file during copy to avoid incomplete model corruption
            val tempFile = File(targetDir, "$cleanName.tmp_${System.currentTimeMillis()}")

            val inputStream = resolver.openInputStream(uri)
                ?: return@withContext ModelImportResult.Error("No se pudo abrir el archivo seleccionado.")

            val totalBytes = targetDir.usableSpace
            var copiedBytes = 0L

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024) // 64 KB chunk
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                        onProgress(copiedBytes, -1L)
                    }
                    output.flush()
                }
            }

            // Validate GGUF header before confirming
            val validation = validateGgufFile(tempFile)
            if (validation.isFailure) {
                tempFile.delete()
                return@withContext ModelImportResult.Error(
                    validation.exceptionOrNull()?.message ?: "El archivo no es un modelo GGUF válido."
                )
            }

            // Atomic rename
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val version = validation.getOrThrow()
            ModelImportResult.Success(
                GgufModelInfo(
                    fileName = targetFile.name,
                    file = targetFile,
                    sizeBytes = targetFile.length(),
                    ggufVersion = version,
                    isActive = true
                )
            )
        } catch (e: Exception) {
            ModelImportResult.Error("Error al importar modelo: ${e.message}")
        }
    }

    fun listInstalledModels(context: Context, activeModelName: String? = null): List<GgufModelInfo> {
        val dir = getModelsDirectory(context)
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) } ?: return emptyList()

        return files.mapNotNull { file ->
            val validation = validateGgufFile(file)
            if (validation.isSuccess) {
                GgufModelInfo(
                    fileName = file.name,
                    file = file,
                    sizeBytes = file.length(),
                    ggufVersion = validation.getOrNull() ?: 3,
                    isActive = file.name == activeModelName
                )
            } else {
                null
            }
        }.sortedByDescending { it.file.lastModified() }
    }

    fun deleteModel(file: File): Boolean {
        return try {
            if (file.exists()) file.delete() else false
        } catch (_: Exception) {
            false
        }
    }
}
