package com.syntra.app.domain

import java.util.Locale

/**
 * Compact presentation model for workout sets.
 * Groups consecutive sets of the same exercise with identical load.
 * Preserves execution order and does not merge non-consecutive groups.
 */
data class CompactSetGroup(
    val weightGrams: Int,
    val repetitions: List<Int>,
    val rir: Int? = null,
    val rpe: Double? = null,
    val restSeconds: Int? = null
) {
    val setCount: Int get() = repetitions.size

    fun formattedWeight(): String = WorkoutPresentation.formatWeightKg(weightGrams)

    fun formattedReps(): String = repetitions.joinToString(", ")
}

data class CompactExercise(
    val name: String,
    val groups: List<CompactSetGroup>
) {
    val totalSeriesCount: Int get() = groups.sumOf { it.setCount }
}

object WorkoutPresentation {

    /**
     * Formats weight in kilograms avoiding unnecessary trailing decimals,
     * using Spanish decimal comma (e.g. 70 kg, 7,5 kg, 7,25 kg).
     */
    fun formatWeightKg(weightGrams: Int): String {
        if (weightGrams % 1000 == 0) {
            return "${weightGrams / 1000} kg"
        }
        val kg = weightGrams / 1000.0
        val raw = String.format(Locale.US, "%.2f", kg).trimEnd('0').trimEnd('.')
        return raw.replace('.', ',') + " kg"
    }

    /**
     * Transforms a list of individual ParsedSet into compact consecutive groups.
     * Consecutiveness is strictly preserved: separated sets with identical weight
     * are never merged together.
     */
    fun groupConsecutiveSets(sets: List<ParsedSet>): List<CompactSetGroup> {
        if (sets.isEmpty()) return emptyList()

        val groups = mutableListOf<CompactSetGroup>()
        var currentWeight = sets[0].weightGrams
        var currentReps = mutableListOf(sets[0].repetitions)

        for (i in 1 until sets.size) {
            val set = sets[i]
            if (set.weightGrams == currentWeight) {
                currentReps.add(set.repetitions)
            } else {
                groups.add(CompactSetGroup(currentWeight, currentReps.toList()))
                currentWeight = set.weightGrams
                currentReps = mutableListOf(set.repetitions)
            }
        }
        groups.add(CompactSetGroup(currentWeight, currentReps.toList()))
        return groups
    }

    /**
     * Converts a ParsedExercise to its compact presentation representation.
     */
    fun toCompact(exercise: ParsedExercise): CompactExercise {
        return CompactExercise(
            name = exercise.name,
            groups = groupConsecutiveSets(exercise.sets)
        )
    }

    /**
     * Converts a list of ParsedExercise to compact representations.
     */
    fun toCompactList(exercises: List<ParsedExercise>): List<CompactExercise> {
        return exercises.map { toCompact(it) }
    }
}
