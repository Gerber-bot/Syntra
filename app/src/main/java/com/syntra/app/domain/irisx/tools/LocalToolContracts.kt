package com.syntra.app.domain.irisx.tools

/**
 * Read-only local tools available to IrisX.
 *
 * Every tool takes a typed request and returns a typed outcome. No tool builds SQL, no tool
 * writes to the database and no tool invents sports data: numbers come from Room rows and
 * deterministic calculations only.
 */
enum class LocalToolId(val actionPrefix: String) {
    LAST_WORKOUT("QUERY_LAST_WORKOUT_TOOL"),
    RECENT_WORKOUTS("QUERY_RECENT_WORKOUTS"),
    LAST_EXERCISE_SESSION("QUERY_LAST_EXERCISE_SESSION"),
    EXERCISE_HISTORY("QUERY_EXERCISE_HISTORY")
}

sealed interface LocalToolRequest {
    /** Last recorded workout, newest session first. */
    data object LastWorkout : LocalToolRequest

    /** Recent recorded workouts, newest session first. */
    data class RecentWorkouts(val maxSessions: Int = 5) : LocalToolRequest

    /** Last session containing the requested exercise. */
    data class LastExerciseSession(val exerciseName: String?) : LocalToolRequest

    /** Previous sessions of one exercise, newest first. */
    data class ExerciseHistory(
        val exerciseName: String?,
        val maxSessions: Int = 5,
        /** True when the user explicitly asked to compare sessions. */
        val requireComparison: Boolean = false
    ) : LocalToolRequest
}

/** One stored set, copied from `WorkoutSetEntity`. */
data class LocalToolSet(
    val exerciseId: Long,
    val exerciseName: String,
    val loadGrams: Int,
    val loadUnit: String,
    val repetitions: Int,
    val rir: Int? = null,
    val isAssisted: Boolean = false,
    val isDumbbell: Boolean = false,
    val orderInExercise: Int,
    val executionOrder: Int,
    val displayLoad: String
)

/** Result of [LocalToolRequest.LastWorkout]. */
data class LocalToolWorkout(
    val sessionId: Long,
    val title: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val totalSets: Int,
    val sets: List<LocalToolSet>,
    val exerciseNames: List<String>
)

/** Verified summary of one stored workout session. */
data class LocalToolWorkoutSummary(
    val sessionId: Long,
    val title: String,
    val startedAtMillis: Long,
    val totalSets: Int,
    val exerciseNames: List<String>
)

/** Result of [LocalToolRequest.RecentWorkouts]. */
data class LocalToolRecentWorkouts(
    val workouts: List<LocalToolWorkoutSummary>,
    val totalRecordedSessions: Int
)

/** Result of [LocalToolRequest.LastExerciseSession]. */
data class LocalToolExerciseSnapshot(
    val exerciseId: Long,
    val exerciseName: String,
    val sessionId: Long,
    val sessionTitle: String,
    val startedAtMillis: Long,
    val modality: String,
    val setsCount: Int,
    val totalReps: Int,
    val topLoadGrams: Int,
    val topLoadReps: Int,
    val topLoadUnit: String,
    val topLoadDisplay: String,
    val sets: List<LocalToolSet>,
    val previousSession: LocalToolPreviousSession? = null
)

/** Verified summary of the session before the latest one. */
data class LocalToolPreviousSession(
    val sessionId: Long,
    val sessionTitle: String,
    val startedAtMillis: Long,
    val modality: String,
    val setsCount: Int,
    val topLoadGrams: Int,
    val topLoadReps: Int,
    val topLoadUnit: String,
    val topLoadDisplay: String
)

/** One verified session inside an exercise history. */
data class LocalToolHistoryEntry(
    val sessionId: Long,
    val sessionTitle: String,
    val startedAtMillis: Long,
    val modality: String,
    val setsCount: Int,
    val totalReps: Int,
    val topLoadGrams: Int,
    val topLoadReps: Int,
    val topLoadUnit: String,
    val topLoadDisplay: String,
    val sets: List<LocalToolSet>
)

/** Result of [LocalToolRequest.ExerciseHistory]. */
data class LocalToolExerciseHistory(
    val exerciseId: Long,
    val exerciseName: String,
    /** Newest session first. */
    val entries: List<LocalToolHistoryEntry>,
    val comparisonAvailable: Boolean,
    val incompatibleModalitiesDetected: Boolean
)

/**
 * Typed result of a tool call.
 *
 * The three failure kinds are deliberately distinct so the conversation can tell "no such
 * data" from "not enough data to answer" from "the query itself failed".
 */
sealed interface LocalToolOutcome<out T> {
    val toolId: LocalToolId

    data class Success<T>(
        override val toolId: LocalToolId,
        val data: T,
        /** Verified, human-readable claims derived only from [data]. */
        val facts: List<String>
    ) : LocalToolOutcome<T>

    /** The requested entity does not exist in the local database. */
    data class NoData(
        override val toolId: LocalToolId,
        val subject: String?,
        val reason: String
    ) : LocalToolOutcome<Nothing>

    /** The entity exists but the stored records cannot answer the question yet. */
    data class InsufficientData(
        override val toolId: LocalToolId,
        val subject: String?,
        val reason: String
    ) : LocalToolOutcome<Nothing>

    /** Invalid or unresolvable request, or a recoverable read failure. */
    data class QueryError(
        override val toolId: LocalToolId,
        val reason: String
    ) : LocalToolOutcome<Nothing>
}

/** Deterministic rendering of a tool outcome; never feeds on model output. */
data class LocalToolPresentation(
    val text: String,
    val facts: List<String>,
    val toolId: LocalToolId
)
