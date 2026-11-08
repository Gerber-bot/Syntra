package com.syntra.app.domain

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/** Deterministic and conservative parser: never writes directly to the database. */
data class ParsedSet(val weightGrams: Int, val repetitions: Int)
data class ParsedExercise(val name: String, val sets: List<ParsedSet>)
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
    private val kg = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*kg\\b")
    private val token = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*kg\\b|\\b(\\d{1,2})\\b")
    private val irisxCommandPrefix = Regex("^(?i)(?:irisx|@irisx)[:,-]?\\s*(.+)$")
    private val knownTitles = setOf("piernas", "pecho", "espalda", "hombros", "brazos", "full body", "torso", "empuje", "tiron", "push", "pull")

    fun normalize(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\s+"), " ")

    fun parse(raw: String, aliases: Map<String, String> = emptyMap()): ParsePreview {
        var sessionTitle: String? = null
        val exercises = mutableListOf<ParsedExercise>()
        val warnings = mutableListOf<String>()
        val assistantCommands = mutableListOf<IrisXDirective>()
        val aliasIndex = aliases.entries.associate { normalize(it.key) to it.value }

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

            // 2. Detect session titles
            if (normalize(line.removeSuffix(":")) in knownTitles && sessionTitle == null) {
                sessionTitle = line.removeSuffix(":")
                return@forEachIndexed
            }

            // 3. Disambiguate everyday phrases (e.g. "buenos días") when missing exercise weight
            val first = kg.find(line)
            if (first == null) {
                val normalizedLine = normalize(line)
                if (normalizedLine == "buenos dias") {
                    warnings += "Línea ${lineNumber + 1}: 'Buenos días' no tiene carga ni series. Si es el ejercicio, especificá el peso (ej. 'Buenos días 40kg 10'). Si es para el asistente, usá 'IrisX: <mensaje>'."
                } else {
                    warnings += "Línea ${lineNumber + 1}: no se reconoció un peso con unidad kg."
                }
                return@forEachIndexed
            }

            // 4. Parse exercise name and sets
            val name = line.substring(0, first.range.first).trim(' ', ':', '-', '–', ',')
            if (name.isBlank()) {
                warnings += "Línea ${lineNumber + 1}: falta el nombre del ejercicio."
                return@forEachIndexed
            }
            val values = line.substring(first.range.first)
            var weight: Int? = null
            val sets = mutableListOf<ParsedSet>()
            for (match in token.findAll(values)) {
                val kilos = match.groups[1]?.value
                if (kilos != null) {
                    val parsed = kilos.replace(',', '.').toDoubleOrNull()
                    if (parsed == null || parsed <= 0 || parsed > 1000) {
                        warnings += "Línea ${lineNumber + 1}: carga fuera de rango."
                        weight = null
                    } else weight = (parsed * 1000).roundToInt()
                } else {
                    val reps = match.groups[2]?.value?.toIntOrNull()
                    if (weight != null && reps != null && reps in 1..99) sets += ParsedSet(weight, reps)
                }
            }
            if (sets.isEmpty()) {
                warnings += "Línea ${lineNumber + 1}: no se encontraron series válidas."
            } else {
                exercises += ParsedExercise(aliasIndex[normalize(name)] ?: name, sets)
            }
        }
        return ParsePreview(sessionTitle, exercises, warnings, assistantCommands)
    }
}
