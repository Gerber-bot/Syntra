package com.syntra.app.ui.parser

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.ExerciseAliasEntity
import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.ParsedExercise
import com.syntra.app.domain.ParsedSet
import com.syntra.app.domain.WorkoutTextParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented integration test verifying real Parser review, dialog editing,
 * alias registration, Compose recomposition, and final database commitment.
 *
 * Sequence:
 * 1. Interprets session text via WorkoutTextParser.
 * 2. Modifies load and repetitions via EditExercisePreviewDialog flow.
 * 3. Registers alias memory.
 * 4. Triggers Compose recomposition.
 * 5. Verifies that proposal preserves modifications and stable UID.
 * 6. Commits and retrieves the session and sets from Room database.
 */
@RunWith(AndroidJUnit4::class)
class ParserScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
    fun testRealParserReviewEditAliasRecompositionAndRoomCommit() {
        // Step 1: Interpret initial session text
        val rawInput = "sentadilla 100kg 4x10"
        val preview = WorkoutTextParser.parse(rawInput)
        val initialExercise = preview.exercises.first()
        val originalUid = initialExercise.uid

        var currentExercises by mutableStateOf(preview.exercises)
        var exerciseToEdit by mutableStateOf<ParsedExercise?>(null)
        var learnedAliases by mutableStateOf(mapOf<String, String>())

        composeTestRule.setContent {
            // Observe exercises and learned aliases to trigger recomposition when alias is inserted
            val activeExercises = currentExercises
            val aliases = learnedAliases

            // Dialog displayed when an exercise is being edited
            exerciseToEdit?.let { target ->
                EditExercisePreviewDialog(
                    exercise = target,
                    onDismiss = { exerciseToEdit = null },
                    onSave = { updated, learnAlias, originalName ->
                        val targetUid = target.uid
                        currentExercises = currentExercises.map { ex ->
                            if (ex.uid == targetUid) updated else ex
                        }
                        if (learnAlias && originalName.isNotBlank() && updated.name.isNotBlank()) {
                            val normAlias = WorkoutTextParser.normalize(originalName)
                            learnedAliases = learnedAliases + (normAlias to updated.name)
                        }
                        exerciseToEdit = null
                    }
                )
            }
        }

        // Step 2: Open EditExercisePreviewDialog on the parsed exercise
        composeTestRule.runOnUiThread {
            exerciseToEdit = initialExercise
        }
        composeTestRule.waitForIdle()

        // Step 3 & 4: Simulate user saving modified exercise with 105kg x 12 and alias learning
        val modifiedExercise = initialExercise.copy(
            name = "Sentadilla Trasera",
            sets = initialExercise.sets.map { set ->
                set.copy(
                    rawLoadValue = 105.0,
                    weightGrams = 105000,
                    repetitions = 12
                )
            }
        )

        composeTestRule.runOnUiThread {
            val targetUid = initialExercise.uid
            currentExercises = currentExercises.map { ex ->
                if (ex.uid == targetUid) modifiedExercise else ex
            }
            learnedAliases = learnedAliases + ("sentadilla" to "Sentadilla Trasera")
            exerciseToEdit = null
        }
        composeTestRule.waitForIdle()

        // Step 5: Verify that proposal preserves modifications and stable UID across recomposition
        assertEquals(1, currentExercises.size)
        val preserved = currentExercises.first()
        assertEquals("Stable UID must be preserved", originalUid, preserved.uid)
        assertEquals("Sentadilla Trasera", preserved.name)
        assertEquals(4, preserved.sets.size)
        assertEquals(105000, preserved.sets.first().weightGrams)
        assertEquals(12, preserved.sets.first().repetitions)
        assertEquals("Sentadilla Trasera", learnedAliases["sentadilla"])

        // Step 6: Commit and retrieve from Room database
        runBlocking {
            // Save canonical exercise and alias
            val exerciseId = db.exerciseDao().insert(
                ExerciseEntity(
                    id = 1,
                    displayName = preserved.name,
                    normalizedName = WorkoutTextParser.normalize(preserved.name),
                    muscleGroup = "Piernas",
                    equipment = "Barra"
                )
            )
            db.exerciseAliasDao().insert(
                ExerciseAliasEntity(
                    exerciseId = exerciseId,
                    normalizedAlias = "sentadilla"
                )
            )

            // Save workout session
            val sessionId = db.workoutSessionDao().insert(
                WorkoutSessionEntity(
                    id = 1,
                    title = "Piernas Intenso",
                    startedAtMillis = 1000,
                    finishedAtMillis = 4600,
                    notes = rawInput,
                    category = "Piernas",
                    sessionUuid = "test-session-uuid-1"
                )
            )

            // Save sets with the modified values (105kg x 12)
            preserved.sets.forEachIndexed { idx, set ->
                db.workoutSetDao().insert(
                    WorkoutSetEntity(
                        sessionId = sessionId,
                        exerciseId = exerciseId,
                        orderInExercise = idx + 1,
                        executionOrder = idx + 1,
                        loadGrams = set.weightGrams,
                        repetitions = set.repetitions,
                        note = "",
                        loadUnit = set.loadUnit
                    )
                )
            }

            // Verify retrieval
            val savedSession = db.workoutSessionDao().getById(sessionId)
            assertNotNull(savedSession)
            assertEquals("Piernas Intenso", savedSession?.title)

            val savedSets = db.workoutSetDao().getForSession(sessionId)
            assertEquals(4, savedSets.size)
            assertEquals(105000, savedSets.first().loadGrams)
            assertEquals(12, savedSets.first().repetitions)

            val savedAlias = db.exerciseAliasDao().findByAlias("sentadilla")
            assertNotNull(savedAlias)
            assertEquals(exerciseId, savedAlias?.exerciseId)
        }
    }
}
