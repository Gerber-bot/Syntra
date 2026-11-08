package com.syntra.app.domain.irisx

import com.syntra.app.domain.WorkoutCategoryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrisXClassifierTest {

    @Test
    fun `audit session with gym vernacular is classified as Pecho, Empuje and has secondary categories`() {
        // Exact session reported in audit:
        // Title: "Entrenamiento"
        // Exercises:
        // - Press banco plano (Pecho)
        // - Inclinado con mancuernas (Pecho)
        // - Inclinado Hammer (Pecho)
        // - Cruce de polea (Pecho)
        // - Vuelo lateral (Hombros)
        // - A la frente con W (Tríceps)
        // - Tríceps con triángulo (Tríceps)
        val exerciseNames = listOf(
            "Press banco plano",
            "Inclinado con mancuernas",
            "Inclinado Hammer",
            "Cruce de polea",
            "Vuelo lateral",
            "A la frente con W",
            "Tríceps con triángulo"
        )

        val result = IrisXClassifier.classifyWorkout("Entrenamiento", exerciseNames)

        assertEquals("Primary category should be PECHO", WorkoutCategoryKey.PECHO, result.primaryCategory)
        assertTrue("Secondary categories should contain BRAZOS", result.secondaryCategories.contains(WorkoutCategoryKey.BRAZOS))
        assertTrue("Secondary categories should contain HOMBROS", result.secondaryCategories.contains(WorkoutCategoryKey.HOMBROS))
        assertEquals("Session type should be Empuje (Push)", "Empuje (Push)", result.sessionType)
        assertFalse("Should not be ambiguous", result.isAmbiguous)
        assertTrue("Reasoning should mention Empuje", result.reasoning.contains("Empuje (Push)"))
        assertEquals(4, result.muscleBreakdown["Pecho"])
        assertEquals(2, result.muscleBreakdown["Tríceps"])
        assertEquals(1, result.muscleBreakdown["Hombros"])
    }

    @Test
    fun `pull session is classified as Espalda and Tiron`() {
        val exerciseNames = listOf(
            "Dominadas",
            "Remo con barra",
            "Jalón al pecho",
            "Curl de bíceps",
            "Curl martillo"
        )

        val result = IrisXClassifier.classifyWorkout("Gimnasio", exerciseNames)

        assertEquals(WorkoutCategoryKey.ESPALDA, result.primaryCategory)
        assertTrue(result.secondaryCategories.contains(WorkoutCategoryKey.BRAZOS))
        assertEquals("Tirón (Pull)", result.sessionType)
    }

    @Test
    fun `legs session with camilla adelante and bulgaras is classified as Piernas`() {
        val exerciseNames = listOf(
            "Sentadilla",
            "Prensa de piernas",
            "Camilla adelante",
            "Búlgaras",
            "Gemelos en máquina"
        )

        val result = IrisXClassifier.classifyWorkout("Entreno de hoy", exerciseNames)

        assertEquals(WorkoutCategoryKey.PIERNAS, result.primaryCategory)
        assertEquals("Piernas (Legs)", result.sessionType)
    }

    @Test
    fun `learned alias is respected by classifier`() {
        // Unknown custom exercise "Mi super maquina"
        val customName = "Mi super maquina"
        val learnedAliases = mapOf("mi super maquina" to "Press de banca")

        val result = IrisXClassifier.classifyWorkout(
            sessionTitle = "Sesión rápida",
            exerciseNames = listOf(customName),
            learnedAliases = learnedAliases
        )

        assertEquals(WorkoutCategoryKey.PECHO, result.primaryCategory)
    }
}
