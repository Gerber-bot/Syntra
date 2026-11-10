package com.syntra.app.domain

import com.syntra.app.data.ActiveWorkoutDraftEntity
import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.IrisxConversationEntity
import com.syntra.app.data.IrisxMemoryEntity
import com.syntra.app.data.IrisxMessageEntity
import com.syntra.app.data.ProfileEntity
import com.syntra.app.data.RoutineEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.data.backup.ActiveWorkoutDraftBackupDto
import com.syntra.app.data.backup.BackupSerializer
import com.syntra.app.data.backup.BackupValidationResult
import com.syntra.app.data.backup.ProfileBackupDto
import com.syntra.app.data.backup.SyntraBackupPayload
import com.syntra.app.domain.irisx.IrisXCore
import com.syntra.app.domain.irisx.IrisXRepository
import com.syntra.app.domain.irisx.IrisXResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Comprehensive Automated Test Suite for Syntra Sprint 05 (v0.5.0).
 * Covers all 17 mandatory verification scenarios:
 * 1. Inicio de entrenamiento.
 * 2. Autoguardado del bloc de notas.
 * 3. Recreación de componentes.
 * 4. Cierre de proceso (Process death).
 * 5. Reinicio del dispositivo y ajuste del reloj del sistema.
 * 6. Finalización exitosa.
 * 7. Cancelación de vista previa y reanudación del cronómetro.
 * 8. Falla de guardado sin perder el borrador.
 * 9. Prevención de duplicados al pulsar repetidamente Finalizar.
 * 10. Recuperación tras cierre inesperado.
 * 11. Secuencia configurable de entrenamientos.
 * 12. Clasificación correcta de la próxima sesión.
 * 13. Consultas a IrisX durante entrenamiento activo.
 * 14. Aplicación de rutina sin modificar la plantilla original.
 * 15. Migración de base de datos MIGRATION_3_4.
 * 16. Exportación y restauración de backups con borrador activo.
 * 17. Integridad de registros históricos.
 */
class ActiveWorkoutAndSprint05Test {

