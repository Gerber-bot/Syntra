package com.syntra.app.domain.irisx

import com.syntra.app.data.IrisxConversationEntity
import com.syntra.app.data.IrisxMessageEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.irisx.tools.IrisXLocalTools
import com.syntra.app.domain.irisx.tools.LocalToolOutcome
import com.syntra.app.domain.irisx.tools.LocalToolRecentWorkouts
import com.syntra.app.domain.irisx.tools.LocalToolRequest
import com.syntra.app.ui.irisx.IrisXTurnLedger
import com.syntra.app.ui.irisx.IrisXTurnRoute
import com.syntra.app.ui.irisx.IrisXTurnRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * Integration and regression tests consolidating Stage B.2:
 * Console routing ([IrisXTurnRouter]) -> [IrisXCore] -> [IrisXLocalTools] -> SQLite/Room.
 */
class IrisXB2ConsolidationTest {

    private val baseTime = 1_760_000_000_000L
    private val dayMs = 86_400_000L

    private fun createPopulatedSqliteDb(): String {
        val dbFile = File.createTempFile("syntra_b2_consolidation_", ".db").apply {
            deleteOnExit()
        }
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE exercises (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName TEXT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        muscleGroup TEXT NOT NULL,
                        secondaryMuscleGroups TEXT NOT NULL DEFAULT '',
                        equipment TEXT,
                        exerciseType TEXT NOT NULL DEFAULT 'strength',
                        status TEXT NOT NULL DEFAULT 'CONFIRMED',
                        createdAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE workout_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        startedAtMillis INTEGER NOT NULL,
                        finishedAtMillis INTEGER,
                        notes TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE workout_sets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        exerciseId INTEGER NOT NULL,
                        orderInExercise INTEGER NOT NULL,
                        executionOrder INTEGER NOT NULL,
                        loadGrams INTEGER NOT NULL,
                        repetitions INTEGER NOT NULL,
                        rir INTEGER,
                        isAssisted INTEGER NOT NULL DEFAULT 0,
                        isDumbbell INTEGER NOT NULL DEFAULT 0,
                        note TEXT NOT NULL DEFAULT '',
                        plannedTarget TEXT
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE irisx_memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        memoryType TEXT NOT NULL,
                        sourceKey TEXT NOT NULL,
                        targetValue TEXT NOT NULL,
                        confidence REAL NOT NULL DEFAULT 1.0,
                        createdAtMillis INTEGER NOT NULL DEFAULT 0,
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE irisx_conversations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        activeMuscleGroup TEXT,
                        activeExerciseName TEXT,
                        activeSessionId INTEGER,
                        activePeriodScope TEXT,
                        createdAtMillis INTEGER NOT NULL DEFAULT 0,
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE irisx_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER NOT NULL,
                        senderRole TEXT NOT NULL,
                        content TEXT NOT NULL,
                        intentType TEXT,
                        referencedExercise TEXT,
                        referencedMuscleGroup TEXT,
                        referencedSessionId INTEGER,
                        createdAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                // Exercises
                stmt.execute("INSERT INTO exercises (id, displayName, normalizedName, muscleGroup, equipment) VALUES (1, 'Sentadilla', 'sentadilla', 'Piernas', 'Barra')")
                stmt.execute("INSERT INTO exercises (id, displayName, normalizedName, muscleGroup, equipment) VALUES (2, 'Press de banca', 'press de banca', 'Pecho', 'Barra')")

                // Session 1: Piernas Base (older)
                val s1 = baseTime - 7 * dayMs
                stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis) VALUES (1, 'Piernas Base', $s1, ${s1 + 3600_000})")
                stmt.execute("INSERT INTO workout_sets (id, sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions) VALUES (1, 1, 1, 1, 1, 95000, 6)")
                stmt.execute("INSERT INTO workout_sets (id, sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions) VALUES (2, 1, 1, 2, 2, 95000, 6)")

                // Session 2: Piernas Fuerza (newer Squat session: 3 sets)
                val s2 = baseTime - 3 * dayMs
                stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis) VALUES (2, 'Piernas Fuerza', $s2, ${s2 + 3600_000})")
                stmt.execute("INSERT INTO workout_sets (id, sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions) VALUES (3, 2, 1, 1, 1, 105000, 5)")
                stmt.execute("INSERT INTO workout_sets (id, sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions) VALUES (4, 2, 1, 2, 2, 100000, 6)")
                stmt.execute("INSERT INTO workout_sets (id, sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions) VALUES (5, 2, 1, 3, 3, 100000, 5)")

