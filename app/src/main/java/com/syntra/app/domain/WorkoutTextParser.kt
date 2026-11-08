package com.syntra.app.domain

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/** Deterministic and conservative parser: never writes directly to the database. */
data class ParsedSet(
    val weightGrams: Int,
    val repetitions: Int,
    val isAssisted: Boolean = false,
    val isDumbbell: Boolean = false,
    val note: String = ""
)

data class ParsedExercise(
    val name: String,
    val sets: List<ParsedSet>,
    val plannedTarget: String? = null,
    val isNewExercise: Boolean = false
)

data class IrisXDirective(val command: String, val line: Int)

data class ParsePreview(
    val sessionTitle: String?,
    val exercises: List<ParsedExercise>,
    val warnings: List<String>,
    val assistantCommands: List<IrisXDirective> = emptyList()
) {
    val seriesCount: Int get() = exercises.sumOf { it.sets.size }
}

object WorkoutTextParser {
    private val kgRegex = Regex("(?i)([-+]?\\d+(?:[.,]\\d+)?)\\s*kg\\b")
    private val tokenRegex = Regex("(?i)([-+]?\\d+(?:[.,]\\d+)?)\\s*kg\\b|\\b(\\d{1,2})\\b")
    private val irisxCommandPrefix = Regex("^(?i)(?:irisx|@irisx)[:,-]?\\s*(.+)$")
    private val knownTitles = setOf("piernas", "pecho", "espalda", "hombros", "brazos", "full body", "torso", "empuje", "tiron", "push", "pull")

    private val planningRegex = Regex("(?i)\\b(\\d+)\\s*(?:x|de)\\s*(\\d+)(?:\\s*(?:a|-)\\s*(\\d+))?\\s*(?:reps?|repeticiones)?\\b")
    private val ambiguousSumRegex = Regex("(?i)\\b(\\d+)\\s*\\+\\s*(\\d+)\\b")
    private val dumbbellRegex = Regex("(?i)\\b(?:mancuernas?|por brazo|cada mano|c/u)\\b")
    private val assistedRegex = Regex("(?i)\\b(?:asistid[oa]s?|ayuda)\\b")
    private val unilateralRegex = Regex("(?i)\\b(?:unilateral|a un brazo|a una mano|a una pierna|bulgaras?|búlgaras?|serrucho)\\b")
    private val weightedBwRegex = Regex("(?i)\\b(?:lastre|lastrad[oa]s?)\\b")

