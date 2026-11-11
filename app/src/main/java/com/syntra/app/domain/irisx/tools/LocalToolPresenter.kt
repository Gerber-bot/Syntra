package com.syntra.app.domain.irisx.tools

import java.text.DateFormat
import java.util.Date

/**
 * Builds the conversational explanation of a tool outcome.
 *
 * The text is assembled only from the typed payload and its [LocalToolOutcome.Success.facts].
 * No neural model participates here, so a number that was not read from the database can never
 * appear in the answer.
 */
object LocalToolPresenter {

    fun present(outcome: LocalToolOutcome<*>): LocalToolPresentation = when (outcome) {
        is LocalToolOutcome.Success -> LocalToolPresentation(
            text = when (val data = outcome.data) {
                is LocalToolWorkout -> renderWorkout(data)
                is LocalToolRecentWorkouts -> renderRecentWorkouts(data)
                is LocalToolExerciseSnapshot -> renderExerciseSnapshot(data)
                is LocalToolExerciseHistory -> renderHistory(data)
                else -> "Consulta completada."
            },
            facts = outcome.facts,
            toolId = outcome.toolId
        )

        is LocalToolOutcome.NoData -> LocalToolPresentation(
            text = "No tengo ese dato: ${outcome.reason}",
            facts = listOf(outcome.reason),
            toolId = outcome.toolId
        )

        is LocalToolOutcome.InsufficientData -> LocalToolPresentation(
            text = "Datos insuficientes: ${outcome.reason}",
            facts = listOf(outcome.reason),
            toolId = outcome.toolId
        )

        is LocalToolOutcome.QueryError -> LocalToolPresentation(
            text = "No pude resolver la consulta: ${outcome.reason}",
            facts = listOf(outcome.reason),
            toolId = outcome.toolId
        )
    }

    private fun renderWorkout(workout: LocalToolWorkout): String = buildString {
        append("🏋️ Último entrenamiento: ${workout.title} (${formatDate(workout.startedAtMillis)})\n")
        append("• Series registradas: ${workout.totalSets}\n")
        val byExercise = workout.sets.groupBy { it.exerciseName }
        if (byExercise.isNotEmpty()) {
            append("Series por ejercicio:\n")
            byExercise.forEach { (exerciseName, sets) ->
                append("• $exerciseName: ${sets.joinToString(", ") { formatSet(it) }}\n")
            }
        }
    }.trim()

    private fun renderRecentWorkouts(recent: LocalToolRecentWorkouts): String = buildString {
        append(
            "📅 Entrenamientos recientes (${recent.workouts.size} de " +
                "${recent.totalRecordedSessions} registrados)\n"
        )
        recent.workouts.forEach { w ->
            val detail = if (w.totalSets == 0) {
                "sin series registradas"
            } else {
                val exList = if (w.exerciseNames.isNotEmpty()) {
                    ": ${w.exerciseNames.joinToString(", ")}"
                } else {
                    ""
                }
                "${w.totalSets} series$exList"
            }
            append("• ${formatDate(w.startedAtMillis)} — ${w.title} ($detail)\n")
        }
    }.trim()

    private fun renderExerciseSnapshot(snapshot: LocalToolExerciseSnapshot): String = buildString {
        append(
            "🏋️ Último registro en ${snapshot.exerciseName} " +
                "(${formatDate(snapshot.startedAtMillis)}) — ${snapshot.sessionTitle}\n"
        )
        append("• Mejor serie: ${snapshot.topLoadDisplay} × ${snapshot.topLoadReps} reps\n")
        val summary = if (snapshot.sets.isNotEmpty()) {
            snapshot.sets.joinToString(", ") { formatSet(it) }
        } else {
            "${snapshot.topLoadDisplay} × ${snapshot.topLoadReps} reps (${snapshot.setsCount} series)"
        }
        append("• Series ejecutadas (${snapshot.setsCount}): $summary\n")
        append("• Repeticiones totales: ${snapshot.totalReps}")
        snapshot.previousSession?.let { previous ->
            append(
                "\n• Sesión anterior (${formatDate(previous.startedAtMillis)}, ${previous.sessionTitle}): " +
                    "${previous.topLoadDisplay} × ${previous.topLoadReps} reps (${previous.setsCount} series)"
            )
        }
    }

    private fun renderHistory(history: LocalToolExerciseHistory): String = buildString {
        append("📊 Historial de ${history.exerciseName} (${history.entries.size} sesiones)\n")
        history.entries.forEach { entry ->
            append(
                "• ${formatDate(entry.startedAtMillis)} — ${entry.sessionTitle}: " +
                    "${entry.topLoadDisplay} × ${entry.topLoadReps} reps " +
                    "(${entry.setsCount} series, ${entry.totalReps} repeticiones)\n"
            )
        }
        if (!history.comparisonAvailable) {
            append("Solo hay una sesión registrada: todavía no hay comparación entre sesiones.")
        }
        if (history.incompatibleModalitiesDetected) {
            append(
                "Las sesiones usan modalidades de carga distintas (por ejemplo, barra y mancuernas): " +
                    "no se comparan directamente."
            )
        }
    }.trim()

    private fun formatSet(set: LocalToolSet): String {
        val modifier = when {
            set.isAssisted -> " asist."
            set.isDumbbell -> " c/u"
            else -> ""
        }
        return "${set.displayLoad}$modifier × ${set.repetitions}"
    }

    private fun formatDate(millis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
}
