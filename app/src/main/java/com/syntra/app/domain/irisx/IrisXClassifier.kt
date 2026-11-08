package com.syntra.app.domain.irisx

import com.syntra.app.domain.ExerciseCatalog
import com.syntra.app.domain.WorkoutCategoryKey
import java.text.Normalizer
import java.util.Locale

object IrisXClassifier {

    private fun normalize(text: String): String {
        return Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private val piernaKeywords = listOf(
        "pierna", "piernas", "leg", "legs", "cuadricep", "cuadriceps", "isquio", "isquios",
        "gluteo", "gluteos", "pantorrilla", "pantorrillas", "gemelo", "gemelos", "patas",
        "sentadilla", "squat", "prensa", "camilla", "sillon", "femoral", "bulgara", "estocada",
        "hack", "hacka", "hip thrust", "peso muerto rumano", "rdl", "rumano", "buenos dias", "gemelos"
    )

    private val pechoKeywords = listOf(
        "pecho", "pechos", "chest", "pectoral", "pectorales", "banca", "bench", "press plano",
        "inclinado", "declinado", "hammer", "cruce", "cruces", "polea cruzada", "apertura", "aperturas",
        "flyes", "fondos", "peck deck", "pec deck", "contractor"
    )

    private val espaldaKeywords = listOf(
        "espalda", "espaldas", "back", "dorsal", "dorsales", "remo", "tiron", "tirones",
        "pull", "dominada", "dominadas", "pull up", "pullups", "chin up", "jalon", "polea al pecho",
        "deadlift", "peso muerto convencional", "gironda", "serrucho", "pullover"
    )

    private val hombroKeywords = listOf(
        "hombro", "hombros", "shoulder", "shoulders", "deltoide", "deltoides", "militar",
        "overhead", "ohp", "vuelo", "vuelos", "lateral", "laterales", "arnold", "pajaro",
        "pajaros", "face pull", "trapecio"
    )

    private val tricepKeywords = listOf(
        "tricep", "triceps", "frances", "frente", "triangulo", "soga", "cuerda", "copa",
        "pushdown", "patada de burro", "fondos en paralelas"
    )

    private val bicepKeywords = listOf(
        "bicep", "biceps", "curl", "martillo", "scott", "predicador", "braquial"
    )

    private val genericTitles = setOf(
        "entrenamiento", "sesion", "rutina", "gym", "gimnasio", "entreno", "workout", "dia"
    )

    /**
     * Resolves the primary muscle group of an exercise by checking:
     * 1. Learned alias mapping (IrisX memory)
     * 2. Exercise catalog (display names and known aliases)
     * 3. Keyword / token heuristic
     */
    fun detectMuscleGroup(
        exerciseName: String,
        learnedAliases: Map<String, String> = emptyMap(),
        muscleOverrides: Map<String, String> = emptyMap()
    ): String {
        val norm = normalize(exerciseName)
        if (norm.isBlank()) return "General"

        // 1. Learned alias check
        val learnedCanonical = learnedAliases[norm] ?: learnedAliases[exerciseName]
        val nameToCheck = learnedCanonical ?: exerciseName
        val normToCheck = normalize(nameToCheck)

        // 1b. Explicit user muscle group override check
        muscleOverrides[normToCheck]?.let { return it }
        muscleOverrides[norm]?.let { return it }

        // 2. ExerciseCatalog direct match or alias match
        for (catEx in ExerciseCatalog.DEFAULT_CATALOG) {
            val catNorm = normalize(catEx.displayName)
            if (normToCheck == catNorm || catEx.aliases.any { normalize(it) == normToCheck }) {
                return catEx.muscleGroup
            }
        }

        // Substring / token matching in catalog
        for (catEx in ExerciseCatalog.DEFAULT_CATALOG) {
            val catNorm = normalize(catEx.displayName)
            if (normToCheck.contains(catNorm) || catEx.aliases.any { normToCheck.contains(normalize(it)) }) {
                return catEx.muscleGroup
            }
        }

        // 3. Keyword heuristics
        if (tricepKeywords.any { normToCheck.contains(it) }) return "Tríceps"
        if (bicepKeywords.any { normToCheck.contains(it) }) return "Bíceps"
        if (pechoKeywords.any { normToCheck.contains(it) }) return "Pecho"
        if (espaldaKeywords.any { normToCheck.contains(it) }) return "Espalda"
        if (hombroKeywords.any { normToCheck.contains(it) }) return "Hombros"
        if (piernaKeywords.any { normToCheck.contains(it) }) return "Piernas"

        return "General"
    }

    /**
     * Maps fine-grained muscle group strings to high-level WorkoutCategoryKey.
     */
    fun mapMuscleToCategory(muscle: String): WorkoutCategoryKey {
        return when (muscle.trim().lowercase(Locale.ROOT)) {
            "pecho", "pectoral", "pectorales" -> WorkoutCategoryKey.PECHO
            "espalda", "dorsal", "dorsales" -> WorkoutCategoryKey.ESPALDA
            "hombros", "hombro", "deltoides" -> WorkoutCategoryKey.HOMBROS
            "brazos", "brazo", "bíceps", "biceps", "tríceps", "triceps", "antebrazo" -> WorkoutCategoryKey.BRAZOS
            "piernas", "pierna", "cuádriceps", "cuadriceps", "isquiosurales", "isquios", "glúteos", "gluteos", "pantorrillas", "gemelos" -> WorkoutCategoryKey.PIERNAS
            else -> WorkoutCategoryKey.SIN_CLASIFICAR
        }
    }

    /**
     * Classifies a workout session taking into account the title and the list of exercises.
     */
    fun classifyWorkout(
        sessionTitle: String,
        exerciseNames: List<String>,
        learnedAliases: Map<String, String> = emptyMap(),
        muscleOverrides: Map<String, String> = emptyMap()
    ): ClassificationResult {
        val normTitle = normalize(sessionTitle)

        // Count votes per category from exercises
        val muscleBreakdown = mutableMapOf<String, Int>()
        val categoryVotes = mutableMapOf<WorkoutCategoryKey, Int>()

        var chestExercises = 0
        var shoulderExercises = 0
        var tricepsExercises = 0
        var backExercises = 0
        var bicepsExercises = 0
        var legExercises = 0

        for (ex in exerciseNames) {
            val muscle = detectMuscleGroup(ex, learnedAliases, muscleOverrides)
            muscleBreakdown[muscle] = (muscleBreakdown[muscle] ?: 0) + 1

            when (muscle) {
                "Pecho" -> {
                    chestExercises++
                    categoryVotes[WorkoutCategoryKey.PECHO] = (categoryVotes[WorkoutCategoryKey.PECHO] ?: 0) + 1
                }
                "Espalda" -> {
                    backExercises++
                    categoryVotes[WorkoutCategoryKey.ESPALDA] = (categoryVotes[WorkoutCategoryKey.ESPALDA] ?: 0) + 1
                }
                "Hombros" -> {
                    shoulderExercises++
                    categoryVotes[WorkoutCategoryKey.HOMBROS] = (categoryVotes[WorkoutCategoryKey.HOMBROS] ?: 0) + 1
                }
                "Tríceps" -> {
                    tricepsExercises++
                    categoryVotes[WorkoutCategoryKey.BRAZOS] = (categoryVotes[WorkoutCategoryKey.BRAZOS] ?: 0) + 1
                }
                "Bíceps" -> {
                    bicepsExercises++
                    categoryVotes[WorkoutCategoryKey.BRAZOS] = (categoryVotes[WorkoutCategoryKey.BRAZOS] ?: 0) + 1
                }
                "Piernas", "Glúteos" -> {
                    legExercises++
                    categoryVotes[WorkoutCategoryKey.PIERNAS] = (categoryVotes[WorkoutCategoryKey.PIERNAS] ?: 0) + 1
                }
                else -> {
                    // Category could be mapped
                    val cat = mapMuscleToCategory(muscle)
                    if (cat != WorkoutCategoryKey.SIN_CLASIFICAR) {
                        categoryVotes[cat] = (categoryVotes[cat] ?: 0) + 1
                    }
                }
            }
        }

        val totalExercises = exerciseNames.size
        val pushCount = chestExercises + shoulderExercises + tricepsExercises
        val pullCount = backExercises + bicepsExercises
        val legCount = legExercises

        // Determine session type (Push, Pull, Legs, Torso, Full Body, Brazos, Mixto)
        val sessionType = when {
            totalExercises >= 2 && pushCount >= 2 && (pushCount.toDouble() / totalExercises) >= 0.65 -> "Empuje (Push)"
            totalExercises >= 2 && pullCount >= 2 && (pullCount.toDouble() / totalExercises) >= 0.65 -> "Tirón (Pull)"
            totalExercises >= 2 && legCount >= 2 && (legCount.toDouble() / totalExercises) >= 0.65 -> "Piernas (Legs)"
            totalExercises >= 3 && legCount >= 1 && (pushCount >= 2 || pullCount >= 2) -> "Full Body"
            totalExercises >= 2 && (chestExercises >= 1 || shoulderExercises >= 1) && backExercises >= 1 && legCount == 0 -> "Torso"
            totalExercises >= 2 && (bicepsExercises + tricepsExercises).toDouble() / totalExercises >= 0.7 -> "Brazos"
            else -> "Mixto"
        }

        // Title direct keyword priority if title is explicitly descriptive
        val titleTokens = normTitle.split(" ").filter { it.isNotBlank() }
        val titleIsGeneric = normTitle.isBlank() ||
                genericTitles.any { normTitle == it || normTitle.startsWith("$it ") } ||
                normTitle.all { it.isDigit() || it.isWhitespace() }

        var primaryCategory: WorkoutCategoryKey
        var isAmbiguous = false

        if (!titleIsGeneric) {
            when {
                normTitle.contains("full body") || normTitle.contains("fullbody") || normTitle.contains("cuerpo completo") -> {
                    primaryCategory = WorkoutCategoryKey.FULL_BODY
                }
                piernaKeywords.any { titleTokens.contains(it) } -> primaryCategory = WorkoutCategoryKey.PIERNAS
                pechoKeywords.any { titleTokens.contains(it) } -> primaryCategory = WorkoutCategoryKey.PECHO
                espaldaKeywords.any { titleTokens.contains(it) } -> primaryCategory = WorkoutCategoryKey.ESPALDA
                hombroKeywords.any { titleTokens.contains(it) } -> primaryCategory = WorkoutCategoryKey.HOMBROS
                titleTokens.any { it.contains("brazo") || it.contains("bicep") || it.contains("tricep") } -> primaryCategory = WorkoutCategoryKey.BRAZOS
                else -> {
                    primaryCategory = resolveFromVotes(categoryVotes, totalExercises)
                }
            }
        } else {
            primaryCategory = resolveFromVotes(categoryVotes, totalExercises)
        }

        // If exercises suggest Full Body and title was generic
        if (sessionType == "Full Body" && primaryCategory == WorkoutCategoryKey.SIN_CLASIFICAR) {
            primaryCategory = WorkoutCategoryKey.FULL_BODY
        }

        // Secondary categories: sort by votes descending, excluding primary
        val secondaryCategories = categoryVotes.entries
            .filter { it.key != primaryCategory && it.key != WorkoutCategoryKey.SIN_CLASIFICAR && it.value > 0 }
            .sortedByDescending { it.value }
            .map { it.key }

        // Check ambiguity
        val maxVotes = categoryVotes.values.maxOrNull() ?: 0
        val topCategories = categoryVotes.filter { it.value == maxVotes && it.value > 0 }
        if (topCategories.size > 1 && totalExercises >= 2) {
            isAmbiguous = true
        }

        // Generate reasoning
        val reasoningParts = mutableListOf<String>()
        val breakdownStr = muscleBreakdown.entries
            .filter { it.key != "General" && it.value > 0 }
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.value} ${it.key}" }

        if (breakdownStr.isNotBlank()) {
            reasoningParts.add("Ejercicios analizados: $breakdownStr.")
        }
        if (sessionType != "Mixto") {
            reasoningParts.add("Patrón identificado: $sessionType.")
        }
        reasoningParts.add("Categoría asignada: ${primaryCategory.displayName}.")

        val reasoning = reasoningParts.joinToString(" ")

        val confidence = if (totalExercises == 0) 0.5 else {
            val recognizedCount = totalExercises - (muscleBreakdown["General"] ?: 0)
            (recognizedCount.toDouble() / totalExercises).coerceIn(0.1, 1.0)
        }

        return ClassificationResult(
            primaryCategory = primaryCategory,
            secondaryCategories = secondaryCategories,
            sessionType = sessionType,
            confidence = confidence,
            muscleBreakdown = muscleBreakdown,
            isAmbiguous = isAmbiguous,
            reasoning = reasoning
        )
    }

    private fun resolveFromVotes(
        categoryVotes: Map<WorkoutCategoryKey, Int>,
        totalExercises: Int
    ): WorkoutCategoryKey {
        if (categoryVotes.isEmpty() || totalExercises == 0) {
            return WorkoutCategoryKey.SIN_CLASIFICAR
        }

        val hasLegs = (categoryVotes[WorkoutCategoryKey.PIERNAS] ?: 0) > 0
        val upperVotes = (categoryVotes[WorkoutCategoryKey.PECHO] ?: 0) +
                (categoryVotes[WorkoutCategoryKey.ESPALDA] ?: 0) +
                (categoryVotes[WorkoutCategoryKey.HOMBROS] ?: 0)

        if (hasLegs && upperVotes >= 2) {
            return WorkoutCategoryKey.FULL_BODY
        }

        val sorted = categoryVotes.entries.sortedByDescending { it.value }
        val top = sorted.firstOrNull()

        return if (top != null && top.value > 0) {
            top.key
        } else {
            WorkoutCategoryKey.SIN_CLASIFICAR
        }
    }
}
