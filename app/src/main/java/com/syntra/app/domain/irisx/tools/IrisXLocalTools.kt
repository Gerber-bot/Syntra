package com.syntra.app.domain.irisx.tools

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.SportsMetricsCalculator
import com.syntra.app.domain.UnitConverter
import com.syntra.app.domain.irisx.IrisXAnalytics
import com.syntra.app.domain.irisx.IrisXRepository
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

/**
 * Read-only query tools over the local sports database.
 *
 * Responsibilities stay separate from interpretation and presentation:
 * - [IrisXRepository] is the only data source (Room or a test fake).
 * - [IrisXAnalytics] performs the deterministic modality-aware calculations.
 * - This class turns raw rows into typed snapshots and classifies the answer as success,
 *   missing data, insufficient data or a recoverable query error.
 */
class IrisXLocalTools(private val repository: IrisXRepository) {

    suspend fun execute(request: LocalToolRequest): LocalToolOutcome<*> {
        return try {
            when (request) {
                LocalToolRequest.LastWorkout -> lastWorkout()
                is LocalToolRequest.LastExerciseSession -> lastExerciseSession(request.exerciseName)
                is LocalToolRequest.ExerciseHistory -> exerciseHistory(request)
            }
        } catch (cancelled: CancellationException) {
            // Cancellation is life-cycle, not a query error: the chat must be able to stop it.
            throw cancelled
        } catch (e: Throwable) {
            LocalToolOutcome.QueryError(
                toolId = request.toolId(),
                reason = "No pude leer los datos locales: ${e.message ?: e::class.simpleName}"
            )
        }
    }

    private suspend fun lastWorkout(): LocalToolOutcome<LocalToolWorkout> {
        val sessions = repository.getAllSessions()
        if (sessions.isEmpty()) {
            return LocalToolOutcome.NoData(
                toolId = LocalToolId.LAST_WORKOUT,
                subject = null,
                reason = "Todavía no hay entrenamientos registrados."
            )
        }

        val latest = sessions.maxWithOrNull(
            compareBy<WorkoutSessionEntity> { it.startedAtMillis }.thenBy { it.id }
        )!!
        val rawSets = repository.getSetsForSession(latest.id).sortedWith(setOrder())
        if (rawSets.isEmpty()) {
            return LocalToolOutcome.InsufficientData(
                toolId = LocalToolId.LAST_WORKOUT,
                subject = latest.title,
                reason = "La sesión más reciente ('${latest.title}', ${formatDate(latest.startedAtMillis)}) " +
                    "existe pero no tiene series registradas."
            )
        }

        val exercisesById = repository.getAllExercises().associateBy { it.id }
        val sets = rawSets.map { it.toToolSet(exercisesById) }
        val exerciseNames = sets.map { it.exerciseName }.distinct()
        val workout = LocalToolWorkout(
            sessionId = latest.id,
            title = latest.title,
            startedAtMillis = latest.startedAtMillis,
            finishedAtMillis = latest.finishedAtMillis,
            totalSets = sets.size,
            sets = sets,
            exerciseNames = exerciseNames
        )

        return LocalToolOutcome.Success(
            toolId = LocalToolId.LAST_WORKOUT,
            data = workout,
            facts = listOf(
                "Sesión: ${latest.title}",
                "Fecha: ${formatDate(latest.startedAtMillis)}",
                "Series registradas: ${sets.size}",
                "Ejercicios: ${exerciseNames.joinToString(", ")}"
            )
        )
    }

    private suspend fun lastExerciseSession(rawName: String?): LocalToolOutcome<LocalToolExerciseSnapshot> {
        val requested = rawName?.trim()
        if (requested.isNullOrBlank()) {
            return LocalToolOutcome.QueryError(
                toolId = LocalToolId.LAST_EXERCISE_SESSION,
                reason = "Indicá qué ejercicio querés consultar."
            )
        }

        val sessions = repository.getAllSessions()
        val allSets = repository.getAllSets()
        val exercises = repository.getAllExercises()
        val aliases = repository.getLearnedAliases()

        val resolved = when (val resolution = ExerciseNameResolver.resolve(requested, exercises, aliases)) {
            is ExerciseResolution.Resolved -> resolution
            ExerciseResolution.NotFound -> return LocalToolOutcome.NoData(
                toolId = LocalToolId.LAST_EXERCISE_SESSION,
                subject = requested,
                reason = "No existe un ejercicio registrado con el nombre '$requested'."
            )
            is ExerciseResolution.Ambiguous -> return LocalToolOutcome.QueryError(
                toolId = LocalToolId.LAST_EXERCISE_SESSION,
                reason = "El nombre '$requested' coincide con varios ejercicios: " +
                    "${resolution.candidates.joinToString(", ")}. Indicá cuál querés consultar."
            )
        }

        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = resolved.displayName,
            sessions = sessions,
            allSets = allSets,
            exercises = exercises,
            learnedAliases = aliases,
            targetExerciseId = resolved.id
        )
        val latest = report.history.lastOrNull()
            ?: return LocalToolOutcome.NoData(
                toolId = LocalToolId.LAST_EXERCISE_SESSION,
                subject = resolved.displayName,
                reason = "Todavía no hay series registradas de '${resolved.displayName}'."
            )

