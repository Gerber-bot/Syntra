package com.syntra.app.domain.irisx

import com.syntra.app.domain.WorkoutCategoryKey

data class ClassificationResult(
    val primaryCategory: WorkoutCategoryKey,
    val secondaryCategories: List<WorkoutCategoryKey> = emptyList(),
    val sessionType: String = "Mixto", // "Empuje (Push)", "Tirón (Pull)", "Piernas (Legs)", "Torso", "Full Body", "Mixto"
    val confidence: Double = 1.0,
    val muscleBreakdown: Map<String, Int> = emptyMap(),
    val isAmbiguous: Boolean = false,
    val reasoning: String = ""
)

data class IrisXMemoryItem(
    val id: Long = 0,
    val type: String, // "ALIAS", "CORRECTION", "PREFERENCE", "CLASSIFICATION_OVERRIDE", "EXPERIENCE"
    val sourceKey: String,
    val targetValue: String,
    val confidence: Double = 1.0,
    val origin: String = "USER_CORRECTION", // "USER_CORRECTION", "SYSTEM_DEFAULT", "EXPLICIT_CONFIRMATION"
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

sealed class IrisXIntent {
    data class ClassifySession(val sessionTitle: String, val exerciseNames: List<String>) : IrisXIntent()
    object QueryLastWorkout : IrisXIntent()
    data class QueryExercise(val exerciseName: String) : IrisXIntent()
    data class LearnAlias(val alias: String, val canonical: String, val force: Boolean = false) : IrisXIntent()
    data class DeleteAlias(val alias: String) : IrisXIntent()
    data class UpdateExerciseMuscle(val exerciseName: String, val muscleGroup: String) : IrisXIntent()
    data class QueryProgression(
        val targetSubject: String? = null,
        val periodScope: PeriodScope? = null
    ) : IrisXIntent()
    data class FollowUpQuery(
        val periodScope: PeriodScope? = null,
        val targetSubject: String? = null,
        val rawText: String
    ) : IrisXIntent()
    data class QueryRecommendations(val targetSubject: String? = null) : IrisXIntent()
    data class SavePreference(val key: String, val value: String, val confirmed: Boolean = false) : IrisXIntent()
    object QueryPreferences : IrisXIntent()
    object QueryRoutines : IrisXIntent()
    object ClearConversationHistory : IrisXIntent()
    // Sprint 05: Active workout & rapid contextual queries
    data class QueryLastExerciseLoad(val exerciseName: String? = null) : IrisXIntent()
    data class QueryExerciseAlternatives(val exerciseName: String? = null) : IrisXIntent()
    data class QueryLastSessionByCategory(val categoryOrMuscle: String) : IrisXIntent()
    data class GeneralQuery(val text: String) : IrisXIntent()
}

sealed class IrisXResponse {
    data class Success(
        val message: String,
        val actionTaken: String? = null,
        val payload: Any? = null
    ) : IrisXResponse()

    data class RequiresConfirmation(
        val question: String,
        val confirmCommand: String,
        val payload: Any? = null
    ) : IrisXResponse()

    data class Unrecognized(
        val message: String,
        val suggestions: List<String> = emptyList()
    ) : IrisXResponse()
}
