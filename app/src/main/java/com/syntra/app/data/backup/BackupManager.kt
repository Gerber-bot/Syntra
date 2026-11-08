package com.syntra.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.ProfileEntity
import com.syntra.app.data.WeighInEntity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class BackupManager(private val db: AppDatabase) {

    suspend fun createBackupPayload(): SyntraBackupPayload {
        val profileEntity = db.profileDao().get()
        val profileDto = profileEntity?.let { p ->
            ProfileBackupDto(
                name = p.name,
                preferredName = p.preferredName,
                heightCm = p.heightCm,
                initialWeightGrams = p.initialWeightGrams,
                activityLevel = p.activityLevel,
                goal = p.goal,
                createdAtMillis = p.createdAtMillis,
                updatedAtMillis = p.updatedAtMillis
            )
        }

        val weighIns = db.weighInDao().getAll().map { w ->
            WeighInBackupDto(
                id = w.id,
                measuredAtMillis = w.measuredAtMillis,
                weightGrams = w.weightGrams,
                note = w.note
            )
        }

        return SyntraBackupPayload(
            formatVersion = BackupSerializer.CURRENT_FORMAT_VERSION,
            appVersion = "0.2.0",
            exportedAtMillis = System.currentTimeMillis(),
            profile = profileDto,
            weighIns = weighIns
        )
    }

    suspend fun exportToFile(context: Context, uri: Uri): Result<SyntraBackupPayload> = runCatching {
        val payload = createBackupPayload()
        val jsonString = BackupSerializer.serialize(payload)
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }
        } ?: throw IllegalStateException("No se pudo abrir el destino para escribir el respaldo.")
        payload
    }

    fun readAndValidateFromUri(context: Context, uri: Uri): BackupValidationResult {
        return try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } ?: return BackupValidationResult.Error("No se pudo leer el archivo seleccionado.")
            BackupSerializer.deserializeAndValidate(jsonString)
        } catch (e: Exception) {
            BackupValidationResult.Error("Error al acceder al archivo: ${e.message}")
        }
    }

    suspend fun restorePayload(payload: SyntraBackupPayload): Result<Unit> = runCatching {
        db.withTransaction {
            if (payload.profile != null) {
                db.profileDao().deleteAll()
                db.profileDao().save(
                    ProfileEntity(
                        id = 1,
                        name = payload.profile.name,
                        preferredName = payload.profile.preferredName,
                        heightCm = payload.profile.heightCm,
                        initialWeightGrams = payload.profile.initialWeightGrams,
                        activityLevel = payload.profile.activityLevel,
                        goal = payload.profile.goal,
                        createdAtMillis = payload.profile.createdAtMillis,
                        updatedAtMillis = payload.profile.updatedAtMillis
                    )
                )
            }
            db.weighInDao().deleteAll()
            if (payload.weighIns.isNotEmpty()) {
                db.weighInDao().insertAll(
                    payload.weighIns.map { w ->
                        WeighInEntity(
                            id = w.id,
                            measuredAtMillis = w.measuredAtMillis,
                            weightGrams = w.weightGrams,
                            note = w.note
                        )
                    }
                )
            }
        }
    }
}
