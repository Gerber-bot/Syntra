package com.syntra.app.domain

import org.junit.Assert.*
import org.junit.Test

class WorkoutPresentationTest {

    @Test
    fun groupsConsecutiveSetsWithSameLoad() {
        val sets = listOf(
            ParsedSet(weightGrams = 70000, repetitions = 12),
            ParsedSet(weightGrams = 70000, repetitions = 10),
            ParsedSet(weightGrams = 70000, repetitions = 8),
            ParsedSet(weightGrams = 65000, repetitions = 8)
        )
        val groups = WorkoutPresentation.groupConsecutiveSets(sets)

        assertEquals(2, groups.size)

        // First group: 3 sets of 70kg
        assertEquals(3, groups[0].setCount)
        assertEquals("70 kg", groups[0].formattedWeight())
        assertEquals("12, 10, 8", groups[0].formattedReps())
        assertEquals(listOf(12, 10, 8), groups[0].repetitions)

        // Second group: 1 set of 65kg
        assertEquals(1, groups[1].setCount)
        assertEquals("65 kg", groups[1].formattedWeight())
        assertEquals("8", groups[1].formattedReps())
        assertEquals(listOf(8), groups[1].repetitions)
    }

    @Test
    fun preservesSeparatedGroupsWithSameWeightWithoutMerging() {
        // Condition: Do not combine automatically separated groups that happen to have same weight
        val sets = listOf(
            ParsedSet(weightGrams = 70000, repetitions = 10),
            ParsedSet(weightGrams = 65000, repetitions = 8),
            ParsedSet(weightGrams = 70000, repetitions = 6)
        )
        val groups = WorkoutPresentation.groupConsecutiveSets(sets)

        assertEquals(3, groups.size)
        assertEquals(70000, groups[0].weightGrams)
        assertEquals(listOf(10), groups[0].repetitions)

        assertEquals(65000, groups[1].weightGrams)
        assertEquals(listOf(8), groups[1].repetitions)

        assertEquals(70000, groups[2].weightGrams)
        assertEquals(listOf(6), groups[2].repetitions)
    }

    @Test
    fun formatsWeightsWithoutUnnecessaryDecimalsAndWithSpanishComma() {
        assertEquals("70 kg", WorkoutPresentation.formatWeightKg(70000))
        assertEquals("100 kg", WorkoutPresentation.formatWeightKg(100000))
        assertEquals("7,5 kg", WorkoutPresentation.formatWeightKg(7500))
        assertEquals("7,25 kg", WorkoutPresentation.formatWeightKg(7250))
        assertEquals("12,5 kg", WorkoutPresentation.formatWeightKg(12500))
    }

    @Test
    fun handlesSingleSetExercise() {
        val sets = listOf(ParsedSet(weightGrams = 100000, repetitions = 5))
        val groups = WorkoutPresentation.groupConsecutiveSets(sets)

        assertEquals(1, groups.size)
        assertEquals(1, groups[0].setCount)
        assertEquals("100 kg", groups[0].formattedWeight())
        assertEquals("5", groups[0].formattedReps())
    }

    @Test
    fun handlesEmptySetsList() {
        val groups = WorkoutPresentation.groupConsecutiveSets(emptyList())
        assertTrue(groups.isEmpty())
    }

    @Test
    fun toCompactPreservesTotalCountAndStructure() {
        val exercise = ParsedExercise(
            name = "Sentadilla",
            sets = listOf(
                ParsedSet(80000, 8),
                ParsedSet(80000, 8),
                ParsedSet(80000, 7),
                ParsedSet(90000, 5)
            )
        )
        val compact = WorkoutPresentation.toCompact(exercise)

        assertEquals("Sentadilla", compact.name)
        assertEquals(2, compact.groups.size)
        assertEquals(4, compact.totalSeriesCount)
        assertEquals("80 kg", compact.groups[0].formattedWeight())
        assertEquals("8, 8, 7", compact.groups[0].formattedReps())
        assertEquals("90 kg", compact.groups[1].formattedWeight())
        assertEquals("5", compact.groups[1].formattedReps())
    }
}
