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
    val isToFailure: Boolean = false,
    val loadUnit: String = "KG",
    val rawLoadValue: Double = UnitConverter.toRawValue(weightGrams, loadUnit),
    val note: String = ""
)

data class ParsedExercise(
    val name: String,
    val sets: List<ParsedSet>,
    val plannedTarget: String? = null,
    val isNewExercise: Boolean = false,
    val uid: String = java.util.UUID.randomUUID().toString()
)

data class IrisXDirective(val command: String, val line: Int)

data class UnrecognizedLine(
    val lineNumber: Int,
    val rawText: String,
    val reason: String
)

private sealed interface ValueTokenItem { val start: Int }
private data class LoadItem(override val start: Int, val value: Double, val unit: String) : ValueTokenItem
private data class RepItem(override val start: Int, val reps: Int, val isFailure: Boolean) : ValueTokenItem

data class ParsePreview(
    val sessionTitle: String?,
    val exercises: List<ParsedExercise>,
    val warnings: List<String>,
    val assistantCommands: List<IrisXDirective> = emptyList(),
    val unrecognizedLines: List<UnrecognizedLine> = emptyList(),
    val rawText: String = ""
) {
    val seriesCount: Int get() = exercises.sumOf { it.sets.size }
}

object WorkoutTextParser {
    private val loadWithUnitRegex = Regex("(?i)([-+]?\\d+(?:[.,]\\d+)?)\\s*(kg|kgs?|kilos?|lbs?|libras?|bloques?|placas?)\\b")
    private val bodyweightRegex = Regex("(?i)\\b(?:peso corporal|corporal|sin peso|bw)\\b")
    private val repTokenRegex = Regex("(?i)\\b(\\d{1,2})\\s*(?:f|al fallo|fallo)\\b|\\b(\\d{1,2})f\\b|\\b(\\d{1,2})\\b")
    private val failureNoteRegex = Regex("(?i)\\(?(fallo[^)]*)\\)?")
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
        knownExercises: Set<String> = emptySet(),
        contextCategory: String? = null
    ): ParsePreview {
        var explicitTitleDetected = false
        var sessionTitle: String? = contextCategory?.trim()?.takeIf { it.isNotEmpty() }
        val exercises = mutableListOf<ParsedExercise>()
        val warnings = mutableListOf<String>()
        val assistantCommands = mutableListOf<IrisXDirective>()
        val unrecognizedLines = mutableListOf<UnrecognizedLine>()
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

            // 2. Detect session titles (exact match or starts with a known session title token when line has no load)
            val cleanHeaderCandidate = normalize(line.removeSuffix(":"))
            val hasLoadIndicator = loadWithUnitRegex.containsMatchIn(line) || bodyweightRegex.containsMatchIn(line)
            val isTitleCandidate = cleanHeaderCandidate in knownTitles ||
                (!explicitTitleDetected && !hasLoadIndicator && knownTitles.any {
                    cleanHeaderCandidate.startsWith("$it ") || cleanHeaderCandidate.startsWith("rutina ")
                })
            if (isTitleCandidate && !explicitTitleDetected) {
                sessionTitle = line.removeSuffix(":")
                explicitTitleDetected = true
                return@forEachIndexed
            }

            // 3. Detect ambiguous expressions like "3 + 6"
            val ambiguousMatch = ambiguousSumRegex.find(line)
            if (ambiguousMatch != null) {
                warnings += "Línea ${lineNumber + 1}: Expresión ambigua detectada ('${ambiguousMatch.value}'). Especificá si son repeticiones asistidas, series o descansos."
            }

            // 4. Locate first load match or bodyweight indicator
            val firstLoad = loadWithUnitRegex.find(line) ?: bodyweightRegex.find(line)

            if (firstLoad == null) {
                // Check if line has planning notation (e.g. "Remo con barra 3 de 10 a 12")
                val planMatch = planningRegex.find(line)
                if (planMatch != null) {
                    val setsCount = planMatch.groups[1]?.value
                    val minReps = planMatch.groups[2]?.value
                    val maxReps = planMatch.groups[3]?.value
                    val plannedTarget = if (maxReps != null) {
                        "$setsCount series de $minReps–$maxReps repeticiones"
                    } else {
                        "$setsCount series de $minReps repeticiones"
                    }
                    val cleanName = line.removeRange(planMatch.range).trim(' ', ':', '-', '–', ',')
                    if (cleanName.isNotBlank()) {
                        val resolvedName = aliasIndex[normalize(cleanName)] ?: cleanName
                        exercises += ParsedExercise(
                            name = resolvedName,
                            sets = emptyList(),
                            plannedTarget = plannedTarget,
                            isNewExercise = !allKnown.contains(normalize(resolvedName))
                        )
                        warnings += "Línea ${lineNumber + 1}: '$resolvedName' tiene plan ($plannedTarget) pero no tiene series ejecutadas aún. Podés completarlas en la revisión."
                        return@forEachIndexed
                    }
                }

                // Detect exercise lines with reps and failure notation but missing load (e.g. 'Femoral parado 13 fallo')
                val failureMatch = Regex("^(.*?)[\\s:]+(\\d{1,2})\\s*(?:f|al fallo|fallo)$", RegexOption.IGNORE_CASE).find(line)
                if (failureMatch != null) {
                    val candidateName = failureMatch.groups[1]?.value?.trim(' ', ':', '-', '–', ',').orEmpty()
                    val reps = failureMatch.groups[2]?.value?.toIntOrNull()
                    if (candidateName.isNotBlank() && reps != null && reps in 1..99) {
                        val resolvedName = aliasIndex[normalize(candidateName)] ?: candidateName
                        val reason = "Se identificó '$resolvedName' con $reps repeticiones al fallo, pero falta la carga (ej. kg, bloques). Podés asignarla en la revisión."
                        unrecognizedLines += UnrecognizedLine(lineNumber + 1, line, reason)
                        warnings += "Línea ${lineNumber + 1}: '$resolvedName' tiene $reps reps al fallo pero falta definir la carga."
                        return@forEachIndexed
                    }
                }

                val normalizedLine = normalize(line)
                val isBuenosDias = normalizedLine == "buenos dias"
                val reason = if (isBuenosDias) {
                    "'Buenos días' no tiene carga ni series. Si es el ejercicio, especificá el peso (ej. 'Buenos días 40kg 10'). Si es para el asistente, usá 'IrisX: <mensaje>'."
                } else {
                    "No se reconoció carga (kg, lb, bloques, placas, peso corporal) ni series."
                }
                val warningMsg = if (isBuenosDias) {
                    "Línea ${lineNumber + 1}: 'Buenos días' no tiene carga ni series. Si es para el asistente IrisX, usá 'IrisX: <mensaje>'."
                } else {
                    "Línea ${lineNumber + 1}: no se reconoció un peso con unidad válida para '$line'."
                }
                unrecognizedLines += UnrecognizedLine(lineNumber + 1, line, reason)
                warnings += warningMsg
                return@forEachIndexed
            }

            // 5. Separate exercise name & planned target from execution values
            val prefix = line.substring(0, firstLoad.range.first).trim()
            val values = line.substring(firstLoad.range.first)

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
                exerciseNameRaw = prefix.removeRange(planMatch.range)
            }

            val cleanName = exerciseNameRaw.trim(' ', ':', '-', '–', ',')
            if (cleanName.isBlank()) {
                unrecognizedLines += UnrecognizedLine(lineNumber + 1, line, "Falta el nombre del ejercicio previo a la carga.")
                warnings += "Línea ${lineNumber + 1}: falta el nombre del ejercicio."
                return@forEachIndexed
            }

            // Detect modifiers
            val isDumbbell = dumbbellRegex.containsMatchIn(line)
            val isAssistedLine = assistedRegex.containsMatchIn(line)
            val isUnilateralLine = unilateralRegex.containsMatchIn(line)
            val isWeightedBwLine = weightedBwRegex.containsMatchIn(line)
            val generalFailureMatch = failureNoteRegex.find(line)

            // Resolve aliases & canonical name
            val resolvedName = aliasIndex[normalize(cleanName)] ?: cleanName
            val isKnown = allKnown.contains(normalize(resolvedName))
            val isNewExercise = !isKnown

            // Collect load matches in values
            val loadMatches = mutableListOf<Triple<IntRange, Double, String>>()
            for (m in loadWithUnitRegex.findAll(values)) {
                val rawClean = m.groups[1]?.value?.replace(',', '.').orEmpty()
                val isNegative = rawClean.startsWith("-")
                val isPositivePlus = rawClean.startsWith("+")
                val absVal = rawClean.removePrefix("+").removePrefix("-").toDoubleOrNull() ?: 0.0
                val rawUnit = m.groups[2]?.value?.lowercase(Locale.ROOT).orEmpty()
                val canonicalUnit = UnitConverter.canonicalUnit(m.groups[2]?.value)
                val signedVal = if (isNegative) -absVal else absVal
                loadMatches.add(Triple(m.range, signedVal, canonicalUnit))
            }
            for (m in bodyweightRegex.findAll(values)) {
                loadMatches.add(Triple(m.range, 0.0, "BW"))
            }
            loadMatches.sortBy { it.first.first }
            val loadRanges = loadMatches.map { it.first }

            // Range validation: flag invalid loads
            val outOfRangeLoad = loadMatches.firstOrNull { (_, v, u) ->
                !UnitConverter.isValidLoad(kotlin.math.abs(v), u)
            }
            if (outOfRangeLoad != null) {
                unrecognizedLines += UnrecognizedLine(lineNumber + 1, line, "Carga fuera de rango (${outOfRangeLoad.second} ${outOfRangeLoad.third}).")
                warnings += "Línea ${lineNumber + 1}: carga fuera de rango para '$cleanName'."
                return@forEachIndexed
            }

            val allSetsToFailure = line.contains(Regex("\\btodas? (?:las series )?al fallo\\b", RegexOption.IGNORE_CASE))

            // Collect rep matches in values (excluding ranges occupied by load tokens)
            data class RepMatch(val range: IntRange, val reps: Int, val isFailure: Boolean)
            val repMatches = mutableListOf<RepMatch>()

            for (m in repTokenRegex.findAll(values)) {
                if (loadRanges.any { lr -> m.range.first in lr || m.range.last in lr }) continue

                val group1 = m.groups[1]?.value
                val group2 = m.groups[2]?.value
                val group3 = m.groups[3]?.value

                val reps = (group1 ?: group2 ?: group3)?.toIntOrNull()
                if (reps != null && reps in 1..99) {
                    val isFail = group1 != null || group2 != null
                    repMatches.add(RepMatch(m.range, reps, isFail))
                }
            }
            repMatches.sortBy { it.range.first }

            val allTokens = mutableListOf<ValueTokenItem>()
            loadMatches.forEach { allTokens.add(LoadItem(it.first.first, it.second, it.third)) }
            repMatches.forEach { allTokens.add(RepItem(it.range.first, it.reps, it.isFailure)) }
            allTokens.sortBy { it.start }

            var currentRawLoad = 0.0
            var currentUnit = "KG"
            var currentAssisted = isAssistedLine
            var currentWeightedBw = isWeightedBwLine
            val sets = mutableListOf<ParsedSet>()

            for (token in allTokens) {
                when (token) {
                    is LoadItem -> {
                        val isNegative = token.value < 0.0
                        val absVal = kotlin.math.abs(token.value)
                        currentRawLoad = absVal
                        currentUnit = token.unit
                        currentAssisted = isAssistedLine || isNegative
                        currentWeightedBw = isWeightedBwLine || (token.value > 0.0 && token.unit == "KG" && values.contains("+"))
                    }
                    is RepItem -> {
                        val weightGrams = UnitConverter.toGrams(currentRawLoad, currentUnit)
                        val setFailure = token.isFailure || allSetsToFailure
                        val noteTags = buildList {
                            if (isUnilateralLine) add("[UNILATERAL]")
                            if (currentWeightedBw && !currentAssisted && currentUnit != "BW") add("[LASTRE]")
                            if (setFailure) add("[FALLO]")
                        }.joinToString(" ")

                        sets += ParsedSet(
                            weightGrams = weightGrams,
                            repetitions = token.reps,
                            isAssisted = currentAssisted,
                            isDumbbell = isDumbbell,
                            isToFailure = setFailure,
                            loadUnit = currentUnit,
                            rawLoadValue = currentRawLoad,
                            note = noteTags
                        )
                    }
                }
            }

            if (sets.isEmpty()) {
                unrecognizedLines += UnrecognizedLine(lineNumber + 1, line, "Se identificó '$resolvedName' pero no se detectaron repeticiones válidas.")
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

        return ParsePreview(sessionTitle, exercises, warnings, assistantCommands, unrecognizedLines, raw)
    }
}
