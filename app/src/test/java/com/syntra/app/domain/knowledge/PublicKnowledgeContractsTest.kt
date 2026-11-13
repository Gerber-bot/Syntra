package com.syntra.app.domain.knowledge

import org.junit.Assert.*
import org.junit.Test

class PublicKnowledgeContractsTest {

    @Test
    fun publicKnowledgeContracts_preservePrivacyAndProvenance() {
        val provenance = KnowledgeProvenance(
            sourceName = "ExRx Anatomy & Kinesiology",
            sourceUrl = "https://exrx.net/WeightExercises/Quadriceps/BBSquat",
            isVerifiedPublicFact = true
        )
        val fact = ExercisePublicFact(
            exerciseName = "Sentadilla trasera",
            primaryMuscles = listOf("Cuádriceps", "Glúteo mayor"),
            mechanics = "Compuesto",
            tips = listOf("Mantener pies firmes", "Profundidad paralela"),
            provenance = provenance
        )

        assertEquals("Sentadilla trasera", fact.exerciseName)
        assertEquals(2, fact.primaryMuscles.size)
        assertEquals("ExRx Anatomy & Kinesiology", fact.provenance.sourceName)
        assertTrue(fact.provenance.isVerifiedPublicFact)

        val successResult = PublicKnowledgeResult.Success(fact)
        assertTrue(successResult is PublicKnowledgeResult)

        val notFound = PublicKnowledgeResult.NotFound("Ejercicio desconocido")
        assertEquals("Ejercicio desconocido", notFound.query)

        val offline = PublicKnowledgeResult.OfflineFallback("Sin conexión disponible. Se empleará el catálogo local.")
        assertTrue(offline.message.contains("Sin conexión"))
    }
}
