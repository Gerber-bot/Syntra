package com.syntra.app.domain

import org.junit.Assert.*
import org.junit.Test

class WorkoutTextParserTest {

    @Test
    fun parsesRepeatedRepsWithOneWeight() {
        val preview = WorkoutTextParser.parse("Piernas\nSentadilla 80kg 8 8 7")
        assertEquals("Piernas", preview.sessionTitle)
        assertEquals(1, preview.exercises.size)
        assertEquals("Sentadilla", preview.exercises[0].name)
        assertEquals(listOf(8, 8, 7), preview.exercises[0].sets.map { it.repetitions })
        assertEquals(80000, preview.exercises[0].sets.first().weightGrams)
        assertEquals(3, preview.seriesCount)
        assertTrue(preview.warnings.isEmpty())
    }

    @Test
    fun parsesFullMultiExerciseWorkoutSession() {
        val input = "Piernas\nSentadilla 80kg 8 8 7\nBuenos días 40kg 12 10 10"
        val preview = WorkoutTextParser.parse(input)
        assertEquals("Piernas", preview.sessionTitle)
        assertEquals(2, preview.exercises.size)

        val sentadilla = preview.exercises[0]
        assertEquals("Sentadilla", sentadilla.name)
        assertEquals(3, sentadilla.sets.size)
        assertEquals(listOf(8, 8, 7), sentadilla.sets.map { it.repetitions })
        assertEquals(80000, sentadilla.sets[0].weightGrams)

        val buenosDias = preview.exercises[1]
        assertEquals("Buenos días", buenosDias.name)
        assertEquals(3, buenosDias.sets.size)
        assertEquals(listOf(12, 10, 10), buenosDias.sets.map { it.repetitions })
        assertEquals(40000, buenosDias.sets[0].weightGrams)

        assertEquals(6, preview.seriesCount)
        assertTrue(preview.warnings.isEmpty())
    }

    @Test
    fun distinguishesBuenosDiasExerciseFromAssistantGreetings() {
        // Exercise with loads and reps is parsed purely as an exercise
        val previewExercise = WorkoutTextParser.parse("Buenos días 40kg 12 10 10")
        assertEquals(1, previewExercise.exercises.size)
        assertEquals("Buenos días", previewExercise.exercises.first().name)
        assertEquals(40000, previewExercise.exercises.first().sets.first().weightGrams)
        assertTrue(previewExercise.warnings.isEmpty())
        assertTrue(previewExercise.assistantCommands.isEmpty())

        // Ambiguous standalone phrase without exercise structure triggers disambiguation warning
        val previewGreeting = WorkoutTextParser.parse("Buenos días")
        assertTrue(previewGreeting.exercises.isEmpty())
        assertEquals(1, previewGreeting.warnings.size)
        assertTrue(previewGreeting.warnings.first().contains("IrisX"))
    }

    @Test
    fun recognizesAndSeparatesIrisXAssistantDirectives() {
        val input = "Piernas\nIrisX: recordar registrar pesaje mañana\nSentadilla 80kg 8 8 7\n@irisx mostrar historial"
        val preview = WorkoutTextParser.parse(input)

        assertEquals("Piernas", preview.sessionTitle)
        assertEquals(1, preview.exercises.size)
        assertEquals("Sentadilla", preview.exercises.first().name)
        assertEquals(2, preview.assistantCommands.size)
        assertEquals("recordar registrar pesaje mañana", preview.assistantCommands[0].command)
        assertEquals("mostrar historial", preview.assistantCommands[1].command)
        assertTrue(preview.warnings.isEmpty())
    }

    @Test
    fun parsesWeightChangesOnSameLine() {
        val preview = WorkoutTextParser.parse("Sentadilla 85kg 8. 90kg 7")
        assertEquals(1, preview.exercises.size)
        assertEquals(listOf(85000, 90000), preview.exercises.first().sets.map { it.weightGrams })
        assertEquals(listOf(8, 7), preview.exercises.first().sets.map { it.repetitions })
        assertTrue(preview.warnings.isEmpty())
    }

    @Test
    fun resolvesConfirmedAlias() {
        val preview = WorkoutTextParser.parse("RDL 110kg 6 5", mapOf("RDL" to "Peso muerto rumano"))
        assertEquals("Peso muerto rumano", preview.exercises.first().name)
        assertEquals(listOf(6, 5), preview.exercises.first().sets.map { it.repetitions })
        assertEquals(110000, preview.exercises.first().sets.first().weightGrams)
    }

