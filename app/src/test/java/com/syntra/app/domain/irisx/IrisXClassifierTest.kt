package com.syntra.app.domain.irisx

import com.syntra.app.domain.WorkoutCategoryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrisXClassifierTest {

    @Test
    fun `audit session with gym vernacular is classified as Pecho, Empuje and has secondary categories`() {
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
    fun `chest specific session is classified as Pecho`() {
        val exerciseNames = listOf(
            "Press de banca",
            "Press inclinado con mancuernas",
            "Aperturas con mancuernas",
            "Fondos"
        )

        val result = IrisXClassifier.classifyWorkout("Pecho plano e inclinado", exerciseNames)
        assertEquals(WorkoutCategoryKey.PECHO, result.primaryCategory)
        assertEquals("Empuje (Push)", result.sessionType)
    }

    @Test
    fun `shoulders specific session is classified as Hombros`() {
        val exerciseNames = listOf(
            "Press militar",
            "Elevaciones laterales",
            "Pájaros / Vuelos posteriores",
            "Face pull"
        )

        val result = IrisXClassifier.classifyWorkout("Rutina de deltoides", exerciseNames)
        assertEquals(WorkoutCategoryKey.HOMBROS, result.primaryCategory)
    }

    @Test
    fun `arms session is classified as Brazos`() {
        val exerciseNames = listOf(
            "Curl de bíceps en banco Scott",
            "Curl martillo",
            "Extensiones de tríceps en polea",
            "Press francés / A la frente"
        )

        val result = IrisXClassifier.classifyWorkout("Brazos", exerciseNames)
        assertEquals(WorkoutCategoryKey.BRAZOS, result.primaryCategory)
        assertEquals("Brazos", result.sessionType)
    }

    @Test
    fun `full body session is classified as Full Body`() {
        val exerciseNames = listOf(
            "Sentadilla",
            "Press de banca",
            "Remo con barra",
            "Press militar"
        )

        val result = IrisXClassifier.classifyWorkout("Full Body A", exerciseNames)
        assertEquals(WorkoutCategoryKey.FULL_BODY, result.primaryCategory)
        assertEquals("Full Body", result.sessionType)
    }

    @Test
    fun `mixed or ambiguous session identifies ambiguity and presents proposed category`() {
        // Tie between 1 Leg and 1 Chest exercise
        val exerciseNames = listOf(
            "Sentadilla",
            "Press de banca"
        )

        val result = IrisXClassifier.classifyWorkout("Rutina corta", exerciseNames)
        assertTrue("Equal split should mark isAmbiguous = true", result.isAmbiguous)
        assertEquals("Mixto", result.sessionType)
    }

    @Test
    fun `unknown exercises yield low confidence and unclassified status`() {
        val exerciseNames = listOf(
            "Movimiento experimental alfa",
            "Maniobra desconocida beta"
        )

        val result = IrisXClassifier.classifyWorkout("Entrenamiento X", exerciseNames)
        assertEquals(WorkoutCategoryKey.SIN_CLASIFICAR, result.primaryCategory)
        assertEquals(0.1, result.confidence, 0.05)
    }

    @Test
    fun `learned alias is respected by classifier`() {
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
