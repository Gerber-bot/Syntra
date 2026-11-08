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
    fun toCompactListConsolidatesMultipleLinesOfSameExerciseIntoOneCardWithMultipleRows() {
        // Exercise entered across multiple lines (e.g. Sentadilla with different weights)
        val parsedExercises = listOf(
            ParsedExercise("Sentadilla", listOf(
                ParsedSet(70000, 12),
                ParsedSet(70000, 10),
                ParsedSet(70000, 8)
            )),
            ParsedExercise("Sentadilla", listOf(
                ParsedSet(65000, 8)
            )),
            ParsedExercise("Buenos días", listOf(
                ParsedSet(40000, 12),
                ParsedSet(40000, 10),
                ParsedSet(40000, 10)
            ))
        )

        val compactList = WorkoutPresentation.toCompactList(parsedExercises)

        // Exactly 2 cards: one for Sentadilla, one for Buenos días
        assertEquals(2, compactList.size)

        // Sentadilla card: 1 card, 2 rows (3x70kg and 1x65kg)
        val sentadilla = compactList[0]
        assertEquals("Sentadilla", sentadilla.name)
        assertEquals(2, sentadilla.groups.size)
        assertEquals(4, sentadilla.totalSeriesCount)

        assertEquals(3, sentadilla.groups[0].setCount)
        assertEquals("70 kg", sentadilla.groups[0].formattedWeight())
        assertEquals("12, 10, 8", sentadilla.groups[0].formattedReps())

        assertEquals(1, sentadilla.groups[1].setCount)
        assertEquals("65 kg", sentadilla.groups[1].formattedWeight())
        assertEquals("8", sentadilla.groups[1].formattedReps())

        // Buenos días card: 1 card, 1 row (3x40kg)
        val buenosDias = compactList[1]
        assertEquals("Buenos días", buenosDias.name)
        assertEquals(1, buenosDias.groups.size)
        assertEquals(3, buenosDias.totalSeriesCount)
        assertEquals(3, buenosDias.groups[0].setCount)
        assertEquals("40 kg", buenosDias.groups[0].formattedWeight())
        assertEquals("12, 10, 10", buenosDias.groups[0].formattedReps())
    }

    @Test
    fun toCompactListKeepsNonConsecutiveWeightsOfSameExerciseInSeparateRows() {
        val parsedExercises = listOf(
            ParsedExercise("Sentadilla", listOf(ParsedSet(70000, 10))),
            ParsedExercise("Sentadilla", listOf(ParsedSet(65000, 8))),
            ParsedExercise("Sentadilla", listOf(ParsedSet(70000, 6)))
        )

        val compactList = WorkoutPresentation.toCompactList(parsedExercises)

        assertEquals(1, compactList.size)
        val sentadilla = compactList[0]
        assertEquals("Sentadilla", sentadilla.name)
        // 3 separate rows inside the single card
        assertEquals(3, sentadilla.groups.size)
        assertEquals(3, sentadilla.totalSeriesCount)

        assertEquals("70 kg", sentadilla.groups[0].formattedWeight())
        assertEquals("10", sentadilla.groups[0].formattedReps())

        assertEquals("65 kg", sentadilla.groups[1].formattedWeight())
        assertEquals("8", sentadilla.groups[1].formattedReps())

        assertEquals("70 kg", sentadilla.groups[2].formattedWeight())
        assertEquals("6", sentadilla.groups[2].formattedReps())
    }
}
