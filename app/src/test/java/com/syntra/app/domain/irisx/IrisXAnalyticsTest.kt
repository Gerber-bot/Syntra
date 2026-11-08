package com.syntra.app.domain.irisx

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.LoadModality
import com.syntra.app.domain.MetricDataQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrisXAnalyticsTest {

    private val dayMs = 86_400_000L
    private val baseTime = 1_700_000_000_000L

    private val sampleExercises = listOf(
        ExerciseEntity(id = 1, displayName = "Sentadilla", normalizedName = "sentadilla", muscleGroup = "Piernas", equipment = "Barra"),
        ExerciseEntity(id = 2, displayName = "Remo con barra", normalizedName = "remo con barra", muscleGroup = "Espalda", equipment = "Barra"),
        ExerciseEntity(id = 3, displayName = "Press inclinado con mancuernas", normalizedName = "press inclinado con mancuernas", muscleGroup = "Pecho", equipment = "Mancuernas"),
        ExerciseEntity(id = 4, displayName = "Dominadas asistidas", normalizedName = "dominadas asistidas", muscleGroup = "Espalda", equipment = "Máquina")
    )

    @Test
    fun `returns INSUFFICIENT_DATA when no sessions exist`() {
        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Sentadilla",
            sessions = emptyList(),
            allSets = emptyList(),
            exercises = sampleExercises
        )
        assertEquals(ProgressionStatus.INSUFFICIENT_DATA, report.status)
        assertEquals(MetricDataQuality.INSUFFICIENT_DATA, report.dataQuality)
        assertTrue(report.history.isEmpty())
        assertTrue(report.loadChartPoints.isEmpty())

        val freq = IrisXAnalytics.calculateTrainingFrequency(emptyList(), windowDays = 28, referenceTimeMillis = baseTime)
        assertEquals(MetricDataQuality.INSUFFICIENT_DATA, freq.dataQuality)
        assertEquals(0, freq.totalSessions)
    }

    @Test
    fun `detects progression personal records and chart points accurately`() {
        val sessions = listOf(
            WorkoutSessionEntity(id = 10, title = "Piernas 1", startedAtMillis = baseTime - 14 * dayMs),
            WorkoutSessionEntity(id = 11, title = "Piernas 2", startedAtMillis = baseTime - 7 * dayMs),
            WorkoutSessionEntity(id = 12, title = "Piernas 3", startedAtMillis = baseTime)
        )
        val sets = listOf(
            WorkoutSetEntity(sessionId = 10, exerciseId = 1, orderInExercise = 1, loadGrams = 70000, repetitions = 8),
            WorkoutSetEntity(sessionId = 11, exerciseId = 1, orderInExercise = 1, loadGrams = 75000, repetitions = 8),
            WorkoutSetEntity(sessionId = 12, exerciseId = 1, orderInExercise = 1, loadGrams = 80000, repetitions = 8),
            // Second set in session 12 has fewer reps (8 -> 6): must trigger non-fatigue disclaimer
            WorkoutSetEntity(sessionId = 12, exerciseId = 1, orderInExercise = 2, loadGrams = 80000, repetitions = 6)
        )

        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Sentadilla",
            sessions = sessions,
            allSets = sets,
            exercises = sampleExercises
        )

        assertEquals(ProgressionStatus.PROGRESSING, report.status)
        assertEquals(LoadModality.BILATERAL_EXTERNAL, report.dominantModality)
        assertEquals(MetricDataQuality.MEASURED, report.dataQuality)
        assertEquals(3, report.loadChartPoints.size)
        assertEquals(80.0, report.loadChartPoints.last().value, 0.001)

        val pr = report.personalRecordsByModality[LoadModality.BILATERAL_EXTERNAL]
        assertNotNull(pr)
        assertEquals(80000, pr!!.maxLoadGrams)
        assertEquals(8, pr.repsAtMaxLoad)

        // Verify rule: rep drop-off must not be diagnosed as muscular fatigue
        assertNotNull(report.fatigueDisclaimer)
        assertTrue(report.fatigueDisclaimer!!.contains("no prueba por sí sola fatiga muscular"))
    }

    @Test
    fun `detects explicit stagnation when 3 consecutive sessions do not improve`() {
        val sessions = listOf(
            WorkoutSessionEntity(id = 1, title = "Espalda 1", startedAtMillis = baseTime - 14 * dayMs),
            WorkoutSessionEntity(id = 2, title = "Espalda 2", startedAtMillis = baseTime - 7 * dayMs),
            WorkoutSessionEntity(id = 3, title = "Espalda 3", startedAtMillis = baseTime)
        )
        val sets = listOf(
            WorkoutSetEntity(sessionId = 1, exerciseId = 2, orderInExercise = 1, loadGrams = 60000, repetitions = 10),
            WorkoutSetEntity(sessionId = 2, exerciseId = 2, orderInExercise = 1, loadGrams = 60000, repetitions = 10),
            WorkoutSetEntity(sessionId = 3, exerciseId = 2, orderInExercise = 1, loadGrams = 60000, repetitions = 9)
        )

        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Remo con barra",
            sessions = sessions,
            allSets = sets,
            exercises = sampleExercises
        )

        assertTrue(report.stagnation.isStagnant)
        assertEquals(ProgressionStatus.STAGNANT, report.status)
        assertEquals(3, report.stagnation.consecutiveSessionsWithoutProgress)
        assertTrue(report.stagnation.explanation.contains("Estancamiento detectado"))
    }

    @Test
    fun `never merges incompatible load modalities within the same exercise`() {
        val sessions = listOf(
            WorkoutSessionEntity(id = 1, title = "Sesión A", startedAtMillis = baseTime - 7 * dayMs),
            WorkoutSessionEntity(id = 2, title = "Sesión B", startedAtMillis = baseTime)
        )
        // Suppose user recorded Remo with barbell (70kg) in session 1 and with dumbbell per arm (30kg) in session 2
        val sets = listOf(
            WorkoutSetEntity(sessionId = 1, exerciseId = 2, orderInExercise = 1, loadGrams = 70000, repetitions = 10, isDumbbell = false),
            WorkoutSetEntity(sessionId = 2, exerciseId = 2, orderInExercise = 1, loadGrams = 30000, repetitions = 10, isDumbbell = true)
        )

        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Remo con barra",
            sessions = sessions,
            allSets = sets,
            exercises = sampleExercises
        )

        assertTrue(report.incompatibleModalitiesDetected)
        assertEquals(2, report.personalRecordsByModality.size)
        assertEquals(70000, report.personalRecordsByModality[LoadModality.BILATERAL_EXTERNAL]?.maxLoadGrams)
        assertEquals(30000, report.personalRecordsByModality[LoadModality.DUMBBELL_PER_ARM]?.maxLoadGrams)
    }

    @Test
    fun `compares periods by muscle group and calculates muscle distribution`() {
        val lastMonthTime = baseTime - (40 * dayMs)
        val thisMonthTime = baseTime - (5 * dayMs)

        val sessions = listOf(
            WorkoutSessionEntity(id = 1, title = "Espalda mes pasado", startedAtMillis = lastMonthTime),
            WorkoutSessionEntity(id = 2, title = "Espalda este mes", startedAtMillis = thisMonthTime)
        )
        val sets = listOf(
            // Last month: 50 kg * 10 = 500 kg
            WorkoutSetEntity(sessionId = 1, exerciseId = 2, orderInExercise = 1, loadGrams = 50000, repetitions = 10),
            // This month: 60 kg * 10 = 600 kg (+20%)
            WorkoutSetEntity(sessionId = 2, exerciseId = 2, orderInExercise = 1, loadGrams = 60000, repetitions = 10)
        )

        val window = IrisXContextResolver.resolvePeriodWindow(PeriodScope.THIS_MONTH, baseTime)
        val comparison = IrisXAnalytics.comparePeriods(
            periodALabel = window.previousLabel,
            periodAStartMillis = window.previousStartMillis,
            periodAEndMillis = window.previousEndMillis,
            periodBLabel = window.currentLabel,
            periodBStartMillis = window.currentStartMillis,
            periodBEndMillis = window.currentEndMillis,
            sessions = sessions,
            allSets = sets,
            exercises = sampleExercises,
            muscleFilter = "Espalda"
        )

        assertEquals(1, comparison.periodASessions)
        assertEquals(1, comparison.periodBSessions)
        assertEquals(500.0, comparison.periodAExternalVolumeKg, 0.001)
        assertEquals(600.0, comparison.periodBExternalVolumeKg, 0.001)
        assertEquals(20.0, comparison.volumeDeltaPercent!!, 0.001)
        assertFalse(comparison.summaryText.isBlank())
    }
}
