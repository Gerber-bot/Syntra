package com.syntra.app.domain.irisx.tools

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.domain.ExerciseCatalog
import com.syntra.app.domain.irisx.IrisXAnalytics

internal sealed interface ExerciseResolution {
    data class Resolved(val id: Long, val displayName: String) : ExerciseResolution
    data object NotFound : ExerciseResolution
    data class Ambiguous(val candidates: List<String>) : ExerciseResolution
}

/**
 * Resolves a user-provided exercise name to exactly one stored exercise.
 *
 * Rules, in order:
 * 1. A learned alias maps the normalized input to its canonical name.
 * 2. The canonical (or raw) name must match an exercise `displayName`/`normalizedName` exactly.
 * 3. Anything else is [ExerciseResolution.NotFound].
 *
 * There is intentionally no fuzzy matching: "press banca inclinado" must never resolve to
 * "press de banca". If two rows end up with the same normalized name the result is ambiguous
 * and the caller must ask for a precise exercise instead of guessing.
 */
internal object ExerciseNameResolver {

    fun resolve(
        rawName: String,
        exercises: List<ExerciseEntity>,
        learnedAliases: Map<String, String>
    ): ExerciseResolution {
        val normalized = IrisXAnalytics.normalize(rawName)
        if (normalized.isBlank()) return ExerciseResolution.NotFound

        val canonical = learnedAliases[normalized]?.let { IrisXAnalytics.normalize(it) }
        // Exact catalog alias ("press banca" -> "Press de banca"). Still not fuzzy: the whole
        // normalized input must equal a catalog name or one of its declared aliases.
        val catalogMatch = ExerciseCatalog.DEFAULT_CATALOG.firstOrNull { entry ->
            IrisXAnalytics.normalize(entry.displayName) == normalized ||
                entry.normalizedName == normalized ||
                entry.aliases.any { IrisXAnalytics.normalize(it) == normalized }
        }
        val acceptedNames = buildSet {
            add(normalized)
            if (!canonical.isNullOrBlank()) add(canonical)
            if (catalogMatch != null) {
                add(IrisXAnalytics.normalize(catalogMatch.displayName))
                add(catalogMatch.normalizedName)
            }
        }

        val matches = exercises.filter { exercise ->
            IrisXAnalytics.normalize(exercise.displayName) in acceptedNames ||
                exercise.normalizedName in acceptedNames
        }

        return when {
            matches.isEmpty() -> ExerciseResolution.NotFound
            matches.size > 1 -> ExerciseResolution.Ambiguous(matches.map { it.displayName })
            else -> ExerciseResolution.Resolved(matches.single().id, matches.single().displayName)
        }
    }
}
