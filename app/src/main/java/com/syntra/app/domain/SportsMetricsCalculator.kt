package com.syntra.app.domain

import com.syntra.app.data.WorkoutSetEntity
import java.text.Normalizer
import java.util.Locale

enum class LoadModality(val displayName: String) {
    BILATERAL_EXTERNAL("Carga bilateral (barra/máquina)"),
    DUMBBELL_PER_ARM("Mancuernas por brazo"),
    UNILATERAL("Ejercicio unilateral"),
    WEIGHTED_BODYWEIGHT("Lastre adicional"),
    ASSISTED_BODYWEIGHT("Ejercicio asistido"),
    BODYWEIGHT("Peso corporal"),
    MACHINE_BLOCKS("Carga por bloques/placas")
}

enum class MetricDataQuality(val displayName: String) {
    MEASURED("Medido"),
    ESTIMATED("Estimado"),
    INSUFFICIENT_DATA("Datos insuficientes")
}

data class SessionVolumeMetrics(
    val totalExternalKg: Double,
    val barbellMachineKg: Double,
    val dumbbellTotalKg: Double,
    val unilateralKg: Double = 0.0,
    val weightedBodyweightKg: Double = 0.0,
    val assistedWeightKg: Double,
    val totalSetsCount: Int,
    val regularSetsCount: Int,
    val dumbbellSetsCount: Int,
    val unilateralSetsCount: Int = 0,
    val weightedBodyweightSetsCount: Int = 0,
    val assistedSetsCount: Int,
    val bodyweightSetsCount: Int,
    val isEstimated: Boolean,
    val dataQuality: MetricDataQuality = MetricDataQuality.MEASURED,
    val summaryFormatted: String
)

object SportsMetricsCalculator {

    private val unilateralKeywords = listOf(
        "unilateral", "a un brazo", "a una mano", "a una pierna",
        "bulgara", "búlgara", "estocada", "zancada", "serrucho",
        "concentrado", "patada de burro", "one arm", "single leg"
    )

    private val weightedBodyweightKeywords = listOf(
        "lastre", "lastrad", "cinturon", "weighted"
    )

    private val bodyweightExerciseKeywords = listOf(
        "dominada", "dominadas", "pull up", "pullups", "chin up",
        "fondos", "dips", "flexiones", "push up", "calistenia"
    )

