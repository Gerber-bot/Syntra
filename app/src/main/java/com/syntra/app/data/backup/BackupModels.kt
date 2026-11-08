package com.syntra.app.data.backup

data class ProfileBackupDto(
    val name: String,
    val preferredName: String,
    val heightCm: Int,
    val initialWeightGrams: Int,
    val activityLevel: String,
    val goal: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class WeighInBackupDto(
    val id: Long = 0,
    val measuredAtMillis: Long,
    val weightGrams: Int,
    val note: String = ""
)

data class ExerciseBackupDto(
    val id: Long = 0,
    val displayName: String,
    val normalizedName: String,
    val muscleGroup: String,
    val secondaryMuscleGroups: String = "",
    val equipment: String? = null,
    val exerciseType: String = "strength",
    val status: String = "CONFIRMED",
    val createdAtMillis: Long
)

data class ExerciseAliasBackupDto(
    val id: Long = 0,
    val exerciseId: Long,
    val normalizedAlias: String,
    val createdAtMillis: Long
)

data class WorkoutSessionBackupDto(
    val id: Long = 0,
    val title: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val notes: String = ""
)

data class WorkoutSetBackupDto(
    val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long,
    val orderInExercise: Int,
    val executionOrder: Int = 0,
    val loadGrams: Int,
    val repetitions: Int,
    val rir: Int? = null,
    val isAssisted: Boolean = false,
    val isDumbbell: Boolean = false,
    val note: String = "",
    val plannedTarget: String? = null
)

data class IrisxMemoryBackupDto(
    val id: Long = 0,
    val memoryType: String,
    val sourceKey: String,
    val targetValue: String,
    val confidence: Double = 1.0,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class IrisxConversationBackupDto(
    val id: Long = 0,
    val title: String,
    val activeMuscleGroup: String? = null,
    val activeExerciseName: String? = null,
    val activeSessionId: Long? = null,
    val activePeriodScope: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class IrisxMessageBackupDto(
    val id: Long = 0,
    val conversationId: Long,
    val senderRole: String,
    val content: String,
    val intentType: String? = null,
    val referencedExercise: String? = null,
    val referencedMuscleGroup: String? = null,
    val referencedSessionId: Long? = null,
    val createdAtMillis: Long
)

data class RoutineBackupDto(
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val targetCategory: String = "General",
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class RoutineExerciseBackupDto(
    val id: Long = 0,
    val routineId: Long,
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
)

data class SyntraBackupPayload(
    val formatVersion: Int = 2,
    val appVersion: String = "0.4.0",
    val exportedAtMillis: Long,
    val profile: ProfileBackupDto?,
    val weighIns: List<WeighInBackupDto> = emptyList(),
    val exercises: List<ExerciseBackupDto> = emptyList(),
    val exerciseAliases: List<ExerciseAliasBackupDto> = emptyList(),
    val workoutSessions: List<WorkoutSessionBackupDto> = emptyList(),
    val workoutSets: List<WorkoutSetBackupDto> = emptyList(),
    val irisxMemories: List<IrisxMemoryBackupDto> = emptyList(),
    val irisxConversations: List<IrisxConversationBackupDto> = emptyList(),
    val irisxMessages: List<IrisxMessageBackupDto> = emptyList(),
    val routines: List<RoutineBackupDto> = emptyList(),
    val routineExercises: List<RoutineExerciseBackupDto> = emptyList()
)

sealed class BackupValidationResult {
    data class Success(val payload: SyntraBackupPayload) : BackupValidationResult()
    data class Error(val message: String) : BackupValidationResult()
}
