package com.syntra.app.domain

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.RoutineEntity
import com.syntra.app.data.RoutineExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.irisx.IrisXAnalytics

data class RoutineExerciseSpec(
    val id: Long = 0,
    val exerciseId: Long? = null,
    val exerciseName: String,
    val orderIndex: Int,
    val targetSets: Int,
    val minReps: Int,
    val maxReps: Int,
    val suggestedLoadGrams: Int? = null,
    val isDumbbell: Boolean = false,
    val isAssisted: Boolean = false,
    val notes: String = ""
) {
    fun formattedRepRange(): String =
        if (minReps == maxReps) "$minReps reps" else "$minReps–$maxReps reps"

    fun formattedPlanTarget(): String =
        if (minReps == maxReps) {
            "$targetSets series de $minReps repeticiones"
        } else {
            "$targetSets series de $minReps–$maxReps repeticiones"
        }

    fun formattedSuggestedLoad(): String? =
        suggestedLoadGrams?.takeIf { it > 0 }?.let { WorkoutPresentation.formatWeightKg(it) }
}

data class RoutineWithExercises(
    val routine: RoutineEntity,
    val exercises: List<RoutineExerciseSpec>
)

data class ExecutedRoutineSetInput(
    val loadGrams: Int,
    val repetitions: Int,
    val rir: Int? = null,
    val isDumbbell: Boolean = false,
    val isAssisted: Boolean = false,
    val note: String = ""
)

data class AppliedRoutineExerciseDraft(
    val exerciseId: Long?,
    val exerciseName: String,
    val orderIndex: Int,
    val plannedTarget: String,
    val plannedSuggestedLoadGrams: Int?,
    val executedSets: List<ExecutedRoutineSetInput>
)

data class AppliedRoutineSessionDraft(
    val sourceRoutineId: Long,
    val sessionTitle: String,
    val startedAtMillis: Long,
    val notes: String,
    val exercises: List<AppliedRoutineExerciseDraft>
)

data class RoutineCoachSuggestion(
    val exerciseName: String,
    val currentSuggestedLoadGrams: Int?,
    val recommendedLoadGrams: Int,
    val reason: String
)

object RoutineManager {

    /**
     * Validates a routine and its exercise items before saving.
     */
    fun validateRoutine(name: String, items: List<RoutineExerciseSpec>): String? {
        if (name.trim().isEmpty()) {
            return "El nombre de la rutina no puede estar vacío."
        }
        if (items.isEmpty()) {
            return "La rutina debe incluir al menos un ejercicio."
        }
        for ((idx, item) in items.withIndex()) {
            if (item.exerciseName.trim().isEmpty()) {
                return "El ejercicio #${idx + 1} no tiene nombre."
            }
            if (item.targetSets !in 1..20) {
                return "Las series objetivo de '${item.exerciseName}' deben estar entre 1 y 20."
            }
            if (item.minReps !in 1..100 || item.maxReps !in 1..100 || item.minReps > item.maxReps) {
                return "El rango de repeticiones de '${item.exerciseName}' (${item.minReps}–${item.maxReps}) no es válido."
            }
            if (item.suggestedLoadGrams != null && (item.suggestedLoadGrams < 0 || item.suggestedLoadGrams > 1_000_000)) {
                return "La carga sugerida de '${item.exerciseName}' está fuera de rango."
            }
        }
        return null
    }

    /**
     * Converts a RoutineEntity + RoutineExerciseEntity list into a domain RoutineWithExercises.
     */
    fun fromEntities(
        routine: RoutineEntity,
        items: List<RoutineExerciseEntity>
    ): RoutineWithExercises {
        val sorted = items.sortedWith(compareBy<RoutineExerciseEntity> { it.orderIndex }.thenBy { it.id })
        return RoutineWithExercises(
            routine = routine,
            exercises = sorted.map { e ->
                RoutineExerciseSpec(
                    id = e.id,
                    exerciseId = e.exerciseId,
                    exerciseName = e.exerciseName,
                    orderIndex = e.orderIndex,
                    targetSets = e.targetSets,
                    minReps = e.minReps,
                    maxReps = e.maxReps,
                    suggestedLoadGrams = e.suggestedLoadGrams,
                    isDumbbell = e.isDumbbell,
                    isAssisted = e.isAssisted,
                    notes = e.notes
                )
            }
        )
    }