    fun normalize(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\s+"), " ")

    fun parse(
        raw: String,
        aliases: Map<String, String> = emptyMap(),
        knownExercises: Set<String> = emptySet()
    ): ParsePreview {
        var sessionTitle: String? = null
        val exercises = mutableListOf<ParsedExercise>()
        val warnings = mutableListOf<String>()
        val assistantCommands = mutableListOf<IrisXDirective>()
        val aliasIndex = aliases.entries.associate { normalize(it.key) to it.value }

        val catalogNormalized = ExerciseCatalog.DEFAULT_CATALOG.map { it.normalizedName }.toSet()
        val allKnown = (knownExercises + catalogNormalized).map { normalize(it) }.toSet()

        raw.lineSequence().forEachIndexed { lineNumber, source ->
            val line = source.trim().trimEnd('.', ';')
            if (line.isEmpty()) return@forEachIndexed

            // 1. Detect explicit commands directed to IrisX assistant
            val irisxMatch = irisxCommandPrefix.find(line)
            if (irisxMatch != null) {
                val commandText = irisxMatch.groups[1]?.value?.trim().orEmpty()
                assistantCommands += IrisXDirective(command = commandText, line = lineNumber + 1)
                return@forEachIndexed
            }

            // 2. Detect session titles (exact match or starts with a known session title token when line has no kg)
            val cleanHeaderCandidate = normalize(line.removeSuffix(":"))
            val isTitleCandidate = cleanHeaderCandidate in knownTitles ||
                (sessionTitle == null && kgRegex.find(line) == null && knownTitles.any {
                    cleanHeaderCandidate.startsWith("$it ") || cleanHeaderCandidate.startsWith("rutina ")
                })
            if (isTitleCandidate && sessionTitle == null) {
                sessionTitle = line.removeSuffix(":")
                return@forEachIndexed
            }

            // 3. Detect ambiguous expressions like "3 + 6"
            val ambiguousMatch = ambiguousSumRegex.find(line)
            if (ambiguousMatch != null) {
                warnings += "Línea ${lineNumber + 1}: Expresión ambigua detectada ('${ambiguousMatch.value}'). Especificá si son repeticiones asistidas, series o descansos."
            }

            // 4. Disambiguate everyday phrases (e.g. "buenos días") when missing exercise weight
            val firstKg = kgRegex.find(line)
            if (firstKg == null) {
                val normalizedLine = normalize(line)
                if (normalizedLine == "buenos dias") {
                    warnings += "Línea ${lineNumber + 1}: 'Buenos días' no tiene carga ni series. Si es el ejercicio, especificá el peso (ej. 'Buenos días 40kg 10'). Si es para el asistente, usá 'IrisX: <mensaje>'."
                } else if (ambiguousMatch == null) {
                    warnings += "Línea ${lineNumber + 1}: no se reconoció un peso con unidad kg."
                }
                return@forEachIndexed
            }

            // 5. Separate exercise name & planned target from execution values
            val prefix = line.substring(0, firstKg.range.first).trim()
            val values = line.substring(firstKg.range.first)

            // Extract planning (e.g. "3 de 10 a 12", "3x10-12")
            var plannedTarget: String? = null
            var exerciseNameRaw = prefix

            val planMatch = planningRegex.find(prefix)
            if (planMatch != null) {
                val setsCount = planMatch.groups[1]?.value
                val minReps = planMatch.groups[2]?.value
                val maxReps = planMatch.groups[3]?.value
                plannedTarget = if (maxReps != null) {
                    "$setsCount series de $minReps–$maxReps repeticiones"
                } else {
                    "$setsCount series de $minReps repeticiones"
                }
                // Strip planning from exercise name
                exerciseNameRaw = prefix.removeRange(planMatch.range)
            }

            val cleanName = exerciseNameRaw.trim(' ', ':', '-', '–', ',')
            if (cleanName.isBlank()) {
                warnings += "Línea ${lineNumber + 1}: falta el nombre del ejercicio."
                return@forEachIndexed
            }

            // Detect modifiers
            val isDumbbell = dumbbellRegex.containsMatchIn(line)
            val isAssistedLine = assistedRegex.containsMatchIn(line)
            val isUnilateralLine = unilateralRegex.containsMatchIn(line)
            val isWeightedBwLine = weightedBwRegex.containsMatchIn(line)

            // Resolve aliases & canonical name
            val resolvedName = aliasIndex[normalize(cleanName)] ?: cleanName
            val isKnown = allKnown.contains(normalize(resolvedName))
            val isNewExercise = !isKnown

            // Parse sets and weights
            var currentWeight: Int? = null
            var currentAssisted = isAssistedLine
            var currentWeightedBw = isWeightedBwLine
            val sets = mutableListOf<ParsedSet>()

            for (match in tokenRegex.findAll(values)) {
                val kilos = match.groups[1]?.value
                if (kilos != null) {
                    val rawClean = kilos.replace(',', '.')
                    val isNegative = rawClean.startsWith("-")
                    val isPositivePlus = rawClean.startsWith("+")
                    val absVal = rawClean.removePrefix("+").removePrefix("-").toDoubleOrNull()
                    if (absVal == null || absVal <= 0 || absVal > 1000) {
                        warnings += "Línea ${lineNumber + 1}: carga fuera de rango ($kilos)."
                        currentWeight = null
                    } else {
                        currentWeight = (absVal * 1000).roundToInt()
                        currentAssisted = isAssistedLine || isNegative
                        currentWeightedBw = isWeightedBwLine || isPositivePlus
                    }
                } else {
                    val reps = match.groups[2]?.value?.toIntOrNull()
                    if (currentWeight != null && reps != null && reps in 1..99) {
                        val noteTags = buildList {
                            if (isUnilateralLine) add("[UNILATERAL]")
                            if (currentWeightedBw && !currentAssisted) add("[LASTRE]")
                        }.joinToString(" ")
                        sets += ParsedSet(
                            weightGrams = currentWeight,
                            repetitions = reps,
                            isAssisted = currentAssisted,
                            isDumbbell = isDumbbell,
                            note = noteTags
                        )
                    }
                }
            }

            if (sets.isEmpty()) {
                warnings += "Línea ${lineNumber + 1}: no se encontraron series válidas para '$resolvedName'."
            } else {
                exercises += ParsedExercise(
                    name = resolvedName,
                    sets = sets,
                    plannedTarget = plannedTarget,
                    isNewExercise = isNewExercise
                )
            }
        }

        return ParsePreview(sessionTitle, exercises, warnings, assistantCommands)
    }
}
