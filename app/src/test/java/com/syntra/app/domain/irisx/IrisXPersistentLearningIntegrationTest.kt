package com.syntra.app.domain.irisx

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.WorkoutCategoryKey
import com.syntra.app.domain.WorkoutTextParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * SQLite-backed repository for pure integration testing of the IrisX persistent lifecycle.
 */
class SqliteIrisXRepository(private val dbUrl: String) : IrisXRepository {

    override suspend fun getLatestSession(): WorkoutSessionEntity? {
        DriverManager.getConnection(dbUrl).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT id, title, startedAtMillis, finishedAtMillis, notes FROM workout_sessions ORDER BY startedAtMillis DESC LIMIT 1")
            if (rs.next()) {
                return WorkoutSessionEntity(
                    id = rs.getLong("id"),
                    title = rs.getString("title"),
                    startedAtMillis = rs.getLong("startedAtMillis"),
                    finishedAtMillis = rs.getLong("finishedAtMillis"),
                    notes = rs.getString("notes")
                )
            }
        }
        return null
    }

    override suspend fun getSetsForSession(sessionId: Long): List<WorkoutSetEntity> {
        val list = mutableListOf<WorkoutSetEntity>()
        DriverManager.getConnection(dbUrl).use { conn ->
            val ps = conn.prepareStatement("SELECT * FROM workout_sets WHERE sessionId = ? ORDER BY executionOrder ASC")
            ps.setLong(1, sessionId)
            val rs = ps.executeQuery()
            while (rs.next()) {
                list.add(
                    WorkoutSetEntity(
                        id = rs.getLong("id"),
                        sessionId = rs.getLong("sessionId"),
                        exerciseId = rs.getLong("exerciseId"),
                        orderInExercise = rs.getInt("orderInExercise"),
                        executionOrder = rs.getInt("executionOrder"),
                        loadGrams = rs.getInt("loadGrams"),
                        repetitions = rs.getInt("repetitions"),
                        rir = if (rs.getObject("rir") != null) rs.getInt("rir") else null,
                        isAssisted = rs.getInt("isAssisted") == 1,
                        isDumbbell = rs.getInt("isDumbbell") == 1,
                        note = rs.getString("note") ?: "",
                        plannedTarget = rs.getString("plannedTarget")
                    )
                )
            }
        }
        return list
    }

    override suspend fun getExerciseNameMap(): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        DriverManager.getConnection(dbUrl).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT id, displayName FROM exercises")
            while (rs.next()) {
                map[rs.getLong("id")] = rs.getString("displayName")
            }
        }
        return map
    }

    override suspend fun findExerciseByNormalized(normalized: String): ExerciseEntity? {
        DriverManager.getConnection(dbUrl).use { conn ->
            val ps = conn.prepareStatement("SELECT * FROM exercises WHERE normalizedName = ? LIMIT 1")
            ps.setString(1, normalized)
            val rs = ps.executeQuery()
            if (rs.next()) {
                return ExerciseEntity(
                    id = rs.getLong("id"),
                    displayName = rs.getString("displayName"),
                    normalizedName = rs.getString("normalizedName"),
                    muscleGroup = rs.getString("muscleGroup"),
                    secondaryMuscleGroups = rs.getString("secondaryMuscleGroups") ?: "",
                    equipment = rs.getString("equipment"),
                    exerciseType = rs.getString("exerciseType") ?: "strength",
                    status = rs.getString("status") ?: "CONFIRMED",
                    createdAtMillis = rs.getLong("createdAtMillis")
                )
            }
        }
        return null
    }

    override suspend fun updateExercise(exercise: ExerciseEntity) {
        DriverManager.getConnection(dbUrl).use { conn ->
            val ps = conn.prepareStatement("UPDATE exercises SET displayName = ?, muscleGroup = ?, status = ? WHERE id = ?")
            ps.setString(1, exercise.displayName)
            ps.setString(2, exercise.muscleGroup)
            ps.setString(3, exercise.status)
            ps.setLong(4, exercise.id)
            ps.executeUpdate()
        }
    }

    override suspend fun getLearnedAliases(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        DriverManager.getConnection(dbUrl).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT sourceKey, targetValue FROM irisx_memories WHERE memoryType = 'ALIAS'")
            while (rs.next()) {
                map[rs.getString("sourceKey")] = rs.getString("targetValue")
            }
        }
        return map
    }

    override suspend fun saveLearnedAlias(alias: String, canonical: String) {
        DriverManager.getConnection(dbUrl).use { conn ->
            val ps = conn.prepareStatement("""
                INSERT INTO irisx_memories (memoryType, sourceKey, targetValue, confidence, createdAtMillis, updatedAtMillis)
                VALUES ('ALIAS', ?, ?, 1.0, ?, ?)
                ON CONFLICT(sourceKey, memoryType) DO UPDATE SET targetValue = excluded.targetValue, updatedAtMillis = excluded.updatedAtMillis
            """.trimIndent())
            val now = System.currentTimeMillis()
            ps.setString(1, alias)
            ps.setString(2, canonical)
            ps.setLong(3, now)
            ps.setLong(4, now)
            ps.executeUpdate()
        }
    }

    override suspend fun deleteLearnedAlias(alias: String) {
        DriverManager.getConnection(dbUrl).use { conn ->
            val ps = conn.prepareStatement("DELETE FROM irisx_memories WHERE sourceKey = ? AND memoryType = 'ALIAS'")
            ps.setString(1, alias)
            ps.executeUpdate()
        }
    }

    override suspend fun saveMemory(memoryType: String, sourceKey: String, targetValue: String) {
        DriverManager.getConnection(dbUrl).use { conn ->
            val ps = conn.prepareStatement("""
                INSERT INTO irisx_memories (memoryType, sourceKey, targetValue, confidence, createdAtMillis, updatedAtMillis)
                VALUES (?, ?, ?, 1.0, ?, ?)
                ON CONFLICT(sourceKey, memoryType) DO UPDATE SET targetValue = excluded.targetValue, updatedAtMillis = excluded.updatedAtMillis
            """.trimIndent())
            val now = System.currentTimeMillis()
            ps.setString(1, memoryType)
            ps.setString(2, sourceKey)
            ps.setString(3, targetValue)
            ps.setLong(4, now)
            ps.setLong(5, now)
            ps.executeUpdate()
        }
    }
}

