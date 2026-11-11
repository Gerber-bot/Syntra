package com.syntra.app.domain.irisx

import com.syntra.app.data.IrisxConversationEntity
import com.syntra.app.domain.ExerciseCatalog
import java.text.Normalizer
import java.util.Locale

enum class PeriodScope(val key: String, val displayName: String, val days: Int) {
    THIS_WEEK("THIS_WEEK", "Esta semana (últimos 7 días)", 7),
    LAST_WEEK("LAST_WEEK", "Semana pasada (hace 7 a 14 días)", 7),
    THIS_MONTH("THIS_MONTH", "Este mes (últimos 30 días)", 30),
    LAST_MONTH("LAST_MONTH", "El mes pasado (hace 30 a 60 días)", 30),
    ALL_TIME("ALL_TIME", "Histórico completo", 3650)
}

/**
 * Holds the active conversational state for a deterministic dialogue thread.
 * Separated strictly from personal memories (irisx_memories) and workout records (workout_sessions).
 */
data class ConversationContextState(
    val conversationId: Long = 0L,
    val title: String = "Conversación IrisX",
    val activeMuscleGroup: String? = null,
    val activeExerciseName: String? = null,
    val activeSessionId: Long? = null,
    val activePeriodScope: PeriodScope = PeriodScope.THIS_MONTH,
    val lastQueryDomain: String? = null, // "PROGRESSION", "VOLUME", "RECORDS", "RECOMMENDATION"
    val pendingConfirmationCommand: String? = null
)

/**
 * Deterministic resolver for follow-up questions and contextual references.
 * Never uses neural inference or guesses unverified preferences.
 */
object IrisXContextResolver {

    private val knownMuscles = mapOf(
        "espalda" to "Espalda",
        "dorsal" to "Espalda",
        "dorsales" to "Espalda",
        "pecho" to "Pecho",
        "pectoral" to "Pecho",
        "pectorales" to "Pecho",
        "pierna" to "Piernas",
        "piernas" to "Piernas",
        "cuadriceps" to "Piernas",
        "isquios" to "Piernas",
        "gluteo" to "Glúteos",
        "gluteos" to "Glúteos",
        "hombro" to "Hombros",
        "hombros" to "Hombros",
        "deltoides" to "Hombros",
        "brazo" to "Brazos",
        "brazos" to "Brazos",
        "biceps" to "Bíceps",
        "triceps" to "Tríceps"
    )

