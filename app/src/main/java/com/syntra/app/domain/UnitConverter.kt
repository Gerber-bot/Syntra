package com.syntra.app.domain

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Centralized, authoritative unit conversion and formatting for athletic loads in Syntra.
 * Ensures lossless roundtrips across parsing, persistence, display and editing.
 */
object UnitConverter {

    const val GRAMS_PER_LB = 453.59237

    fun canonicalUnit(rawUnit: String?): String {
        val u = rawUnit?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            u.startsWith("kg") || u.startsWith("kilo") -> "KG"
            u.startsWith("lb") || u.startsWith("libra") -> "LBS"
            u.startsWith("bloque") -> "BLOQUES"
            u.startsWith("placa") -> "PLACAS"
            u == "bw" || u.contains("corporal") -> "BW"
            else -> "KG"
        }
    }

    /**
     * Converts a raw user-entered load into integer grams for persistence in SQLite.
     */
    fun toGrams(rawValue: Double, unit: String): Int {
        val cUnit = canonicalUnit(unit)
        return when (cUnit) {
            "BW" -> 0
            "KG" -> (rawValue * 1000.0).roundToInt()
            "LBS" -> (rawValue * GRAMS_PER_LB).roundToInt()
            "BLOQUES", "PLACAS" -> (rawValue * 1000.0).roundToInt()
            else -> (rawValue * 1000.0).roundToInt()
        }
    }

    /**
     * Converts integer grams from SQLite back into the exact original unit value.
     */
    fun toRawValue(weightGrams: Int, unit: String): Double {
        val cUnit = canonicalUnit(unit)
        return when (cUnit) {
            "BW" -> 0.0
            "KG" -> weightGrams / 1000.0
            "LBS" -> weightGrams / GRAMS_PER_LB
            "BLOQUES", "PLACAS" -> weightGrams / 1000.0
            else -> weightGrams / 1000.0
        }
    }

    /**
     * Validates whether a load value is physiologically possible and valid for its unit.
     */
    fun isValidLoad(rawValue: Double, unit: String): Boolean {
        val cUnit = canonicalUnit(unit)
        if (cUnit == "BW") return true
        if (rawValue <= 0.0) return false
        return when (cUnit) {
            "KG" -> rawValue <= 1000.0
            "LBS" -> rawValue <= 2200.0
            "BLOQUES", "PLACAS" -> rawValue <= 100.0
            else -> rawValue <= 1000.0
        }
    }

    /**
     * Formats load for display, avoiding loss of precision for pounds and decimal kilograms.
     */
    fun formatDisplay(weightGrams: Int, unit: String): String {
        val cUnit = canonicalUnit(unit)
        if (cUnit == "BW") return "BW"
        val raw = toRawValue(weightGrams, cUnit)
        return when (cUnit) {
            "BLOQUES" -> "${raw.roundToInt()} bloques"
            "PLACAS" -> "${raw.roundToInt()} placas"
            "LBS" -> {
                val rounded = (raw * 10.0).roundToInt() / 10.0
                if (rounded % 1.0 == 0.0) "${rounded.toInt()} lb" else "$rounded lb"
            }
            else -> {
                if (weightGrams % 1000 == 0) {
                    "${weightGrams / 1000} kg"
                } else {
                    val kg = weightGrams / 1000.0
                    val formatted = String.format(Locale.US, "%.2f", kg).trimEnd('0').trimEnd('.')
                    formatted.replace('.', ',') + " kg"
                }
            }
        }
    }
}