    @Test
    fun resolvesAliasesCaseAndAccentInsensitive() {
        val aliases = mapOf("buenos dias" to "Buenos días con barra")
        val preview = WorkoutTextParser.parse("Buenos Días 40kg 10 10", aliases)
        assertEquals("Buenos días con barra", preview.exercises.first().name)
    }

    @Test
    fun doesNotInventDataForUnknownInput() {
        val preview = WorkoutTextParser.parse("Hoy entrené bien")
        assertTrue(preview.exercises.isEmpty())
        assertTrue(preview.warnings.isNotEmpty())
        assertTrue(preview.warnings.any { it.contains("no se reconoció un peso") })
    }

    @Test
    fun supportsDecimalCommas() {
        val preview = WorkoutTextParser.parse("Laterales 7,5kg 12 11")
        assertEquals(1, preview.exercises.size)
        assertEquals("Laterales", preview.exercises.first().name)
        assertEquals(7500, preview.exercises.first().sets.first().weightGrams)
        assertEquals(listOf(12, 11), preview.exercises.first().sets.map { it.repetitions })
        assertTrue(preview.warnings.isEmpty())
    }

    @Test
    fun warnsWhenMissingExerciseName() {
        val preview = WorkoutTextParser.parse("80kg 8 8 7")
        assertTrue(preview.exercises.isEmpty())
        assertTrue(preview.warnings.any { it.contains("falta el nombre del ejercicio") })
    }

    @Test
    fun warnsWhenNoValidSetsFound() {
        val preview = WorkoutTextParser.parse("Sentadilla 80kg")
        assertTrue(preview.exercises.isEmpty())
        assertTrue(preview.warnings.any { it.contains("no se encontraron series válidas") })
    }

    @Test
    fun rejectsOutOfRangeWeight() {
        val previewOver = WorkoutTextParser.parse("Sentadilla 1500kg 5")
        assertTrue(previewOver.exercises.isEmpty())
        assertTrue(previewOver.warnings.any { it.contains("carga fuera de rango") })

        val previewZero = WorkoutTextParser.parse("Sentadilla 0kg 5")
        assertTrue(previewZero.exercises.isEmpty())
        assertTrue(previewZero.warnings.any { it.contains("carga fuera de rango") })
    }

    @Test
    fun handlesEmptyLinesAndPunctuation() {
        val input = "\n\nPiernas:\n\nSentadilla 80kg 8 8 7;\n\n"
        val preview = WorkoutTextParser.parse(input)
        assertEquals("Piernas", preview.sessionTitle)
        assertEquals(1, preview.exercises.size)
        assertEquals(3, preview.seriesCount)
    }

    // InputValidators Tests

    @Test
    fun validatesWeightGramsParsing() {
        assertEquals(70000, InputValidators.parseWeightGrams("70"))
        assertEquals(70500, InputValidators.parseWeightGrams("70.5"))
        assertEquals(70500, InputValidators.parseWeightGrams("70,5"))
        assertEquals(82350, InputValidators.parseWeightGrams(" 82,35 "))

        // Boundary values (20kg to 400kg)
        assertEquals(20000, InputValidators.parseWeightGrams("20.0"))
        assertEquals(400000, InputValidators.parseWeightGrams("400.0"))

        // Out of range & invalid formats
        assertNull(InputValidators.parseWeightGrams("19.9"))
        assertNull(InputValidators.parseWeightGrams("400.1"))
        assertNull(InputValidators.parseWeightGrams("0"))
        assertNull(InputValidators.parseWeightGrams("-5"))
        assertNull(InputValidators.parseWeightGrams("abc"))
        assertNull(InputValidators.parseWeightGrams(""))
    }

    @Test
    fun formatsWeightGramsCorrectly() {
        assertEquals("80.00", InputValidators.formatWeightKg(80000))
        assertEquals("7.50", InputValidators.formatWeightKg(7500))
        assertEquals("72.35", InputValidators.formatWeightKg(72350))
    }

    @Test
    fun validatesProfileInputsCorrectly() {
        // Valid profile
        val validResult = InputValidators.validateProfile("Federico", "Fede", "178", 75000)
        assertNull(validResult)

        // Invalid cases
        assertNotNull(InputValidators.validateProfile("", "Fede", "178", 75000))
        assertNotNull(InputValidators.validateProfile("Federico", " ", "178", 75000))
        assertNotNull(InputValidators.validateProfile("Federico", "Fede", "99", 75000)) // < 100 cm
        assertNotNull(InputValidators.validateProfile("Federico", "Fede", "251", 75000)) // > 250 cm
        assertNotNull(InputValidators.validateProfile("Federico", "Fede", "abc", 75000))
        assertNotNull(InputValidators.validateProfile("Federico", "Fede", "178", null))
    }
}
