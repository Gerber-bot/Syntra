package com.syntra.app.domain

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.irisx.IrisXAnalytics
import com.syntra.app.domain.irisx.IrisXCoach
import com.syntra.app.domain.irisx.ProgressionStatus
import com.syntra.app.domain.irisx.RecommendationCategory
import org.junit.Assert.*
import org.junit.Test

class DataIntegrityStageA6Test {
    private val sessions = listOf(
        WorkoutSessionEntity(id = 1, title = "S1", startedAtMillis = 1000),
        WorkoutSessionEntity(id = 2, title = "S2", startedAtMillis = 2000)
    )

    private fun exercise(id: Long, name: String, equipment: String? = null) = ExerciseEntity(
        id = id,
        displayName = name,
        normalizedName = "${WorkoutTextParser.normalize(name)} $id",
        muscleGroup = "Piernas",
        equipment = equipment
    )

    private fun sets(secondExerciseId: Long = 2) = listOf(
        WorkoutSetEntity(id = 1, sessionId = 1, exerciseId = 1, orderInExercise = 1,
            loadGrams = 13000, repetitions = 12, loadUnit = "BLOQUES"),
        WorkoutSetEntity(id = 2, sessionId = 2, exerciseId = secondExerciseId, orderInExercise = 1,
            loadGrams = 13000, repetitions = 12, loadUnit = "BLOQUES")
    )

    private fun report(
        exercises: List<ExerciseEntity>,
        aliases: Map<String, String> = emptyMap(),
        targetId: Long? = 1,
        query: String = exercises.first().displayName,
        secondExerciseId: Long = 2
    ) = IrisXAnalytics.analyzeExerciseProgression(
        exerciseName = query,
        sessions = sessions,
        allSets = sets(secondExerciseId),
        exercises = exercises,
        learnedAliases = aliases,
        targetExerciseId = targetId
    )

    private fun recommendations(
        exercises: List<ExerciseEntity>,
        aliases: Map<String, String> = emptyMap(),
        secondExerciseId: Long = 2
    ) = IrisXCoach.generateRecommendations(
        profile = null,
        sessions = sessions,
        allSets = sets(secondExerciseId),
        exercises = exercises,
        learnedAliases = aliases,
        referenceTimeMillis = 3000
    )

    private fun assertNoNumericRecommendation(
        exercises: List<ExerciseEntity>,
        aliases: Map<String, String> = emptyMap()
    ) {
        assertTrue(recommendations(exercises, aliases).none {
            it.category == RecommendationCategory.LOAD_PROGRESSION ||
                it.category == RecommendationCategory.STAGNATION_BREAKER
        })
    }

    @Test
    fun nameAliasDoesNotMergeDistinctIdsWithNullEquipment() {
        val exercises = listOf(exercise(1, "Prensa A"), exercise(2, "Prensa B"))
        val aliases = mapOf("prensa b" to "Prensa A")
        val actual = report(exercises, aliases)
        assertEquals(listOf(1L), actual.history.map { it.exerciseId })
        assertEquals(ProgressionStatus.INSUFFICIENT_DATA, actual.status)
        assertNoNumericRecommendation(exercises, aliases)
    }

    @Test
    fun distinctIdsWithUnknownEquipmentStaySeparateWithoutAliases() {
        val exercises = listOf(exercise(1, "Prensa A"), exercise(2, "Prensa B"))
        assertEquals(listOf(1L), report(exercises).history.map { it.exerciseId })
        assertNoNumericRecommendation(exercises)
    }

    @Test
    fun identicalNamesWithUnknownEquipmentRequireAnId() {
        val exercises = listOf(exercise(1, "Prensa"), exercise(2, "Prensa"))
        val actual = report(exercises, targetId = null)
        assertEquals(ProgressionStatus.INSUFFICIENT_DATA, actual.status)
        assertTrue(actual.history.isEmpty())
        assertEquals(listOf(1L), report(exercises).history.map { it.exerciseId })
        assertNoNumericRecommendation(exercises)
    }

    @Test
    fun identicalEquipmentLabelsDoNotDisambiguateDifferentIds() {
        val exercises = listOf(exercise(1, "Prensa", "Matrix"), exercise(2, "Prensa", "Matrix"))
        val actual = report(exercises, targetId = null)
        assertEquals(ProgressionStatus.INSUFFICIENT_DATA, actual.status)
        assertTrue(actual.history.isEmpty())
        assertNoNumericRecommendation(exercises)
    }

    @Test
    fun partiallyMatchingNamesNeverMerge() {
        val exercises = listOf(exercise(1, "Prensa"), exercise(2, "Prensa 45"))
        val actual = report(exercises, targetId = null)
        assertEquals(listOf(1L), actual.history.map { it.exerciseId })
        assertEquals(ProgressionStatus.INSUFFICIENT_DATA, actual.status)
        assertNoNumericRecommendation(exercises)
    }

    @Test
    fun sameStableIdRetainsLegitimateHistoryWithoutEquipmentMetadata() {
        val exercises = listOf(exercise(1, "Prensa"))
        val actual = report(exercises, secondExerciseId = 1)
        assertEquals(listOf(1L, 1L), actual.history.map { it.exerciseId })
        assertEquals(ProgressionStatus.MAINTAINING, actual.status)
        assertTrue(recommendations(exercises, secondExerciseId = 1).any {
            it.category == RecommendationCategory.LOAD_PROGRESSION && it.proposal.contains("14 bloques")
        })
    }

    @Test
    fun knownDifferentEquipmentStaysIsolatedEvenWithNameAlias() {
        val exercises = listOf(exercise(1, "Prensa A", "Matrix"), exercise(2, "Prensa B", "Nautilus"))
        val aliases = mapOf("prensa b" to "Prensa A")
        assertEquals(listOf(1L), report(exercises, aliases).history.map { it.exerciseId })
        assertNoNumericRecommendation(exercises, aliases)
    }

    @Test
    fun nameAliasAndSameEquipmentLabelAreNotPhysicalEquivalence() {
        val exercises = listOf(exercise(1, "Prensa A", "Matrix"), exercise(2, "Prensa B", "Matrix"))
        val aliases = mapOf("prensa b" to "Prensa A")
        assertEquals(listOf(1L), report(exercises, aliases).history.map { it.exerciseId })
        assertNoNumericRecommendation(exercises, aliases)
    }

    @Test
    fun blankAndUnknownEquipmentDoNotBecomeEquivalentThroughAlias() {
        val exercises = listOf(exercise(1, "Prensa A", "  "), exercise(2, "Prensa B"))
        val aliases = mapOf("prensa b" to "Prensa A")
        assertEquals(listOf(1L), report(exercises, aliases).history.map { it.exerciseId })
        assertNoNumericRecommendation(exercises, aliases)
    }

    @Test
    fun confirmedAliasStillResolvesOneUnambiguousStableId() {
        val exercises = listOf(exercise(1, "Prensa A"))
        val actual = report(exercises, aliases = mapOf("prensa" to "Prensa A"),
            targetId = null, query = "prensa", secondExerciseId = 1)
        assertEquals(listOf(1L, 1L), actual.history.map { it.exerciseId })
        assertEquals(ProgressionStatus.MAINTAINING, actual.status)
    }
}
