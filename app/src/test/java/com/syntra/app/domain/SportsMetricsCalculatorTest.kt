package com.syntra.app.domain

import com.syntra.app.data.WorkoutSetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsMetricsCalculatorTest {

    @Test
    fun `calculates barbell volume accurately`() {
        val sets = listOf(
            WorkoutSetEntity(
                sessionId = 1, exerciseId = 1, orderInExercise = 1,
                loadGrams = 80000, repetitions = 10 // 80 kg * 10 = 800 kg
            ),
            WorkoutSetEntity(
                sessionId = 1, exerciseId = 1, orderInExercise = 2,
                loadGrams = 80000, repetitions = 8 // 80 kg * 8 = 640 kg
            )
        )

        val metrics = SportsMetricsCalculator.calculateSessionVolume(sets)
        assertEquals(1440.0, metrics.totalExternalKg, 0.001)
        assertEquals(1440.0, metrics.barbellMachineKg, 0.001)
        assertEquals(0.0, metrics.dumbbellTotalKg, 0.001)
        assertEquals(2, metrics.regularSetsCount)
        assertFalse(metrics.isEstimated)
    }

    @Test
    fun `calculates dumbbell volume multiplying by two for bilateral movement`() {
        // User lifts 25 kg dumbbells for 10 reps -> 25 kg each hand = 50 kg total pair per rep
        val sets = listOf(
            WorkoutSetEntity(
                sessionId = 1, exerciseId = 2, orderInExercise = 1,
                loadGrams = 25000, repetitions = 10, isDumbbell = true
            )
        )

        val metrics = SportsMetricsCalculator.calculateSessionVolume(sets)
        // 25 kg * 2 * 10 reps = 500 kg total bilateral external volume
        assertEquals(500.0, metrics.totalExternalKg, 0.001)
        assertEquals(500.0, metrics.dumbbellTotalKg, 0.001)
        assertEquals(1, metrics.dumbbellSetsCount)
    }

    @Test
    fun `assisted sets are tracked separately and not counted as positive external load`() {
        val sets = listOf(
            WorkoutSetEntity(
                sessionId = 1, exerciseId = 3, orderInExercise = 1,
                loadGrams = 15000, repetitions = 8, isAssisted = true // -15 kg assistance
            )
        )

        val metrics = SportsMetricsCalculator.calculateSessionVolume(sets)
        // External load lifted is not 15 kg positive! It is counterweight.
        assertEquals(0.0, metrics.totalExternalKg, 0.001)
        assertEquals(120.0, metrics.assistedWeightKg, 0.001) // 15 kg * 8 = 120 kg assistance
        assertEquals(1, metrics.assistedSetsCount)
        assertTrue(metrics.isEstimated)
    }

    @Test
    fun `mixed session calculates components cleanly`() {
        val sets = listOf(
            WorkoutSetEntity(sessionId = 1, exerciseId = 1, orderInExercise = 1, loadGrams = 100000, repetitions = 5), // 500 kg
            WorkoutSetEntity(sessionId = 1, exerciseId = 2, orderInExercise = 1, loadGrams = 20000, repetitions = 10, isDumbbell = true), // 400 kg
            WorkoutSetEntity(sessionId = 1, exerciseId = 3, orderInExercise = 1, loadGrams = 0, repetitions = 12), // bodyweight
            WorkoutSetEntity(sessionId = 1, exerciseId = 4, orderInExercise = 1, loadGrams = 10000, repetitions = 8, isAssisted = true) // assisted
        )

        val metrics = SportsMetricsCalculator.calculateSessionVolume(sets)
        assertEquals(900.0, metrics.totalExternalKg, 0.001)
        assertEquals(500.0, metrics.barbellMachineKg, 0.001)
        assertEquals(400.0, metrics.dumbbellTotalKg, 0.001)
        assertEquals(1, metrics.bodyweightSetsCount)
        assertEquals(1, metrics.assistedSetsCount)
        assertTrue(metrics.isEstimated)
        assertTrue(metrics.summaryFormatted.contains("900 kg"))
    }
}