        val exercisesById = exercises.associateBy { it.id }
        val exercise = exercisesById[resolved.id]
        val sets = setsForPoint(allSets, resolved.id, exercise, latest.sessionId, latest.modality.name)
            .map { it.toToolSet(exercisesById) }
        val previous = report.history.dropLast(1).lastOrNull { it.modality == latest.modality }

        val snapshot = LocalToolExerciseSnapshot(
            exerciseId = resolved.id,
            exerciseName = resolved.displayName,
            sessionId = latest.sessionId,
            sessionTitle = latest.sessionTitle,
            startedAtMillis = latest.timestampMillis,
            modality = latest.modality.name,
            setsCount = latest.setsCount,
            totalReps = latest.totalReps,
            topLoadGrams = latest.topLoadGrams,
            topLoadReps = latest.topLoadReps,
            topLoadUnit = latest.unit,
            topLoadDisplay = UnitConverter.formatDisplay(latest.topLoadGrams, latest.unit),
            sets = sets,
            previousSession = previous?.let {
                LocalToolPreviousSession(
                    sessionId = it.sessionId,
                    sessionTitle = it.sessionTitle,
                    startedAtMillis = it.timestampMillis,
                    modality = it.modality.name,
                    setsCount = it.setsCount,
                    topLoadGrams = it.topLoadGrams,
                    topLoadReps = it.topLoadReps,
                    topLoadUnit = it.unit,
                    topLoadDisplay = UnitConverter.formatDisplay(it.topLoadGrams, it.unit)
                )
            }
        )