    private fun normalize(text: String): String {
        return Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Deterministically classifies the load modality of a set without mixing incompatible loads.
     */
    fun classifySetModality(
        set: WorkoutSetEntity,
        exerciseName: String = "",
        equipment: String? = null
    ): LoadModality {
        val normNote = normalize(set.note)
        val normEx = normalize(exerciseName)
        val normEq = equipment?.let { normalize(it) } ?: ""

        if (set.loadUnit.equals("BLOQUES", ignoreCase = true) || set.loadUnit.equals("PLACAS", ignoreCase = true)) {
            return LoadModality.MACHINE_BLOCKS
        }

        if (set.isAssisted || normNote.contains("[asistido]")) {
            return LoadModality.ASSISTED_BODYWEIGHT
        }

        if (set.loadUnit.equals("BW", ignoreCase = true) || set.loadGrams == 0) {
            return LoadModality.BODYWEIGHT
        }

        val isWeightedBw = normNote.contains("[lastre]") ||
            weightedBodyweightKeywords.any { normNote.contains(it) || normEx.contains(it) } ||
            (bodyweightExerciseKeywords.any { normEx.contains(it) } && set.loadGrams > 0 && !set.isDumbbell)

        if (isWeightedBw) {
            return LoadModality.WEIGHTED_BODYWEIGHT
        }

        val isUnilateral = normNote.contains("[unilateral]") ||
            unilateralKeywords.any { normNote.contains(it) || normEx.contains(it) }

        if (isUnilateral) {
            return LoadModality.UNILATERAL
        }

        if (set.isDumbbell || normEq.contains("mancuerna")) {
            return LoadModality.DUMBBELL_PER_ARM
        }

        return LoadModality.BILATERAL_EXTERNAL
    }

    /**
     * Checks if two load modalities can had their raw loadGrams directly compared as equivalent.
     * Prevents comparing e.g. per-hand dumbbell vs barbell, or assisted counterweight vs weighted pullups.
     */
    fun areModalitiesCompatible(a: LoadModality, b: LoadModality): Boolean {
        return a == b
    }

    /**
     * Effective external load in grams lifted per repetition for a given modality.
     * - BILATERAL_EXTERNAL: loadGrams
     * - DUMBBELL_PER_ARM: loadGrams * 2 (two dumbbells moved simultaneously)
     * - UNILATERAL: loadGrams (load moved by the target limb per side)
     * - WEIGHTED_BODYWEIGHT: loadGrams (additional ballast moved)
     * - ASSISTED_BODYWEIGHT: 0 (assistance counterweight reduces load; never added to external tonnage)
     * - BODYWEIGHT: 0 external load
     */
    fun effectiveExternalLoadGramsPerRep(set: WorkoutSetEntity, modality: LoadModality): Long {
        val grams = set.loadGrams.toLong().coerceAtLeast(0L)
        return when (modality) {
            LoadModality.BILATERAL_EXTERNAL -> grams
            LoadModality.DUMBBELL_PER_ARM -> grams * 2L
            LoadModality.UNILATERAL -> grams
            LoadModality.WEIGHTED_BODYWEIGHT -> grams
            LoadModality.ASSISTED_BODYWEIGHT -> 0L
            LoadModality.BODYWEIGHT -> 0L
            LoadModality.MACHINE_BLOCKS -> 0L
        }
    }

    fun calculateSessionVolume(
        sets: List<WorkoutSetEntity>,
        bodyweightGrams: Int? = null,
        exerciseNamesById: Map<Long, String> = emptyMap(),
        equipmentById: Map<Long, String?> = emptyMap()
    ): SessionVolumeMetrics {
        if (sets.isEmpty()) {
            return SessionVolumeMetrics(
                totalExternalKg = 0.0,
                barbellMachineKg = 0.0,
                dumbbellTotalKg = 0.0,
                unilateralKg = 0.0,
                weightedBodyweightKg = 0.0,
                assistedWeightKg = 0.0,
                totalSetsCount = 0,
                regularSetsCount = 0,
                dumbbellSetsCount = 0,
                unilateralSetsCount = 0,
                weightedBodyweightSetsCount = 0,
                assistedSetsCount = 0,
                bodyweightSetsCount = 0,
                isEstimated = false,
                dataQuality = MetricDataQuality.INSUFFICIENT_DATA,
                summaryFormatted = "Sin series registradas."
            )
        }

        var barbellMachineGrams = 0L
        var dumbbellTotalGrams = 0L
        var unilateralGrams = 0L
        var weightedBodyweightGrams = 0L
        var assistedGrams = 0L

        var regularSets = 0
        var machineBlocksSets = 0
        var dumbbellSets = 0
        var unilateralSets = 0
        var weightedBwSets = 0
        var assistedSets = 0
        var bodyweightSets = 0

        for (s in sets) {
            val exName = exerciseNamesById[s.exerciseId] ?: ""
            val eq = equipmentById[s.exerciseId]
            val modality = classifySetModality(s, exName, eq)
            val setGrams = s.loadGrams.toLong().coerceAtLeast(0L)
            val reps = s.repetitions.toLong().coerceAtLeast(0L)

            when (modality) {
                LoadModality.ASSISTED_BODYWEIGHT -> {
                    assistedSets++
                    assistedGrams += setGrams * reps
                }
                LoadModality.BODYWEIGHT -> {
                    bodyweightSets++
                }
                LoadModality.WEIGHTED_BODYWEIGHT -> {
                    weightedBwSets++
                    weightedBodyweightGrams += setGrams * reps
                }
                LoadModality.UNILATERAL -> {
                    unilateralSets++
                    unilateralGrams += setGrams * reps
                }
                LoadModality.DUMBBELL_PER_ARM -> {
                    dumbbellSets++
                    dumbbellTotalGrams += (setGrams * 2L) * reps
                }
                LoadModality.BILATERAL_EXTERNAL -> {
                    regularSets++
                    barbellMachineGrams += setGrams * reps
                }
                LoadModality.MACHINE_BLOCKS -> {
                    // Machine blocks/plates: tracked separately so barbell/machine kg set count is accurate
                    machineBlocksSets++
                }
            }
        }

        val totalExternalGrams = barbellMachineGrams + dumbbellTotalGrams + unilateralGrams + weightedBodyweightGrams
        val totalExternalKg = totalExternalGrams / 1000.0
        val barbellKg = barbellMachineGrams / 1000.0
        val dumbbellKg = dumbbellTotalGrams / 1000.0
        val unilateralKg = unilateralGrams / 1000.0
        val weightedBwKg = weightedBodyweightGrams / 1000.0
        val assistedKg = assistedGrams / 1000.0

        val parts = mutableListOf<String>()
        parts.add("Carga externa total: ${formatKg(totalExternalKg)}")

        val details = mutableListOf<String>()
        if (barbellKg > 0) details.add("${formatKg(barbellKg)} barra/máquina ($regularSets series)")
        if (dumbbellKg > 0) details.add("${formatKg(dumbbellKg)} mancuernas bilat. ($dumbbellSets series)")
        if (unilateralKg > 0) details.add("${formatKg(unilateralKg)} unilateral ($unilateralSets series)")
        if (weightedBwKg > 0) details.add("+${formatKg(weightedBwKg)} lastre ($weightedBwSets series)")
        if (assistedSets > 0) details.add("$assistedSets series asistidas (-${formatKg(assistedKg)} contrapeso)")
        if (bodyweightSets > 0) details.add("$bodyweightSets series corporales")
        if (machineBlocksSets > 0) details.add("$machineBlocksSets series en bloques/placas")

        if (details.isNotEmpty()) {
            parts.add("Desglose: ${details.joinToString(" • ")}.")
        }

        val isEstimated = bodyweightSets > 0 || assistedSets > 0 || weightedBwSets > 0
        val dataQuality = if (isEstimated && bodyweightGrams == null) {
            MetricDataQuality.ESTIMATED
        } else {
            MetricDataQuality.MEASURED
        }

        return SessionVolumeMetrics(
            totalExternalKg = totalExternalKg,
            barbellMachineKg = barbellKg,
            dumbbellTotalKg = dumbbellKg,
            unilateralKg = unilateralKg,
            weightedBodyweightKg = weightedBwKg,
            assistedWeightKg = assistedKg,
            totalSetsCount = sets.size,
            regularSetsCount = regularSets,
            dumbbellSetsCount = dumbbellSets,
            unilateralSetsCount = unilateralSets,
            weightedBodyweightSetsCount = weightedBwSets,
            assistedSetsCount = assistedSets,
            bodyweightSetsCount = bodyweightSets,
            isEstimated = isEstimated,
            dataQuality = dataQuality,
            summaryFormatted = parts.joinToString("\n")
        )
    }

    fun formatKg(kg: Double): String {
        return if (kg % 1.0 == 0.0) {
            "${kg.toInt()} kg"
        } else {
            String.format(Locale.ROOT, "%.1f kg", kg)
        }
    }
}
