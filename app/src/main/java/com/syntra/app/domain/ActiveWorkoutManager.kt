package com.syntra.app.domain

import androidx.room.withTransaction
import com.syntra.app.data.ActiveWorkoutDraftEntity
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Valid states for the Active Workout lifecycle (Sprint 05).
 *
 * State machine transitions:
 * - IDLE -> ACTIVE (when starting a blank session, routine session, or recovering draft)
 * - ACTIVE -> REVIEW_PENDING (when user taps Finalizar to review parsed exercises)
 * - REVIEW_PENDING -> ACTIVE (when user cancels review and returns to notepad)
 * - REVIEW_PENDING -> COMPLETED (only after atomic SQLite commit succeeds)
 * - REVIEW_PENDING -> ERROR (when SQLite commit fails; draft stays recoverable)
 * - ERROR -> REVIEW_PENDING / COMPLETED (when retrying finalization)
 * - ACTIVE / REVIEW_PENDING / ERROR -> CANCELLED -> IDLE (only with explicit confirmation)
 */
enum class WorkoutSessionState {
    IDLE,
    ACTIVE,
    REVIEW_PENDING,
    COMPLETED,
    CANCELLED,
    ERROR;

    companion object {
        fun fromString(value: String?): WorkoutSessionState =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: IDLE
    }
}

enum class DraftSaveStatus {
    SAVED,
    SAVING,
    ERROR
}

data class ActiveTimerSnapshot(
    val elapsedDurationMillis: Long,
    val formattedHms: String,
    val isPaused: Boolean,
    val rebootRecovered: Boolean
)

/**
 * Hybrid persistent timer (Monotonic elapsedRealtime + Wall-clock fallback).
 *
 * - Immune to manual system clock changes while the device has not rebooted
 *   (`currentElapsedRealtimeMillis >= draft.lastKnownElapsedRealtimeMillis`).
 * - Survives device reboots (`currentElapsedRealtimeMillis < draft.lastKnownElapsedRealtimeMillis`)
 *   by computing the wall-clock delta since `startedAtWallMillis` minus `accumulatedPausedMillis`,
 *   bounded below by `lastPersistedDurationMillis`.
 * - Freezes accurately when `state == REVIEW_PENDING`.
 */
object ActiveWorkoutTimer {

    fun computeSnapshot(
        draft: ActiveWorkoutDraftEntity,
        currentWallMillis: Long,
        currentElapsedRealtimeMillis: Long
    ): ActiveTimerSnapshot {
        val state = WorkoutSessionState.fromString(draft.state)
        val isPaused = state == WorkoutSessionState.REVIEW_PENDING || state == WorkoutSessionState.ERROR

        // Detect device reboot: monotonic clock reset below last known value or start value
        val rebootDetected = currentElapsedRealtimeMillis < draft.lastKnownElapsedRealtimeMillis ||
            currentElapsedRealtimeMillis < draft.startedAtElapsedRealtimeMillis

        val durationMillis: Long = if (!rebootDetected) {
            val effectiveEndElapsed = if (isPaused && draft.reviewStartedAtElapsedMillis != null) {
                draft.reviewStartedAtElapsedMillis
            } else {
                currentElapsedRealtimeMillis
            }
            val rawElapsed = effectiveEndElapsed - draft.startedAtElapsedRealtimeMillis - draft.accumulatedPausedMillis
            rawElapsed.coerceAtLeast(draft.lastPersistedDurationMillis).coerceAtLeast(0L)
        } else {
            val effectiveEndWall = if (isPaused && draft.reviewStartedAtWallMillis != null) {
                draft.reviewStartedAtWallMillis
            } else {
                currentWallMillis
            }
            val rawWall = effectiveEndWall - draft.startedAtWallMillis - draft.accumulatedPausedMillis
            rawWall.coerceAtLeast(draft.lastPersistedDurationMillis).coerceAtLeast(0L)
        }

        return ActiveTimerSnapshot(
            elapsedDurationMillis = durationMillis,
            formattedHms = formatDurationHms(durationMillis),
            isPaused = isPaused,
            rebootRecovered = rebootDetected
        )
    }

