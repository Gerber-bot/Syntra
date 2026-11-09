package com.syntra.app.domain

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.IrisxMemoryEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.irisx.IrisXClassifier
import java.text.Normalizer
import java.util.Locale

data class TrainingSequenceConfig(
    val steps: List<String>,
    val manualOverrideCategory: String? = null
) {
    val isConfigured: Boolean get() = steps.isNotEmpty()
    fun formattedSequence(): String = steps.joinToString(" → ")
}

data class NextSessionPlan(
    val isConfigured: Boolean,
    val sequenceSteps: List<String>,
    val nextCategoryName: String?,
    val manualOverrideActive: Boolean,
    val lastCompletedSessionId: Long?,
    val lastCompletedCategoryName: String?,
    val suggestedRoutine: RoutineWithExercises?,
    val reason: String
)

/**
 * Configurable next-session planner (Sprint 05 — Fase 4 & 5).
 *
 * Rules:
 * - Sequence is configurable by the user and persisted in `irisx_memories` (`memoryType = "TRAINING_SEQUENCE"`).
 * - Never imposes a hardcoded sequence if the user hasn't configured one.
 * - Advances the sequence ONLY based on completed and committed workout sessions in SQLite
 *   (never on drafts, `REVIEW_PENDING`, or cancelled sessions).
 * - Supports manual override for the next session (`manual_override_step`), which clears automatically
 *   once a new completed session is recorded after the override timestamp or when cleared by the user.
 */
object NextSessionPlanner {

    const val MEMORY_TYPE_SEQUENCE = "TRAINING_SEQUENCE"
    const val KEY_STEPS = "sequence_steps"
    const val KEY_MANUAL_NEXT = "manual_next_step"

