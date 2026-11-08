package com.syntra.app.domain

import java.util.Locale

/**
 * Compact presentation model for workout sets.
 * Groups consecutive sets of the same exercise with identical load into table rows.
 * Preserves execution order and does not merge non-consecutive groups.
 */
data class CompactSetGroup(
    val weightGrams: Int,
    val repetitions: List<Int>,
    val rir: Int? = null,
    val rpe: Double? = null,
    val restSeconds: Int? = null,
    val isAssisted: Boolean = false,
    val isDumbbell: Boolean = false,
    val note: String = ""
) {
    val setCount: Int get() = repetitions.size

    fun formattedWeight(): String {
        val base = WorkoutPresentation.formatWeightKg(weightGrams)
        return when {
            isAssisted -> "-$base (asistido)"
            isDumbbell -> "$base (mancuerna)"
            note.isNotBlank() -> "$base ($note)"
            else -> base
        }
    }

    fun formattedReps(): String = repetitions.joinToString(", ")
}

data class CompactExercise(
    val name: String,
    val groups: List<CompactSetGroup>,
    val plannedTarget: String? = null,
    val isNewExercise: Boolean = false
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
        var currentAssisted = sets[0].isAssisted
        var currentDumbbell = sets[0].isDumbbell
        var currentNote = sets[0].note
        var currentReps = mutableListOf(sets[0].repetitions)

        for (i in 1 until sets.size) {
            val set = sets[i]
            if (set.weightGrams == currentWeight &&
                set.isAssisted == currentAssisted &&
                set.isDumbbell == currentDumbbell &&
                set.note == currentNote) {
                currentReps.add(set.repetitions)
            } else {
                groups.add(
                    CompactSetGroup(
                        weightGrams = currentWeight,
                        repetitions = currentReps.toList(),
                        isAssisted = currentAssisted,
                        isDumbbell = currentDumbbell,
                        note = currentNote
                    )
                )
                currentWeight = set.weightGrams
                currentAssisted = set.isAssisted
                currentDumbbell = set.isDumbbell
                currentNote = set.note
                currentReps = mutableListOf(set.repetitions)
            }
        }
        groups.add(
            CompactSetGroup(
                weightGrams = currentWeight,
                repetitions = currentReps.toList(),
                isAssisted = currentAssisted,
                isDumbbell = currentDumbbell,
                note = currentNote
            )
        )
        return groups
    }

    /**
     * Converts a single ParsedExercise to its compact presentation representation.
     */
    fun toCompact(exercise: ParsedExercise): CompactExercise {
        return CompactExercise(
            name = exercise.name,
            groups = groupConsecutiveSets(exercise.sets),
            plannedTarget = exercise.plannedTarget,
            isNewExercise = exercise.isNewExercise
        )
    }

    /**
     * Consolidates parsed exercises into ONE card per exercise.
     * Multiple lines of the same exercise (with same or different weights)
     * are merged into a single CompactExercise whose table contains multiple rows.
     */
    fun toCompactList(exercises: List<ParsedExercise>): List<CompactExercise> {
        if (exercises.isEmpty()) return emptyList()

        val exerciseMap = linkedMapOf<String, Triple<String, MutableList<ParsedSet>, MutableList<String>>>()
        val newExerciseMap = mutableMapOf<String, Boolean>()

        for (exercise in exercises) {
            val key = WorkoutTextParser.normalize(exercise.name)
            val entry = exerciseMap.getOrPut(key) { Triple(exercise.name, mutableListOf(), mutableListOf()) }
            entry.second.addAll(exercise.sets)
            if (exercise.plannedTarget != null) {
                entry.third.add(exercise.plannedTarget)
            }
            if (exercise.isNewExercise) {
                newExerciseMap[key] = true
            }
        }

        return exerciseMap.map { (key, triple) ->
            val (displayName, allSets, plans) = triple
            CompactExercise(
                name = displayName,
                groups = groupConsecutiveSets(allSets),
                plannedTarget = plans.firstOrNull(),
                isNewExercise = newExerciseMap[key] == true
            )
        }
    }
}