    /**
     * Re-anchors a draft's monotonic timestamps if a reboot occurred so subsequent monotonic ticks
     * continue seamlessly from the recovered duration.
     */
    fun normalizeDraftClocks(
        draft: ActiveWorkoutDraftEntity,
        currentWallMillis: Long,
        currentElapsedRealtimeMillis: Long
    ): ActiveWorkoutDraftEntity {
        val snapshot = computeSnapshot(draft, currentWallMillis, currentElapsedRealtimeMillis)
        return if (snapshot.rebootRecovered) {
            val state = WorkoutSessionState.fromString(draft.state)
            val isPaused = state == WorkoutSessionState.REVIEW_PENDING || state == WorkoutSessionState.ERROR
            val reanchoredStartElapsed = currentElapsedRealtimeMillis - snapshot.elapsedDurationMillis
            draft.copy(
                startedAtElapsedRealtimeMillis = reanchoredStartElapsed,
                accumulatedPausedMillis = 0L,
                reviewStartedAtElapsedMillis = if (isPaused) currentElapsedRealtimeMillis else null,
                reviewStartedAtWallMillis = if (isPaused) (draft.reviewStartedAtWallMillis ?: currentWallMillis) else null,
                lastKnownElapsedRealtimeMillis = currentElapsedRealtimeMillis,
                lastPersistedDurationMillis = snapshot.elapsedDurationMillis
            )
        } else {
            draft.copy(
                lastKnownElapsedRealtimeMillis = currentElapsedRealtimeMillis,
                lastPersistedDurationMillis = snapshot.elapsedDurationMillis
            )
        }
    }

    fun formatDurationHms(durationMillis: Long): String {
        val totalSeconds = (durationMillis.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun formatSavedTimestamp(wallMillis: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(wallMillis))
    }
}

data class ActiveWorkoutReviewBundle(
    val draft: ActiveWorkoutDraftEntity,
    val frozenTimer: ActiveTimerSnapshot,
    val parsePreview: ParsePreview
)

sealed class FinalizationResult {
    data class Success(
        val sessionId: Long,
        val sessionUuid: String,
        val alreadyCommitted: Boolean = false
    ) : FinalizationResult()