        return LocalToolOutcome.Success(
            toolId = LocalToolId.LAST_EXERCISE_SESSION,
            data = snapshot,
            facts = buildList {
                add("Ejercicio: ${snapshot.exerciseName}")
                add("Sesión: ${snapshot.sessionTitle}")
                add("Fecha: ${formatDate(snapshot.startedAtMillis)}")
                add("Series: ${snapshot.setsCount}")
                add("Mejor serie: ${snapshot.topLoadDisplay} × ${snapshot.topLoadReps} reps")
                add("Repeticiones totales: ${snapshot.totalReps}")
                snapshot.previousSession?.let {
                    add(
                        "Sesión anterior: ${formatDate(it.startedAtMillis)} — ${it.sessionTitle}: " +
                            "${it.topLoadDisplay} × ${it.topLoadReps} reps (${it.setsCount} series)"
                    )
                }
            }
        )
    }

    private suspend fun exerciseHistory(
        request: LocalToolRequest.ExerciseHistory
    ): LocalToolOutcome<LocalToolExerciseHistory> {
        val requested = request.exerciseName?.trim()
        if (requested.isNullOrBlank()) {
            return LocalToolOutcome.QueryError(
                toolId = LocalToolId.EXERCISE_HISTORY,
                reason = "Indicá de qué ejercicio querés ver el historial."
            )
        }

        val sessions = repository.getAllSessions()
        val allSets = repository.getAllSets()
        val exercises = repository.getAllExercises()
        val aliases = repository.getLearnedAliases()

        val resolved = when (val resolution = ExerciseNameResolver.resolve(requested, exercises, aliases)) {
            is ExerciseResolution.Resolved -> resolution
            ExerciseResolution.NotFound -> return LocalToolOutcome.NoData(
                toolId = LocalToolId.EXERCISE_HISTORY,
                subject = requested,
                reason = "No existe un ejercicio registrado con el nombre '$requested'."
            )
            is ExerciseResolution.Ambiguous -> return LocalToolOutcome.QueryError(
                toolId = LocalToolId.EXERCISE_HISTORY,
                reason = "El nombre '$requested' coincide con varios ejercicios: " +
                    "${resolution.candidates.joinToString(", ")}. Indicá cuál querés consultar."
            )
        }

        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = resolved.displayName,
            sessions = sessions,
            allSets = allSets,
            exercises = exercises,
            learnedAliases = aliases,
            targetExerciseId = resolved.id
        )
        if (report.history.isEmpty()) {
            return LocalToolOutcome.NoData(
                toolId = LocalToolId.EXERCISE_HISTORY,
                subject = resolved.displayName,
                reason = "Todavía no hay sesiones registradas de '${resolved.displayName}'."
            )
        }

        val chronological = report.history.sortedBy { it.timestampMillis }
        val selected = chronological
            .takeLast(request.maxSessions.coerceAtLeast(1))
            .reversed()
        val comparisonAvailable = chronological.size >= 2

        if (request.requireComparison && !comparisonAvailable) {
            val only = selected.first()
            return LocalToolOutcome.InsufficientData(
                toolId = LocalToolId.EXERCISE_HISTORY,
                subject = resolved.displayName,
                reason = "Solo hay una sesión registrada de '${resolved.displayName}' " +
                    "(${only.sessionTitle}, ${formatDate(only.timestampMillis)}: " +
                    "${UnitConverter.formatDisplay(only.topLoadGrams, only.unit)} × ${only.topLoadReps} reps, " +
                    "${only.setsCount} series). Se necesitan al menos dos para comparar."
            )
        }
        if (request.requireComparison && report.incompatibleModalitiesDetected) {
            return LocalToolOutcome.InsufficientData(
                toolId = LocalToolId.EXERCISE_HISTORY,
                subject = resolved.displayName,
                reason = "Las sesiones registradas de '${resolved.displayName}' usan modalidades de carga " +
                    "distintas; no se pueden comparar directamente."
            )
        }

        val exercisesById = exercises.associateBy { it.id }
        val exercise = exercisesById[resolved.id]
        val entries = selected.map { point ->
            LocalToolHistoryEntry(
                sessionId = point.sessionId,
                sessionTitle = point.sessionTitle,
                startedAtMillis = point.timestampMillis,
                modality = point.modality.name,
                setsCount = point.setsCount,
                totalReps = point.totalReps,
                topLoadGrams = point.topLoadGrams,
                topLoadReps = point.topLoadReps,
                topLoadUnit = point.unit,
                topLoadDisplay = UnitConverter.formatDisplay(point.topLoadGrams, point.unit),
                sets = setsForPoint(allSets, resolved.id, exercise, point.sessionId, point.modality.name)
                    .map { it.toToolSet(exercisesById) }
            )
        }

        val history = LocalToolExerciseHistory(
            exerciseId = resolved.id,
            exerciseName = resolved.displayName,
            entries = entries,
            comparisonAvailable = comparisonAvailable,
            incompatibleModalitiesDetected = report.incompatibleModalitiesDetected
        )

        return LocalToolOutcome.Success(
            toolId = LocalToolId.EXERCISE_HISTORY,
            data = history,
            facts = buildList {
                add("Ejercicio: ${history.exerciseName}")
                add("Sesiones registradas: ${chronological.size}")
                entries.forEach { entry ->
                    add(
                        "Sesión ${formatDate(entry.startedAtMillis)} — ${entry.sessionTitle}: " +
                            "${entry.topLoadDisplay} × ${entry.topLoadReps} reps (${entry.setsCount} series)"
                    )
                }
                if (!history.comparisonAvailable) {
                    add("Comparación no disponible: se necesita más de una sesión registrada.")
                }
                if (history.incompatibleModalitiesDetected) {
                    add("Modalidades de carga distintas: no se comparan directamente.")
                }
            }
        )
    }

    private fun setsForPoint(
        allSets: List<WorkoutSetEntity>,
        exerciseId: Long,
        exercise: ExerciseEntity?,
        sessionId: Long,
        modality: String
    ): List<WorkoutSetEntity> {
        val sameExercise = allSets.filter { it.sessionId == sessionId && it.exerciseId == exerciseId }
        val sameModality = sameExercise.filter {
            SportsMetricsCalculator.classifySetModality(it, exercise?.displayName.orEmpty(), exercise?.equipment)
                .name == modality
        }
        return (if (sameModality.isNotEmpty()) sameModality else sameExercise)
            .sortedWith(setOrder())
    }

    private fun WorkoutSetEntity.toToolSet(exercisesById: Map<Long, ExerciseEntity>): LocalToolSet {
        val displayName = exercisesById[exerciseId]?.displayName
            ?: "Ejercicio sin nombre (id=$exerciseId)"
        return LocalToolSet(
            exerciseId = exerciseId,
            exerciseName = displayName,
            loadGrams = loadGrams,
            loadUnit = loadUnit,
            repetitions = repetitions,
            rir = rir,
            isAssisted = isAssisted,
            isDumbbell = isDumbbell,
            orderInExercise = orderInExercise,
            executionOrder = executionOrder,
            displayLoad = UnitConverter.formatDisplay(loadGrams, loadUnit)
        )
    }

    private fun setOrder(): Comparator<WorkoutSetEntity> =
        compareBy<WorkoutSetEntity> { it.executionOrder }
            .thenBy { it.orderInExercise }
            .thenBy { it.id }

    private fun formatDate(millis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    private fun LocalToolRequest.toolId(): LocalToolId = when (this) {
        LocalToolRequest.LastWorkout -> LocalToolId.LAST_WORKOUT
        is LocalToolRequest.LastExerciseSession -> LocalToolId.LAST_EXERCISE_SESSION
        is LocalToolRequest.ExerciseHistory -> LocalToolId.EXERCISE_HISTORY
    }
}