    fun normalize(text: String): String {
        return Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun detectPeriodScope(normInput: String): PeriodScope? {
        return when {
            normInput.contains("mes pasado") || normInput.contains("mes anterior") || normInput.contains("ultimo mes") ->
                PeriodScope.LAST_MONTH
            normInput.contains("este mes") || normInput.contains("mes actual") ->
                PeriodScope.THIS_MONTH
            normInput.contains("semana pasada") || normInput.contains("semana anterior") ->
                PeriodScope.LAST_WEEK
            normInput.contains("esta semana") || normInput.contains("semana actual") || normInput.contains("ultimos 7 dias") ->
                PeriodScope.THIS_WEEK
            normInput.contains("historico") || normInput.contains("desde el principio") || normInput.contains("todo el historial") ->
                PeriodScope.ALL_TIME
            else -> null
        }
    }

    fun detectMuscleReference(normInput: String): String? {
        val tokens = normInput.split(" ")
        for (token in tokens) {
            knownMuscles[token]?.let { return it }
        }
        return null
    }

    /**
     * Distinguishes a general training question ("qué hacer un día de piernas") from a
     * recommendation about the athlete's own records ("recomendaciones para mejorar mi press").
     *
     * General requests must not demand history: the answer is generic guidance, never invented
     * personal data. Explicit personal references always win over general vocabulary.
     */
    fun isGeneralRecommendationRequest(normInput: String): Boolean {
        if (personalRecommendationMarkers.any { normInput.contains(it) }) return false
        return generalRecommendationMarkers.any { normInput.contains(it) }
    }

    private val personalRecommendationMarkers = listOf(
        "mi progreso", "mi progresion", "mis sesiones", "mis datos", "mi historial",
        "mi ultimo", "mi ultima", "segun mi", "mi rutina", "mi entrenamiento",
        "para mejorar mi", "mejorar mis", "mi marca", "mis marcas", "mis cargas"
    )

    private val generalRecommendationMarkers = listOf(
        "general", "generales", "guia", "principiante", "principiantes",
        "que hacer", "que hago", "que ejercicios", "como entrenar",
        "dia de", "rutina para", "armar una rutina", "planificar"
    )

    fun detectExerciseReference(
        normInput: String,
        knownExerciseNames: List<String>,
        learnedAliases: Map<String, String>
    ): String? {
        // 1. Check learned aliases longest first
        for ((alias, canonical) in learnedAliases.entries.sortedByDescending { it.key.length }) {
            val normAlias = normalize(alias)
            if (normAlias.isNotBlank() && containsWholePhrase(normInput, normAlias)) {
                return canonical
            }
        }

        // 2. Check database exercises longest first
        for (exName in knownExerciseNames.sortedByDescending { it.length }) {
            val normEx = normalize(exName)
            if (normEx.isNotBlank() && containsWholePhrase(normInput, normEx)) {
                return exName
            }
        }

        // 3. Check default catalog display names and aliases
        for (cat in ExerciseCatalog.DEFAULT_CATALOG) {
            val catNorm = normalize(cat.displayName)
            if (containsWholePhrase(normInput, catNorm)) {
                return cat.displayName
            }
            for (al in cat.aliases.sortedByDescending { it.length }) {
                val alNorm = normalize(al)
                if (alNorm.isNotBlank() && containsWholePhrase(normInput, alNorm)) {
                    return cat.displayName
                }
            }
        }

        return null
    }

    private fun containsWholePhrase(haystack: String, needle: String): Boolean {
        val paddedHaystack = " $haystack "
        val paddedNeedle = " $needle "
        return paddedHaystack.contains(paddedNeedle)
    }

    /**
     * Resolves PeriodScope into concrete [startMillis, endMillis] boundaries and a comparison baseline
     * relative to [referenceTimeMillis].
     */
    fun resolvePeriodWindow(
        scope: PeriodScope,
        referenceTimeMillis: Long = System.currentTimeMillis()
    ): PeriodWindow {
        val dayMs = 86_400_000L
        return when (scope) {
            PeriodScope.THIS_WEEK -> {
                val end = referenceTimeMillis
                val start = end - (7 * dayMs) + 1
                val prevEnd = start - 1
                val prevStart = prevEnd - (7 * dayMs) + 1
                PeriodWindow(
                    currentLabel = "Esta semana",
                    currentStartMillis = start,
                    currentEndMillis = end,
                    previousLabel = "Semana anterior",
                    previousStartMillis = prevStart,
                    previousEndMillis = prevEnd
                )
            }
            PeriodScope.LAST_WEEK -> {
                val end = referenceTimeMillis - (7 * dayMs)
                val start = end - (7 * dayMs) + 1
                val prevEnd = start - 1
                val prevStart = prevEnd - (7 * dayMs) + 1
                PeriodWindow(
                    currentLabel = "Semana pasada",
                    currentStartMillis = start,
                    currentEndMillis = end,
                    previousLabel = "Hace 2 semanas",
                    previousStartMillis = prevStart,
                    previousEndMillis = prevEnd
                )
            }
            PeriodScope.THIS_MONTH -> {
                val end = referenceTimeMillis
                val start = end - (30 * dayMs) + 1
                val prevEnd = start - 1
                val prevStart = prevEnd - (30 * dayMs) + 1
                PeriodWindow(
                    currentLabel = "Este mes (últimos 30d)",
                    currentStartMillis = start,
                    currentEndMillis = end,
                    previousLabel = "Mes anterior (30-60d)",
                    previousStartMillis = prevStart,
                    previousEndMillis = prevEnd
                )
            }
            PeriodScope.LAST_MONTH -> {
                val end = referenceTimeMillis - (30 * dayMs)
                val start = end - (30 * dayMs) + 1
                val currentEnd = referenceTimeMillis
                val currentStart = currentEnd - (30 * dayMs) + 1
                PeriodWindow(
                    currentLabel = "El mes pasado (30-60d)",
                    currentStartMillis = start,
                    currentEndMillis = end,
                    previousLabel = "Este mes (últimos 30d)",
                    previousStartMillis = currentStart,
                    previousEndMillis = currentEnd
                )
            }
            PeriodScope.ALL_TIME -> {
                PeriodWindow(
                    currentLabel = "Histórico completo",
                    currentStartMillis = 0L,
                    currentEndMillis = referenceTimeMillis,
                    previousLabel = "Período inicial",
                    previousStartMillis = 0L,
                    previousEndMillis = referenceTimeMillis / 2
                )
            }
        }
    }
}

data class PeriodWindow(
    val currentLabel: String,
    val currentStartMillis: Long,
    val currentEndMillis: Long,
    val previousLabel: String,
    val previousStartMillis: Long,
    val previousEndMillis: Long
)

fun IrisxConversationEntity.toState(): ConversationContextState {
    val period = PeriodScope.values().firstOrNull { it.key == activePeriodScope } ?: PeriodScope.THIS_MONTH
    return ConversationContextState(
        conversationId = id,
        title = title,
        activeMuscleGroup = activeMuscleGroup,
        activeExerciseName = activeExerciseName,
        activeSessionId = activeSessionId,
        activePeriodScope = period
    )
}
