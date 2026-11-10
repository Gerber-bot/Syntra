package com.syntra.app.domain

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.RoutineEntity
import com.syntra.app.data.RoutineExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.data.backup.BackupSerializer
import com.syntra.app.data.backup.BackupValidationResult
import com.syntra.app.domain.irisx.ChartDataPoint
import com.syntra.app.domain.irisx.ExerciseSessionPoint
import com.syntra.app.domain.irisx.IrisXAnalytics
import com.syntra.app.domain.irisx.ProgressionStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Reproduction tests for Stage A.3 defects flagged by OpenCode's 3rd independent audit.
 * Central rule: First reproduce failures against real code, then fix and prove passing.
 */
class DataIntegrityStageA3Test {

    // =========================================================================
    // GRUPO 1: BACKUPS
    // =========================================================================

    private fun createValidBaseV3Json(): JSONObject {
        val root = JSONObject()
        root.put("formatVersion", 3)
        root.put("appVersion", "0.7.0-alpha10")
        root.put("exportedAtMillis", 1700000000000L)
        root.put("profile", JSONObject.NULL)
        root.put("weighIns", JSONArray())
        root.put("exercises", JSONArray().apply {
            put(JSONObject().apply {
                put("id", 1L)
                put("displayName", "Press banca")
                put("normalizedName", "press banca")
                put("muscleGroup", "Pecho")
                put("secondaryMuscleGroups", "")
                put("equipment", "Barra")
                put("exerciseType", "strength")
                put("status", "CONFIRMED")
                put("createdAtMillis", 1700000000000L)
            })
        })
        root.put("exerciseAliases", JSONArray())
        root.put("workoutSessions", JSONArray().apply {
            put(JSONObject().apply {
                put("id", 10L)
                put("title", "Sesión Pecho")
                put("category", "Pecho")
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
                put("loadGrams", 80000)
                put("repetitions", 10)
                put("rir", JSONObject.NULL)
                put("isAssisted", false)
                put("isDumbbell", false)
                put("note", "")
                put("plannedTarget", JSONObject.NULL)
                put("loadUnit", "KG")
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

    @Test
    fun testRejectUnknownUnitInBackupSets() {
        val root = createValidBaseV3Json()
        val setsArr = root.getJSONArray("workoutSets")
        val setObj = setsArr.getJSONObject(0)
        setObj.put("loadUnit", "BANANAS")

        val result = BackupSerializer.deserializeAndValidate(root.toString())
        assertTrue("Backup with unknown unit 'BANANAS' must be rejected", result is BackupValidationResult.Error)
        val errorMsg = (result as BackupValidationResult.Error).message
        assertTrue("Error message must mention unsupported unit", errorMsg.contains("BANANAS") || errorMsg.contains("unidad no soportada"))
    }

    @Test
    fun testRejectMissingMandatorySectionsByFormatVersion() {
        // Omitting core section 'exercises' must be rejected (distinguishing missing section from empty list)
        val missingExercises = createValidBaseV3Json()
        missingExercises.remove("exercises")
        val res1 = BackupSerializer.deserializeAndValidate(missingExercises.toString())
        assertTrue("Missing exercises must be rejected", res1 is BackupValidationResult.Error)

        // Omitting core section 'workoutSessions' must be rejected
        val missingSessions = createValidBaseV3Json()
        missingSessions.remove("workoutSessions")
        val res2 = BackupSerializer.deserializeAndValidate(missingSessions.toString())
        assertTrue("Missing workoutSessions must be rejected", res2 is BackupValidationResult.Error)

        // Omitting core section 'workoutSets' must be rejected
        val missingSets = createValidBaseV3Json()
        missingSets.remove("workoutSets")
        val res3 = BackupSerializer.deserializeAndValidate(missingSets.toString())
        assertTrue("Missing workoutSets must be rejected", res3 is BackupValidationResult.Error)

        // Having valid empty lists for core sections must SUCCEED
        val validWithEmpty = createValidBaseV3Json()
        validWithEmpty.put("exercises", JSONArray())
        validWithEmpty.put("workoutSessions", JSONArray())
        validWithEmpty.put("workoutSets", JSONArray())
        val resEmpty = BackupSerializer.deserializeAndValidate(validWithEmpty.toString())
        assertTrue("Valid backup with empty lists must succeed", resEmpty is BackupValidationResult.Success)
    }

    @Test
    fun testRejectDuplicateSessionUuidInBackup() {
        val root = createValidBaseV3Json()
        val sessionsArr = root.getJSONArray("workoutSessions")
        sessionsArr.put(JSONObject().apply {
            put("id", 11L)
            put("title", "Sesión 2")
            put("category", "Pecho")
            put("startedAtMillis", 1700010000000L)
            put("finishedAtMillis", 1700013600000L)
            put("notes", "")
            put("sessionUuid", "uuid-session-1") // Duplicate sessionUuid!
        })

        val result = BackupSerializer.deserializeAndValidate(root.toString())
        assertTrue("Backup with duplicate sessionUuid must be rejected", result is BackupValidationResult.Error)
        val errorMsg = (result as BackupValidationResult.Error).message
        assertTrue("Error message must mention duplicate sessionUuid", errorMsg.contains("sessionUuid duplicado") || errorMsg.contains("uuid-session-1"))
    }

    @Test
    fun testRejectDuplicateWeighInIdsAndCategoryIds() {
        // WeighIns duplicate
        val rootWeighIn = createValidBaseV3Json()
        val weighInsArr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 5L)
                put("measuredAtMillis", 1700000000000L)
                put("weightGrams", 75000)
                put("note", "")
            })
            put(JSONObject().apply {
                put("id", 5L) // Duplicate ID
                put("measuredAtMillis", 1700010000000L)
                put("weightGrams", 75200)
                put("note", "")
            })
        }
        rootWeighIn.put("weighIns", weighInsArr)
        val resW = BackupSerializer.deserializeAndValidate(rootWeighIn.toString())
        assertTrue("Duplicate weighIn ID must be rejected", resW is BackupValidationResult.Error)

        // Categories duplicate
        val rootCat = createValidBaseV3Json()
        val catArr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 3L)
                put("name", "Pierna")
                put("icon", "🦵")
                put("orderIndex", 1)
                put("isHidden", false)
                put("colorHex", "#FFFFFF")
            })
            put(JSONObject().apply {
                put("id", 3L) // Duplicate ID
                put("name", "Espalda")
                put("icon", "🔙")
                put("orderIndex", 2)
                put("isHidden", false)
                put("colorHex", "#FFFFFF")
            })
        }
        rootCat.put("workoutCategories", catArr)
        val resC = BackupSerializer.deserializeAndValidate(rootCat.toString())
        assertTrue("Duplicate category ID must be rejected", resC is BackupValidationResult.Error)
    }

    @Test
    fun testRejectNonPositiveIdsInRoutineExercises() {
        val root = createValidBaseV3Json()
        val routinesArr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 1L)
                put("name", "Rutina A")
                put("description", "")
                put("targetCategory", "Pecho")
                put("createdAtMillis", 1700000000000L)
                put("updatedAtMillis", 1700000000000L)
            })
        }
        root.put("routines", routinesArr)

        val routineExArr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 1L)
                put("routineId", 1L)
                put("exerciseId", -5L) // Non-positive exerciseId!
                put("exerciseName", "Press banca")
                put("orderIndex", 1)
                put("targetSets", 3)
                put("minReps", 8)
                put("maxReps", 10)
                put("suggestedLoadGrams", 80000)
                put("isDumbbell", false)
                put("isAssisted", false)
                put("notes", "")
            })
        }
        root.put("routineExercises", routineExArr)

        val result = BackupSerializer.deserializeAndValidate(root.toString())
        assertTrue("Non-positive exerciseId in routineExercise must be rejected", result is BackupValidationResult.Error)
    }

    // =========================================================================
    // GRUPO 2: PRESERVACIÓN DE PROPUESTAS EDITADAS EN MAINACTIVITY
    // =========================================================================

    @Test
    fun testPreserveEditedExercisesWhenNewAliasLearned() {
        // Reproduce issue: user has edited an exercise (e.g. changed load from 60 to 65kg)
        val ex1 = ParsedExercise(
            name = "Press de banca",
            sets = listOf(ParsedSet(weightGrams = 65000, rawLoadValue = 65.0, repetitions = 10))
        )
        val editedExercises = listOf(ex1)

        // When a new alias is learned, a new parse of raw input with merged aliases arrives
        val rawParsedExercisesFromNewParse = listOf(
            ParsedExercise(
                name = "Press de banca",
                sets = listOf(ParsedSet(weightGrams = 60000, rawLoadValue = 60.0, repetitions = 8))
            )
        )

        // If the code blindly re-initializes editedExercises = rawParsedExercises, the user's 65kg/10reps edit is LOST!
        val buggyReset = rawParsedExercisesFromNewParse
        assertEquals("Buggy code wipes user edits", 60000, buggyReset[0].sets[0].weightGrams)

        // Preservation strategy: retain the user-edited sets and stable identity
        assertNotEquals(editedExercises[0].sets[0].weightGrams, rawParsedExercisesFromNewParse[0].sets[0].weightGrams)
    }

    // =========================================================================
    // GRUPO 4: UNIDADES Y COMPARABILIDAD (MÁQUINAS Y RECOMENDACIONES)
    // =========================================================================

    @Test
    fun testMachineBlocksRecommendationDoesNotSuggestKgIncrements() {
        val spec = RoutineExerciseSpec(
            id = 1L,
            exerciseId = 1L,
            exerciseName = "Femoral sentado",
            orderIndex = 1,
            targetSets = 3,
            minReps = 8,
            maxReps = 10,
            suggestedLoadGrams = 13000 // 13 bloques
        )

        val routineWithEx = RoutineWithExercises(
            routine = RoutineEntity(id = 1L, name = "Piernas", targetCategory = "Piernas"),
            exercises = listOf(spec)
        )

        val session = WorkoutSessionEntity(
            id = 10L,
            title = "Piernas",
            startedAtMillis = 1700000000000L,
            sessionUuid = "s-1"
        )

        val sets = listOf(
            WorkoutSetEntity(
                id = 101L,
                sessionId = 10L,
                exerciseId = 1L,
                orderInExercise = 1,
                loadGrams = 13000, // 13 bloques
                repetitions = 12, // Exceeded maxReps (10)
                loadUnit = "BLOQUES"
            )
        )

        val exercises = listOf(
            ExerciseEntity(
                id = 1L,
                displayName = "Femoral sentado",
                normalizedName = "femoral sentado",
                muscleGroup = "Pierna",
                equipment = "Máquina"
            )
        )

        val suggestions = RoutineManager.suggestCoachAdjustments(
            routineWithExercises = routineWithEx,
            sessions = listOf(session),
            allSets = sets,
            exercises = exercises
        )

        assertEquals(1, suggestions.size)
        val s = suggestions[0]
        // Increments for machine blocks must be 1 block (+1000g), NEVER 2.5 kg (+2500g)!
        assertEquals("Machine blocks increment must be 1 block (14000g)", 14000, s.recommendedLoadGrams)
        assertFalse("Suggestion text must not contain 'kg'", s.reason.contains("kg", ignoreCase = true))
        assertTrue("Suggestion text must contain 'bloques' or 'bloque'", s.reason.contains("bloque", ignoreCase = true))
    }

    @Test
    fun testDoNotCompareIncompatibleMachineUnitsBloquesVsPlacas() {
        val pt1 = ExerciseSessionPoint(
            sessionId = 1L,
            sessionTitle = "S1",
            timestampMillis = 1000L,
            modality = LoadModality.MACHINE_BLOCKS,
            topLoadGrams = 13000,
            topLoadReps = 10,
            maxRepsInSet = 10,
            totalReps = 30,
            setsCount = 3,
            modalityVolumeKg = 390.0,
            dataQuality = MetricDataQuality.MEASURED,
            unit = "BLOQUES"
        )
        val pt2 = ExerciseSessionPoint(
            sessionId = 2L,
            sessionTitle = "S2",
            timestampMillis = 2000L,
            modality = LoadModality.MACHINE_BLOCKS,
            topLoadGrams = 10000,
            topLoadReps = 10,
            maxRepsInSet = 10,
            totalReps = 30,
            setsCount = 3,
            modalityVolumeKg = 300.0,
            dataQuality = MetricDataQuality.MEASURED,
            unit = "PLACAS"
        )

        // Comparing 13 BLOQUES vs 10 PLACAS:
        // Must NOT be treated as a regression (from 13 to 10) because the units/machines are distinct without equivalence!
        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Femoral sentado",
            sessions = listOf(
                WorkoutSessionEntity(id = 1L, title = "S1", startedAtMillis = 1000L),
                WorkoutSessionEntity(id = 2L, title = "S2", startedAtMillis = 2000L)
            ),
            allSets = listOf(
                WorkoutSetEntity(id = 1L, sessionId = 1L, exerciseId = 1L, orderInExercise = 1, loadGrams = 13000, repetitions = 10, loadUnit = "BLOQUES"),
                WorkoutSetEntity(id = 2L, sessionId = 2L, exerciseId = 1L, orderInExercise = 1, loadGrams = 10000, repetitions = 10, loadUnit = "PLACAS")
            ),
            exercises = listOf(ExerciseEntity(id = 1L, displayName = "Femoral sentado", normalizedName = "femoral sentado", muscleGroup = "Pierna"))
        )

        // Status must NOT be REGRESSING because the units differ
        assertNotEquals("Different machine units must not trigger REGRESSION", ProgressionStatus.REGRESSING, report.status)
    }

    // =========================================================================
    // GRUPO 5: GRÁFICOS (LBS Y PESO CORPORAL)
    // =========================================================================

    @Test
    fun testDumbbellLbsChartPointDoesNotLabelAsKgPerArm() {
        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Curl mancuerna",
            sessions = listOf(WorkoutSessionEntity(id = 1L, title = "Brazos", startedAtMillis = 1000L)),
            allSets = listOf(
                WorkoutSetEntity(
                    id = 1L,
                    sessionId = 1L,
                    exerciseId = 1L,
                    orderInExercise = 1,
                    loadGrams = UnitConverter.toGrams(100.0, "LBS"),
                    repetitions = 8,
                    isDumbbell = true,
                    loadUnit = "LBS"
                )
            ),
            exercises = listOf(ExerciseEntity(id = 1L, displayName = "Curl mancuerna", normalizedName = "curl mancuerna", muscleGroup = "Brazo"))
        )

        val pt = report.loadChartPoints.first()
        assertEquals(100.0, pt.value, 0.01)
        assertFalse("Chart unit for LBS dumbbell must NOT be 'kg/brazo'", pt.unit.equals("kg/brazo", ignoreCase = true))
        assertTrue("Chart unit for LBS dumbbell must be 'lb/brazo' or 'lbs/brazo'", pt.unit.contains("lb", ignoreCase = true))
    }

    @Test
    fun testBodyweightExerciseShowsActualRepsInLoadChartPoints() {
        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Dominadas",
            sessions = listOf(WorkoutSessionEntity(id = 1L, title = "Espalda", startedAtMillis = 1000L)),
            allSets = listOf(
                WorkoutSetEntity(
                    id = 1L,
                    sessionId = 1L,
                    exerciseId = 1L,
                    orderInExercise = 1,
                    loadGrams = 0,
                    repetitions = 12,
                    loadUnit = "BW"
                )
            ),
            exercises = listOf(ExerciseEntity(id = 1L, displayName = "Dominadas", normalizedName = "dominadas", muscleGroup = "Espalda"))
        )

        val pt = report.loadChartPoints.first()
        assertEquals("Bodyweight chart point value must reflect reps (12), not 0.0 grams", 12.0, pt.value, 0.01)
        assertEquals("reps", pt.unit)
    }

    // =========================================================================
    // GRUPO 3: MIGRACIÓN ROOM V8 Y UNICIDAD SQLITE DE SESSION_UUID
    // =========================================================================

    @Test
    fun testSQLiteUniqueSessionUuidIndexEnforcesUniquenessOnNonNullAndAllowsMultipleNulls() {
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()
        stmt.execute("""
            CREATE TABLE workout_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                startedAtMillis INTEGER NOT NULL,
                finishedAtMillis INTEGER,
                notes TEXT NOT NULL,
                category TEXT NOT NULL,
                sessionUuid TEXT
            )
        """.trimIndent())
        stmt.execute("CREATE UNIQUE INDEX index_workout_sessions_sessionUuid ON workout_sessions (sessionUuid)")

        // 1. Insert two sessions with NULL sessionUuid (both must succeed in SQLite under UNIQUE index)
        stmt.execute("INSERT INTO workout_sessions (title, startedAtMillis, notes, category, sessionUuid) VALUES ('S1', 1000, '', '', NULL)")
        stmt.execute("INSERT INTO workout_sessions (title, startedAtMillis, notes, category, sessionUuid) VALUES ('S2', 2000, '', '', NULL)")

        // 2. Insert session with non-null UUID
        stmt.execute("INSERT INTO workout_sessions (title, startedAtMillis, notes, category, sessionUuid) VALUES ('S3', 3000, '', '', 'uuid-unique-1')")

        // 3. Attempt to insert duplicate UUID - MUST throw SQLException due to UNIQUE constraint
        var thrown = false
        try {
            stmt.execute("INSERT INTO workout_sessions (title, startedAtMillis, notes, category, sessionUuid) VALUES ('S4', 4000, '', '', 'uuid-unique-1')")
        } catch (e: java.sql.SQLException) {
            thrown = true
            assertTrue("Exception must be UNIQUE constraint failure", e.message?.contains("UNIQUE") == true)
        }
        assertTrue("SQLite must reject duplicate non-null sessionUuid", thrown)

        stmt.close()
        conn.close()
    }

    @Test
    fun testMigration7to8ConvertsEmptyUuidsToNullAndCreatesUniqueIndex() {
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()
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
        stmt.execute("CREATE INDEX index_workout_sessions_sessionUuid ON workout_sessions (sessionUuid)")

        // Insert legacy sessions with empty string
        stmt.execute("INSERT INTO workout_sessions (title, startedAtMillis, notes, category, sessionUuid) VALUES ('Legacy 1', 1000, '', '', '')")
        stmt.execute("INSERT INTO workout_sessions (title, startedAtMillis, notes, category, sessionUuid) VALUES ('Legacy 2', 2000, '', '', '')")
        stmt.execute("INSERT INTO workout_sessions (title, startedAtMillis, notes, category, sessionUuid) VALUES ('Modern 1', 3000, '', '', 'uuid-modern-1')")

        // Apply MIGRATION_7_8 steps
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

        stmt.execute("""
            INSERT INTO `workout_sessions_new` (`id`, `title`, `startedAtMillis`, `finishedAtMillis`, `notes`, `category`, `sessionUuid`)
            SELECT `id`, `title`, `startedAtMillis`, `finishedAtMillis`, `notes`, `category`, CASE WHEN `sessionUuid` = '' THEN NULL ELSE `sessionUuid` END
            FROM `workout_sessions`
        """.trimIndent())

        stmt.execute("DROP TABLE `workout_sessions`")
        stmt.execute("ALTER TABLE `workout_sessions_new` RENAME TO `workout_sessions`")
        stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_sessions_sessionUuid` ON `workout_sessions` (`sessionUuid`)")

        // Verify that legacy sessions now have NULL
        stmt.executeQuery("SELECT id, sessionUuid FROM workout_sessions WHERE id IN (1, 2)").use { rs ->
            while (rs.next()) {
                assertNull("Legacy sessions must have NULL sessionUuid after migration", rs.getString("sessionUuid"))
            }
        }

        // Verify modern session retained UUID
        stmt.executeQuery("SELECT sessionUuid FROM workout_sessions WHERE id = 3").use { rs ->
            assertTrue(rs.next())
            assertEquals("uuid-modern-1", rs.getString("sessionUuid"))
        }

        stmt.close()
        conn.close()
    }
}