    /**
     * Builds a new session draft from a routine, strictly preserving the distinction
     * between planning (plannedTarget, plannedSuggestedLoadGrams) and execution (executedSets).
     * Never mutates or overwrites historical sessions.
     */
    fun createSessionDraftFromRoutine(
        routineWithExercises: RoutineWithExercises,
        startedAtMillis: Long = System.currentTimeMillis(),
        customExecutionByOrder: Map<Int, List<ExecutedRoutineSetInput>> = emptyMap()
    ): AppliedRoutineSessionDraft {
        val drafts = routineWithExercises.exercises.map { spec ->
            val defaultLoad = spec.suggestedLoadGrams ?: 20_000
            val defaultSets = (1..spec.targetSets).map {
                ExecutedRoutineSetInput(
                    loadGrams = defaultLoad,
                    repetitions = spec.minReps,
                    isDumbbell = spec.isDumbbell,
                    isAssisted = spec.isAssisted,
                    note = spec.notes
                )
            }
            val executed = customExecutionByOrder[spec.orderIndex] ?: defaultSets
            AppliedRoutineExerciseDraft(
                exerciseId = spec.exerciseId,
                exerciseName = spec.exerciseName,
                orderIndex = spec.orderIndex,
                plannedTarget = spec.formattedPlanTarget(),
                plannedSuggestedLoadGrams = spec.suggestedLoadGrams,
                executedSets = executed
            )
        }

        return AppliedRoutineSessionDraft(
            sourceRoutineId = routineWithExercises.routine.id,
            sessionTitle = routineWithExercises.routine.name,
            startedAtMillis = startedAtMillis,
            notes = "Basado en rutina: ${routineWithExercises.routine.name}",
            exercises = drafts
        )
    }

    /**
     * Formats a routine into WorkoutTextParser text so the user can load a routine into the
     * Analizador screen, adjust real weights/reps executed today, and confirm saving.
     */
    fun toParserTextTemplate(routineWithExercises: RoutineWithExercises): String {
        return buildString {
            append(routineWithExercises.routine.name)
            append("\n")
            routineWithExercises.exercises.forEach { ex ->
                val rangeStr = if (ex.minReps == ex.maxReps) "${ex.targetSets}x${ex.minReps}" else "${ex.targetSets}x${ex.minReps}-${ex.maxReps}"
                val modStr = when {
                    ex.isAssisted -> " asistidas"
                    ex.isDumbbell -> " mancuernas"
                    else -> ""
                }
                val kgVal = (ex.suggestedLoadGrams?.takeIf { it > 0 } ?: 20_000) / 1000.0
                val kgFormatted = if (kgVal % 1.0 == 0.0) "${kgVal.toInt()}kg" else "${kgVal.toString().replace('.', ',')}kg"
                val repsJoined = (1..ex.targetSets).joinToString(" ") { ex.minReps.toString() }
                append("${ex.exerciseName}$modStr $rangeStr: $kgFormatted $repsJoined\n")
            }
        }.trim()
    }

    /**
     * Integrates with IrisX Analytics/Coach to suggest load adjustments for a routine
     * based on the user's real historical performance, without modifying the routine automatically.
     */
    fun suggestCoachAdjustments(
        routineWithExercises: RoutineWithExercises,
        sessions: List<WorkoutSessionEntity>,
        allSets: List<WorkoutSetEntity>,
        exercises: List<ExerciseEntity>,
        learnedAliases: Map<String, String> = emptyMap()
    ): List<RoutineCoachSuggestion> {
        val suggestions = mutableListOf<RoutineCoachSuggestion>()
        for (spec in routineWithExercises.exercises) {
            val prog = IrisXAnalytics.analyzeExerciseProgression(
                exerciseName = spec.exerciseName,
                sessions = sessions,
                allSets = allSets,
                exercises = exercises,
                learnedAliases = learnedAliases
            )
            val modality = prog.dominantModality ?: continue
            val modHistory = prog.history.filter { it.modality == modality }
            val last = modHistory.lastOrNull() ?: continue

            // If user exceeded maxReps at or above suggested load, propose small increment
            val currentSuggested = spec.suggestedLoadGrams ?: last.topLoadGrams
            if (last.topLoadGrams >= currentSuggested && last.topLoadReps >= spec.maxReps && modality != LoadModality.BODYWEIGHT) {
                val incrementGrams = if (modality == LoadModality.DUMBBELL_PER_ARM || modality == LoadModality.UNILATERAL) 1000 else 2500
                val recommended = last.topLoadGrams + incrementGrams
                val lastLoad = UnitConverter.formatDisplay(last.topLoadGrams, last.unit)
                val recLoad = UnitConverter.formatDisplay(recommended, last.unit)
                suggestions.add(
                    RoutineCoachSuggestion(
                        exerciseName = spec.exerciseName,
                        currentSuggestedLoadGrams = spec.suggestedLoadGrams,
                        recommendedLoadGrams = recommended,
                        reason = "Alcanzaste el tope del rango (${last.topLoadReps} reps con $lastLoad). IrisX Coach sugiere $recLoad para la próxima sesión."
                    )
                )
            } else if (spec.suggestedLoadGrams == null && last.topLoadGrams > 0) {
                val lastLoad = UnitConverter.formatDisplay(last.topLoadGrams, last.unit)
                suggestions.add(
                    RoutineCoachSuggestion(
                        exerciseName = spec.exerciseName,
                        currentSuggestedLoadGrams = null,
                        recommendedLoadGrams = last.topLoadGrams,
                        reason = "Sin carga base definida en la rutina; tu última carga registrada fue $lastLoad × ${last.topLoadReps} reps."
                    )
                )
            }
        }
        return suggestions
    }
}
