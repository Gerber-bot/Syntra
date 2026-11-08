package com.syntra.app.domain

import com.syntra.app.data.WorkoutSetEntity
import java.util.Locale

data class SessionVolumeMetrics(
    val totalExternalKg: Double,
    val barbellMachineKg: Double,
    val dumbbellTotalKg: Double,
    val assistedWeightKg: Double,
    val totalSetsCount: Int,
    val regularSetsCount: Int,
    val dumbbellSetsCount: Int,
    val assistedSetsCount: Int,
    val bodyweightSetsCount: Int,
    val isEstimated: Boolean,
    val summaryFormatted: String
)

object SportsMetricsCalculator {

    fun calculateSessionVolume(
        sets: List<WorkoutSetEntity>,
        bodyweightGrams: Int? = null
    ): SessionVolumeMetrics {
        if (sets.isEmpty()) {
            return SessionVolumeMetrics(
                totalExternalKg = 0.0,
                barbellMachineKg = 0.0,
                dumbbellTotalKg = 0.0,
                assistedWeightKg = 0.0,
                totalSetsCount = 0,
                regularSetsCount = 0,
                dumbbellSetsCount = 0,
                assistedSetsCount = 0,
                bodyweightSetsCount = 0,
                isEstimated = false,
                summaryFormatted = "Sin series registradas."
            )
        }

        var barbellMachineGrams = 0L
        var dumbbellTotalGrams = 0L
        var assistedGrams = 0L

        var regularSets = 0
        var dumbbellSets = 0
        var assistedSets = 0
        var bodyweightSets = 0

        for (s in sets) {
            val setGrams = s.loadGrams.toLong()
            val reps = s.repetitions.toLong()

            when {
                s.isAssisted -> {
                    assistedSets++
                    assistedGrams += setGrams * reps
                }
                s.isDumbbell -> {
                    dumbbellSets++
                    // Dumbbell: user enters load per hand; total bilateral work is 2x
                    dumbbellTotalGrams += (setGrams * 2) * reps
                }
                setGrams == 0L -> {
                    bodyweightSets++
                }
                else -> {
                    regularSets++
                    barbellMachineGrams += setGrams * reps
                }
            }
        }

        val totalExternalGrams = barbellMachineGrams + dumbbellTotalGrams
        val totalExternalKg = totalExternalGrams / 1000.0
        val barbellKg = barbellMachineGrams / 1000.0
        val dumbbellKg = dumbbellTotalGrams / 1000.0
        val assistedKg = assistedGrams / 1000.0

        val parts = mutableListOf<String>()
        parts.add("Carga externa total: ${formatKg(totalExternalKg)}")

        val details = mutableListOf<String>()
        if (barbellKg > 0) details.add("${formatKg(barbellKg)} barra/máquina ($regularSets series)")
        if (dumbbellKg > 0) details.add("${formatKg(dumbbellKg)} mancuernas bilat. ($dumbbellSets series)")
        if (assistedSets > 0) details.add("$assistedSets series asistidas (-${formatKg(assistedKg)} contrapeso)")
        if (bodyweightSets > 0) details.add("$bodyweightSets series corporales")

        if (details.isNotEmpty()) {
            parts.add("Desglose: ${details.joinToString(" • ")}.")
        }

        val isEstimated = bodyweightSets > 0 || assistedSets > 0

        return SessionVolumeMetrics(
            totalExternalKg = totalExternalKg,
            barbellMachineKg = barbellKg,
            dumbbellTotalKg = dumbbellKg,
            assistedWeightKg = assistedKg,
            totalSetsCount = sets.size,
            regularSetsCount = regularSets,
            dumbbellSetsCount = dumbbellSets,
            assistedSetsCount = assistedSets,
            bodyweightSetsCount = bodyweightSets,
            isEstimated = isEstimated,
            summaryFormatted = parts.joinToString("\n")
        )
    }

    private fun formatKg(kg: Double): String {
        return if (kg % 1.0 == 0.0) {
            "${kg.toInt()} kg"
        } else {
            String.format(Locale.ROOT, "%.1f kg", kg)
        }
    }
}
