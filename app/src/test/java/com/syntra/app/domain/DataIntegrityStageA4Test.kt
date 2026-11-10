package com.syntra.app.domain

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.data.backup.BackupSerializer
import com.syntra.app.data.backup.BackupValidationResult
import com.syntra.app.domain.irisx.ExerciseSessionPoint
import com.syntra.app.domain.irisx.IrisXAnalytics
import com.syntra.app.domain.irisx.IrisXCoach
import com.syntra.app.domain.irisx.ProgressionStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager

/**
 * Reproduction tests for Stage A.4 defects flagged by OpenCode audit.
 * Central rule: First reproduce failures against real code, then fix and prove passing.
 */
class DataIntegrityStageA4Test {

    private fun createValidBaseV3Json(): JSONObject {
        val root = JSONObject()
        root.put("formatVersion", 3)
        root.put("appVersion", "0.7.0-alpha11")
        root.put("exportedAtMillis", 1700000000000L)
        root.put("profile", JSONObject.NULL)
        root.put("weighIns", JSONArray())
        root.put("exercises", JSONArray().apply {
            put(JSONObject().apply {
                put("id", 1L)
                put("displayName", "Femoral sentado")
                put("normalizedName", "femoral sentado")
                put("muscleGroup", "Pierna")
                put("secondaryMuscleGroups", "")
                put("equipment", "Máquina")
                put("exerciseType", "strength")
                put("status", "CONFIRMED")
                put("createdAtMillis", 1700000000000L)
            })
        })
        root.put("exerciseAliases", JSONArray())
        root.put("workoutSessions", JSONArray().apply {
            put(JSONObject().apply {
                put("id", 10L)
                put("title", "Sesión Pierna")
                put("category", "Pierna")
                put("startedAtMillis", 1700000000000L)
                put("finishedAtMillis", 1700003600000L)
                put("notes", "")
                put("sessionUuid", "uuid-session-1")
            })
        })
        root.put("workoutSets", JSONArray().apply {
            put(JSONObject().apply {
                put("id", 100L)
                put("sessionId", 10L)
                put("exerciseId", 1L)
                put("orderInExercise", 1)
                put("executionOrder", 1)
                put("loadGrams", 13000)
                put("repetitions", 12)
                put("rir", JSONObject.NULL)
                put("isAssisted", false)
                put("isDumbbell", false)
                put("note", "")
                put("plannedTarget", JSONObject.NULL)
                put("loadUnit", "BLOQUES")
                put("isToFailure", false)
            })
        })
        root.put("irisxMemories", JSONArray())
        root.put("irisxConversations", JSONArray())
        root.put("irisxMessages", JSONArray())
        root.put("routines", JSONArray())
        root.put("routineExercises", JSONArray())
        root.put("activeWorkoutDraft", JSONObject.NULL)
        root.put("workoutCategories", JSONArray())
        return root
    }

    // =========================================================================
    // 1. BACKUPS INCOMPLETOS
    // =========================================================================

    @Test
    fun testRejectBackupWithoutWeighInsForFullRestore() {
        val root = createValidBaseV3Json()
        root.remove("weighIns") // Section completely absent

        val result = BackupSerializer.deserializeAndValidate(root.toString())
        assertTrue("Backup missing 'weighIns' section must be rejected for full restore", result is BackupValidationResult.Error)
        val msg = (result as BackupValidationResult.Error).message
        assertTrue("Error message must indicate missing 'weighIns'", msg.contains("weighIns"))
    }

    @Test
    fun testRejectBackupWithNullWeighIns() {
        val root = createValidBaseV3Json()
        root.put("weighIns", JSONObject.NULL) // Section is null

        val result = BackupSerializer.deserializeAndValidate(root.toString())
        assertTrue("Backup with null 'weighIns' must be rejected", result is BackupValidationResult.Error)
        val msg = (result as BackupValidationResult.Error).message
        assertTrue("Error message must indicate invalid/null 'weighIns'", msg.contains("weighIns"))
    }

    @Test
    fun testAcceptBackupWithExplicitEmptyWeighIns() {
        val root = createValidBaseV3Json()
        root.put("weighIns", JSONArray()) // Explicitly empty list

        val result = BackupSerializer.deserializeAndValidate(root.toString())
        assertTrue("Backup with explicitly empty 'weighIns: []' must succeed", result is BackupValidationResult.Success)
    }

