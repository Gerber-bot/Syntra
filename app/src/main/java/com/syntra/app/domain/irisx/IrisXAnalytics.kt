package com.syntra.app.domain.irisx

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.LoadModality
import com.syntra.app.domain.MetricDataQuality
import com.syntra.app.domain.SportsMetricsCalculator
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Data point prepared for deterministic chart rendering in Compose Canvas.
 */
data class ChartDataPoint(
    val label: String,
    val timestampMillis: Long,
    val value: Double,
    val unit: String,
    val modality: LoadModality? = null,
    val dataQuality: MetricDataQuality = MetricDataQuality.MEASURED
)

data class ExerciseSessionPoint(
    val sessionId: Long,
    val sessionTitle: String,
    val timestampMillis: Long,
    val modality: LoadModality,
    val topLoadGrams: Int,
    val topLoadReps: Int,
    val maxRepsInSet: Int,
    val totalReps: Int,
    val setsCount: Int,
    val modalityVolumeKg: Double,
    val assistedCounterweightKg: Double = 0.0,
    val dataQuality: MetricDataQuality,
    val unit: String = "KG"
)

data class PersonalRecord(
    val exerciseName: String,
    val modality: LoadModality,
    val maxLoadGrams: Int,
    val repsAtMaxLoad: Int,
    val maxSingleSetVolumeKg: Double,
    val maxRepsInSingleSet: Int,
    val loadAtMaxRepsGrams: Int,
    val achievedAtMillis: Long,
    val sessionId: Long,
    val dataQuality: MetricDataQuality,
    val unit: String = "KG"
)

enum class ProgressionStatus(val displayName: String) {
    PROGRESSING("En progresión"),
    MAINTAINING("Estable / Mantenimiento"),
    STAGNANT("Estancamiento detectado"),
    REGRESSING("Retroceso puntual"),
    INSUFFICIENT_DATA("Datos insuficientes")
}

data class StagnationReport(
    val isStagnant: Boolean,
    val consecutiveSessionsWithoutProgress: Int,
    val modality: LoadModality?,
    val criteriaUsed: String,
    val explanation: String,
    val dataQuality: MetricDataQuality
)

data class ExerciseProgressionReport(
    val exerciseName: String,
    val dominantModality: LoadModality?,
    val status: ProgressionStatus,
    val history: List<ExerciseSessionPoint>,
    val personalRecordsByModality: Map<LoadModality, PersonalRecord>,
    val stagnation: StagnationReport,
    val loadChartPoints: List<ChartDataPoint>,
    val volumeChartPoints: List<ChartDataPoint>,
    val incompatibleModalitiesDetected: Boolean,
    val fatigueDisclaimer: String?,
    val dataQuality: MetricDataQuality,
    val summaryText: String
)

data class PeriodComparisonReport(
    val periodALabel: String,
    val periodBLabel: String,
    val periodASessions: Int,
    val periodBSessions: Int,
    val periodAExternalVolumeKg: Double,
    val periodBExternalVolumeKg: Double,
    val volumeDeltaPercent: Double?,
    val periodASets: Int,
    val periodBSets: Int,
    val muscleGroupSessionsDelta: Map<String, Int>,
    val dataQuality: MetricDataQuality,
    val summaryText: String
)

data class PeriodTrendBucket(
    val bucketLabel: String,
    val startMillis: Long,
    val endMillis: Long,
    val sessionCount: Int,
    val totalSets: Int,
    val externalVolumeKg: Double,
    val dataQuality: MetricDataQuality
)

data class MuscleGroupDistribution(
    val sessionsByMuscle: Map<String, Int>,
    val setsByMuscle: Map<String, Int>,
    val volumeKgByMuscle: Map<String, Double>,
    val percentagesBySets: Map<String, Double>,
    val totalAnalyzedSessions: Int,
    val totalAnalyzedSets: Int,
    val dataQuality: MetricDataQuality
)

data class TrainingFrequencyReport(
    val totalSessions: Int,
    val windowDays: Int,
    val averageSessionsPerWeek: Double,
    val averageDaysBetweenSessions: Double?,
    val dataQuality: MetricDataQuality,
    val summaryText: String
)

object IrisXAnalytics {

    private const val DAY_MILLIS = 86_400_000L
    private const val MIN_SESSIONS_FOR_PROGRESSION = 2
    private const val STAGNATION_THRESHOLD_SESSIONS = 3

