package com.entrenadorlocal.app.domain

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Domain validators for user input (profile, weight measurements).
 * Pure Kotlin without Android framework dependencies.
 */
object InputValidators {
    const val MIN_HEIGHT_CM = 100
    const val MAX_HEIGHT_CM = 250
    const val MIN_WEIGHT_KG = 20.0
    const val MAX_WEIGHT_KG = 400.0

    /**
     * Parses weight input string (supporting commas and dots as decimal separators)
     * into weight in grams, validating range between 20kg and 400kg.
     */
    fun parseWeightGrams(input: String): Int? {
        val value = input.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (value in MIN_WEIGHT_KG..MAX_WEIGHT_KG) (value * 1000).roundToInt() else null
    }

    /**
     * Formats weight in grams to string representation in kilograms with 2 decimal places.
     */
    fun formatWeightKg(grams: Int): String {
        return String.format(Locale.US, "%.2f", grams / 1000.0)
    }

    /**
     * Validates profile input parameters. Returns null if valid, or an error message if invalid.
     */
    fun validateProfile(
        name: String,
        preferredName: String,
        heightInput: String,
        weightGrams: Int?
    ): String? {
        val trimmedName = name.trim()
        val trimmedPreferred = preferredName.trim()
        val height = heightInput.trim().toIntOrNull()

        if (trimmedName.isBlank() || trimmedPreferred.isBlank() ||
            height == null || height !in MIN_HEIGHT_CM..MAX_HEIGHT_CM ||
            weightGrams == null) {
            return "Completá nombre, apodo, estatura (100–250 cm) y peso (20–400 kg)."
        }
        return null
    }
}
