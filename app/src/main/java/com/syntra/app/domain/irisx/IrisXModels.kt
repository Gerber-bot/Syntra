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
    data class LearnAlias(val alias: String, val canonical: String) : IrisXIntent()
    data class UpdateExerciseMuscle(val exerciseName: String, val muscleGroup: String) : IrisXIntent()
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
