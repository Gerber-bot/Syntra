package com.syntra.app.domain.irisx

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.IrisxConversationEntity
import com.syntra.app.data.IrisxMessageEntity
import com.syntra.app.data.ProfileEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * SQLite-backed repository supporting full Sprint 04 tables for integration testing
 * of IrisX Context, Analytics, and Coach.
 */
class Sprint04SqliteIrisXRepository(private val dbUrl: String) : IrisXRepository {

    override suspend fun getLatestSession(): WorkoutSessionEntity? =
        getAllSessions().maxByOrNull { it.startedAtMillis }

    override suspend fun getAllSessions(): List<WorkoutSessionEntity> {
        val list = mutableListOf<WorkoutSessionEntity>()
        DriverManager.getConnection(dbUrl).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT * FROM workout_sessions ORDER BY startedAtMillis DESC")
            while (rs.next()) {
                list.add(
                    WorkoutSessionEntity(
                        id = rs.getLong("id"),
                        title = rs.getString("title"),
                        startedAtMillis = rs.getLong("startedAtMillis"),
                        finishedAtMillis = rs.getLong("finishedAtMillis"),
                        notes = rs.getString("notes") ?: ""
                    )
                )
            }
        }
        return list
    }

    override suspend fun getSetsForSession(sessionId: Long): List<WorkoutSetEntity> =
        getAllSets().filter { it.sessionId == sessionId }

    override suspend fun getAllSets(): List<WorkoutSetEntity> {
        val list = mutableListOf<WorkoutSetEntity>()
        DriverManager.getConnection(dbUrl).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT * FROM workout_sets ORDER BY sessionId ASC, executionOrder ASC")
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

    override suspend fun getExerciseNameMap(): Map<Long, String> =
        getAllExercises().associate { it.id to it.displayName }

    override suspend fun getAllExercises(): List<ExerciseEntity> {
        val list = mutableListOf<ExerciseEntity>()
        DriverManager.getConnection(dbUrl).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT * FROM exercises")
            while (rs.next()) {
                list.add(
                    ExerciseEntity(
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
                )
            }
        }
        return list
    }

    override suspend fun findExerciseByNormalized(normalized: String): ExerciseEntity? =
        getAllExercises().firstOrNull { it.normalizedName == normalized }

    override suspend fun updateExercise(exercise: ExerciseEntity) {}

    override suspend fun getLearnedAliases(): Map<String, String> = getMemoryByType("ALIAS")

    override suspend fun getConfirmedPreferences(): Map<String, String> = getMemoryByType("PREFERENCE")

    private fun getMemoryByType(type: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        DriverManager.getConnection(dbUrl).use { conn ->
            val ps = conn.prepareStatement("SELECT sourceKey, targetValue FROM irisx_memories WHERE memoryType = ?")
            ps.setString(1, type)
            val rs = ps.executeQuery()
            while (rs.next()) {
                map[rs.getString("sourceKey")] = rs.getString("targetValue")
            }
        }
        return map
    }

    override suspend fun saveLearnedAlias(alias: String, canonical: String) {
        saveMemory("ALIAS", alias, canonical)
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

    override suspend fun getOrCreateActiveConversation(): IrisxConversationEntity {
        DriverManager.getConnection(dbUrl).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT * FROM irisx_conversations ORDER BY updatedAtMillis DESC LIMIT 1")
            if (rs.next()) {
                return IrisxConversationEntity(
                    id = rs.getLong("id"),
                    title = rs.getString("title"),
                    activeMuscleGroup = rs.getString("activeMuscleGroup"),
                    activeExerciseName = rs.getString("activeExerciseName"),
                    activeSessionId = if (rs.getObject("activeSessionId") != null) rs.getLong("activeSessionId") else null,
                    activePeriodScope = rs.getString("activePeriodScope"),
                    createdAtMillis = rs.getLong("createdAtMillis"),
                    updatedAtMillis = rs.getLong("updatedAtMillis")
                )
            }
            val now = System.currentTimeMillis()
            val ps = conn.prepareStatement(
                "INSERT INTO irisx_conversations (title, activePeriodScope, createdAtMillis, updatedAtMillis) VALUES ('Principal', 'THIS_MONTH', ?, ?)"
            )
            ps.setLong(1, now)
            ps.setLong(2, now)
            ps.executeUpdate()
            val idRs = conn.createStatement().executeQuery("SELECT last_insert_rowid()")
            val id = if (idRs.next()) idRs.getLong(1) else 1L
            return IrisxConversationEntity(id = id, title = "Principal", activePeriodScope = "THIS_MONTH", createdAtMillis = now, updatedAtMillis = now)
        }
    }

    override suspend fun updateConversationState(conversation: IrisxConversationEntity) {
        DriverManager.getConnection(dbUrl).use { conn ->
            val ps = conn.prepareStatement("""
                UPDATE irisx_conversations
                SET activeMuscleGroup = ?, activeExerciseName = ?, activeSessionId = ?, activePeriodScope = ?, updatedAtMillis = ?
                WHERE id = ?
            """.trimIndent())
            ps.setString(1, conversation.activeMuscleGroup)
            ps.setString(2, conversation.activeExerciseName)
            if (conversation.activeSessionId != null) ps.setLong(3, conversation.activeSessionId) else ps.setNull(3, java.sql.Types.INTEGER)
            ps.setString(4, conversation.activePeriodScope)
            ps.setLong(5, conversation.updatedAtMillis)
            ps.setLong(6, conversation.id)
            ps.executeUpdate()
        }
    }

    override suspend fun appendMessage(message: IrisxMessageEntity): Long {
        DriverManager.getConnection(dbUrl).use { conn ->
            val ps = conn.prepareStatement("""
                INSERT INTO irisx_messages (conversationId, senderRole, content, intentType, referencedExercise, referencedMuscleGroup, createdAtMillis)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent())
            ps.setLong(1, message.conversationId)
            ps.setString(2, message.senderRole)
            ps.setString(3, message.content)
            ps.setString(4, message.intentType)
            ps.setString(5, message.referencedExercise)
            ps.setString(6, message.referencedMuscleGroup)
            ps.setLong(7, message.createdAtMillis)
            ps.executeUpdate()
            return 1L
        }
    }

    override suspend fun getMessages(conversationId: Long): List<IrisxMessageEntity> {
        val list = mutableListOf<IrisxMessageEntity>()
        DriverManager.getConnection(dbUrl).use { conn ->
            val ps = conn.prepareStatement("SELECT * FROM irisx_messages WHERE conversationId = ? ORDER BY createdAtMillis ASC, id ASC")
            ps.setLong(1, conversationId)
            val rs = ps.executeQuery()
            while (rs.next()) {
                list.add(
                    IrisxMessageEntity(
                        id = rs.getLong("id"),
                        conversationId = rs.getLong("conversationId"),
                        senderRole = rs.getString("senderRole"),
                        content = rs.getString("content"),
                        intentType = rs.getString("intentType"),
                        referencedExercise = rs.getString("referencedExercise"),
                        referencedMuscleGroup = rs.getString("referencedMuscleGroup"),
                        createdAtMillis = rs.getLong("createdAtMillis")
                    )
                )
            }
        }
        return list
    }

    override suspend fun clearAllConversations() {
        DriverManager.getConnection(dbUrl).use { conn ->
            conn.createStatement().execute("DELETE FROM irisx_messages")
            conn.createStatement().execute("DELETE FROM irisx_conversations")
        }
    }
}

class IrisXContextAndCoachIntegrationTest {

    private val dayMs = 86_400_000L
    private val refNow = 1_710_000_000_000L

    private fun setupSqliteDb(): String {
        val tempFile = java.io.File.createTempFile("test_sprint04_context_", ".db")
        tempFile.deleteOnExit()
        val dbUrl = "jdbc:sqlite:${tempFile.absolutePath}"

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
                CREATE TABLE irisx_memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    memoryType TEXT NOT NULL,
                    sourceKey TEXT NOT NULL,
                    targetValue TEXT NOT NULL,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    createdAtMillis INTEGER NOT NULL,
                    updatedAtMillis INTEGER NOT NULL,
                    UNIQUE(sourceKey, memoryType)
                )
            """.trimIndent())
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
            stmt.execute("""
                CREATE TABLE irisx_conversations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    activeMuscleGroup TEXT,
                    activeExerciseName TEXT,
                    activeSessionId INTEGER,
                    activePeriodScope TEXT,
                    createdAtMillis INTEGER NOT NULL,
                    updatedAtMillis INTEGER NOT NULL
                )
            """.trimIndent())
            stmt.execute("""
                CREATE TABLE irisx_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    conversationId INTEGER NOT NULL,
                    senderRole TEXT NOT NULL,
                    content TEXT NOT NULL,
                    intentType TEXT,
                    referencedExercise TEXT,
                    referencedMuscleGroup TEXT,
                    referencedSessionId INTEGER,
                    createdAtMillis INTEGER NOT NULL
                )
            """.trimIndent())

            // Seed exercises and workouts across last month and this month
            stmt.execute("INSERT INTO exercises (id, displayName, normalizedName, muscleGroup, equipment, createdAtMillis) VALUES (1, 'Remo con barra', 'remo con barra', 'Espalda', 'Barra', $refNow)")
            stmt.execute("INSERT INTO exercises (id, displayName, normalizedName, muscleGroup, equipment, createdAtMillis) VALUES (2, 'Press de banca', 'press de banca', 'Pecho', 'Barra', $refNow)")

            val lastMonthSessionTime = refNow - (40 * dayMs)
            val thisMonthSessionTime = refNow - (5 * dayMs)
            stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis) VALUES (10, 'Espalda Mes Pasado', $lastMonthSessionTime, $lastMonthSessionTime)")
            stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis) VALUES (11, 'Espalda Este Mes', $thisMonthSessionTime, $thisMonthSessionTime)")

            stmt.execute("INSERT INTO workout_sets (sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions) VALUES (10, 1, 1, 1, 60000, 10)")
            stmt.execute("INSERT INTO workout_sets (sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions) VALUES (11, 1, 1, 1, 70000, 10)")
        }
        return dbUrl
    }

    @Test
    fun `contextual dialogue resolves follow-up question and persists across SQLite reboots`() = runBlocking {
        val dbUrl = setupSqliteDb()

        // 1. User asks: "¿Cómo progresé en espalda?"
        var repo = Sprint04SqliteIrisXRepository(dbUrl)
        var core = IrisXCore(repo)

        val resp1 = core.ask("¿Cómo progresé en espalda?", referenceTimeMillis = refNow)
        assertTrue(resp1 is IrisXResponse.Success)
        val msg1 = (resp1 as IrisXResponse.Success).message
        assertTrue(msg1.contains("Espalda"))
        assertTrue(msg1.contains("Remo con barra"))
        assertEquals("Espalda", core.getCurrentContextState().activeMuscleGroup)

        // 2. Simulate app reboot: new repository & IrisXCore instance reading from SQLite
        repo = Sprint04SqliteIrisXRepository(dbUrl)
        core = IrisXCore(repo)

        // 3. User asks follow-up: "¿Y el mes pasado?" without repeating "espalda"
        val resp2 = core.ask("¿Y el mes pasado?", referenceTimeMillis = refNow)
        assertTrue(resp2 is IrisXResponse.Success)
        val msg2 = (resp2 as IrisXResponse.Success).message
        assertTrue("Follow-up must retain Espalda context", msg2.contains("Espalda"))
        assertTrue("Follow-up must analyze last month window", msg2.contains("El mes pasado"))

        // 4. Verify messages are stored in irisx_messages (2 user + 2 assistant = 4 messages)
        val conv = repo.getOrCreateActiveConversation()
        val storedMessages = repo.getMessages(conv.id)
        assertEquals(4, storedMessages.size)

        // 5. Clear conversation and verify workouts remain intact
        val clearResp = core.ask("borrar conversación", referenceTimeMillis = refNow)
        assertTrue(clearResp is IrisXResponse.Success)
        assertNull(core.getCurrentContextState().activeMuscleGroup)
        assertEquals(2, repo.getAllSessions().size)
    }

    @Test
    fun `preferences require explicit confirmation before permanent storage`() = runBlocking {
        val dbUrl = setupSqliteDb()
        val repo = Sprint04SqliteIrisXRepository(dbUrl)
        val core = IrisXCore(repo)

        // Unconfirmed preference statement must NOT be saved automatically
        val unconfirmed = core.ask("preferencia frecuencia = 4 dias", referenceTimeMillis = refNow)
        assertTrue(unconfirmed is IrisXResponse.RequiresConfirmation)
        assertTrue(repo.getConfirmedPreferences().isEmpty())

        val confirmCmd = (unconfirmed as IrisXResponse.RequiresConfirmation).confirmCommand
        val confirmed = core.ask(confirmCmd, referenceTimeMillis = refNow)
        assertTrue(confirmed is IrisXResponse.Success)
        assertEquals("4 dias", repo.getConfirmedPreferences()["frecuencia"])
    }

    @Test
    fun `coach generates verified rule-based recommendations with full justification`() = runBlocking {
        val dbUrl = setupSqliteDb()
        val repo = Sprint04SqliteIrisXRepository(dbUrl)
        val core = IrisXCore(repo)

        val response = core.ask("recomendaciones", referenceTimeMillis = refNow)
        assertTrue(response is IrisXResponse.Success)
        val success = response as IrisXResponse.Success
        assertTrue(success.message.contains("Propuesta:"))
        assertTrue(success.message.contains("Datos utilizados:"))
        assertTrue(success.message.contains("Motivo:"))
        assertTrue(success.message.contains("Certeza y limitaciones"))

        @Suppress("UNCHECKED_CAST")
        val recs = success.payload as? List<CoachRecommendation>
        assertNotNull(recs)
        assertFalse(recs!!.isEmpty())
        recs.forEach { rec ->
            assertFalse(rec.proposal.isBlank())
            assertFalse(rec.dataUsed.isEmpty())
            assertFalse(rec.reason.isBlank())
            assertFalse(rec.certaintyAndLimitations.isBlank())
        }
    }
}