class IrisXPersistentLearningIntegrationTest {

    private fun initSchema(dbUrl: String) {
        DriverManager.getConnection(dbUrl).use { conn ->
            val stmt = conn.createStatement()
            stmt.execute("""
                CREATE TABLE exercises (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    displayName TEXT NOT NULL,
                    normalizedName TEXT NOT NULL UNIQUE,
                    muscleGroup TEXT NOT NULL,
                    secondaryMuscleGroups TEXT NOT NULL DEFAULT '',
                    equipment TEXT,
                    exerciseType TEXT NOT NULL DEFAULT 'strength',
                    status TEXT NOT NULL DEFAULT 'CONFIRMED',
                    createdAtMillis INTEGER NOT NULL
                )
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE exercise_aliases (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    exerciseId INTEGER NOT NULL,
                    normalizedAlias TEXT NOT NULL UNIQUE,
                    createdAtMillis INTEGER NOT NULL
                )
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE irisx_memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    memoryType TEXT NOT NULL,
                    sourceKey TEXT NOT NULL,
                    targetValue TEXT NOT NULL,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    createdAtMillis INTEGER NOT NULL,
                    updatedAtMillis INTEGER NOT NULL
                )
            """.trimIndent())
            stmt.execute("CREATE UNIQUE INDEX index_irisx_memories_sourceKey_memoryType ON irisx_memories (sourceKey, memoryType)")

            stmt.execute("""
                CREATE TABLE workout_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    startedAtMillis INTEGER NOT NULL,
                    finishedAtMillis INTEGER,
                    notes TEXT NOT NULL DEFAULT ''
                )
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE workout_sets (
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

            // Seed canonical exercise
            stmt.execute("""
                INSERT INTO exercises (id, displayName, normalizedName, muscleGroup, secondaryMuscleGroups, equipment, exerciseType, status, createdAtMillis)
                VALUES (1, 'Extensión de cuádriceps en máquina', 'extension de cuadriceps en maquina', 'Piernas', 'Cuádriceps', 'Máquina', 'strength', 'CONFIRMED', 1700000000000)
            """.trimIndent())
        }
    }

    @Test
    fun `full persistent learning lifecycle with SQLite reboot, parser reuse and conflict validation`() = runBlocking {
        val tempFile = java.io.File.createTempFile("test_irisx_lifecycle_", ".db")
        tempFile.deleteOnExit()
        val dbUrl = "jdbc:sqlite:${tempFile.absolutePath}"
        initSchema(dbUrl)

        // --- PASO 1 & 2: Instanciar primer IrisXCore y registrar alias ---
        var repo: IrisXRepository = SqliteIrisXRepository(dbUrl)
        var core = IrisXCore(repo)

        // 1. Aprender equivalencia
        val learnResult = core.execute(
            IrisXIntent.LearnAlias(alias = "camilla adelante", canonical = "Extensión de cuádriceps en máquina")
        )
        assertTrue("Learn alias must succeed", learnResult is IrisXResponse.Success)

        // --- PASO 3 & 4: Reinicializar componentes IrisX desde cero simulando reinicio de la app ---
        // Descartamos las instancias 'core' y 'repo' anteriores
        repo = SqliteIrisXRepository(dbUrl)
        core = IrisXCore(repo)

        // --- PASO 5: Recuperar equivalencia desde SQLite tras reinicio ---
        val recoveredAliases = core.getLearnedAliases()
        assertEquals("Extensión de cuádriceps en máquina", recoveredAliases["camilla adelante"])

        // --- PASO 6: Utilizarla en una nueva interpretación de texto ---
        val rawInput = "Piernas\nCamilla adelante 45kg 12 10 8"
        val parsed = WorkoutTextParser.parse(rawInput, aliases = recoveredAliases)

        assertNotNull(parsed)
        assertEquals(1, parsed.exercises.size)
        assertEquals("Extensión de cuádriceps en máquina", parsed.exercises[0].name)
        assertEquals(3, parsed.exercises[0].sets.size)

        // Clasificar con IrisXClassifier usando el alias aprendido
        val classification = IrisXClassifier.classifyWorkout(
            sessionTitle = "Piernas",
            exerciseNames = listOf("Camilla adelante"),
            learnedAliases = recoveredAliases
        )
        assertEquals(WorkoutCategoryKey.PIERNAS, classification.primaryCategory)
        assertEquals("Piernas", classification.muscleBreakdown["Piernas"]?.let { "Piernas" })

        // --- PASO 7: Validar detección de conflicto ante alias duplicado sin autorización ---
        // El usuario intenta reasignar "camilla adelante" a "Press de banca"
        val conflictResult = core.execute(
            IrisXIntent.LearnAlias(alias = "camilla adelante", canonical = "Press de banca", force = false)
        )
        assertTrue("Must detect conflict and require confirmation", conflictResult is IrisXResponse.RequiresConfirmation)
        val conf = conflictResult as IrisXResponse.RequiresConfirmation
        assertTrue(conf.question.contains("Extensión de cuádriceps en máquina"))

        // Verificar que en SQLite NO se sobrescribió
        val nonOverwritten = core.getLearnedAliases()
        assertEquals("Extensión de cuádriceps en máquina", nonOverwritten["camilla adelante"])

        // Ahora autorizamos con confirmación explícita (force = true)
        val confirmResult = core.execute(
            IrisXIntent.LearnAlias(alias = "camilla adelante", canonical = "Press de banca", force = true)
        )
        assertTrue("Forced overwrite must succeed", confirmResult is IrisXResponse.Success)
        assertEquals("Press de banca", core.getLearnedAliases()["camilla adelante"])

        // --- PASO 8: Eliminar la equivalencia y verificar en SQLite ---
        val deleteResult = core.execute(IrisXIntent.DeleteAlias("camilla adelante"))
        assertTrue("Deletion must succeed", deleteResult is IrisXResponse.Success)

        val finalAliases = core.getLearnedAliases()
        assertFalse("Alias must no longer exist in SQLite", finalAliases.containsKey("camilla adelante"))
    }
}