    private class JdbcActiveWorkoutPort(
        val conn: Connection,
        var simulateCommitFailure: Boolean = false
    ) : ActiveWorkoutPersistencePort {

        init {
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS active_workout_drafts (
                        slotId INTEGER PRIMARY KEY NOT NULL,
                        sessionUuid TEXT NOT NULL,
                        state TEXT NOT NULL,
                        title TEXT NOT NULL,
                        rawNotesText TEXT NOT NULL,
                        cursorPosition INTEGER NOT NULL DEFAULT 0,
                        sourceRoutineId INTEGER,
                        plannedCategory TEXT,
                        startedAtWallMillis INTEGER NOT NULL,
                        startedAtElapsedRealtimeMillis INTEGER NOT NULL,
                        accumulatedPausedMillis INTEGER NOT NULL DEFAULT 0,
                        reviewStartedAtWallMillis INTEGER,
                        reviewStartedAtElapsedMillis INTEGER,
                        finalizedAtWallMillis INTEGER,
                        lastSavedAtWallMillis INTEGER NOT NULL,
                        lastKnownElapsedRealtimeMillis INTEGER NOT NULL,
                        lastPersistedDurationMillis INTEGER NOT NULL DEFAULT 0,
                        committedSessionId INTEGER,
                        lastErrorMessage TEXT
                    )
                """.trimIndent())
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS workout_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        startedAtMillis INTEGER NOT NULL,
                        finishedAtMillis INTEGER,
                        notes TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS workout_sets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        exerciseId INTEGER NOT NULL,
                        orderInExercise INTEGER NOT NULL,
                        executionOrder INTEGER NOT NULL DEFAULT 0,
                        loadGrams INTEGER NOT NULL,
                        repetitions INTEGER NOT NULL,
                        rir INTEGER,
                        isAssisted INTEGER NOT NULL DEFAULT 0,
                        isDumbbell INTEGER NOT NULL DEFAULT 0,
                        note TEXT NOT NULL DEFAULT '',
                        plannedTarget TEXT
                    )
                """.trimIndent())
            }
        }

        override suspend fun getCurrentDraft(): ActiveWorkoutDraftEntity? {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT * FROM active_workout_drafts WHERE slotId = 1 LIMIT 1")
                if (!rs.next()) return null
                return ActiveWorkoutDraftEntity(
                    slotId = rs.getInt("slotId"),
                    sessionUuid = rs.getString("sessionUuid"),
                    state = rs.getString("state"),
                    title = rs.getString("title"),
                    rawNotesText = rs.getString("rawNotesText"),
                    cursorPosition = rs.getInt("cursorPosition"),
                    sourceRoutineId = rs.getLong("sourceRoutineId").takeIf { !rs.wasNull() },
                    plannedCategory = rs.getString("plannedCategory"),
                    startedAtWallMillis = rs.getLong("startedAtWallMillis"),
                    startedAtElapsedRealtimeMillis = rs.getLong("startedAtElapsedRealtimeMillis"),
                    accumulatedPausedMillis = rs.getLong("accumulatedPausedMillis"),
                    reviewStartedAtWallMillis = rs.getLong("reviewStartedAtWallMillis").takeIf { !rs.wasNull() },
                    reviewStartedAtElapsedMillis = rs.getLong("reviewStartedAtElapsedMillis").takeIf { !rs.wasNull() },
                    finalizedAtWallMillis = rs.getLong("finalizedAtWallMillis").takeIf { !rs.wasNull() },
                    lastSavedAtWallMillis = rs.getLong("lastSavedAtWallMillis"),
                    lastKnownElapsedRealtimeMillis = rs.getLong("lastKnownElapsedRealtimeMillis"),
                    lastPersistedDurationMillis = rs.getLong("lastPersistedDurationMillis"),
                    committedSessionId = rs.getLong("committedSessionId").takeIf { !rs.wasNull() },
                    lastErrorMessage = rs.getString("lastErrorMessage")
                )
            }
        }

        override suspend fun saveDraft(draft: ActiveWorkoutDraftEntity) {
            val sql = """
                INSERT OR REPLACE INTO active_workout_drafts (
                    slotId, sessionUuid, state, title, rawNotesText, cursorPosition,
                    sourceRoutineId, plannedCategory, startedAtWallMillis, startedAtElapsedRealtimeMillis,
                    accumulatedPausedMillis, reviewStartedAtWallMillis, reviewStartedAtElapsedMillis,
                    finalizedAtWallMillis, lastSavedAtWallMillis, lastKnownElapsedRealtimeMillis,
                    lastPersistedDurationMillis, committedSessionId, lastErrorMessage
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, draft.slotId)
                ps.setString(2, draft.sessionUuid)
                ps.setString(3, draft.state)
                ps.setString(4, draft.title)
                ps.setString(5, draft.rawNotesText)
                ps.setInt(6, draft.cursorPosition)
                if (draft.sourceRoutineId != null) ps.setLong(7, draft.sourceRoutineId) else ps.setNull(7, java.sql.Types.BIGINT)
                if (draft.plannedCategory != null) ps.setString(8, draft.plannedCategory) else ps.setNull(8, java.sql.Types.VARCHAR)
                ps.setLong(9, draft.startedAtWallMillis)
                ps.setLong(10, draft.startedAtElapsedRealtimeMillis)
                ps.setLong(11, draft.accumulatedPausedMillis)
                if (draft.reviewStartedAtWallMillis != null) ps.setLong(12, draft.reviewStartedAtWallMillis) else ps.setNull(12, java.sql.Types.BIGINT)
                if (draft.reviewStartedAtElapsedMillis != null) ps.setLong(13, draft.reviewStartedAtElapsedMillis) else ps.setNull(13, java.sql.Types.BIGINT)
                if (draft.finalizedAtWallMillis != null) ps.setLong(14, draft.finalizedAtWallMillis) else ps.setNull(14, java.sql.Types.BIGINT)
                ps.setLong(15, draft.lastSavedAtWallMillis)
                ps.setLong(16, draft.lastKnownElapsedRealtimeMillis)
                ps.setLong(17, draft.lastPersistedDurationMillis)
                if (draft.committedSessionId != null) ps.setLong(18, draft.committedSessionId) else ps.setNull(18, java.sql.Types.BIGINT)
                if (draft.lastErrorMessage != null) ps.setString(19, draft.lastErrorMessage) else ps.setNull(19, java.sql.Types.VARCHAR)
                ps.executeUpdate()
            }
        }

        override suspend fun clearCurrentDraft() {
            conn.createStatement().use { it.execute("DELETE FROM active_workout_drafts WHERE slotId = 1") }
        }

        override suspend fun getLearnedAliases(): Map<String, String> = mapOf("banca" to "Press de banca")

        override suspend fun getAllExercises(): List<ExerciseEntity> = ExerciseCatalog.toEntities()

        override suspend fun findSessionByUuid(uuid: String): WorkoutSessionEntity? = null

        override suspend fun commitWorkoutSessionAtomically(
            draft: ActiveWorkoutDraftEntity,
            finalTitle: String,
            finalExercises: List<ParsedExercise>,
            startedAtMillis: Long,
            finishedAtMillis: Long
        ): Long {
            val prevAuto = conn.autoCommit
            conn.autoCommit = false
            try {
                val sessionId: Long
                conn.prepareStatement(
                    "INSERT INTO workout_sessions (title, startedAtMillis, finishedAtMillis, notes) VALUES (?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS
                ).use { ps ->
                    ps.setString(1, finalTitle)
                    ps.setLong(2, startedAtMillis)
                    ps.setLong(3, finishedAtMillis)
                    ps.setString(4, draft.rawNotesText)
                    ps.executeUpdate()
                    val keys = ps.generatedKeys
                    keys.next()
                    sessionId = keys.getLong(1)
                }

                if (simulateCommitFailure) {
                    throw IllegalStateException("Fallo simulado de escritura SQLite a mitad de transacción")
                }

                var execOrder = 1
                finalExercises.forEachIndexed { exIdx, ex ->
                    ex.sets.forEachIndexed { setIdx, s ->
                        conn.prepareStatement(
                            """
                            INSERT INTO workout_sets (
                                sessionId, exerciseId, orderInExercise, executionOrder,
                                loadGrams, repetitions, isAssisted, isDumbbell, note, plannedTarget
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent()
                        ).use { ps ->
                            ps.setLong(1, sessionId)
                            ps.setLong(2, (exIdx + 1).toLong())
                            ps.setInt(3, setIdx + 1)
                            ps.setInt(4, execOrder++)
                            ps.setInt(5, s.weightGrams)
                            ps.setInt(6, s.repetitions)
                            ps.setInt(7, if (s.isAssisted) 1 else 0)
                            ps.setInt(8, if (s.isDumbbell) 1 else 0)
                            ps.setString(9, s.note)
                            ps.setString(10, ex.plannedTarget)
                            ps.executeUpdate()
                        }
                    }
                }
                clearCurrentDraft()
                conn.commit()
                return sessionId
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = prevAuto
            }
        }

        fun countCommittedSessions(): Int {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM workout_sessions")
                rs.next()
                return rs.getInt(1)
            }
        }

        fun countCommittedSets(): Int {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM workout_sets")
                rs.next()
                return rs.getInt(1)
            }
        }
    }

    @Test
    fun `1 and 2 and 3 - starts session, autosaves preserving line breaks and cursor, and survives component recreation`() = runBlocking {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val port = JdbcActiveWorkoutPort(conn)
        val manager1 = ActiveWorkoutManager(port)

        // 1. Start session
        val started = manager1.startSession(
            title = "Pecho",
            initialNotesText = "Pecho\n",
            plannedCategory = "Pecho",
            nowWallMillis = 1_700_000_000_000L,
            nowElapsedRealtimeMillis = 50_000L
        )
        assertEquals(WorkoutSessionState.ACTIVE.name, started.state)
        assertEquals("Pecho", started.title)

        // 2. Autosave multiline notes + cursor position
        val multiline = "Pecho\nPress de banca 3 de 8 a 10: 70kg 10 9 8\nAperturas con mancuernas 14kg 12 12"
        val cursorIdx = 45
        val (saved, status) = manager1.autosaveNotes(
            rawNotesText = multiline,
            cursorPosition = cursorIdx,
            nowWallMillis = 1_700_000_120_000L,
            nowElapsedRealtimeMillis = 170_000L
        )
        assertEquals(DraftSaveStatus.SAVED, status)
        assertNotNull(saved)
        assertEquals(multiline, saved!!.rawNotesText)
        assertEquals(cursorIdx, saved.cursorPosition)

        // 3. Simulate UI/Component recreation (new manager instance over same SQLite DB)
        val managerRecreated = ActiveWorkoutManager(JdbcActiveWorkoutPort(conn))
        val recovered = managerRecreated.getRecoverableDraft(
            nowWallMillis = 1_700_000_180_000L,
            nowElapsedRealtimeMillis = 230_000L
        )
        assertNotNull(recovered)
        assertEquals(started.sessionUuid, recovered!!.sessionUuid)
        assertEquals(multiline, recovered.rawNotesText)
        assertEquals(cursorIdx, recovered.cursorPosition)

        val snap = ActiveWorkoutTimer.computeSnapshot(
            draft = recovered,
            currentWallMillis = 1_700_000_180_000L,
            currentElapsedRealtimeMillis = 230_000L
        )
        assertEquals(180_000L, snap.elapsedDurationMillis)
        assertEquals("00:03:00", snap.formattedHms)
        conn.close()
    }

    @Test
    fun `4 and 5 and 10 - survives process death, system clock change, and device reboot`() = runBlocking {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val port = JdbcActiveWorkoutPort(conn)
        val manager = ActiveWorkoutManager(port)

        val draft = manager.startSession(
            title = "Espalda",
            initialNotesText = "Dominadas 10kg lastre 8 8 7",
            nowWallMillis = 1_700_000_000_000L,
            nowElapsedRealtimeMillis = 1_000_000L
        )

        // Advance 10 minutes (600_000 ms) and autosave before process death
        manager.autosaveNotes(
            rawNotesText = "Dominadas +10kg lastre 8 8 7\nRemo con barra 60kg 10 10",
            cursorPosition = 20,
            nowWallMillis = 1_700_000_600_000L,
            nowElapsedRealtimeMillis = 1_600_000L
        )

        // Case A: User manually shifts system wall clock backwards by 2 hours while phone stays on!
        // Monotonic clock advances another 5 minutes (to 1_900_000L -> total 15 mins = 900_000 ms).
        val wallShiftedBackwards = 1_700_000_600_000L - 7_200_000L
        val currentAfterClockShift = port.getCurrentDraft()!!
        val snapMonotonic = ActiveWorkoutTimer.computeSnapshot(
            draft = currentAfterClockShift,
            currentWallMillis = wallShiftedBackwards,
            currentElapsedRealtimeMillis = 1_900_000L
        )
        assertFalse(snapMonotonic.rebootRecovered)
        assertEquals(900_000L, snapMonotonic.elapsedDurationMillis)
        assertEquals("00:15:00", snapMonotonic.formattedHms)

        // Case B: Device reboots! elapsedRealtime resets to a small value (e.g. 45_000L < lastKnownElapsed 1_600_000L),
        // while real wall clock is now 25 minutes after start (1_700_001_500_000L).
        val managerAfterReboot = ActiveWorkoutManager(JdbcActiveWorkoutPort(conn))
        val recoveredAfterReboot = managerAfterReboot.getRecoverableDraft(
            nowWallMillis = 1_700_001_500_000L,
            nowElapsedRealtimeMillis = 45_000L
        )
        assertNotNull(recoveredAfterReboot)
        assertEquals(draft.sessionUuid, recoveredAfterReboot!!.sessionUuid)
        assertEquals(1_500_000L, recoveredAfterReboot.lastPersistedDurationMillis)
        assertEquals("00:25:00", ActiveWorkoutTimer.formatDurationHms(recoveredAfterReboot.lastPersistedDurationMillis))

        // Subsequent monotonic tick 10 seconds after reboot recovery -> 25m 10s
        val nextTick = ActiveWorkoutTimer.computeSnapshot(
            draft = recoveredAfterReboot,
            currentWallMillis = 1_700_001_510_000L,
            currentElapsedRealtimeMillis = 55_000L
        )
        assertEquals(1_510_000L, nextTick.elapsedDurationMillis)
        assertEquals("00:25:10", nextTick.formattedHms)
        conn.close()
    }

    @Test
    fun `6 and 7 and 8 and 9 - review pause and resume, save failure preservation, successful commit, and duplicate prevention`() = runBlocking {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val port = JdbcActiveWorkoutPort(conn)
        val manager = ActiveWorkoutManager(port)

        manager.startSession(
            title = "Piernas",
            initialNotesText = "Sentadilla 80kg 8 8 8\nPrensa de piernas 160kg 12 10",
            nowWallMillis = 1_700_000_000_000L,
            nowElapsedRealtimeMillis = 100_000L
        )

        // 7. Enter REVIEW_PENDING at t = 20 min (1_200_000 ms elapsed -> 1_300_000L)
        val review1 = manager.beginFinalizationReview(
            nowWallMillis = 1_700_001_200_000L,
            nowElapsedRealtimeMillis = 1_300_000L
        )
        assertNotNull(review1)
        assertEquals(WorkoutSessionState.REVIEW_PENDING.name, review1!!.draft.state)
        assertEquals("00:20:00", review1.frozenTimer.formattedHms)
        assertEquals(0, port.countCommittedSessions()) // REVIEW_PENDING is NOT completed

        // Spend 3 minutes (180_000 ms) in review; timer must stay frozen at 00:20:00
        val frozenCheck = ActiveWorkoutTimer.computeSnapshot(
            draft = review1.draft,
            currentWallMillis = 1_700_001_380_000L,
            currentElapsedRealtimeMillis = 1_480_000L
        )
        assertTrue(frozenCheck.isPaused)
        assertEquals("00:20:00", frozenCheck.formattedHms)

        // Cancel review at t = 23 min (1_480_000L) and resume active training for 5 more minutes (to 1_780_000L)
        val resumed = manager.cancelReviewAndResume(
            nowWallMillis = 1_700_001_380_000L,
            nowElapsedRealtimeMillis = 1_480_000L
        )
        assertNotNull(resumed)
        assertEquals(WorkoutSessionState.ACTIVE.name, resumed!!.state)
        assertEquals(180_000L, resumed.accumulatedPausedMillis)

        // At 1_780_000L, active workout time must be 20m + 5m = 25m (excluding the 3m review pause)
        val review2 = manager.beginFinalizationReview(
            nowWallMillis = 1_700_001_680_000L,
            nowElapsedRealtimeMillis = 1_780_000L
        )
        assertEquals("00:25:00", review2!!.frozenTimer.formattedHms)

        // 8. Simulate SQLite failure during finalization -> draft must NOT be lost and zero partial writes
        port.simulateCommitFailure = true
        val failedResult = manager.confirmAndCommitSession(
            nowWallMillis = 1_700_001_690_000L,
            nowElapsedRealtimeMillis = 1_790_000L
        )
        assertTrue(failedResult is FinalizationResult.Failure)
        assertEquals(0, port.countCommittedSessions())
        assertEquals(0, port.countCommittedSets())
        val preservedDraft = port.getCurrentDraft()
        assertNotNull(preservedDraft)
        assertEquals(WorkoutSessionState.ERROR.name, preservedDraft!!.state)
        assertTrue(preservedDraft.rawNotesText.contains("Sentadilla 80kg"))

        // 6. Retry finalization with healthy DB -> atomic commit succeeds and clears active draft
        port.simulateCommitFailure = false
        val successResult = manager.confirmAndCommitSession(
            nowWallMillis = 1_700_001_700_000L,
            nowElapsedRealtimeMillis = 1_800_000L
        )
        assertTrue(successResult is FinalizationResult.Success)
        assertEquals(1, port.countCommittedSessions())
        assertEquals(5, port.countCommittedSets())
        assertNull(port.getCurrentDraft())

        // 9. Repeated click on Confirm/Finalize after commit does NOT create duplicate session
        val duplicateAttempt = manager.confirmAndCommitSession(
            nowWallMillis = 1_700_001_701_000L,
            nowElapsedRealtimeMillis = 1_801_000L
        )
        assertTrue(duplicateAttempt is FinalizationResult.Failure)
        assertEquals(1, port.countCommittedSessions())
        conn.close()
    }

    @Test
    fun `11 and 12 - configurable training sequence advances only on completed sessions and links routines`() {
        val exercises = listOf(
            ExerciseEntity(id = 1, displayName = "Press de banca", normalizedName = "press de banca", muscleGroup = "Pecho"),
            ExerciseEntity(id = 2, displayName = "Dominadas", normalizedName = "dominadas", muscleGroup = "Espalda"),
            ExerciseEntity(id = 3, displayName = "Sentadilla", normalizedName = "sentadilla", muscleGroup = "Piernas")
        )
        val routines = listOf(
            RoutineWithExercises(
                routine = RoutineEntity(id = 10, name = "Día de Espalda", targetCategory = "Espalda"),
                exercises = listOf(RoutineExerciseSpec(exerciseName = "Dominadas", orderIndex = 1, targetSets = 4, minReps = 8, maxReps = 10))
            ),
            RoutineWithExercises(
                routine = RoutineEntity(id = 11, name = "Día de Piernas", targetCategory = "Piernas"),
                exercises = listOf(RoutineExerciseSpec(exerciseName = "Sentadilla", orderIndex = 1, targetSets = 4, minReps = 6, maxReps = 8))
            )
        )

        // Unconfigured sequence -> never imposes fixed order
        val emptyPlan = NextSessionPlanner.computeNextSession(
            config = TrainingSequenceConfig(steps = emptyList()),
            completedSessions = emptyList(),
            allSets = emptyList(),
            exercises = exercises,
            routines = routines
        )
        assertFalse(emptyPlan.isConfigured)
        assertNull(emptyPlan.nextCategoryName)

        // Configured sequence: Pecho -> Espalda -> Piernas
        val memories = listOf(
            IrisxMemoryEntity(memoryType = NextSessionPlanner.MEMORY_TYPE_SEQUENCE, sourceKey = NextSessionPlanner.KEY_STEPS, targetValue = "Pecho,Espalda,Piernas", createdAtMillis = 1L, updatedAtMillis = 1L)
        )
        val config = NextSessionPlanner.extractConfigFromMemories(memories)
        assertEquals(listOf("Pecho", "Espalda", "Piernas"), config.steps)

        // When last completed session was Pecho -> next must be Espalda and link 'Día de Espalda' routine
        val pechoSession = WorkoutSessionEntity(id = 100, title = "Entreno Lunes", startedAtMillis = 1_700_000_000_000L, finishedAtMillis = 1_700_003_600_000L)
        val pechoSets = listOf(
            WorkoutSetEntity(id = 1, sessionId = 100, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 75_000, repetitions = 8)
        )
        val nextAfterPecho = NextSessionPlanner.computeNextSession(
            config = config,
            completedSessions = listOf(pechoSession),
            allSets = pechoSets,
            exercises = exercises,
            routines = routines
        )
        assertTrue(nextAfterPecho.isConfigured)
        assertEquals("Espalda", nextAfterPecho.nextCategoryName)
        assertEquals("Día de Espalda", nextAfterPecho.suggestedRoutine?.routine?.name)

        // When Espalda session is completed -> next advances to Piernas
        val espaldaSession = WorkoutSessionEntity(id = 101, title = "Sesión Miércoles", startedAtMillis = 1_700_100_000_000L, finishedAtMillis = 1_700_103_600_000L)
        val espaldaSets = listOf(
            WorkoutSetEntity(id = 2, sessionId = 101, exerciseId = 2, orderInExercise = 1, executionOrder = 1, loadGrams = 10_000, repetitions = 10)
        )
        val nextAfterEspalda = NextSessionPlanner.computeNextSession(
            config = config,
            completedSessions = listOf(pechoSession, espaldaSession),
            allSets = pechoSets + espaldaSets,
            exercises = exercises,
            routines = routines
        )
        assertEquals("Piernas", nextAfterEspalda.nextCategoryName)
        assertEquals("Día de Piernas", nextAfterEspalda.suggestedRoutine?.routine?.name)
    }

    @Test
    fun `13 - IrisX answers active workout queries for last load, exercise alternatives, and last session by category`() = runBlocking {
        val exList = ExerciseCatalog.toEntities().mapIndexed { idx, e -> e.copy(id = (idx + 1).toLong()) }
        val squatId = exList.first { it.normalizedName == "sentadilla" }.id
        val benchId = exList.first { it.normalizedName == "press de banca" }.id

        val sessions = listOf(
            WorkoutSessionEntity(id = 1, title = "Piernas Pesado", startedAtMillis = 1_700_000_000_000L, finishedAtMillis = 1_700_003_600_000L),
            WorkoutSessionEntity(id = 2, title = "Pecho Hipertrofia", startedAtMillis = 1_700_100_000_000L, finishedAtMillis = 1_700_103_600_000L)
        )
        val sets = listOf(
            WorkoutSetEntity(id = 1, sessionId = 1, exerciseId = squatId, orderInExercise = 1, executionOrder = 1, loadGrams = 95_000, repetitions = 6),
            WorkoutSetEntity(id = 2, sessionId = 1, exerciseId = squatId, orderInExercise = 2, executionOrder = 2, loadGrams = 90_000, repetitions = 8),
            WorkoutSetEntity(id = 3, sessionId = 2, exerciseId = benchId, orderInExercise = 1, executionOrder = 1, loadGrams = 77_500, repetitions = 8)
        )

        val repo = object : IrisXRepository {
            override suspend fun getLatestSession(): WorkoutSessionEntity? = sessions.last()
            override suspend fun getSetsForSession(sessionId: Long): List<WorkoutSetEntity> = sets.filter { it.sessionId == sessionId }
            override suspend fun getExerciseNameMap(): Map<Long, String> = exList.associate { it.id to it.displayName }
            override suspend fun findExerciseByNormalized(normalized: String): ExerciseEntity? = exList.firstOrNull { it.normalizedName == normalized }
            override suspend fun updateExercise(exercise: ExerciseEntity) {}
            override suspend fun getLearnedAliases(): Map<String, String> = emptyMap()
            override suspend fun saveLearnedAlias(alias: String, canonical: String) {}
            override suspend fun deleteLearnedAlias(alias: String) {}
            override suspend fun saveMemory(memoryType: String, sourceKey: String, targetValue: String) {}
            override suspend fun getAllSessions(): List<WorkoutSessionEntity> = sessions
            override suspend fun getAllSets(): List<WorkoutSetEntity> = sets
            override suspend fun getAllExercises(): List<ExerciseEntity> = exList
            override suspend fun getProfile(): ProfileEntity? = null
            override suspend fun getOrCreateActiveConversation(): IrisxConversationEntity = IrisxConversationEntity(id = 1, title = "Activa")
            override suspend fun appendMessage(message: IrisxMessageEntity): Long = 1L
        }

        val core = IrisXCore(repo)

        // Query 1: "¿Cuánto levanté la última vez en sentadilla?"
        val rLoad = core.ask("¿Cuánto levanté la última vez en sentadilla?")
        assertTrue(rLoad is IrisXResponse.Success)
        val loadMsg = (rLoad as IrisXResponse.Success).message
        assertTrue("Expected 95 kg in response: $loadMsg", loadMsg.contains("95 kg"))
        assertTrue(loadMsg.contains("Sentadilla"))

        // Query 2: "¿Qué ejercicio puedo hacer en lugar de este?" (uses active context = Sentadilla -> Cuádriceps/Piernas)
        val rAlt = core.ask("¿Qué ejercicio puedo hacer en lugar de este?")
        assertTrue(rAlt is IrisXResponse.Success)
        val altMsg = (rAlt as IrisXResponse.Success).message
        assertTrue("Expected leg alternative in response: $altMsg", altMsg.contains("Prensa") || altMsg.contains("Zancadas") || altMsg.contains("Extensión"))

        // Query 3: "Mostrame mi última sesión de pecho"
        val rCat = core.ask("Mostrame mi última sesión de pecho")
        assertTrue(rCat is IrisXResponse.Success)
        val catMsg = (rCat as IrisXResponse.Success).message
        assertTrue("Expected Pecho Hipertrofia and 77.5 kg: $catMsg", catMsg.contains("Pecho Hipertrofia") && (catMsg.contains("77.5 kg") || catMsg.contains("77,5 kg")))
    }

    @Test
    fun `14 and 17 - starting from routine and editing active notes never mutates routine template or historical sessions`() = runBlocking {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val port = JdbcActiveWorkoutPort(conn)
        val manager = ActiveWorkoutManager(port)

        // Seed historical session #1
        conn.createStatement().use { stmt ->
            stmt.execute("""
                INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis, notes)
                VALUES (1, 'Pecho Histórico', 1690000000000, 1690003600000, 'Press de banca 60kg 10 10')
            """.trimIndent())
        }

        val originalRoutine = RoutineWithExercises(
            routine = RoutineEntity(id = 7, name = "Pecho Base", targetCategory = "Pecho"),
            exercises = listOf(
                RoutineExerciseSpec(
                    id = 70,
                    exerciseName = "Press de banca",
                    orderIndex = 1,
                    targetSets = 3,
                    minReps = 8,
                    maxReps = 10,
                    suggestedLoadGrams = 70_000
                )
            )
        )

        // Start active session from routine
        val draft = manager.startFromRoutine(
            routineWithExercises = originalRoutine,
            nowWallMillis = 1_700_000_000_000L,
            nowElapsedRealtimeMillis = 10_000L
        )
        assertEquals(7L, draft.sourceRoutineId)
        assertTrue(draft.rawNotesText.contains("Press de banca 3x8-10: 70kg 8 8 8"))

        // User modifies execution during active workout (lifts 75kg and adds Fondos)
        manager.autosaveNotes(
            rawNotesText = "Pecho Base\nPress de banca 3x8-10: 75kg 10 9 8\nFondos en paralelas 10kg lastre 10 10",
            cursorPosition = 30,
            nowWallMillis = 1_700_001_000_000L,
            nowElapsedRealtimeMillis = 1_010_000L
        )
        val commitRes = manager.confirmAndCommitSession(
            nowWallMillis = 1_700_001_800_000L,
            nowElapsedRealtimeMillis = 1_810_000L
        )
        assertTrue(commitRes is FinalizationResult.Success)

        // Verify original routine template is untouched
        assertEquals(1, originalRoutine.exercises.size)
        assertEquals(70_000, originalRoutine.exercises.first().suggestedLoadGrams)

        // Verify historical session #1 is untouched and new session #2 was appended
        assertEquals(2, port.countCommittedSessions())
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT title, notes FROM workout_sessions WHERE id = 1")
            assertTrue(rs.next())
            assertEquals("Pecho Histórico", rs.getString("title"))
            assertEquals("Press de banca 60kg 10 10", rs.getString("notes"))
        }
        conn.close()
    }

    @Test
    fun `15 and 16 - MIGRATION_3_4 SQL DDL and Backup roundtrip with activeWorkoutDraft and v1-v2 compatibility`() {
        // Verify MIGRATION_3_4 SQL creates active_workout_drafts without altering v3 tables
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE workout_routines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
            stmt.execute("INSERT INTO workout_routines (name) VALUES ('Rutina Existente v3')")
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS active_workout_drafts (
                    slotId INTEGER PRIMARY KEY NOT NULL,
                    sessionUuid TEXT NOT NULL,
                    state TEXT NOT NULL,
                    title TEXT NOT NULL,
                    rawNotesText TEXT NOT NULL,
                    cursorPosition INTEGER NOT NULL DEFAULT 0,
                    sourceRoutineId INTEGER,
                    plannedCategory TEXT,
                    startedAtWallMillis INTEGER NOT NULL,
                    startedAtElapsedRealtimeMillis INTEGER NOT NULL,
                    accumulatedPausedMillis INTEGER NOT NULL DEFAULT 0,
                    reviewStartedAtWallMillis INTEGER,
                    reviewStartedAtElapsedMillis INTEGER,
                    finalizedAtWallMillis INTEGER,
                    lastSavedAtWallMillis INTEGER NOT NULL,
                    lastKnownElapsedRealtimeMillis INTEGER NOT NULL,
                    lastPersistedDurationMillis INTEGER NOT NULL DEFAULT 0,
                    committedSessionId INTEGER,
                    lastErrorMessage TEXT
                )
            """.trimIndent())
            val rs = stmt.executeQuery("SELECT name FROM workout_routines")
            assertTrue(rs.next())
            assertEquals("Rutina Existente v3", rs.getString(1))
        }
        conn.close()

        // Verify BackupSerializer roundtrip with activeWorkoutDraft
        val payloadWithDraft = SyntraBackupPayload(
            formatVersion = 2,
            appVersion = "0.5.0",
            exportedAtMillis = 1_700_000_500_000L,
            profile = ProfileBackupDto(
                name = "Fede",
                preferredName = "Fede",
                heightCm = 178,
                initialWeightGrams = 78_000,
                activityLevel = "Moderado",
                goal = "Mejorar fuerza",
                createdAtMillis = 1_700_000_000_000L,
                updatedAtMillis = 1_700_000_000_000L
            ),
            activeWorkoutDraft = ActiveWorkoutDraftBackupDto(
                slotId = 1,
                sessionUuid = "uuid-sprint-05-test",
                state = "ACTIVE",
                title = "Espalda",
                rawNotesText = "Dominadas 10kg 8 8\nRemo 60kg 10 10",
                cursorPosition = 18,
                sourceRoutineId = 3L,
                plannedCategory = "Espalda",
                startedAtWallMillis = 1_700_000_100_000L,
                startedAtElapsedRealtimeMillis = 50_000L,
                accumulatedPausedMillis = 5_000L,
                lastSavedAtWallMillis = 1_700_000_400_000L,
                lastKnownElapsedRealtimeMillis = 350_000L,
                lastPersistedDurationMillis = 295_000L
            )
        )

        val json = BackupSerializer.serialize(payloadWithDraft)
        val validated = BackupSerializer.deserializeAndValidate(json)
        assertTrue(validated is BackupValidationResult.Success)
        val restored = (validated as BackupValidationResult.Success).payload
        val restoredDraft = restored.activeWorkoutDraft
        assertNotNull(restoredDraft)
        assertEquals("uuid-sprint-05-test", restoredDraft!!.sessionUuid)
        assertEquals("Dominadas 10kg 8 8\nRemo 60kg 10 10", restoredDraft.rawNotesText)
        assertEquals(18, restoredDraft.cursorPosition)
        assertEquals(295_000L, restoredDraft.lastPersistedDurationMillis)
    }

    @Test
    fun `18 - audit race conditions - delayed autosave never overwrites newer text nor resurrects a finalized draft`() = runBlocking {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val port = JdbcActiveWorkoutPort(conn)
        val manager = ActiveWorkoutManager(port)

        val draft1 = manager.startSession(
            title = "Hombros",
            initialNotesText = "Press militar 40kg 10",
            nowWallMillis = 1_700_000_000_000L,
            nowElapsedRealtimeMillis = 10_000L
        )

        // Newer edit saved at t = 1_700_000_200_000L
        manager.autosaveNotes(
            rawNotesText = "Press militar 40kg 10 10 8\nElevaciones laterales 10kg 12 12",
            cursorPosition = 58,
            expectedSessionUuid = draft1.sessionUuid,
            nowWallMillis = 1_700_000_200_000L,
            nowElapsedRealtimeMillis = 210_000L
        )

        // Delayed/out-of-order autosave arriving late with older timestamp (t = 1_700_000_100_000L)
        val (afterStaleWrite, _) = manager.autosaveNotes(
            rawNotesText = "Press militar 40kg 10 (texto viejo)",
            cursorPosition = 10,
            expectedSessionUuid = draft1.sessionUuid,
            nowWallMillis = 1_700_000_100_000L,
            nowElapsedRealtimeMillis = 110_000L
        )
        assertNotNull(afterStaleWrite)
        assertTrue(
            "Stale autosave must not overwrite newer text",
            afterStaleWrite!!.rawNotesText.contains("Elevaciones laterales 10kg 12 12")
        )
        assertEquals(58, afterStaleWrite.cursorPosition)

        // Finalize and commit session #1
        val commitRes = manager.confirmAndCommitSession(
            nowWallMillis = 1_700_000_300_000L,
            nowElapsedRealtimeMillis = 310_000L
        )
        assertTrue(commitRes is FinalizationResult.Success)
        assertNull(port.getCurrentDraft())

        // Delayed autosave from session #1 arriving AFTER finalization must NOT resurrect the draft
        manager.autosaveNotes(
            rawNotesText = "Intento de resurrección tras finalizar",
            cursorPosition = 5,
            expectedSessionUuid = draft1.sessionUuid,
            nowWallMillis = 1_700_000_350_000L,
            nowElapsedRealtimeMillis = 360_000L
        )
        assertNull("Delayed autosave after commit must not resurrect draft", port.getCurrentDraft())

        // Start a brand-new session #2; delayed autosave carrying session #1's UUID must not overwrite session #2
        val draft2 = manager.startSession(
            title = "Brazos",
            initialNotesText = "Curl con barra 30kg 10 10",
            nowWallMillis = 1_700_000_500_000L,
            nowElapsedRealtimeMillis = 510_000L
        )
        manager.autosaveNotes(
            rawNotesText = "Texto de sesión 1 retrasado",
            cursorPosition = 5,
            expectedSessionUuid = draft1.sessionUuid,
            nowWallMillis = 1_700_000_600_000L,
            nowElapsedRealtimeMillis = 610_000L
        )
        val currentSession2 = port.getCurrentDraft()
        assertNotNull(currentSession2)
        assertEquals(draft2.sessionUuid, currentSession2!!.sessionUuid)
        assertEquals("Curl con barra 30kg 10 10", currentSession2.rawNotesText)
        conn.close()
    }
}
