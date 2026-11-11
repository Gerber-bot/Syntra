package com.syntra.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.irisx.IrisXCore
import com.syntra.app.domain.irisx.IrisXResponse
import com.syntra.app.domain.irisx.RoomIrisXRepository
import com.syntra.app.ui.irisx.IrisXTurnRoute
import com.syntra.app.ui.irisx.IrisXTurnRouter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Room integration test for Stage B.2 using an isolated in-memory database.
 * Never opens `syntra.db` or touches personal user records.
 */
@RunWith(AndroidJUnit4::class)
class IrisXB2RoomInstrumentedTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun realRoomRepositoryAnswersFiveB2QueriesWithoutInventingData() {
        runBlocking {
            val squatId = db.exerciseDao().insert(
                ExerciseEntity(
                    displayName = "Sentadilla",
                    normalizedName = "sentadilla",
                    muscleGroup = "Piernas",
                    equipment = "Barra"
                )
            )
            val benchId = db.exerciseDao().insert(
                ExerciseEntity(
                    displayName = "Press de banca",
                    normalizedName = "press de banca",
                    muscleGroup = "Pecho",
                    equipment = "Barra"
                )
            )

            val now = 1_760_000_000_000L
            val dayMs = 86_400_000L

            val s1 = db.workoutSessionDao().insert(
                WorkoutSessionEntity(title = "Piernas Base", startedAtMillis = now - 6 * dayMs)
            )
            db.workoutSetDao().insert(
                WorkoutSetEntity(sessionId = s1, exerciseId = squatId, orderInExercise = 1, executionOrder = 1, loadGrams = 95_000, repetitions = 6)
            )

            val s2 = db.workoutSessionDao().insert(
                WorkoutSessionEntity(title = "Piernas Fuerza", startedAtMillis = now - 3 * dayMs)
            )
            db.workoutSetDao().insert(
                WorkoutSetEntity(sessionId = s2, exerciseId = squatId, orderInExercise = 1, executionOrder = 1, loadGrams = 105_000, repetitions = 5)
            )
            db.workoutSetDao().insert(
                WorkoutSetEntity(sessionId = s2, exerciseId = squatId, orderInExercise = 2, executionOrder = 2, loadGrams = 100_000, repetitions = 6)
            )

            val s3 = db.workoutSessionDao().insert(
                WorkoutSessionEntity(title = "Pecho Pesado", startedAtMillis = now - dayMs)
            )
            db.workoutSetDao().insert(
                WorkoutSetEntity(sessionId = s3, exerciseId = benchId, orderInExercise = 1, executionOrder = 1, loadGrams = 85_000, repetitions = 5)
            )

            val repo = RoomIrisXRepository(db)
            val core = IrisXCore(repo)
            val conv = repo.getOrCreateActiveConversation()

            val q1 = "¿Cuál fue mi último entrenamiento?"
            val q2 = "¿Cuántas series de sentadilla hice la última vez?"
            val q3 = "¿Con cuánto peso hice sentadilla?"
            val q4 = "¿Cómo progresé en sentadilla?"
            val q5 = "¿Qué entrenamientos hice recientemente?"

            listOf(q1, q2, q3, q4, q5).forEach { q ->
                assertEquals(
                    IrisXTurnRoute.DETERMINISTIC,
                    IrisXTurnRouter.resolveRoute(core.parseInput(q), neuralReady = true, guardianBlocked = false)
                )
            }

            val r1 = core.ask(q1, referenceTimeMillis = now, conversation = conv) as IrisXResponse.Success
            assertEquals("QUERY_LAST_WORKOUT_TOOL", r1.actionTaken)
            assertTrue(r1.message.contains("Pecho Pesado"))
            assertTrue(r1.message.contains("85 kg"))

            val r2 = core.ask(q2, referenceTimeMillis = now, conversation = conv) as IrisXResponse.Success
            assertEquals("QUERY_LAST_EXERCISE_SESSION", r2.actionTaken)
            assertTrue(r2.message.contains("Series ejecutadas (2)"))

            val r3 = core.ask(q3, referenceTimeMillis = now, conversation = conv) as IrisXResponse.Success
            assertEquals("QUERY_LAST_EXERCISE_SESSION", r3.actionTaken)
            assertTrue(r3.message.contains("105 kg"))

            val r4 = core.ask(q4, referenceTimeMillis = now, conversation = conv) as IrisXResponse.Success
            assertEquals("QUERY_EXERCISE_PROGRESSION", r4.actionTaken)
            assertTrue(r4.message.contains("Progresión de Sentadilla"))

            val r5 = core.ask(q5, referenceTimeMillis = now, conversation = conv) as IrisXResponse.Success
            assertEquals("QUERY_RECENT_WORKOUTS", r5.actionTaken)
            assertTrue(r5.message.contains("3 de 3 registrados"))

            val persisted = repo.getMessages(conv.id)
            assertEquals(10, persisted.size)
        }
    }
}
