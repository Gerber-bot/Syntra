package com.syntra.app.domain

import com.syntra.app.data.WorkoutSessionEntity
import java.text.Normalizer
import java.util.Locale

enum class WorkoutCategoryKey(val displayName: String, val icon: String) {
    TODAS("Todas", "★"),
    PIERNAS("Piernas", "🦵"),
    PECHO("Pecho", "🛡️"),
    ESPALDA("Espalda", "🏹"),
    HOMBROS("Hombros", "🏋️"),
    BRAZOS("Brazos", "💪"),
    FULL_BODY("Full Body", "⚡"),
    SIN_CLASIFICAR("Otras / Sin clasificar", "📁")
}

data class CategorySummary(
    val key: WorkoutCategoryKey,
    val displayName: String,
    val icon: String,
    val sessionCount: Int,
    val latestSessionMillis: Long?
)

object WorkoutCategoryClassifier {

    private fun normalize(text: String): String {
        return Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private val piernaKeywords = setOf("pierna", "piernas", "leg", "legs", "cuadriceps", "isquio", "isquios", "gluteo", "gluteos", "pantorrilla", "pantorrillas", "gemelo", "gemelos", "patas")
    private val pechoKeywords = setOf("pecho", "pechos", "chest", "pectoral", "pectorales", "banca")
    private val espaldaKeywords = setOf("espalda", "espaldas", "back", "dorsal", "dorsales", "remo", "tiron", "tirones", "pull")
    private val hombroKeywords = setOf("hombro", "hombros", "shoulder", "shoulders", "deltoide", "deltoides", "militar")
    private val brazoKeywords = setOf("brazo", "brazos", "arm", "arms", "bicep", "biceps", "tricep", "triceps", "antebrazo", "antebrazos")
    private val fullBodyKeywords = setOf("full body", "fullbody", "cuerpo completo", "total body")
    private val genericTitles = setOf("entrenamiento", "sesion", "rutina", "gym", "gimnasio", "entreno", "workout")

    fun classify(title: String, exerciseMuscleGroups: List<String> = emptyList()): WorkoutCategoryKey {
        val norm = normalize(title)
        if (norm.isBlank()) {
            return classifyFromMuscleGroups(exerciseMuscleGroups)
        }

        // Direct matching or token matching in title
        val tokens = norm.split(" ").filter { it.isNotBlank() }

        // Full Body priority (multi-word)
        if (fullBodyKeywords.any { norm.contains(it) }) {
            return WorkoutCategoryKey.FULL_BODY
        }

        // Single word or keyword matching
        if (tokens.any { piernaKeywords.contains(it) }) return WorkoutCategoryKey.PIERNAS
        if (tokens.any { pechoKeywords.contains(it) }) return WorkoutCategoryKey.PECHO
        if (tokens.any { espaldaKeywords.contains(it) }) return WorkoutCategoryKey.ESPALDA
        if (tokens.any { hombroKeywords.contains(it) }) return WorkoutCategoryKey.HOMBROS
        if (tokens.any { brazoKeywords.contains(it) }) return WorkoutCategoryKey.BRAZOS

        // If title is generic, fallback to exercise muscle groups
        if (genericTitles.any { norm.contains(it) } || norm.all { it.isDigit() || it.isWhitespace() }) {
            val fromExercises = classifyFromMuscleGroups(exerciseMuscleGroups)
            if (fromExercises != WorkoutCategoryKey.SIN_CLASIFICAR) {
                return fromExercises
            }
        }

        // Ambiguous or unclassified
        return WorkoutCategoryKey.SIN_CLASIFICAR
    }

    private fun classifyFromMuscleGroups(muscleGroups: List<String>): WorkoutCategoryKey {
        if (muscleGroups.isEmpty()) return WorkoutCategoryKey.SIN_CLASIFICAR

        val normalizedGroups = muscleGroups.map { normalize(it) }.filter { it.isNotBlank() && it != "general" }
        if (normalizedGroups.isEmpty()) return WorkoutCategoryKey.SIN_CLASIFICAR

        val categoryVotes = mutableMapOf<WorkoutCategoryKey, Int>()
        for (g in normalizedGroups) {
            when {
                piernaKeywords.any { g.contains(it) } -> categoryVotes[WorkoutCategoryKey.PIERNAS] = (categoryVotes[WorkoutCategoryKey.PIERNAS] ?: 0) + 1
                pechoKeywords.any { g.contains(it) } -> categoryVotes[WorkoutCategoryKey.PECHO] = (categoryVotes[WorkoutCategoryKey.PECHO] ?: 0) + 1
                espaldaKeywords.any { g.contains(it) } -> categoryVotes[WorkoutCategoryKey.ESPALDA] = (categoryVotes[WorkoutCategoryKey.ESPALDA] ?: 0) + 1
                hombroKeywords.any { g.contains(it) } -> categoryVotes[WorkoutCategoryKey.HOMBROS] = (categoryVotes[WorkoutCategoryKey.HOMBROS] ?: 0) + 1
                brazoKeywords.any { g.contains(it) } -> categoryVotes[WorkoutCategoryKey.BRAZOS] = (categoryVotes[WorkoutCategoryKey.BRAZOS] ?: 0) + 1
            }
        }

        if (categoryVotes.isEmpty()) return WorkoutCategoryKey.SIN_CLASIFICAR

        // If upper & lower groups are both heavily present -> FULL_BODY
        val hasLegs = (categoryVotes[WorkoutCategoryKey.PIERNAS] ?: 0) > 0
        val upperVotes = (categoryVotes[WorkoutCategoryKey.PECHO] ?: 0) +
                (categoryVotes[WorkoutCategoryKey.ESPALDA] ?: 0) +
                (categoryVotes[WorkoutCategoryKey.HOMBROS] ?: 0)
        if (hasLegs && upperVotes >= 2) {
            return WorkoutCategoryKey.FULL_BODY
        }

        // Predominant group
        val dominant = categoryVotes.maxByOrNull { it.value }
        return if (dominant != null && dominant.value > 0) {
            dominant.key
        } else {
            WorkoutCategoryKey.SIN_CLASIFICAR
        }
    }

    fun buildSummaries(
        sessions: List<WorkoutSessionEntity>,
        sessionCategoryMap: Map<Long, WorkoutCategoryKey>
    ): List<CategorySummary> {
        val totalSessions = sessions.size
        val latestTotal = sessions.maxOfOrNull { it.startedAtMillis }

        val allCategories = listOf(
            WorkoutCategoryKey.TODAS,
            WorkoutCategoryKey.PIERNAS,
            WorkoutCategoryKey.PECHO,
            WorkoutCategoryKey.ESPALDA,
            WorkoutCategoryKey.HOMBROS,
            WorkoutCategoryKey.BRAZOS,
            WorkoutCategoryKey.FULL_BODY,
            WorkoutCategoryKey.SIN_CLASIFICAR
        )

        val grouped = sessions.groupBy { sessionCategoryMap[it.id] ?: WorkoutCategoryKey.SIN_CLASIFICAR }

        return allCategories.map { key ->
            if (key == WorkoutCategoryKey.TODAS) {
                CategorySummary(
                    key = key,
                    displayName = key.displayName,
                    icon = key.icon,
                    sessionCount = totalSessions,
                    latestSessionMillis = latestTotal
                )
            } else {
                val matching = grouped[key].orEmpty()
                CategorySummary(
                    key = key,
                    displayName = key.displayName,
                    icon = key.icon,
                    sessionCount = matching.size,
                    latestSessionMillis = matching.maxOfOrNull { it.startedAtMillis }
                )
            }
        }
    }
}
