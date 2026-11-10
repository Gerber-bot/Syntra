package com.syntra.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.syntra.app.data.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class BackupManager(private val db: AppDatabase) {

    suspend fun createBackupPayload(): SyntraBackupPayload = db.withTransaction {
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

        val exercises = db.exerciseDao().getAll().map { e ->
            ExerciseBackupDto(
                id = e.id,
                displayName = e.displayName,
                normalizedName = e.normalizedName,
                muscleGroup = e.muscleGroup,
                secondaryMuscleGroups = e.secondaryMuscleGroups,
                equipment = e.equipment,
                exerciseType = e.exerciseType,
                status = e.status,
                createdAtMillis = e.createdAtMillis
            )
        }

        val aliases = db.exerciseAliasDao().getAll().map { a ->
            ExerciseAliasBackupDto(
                id = a.id,
                exerciseId = a.exerciseId,
                normalizedAlias = a.normalizedAlias,
                createdAtMillis = a.createdAtMillis
            )
        }

        val sessions = db.workoutSessionDao().getAll().map { s ->
            WorkoutSessionBackupDto(
                id = s.id,
                title = s.title,
                category = s.category,
                startedAtMillis = s.startedAtMillis,
                finishedAtMillis = s.finishedAtMillis,
                notes = s.notes
            )
        }

        val sets = db.workoutSetDao().getAll().map { set ->
            WorkoutSetBackupDto(
                id = set.id,
                sessionId = set.sessionId,
                exerciseId = set.exerciseId,
                orderInExercise = set.orderInExercise,
                executionOrder = set.executionOrder,
                loadGrams = set.loadGrams,
                repetitions = set.repetitions,
                rir = set.rir,
                isAssisted = set.isAssisted,
                isDumbbell = set.isDumbbell,
                note = set.note,
                plannedTarget = set.plannedTarget,
                loadUnit = set.loadUnit,
                isToFailure = set.isToFailure
            )
        }

        val memories = db.irisxMemoryDao().getAll().map { m ->
            IrisxMemoryBackupDto(
                id = m.id,
                memoryType = m.memoryType,
                sourceKey = m.sourceKey,
                targetValue = m.targetValue,
                confidence = m.confidence,
                createdAtMillis = m.createdAtMillis,
                updatedAtMillis = m.updatedAtMillis
            )
        }

        val conversations = db.irisxConversationDao().getAllConversations().map { c ->
            IrisxConversationBackupDto(
                id = c.id,
                title = c.title,
                activeMuscleGroup = c.activeMuscleGroup,
                activeExerciseName = c.activeExerciseName,
                activeSessionId = c.activeSessionId,
                activePeriodScope = c.activePeriodScope,
                createdAtMillis = c.createdAtMillis,
                updatedAtMillis = c.updatedAtMillis
            )
        }

        val messages = db.irisxConversationDao().getAllMessages().map { msg ->
            IrisxMessageBackupDto(
                id = msg.id,
                conversationId = msg.conversationId,
                senderRole = msg.senderRole,
                content = msg.content,
                intentType = msg.intentType,
                referencedExercise = msg.referencedExercise,
                referencedMuscleGroup = msg.referencedMuscleGroup,
                referencedSessionId = msg.referencedSessionId,
                createdAtMillis = msg.createdAtMillis
            )
        }

        val routines = db.routineDao().getAllRoutines().map { r ->
            RoutineBackupDto(
                id = r.id,
                name = r.name,
                description = r.description,
                targetCategory = r.targetCategory,
                createdAtMillis = r.createdAtMillis,
                updatedAtMillis = r.updatedAtMillis
            )
        }

        val routineExercises = db.routineDao().getAllRoutineExercises().map { re ->
            RoutineExerciseBackupDto(
                id = re.id,
                routineId = re.routineId,
                exerciseId = re.exerciseId,
                exerciseName = re.exerciseName,
                orderIndex = re.orderIndex,
                targetSets = re.targetSets,
                minReps = re.minReps,
                maxReps = re.maxReps,
                suggestedLoadGrams = re.suggestedLoadGrams,
                isDumbbell = re.isDumbbell,
                isAssisted = re.isAssisted,
                notes = re.notes
            )
        }

        val activeDraft = db.activeWorkoutDraftDao().getCurrentDraft()?.let { d ->
            ActiveWorkoutDraftBackupDto(
                slotId = d.slotId,
                sessionUuid = d.sessionUuid,
                state = d.state,
                title = d.title,
                rawNotesText = d.rawNotesText,
                cursorPosition = d.cursorPosition,
                sourceRoutineId = d.sourceRoutineId,
                plannedCategory = d.plannedCategory,
                startedAtWallMillis = d.startedAtWallMillis,
                startedAtElapsedRealtimeMillis = d.startedAtElapsedRealtimeMillis,
                accumulatedPausedMillis = d.accumulatedPausedMillis,
                reviewStartedAtWallMillis = d.reviewStartedAtWallMillis,
                reviewStartedAtElapsedMillis = d.reviewStartedAtElapsedMillis,
                finalizedAtWallMillis = d.finalizedAtWallMillis,
                lastSavedAtWallMillis = d.lastSavedAtWallMillis,
                lastKnownElapsedRealtimeMillis = d.lastKnownElapsedRealtimeMillis,
                lastPersistedDurationMillis = d.lastPersistedDurationMillis,
                committedSessionId = d.committedSessionId,
                lastErrorMessage = d.lastErrorMessage
            )
        }

        val categories = db.categoryDao().getAll().map { c ->
            CategoryBackupDto(
                id = c.id,
                name = c.name,
                icon = c.icon,
                orderIndex = c.orderIndex,
                isHidden = c.isHidden,
                colorHex = c.colorHex
            )
        }

        SyntraBackupPayload(
            formatVersion = BackupSerializer.CURRENT_FORMAT_VERSION,
            appVersion = "0.7.0-alpha9",
            exportedAtMillis = System.currentTimeMillis(),
            profile = profileDto,
            weighIns = weighIns,
            exercises = exercises,
            exerciseAliases = aliases,
            workoutSessions = sessions,
            workoutSets = sets,
            irisxMemories = memories,
            irisxConversations = conversations,
            irisxMessages = messages,
            routines = routines,
            routineExercises = routineExercises,
            activeWorkoutDraft = activeDraft,
            categories = categories
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

            db.exerciseDao().deleteAll()
            if (payload.exercises.isNotEmpty()) {
                db.exerciseDao().insertAll(
                    payload.exercises.map { e ->
                        ExerciseEntity(
                            id = e.id,
                            displayName = e.displayName,
                            normalizedName = e.normalizedName,
                            muscleGroup = e.muscleGroup,
                            secondaryMuscleGroups = e.secondaryMuscleGroups,
                            equipment = e.equipment,
                            exerciseType = e.exerciseType,
                            status = e.status,
                            createdAtMillis = e.createdAtMillis
                        )
                    }
                )
            }

            db.exerciseAliasDao().deleteAll()
            if (payload.exerciseAliases.isNotEmpty()) {
                db.exerciseAliasDao().insertAll(
                    payload.exerciseAliases.map { a ->
                        ExerciseAliasEntity(
                            id = a.id,
                            exerciseId = a.exerciseId,
                            normalizedAlias = a.normalizedAlias,
                            createdAtMillis = a.createdAtMillis
                        )
                    }
                )
            }

            db.workoutSessionDao().deleteAll()
            if (payload.workoutSessions.isNotEmpty()) {
                db.workoutSessionDao().insertAll(
                    payload.workoutSessions.map { s ->
                        WorkoutSessionEntity(
                            id = s.id,
                            title = s.title,
                            category = s.category,
                            startedAtMillis = s.startedAtMillis,
                            finishedAtMillis = s.finishedAtMillis,
                            notes = s.notes
                        )
                    }
                )
            }

            db.workoutSetDao().deleteAll()
            if (payload.workoutSets.isNotEmpty()) {
                db.workoutSetDao().insertAll(
                    payload.workoutSets.map { set ->
                        WorkoutSetEntity(
                            id = set.id,
                            sessionId = set.sessionId,
                            exerciseId = set.exerciseId,
                            orderInExercise = set.orderInExercise,
                            executionOrder = set.executionOrder,
                            loadGrams = set.loadGrams,
                            repetitions = set.repetitions,
                            rir = set.rir,
                            isAssisted = set.isAssisted,
                            isDumbbell = set.isDumbbell,
                            note = set.note,
                            plannedTarget = set.plannedTarget,
                            loadUnit = set.loadUnit,
                            isToFailure = set.isToFailure
                        )
                    }
                )
            }

            db.irisxMemoryDao().deleteAll()
            if (payload.irisxMemories.isNotEmpty()) {
                db.irisxMemoryDao().insertAll(
                    payload.irisxMemories.map { m ->
                        IrisxMemoryEntity(
                            id = m.id,
                            memoryType = m.memoryType,
                            sourceKey = m.sourceKey,
                            targetValue = m.targetValue,
                            confidence = m.confidence,
                            createdAtMillis = m.createdAtMillis,
                            updatedAtMillis = m.updatedAtMillis
                        )
                    }
                )
            }

            db.irisxConversationDao().deleteAllMessages()
            db.irisxConversationDao().deleteAllConversations()
            if (payload.irisxConversations.isNotEmpty()) {
                db.irisxConversationDao().insertAllConversations(
                    payload.irisxConversations.map { c ->
                        IrisxConversationEntity(
                            id = c.id,
                            title = c.title,
                            activeMuscleGroup = c.activeMuscleGroup,
                            activeExerciseName = c.activeExerciseName,
                            activeSessionId = c.activeSessionId,
                            activePeriodScope = c.activePeriodScope,
                            createdAtMillis = c.createdAtMillis,
                            updatedAtMillis = c.updatedAtMillis
                        )
                    }
                )
            }
            if (payload.irisxMessages.isNotEmpty()) {
                db.irisxConversationDao().insertAllMessages(
                    payload.irisxMessages.map { msg ->
                        IrisxMessageEntity(
                            id = msg.id,
                            conversationId = msg.conversationId,
                            senderRole = msg.senderRole,
                            content = msg.content,
                            intentType = msg.intentType,
                            referencedExercise = msg.referencedExercise,
                            referencedMuscleGroup = msg.referencedMuscleGroup,
                            referencedSessionId = msg.referencedSessionId,
                            createdAtMillis = msg.createdAtMillis
                        )
                    }
                )
            }

            db.routineDao().deleteAllRoutineExercises()
            db.routineDao().deleteAllRoutines()
            if (payload.routines.isNotEmpty()) {
                db.routineDao().insertAllRoutines(
                    payload.routines.map { r ->
                        RoutineEntity(
                            id = r.id,
                            name = r.name,
                            description = r.description,
                            targetCategory = r.targetCategory,
                            createdAtMillis = r.createdAtMillis,
                            updatedAtMillis = r.updatedAtMillis
                        )
                    }
                )
            }
            if (payload.routineExercises.isNotEmpty()) {
                db.routineDao().insertAllRoutineExercises(
                    payload.routineExercises.map { re ->
                        RoutineExerciseEntity(
                            id = re.id,
                            routineId = re.routineId,
                            exerciseId = re.exerciseId,
                            exerciseName = re.exerciseName,
                            orderIndex = re.orderIndex,
                            targetSets = re.targetSets,
                            minReps = re.minReps,
                            maxReps = re.maxReps,
                            suggestedLoadGrams = re.suggestedLoadGrams,
                            isDumbbell = re.isDumbbell,
                            isAssisted = re.isAssisted,
                            notes = re.notes
                        )
                    }
                )
            }

            db.activeWorkoutDraftDao().deleteAllDrafts()
            if (payload.activeWorkoutDraft != null) {
                val d = payload.activeWorkoutDraft
                db.activeWorkoutDraftDao().saveDraft(
                    ActiveWorkoutDraftEntity(
                        slotId = d.slotId,
                        sessionUuid = d.sessionUuid,
                        state = d.state,
                        title = d.title,
                        rawNotesText = d.rawNotesText,
                        cursorPosition = d.cursorPosition,
                        sourceRoutineId = d.sourceRoutineId,
                        plannedCategory = d.plannedCategory,
                        startedAtWallMillis = d.startedAtWallMillis,
                        startedAtElapsedRealtimeMillis = d.startedAtElapsedRealtimeMillis,
                        accumulatedPausedMillis = d.accumulatedPausedMillis,
                        reviewStartedAtWallMillis = d.reviewStartedAtWallMillis,
                        reviewStartedAtElapsedMillis = d.reviewStartedAtElapsedMillis,
                        finalizedAtWallMillis = d.finalizedAtWallMillis,
                        lastSavedAtWallMillis = d.lastSavedAtWallMillis,
                        lastKnownElapsedRealtimeMillis = d.lastKnownElapsedRealtimeMillis,
                        lastPersistedDurationMillis = d.lastPersistedDurationMillis,
                        committedSessionId = d.committedSessionId,
                        lastErrorMessage = d.lastErrorMessage
                    )
                )
            }

            if (payload.categories.isNotEmpty()) {
                db.categoryDao().deleteAll()
                db.categoryDao().insertAll(
                    payload.categories.map { c ->
                        CategoryEntity(
                            id = c.id,
                            name = c.name,
                            icon = c.icon,
                            orderIndex = c.orderIndex,
                            isHidden = c.isHidden,
                            colorHex = c.colorHex
                        )
                    }
                )
            }
        }
    }
}