    fun normalize(text: String): String {
        return Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Analyzes progression, history, PRs, stagnation, and chart points for a specific exercise.
     * Strictly separates incompatible load modalities (e.g., never mixes bilateral barbell with
     * per-arm dumbbell or assisted counterweight).
     */
    fun analyzeExerciseProgression(
        exerciseName: String,
        sessions: List<WorkoutSessionEntity>,
        allSets: List<WorkoutSetEntity>,
        exercises: List<ExerciseEntity>,
        learnedAliases: Map<String, String> = emptyMap()
    ): ExerciseProgressionReport {
        val targetNorm = normalize(exerciseName)
        val canonicalTarget = learnedAliases[targetNorm]?.let { normalize(it) } ?: targetNorm

        // Match exercise IDs
        val matchingExercises = exercises.filter { ex ->
            val exNorm = normalize(ex.displayName)
            exNorm == canonicalTarget || exNorm == targetNorm ||
                exNorm.contains(canonicalTarget) || canonicalTarget.contains(exNorm)
        }
        val matchingIds = matchingExercises.map { it.id }.toSet()
        val displayTitle = matchingExercises.firstOrNull()?.displayName ?: exerciseName.trim()
        val equipmentMap = exercises.associate { it.id to it.equipment }
        val nameMap = exercises.associate { it.id to it.displayName }

        val relevantSets = allSets.filter { it.exerciseId in matchingIds }
        if (relevantSets.isEmpty()) {
            return ExerciseProgressionReport(
                exerciseName = displayTitle,
                dominantModality = null,
                status = ProgressionStatus.INSUFFICIENT_DATA,
                history = emptyList(),
                personalRecordsByModality = emptyMap(),
                stagnation = StagnationReport(
                    isStagnant = false,
                    consecutiveSessionsWithoutProgress = 0,
                    modality = null,
                    criteriaUsed = "Mínimo $STAGNATION_THRESHOLD_SESSIONS sesiones en la misma modalidad.",
                    explanation = "Datos insuficientes: no hay sesiones registradas para '$displayTitle'.",
                    dataQuality = MetricDataQuality.INSUFFICIENT_DATA
                ),
                loadChartPoints = emptyList(),
                volumeChartPoints = emptyList(),
                incompatibleModalitiesDetected = false,
                fatigueDisclaimer = null,
                dataQuality = MetricDataQuality.INSUFFICIENT_DATA,
                summaryText = "Datos insuficientes: no se encontraron registros históricos para '$displayTitle'."
            )
        }

        val sessionById = sessions.associateBy { it.id }
        val setsBySession = relevantSets.groupBy { it.sessionId }

        val historyPoints = mutableListOf<ExerciseSessionPoint>()

        // Build chronological points grouped by session AND modality so incompatible loads are never merged
        for ((sessionId, sessionSets) in setsBySession) {
            val session = sessionById[sessionId] ?: continue
            val byModality = sessionSets.groupBy { s ->
                SportsMetricsCalculator.classifySetModality(
                    set = s,
                    exerciseName = nameMap[s.exerciseId] ?: displayTitle,
                    equipment = equipmentMap[s.exerciseId]
                )
            }

            for ((modality, modSets) in byModality) {
                // For ASSISTED_BODYWEIGHT, lower counterweight = harder (less help)
                val bestSet = if (modality == LoadModality.ASSISTED_BODYWEIGHT) {
                    modSets.minWithOrNull(compareBy<WorkoutSetEntity> { it.loadGrams }.thenByDescending { it.repetitions })!!
                } else {
                    modSets.maxWithOrNull(compareBy<WorkoutSetEntity> { it.loadGrams }.thenBy { it.repetitions })!!
                }

                val maxReps = modSets.maxOf { it.repetitions }
                val totalReps = modSets.sumOf { it.repetitions }

                val volMetrics = SportsMetricsCalculator.calculateSessionVolume(
                    sets = modSets,
                    exerciseNamesById = nameMap,
                    equipmentById = equipmentMap
                )

                val quality = when (modality) {
                    LoadModality.ASSISTED_BODYWEIGHT, LoadModality.BODYWEIGHT, LoadModality.WEIGHTED_BODYWEIGHT ->
                        MetricDataQuality.ESTIMATED
                    else -> MetricDataQuality.MEASURED
                }

                historyPoints.add(
                    ExerciseSessionPoint(
                        sessionId = session.id,
                        sessionTitle = session.title,
                        timestampMillis = session.startedAtMillis,
                        modality = modality,
                        topLoadGrams = bestSet.loadGrams,
                        topLoadReps = bestSet.repetitions,
                        maxRepsInSet = maxReps,
                        totalReps = totalReps,
                        setsCount = modSets.size,
                        modalityVolumeKg = volMetrics.totalExternalKg,
                        assistedCounterweightKg = volMetrics.assistedWeightKg,
                        dataQuality = quality,
                        unit = bestSet.loadUnit
                    )
                )
            }
        }

        val sortedHistory = historyPoints.sortedBy { it.timestampMillis }
        val distinctModalities = sortedHistory.map { it.modality }.distinct()
        val incompatibleModalitiesDetected = distinctModalities.size > 1

        // Dominant modality is the one with the most session entries
        val dominantModality = sortedHistory
            .groupBy { it.modality }
            .maxByOrNull { it.value.size }
            ?.key

        val dominantHistory = sortedHistory.filter { it.modality == dominantModality }

        // Personal Records per modality (never mixing modalities)
        val prsByModality = mutableMapOf<LoadModality, PersonalRecord>()
        for (modality in distinctModalities) {
            val modPoints = sortedHistory.filter { it.modality == modality }
            val modSets = relevantSets.filter { s ->
                SportsMetricsCalculator.classifySetModality(
                    s, nameMap[s.exerciseId] ?: displayTitle, equipmentMap[s.exerciseId]
                ) == modality
            }
            if (modPoints.isEmpty() || modSets.isEmpty()) continue

            val bestPoint = if (modality == LoadModality.ASSISTED_BODYWEIGHT) {
                modPoints.minWithOrNull(compareBy<ExerciseSessionPoint> { it.topLoadGrams }.thenByDescending { it.topLoadReps })!!
            } else {
                modPoints.maxWithOrNull(compareBy<ExerciseSessionPoint> { it.topLoadGrams }.thenBy { it.topLoadReps })!!
            }

            val maxRepsSet = modSets.maxWithOrNull(compareBy<WorkoutSetEntity> { it.repetitions }.thenBy { it.loadGrams })!!
            val maxSingleSetVolKg = modSets.maxOf { s ->
                val effGrams = SportsMetricsCalculator.effectiveExternalLoadGramsPerRep(s, modality)
                (effGrams * s.repetitions) / 1000.0
            }

            prsByModality[modality] = PersonalRecord(
                exerciseName = displayTitle,
                modality = modality,
                maxLoadGrams = bestPoint.topLoadGrams,
                repsAtMaxLoad = bestPoint.topLoadReps,
                maxSingleSetVolumeKg = maxSingleSetVolKg,
                maxRepsInSingleSet = maxRepsSet.repetitions,
                loadAtMaxRepsGrams = maxRepsSet.loadGrams,
                achievedAtMillis = bestPoint.timestampMillis,
                sessionId = bestPoint.sessionId,
                dataQuality = bestPoint.dataQuality,
                unit = bestPoint.unit
            )
        }

        // Stagnation detection on dominant modality
        val stagnation = evaluateStagnation(displayTitle, dominantModality, dominantHistory)

        // Determine overall status on dominant modality
        val status = when {
            dominantHistory.size < MIN_SESSIONS_FOR_PROGRESSION -> ProgressionStatus.INSUFFICIENT_DATA
            stagnation.isStagnant -> ProgressionStatus.STAGNANT
            else -> {
                val prev = dominantHistory[dominantHistory.size - 2]
                val last = dominantHistory.last()
                if (prev.unit != last.unit) {
                    ProgressionStatus.INSUFFICIENT_DATA
                } else {
                    val improved = isSessionImproved(prev, last, dominantModality!!)
                    val regressed = isSessionRegressed(prev, last, dominantModality)
                    when {
                        improved -> ProgressionStatus.PROGRESSING
                        regressed -> ProgressionStatus.REGRESSING
                        else -> ProgressionStatus.MAINTAINING
                    }
                }
            }
        }

        // Check if within the latest session repetitions decreased across sets (e.g. 12, 10, 8)
        // Rule: "No inferir fatiga muscular exclusivamente por una disminución de repeticiones."
        val latestSessionId = sortedHistory.lastOrNull()?.sessionId
        val latestSets = if (latestSessionId != null) {
            relevantSets.filter { it.sessionId == latestSessionId }.sortedBy { it.orderInExercise }
        } else emptyList()
        val hasRepDropOff = latestSets.size >= 2 &&
            latestSets.zipWithNext().any { (a, b) -> a.loadGrams == b.loadGrams && b.repetitions < a.repetitions }
        val fatigueDisclaimer = if (hasRepDropOff || status == ProgressionStatus.REGRESSING) {
            "Nota metodológica: una disminución de repeticiones entre series o sesiones no prueba por sí sola fatiga muscular acumulada (puede responder al descanso entre series, cercanía al fallo o secuencia de ejercicios)."
        } else null

        // Build Chart Data Points for the dominant modality
        val loadChartPoints = dominantHistory.mapIndexed { idx, pt ->
            val displayVal = if (pt.modality == LoadModality.BODYWEIGHT) {
                pt.maxRepsInSet.toDouble()
            } else {
                com.syntra.app.domain.UnitConverter.toRawValue(pt.topLoadGrams, pt.unit)
            }
            val unitStr = when (pt.modality) {
                LoadModality.DUMBBELL_PER_ARM -> {
                    val baseUnit = when (pt.unit) {
                        "LBS" -> "lb"
                        else -> "kg"
                    }
                    "$baseUnit/brazo"
                }
                LoadModality.MACHINE_BLOCKS -> pt.unit.lowercase()
                LoadModality.BODYWEIGHT -> "reps"
                else -> pt.unit.lowercase()
            }
            ChartDataPoint(
                label = "S${idx + 1}",
                timestampMillis = pt.timestampMillis,
                value = displayVal,
                unit = unitStr,
                modality = pt.modality,
                dataQuality = pt.dataQuality
            )
        }

        val volumeChartPoints = dominantHistory.mapIndexed { idx, pt ->
            ChartDataPoint(
                label = "S${idx + 1}",
                timestampMillis = pt.timestampMillis,
                value = pt.modalityVolumeKg,
                unit = "kg",
                modality = pt.modality,
                dataQuality = pt.dataQuality
            )
        }

        val overallQuality = when {
            dominantHistory.size < MIN_SESSIONS_FOR_PROGRESSION -> MetricDataQuality.INSUFFICIENT_DATA
            dominantHistory.any { it.dataQuality == MetricDataQuality.ESTIMATED } -> MetricDataQuality.ESTIMATED
            else -> MetricDataQuality.MEASURED
        }

        val summary = buildString {
            append("📊 Progresión de $displayTitle\n")
            if (dominantModality != null) {
                append("Modalidad principal: ${dominantModality.displayName} (${dominantHistory.size} sesiones)\n")
            }
            append("Estado: ${status.displayName} [${overallQuality.displayName}]\n")
            val pr = dominantModality?.let { prsByModality[it] }
            if (pr != null) {
                val prLoadFormatted = when (dominantModality) {
                    LoadModality.MACHINE_BLOCKS -> com.syntra.app.domain.UnitConverter.formatDisplay(pr.maxLoadGrams, pr.unit)
                    LoadModality.BODYWEIGHT -> "peso corporal"
                    else -> com.syntra.app.domain.UnitConverter.formatDisplay(pr.maxLoadGrams, pr.unit)
                }
                val suffix = when (dominantModality) {
                    LoadModality.DUMBBELL_PER_ARM -> " por brazo"
                    LoadModality.UNILATERAL -> " por lado"
                    LoadModality.WEIGHTED_BODYWEIGHT -> " de lastre"
                    LoadModality.ASSISTED_BODYWEIGHT -> " de asistencia"
                    else -> ""
                }
                append("Récord registrado: $prLoadFormatted$suffix × ${pr.repsAtMaxLoad} reps\n")
            }
            if (dominantHistory.size >= 2) {
                val first = dominantHistory.first()
                val last = dominantHistory.last()
                val firstFormatted = com.syntra.app.domain.UnitConverter.formatDisplay(first.topLoadGrams, first.unit)
                val lastFormatted = com.syntra.app.domain.UnitConverter.formatDisplay(last.topLoadGrams, last.unit)
                append("Evolución: de $firstFormatted × ${first.topLoadReps} a $lastFormatted × ${last.topLoadReps}\n")
            }
            if (incompatibleModalitiesDetected) {
                append("ℹ Se registraron múltiples modalidades (${distinctModalities.joinToString { it.displayName }}). Las cargas se analizaron por separado sin mezclarlas.\n")
            }
            if (stagnation.isStagnant) {
                append("⚠ ${stagnation.explanation}\n")
            }
            if (fatigueDisclaimer != null) {
                append(fatigueDisclaimer)
            }
        }.trim()

        return ExerciseProgressionReport(
            exerciseName = displayTitle,
            dominantModality = dominantModality,
            status = status,
            history = sortedHistory,
            personalRecordsByModality = prsByModality,
            stagnation = stagnation,
            loadChartPoints = loadChartPoints,
            volumeChartPoints = volumeChartPoints,
            incompatibleModalitiesDetected = incompatibleModalitiesDetected,
            fatigueDisclaimer = fatigueDisclaimer,
            dataQuality = overallQuality,
            summaryText = summary
        )
    }

    private fun isSessionImproved(
        prev: ExerciseSessionPoint,
        curr: ExerciseSessionPoint,
        modality: LoadModality
    ): Boolean {
        if (prev.modality != curr.modality || prev.unit != curr.unit) return false
        return when (modality) {
            LoadModality.ASSISTED_BODYWEIGHT -> {
                curr.topLoadGrams < prev.topLoadGrams ||
                    (curr.topLoadGrams == prev.topLoadGrams && curr.topLoadReps > prev.topLoadReps)
            }
            LoadModality.BODYWEIGHT -> {
                curr.maxRepsInSet > prev.maxRepsInSet || curr.totalReps > prev.totalReps
            }
            else -> {
                curr.topLoadGrams > prev.topLoadGrams ||
                    (curr.topLoadGrams == prev.topLoadGrams && curr.topLoadReps > prev.topLoadReps) ||
                    (curr.topLoadGrams == prev.topLoadGrams && curr.modalityVolumeKg > prev.modalityVolumeKg)
            }
        }
    }

    private fun isSessionRegressed(
        prev: ExerciseSessionPoint,
        curr: ExerciseSessionPoint,
        modality: LoadModality
    ): Boolean {
        if (prev.modality != curr.modality || prev.unit != curr.unit) return false
        return when (modality) {
            LoadModality.ASSISTED_BODYWEIGHT -> {
                curr.topLoadGrams > prev.topLoadGrams ||
                    (curr.topLoadGrams == prev.topLoadGrams && curr.topLoadReps < prev.topLoadReps)
            }
            LoadModality.BODYWEIGHT -> {
                curr.maxRepsInSet < prev.maxRepsInSet && curr.totalReps < prev.totalReps
            }
            else -> {
                curr.topLoadGrams < prev.topLoadGrams ||
                    (curr.topLoadGrams == prev.topLoadGrams && curr.topLoadReps < prev.topLoadReps && curr.modalityVolumeKg < prev.modalityVolumeKg)
            }
        }
    }

    /**
     * Explicit stagnation detection:
     * Requires at least 3 consecutive sessions of the same modality where neither maximum load,
     * repetitions at top load, nor total modality volume has exceeded the baseline session of the window.
     */
    fun evaluateStagnation(
        exerciseName: String,
        modality: LoadModality?,
        modalityHistory: List<ExerciseSessionPoint>
    ): StagnationReport {
        val criterion = "Criterio explícito: ≥$STAGNATION_THRESHOLD_SESSIONS sesiones consecutivas en la misma modalidad sin superar la carga tope, las repeticiones con dicha carga ni el volumen de trabajo."

        if (modality == null || modalityHistory.size < STAGNATION_THRESHOLD_SESSIONS) {
            return StagnationReport(
                isStagnant = false,
                consecutiveSessionsWithoutProgress = modalityHistory.size,
                modality = modality,
                criteriaUsed = criterion,
                explanation = "Se requieren al menos $STAGNATION_THRESHOLD_SESSIONS sesiones en la misma modalidad para evaluar estancamiento (actual: ${modalityHistory.size}).",
                dataQuality = MetricDataQuality.INSUFFICIENT_DATA
            )
        }

        val recent = modalityHistory.takeLast(STAGNATION_THRESHOLD_SESSIONS)
        val baseline = recent.first()
        var progressedInWindow = false

        for (i in 1 until recent.size) {
            if (isSessionImproved(baseline, recent[i], modality)) {
                progressedInWindow = true
                break
            }
        }

        val isStagnant = !progressedInWindow
        val quality = if (recent.any { it.dataQuality == MetricDataQuality.ESTIMATED }) {
            MetricDataQuality.ESTIMATED
        } else {
            MetricDataQuality.MEASURED
        }

        val explanation = if (isStagnant) {
            val kg = SportsMetricsCalculator.formatKg(baseline.topLoadGrams / 1000.0)
            "Estancamiento detectado en $exerciseName (${modality.displayName}): $STAGNATION_THRESHOLD_SESSIONS sesiones sin superar la marca de $kg × ${baseline.topLoadReps} reps."
        } else {
            "Sin estancamiento en $exerciseName: se registró progresión dentro de las últimas $STAGNATION_THRESHOLD_SESSIONS sesiones."
        }

        return StagnationReport(
            isStagnant = isStagnant,
            consecutiveSessionsWithoutProgress = if (isStagnant) STAGNATION_THRESHOLD_SESSIONS else 0,
            modality = modality,
            criteriaUsed = criterion,
            explanation = explanation,
            dataQuality = quality
        )
    }

    /**
     * Calculates muscle group distribution across sessions and sets within an optional time window.
     */
    fun calculateMuscleDistribution(
        sessions: List<WorkoutSessionEntity>,
        allSets: List<WorkoutSetEntity>,
        exercises: List<ExerciseEntity>,
        learnedAliases: Map<String, String> = emptyMap(),
        muscleOverrides: Map<String, String> = emptyMap(),
        sinceMillis: Long? = null,
        untilMillis: Long? = null
    ): MuscleGroupDistribution {
        val filteredSessions = sessions.filter { s ->
            (sinceMillis == null || s.startedAtMillis >= sinceMillis) &&
                (untilMillis == null || s.startedAtMillis <= untilMillis)
        }

        if (filteredSessions.isEmpty()) {
            return MuscleGroupDistribution(
                sessionsByMuscle = emptyMap(),
                setsByMuscle = emptyMap(),
                volumeKgByMuscle = emptyMap(),
                percentagesBySets = emptyMap(),
                totalAnalyzedSessions = 0,
                totalAnalyzedSets = 0,
                dataQuality = MetricDataQuality.INSUFFICIENT_DATA
            )
        }

        val sessionIds = filteredSessions.map { it.id }.toSet()
        val filteredSets = allSets.filter { it.sessionId in sessionIds }
        val exerciseMap = exercises.associateBy { it.id }

        val sessionsByMuscle = mutableMapOf<String, MutableSet<Long>>()
        val setsByMuscle = mutableMapOf<String, Int>()
        val volumeGramsByMuscle = mutableMapOf<String, Long>()

        for (set in filteredSets) {
            val ex = exerciseMap[set.exerciseId]
            val exName = ex?.displayName ?: ""
            val detectedMuscle = if (ex != null && ex.muscleGroup.isNotBlank() && ex.muscleGroup != "General") {
                ex.muscleGroup
            } else {
                IrisXClassifier.detectMuscleGroup(exName, learnedAliases, muscleOverrides)
            }

            sessionsByMuscle.getOrPut(detectedMuscle) { mutableSetOf() }.add(set.sessionId)
            setsByMuscle[detectedMuscle] = (setsByMuscle[detectedMuscle] ?: 0) + 1

            val modality = SportsMetricsCalculator.classifySetModality(set, exName, ex?.equipment)
            val effGrams = SportsMetricsCalculator.effectiveExternalLoadGramsPerRep(set, modality)
            volumeGramsByMuscle[detectedMuscle] = (volumeGramsByMuscle[detectedMuscle] ?: 0L) + (effGrams * set.repetitions)
        }

        val totalSets = filteredSets.size
        val pctBySets = if (totalSets > 0) {
            setsByMuscle.mapValues { (_, count) -> ((count.toDouble() / totalSets) * 1000.0).roundToInt() / 10.0 }
        } else emptyMap()

        return MuscleGroupDistribution(
            sessionsByMuscle = sessionsByMuscle.mapValues { it.value.size },
            setsByMuscle = setsByMuscle,
            volumeKgByMuscle = volumeGramsByMuscle.mapValues { it.value / 1000.0 },
            percentagesBySets = pctBySets,
            totalAnalyzedSessions = filteredSessions.size,
            totalAnalyzedSets = totalSets,
            dataQuality = if (totalSets > 0) MetricDataQuality.MEASURED else MetricDataQuality.INSUFFICIENT_DATA
        )
    }

    /**
     * Compares two time periods (e.g. current month vs previous month, or last 7 days vs prior 7 days),
     * optionally filtered by a specific muscle group or category.
     */
    fun comparePeriods(
        periodALabel: String,
        periodAStartMillis: Long,
        periodAEndMillis: Long,
        periodBLabel: String,
        periodBStartMillis: Long,
        periodBEndMillis: Long,
        sessions: List<WorkoutSessionEntity>,
        allSets: List<WorkoutSetEntity>,
        exercises: List<ExerciseEntity>,
        muscleFilter: String? = null,
        learnedAliases: Map<String, String> = emptyMap(),
        muscleOverrides: Map<String, String> = emptyMap()
    ): PeriodComparisonReport {
        val exerciseMap = exercises.associateBy { it.id }
        val nameMap = exercises.associate { it.id to it.displayName }
        val eqMap = exercises.associate { it.id to it.equipment }

        fun filterSetsForPeriod(start: Long, end: Long): Pair<Set<Long>, List<WorkoutSetEntity>> {
            val periodSessions = sessions.filter { it.startedAtMillis in start..end }
            val sIds = periodSessions.map { it.id }.toSet()
            val rawSets = allSets.filter { it.sessionId in sIds }

            if (muscleFilter.isNullOrBlank()) {
                return sIds to rawSets
            }

            val normFilter = normalize(muscleFilter)
            val matchingSets = rawSets.filter { s ->
                val ex = exerciseMap[s.exerciseId]
                val m = if (ex != null && ex.muscleGroup.isNotBlank() && ex.muscleGroup != "General") {
                    ex.muscleGroup
                } else {
                    IrisXClassifier.detectMuscleGroup(ex?.displayName ?: "", learnedAliases, muscleOverrides)
                }
                val normM = normalize(m)
                val catKey = IrisXClassifier.mapMuscleToCategory(m)
                normM.contains(normFilter) || normFilter.contains(normM) ||
                    normalize(catKey.displayName).contains(normFilter)
            }
            val matchingSessionIds = matchingSets.map { it.sessionId }.toSet()
            return matchingSessionIds to matchingSets
        }

        val (sessionsA, setsA) = filterSetsForPeriod(periodAStartMillis, periodAEndMillis)
        val (sessionsB, setsB) = filterSetsForPeriod(periodBStartMillis, periodBEndMillis)

        val volA = SportsMetricsCalculator.calculateSessionVolume(setsA, exerciseNamesById = nameMap, equipmentById = eqMap)
        val volB = SportsMetricsCalculator.calculateSessionVolume(setsB, exerciseNamesById = nameMap, equipmentById = eqMap)

        val deltaPercent = if (volA.totalExternalKg > 0.0) {
            (((volB.totalExternalKg - volA.totalExternalKg) / volA.totalExternalKg) * 1000.0).roundToInt() / 10.0
        } else null

        val distA = calculateMuscleDistribution(sessions, allSets, exercises, learnedAliases, muscleOverrides, periodAStartMillis, periodAEndMillis)
        val distB = calculateMuscleDistribution(sessions, allSets, exercises, learnedAliases, muscleOverrides, periodBStartMillis, periodBEndMillis)

        val allMuscles = (distA.sessionsByMuscle.keys + distB.sessionsByMuscle.keys).distinct()
        val muscleDeltas = allMuscles.associateWith { m ->
            (distB.sessionsByMuscle[m] ?: 0) - (distA.sessionsByMuscle[m] ?: 0)
        }

        val quality = when {
            sessionsA.isEmpty() && sessionsB.isEmpty() -> MetricDataQuality.INSUFFICIENT_DATA
            sessionsA.isEmpty() || sessionsB.isEmpty() -> MetricDataQuality.ESTIMATED
            else -> MetricDataQuality.MEASURED
        }

        val scopeLabel = if (!muscleFilter.isNullOrBlank()) " ($muscleFilter)" else ""
        val summary = buildString {
            append("📅 Comparativa$scopeLabel: $periodBLabel vs $periodALabel\n")
            append("• $periodALabel: ${sessionsA.size} sesiones, ${setsA.size} series, ${SportsMetricsCalculator.formatKg(volA.totalExternalKg)}\n")
            append("• $periodBLabel: ${sessionsB.size} sesiones, ${setsB.size} series, ${SportsMetricsCalculator.formatKg(volB.totalExternalKg)}\n")
            if (deltaPercent != null) {
                val sign = if (deltaPercent >= 0) "+" else ""
                append("• Variación de volumen externo: $sign$deltaPercent%")
            } else {
                append("• Sin base previa en '$periodALabel' para calcular porcentaje de variación.")
            }
        }

        return PeriodComparisonReport(
            periodALabel = periodALabel,
            periodBLabel = periodBLabel,
            periodASessions = sessionsA.size,
            periodBSessions = sessionsB.size,
            periodAExternalVolumeKg = volA.totalExternalKg,
            periodBExternalVolumeKg = volB.totalExternalKg,
            volumeDeltaPercent = deltaPercent,
            periodASets = setsA.size,
            periodBSets = setsB.size,
            muscleGroupSessionsDelta = muscleDeltas,
            dataQuality = quality,
            summaryText = summary
        )
    }

    /**
     * Calculates weekly or monthly volume and session trends.
     */
    fun calculateTrends(
        sessions: List<WorkoutSessionEntity>,
        allSets: List<WorkoutSetEntity>,
        exercises: List<ExerciseEntity>,
        bucketDays: Int = 7,
        bucketCount: Int = 4,
        referenceTimeMillis: Long = System.currentTimeMillis()
    ): List<PeriodTrendBucket> {
        if (sessions.isEmpty()) return emptyList()

        val nameMap = exercises.associate { it.id to it.displayName }
        val eqMap = exercises.associate { it.id to it.equipment }
        val setsBySession = allSets.groupBy { it.sessionId }
        val windowMillis = bucketDays * DAY_MILLIS

        val buckets = mutableListOf<PeriodTrendBucket>()
        for (i in (bucketCount - 1) downTo 0) {
            val end = referenceTimeMillis - (i * windowMillis)
            val start = end - windowMillis + 1
            val bucketSessions = sessions.filter { it.startedAtMillis in start..end }
            val bucketSets = bucketSessions.flatMap { setsBySession[it.id].orEmpty() }
            val vol = SportsMetricsCalculator.calculateSessionVolume(
                sets = bucketSets,
                exerciseNamesById = nameMap,
                equipmentById = eqMap
            )
            val label = if (bucketDays == 7) {
                if (i == 0) "Sem. actual" else "Hace ${i}s"
            } else {
                if (i == 0) "Mes actual" else "Hace ${i}m"
            }
            buckets.add(
                PeriodTrendBucket(
                    bucketLabel = label,
                    startMillis = start,
                    endMillis = end,
                    sessionCount = bucketSessions.size,
                    totalSets = bucketSets.size,
                    externalVolumeKg = vol.totalExternalKg,
                    dataQuality = if (bucketSets.isEmpty()) MetricDataQuality.INSUFFICIENT_DATA else vol.dataQuality
                )
            )
        }
        return buckets
    }

    /**
     * Calculates session frequency over a specified window of days.
     */
    fun calculateTrainingFrequency(
        sessions: List<WorkoutSessionEntity>,
        windowDays: Int = 28,
        referenceTimeMillis: Long = System.currentTimeMillis()
    ): TrainingFrequencyReport {
        val cutoff = referenceTimeMillis - (windowDays * DAY_MILLIS)
        val recent = sessions.filter { it.startedAtMillis in cutoff..referenceTimeMillis }
            .sortedBy { it.startedAtMillis }

        if (recent.isEmpty()) {
            return TrainingFrequencyReport(
                totalSessions = 0,
                windowDays = windowDays,
                averageSessionsPerWeek = 0.0,
                averageDaysBetweenSessions = null,
                dataQuality = MetricDataQuality.INSUFFICIENT_DATA,
                summaryText = "Sin sesiones registradas en los últimos $windowDays días."
            )
        }

        val weeks = windowDays / 7.0
        val avgPerWeek = ((recent.size / weeks) * 10.0).roundToInt() / 10.0

        val avgDaysBetween = if (recent.size >= 2) {
            val gaps = recent.zipWithNext { a, b ->
                (b.startedAtMillis - a.startedAtMillis).toDouble() / DAY_MILLIS
            }
            ((gaps.average()) * 10.0).roundToInt() / 10.0
        } else null

        val quality = if (recent.size >= 2) MetricDataQuality.MEASURED else MetricDataQuality.ESTIMATED
        val summary = buildString {
            append("Frecuencia ($windowDays días): ${recent.size} sesiones ($avgPerWeek sesiones/semana)")
            if (avgDaysBetween != null) {
                append(" • Descanso medio: $avgDaysBetween días entre sesiones.")
            }
        }

        return TrainingFrequencyReport(
            totalSessions = recent.size,
            windowDays = windowDays,
            averageSessionsPerWeek = avgPerWeek,
            averageDaysBetweenSessions = avgDaysBetween,
            dataQuality = quality,
            summaryText = summary
        )
    }
}
