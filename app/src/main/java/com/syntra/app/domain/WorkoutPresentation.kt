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
    val isToFailure: Boolean = false,
    val loadUnit: String = "KG",
    val rawLoadValue: Double = weightGrams / 1000.0,
    val note: String = "",
    val repNotations: List<String> = emptyList()
) {
    val setCount: Int get() = repetitions.size

    fun formattedWeight(): String {
        val base = when (loadUnit.uppercase()) {
            "BLOQUES" -> "${rawLoadValue.toInt()} bloques"
            "PLACAS" -> "${rawLoadValue.toInt()} placas"
            "LBS", "LB" -> "${rawLoadValue.toInt()} lb"
            "BW" -> "Peso corporal"
            else -> WorkoutPresentation.formatWeightKg(weightGrams)
        }
        return when {
            isAssisted -> "-$base (asistido)"
            isDumbbell && note.contains("[UNILATERAL]") -> "$base (mancuerna unilat.)"
            isDumbbell -> "$base (mancuerna)"
            note == "[LASTRE]" -> "+$base (lastre)"
            note == "[UNILATERAL]" -> "$base (unilateral)"
            note.isNotBlank() && !note.startsWith("[FALLO") -> "$base ($note)"
            else -> base
        }
    }

    fun formattedReps(): String {
        return if (repNotations.isNotEmpty() && repNotations.size == repetitions.size) {
            repNotations.joinToString(", ")
        } else if (isToFailure) {
            repetitions.joinToString(", ") { "$it f" }
        } else {
            repetitions.joinToString(", ")
        }
    }
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
        var currentUnit = sets[0].loadUnit
        var currentRawLoad = sets[0].rawLoadValue
        var currentAssisted = sets[0].isAssisted
        var currentDumbbell = sets[0].isDumbbell
        var currentNote = sets[0].note
        var currentReps = mutableListOf(sets[0].repetitions)
        var currentRepNotations = mutableListOf(if (sets[0].isToFailure) "${sets[0].repetitions}f" else "${sets[0].repetitions}")

        fun baseNote(n: String) = n.replace("[FALLO]", "").trim()

        for (i in 1 until sets.size) {
            val set = sets[i]
            val notation = if (set.isToFailure) "${set.repetitions}f" else "${set.repetitions}"
            if (set.weightGrams == currentWeight &&
                set.loadUnit.equals(currentUnit, ignoreCase = true) &&
                set.isAssisted == currentAssisted &&
                set.isDumbbell == currentDumbbell &&
                baseNote(set.note) == baseNote(currentNote)) {
                currentReps.add(set.repetitions)
                currentRepNotations.add(notation)
            } else {
                groups.add(
                    CompactSetGroup(
                        weightGrams = currentWeight,
                        repetitions = currentReps.toList(),
                        isAssisted = currentAssisted,
                        isDumbbell = currentDumbbell,
                        isToFailure = currentRepNotations.any { it.endsWith("f") },
                        loadUnit = currentUnit,
                        rawLoadValue = currentRawLoad,
                        note = baseNote(currentNote),
                        repNotations = currentRepNotations.toList()
                    )
                )
                currentWeight = set.weightGrams
                currentUnit = set.loadUnit
                currentRawLoad = set.rawLoadValue
                currentAssisted = set.isAssisted
                currentDumbbell = set.isDumbbell
                currentNote = set.note
                currentReps = mutableListOf(set.repetitions)
                currentRepNotations = mutableListOf(notation)
            }
        }
        groups.add(
            CompactSetGroup(
                weightGrams = currentWeight,
                repetitions = currentReps.toList(),
                isAssisted = currentAssisted,
                isDumbbell = currentDumbbell,
                isToFailure = currentRepNotations.any { it.endsWith("f") },
                loadUnit = currentUnit,
                rawLoadValue = currentRawLoad,
                note = baseNote(currentNote),
                repNotations = currentRepNotations.toList()
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