                // Session 3: Pecho Pesado (latest overall workout)
                val s3 = baseTime - 1 * dayMs
                stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis) VALUES (3, 'Pecho Pesado', $s3, ${s3 + 3600_000})")
                stmt.execute("INSERT INTO workout_sets (id, sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions) VALUES (6, 3, 2, 1, 1, 82500, 6)")
                stmt.execute("INSERT INTO workout_sets (id, sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions) VALUES (7, 3, 2, 2, 2, 82500, 6)")

                // Visible conversation
                stmt.execute("INSERT INTO irisx_conversations (id, title, activePeriodScope, createdAtMillis, updatedAtMillis) VALUES (1, 'Principal', 'THIS_MONTH', $baseTime, $baseTime)")
            }
        }
        return url
    }

    @Test
    fun `console routes all five B2 questions to deterministic tools even when Qwen3 is loaded`() = runBlocking {
        val dbUrl = createPopulatedSqliteDb()
        val repo = Sprint04SqliteIrisXRepository(dbUrl)
        val core = IrisXCore(repo)
        val conv = repo.getOrCreateActiveConversation()

        val questions = listOf(
            "¿Cuál fue mi último entrenamiento?",
            "¿Cuántas series de sentadilla hice la última vez?",
            "¿Con cuánto peso hice sentadilla?",
            "¿Cómo progresé en sentadilla?",
            "¿Qué entrenamientos hice recientemente?"
        )

        questions.forEach { q ->
            val intent = core.parseInput(q)
            assertEquals(
                "Query '$q' must never route to Qwen3 when Qwen3 is ready",
                IrisXTurnRoute.DETERMINISTIC,
                IrisXTurnRouter.resolveRoute(intent, neuralReady = true, guardianBlocked = false)
            )
            assertEquals(
                IrisXTurnRoute.DETERMINISTIC,
                IrisXTurnRouter.resolveRoute(intent, neuralReady = false, guardianBlocked = false)
            )
        }

        // Open-ended general question DOES route to Qwen3 when available
        val freeIntent = core.parseInput("¿Por qué conviene controlar la fase excéntrica?")
        assertTrue(freeIntent is IrisXIntent.GeneralQuery)
        assertEquals(
            IrisXTurnRoute.NEURAL,
            IrisXTurnRouter.resolveRoute(freeIntent, neuralReady = true, guardianBlocked = false)
        )
        assertEquals(
            IrisXTurnRoute.DETERMINISTIC,
            IrisXTurnRouter.resolveRoute(freeIntent, neuralReady = true, guardianBlocked = true)
        )

        // 1. ¿Cuál fue mi último entrenamiento? -> LastWorkout tool
        val r1 = core.ask(questions[0], referenceTimeMillis = baseTime, conversation = conv) as IrisXResponse.Success
        assertEquals("QUERY_LAST_WORKOUT_TOOL", r1.actionTaken)
        assertTrue(r1.message.contains("Pecho Pesado"))
        assertTrue(r1.message.contains("Press de banca"))
        assertTrue(r1.message.contains("82.5 kg") || r1.message.contains("82,5 kg"))
        assertTrue(r1.message.contains("Series registradas: 2"))

        // 2. ¿Cuántas series de sentadilla hice la última vez? -> LastExerciseSession tool
        val r2 = core.ask(questions[1], referenceTimeMillis = baseTime, conversation = conv) as IrisXResponse.Success
        assertEquals("QUERY_LAST_EXERCISE_SESSION", r2.actionTaken)
        assertTrue(r2.message.contains("Sentadilla"))
        assertTrue(r2.message.contains("Piernas Fuerza"))
        assertTrue(r2.message.contains("Series ejecutadas (3)"))
        assertTrue(r2.message.contains("105 kg"))

        // 3. ¿Con cuánto peso hice sentadilla? -> LastExerciseSession tool
        val r3 = core.ask(questions[2], referenceTimeMillis = baseTime, conversation = conv) as IrisXResponse.Success
        assertEquals("QUERY_LAST_EXERCISE_SESSION", r3.actionTaken)
        assertTrue(r3.message.contains("Sentadilla"))
        assertTrue(r3.message.contains("Mejor serie: 105 kg × 5 reps"))

        // 4. ¿Cómo progresé en sentadilla? -> Progression analytics
        val r4 = core.ask(questions[3], referenceTimeMillis = baseTime, conversation = conv) as IrisXResponse.Success
        assertEquals("QUERY_EXERCISE_PROGRESSION", r4.actionTaken)
        assertTrue(r4.message.contains("Progresión de Sentadilla"))
        assertTrue(r4.message.contains("105 kg"))
        assertTrue(r4.message.contains("95 kg"))

        // 5. ¿Qué entrenamientos hice recientemente? -> RecentWorkouts tool
        val r5 = core.ask(questions[4], referenceTimeMillis = baseTime, conversation = conv) as IrisXResponse.Success
        assertEquals("QUERY_RECENT_WORKOUTS", r5.actionTaken)
        assertTrue(r5.message.contains("3 de 3 registrados"))
        assertTrue(r5.message.contains("Pecho Pesado"))
        assertTrue(r5.message.contains("Piernas Fuerza"))
        assertTrue(r5.message.contains("Piernas Base"))

        // Single-writer persistence check: 5 questions -> exactly 5 USER + 5 IRISX = 10 rows in SQLite
        val stored = repo.getMessages(conv.id)
        assertEquals(10, stored.size)
        assertEquals(5, stored.count { it.senderRole == "USER" })
        assertEquals(5, stored.count { it.senderRole == "IRISX" })
    }

    @Test
    fun `parsing disambiguates exercise history, recent workouts, sets queries and recommendations`() {
        val core = IrisXCore(EmptyStubRepository())

        val qLast = core.parseInput("¿Cuál fue mi último entrenamiento?")
        assertTrue(qLast is IrisXIntent.QueryLastWorkout)

        val qLastEx = core.parseInput("¿Qué hice la última vez en sentadilla?")
        assertTrue(qLastEx is IrisXIntent.QueryLastExerciseLoad)
        assertEquals("sentadilla", (qLastEx as IrisXIntent.QueryLastExerciseLoad).exerciseName)

        val qSets = core.parseInput("¿Cuántas series de press de banca hice la última vez?")
        assertTrue(qSets is IrisXIntent.QueryLastExerciseLoad)
        assertEquals("press de banca", (qSets as IrisXIntent.QueryLastExerciseLoad).exerciseName)

        val qWeight = core.parseInput("¿Con cuánto peso hice peso muerto rumano?")
        assertTrue(qWeight is IrisXIntent.QueryLastExerciseLoad)
        assertEquals("peso muerto rumano", (qWeight as IrisXIntent.QueryLastExerciseLoad).exerciseName)

        val qRecent = core.parseInput("¿Qué entrenamientos hice recientemente?")
        assertTrue(qRecent is IrisXIntent.QueryRecentWorkouts)

        val qUltimasBare = core.parseInput("últimas sesiones")
        assertTrue(qUltimasBare is IrisXIntent.QueryRecentWorkouts)

        val qHistorialEntrenos = core.parseInput("historial de entrenamientos")
        assertTrue(qHistorialEntrenos is IrisXIntent.QueryRecentWorkouts)

        val qUltimasEx = core.parseInput("últimas sesiones de sentadilla")
        assertTrue(qUltimasEx is IrisXIntent.QueryExerciseHistory)
        assertEquals("sentadilla", (qUltimasEx as IrisXIntent.QueryExerciseHistory).exerciseName)

        val qClear = core.parseInput("borrar historial de chat")
        assertTrue(qClear is IrisXIntent.ClearConversationHistory)

        val qAdvice = core.parseInput("¿Cuántas series recomendás para hipertrofia?")
        assertTrue(qAdvice is IrisXIntent.QueryRecommendations)
    }

    @Test
    fun `all five questions return honest insufficient or missing data on empty DB without inventing records`() = runBlocking {
        val core = IrisXCore(EmptyStubRepository())
        val conv = IrisxConversationEntity(id = 1L, title = "Activa")

        val r1 = core.ask("¿Cuál fue mi último entrenamiento?", conversation = conv) as IrisXResponse.Success
        assertEquals("QUERY_LAST_WORKOUT_TOOL_NO_DATA", r1.actionTaken)
        assertTrue(r1.message.contains("No tengo ese dato"))

        val r2 = core.ask("¿Cuántas series de sentadilla hice la última vez?", conversation = conv) as IrisXResponse.Success
        assertEquals("QUERY_LAST_EXERCISE_SESSION_NO_DATA", r2.actionTaken)
        assertTrue(r2.message.contains("No tengo ese dato"))

        val r3 = core.ask("¿Con cuánto peso hice sentadilla?", conversation = conv) as IrisXResponse.Success
        assertEquals("QUERY_LAST_EXERCISE_SESSION_NO_DATA", r3.actionTaken)
        assertTrue(r3.message.contains("No tengo ese dato"))

        val r4 = core.ask("¿Cómo progresé en sentadilla?", conversation = conv) as IrisXResponse.Success
        assertEquals("QUERY_PROGRESSION_EMPTY", r4.actionTaken)
        assertTrue(r4.message.contains("Datos insuficientes"))

        val r5 = core.ask("¿Qué entrenamientos hice recientemente?", conversation = conv) as IrisXResponse.Success
        assertEquals("QUERY_RECENT_WORKOUTS_NO_DATA", r5.actionTaken)
        assertTrue(r5.message.contains("No tengo ese dato"))
    }

    @Test
    fun `recent workouts tool reports zero-set sessions honestly without fabricating sets`() = runBlocking {
        val repo = object : EmptyStubRepository() {
            override suspend fun getAllSessions(): List<WorkoutSessionEntity> = listOf(
                WorkoutSessionEntity(id = 2L, title = "Cardio suave", startedAtMillis = baseTime),
                WorkoutSessionEntity(id = 1L, title = "Full Body", startedAtMillis = baseTime - dayMs)
            )
            override suspend fun getSetsForSession(sessionId: Long): List<WorkoutSetEntity> =
                if (sessionId == 1L) {
                    listOf(
                        WorkoutSetEntity(
                            id = 10L, sessionId = 1L, exerciseId = 1L,
                            orderInExercise = 1, executionOrder = 1,
                            loadGrams = 60_000, repetitions = 10
                        )
                    )
                } else emptyList()
            override suspend fun getExerciseNameMap(): Map<Long, String> = mapOf(1L to "Sentadilla")
        }

        val outcome = IrisXLocalTools(repo).execute(LocalToolRequest.RecentWorkouts())
        assertTrue(outcome is LocalToolOutcome.Success)
        val recent = (outcome as LocalToolOutcome.Success).data as LocalToolRecentWorkouts
        assertEquals(2, recent.workouts.size)
        assertEquals("Cardio suave", recent.workouts[0].title)
        assertEquals(0, recent.workouts[0].totalSets)
        assertEquals("Full Body", recent.workouts[1].title)
        assertEquals(1, recent.workouts[1].totalSets)

        val response = IrisXCore(repo).ask("¿Qué entrenamientos hice recientemente?") as IrisXResponse.Success
        assertTrue(response.message.contains("Cardio suave (sin series registradas)"))
        assertTrue(response.message.contains("Full Body (1 series: Sentadilla)"))
    }

    @Test
    fun `turn ledger preserves single-flight ownership and RecentWorkouts propagates cancellation`() = runBlocking {
        val ledger = IrisXTurnLedger()
        val firstTurn = ledger.beginTurn()
        assertNotNull(firstTurn)
        assertNull("Concurrent second turn must be rejected", ledger.beginTurn())
        assertTrue(ledger.isActive(firstTurn))
        assertTrue(ledger.finishTurn(firstTurn!!))
        assertFalse(ledger.hasActiveTurn)

        val blockingRepo = object : EmptyStubRepository() {
            override suspend fun getAllSessions(): List<WorkoutSessionEntity> = awaitCancellation()
        }
        val deferred = async {
            IrisXLocalTools(blockingRepo).execute(LocalToolRequest.RecentWorkouts())
        }
        val cancelJob = launch {
            yield()
            deferred.cancel()
        }
        cancelJob.join()
        var caught: Throwable? = null
        try {
            deferred.await()
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is CancellationException)
    }

    private open class EmptyStubRepository : IrisXRepository {
        private val persisted = mutableListOf<IrisxMessageEntity>()
        override suspend fun getLatestSession(): WorkoutSessionEntity? = null
        override suspend fun getSetsForSession(sessionId: Long): List<WorkoutSetEntity> = emptyList()
        override suspend fun getExerciseNameMap(): Map<Long, String> = emptyMap()
        override suspend fun findExerciseByNormalized(normalized: String) = null
        override suspend fun updateExercise(exercise: com.syntra.app.data.ExerciseEntity) {}
        override suspend fun getLearnedAliases(): Map<String, String> = emptyMap()
        override suspend fun saveLearnedAlias(alias: String, canonical: String) {}
        override suspend fun deleteLearnedAlias(alias: String) {}
        override suspend fun saveMemory(memoryType: String, sourceKey: String, targetValue: String) {}
        override suspend fun appendMessage(message: IrisxMessageEntity): Long {
            persisted.add(message)
            return persisted.size.toLong()
        }
    }
}