    @Test
    fun testRejectSessionUuidsCollidingAfterTrim() {
        val root = createValidBaseV3Json()
        val sessionsArr = root.getJSONArray("workoutSessions")
        sessionsArr.put(JSONObject().apply {
            put("id", 11L)
            put("title", "Sesión 2")
            put("category", "Pierna")
            put("startedAtMillis", 1700010000000L)
            put("finishedAtMillis", 1700013600000L)
            put("notes", "")
            put("sessionUuid", " uuid-session-1 ") // Untrimmed duplicate of "uuid-session-1"
        })

        val result = BackupSerializer.deserializeAndValidate(root.toString())
        assertTrue("UUIDs colliding after trim must be rejected", result is BackupValidationResult.Error)
        val msg = (result as BackupValidationResult.Error).message
        assertTrue(msg.contains("sessionUuid duplicado"))
    }

    @Test
    fun testRejectDuplicateIrisXMemoryIds() {
        val root = createValidBaseV3Json()
        val memArr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 42L)
                put("memoryType", "ALIAS")
                put("sourceKey", "banca")
                put("targetValue", "Press de banca")
                put("confidence", 1.0)
            })
            put(JSONObject().apply {
                put("id", 42L) // Duplicate ID!
                put("memoryType", "ALIAS")
                put("sourceKey", "rdl")
                put("targetValue", "Peso muerto rumano")
                put("confidence", 1.0)
            })
        }
        root.put("irisxMemories", memArr)

        val result = BackupSerializer.deserializeAndValidate(root.toString())
        assertTrue("Duplicate irisx memory ID must be rejected", result is BackupValidationResult.Error)
        val msg = (result as BackupValidationResult.Error).message
        assertTrue("Error message must mention duplicate memory ID", msg.contains("memoria") && msg.contains("duplicado"))
    }

    // =========================================================================
    // 2. MIGRACIÓN ROOM CON UUIDs DUPLICADOS
    // =========================================================================

    @Test
    fun testMigration7to8ResolvesDuplicateUuidsDeterministicallyWithoutDroppingRows() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()

        // Create Room v7 schema
        stmt.execute("""
            CREATE TABLE workout_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                startedAtMillis INTEGER NOT NULL,
                finishedAtMillis INTEGER,
                notes TEXT NOT NULL,
                category TEXT NOT NULL,
                sessionUuid TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        stmt.execute("""
            CREATE TABLE workout_sets (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER NOT NULL,
                exerciseId INTEGER NOT NULL,
                orderInExercise INTEGER NOT NULL,
                executionOrder INTEGER NOT NULL,
                loadGrams INTEGER NOT NULL,
                repetitions INTEGER NOT NULL,
                rir INTEGER,
                isAssisted INTEGER NOT NULL,
                isDumbbell INTEGER NOT NULL,
                note TEXT NOT NULL,
                plannedTarget TEXT,
                loadUnit TEXT NOT NULL,
                isToFailure INTEGER NOT NULL
            )
        """.trimIndent())
        stmt.execute("CREATE INDEX index_workout_sessions_sessionUuid ON workout_sessions (sessionUuid)")

        // Populate v7 with:
        // 1. Two historical sessions with empty UUID
        stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, notes, category, sessionUuid) VALUES (1, 'Legacy A', 1000, '', '', '')")
        stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, notes, category, sessionUuid) VALUES (2, 'Legacy B', 2000, '', '', '')")
        // 2. A session with unique UUID
        stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, notes, category, sessionUuid) VALUES (3, 'Unique C', 3000, '', '', 'uuid-c')")
        // 3. TWO sessions with identical duplicate UUID!
        stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, notes, category, sessionUuid) VALUES (4, 'Dup D1', 4000, '', '', 'uuid-dup-shared')")
        stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, notes, category, sessionUuid) VALUES (5, 'Dup D2', 5000, '', '', 'uuid-dup-shared')")

        // Sets attached to each session
        for (sid in 1..5) {
            stmt.execute("INSERT INTO workout_sets (sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions, note, loadUnit, isToFailure, isAssisted, isDumbbell) VALUES ($sid, 1, 1, 1, 50000, 10, '', 'KG', 0, 0, 0)")
        }

        // Apply our upgraded deterministic migration logic
        // 1. Create table with nullable sessionUuid
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `workout_sessions_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `startedAtMillis` INTEGER NOT NULL,
                `finishedAtMillis` INTEGER,
                `notes` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `sessionUuid` TEXT
            )
        """.trimIndent())

        // 2. Insert with deterministic UUID deduplication:
        // - Empty/blank -> NULL
        // - If duplicate non-empty UUID, keep UUID for min(id), suffix subsequent rows with _dup_<id>
        stmt.execute("""
            INSERT INTO `workout_sessions_new` (`id`, `title`, `startedAtMillis`, `finishedAtMillis`, `notes`, `category`, `sessionUuid`)
            SELECT 
                s.id, 
                s.title, 
                s.startedAtMillis, 
                s.finishedAtMillis, 
                s.notes, 
                s.category,
                CASE 
                    WHEN trim(s.sessionUuid) = '' THEN NULL
                    WHEN s.id = (
                        SELECT min(d.id) 
                        FROM workout_sessions d 
                        WHERE trim(d.sessionUuid) = trim(s.sessionUuid)
                    ) THEN trim(s.sessionUuid)
                    ELSE trim(s.sessionUuid) || '_dup_' || s.id
                END
            FROM `workout_sessions` s
        """.trimIndent())

        stmt.execute("DROP TABLE `workout_sessions`")
        stmt.execute("ALTER TABLE `workout_sessions_new` RENAME TO `workout_sessions`")
        // 3. Create UNIQUE INDEX - MUST NOT FAIL!
        stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_sessions_sessionUuid` ON `workout_sessions` (`sessionUuid`)")

        // Verify total row count preserved
        stmt.executeQuery("SELECT count(*) FROM workout_sessions").use { rs ->
            assertTrue(rs.next())
            assertEquals(5, rs.getInt(1))
        }

        // Verify sets count preserved and relationships intact
        stmt.executeQuery("SELECT count(*) FROM workout_sets").use { rs ->
            assertTrue(rs.next())
            assertEquals(5, rs.getInt(1))
        }

        // Verify legacy empty uuids became NULL
        stmt.executeQuery("SELECT sessionUuid FROM workout_sessions WHERE id IN (1, 2)").use { rs ->
            while (rs.next()) {
                assertNull(rs.getString("sessionUuid"))
            }
        }

        // Verify session 4 kept original UUID
        stmt.executeQuery("SELECT sessionUuid FROM workout_sessions WHERE id = 4").use { rs ->
            assertTrue(rs.next())
            assertEquals("uuid-dup-shared", rs.getString("sessionUuid"))
        }

        // Verify session 5 got deterministic unique disambiguated UUID
        stmt.executeQuery("SELECT sessionUuid FROM workout_sessions WHERE id = 5").use { rs ->
            assertTrue(rs.next())
            assertEquals("uuid-dup-shared_dup_5", rs.getString("sessionUuid"))
        }

        stmt.close()
        conn.close()
    }

    // =========================================================================
    // 3. RECOMENDACIONES DEPORTIVAS (IRISX COACH)
    // =========================================================================

    @Test
    fun testIrisXCoachDoesNotRecommendKgForMachineBlocks() {
        val exercises = listOf(
            ExerciseEntity(
                id = 1L,
                displayName = "Femoral sentado",
                normalizedName = "femoral sentado",
                muscleGroup = "Pierna",
                equipment = "Máquina"
            )
        )

        val sessions = listOf(
            WorkoutSessionEntity(id = 1L, title = "Pierna 1", startedAtMillis = 1000L),
            WorkoutSessionEntity(id = 2L, title = "Pierna 2", startedAtMillis = 2000L)
        )

        // Two sessions doing 13 bloques x 12 reps
        val sets = listOf(
            WorkoutSetEntity(id = 1L, sessionId = 1L, exerciseId = 1L, orderInExercise = 1, loadGrams = 13000, repetitions = 12, loadUnit = "BLOQUES"),
            WorkoutSetEntity(id = 2L, sessionId = 2L, exerciseId = 1L, orderInExercise = 1, loadGrams = 13000, repetitions = 12, loadUnit = "BLOQUES")
        )

        val recommendations = IrisXCoach.generateRecommendations(
            profile = null,
            sessions = sessions,
            allSets = sets,
            exercises = exercises,
            targetExerciseOrMuscle = "Femoral sentado"
        )

        val loadRec = recommendations.firstOrNull { it.category == com.syntra.app.domain.irisx.RecommendationCategory.LOAD_PROGRESSION }
        assertNotNull("Coach must generate a load progression recommendation", loadRec)
        // Must NEVER propose 15,5 kg or contain "kg" for machine blocks!
        assertFalse("Coach proposal must not contain 'kg'", loadRec!!.proposal.contains("kg", ignoreCase = true))
        assertFalse("Coach proposal must not contain '15,5'", loadRec.proposal.contains("15,5"))
        assertTrue("Coach proposal must recommend 14 bloques", loadRec.proposal.contains("14") && loadRec.proposal.contains("bloque", ignoreCase = true))
    }

    @Test
    fun testIrisXCoachDoesNotMixDifferentExercisesByPartialNameMatch() {
        val exercises = listOf(
            ExerciseEntity(id = 1L, displayName = "Press militar", normalizedName = "press militar", muscleGroup = "Hombro"),
            ExerciseEntity(id = 2L, displayName = "Press banca", normalizedName = "press banca", muscleGroup = "Pecho")
        )

        val sessions = listOf(
            WorkoutSessionEntity(id = 1L, title = "Torso 1", startedAtMillis = 1000L),
            WorkoutSessionEntity(id = 2L, title = "Torso 2", startedAtMillis = 2000L)
        )

        val sets = listOf(
            WorkoutSetEntity(id = 1L, sessionId = 1L, exerciseId = 1L, orderInExercise = 1, loadGrams = 50000, repetitions = 10, loadUnit = "KG"),
            WorkoutSetEntity(id = 2L, sessionId = 2L, exerciseId = 1L, orderInExercise = 1, loadGrams = 50000, repetitions = 10, loadUnit = "KG"),
            WorkoutSetEntity(id = 3L, sessionId = 1L, exerciseId = 2L, orderInExercise = 2, loadGrams = 80000, repetitions = 10, loadUnit = "KG"),
            WorkoutSetEntity(id = 4L, sessionId = 2L, exerciseId = 2L, orderInExercise = 2, loadGrams = 80000, repetitions = 10, loadUnit = "KG")
        )

        // When requesting specifically for "Press militar", it must NOT produce recommendations for "Press banca"
        val recommendations = IrisXCoach.generateRecommendations(
            profile = null,
            sessions = sessions,
            allSets = sets,
            exercises = exercises,
            targetExerciseOrMuscle = "Press militar"
        )

        val hasPressBanca = recommendations.any { it.proposal.contains("Press banca", ignoreCase = true) || it.id.contains("rec_prog_2") }
        assertFalse("Recommendations for 'Press militar' must not mix in 'Press banca'", hasPressBanca)
    }

    // =========================================================================
    // 4. FALSO ESTANCAMIENTO CON UNIDADES INCOMPATIBLES (IRISX ANALYTICS)
    // =========================================================================

    @Test
    fun testSequence13Bloques10Placas9BloquesDoesNotReturnStagnant() {
        // Reproduce: 13 BLOQUES -> 10 PLACAS -> 9 BLOQUES
        val pt1 = ExerciseSessionPoint(
            sessionId = 1L, sessionTitle = "S1", timestampMillis = 1000L,
            modality = LoadModality.MACHINE_BLOCKS, topLoadGrams = 13000, topLoadReps = 10,
            maxRepsInSet = 10, totalReps = 30, setsCount = 3, modalityVolumeKg = 390.0,
            dataQuality = MetricDataQuality.MEASURED, unit = "BLOQUES"
        )
        val pt2 = ExerciseSessionPoint(
            sessionId = 2L, sessionTitle = "S2", timestampMillis = 2000L,
            modality = LoadModality.MACHINE_BLOCKS, topLoadGrams = 10000, topLoadReps = 10,
            maxRepsInSet = 10, totalReps = 30, setsCount = 3, modalityVolumeKg = 300.0,
            dataQuality = MetricDataQuality.MEASURED, unit = "PLACAS"
        )
        val pt3 = ExerciseSessionPoint(
            sessionId = 3L, sessionTitle = "S3", timestampMillis = 3000L,
            modality = LoadModality.MACHINE_BLOCKS, topLoadGrams = 9000, topLoadReps = 10,
            maxRepsInSet = 10, totalReps = 30, setsCount = 3, modalityVolumeKg = 270.0,
            dataQuality = MetricDataQuality.MEASURED, unit = "BLOQUES"
        )

        val stagnation = IrisXAnalytics.evaluateStagnation(
            exerciseName = "Femoral sentado",
            modality = LoadModality.MACHINE_BLOCKS,
            modalityHistory = listOf(pt1, pt2, pt3)
        )

        // MUST NOT be stagnant! Because intermediate unit was PLACAS while baseline was BLOQUES
        assertFalse("Mixed incompatible machine units must NOT trigger isStagnant = true", stagnation.isStagnant)
        assertEquals("When units in window are incompatible, quality must be INSUFFICIENT_DATA", MetricDataQuality.INSUFFICIENT_DATA, stagnation.dataQuality)
    }
}
