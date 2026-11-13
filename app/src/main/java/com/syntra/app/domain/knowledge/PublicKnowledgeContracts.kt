package com.syntra.app.domain.knowledge

/**
 * Factual provenance metadata for any public knowledge query.
 * Guarantees zero tracking, zero personal data transmission, and zero paid API lock-in.
 */
data class KnowledgeProvenance(
    val sourceName: String,
    val sourceUrl: String? = null,
    val fetchedAtMillis: Long = System.currentTimeMillis(),
    val isVerifiedPublicFact: Boolean = true
)

data class ExercisePublicFact(
    val exerciseName: String,
    val primaryMuscles: List<String>,
    val mechanics: String? = null, // e.g. "Compound", "Isolation"
    val tips: List<String> = emptyList(),
    val provenance: KnowledgeProvenance
)

sealed class PublicKnowledgeResult {
    data class Success(val fact: ExercisePublicFact) : PublicKnowledgeResult()
    data class NotFound(val query: String) : PublicKnowledgeResult()
    data class OfflineFallback(val message: String) : PublicKnowledgeResult()
    data class Error(val message: String) : PublicKnowledgeResult()
}

/**
 * Clean public interface for querying public sports and anatomy facts.
 * Implementations strictly transmit only public canonical query strings (e.g. "bench press"),
 * never sending athlete weights, routine logs, personal notes, or device identifiers.
 */
interface PublicKnowledgeProvider {
    suspend fun lookupExerciseFact(canonicalExerciseName: String): PublicKnowledgeResult
}
