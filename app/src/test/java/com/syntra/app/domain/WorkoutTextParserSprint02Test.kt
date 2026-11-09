package com.syntra.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutTextParserSprint02Test {

    @Test
    fun `distinguishes planned target from exercise name and execution sets`() {
        val input = "Remo con barra agarre supino 3 de 10 a 12: 45kg 11, 9. 40kg 10"
        val preview = WorkoutTextParser.parse(input)

        assertEquals(1, preview.exercises.size)
        val exercise = preview.exercises[0]

        // 1. Name must NOT include the planning
        assertEquals("Remo con barra agarre supino", exercise.name)

        // 2. Planning target must be extracted
        assertEquals("3 series de 10–12 repeticiones", exercise.plannedTarget)

        // 3. Execution sets must be correctly parsed
        assertEquals(3, exercise.sets.size)
        assertEquals(45000, exercise.sets[0].weightGrams)
        assertEquals(11, exercise.sets[0].repetitions)
        assertEquals(45000, exercise.sets[1].weightGrams)
        assertEquals(9, exercise.sets[1].repetitions)
        assertEquals(40000, exercise.sets[2].weightGrams)
        assertEquals(10, exercise.sets[2].repetitions)
    }

    @Test
    fun `detects ambiguous expressions like 3 + 6 without inventing arbitrary sets`() {
        val input = "Dominadas 3 + 6"
        val preview = WorkoutTextParser.parse(input)

        // Must not create sets
        assertTrue(preview.exercises.isEmpty())

        // Must emit ambiguity warning
        assertTrue(preview.warnings.any { it.contains("Expresión ambigua detectada ('3 + 6')") })
    }

    @Test
    fun `detects dumbbell modifier per arm`() {
        val input = "Curl de bíceps mancuernas 14kg 10 10"
        val preview = WorkoutTextParser.parse(input)

        assertEquals(1, preview.exercises.size)
        val exercise = preview.exercises[0]
        assertEquals(2, exercise.sets.size)
        assertTrue(exercise.sets[0].isDumbbell)
        assertTrue(exercise.sets[1].isDumbbell)
    }

    @Test
    fun `detects assisted loads`() {
        val input = "Dominadas asistidas 15kg 10 8"
        val preview = WorkoutTextParser.parse(input)

        assertEquals(1, preview.exercises.size)
        val exercise = preview.exercises[0]
        assertTrue(exercise.sets[0].isAssisted)
    }

    @Test
    fun `resolves aliases from IrisX memory or configuration`() {
        val aliases = mapOf("rdl" to "Peso muerto rumano")
        val input = "RDL 70kg 10 10"
        val preview = WorkoutTextParser.parse(input, aliases = aliases)

        assertEquals(1, preview.exercises.size)
        assertEquals("Peso muerto rumano", preview.exercises[0].name)
        assertFalse(preview.exercises[0].isNewExercise)
    }

    @Test
    fun `flags unknown exercises for proposal and inclusion`() {
        val input = "Zancadas búlgaras con déficit 20kg 10 10"
        val preview = WorkoutTextParser.parse(input)

        assertEquals(1, preview.exercises.size)
        val exercise = preview.exercises[0]
        assertTrue(exercise.isNewExercise)
    }

    @Test
    fun `compact representation preserves planning and new exercise flags`() {
        val input = "Remo con barra 3x10: 50kg 10 10 10"
        val preview = WorkoutTextParser.parse(input)
        val compactList = WorkoutPresentation.toCompactList(preview.exercises)

        assertEquals(1, compactList.size)
        val compact = compactList[0]
        assertEquals("Remo con barra", compact.name)
        assertEquals("3 series de 10 repeticiones", compact.plannedTarget)
        assertEquals(1, compact.groups.size)
        assertEquals(3, compact.groups[0].setCount)
        assertEquals("50 kg", compact.groups[0].formattedWeight())
    }
}