    data class Failure(
        val errorMessage: String,
        val preservedDraft: ActiveWorkoutDraftEntity
    ) : FinalizationResult()
}

/**
 * Storage abstraction for ActiveWorkoutManager so domain tests can run both in-memory
 * and against real Room SQLite instances, and simulate database failures cleanly.
 */
interface ActiveWorkoutPersistencePort {
    suspend fun getCurrentDraft(): ActiveWorkoutDraftEntity?
    suspend fun saveDraft(draft: ActiveWorkoutDraftEntity)
    suspend fun clearCurrentDraft()
    suspend fun getLearnedAliases(): Map<String, String>
    suspend fun getAllExercises(): List<ExerciseEntity>
    suspend fun commitWorkoutSessionAtomically(
        draft: ActiveWorkoutDraftEntity,
        finalTitle: String,
        finalExercises: List<ParsedExercise>,
        startedAtMillis: Long,
        finishedAtMillis: Long
    ): Long
}

class RoomActiveWorkoutPersistencePort(
    private val db: AppDatabase
) : ActiveWorkoutPersistencePort {
    override suspend fun getCurrentDraft(): ActiveWorkoutDraftEntity? =
        db.activeWorkoutDraftDao().getCurrentDraft()

    override suspend fun saveDraft(draft: ActiveWorkoutDraftEntity) {
        db.activeWorkoutDraftDao().saveDraft(draft)
    }

    override suspend fun clearCurrentDraft() {
        db.activeWorkoutDraftDao().clearCurrentDraft()
    }

    override suspend fun getLearnedAliases(): Map<String, String> {
        return db.irisxMemoryDao().findByType("ALIAS").associate { it.sourceKey to it.targetValue }
    }

    override suspend fun getAllExercises(): List<ExerciseEntity> {
        return db.exerciseDao().getAll()
    }

    override suspend fun commitWorkoutSessionAtomically(
        draft: ActiveWorkoutDraftEntity,
        finalTitle: String,
        finalExercises: List<ParsedExercise>,
        startedAtMillis: Long,
        finishedAtMillis: Long
    ): Long {
        return db.withTransaction {
            val sId = db.workoutSessionDao().insert(
                WorkoutSessionEntity(
                    title = finalTitle,
                    startedAtMillis = startedAtMillis,
                    finishedAtMillis = finishedAtMillis,
                    notes = draft.rawNotesText
                )
            )
            var execOrder = 1
            finalExercises.forEach { ex ->
                val norm = WorkoutTextParser.normalize(ex.name)
                var exEntity = db.exerciseDao().findByNormalizedName(norm)
                if (exEntity == null) {
                    val newId = db.exerciseDao().insert(
                        ExerciseEntity(
                            displayName = ex.name,
                            normalizedName = norm,
                            muscleGroup = "General",
                            secondaryMuscleGroups = "",
                            equipment = null,
                            status = if (ex.isNewExercise) "PENDING_CONFIRMATION" else "CONFIRMED",
                            createdAtMillis = finishedAtMillis
                        )
                    )
                    exEntity = db.exerciseDao().findById(newId) ?: ExerciseEntity(
                        id = newId,
                        displayName = ex.name,
                        normalizedName = norm,
                        muscleGroup = "General",
                        createdAtMillis = finishedAtMillis
                    )
                }
                ex.sets.forEachIndexed { idx, s ->
                    db.workoutSetDao().insert(
                        WorkoutSetEntity(
                            sessionId = sId,
                            exerciseId = exEntity.id,
                            orderInExercise = idx + 1,
                            executionOrder = execOrder++,
                            loadGrams = s.weightGrams,
                            repetitions = s.repetitions,
                            rir = null,
                            isAssisted = s.isAssisted,
                            isDumbbell = s.isDumbbell,
                            note = s.note,
                            plannedTarget = ex.plannedTarget
                        )
                    )
                }
            }
            // Clear manual next-session override once a workout session is committed
            val manualOverride = db.irisxMemoryDao().findByTypeAndKey(
                NextSessionPlanner.MEMORY_TYPE_SEQUENCE,
                NextSessionPlanner.KEY_MANUAL_NEXT
            )
            if (manualOverride != null) {
                db.irisxMemoryDao().deleteById(manualOverride.id)
            }
            sId
        }
    }
}

/**
 * Manages the active workout lifecycle, hybrid persistent timer, debounced/lifecycle notepad autosave,
 * and atomic finalization review & commit protocol.
 */
class ActiveWorkoutManager(
    private val port: ActiveWorkoutPersistencePort
) {
    private val commitLock = Any()
    @Volatile private var commitInFlightUuid: String? = null

    /**
     * Returns the current recoverable draft if one exists in ACTIVE, REVIEW_PENDING, or ERROR state.
     */
    suspend fun getRecoverableDraft(
        nowWallMillis: Long = System.currentTimeMillis(),
        nowElapsedRealtimeMillis: Long = nowWallMillis
    ): ActiveWorkoutDraftEntity? {
        val existing = port.getCurrentDraft() ?: return null
        val state = WorkoutSessionState.fromString(existing.state)
        if (state == WorkoutSessionState.COMPLETED || state == WorkoutSessionState.CANCELLED || state == WorkoutSessionState.IDLE) {
            return null
        }
        val normalized = ActiveWorkoutTimer.normalizeDraftClocks(existing, nowWallMillis, nowElapsedRealtimeMillis)
        if (normalized != existing) {
            port.saveDraft(normalized)
        }
        return normalized
    }

    /**
     * Starts a new active workout session or recovers an existing unfinished session if `recoverExisting == true`.
     * Never silently overwrites an active draft unless `forceReplaceExisting == true`.
     */
    suspend fun startSession(
        title: String = "",
        initialNotesText: String = "",
        sourceRoutineId: Long? = null,
        plannedCategory: String? = null,
        recoverExisting: Boolean = true,
        forceReplaceExisting: Boolean = false,
        nowWallMillis: Long = System.currentTimeMillis(),
        nowElapsedRealtimeMillis: Long = nowWallMillis
    ): ActiveWorkoutDraftEntity {
        val existing = getRecoverableDraft(nowWallMillis, nowElapsedRealtimeMillis)
        if (existing != null && !forceReplaceExisting) {
            if (recoverExisting) {
                return existing
            }
            return existing
        }

        val initialCursor = initialNotesText.length
        val newDraft = ActiveWorkoutDraftEntity(
            slotId = 1,
            sessionUuid = UUID.randomUUID().toString(),
            state = WorkoutSessionState.ACTIVE.name,
            title = title.trim(),
            rawNotesText = initialNotesText,
            cursorPosition = initialCursor,
            sourceRoutineId = sourceRoutineId,
            plannedCategory = plannedCategory?.trim()?.takeIf { it.isNotEmpty() },
            startedAtWallMillis = nowWallMillis,
            startedAtElapsedRealtimeMillis = nowElapsedRealtimeMillis,
            accumulatedPausedMillis = 0L,
            reviewStartedAtWallMillis = null,
            reviewStartedAtElapsedMillis = null,
            finalizedAtWallMillis = null,
            lastSavedAtWallMillis = nowWallMillis,
            lastKnownElapsedRealtimeMillis = nowElapsedRealtimeMillis,
            lastPersistedDurationMillis = 0L,
            committedSessionId = null,
            lastErrorMessage = null
        )
        port.saveDraft(newDraft)
        return newDraft
    }

    /**
     * Starts an active workout pre-populated from a `RoutineWithExercises` template.
     * Modifications during the active workout occur solely in `active_workout_drafts` and never mutate the routine.
     */
    suspend fun startFromRoutine(
        routineWithExercises: RoutineWithExercises,
        forceReplaceExisting: Boolean = false,
        nowWallMillis: Long = System.currentTimeMillis(),
        nowElapsedRealtimeMillis: Long = nowWallMillis
    ): ActiveWorkoutDraftEntity {
        val templateText = RoutineManager.toParserTextTemplate(routineWithExercises)
        return startSession(
            title = routineWithExercises.routine.name,
            initialNotesText = templateText,
            sourceRoutineId = routineWithExercises.routine.id,
            plannedCategory = routineWithExercises.routine.targetCategory,
            recoverExisting = !forceReplaceExisting,
            forceReplaceExisting = forceReplaceExisting,
            nowWallMillis = nowWallMillis,
            nowElapsedRealtimeMillis = nowElapsedRealtimeMillis
        )
    }

    /**
     * Autosaves the free-text notepad, preserving line breaks, cursor position, and current timer duration.
     */
    suspend fun autosaveNotes(
        rawNotesText: String,
        cursorPosition: Int,
        title: String? = null,
        nowWallMillis: Long = System.currentTimeMillis(),
        nowElapsedRealtimeMillis: Long = nowWallMillis
    ): Pair<ActiveWorkoutDraftEntity?, DraftSaveStatus> {
        return try {
            val current = port.getCurrentDraft() ?: return null to DraftSaveStatus.ERROR
            val normalized = ActiveWorkoutTimer.normalizeDraftClocks(current, nowWallMillis, nowElapsedRealtimeMillis)
            val safeCursor = cursorPosition.coerceIn(0, rawNotesText.length)
            val updated = normalized.copy(
                title = title?.trim() ?: normalized.title,
                rawNotesText = rawNotesText,
                cursorPosition = safeCursor,
                lastSavedAtWallMillis = nowWallMillis,
                lastErrorMessage = null
            )
            port.saveDraft(updated)
            updated to DraftSaveStatus.SAVED
        } catch (_: Exception) {
            port.getCurrentDraft() to DraftSaveStatus.ERROR
        }
    }

    /**
     * Transitions the session from `ACTIVE` (or `ERROR`) to `REVIEW_PENDING`, freezing the timer
     * and running `WorkoutTextParser.parse` with learned aliases and catalog exercises.
     * Note: A session in `REVIEW_PENDING` is NOT completed yet.
     */
    suspend fun beginFinalizationReview(
        nowWallMillis: Long = System.currentTimeMillis(),
        nowElapsedRealtimeMillis: Long = nowWallMillis
    ): ActiveWorkoutReviewBundle? {
        val current = port.getCurrentDraft() ?: return null
        val normalized = ActiveWorkoutTimer.normalizeDraftClocks(current, nowWallMillis, nowElapsedRealtimeMillis)
        val currentState = WorkoutSessionState.fromString(normalized.state)
        if (currentState == WorkoutSessionState.COMPLETED || currentState == WorkoutSessionState.CANCELLED) {
            return null
        }

        val reviewDraft = if (currentState == WorkoutSessionState.REVIEW_PENDING && normalized.reviewStartedAtElapsedMillis != null) {
            normalized
        } else {
            val snapshot = ActiveWorkoutTimer.computeSnapshot(normalized, nowWallMillis, nowElapsedRealtimeMillis)
            normalized.copy(
                state = WorkoutSessionState.REVIEW_PENDING.name,
                reviewStartedAtWallMillis = nowWallMillis,
                reviewStartedAtElapsedMillis = nowElapsedRealtimeMillis,
                lastSavedAtWallMillis = nowWallMillis,
                lastKnownElapsedRealtimeMillis = nowElapsedRealtimeMillis,
                lastPersistedDurationMillis = snapshot.elapsedDurationMillis,
                lastErrorMessage = null
            )
        }
        port.saveDraft(reviewDraft)

        val aliases = port.getLearnedAliases()
        val knownNames = port.getAllExercises().map { it.normalizedName }.toSet()
        val preview = WorkoutTextParser.parse(
            raw = reviewDraft.rawNotesText,
            aliases = aliases,
            knownExercises = knownNames
        )
        val effectiveTitle = preview.sessionTitle
            ?: reviewDraft.title.takeIf { it.isNotBlank() }
            ?: reviewDraft.plannedCategory
        val enrichedPreview = preview.copy(sessionTitle = effectiveTitle)
        val frozenSnapshot = ActiveWorkoutTimer.computeSnapshot(reviewDraft, nowWallMillis, nowElapsedRealtimeMillis)

        return ActiveWorkoutReviewBundle(
            draft = reviewDraft,
            frozenTimer = frozenSnapshot,
            parsePreview = enrichedPreview
        )
    }

    /**
     * Cancels the finalization review (`REVIEW_PENDING -> ACTIVE`), accumulating the time spent
     * in review into `accumulatedPausedMillis` so the workout timer resumes without counting review pause time.
     */
    suspend fun cancelReviewAndResume(
        nowWallMillis: Long = System.currentTimeMillis(),
        nowElapsedRealtimeMillis: Long = nowWallMillis
    ): ActiveWorkoutDraftEntity? {
        val current = port.getCurrentDraft() ?: return null
        val normalized = ActiveWorkoutTimer.normalizeDraftClocks(current, nowWallMillis, nowElapsedRealtimeMillis)
        val state = WorkoutSessionState.fromString(normalized.state)
        if (state != WorkoutSessionState.REVIEW_PENDING && state != WorkoutSessionState.ERROR) {
            return normalized
        }

        val pauseDeltaElapsed = normalized.reviewStartedAtElapsedMillis?.let {
            (nowElapsedRealtimeMillis - it).coerceAtLeast(0L)
        } ?: normalized.reviewStartedAtWallMillis?.let {
            (nowWallMillis - it).coerceAtLeast(0L)
        } ?: 0L

        val resumed = normalized.copy(
            state = WorkoutSessionState.ACTIVE.name,
            accumulatedPausedMillis = normalized.accumulatedPausedMillis + pauseDeltaElapsed,
            reviewStartedAtWallMillis = null,
            reviewStartedAtElapsedMillis = null,
            lastSavedAtWallMillis = nowWallMillis,
            lastKnownElapsedRealtimeMillis = nowElapsedRealtimeMillis,
            lastErrorMessage = null
        )
        port.saveDraft(resumed)
        return resumed
    }

    /**
     * Confirms and commits the active workout session atomically to SQLite.
     * - Protects against duplicate creation on repeated taps of Finalizar / Confirmar.
     * - Preserves the draft and enters `ERROR` state if the transaction fails or has no valid sets.
     * - Clears the active draft ONLY after the SQLite transaction succeeds.
     */
    suspend fun confirmAndCommitSession(
        editedTitle: String? = null,
        editedExercises: List<ParsedExercise>? = null,
        nowWallMillis: Long = System.currentTimeMillis(),
        nowElapsedRealtimeMillis: Long = nowWallMillis
    ): FinalizationResult {
        val draft = port.getCurrentDraft()
            ?: return FinalizationResult.Failure(
                errorMessage = "No existe una sesión activa para finalizar.",
                preservedDraft = ActiveWorkoutDraftEntity(
                    slotId = 1,
                    sessionUuid = "",
                    state = WorkoutSessionState.ERROR.name,
                    title = "",
                    rawNotesText = "",
                    startedAtWallMillis = nowWallMillis,
                    startedAtElapsedRealtimeMillis = nowElapsedRealtimeMillis,
                    lastSavedAtWallMillis = nowWallMillis,
                    lastKnownElapsedRealtimeMillis = nowElapsedRealtimeMillis
                )
            )

        // Idempotency guard 1: already committed in DB
        if (draft.committedSessionId != null || WorkoutSessionState.fromString(draft.state) == WorkoutSessionState.COMPLETED) {
            return FinalizationResult.Success(
                sessionId = draft.committedSessionId ?: 0L,
                sessionUuid = draft.sessionUuid,
                alreadyCommitted = true
            )
        }

        // Idempotency guard 2: concurrent click in-flight for the same sessionUuid
        synchronized(commitLock) {
            if (commitInFlightUuid == draft.sessionUuid) {
                return FinalizationResult.Success(
                    sessionId = draft.committedSessionId ?: -1L,
                    sessionUuid = draft.sessionUuid,
                    alreadyCommitted = true
                )
            }
            commitInFlightUuid = draft.sessionUuid
        }

        try {
            val normalized = ActiveWorkoutTimer.normalizeDraftClocks(draft, nowWallMillis, nowElapsedRealtimeMillis)
            val snapshot = ActiveWorkoutTimer.computeSnapshot(normalized, nowWallMillis, nowElapsedRealtimeMillis)

            val exercisesToSave = editedExercises ?: run {
                val aliases = port.getLearnedAliases()
                val knownNames = port.getAllExercises().map { it.normalizedName }.toSet()
                WorkoutTextParser.parse(
                    raw = normalized.rawNotesText,
                    aliases = aliases,
                    knownExercises = knownNames
                ).exercises
            }

            val totalSets = exercisesToSave.sumOf { it.sets.size }
            if (exercisesToSave.isEmpty() || totalSets == 0) {
                val errMsg = "No se detectaron ejercicios ni series válidas con peso (kg) para guardar."
                val errDraft = normalized.copy(
                    state = WorkoutSessionState.ERROR.name,
                    lastErrorMessage = errMsg,
                    lastSavedAtWallMillis = nowWallMillis
                )
                port.saveDraft(errDraft)
                return FinalizationResult.Failure(errMsg, errDraft)
            }

            val resolvedTitle = editedTitle?.trim()?.takeIf { it.isNotEmpty() }
                ?: normalized.title.trim().takeIf { it.isNotEmpty() }
                ?: normalized.plannedCategory?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Entrenamiento"

            val finishedWall = nowWallMillis.coerceAtLeast(normalized.startedAtWallMillis + snapshot.elapsedDurationMillis)

            val committedId = port.commitWorkoutSessionAtomically(
                draft = normalized,
                finalTitle = resolvedTitle,
                finalExercises = exercisesToSave,
                startedAtMillis = normalized.startedAtWallMillis,
                finishedAtMillis = finishedWall
            )

            // Mark COMPLETED first, then clear current draft slot so no active draft remains
            val completedDraft = normalized.copy(
                state = WorkoutSessionState.COMPLETED.name,
                finalizedAtWallMillis = finishedWall,
                committedSessionId = committedId,
                lastErrorMessage = null
            )
            port.saveDraft(completedDraft)
            port.clearCurrentDraft()

            return FinalizationResult.Success(
                sessionId = committedId,
                sessionUuid = normalized.sessionUuid,
                alreadyCommitted = false
            )
        } catch (e: Exception) {
            val errMsg = e.message?.takeIf { it.isNotBlank() } ?: "Error al guardar la sesión en SQLite."
            val errorDraft = draft.copy(
                state = WorkoutSessionState.ERROR.name,
                lastErrorMessage = errMsg,
                lastSavedAtWallMillis = nowWallMillis
            )
            try {
                port.saveDraft(errorDraft)
            } catch (_: Exception) {
                // Keep original draft untouched if saving error state also fails
            }
            return FinalizationResult.Failure(
                errorMessage = errMsg,
                preservedDraft = errorDraft
            )
        } finally {
            synchronized(commitLock) {
                if (commitInFlightUuid == draft.sessionUuid) {
                    commitInFlightUuid = null
                }
            }
        }
    }

    /**
     * Discards the active workout draft ONLY when the user explicitly confirms (`confirmed == true`).
     */
    suspend fun cancelSession(confirmed: Boolean): Boolean {
        if (!confirmed) return false
        port.clearCurrentDraft()
        return true
    }
}