    private fun normalize(text: String): String =
        Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun parseSequenceSteps(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(Regex("[,;→>-]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun serializeSequenceSteps(steps: List<String>): String =
        steps.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

    fun extractConfigFromMemories(memories: List<IrisxMemoryEntity>): TrainingSequenceConfig {
        val seqMemories = memories.filter { it.memoryType == MEMORY_TYPE_SEQUENCE }
        val stepsRaw = seqMemories.firstOrNull { it.sourceKey == KEY_STEPS }?.targetValue
        val manualRaw = seqMemories.firstOrNull { it.sourceKey == KEY_MANUAL_NEXT }?.targetValue
            ?.takeIf { it.isNotBlank() }
        return TrainingSequenceConfig(
            steps = parseSequenceSteps(stepsRaw),
            manualOverrideCategory = manualRaw
        )
    }

    /**
     * Classifies a completed session into its primary category name using `IrisXClassifier` and `WorkoutCategoryClassifier`.
     */
    fun classifyCompletedSession(
        session: WorkoutSessionEntity,
        sessionSets: List<WorkoutSetEntity>,
        exercisesById: Map<Long, ExerciseEntity>,
        learnedAliases: Map<String, String> = emptyMap(),
        muscleOverrides: Map<String, String> = emptyMap()
    ): WorkoutCategoryKey {
        val orderedSets = sessionSets.sortedWith(compareBy<WorkoutSetEntity> { it.executionOrder }.thenBy { it.id })
        val exerciseNames = orderedSets.mapNotNull { exercisesById[it.exerciseId]?.displayName }.distinct()
        val exerciseMuscles = orderedSets.mapNotNull { exercisesById[it.exerciseId]?.muscleGroup }
        val classification = IrisXClassifier.classifyWorkout(
            sessionTitle = session.title,
            exerciseNames = exerciseNames,
            learnedAliases = learnedAliases,
            muscleOverrides = muscleOverrides
        )
        if (classification.primaryCategory != WorkoutCategoryKey.SIN_CLASIFICAR) {
            return classification.primaryCategory
        }
        return WorkoutCategoryClassifier.classify(
            title = session.title,
            exerciseMuscleGroups = exerciseMuscles,
            exerciseNames = exerciseNames,
            learnedAliases = learnedAliases
        )
    }

    /**
     * Checks whether a sequence step matches a completed session category or session title.
     */
    fun stepMatchesSession(
        stepName: String,
        sessionTitle: String,
        classifiedCategory: WorkoutCategoryKey
    ): Boolean {
        val normStep = normalize(stepName)
        val normTitle = normalize(sessionTitle)
        val normCat = normalize(classifiedCategory.displayName)
        if (normStep.isEmpty()) return false
        if (normStep == normCat || normCat.contains(normStep) || normStep.contains(normCat)) return true
        if (normTitle == normStep || normTitle.contains(normStep)) return true
        val stepCategory = WorkoutCategoryClassifier.classify(title = stepName)
        return stepCategory != WorkoutCategoryKey.SIN_CLASIFICAR && stepCategory == classifiedCategory
    }

    /**
     * Computes the next planned session from the user's configured sequence, completed sessions in SQLite,
     * and available routines.
     * Note: Uncommitted drafts or cancelled workouts are never passed in `completedSessions` and never advance the sequence.
     */
    fun computeNextSession(
        config: TrainingSequenceConfig,
        completedSessions: List<WorkoutSessionEntity>,
        allSets: List<WorkoutSetEntity>,
        exercises: List<ExerciseEntity>,
        routines: List<RoutineWithExercises> = emptyList(),
        learnedAliases: Map<String, String> = emptyMap(),
        muscleOverrides: Map<String, String> = emptyMap()
    ): NextSessionPlan {
        val exercisesById = exercises.associateBy { it.id }
        val setsBySession = allSets.groupBy { it.sessionId }
        val sortedCompleted = completedSessions.sortedWith(
            compareByDescending<WorkoutSessionEntity> { it.startedAtMillis }.thenByDescending { it.id }
        )

        val latestSession = sortedCompleted.firstOrNull()
        val latestCategory = latestSession?.let {
            classifyCompletedSession(
                session = it,
                sessionSets = setsBySession[it.id].orEmpty(),
                exercisesById = exercisesById,
                learnedAliases = learnedAliases,
                muscleOverrides = muscleOverrides
            )
        }

        // 1. Manual override takes explicit priority if set by the user
        val manualCategory = config.manualOverrideCategory?.trim()?.takeIf { it.isNotEmpty() }
        if (manualCategory != null) {
            val matchedRoutine = findMatchingRoutine(manualCategory, routines)
            return NextSessionPlan(
                isConfigured = config.isConfigured,
                sequenceSteps = config.steps,
                nextCategoryName = manualCategory,
                manualOverrideActive = true,
                lastCompletedSessionId = latestSession?.id,
                lastCompletedCategoryName = latestCategory?.displayName,
                suggestedRoutine = matchedRoutine,
                reason = "Seleccionada manualmente por vos para hoy."
            )
        }

        // 2. If user hasn't configured a sequence yet, do not impose a fixed sequence
        if (!config.isConfigured) {
            return NextSessionPlan(
                isConfigured = false,
                sequenceSteps = emptyList(),
                nextCategoryName = null,
                manualOverrideActive = false,
                lastCompletedSessionId = latestSession?.id,
                lastCompletedCategoryName = latestCategory?.displayName,
                suggestedRoutine = null,
                reason = "Sin secuencia definida. Podés configurar tu orden de entrenamiento o elegir manualmente."
            )
        }

        val steps = config.steps
        // 3. Find the most recent completed session that matches one of the sequence steps
        var matchedStepIndex: Int? = null
        var matchedSession: WorkoutSessionEntity? = null
        var matchedCategoryName: String? = null

        for (session in sortedCompleted) {
            val cat = classifyCompletedSession(
                session = session,
                sessionSets = setsBySession[session.id].orEmpty(),
                exercisesById = exercisesById,
                learnedAliases = learnedAliases,
                muscleOverrides = muscleOverrides
            )
            val idx = steps.indexOfFirst { step -> stepMatchesSession(step, session.title, cat) }
            if (idx >= 0) {
                matchedStepIndex = idx
                matchedSession = session
                matchedCategoryName = steps[idx]
                break
            }
        }

        val nextStepIndex = if (matchedStepIndex != null) {
            (matchedStepIndex + 1) % steps.size
        } else {
            0
        }
        val nextCategory = steps[nextStepIndex]
        val matchedRoutine = findMatchingRoutine(nextCategory, routines)

        val reason = if (matchedSession != null && matchedCategoryName != null) {
            "Tu última sesión relevante completada fue $matchedCategoryName (${matchedSession.title}); según tu secuencia (${config.formattedSequence()}), sigue $nextCategory."
        } else {
            "Primer paso de tu secuencia configurada (${config.formattedSequence()})."
        }

        return NextSessionPlan(
            isConfigured = true,
            sequenceSteps = steps,
            nextCategoryName = nextCategory,
            manualOverrideActive = false,
            lastCompletedSessionId = latestSession?.id,
            lastCompletedCategoryName = latestCategory?.displayName,
            suggestedRoutine = matchedRoutine,
            reason = reason
        )
    }

    fun findMatchingRoutine(
        categoryOrStepName: String,
        routines: List<RoutineWithExercises>
    ): RoutineWithExercises? {
        if (routines.isEmpty()) return null
        val normTarget = normalize(categoryOrStepName)
        // Exact or substring match on targetCategory first, then routine name
        return routines.firstOrNull { normalize(it.routine.targetCategory) == normTarget }
            ?: routines.firstOrNull { normalize(it.routine.name) == normTarget }
            ?: routines.firstOrNull {
                val catNorm = normalize(it.routine.targetCategory)
                val nameNorm = normalize(it.routine.name)
                (catNorm.isNotEmpty() && (catNorm.contains(normTarget) || normTarget.contains(catNorm))) ||
                    (nameNorm.isNotEmpty() && (nameNorm.contains(normTarget) || normTarget.contains(nameNorm)))
            }
    }
}
