package com.syntra.app.domain

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import android.database.Cursor
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.MetricDataQuality
import com.syntra.app.domain.irisx.IrisXAnalytics
import com.syntra.app.domain.irisx.IrisXCoach
import com.syntra.app.domain.irisx.ProgressionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

class DataIntegrityStageA5Test {

    // Helper: Wraps a JDBC Connection into a SupportSQLiteDatabase Proxy
    private fun createSupportDatabaseProxy(conn: Connection): SupportSQLiteDatabase {
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args[0] as String
                    conn.createStatement().use { it.execute(sql) }
                    null
                }
                "query" -> {
                    val sql = args[0] as String
                    val stmt = conn.createStatement()
                    val rs = stmt.executeQuery(sql)
                    createCursorProxy(rs, stmt)
                }
                "compileStatement" -> {
                    val sql = args[0] as String
                    val prep = conn.prepareStatement(sql)
                    createStatementProxy(prep)
                }
                "isOpen" -> !conn.isClosed
                "inTransaction" -> !conn.autoCommit
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler
        ) as SupportSQLiteDatabase
    }

    private fun createStatementProxy(prep: java.sql.PreparedStatement): SupportSQLiteStatement {
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "bindLong" -> {
                    val idx = args[0] as Int
                    val value = args[1] as Long
                    prep.setLong(idx, value)
                    null
                }
                "bindString" -> {
                    val idx = args[0] as Int
                    val value = args[1] as String
                    prep.setString(idx, value)
                    null
                }
                "bindNull" -> {
                    val idx = args[0] as Int
                    prep.setNull(idx, Types.NULL)
                    null
                }
                "executeInsert" -> {
                    prep.executeUpdate().toLong()
                }
                "executeUpdateDelete" -> {
                    prep.executeUpdate()
                }
                "close" -> {
                    prep.close()
                    null
                }
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            SupportSQLiteStatement::class.java.classLoader,
            arrayOf(SupportSQLiteStatement::class.java),
            handler
        ) as SupportSQLiteStatement
    }

    private fun createCursorProxy(rs: ResultSet, stmt: java.sql.Statement): Cursor {
        val meta = rs.metaData
        val colMap = mutableMapOf<String, Int>()
        for (i in 1..meta.columnCount) {
            colMap[meta.getColumnLabel(i).lowercase()] = i - 1
        }

        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getColumnIndexOrThrow" -> {
                    val name = (args[0] as String).lowercase()
                    colMap[name] ?: throw IllegalArgumentException("Column $name not found")
                }
                "moveToNext" -> rs.next()
                "getLong" -> {
                    val idx = (args[0] as Int) + 1
                    rs.getLong(idx)
                }
                "getString" -> {
                    val idx = (args[0] as Int) + 1
                    rs.getString(idx)
                }
                "isNull" -> {
                    val idx = (args[0] as Int) + 1
                    rs.getObject(idx) == null
                }
                "close" -> {
                    rs.close()
                    stmt.close()
                    null
                }
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            Cursor::class.java.classLoader,
            arrayOf(Cursor::class.java),
            handler
        ) as Cursor
    }

    // =========================================================================
    // 1. MIGRATION_7_8 Tests (Executed via actual AppDatabase.MIGRATION_7_8 object)
    // =========================================================================

    @Test
    fun testRealMigration7_8ResolvesCollisionsWithExistingSuffixesAndEnsuresUniqueIndex() {
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

        // Populate table with the exact reproduction pattern reported by OpenCode:
        // ID 1: "root"
        // ID 2: " root " (untrimmed duplicate)
        // ID 3: "root_dup_2" (already exists in database before migration!)
        // ID 4: "root_dup_2_1" (chained collision!)
        // ID 5: "   " (blank whitespace -> must become NULL)
        // ID 6: "" (empty string -> must become NULL)
        stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, notes, category, sessionUuid) VALUES (1, 'S1', 1000, '', 'General', 'root')")
        stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, notes, category, sessionUuid) VALUES (2, 'S2', 2000, '', 'General', ' root ')")
        stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, notes, category, sessionUuid) VALUES (3, 'S3', 3000, '', 'General', 'root_dup_2')")
        stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, notes, category, sessionUuid) VALUES (4, 'S4', 4000, '', 'General', 'root_dup_2_1')")
        stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, notes, category, sessionUuid) VALUES (5, 'S5', 5000, '', 'General', '   ')")
        stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, notes, category, sessionUuid) VALUES (6, 'S6', 6000, '', 'General', '')")

        // Sets referencing session IDs
        for (sid in 1..6) {
            stmt.execute("INSERT INTO workout_sets (sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions, note, loadUnit, isToFailure, isAssisted, isDumbbell) VALUES ($sid, 1, 1, 1, 50000, 10, '', 'KG', 0, 0, 0)")
        }

        // EXECUTE REAL MIGRATION_7_8 OBJECT ON DB PROXY
        val dbProxy = createSupportDatabaseProxy(conn)
        AppDatabase.MIGRATION_7_8.migrate(dbProxy)

        // Verify UNIQUE constraint and values
        val rs = stmt.executeQuery("SELECT id, sessionUuid FROM workout_sessions ORDER BY id ASC")
        val results = mutableMapOf<Long, String?>()
        while (rs.next()) {
            results[rs.getLong("id")] = rs.getString("sessionUuid")
        }

        // 1. ID 1 kept original "root"
        assertEquals("root", results[1L])
        // 2. ID 3 kept original "root_dup_2"
        assertEquals("root_dup_2", results[3L])
        // 3. ID 4 kept original "root_dup_2_1"
        assertEquals("root_dup_2_1", results[4L])
        // 4. ID 2 generated collision-free substitute without colliding with ID 3 or ID 4:
        // Candidate 1: root_dup_2 (collides with ID 3) -> Candidate 2: root_dup_2_1 (collides with ID 4) -> Candidate 3: root_dup_2_2!
        assertEquals("root_dup_2_2", results[2L])
        // 5. Blank and empty UUIDs mapped to NULL
        assertNull("Blank UUID must be NULL", results[5L])
        assertNull("Empty UUID must be NULL", results[6L])

        // Verify all 6 session records and 6 set records preserved
        val countSessions = stmt.executeQuery("SELECT count(*) FROM workout_sessions").let { it.next(); it.getInt(1) }
        val countSets = stmt.executeQuery("SELECT count(*) FROM workout_sets").let { it.next(); it.getInt(1) }
        assertEquals(6, countSessions)
        assertEquals(6, countSets)

        // Verify UNIQUE index exists and functions: inserting duplicate non-null UUID fails
        var duplicateFailed = false
        try {
            stmt.execute("INSERT INTO workout_sessions (title, startedAtMillis, notes, category, sessionUuid) VALUES ('Dup', 7000, '', 'General', 'root')")
        } catch (e: Exception) {
            duplicateFailed = true
        }
        assertTrue("UNIQUE constraint must reject new duplicates", duplicateFailed)

        conn.close()
    }

    // =========================================================================
    // 2. Exercise & Equipment Isolation Tests (Analytics -> Coach)
    // =========================================================================

    @Test
    fun testTwoDifferentMachinesWithSameNameDoNotTriggerCoachProgression() {
        val exMachineA = ExerciseEntity(id = 10, displayName = "Prensa 45", normalizedName = "prensa 45", muscleGroup = "Piernas", equipment = "Máquina Matrix")
        val exMachineB = ExerciseEntity(id = 20, displayName = "Prensa 45", normalizedName = "prensa 45", muscleGroup = "Piernas", equipment = "Máquina Nautilus")

        val session1 = WorkoutSessionEntity(id = 1, title = "Sesión Pierna A", startedAtMillis = 100_000, notes = "", category = "Piernas")
        val session2 = WorkoutSessionEntity(id = 2, title = "Sesión Pierna B", startedAtMillis = 200_000, notes = "", category = "Piernas")

        // 1 session on Machine A (Matrix)
        val setA = WorkoutSetEntity(id = 1, sessionId = 1, exerciseId = 10, orderInExercise = 1, executionOrder = 1, loadGrams = 100_000, repetitions = 10, note = "", loadUnit = "KG")
        // 1 session on Machine B (Nautilus)
        val setB = WorkoutSetEntity(id = 2, sessionId = 2, exerciseId = 20, orderInExercise = 1, executionOrder = 1, loadGrams = 100_000, repetitions = 10, note = "", loadUnit = "KG")

        val allExercises = listOf(exMachineA, exMachineB)
        val allSessions = listOf(session1, session2)
        val allSets = listOf(setA, setB)

        // Run full productive Coach recommendation pipeline
        val recommendations = IrisXCoach.generateRecommendations(
            profile = null,
            sessions = allSessions,
            allSets = allSets,
            exercises = allExercises
        )

        // Neither machine should trigger LOAD_PROGRESSION since each machine has only 1 session!
        val progRecs = recommendations.filter { it.category == com.syntra.app.domain.irisx.RecommendationCategory.LOAD_PROGRESSION }
        assertTrue("Coach must NOT recommend load progression when each machine has only 1 session", progRecs.isEmpty())

        // Verify analytics directly: targeted progression analysis for Machine A
        val reportA = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Prensa 45",
            targetExerciseId = 10,
            sessions = allSessions,
            allSets = allSets,
            exercises = allExercises
        )
        assertEquals("Targeted report must contain only sets for Machine A", 1, reportA.history.size)
        assertEquals(ProgressionStatus.INSUFFICIENT_DATA, reportA.status)

        // If queried without targetExerciseId across ambiguous machines with distinct equipments:
        val reportAmbiguous = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Prensa 45",
            sessions = allSessions,
            allSets = allSets,
            exercises = allExercises
        )
        assertEquals("Ambiguous machines must return INSUFFICIENT_DATA", ProgressionStatus.INSUFFICIENT_DATA, reportAmbiguous.status)
    }

    @Test
    fun testPartialNameMatchesAreNeverAggregated() {
        val ex1 = ExerciseEntity(id = 1, displayName = "Prensa", normalizedName = "prensa", muscleGroup = "Piernas", equipment = "Máquina")
        val ex2 = ExerciseEntity(id = 2, displayName = "Prensa 45", normalizedName = "prensa 45", muscleGroup = "Piernas", equipment = "Máquina")

        val session1 = WorkoutSessionEntity(id = 1, title = "S1", startedAtMillis = 100_000, notes = "", category = "Piernas")
        val set1 = WorkoutSetEntity(id = 1, sessionId = 1, exerciseId = 2, orderInExercise = 1, executionOrder = 1, loadGrams = 120_000, repetitions = 10, note = "", loadUnit = "KG")

        // Analyze progression specifically for "Prensa" (ID 1)
        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Prensa",
            sessions = listOf(session1),
            allSets = listOf(set1),
            exercises = listOf(ex1, ex2)
        )
        // Must NOT match "Prensa 45" (exerciseId = 2) when analyzing "Prensa"!
        assertEquals(0, report.history.size)
        assertEquals(ProgressionStatus.INSUFFICIENT_DATA, report.status)
    }

    @Test
    fun testConfirmedAliasAggregatesSameEquipment() {
        val ex1 = ExerciseEntity(id = 1, displayName = "Press Banca Plano", normalizedName = "press banca plano", muscleGroup = "Pecho", equipment = "Barra")
        val exAlias = ExerciseEntity(id = 2, displayName = "Banca Plana", normalizedName = "banca plana", muscleGroup = "Pecho", equipment = "Barra")

        val s1 = WorkoutSessionEntity(id = 1, title = "S1", startedAtMillis = 100_000, notes = "", category = "Pecho")
        val s2 = WorkoutSessionEntity(id = 2, title = "S2", startedAtMillis = 200_000, notes = "", category = "Pecho")

        val set1 = WorkoutSetEntity(id = 1, sessionId = 1, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 80_000, repetitions = 10, note = "", loadUnit = "KG")
        val set2 = WorkoutSetEntity(id = 2, sessionId = 2, exerciseId = 2, orderInExercise = 1, executionOrder = 1, loadGrams = 80_000, repetitions = 10, note = "", loadUnit = "KG")

        val aliases = mapOf("banca plana" to "Press Banca Plano")

        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Press Banca Plano",
            targetExerciseId = 1,
            sessions = listOf(s1, s2),
            allSets = listOf(set1, set2),
            exercises = listOf(ex1, exAlias),
            learnedAliases = aliases
        )

        assertEquals("Confirmed alias with matching equipment must be aggregated", 2, report.history.size)
    }

    // =========================================================================
    // 3. KG vs LBS & Convertibility Tests
    // =========================================================================

    @Test
    fun testIncreasingLoadsKgLbsLbsProduceProgressingAndNeverStagnant() {
        val ex = ExerciseEntity(id = 1, displayName = "Press Militar", normalizedName = "press militar", muscleGroup = "Hombros", equipment = "Mancuernas")

        // 3 consecutive sessions with strictly increasing canonical physical mass:
        // Session 1: 20 kg = 20,000 g
        // Session 2: 50 lb = 22,680 g
        // Session 3: 55 lb = 24,948 g
        val s1 = WorkoutSessionEntity(id = 1, title = "S1", startedAtMillis = 100_000, notes = "", category = "Hombros")
        val s2 = WorkoutSessionEntity(id = 2, title = "S2", startedAtMillis = 200_000, notes = "", category = "Hombros")
        val s3 = WorkoutSessionEntity(id = 3, title = "S3", startedAtMillis = 300_000, notes = "", category = "Hombros")

        val set1 = WorkoutSetEntity(id = 1, sessionId = 1, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 20_000, repetitions = 10, note = "", loadUnit = "KG")
        val set2 = WorkoutSetEntity(id = 2, sessionId = 2, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 22_680, repetitions = 10, note = "", loadUnit = "LBS")
        val set3 = WorkoutSetEntity(id = 3, sessionId = 3, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 24_948, repetitions = 10, note = "", loadUnit = "LBS")

        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Press Militar",
            sessions = listOf(s1, s2, s3),
            allSets = listOf(set1, set2, set3),
            exercises = listOf(ex)
        )

        assertFalse("Increasing mass window must NOT report stagnation", report.stagnation.isStagnant)
        assertEquals("ProgressionStatus must be PROGRESSING", ProgressionStatus.PROGRESSING, report.status)
        assertTrue("Explanation must indicate progression", report.stagnation.explanation.contains("se registró progresión"))
    }

    @Test
    fun testDecreasingLoadsKgLbsLbsProduceRegressingAndNeverStagnant() {
        val ex = ExerciseEntity(id = 1, displayName = "Remo con Mancuerna", normalizedName = "remo con mancuerna", muscleGroup = "Espalda", equipment = "Mancuerna")

        // Decreasing mass:
        // Session 1: 30 kg = 30,000 g
        // Session 2: 55 lb = 24,948 g (~25 kg)
        // Session 3: 45 lb = 20,412 g (~20 kg)
        val s1 = WorkoutSessionEntity(id = 1, title = "S1", startedAtMillis = 100_000, notes = "", category = "Espalda")
        val s2 = WorkoutSessionEntity(id = 2, title = "S2", startedAtMillis = 200_000, notes = "", category = "Espalda")
        val s3 = WorkoutSessionEntity(id = 3, title = "S3", startedAtMillis = 300_000, notes = "", category = "Espalda")

        val set1 = WorkoutSetEntity(id = 1, sessionId = 1, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 30_000, repetitions = 10, note = "", loadUnit = "KG")
        val set2 = WorkoutSetEntity(id = 2, sessionId = 2, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 24_948, repetitions = 10, note = "", loadUnit = "LBS")
        val set3 = WorkoutSetEntity(id = 3, sessionId = 3, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 20_412, repetitions = 10, note = "", loadUnit = "LBS")

        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Remo con Mancuerna",
            sessions = listOf(s1, s2, s3),
            allSets = listOf(set1, set2, set3),
            exercises = listOf(ex)
        )

        assertFalse("Decreasing performance must NOT be labeled as flat stagnation", report.stagnation.isStagnant)
        assertEquals("Status must be REGRESSING", ProgressionStatus.REGRESSING, report.status)
        assertTrue("Explanation should mention reduction/retroceso", report.stagnation.explanation.contains("disminución de carga") || report.stagnation.explanation.contains("retroceso"))
    }

    @Test
    fun testFlatLoadsProduceStagnant() {
        val ex = ExerciseEntity(id = 1, displayName = "Sentadilla Hack", normalizedName = "sentadilla hack", muscleGroup = "Piernas", equipment = "Máquina")

        // 3 consecutive sessions stuck at identical load and reps
        val s1 = WorkoutSessionEntity(id = 1, title = "S1", startedAtMillis = 100_000, notes = "", category = "Piernas")
        val s2 = WorkoutSessionEntity(id = 2, title = "S2", startedAtMillis = 200_000, notes = "", category = "Piernas")
        val s3 = WorkoutSessionEntity(id = 3, title = "S3", startedAtMillis = 300_000, notes = "", category = "Piernas")

        val set1 = WorkoutSetEntity(id = 1, sessionId = 1, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 80_000, repetitions = 8, note = "", loadUnit = "KG")
        val set2 = WorkoutSetEntity(id = 2, sessionId = 2, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 80_000, repetitions = 8, note = "", loadUnit = "KG")
        val set3 = WorkoutSetEntity(id = 3, sessionId = 3, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 80_000, repetitions = 8, note = "", loadUnit = "KG")

        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Sentadilla Hack",
            sessions = listOf(s1, s2, s3),
            allSets = listOf(set1, set2, set3),
            exercises = listOf(ex)
        )

        assertTrue("Flat loads for 3 sessions must trigger stagnation", report.stagnation.isStagnant)
        assertEquals("Status must be STAGNANT", ProgressionStatus.STAGNANT, report.status)
    }

    @Test
    fun testIncompatibleMachineUnitsBloquesPlacasBloquesProduceInsufficientData() {
        val ex = ExerciseEntity(id = 1, displayName = "Polea Tríceps", normalizedName = "polea triceps", muscleGroup = "Brazos", equipment = "Polea")

        val s1 = WorkoutSessionEntity(id = 1, title = "S1", startedAtMillis = 100_000, notes = "", category = "Brazos")
        val s2 = WorkoutSessionEntity(id = 2, title = "S2", startedAtMillis = 200_000, notes = "", category = "Brazos")
        val s3 = WorkoutSessionEntity(id = 3, title = "S3", startedAtMillis = 300_000, notes = "", category = "Brazos")

        val set1 = WorkoutSetEntity(id = 1, sessionId = 1, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 13_000, repetitions = 10, note = "", loadUnit = "BLOQUES")
        val set2 = WorkoutSetEntity(id = 2, sessionId = 2, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 10_000, repetitions = 10, note = "", loadUnit = "PLACAS")
        val set3 = WorkoutSetEntity(id = 3, sessionId = 3, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 9_000, repetitions = 10, note = "", loadUnit = "BLOQUES")

        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Polea Tríceps",
            sessions = listOf(s1, s2, s3),
            allSets = listOf(set1, set2, set3),
            exercises = listOf(ex)
        )

        assertFalse("Incompatible units must NOT trigger false stagnation", report.stagnation.isStagnant)
        assertEquals(MetricDataQuality.INSUFFICIENT_DATA, report.stagnation.dataQuality)
        assertEquals(ProgressionStatus.INSUFFICIENT_DATA, report.status)
        assertTrue(report.stagnation.explanation.contains("unidades no comparables"))
    }
}
