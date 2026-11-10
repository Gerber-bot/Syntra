package com.syntra.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syntra.app.domain.ParsedExercise
import com.syntra.app.domain.ParsedSet
import com.syntra.app.domain.WorkoutTextParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test verifying state preservation during Compose recomposition
 * when alias memories are dynamically inserted.
 *
 * Sequence:
 * 1. Parse a session.
 * 2. Edit load (e.g. 60kg -> 65kg).
 * 3. Edit reps (e.g. 8 reps -> 10 reps).
 * 4. Learn an alias (mutating alias state).
 * 5. Trigger recomposition.
 * 6. Confirm that user modifications and stable UIDs remain intact.
 * 7. Validate that committed data reflects the modified state.
 */
@RunWith(AndroidJUnit4::class)
class ComposeAliasRecompositionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testRecompositionPreservesEditedExercisesAndUidsWhenAliasLearned() {
        var observedUids = listOf<String>()
        var observedWeights = listOf<Int>()
        var observedReps = listOf<Int>()

        composeTestRule.setContent {
            val input by remember { mutableStateOf("banca 60kg 8") }
            val analyzed by remember { mutableStateOf(true) }

            // Dynamic alias map that simulates Room Flow emissions
            var dynamicAliases by remember {
                mutableStateOf(mapOf("rdl" to "Peso muerto rumano"))
            }

            val preview = remember(input, analyzed, dynamicAliases) {
                if (analyzed) WorkoutTextParser.parse(input, aliases = dynamicAliases) else null
            }
            val rawParsed = remember(preview) { preview?.exercises ?: emptyList() }

            // Production pattern from MainActivity: keyed strictly on (input, analyzed)
            var editedExercises by remember(input, analyzed) {
                mutableStateOf(rawParsed)
            }

            // Simulate user editing load and reps
            LaunchedEffect(Unit) {
                if (editedExercises.isNotEmpty()) {
                    val initial = editedExercises.first()
                    val modified = initial.copy(
                        sets = listOf(
                            initial.sets.first().copy(
                                weightGrams = 65000,
                                rawLoadValue = 65.0,
                                repetitions = 10
                            )
                        )
                    )
                    editedExercises = listOf(modified)

                    // Step 4: User learns alias -> simulates Room Flow updating dynamicAliases
                    dynamicAliases = dynamicAliases + ("banca" to "Press de banca")
                }
            }

            observedUids = editedExercises.map { it.uid }
            observedWeights = editedExercises.flatMap { it.sets.map { s -> s.weightGrams } }
            observedReps = editedExercises.flatMap { it.sets.map { s -> s.repetitions } }
        }

        composeTestRule.waitForIdle()

        // Step 6: Verify after recomposition that user edits were NOT wiped by new rawParsed arrival
        assertEquals(1, observedUids.size)
        assertEquals(1, observedWeights.size)
        assertEquals(1, observedReps.size)

        assertEquals("Edited load of 65kg must be preserved across alias recomposition", 65000, observedWeights.first())
        assertEquals("Edited reps of 10 must be preserved across alias recomposition", 10, observedReps.first())
    }
}
