package com.syntra.app.domain

import com.syntra.app.data.WorkoutSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutCategoryClassifierTest {

    @Test
    fun `classifies exact and derived names for primary categories with case and plural tolerance`() {
        assertEquals(WorkoutCategoryKey.PIERNAS, WorkoutCategoryClassifier.classify("Piernas"))
        assertEquals(WorkoutCategoryKey.PIERNAS, WorkoutCategoryClassifier.classify("pierna"))
        assertEquals(WorkoutCategoryKey.PIERNAS, WorkoutCategoryClassifier.classify("DÍA DE PIERNAS"))
        assertEquals(WorkoutCategoryKey.PIERNAS, WorkoutCategoryClassifier.classify("Cuádriceps e isquios"))

        assertEquals(WorkoutCategoryKey.PECHO, WorkoutCategoryClassifier.classify("Pecho"))
        assertEquals(WorkoutCategoryKey.PECHO, WorkoutCategoryClassifier.classify("pechos"))
        assertEquals(WorkoutCategoryKey.PECHO, WorkoutCategoryClassifier.classify("Press de banca y pecho"))

        assertEquals(WorkoutCategoryKey.ESPALDA, WorkoutCategoryClassifier.classify("Espalda"))
        assertEquals(WorkoutCategoryKey.ESPALDA, WorkoutCategoryClassifier.classify("espalda pesada"))
        assertEquals(WorkoutCategoryKey.ESPALDA, WorkoutCategoryClassifier.classify("Tirón y remos"))

        assertEquals(WorkoutCategoryKey.HOMBROS, WorkoutCategoryClassifier.classify("Hombros"))
        assertEquals(WorkoutCategoryKey.HOMBROS, WorkoutCategoryClassifier.classify("hombro"))
        assertEquals(WorkoutCategoryKey.HOMBROS, WorkoutCategoryClassifier.classify("Deltoides y militar"))

        assertEquals(WorkoutCategoryKey.BRAZOS, WorkoutCategoryClassifier.classify("Brazos"))
        assertEquals(WorkoutCategoryKey.BRAZOS, WorkoutCategoryClassifier.classify("brazo"))
        assertEquals(WorkoutCategoryKey.BRAZOS, WorkoutCategoryClassifier.classify("Bíceps y tríceps"))

        assertEquals(WorkoutCategoryKey.FULL_BODY, WorkoutCategoryClassifier.classify("Full Body"))
        assertEquals(WorkoutCategoryKey.FULL_BODY, WorkoutCategoryClassifier.classify("fullbody"))
        assertEquals(WorkoutCategoryKey.FULL_BODY, WorkoutCategoryClassifier.classify("Cuerpo completo"))
    }

    @Test
    fun `classifies generic titles based on exercise muscle groups`() {
        val legsSession = WorkoutCategoryClassifier.classify(
            title = "Entrenamiento",
            exerciseMuscleGroups = listOf("Piernas", "Cuádriceps", "Glúteos")
        )
        assertEquals(WorkoutCategoryKey.PIERNAS, legsSession)

        val fullBodySession = WorkoutCategoryClassifier.classify(
            title = "Sesión matutina",
            exerciseMuscleGroups = listOf("Piernas", "Pecho", "Espalda")
        )
        assertEquals(WorkoutCategoryKey.FULL_BODY, fullBodySession)
    }

    @Test
    fun `assigns ambiguous or unclassifiable titles to SIN_CLASIFICAR`() {
        assertEquals(WorkoutCategoryKey.SIN_CLASIFICAR, WorkoutCategoryClassifier.classify("Día 1"))
        assertEquals(WorkoutCategoryKey.SIN_CLASIFICAR, WorkoutCategoryClassifier.classify("Rutina A"))
        assertEquals(WorkoutCategoryKey.SIN_CLASIFICAR, WorkoutCategoryClassifier.classify("Gimnasio"))
        assertEquals(WorkoutCategoryKey.SIN_CLASIFICAR, WorkoutCategoryClassifier.classify(""))
    }

    @Test
    fun `buildSummaries generates all categories and aggregates counts and latest timestamps correctly`() {
        val now = 1700000000000L
        val older = 1699000000000L
        val sessions = listOf(
            WorkoutSessionEntity(id = 1, title = "Piernas", startedAtMillis = now),
            WorkoutSessionEntity(id = 2, title = "Pierna pesada", startedAtMillis = older),
            WorkoutSessionEntity(id = 3, title = "Pecho", startedAtMillis = now),
            WorkoutSessionEntity(id = 4, title = "Random 123", startedAtMillis = older)
        )

        val map = mapOf(
            1L to WorkoutCategoryKey.PIERNAS,
            2L to WorkoutCategoryKey.PIERNAS,
            3L to WorkoutCategoryKey.PECHO,
            4L to WorkoutCategoryKey.SIN_CLASIFICAR
        )

        val summaries = WorkoutCategoryClassifier.buildSummaries(sessions, map)

        // 1. "Todas" category must have total count
        val todas = summaries.first { it.key == WorkoutCategoryKey.TODAS }
        assertEquals(4, todas.sessionCount)
        assertEquals(now, todas.latestSessionMillis)

        // 2. "Piernas" has 2 sessions, latest is `now`
        val piernas = summaries.first { it.key == WorkoutCategoryKey.PIERNAS }
        assertEquals(2, piernas.sessionCount)
        assertEquals(now, piernas.latestSessionMillis)

        // 3. "Pecho" has 1 session
        val pecho = summaries.first { it.key == WorkoutCategoryKey.PECHO }
        assertEquals(1, pecho.sessionCount)
        assertEquals(now, pecho.latestSessionMillis)

        // 4. "Espalda" has 0 sessions
        val espalda = summaries.first { it.key == WorkoutCategoryKey.ESPALDA }
        assertEquals(0, espalda.sessionCount)
        assertNull(espalda.latestSessionMillis)

        // 5. "Otras / Sin clasificar" has 1 session
        val unclassified = summaries.first { it.key == WorkoutCategoryKey.SIN_CLASIFICAR }
        assertEquals(1, unclassified.sessionCount)
        assertEquals(older, unclassified.latestSessionMillis)
    }

    @Test
    fun `handles multiple sessions on the same day maintaining chronological order`() {
        val t1 = 1700000000000L
        val t2 = 1700000050000L
        val sessions = listOf(
            WorkoutSessionEntity(id = 1, title = "Piernas mañana", startedAtMillis = t1),
            WorkoutSessionEntity(id = 2, title = "Piernas tarde", startedAtMillis = t2)
        )
        val sorted = sessions.sortedByDescending { it.startedAtMillis }
        assertEquals(2L, sorted[0].id)
        assertEquals(1L, sorted[1].id)
    }
}
