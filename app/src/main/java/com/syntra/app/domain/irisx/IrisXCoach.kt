package com.syntra.app.domain.irisx

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.ProfileEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.LoadModality
import com.syntra.app.domain.MetricDataQuality
import com.syntra.app.domain.SportsMetricsCalculator
import kotlin.math.roundToInt

enum class RecommendationCategory(val displayName: String) {
    LOAD_PROGRESSION("Progresión de carga"),
    STAGNATION_BREAKER("Desbloqueo de estancamiento"),
    MUSCLE_BALANCE("Equilibrio muscular"),
    FREQUENCY_AND_RECOVERY("Frecuencia y recuperación"),
    GOAL_ALIGNMENT("Alineación con objetivo personal"),
    DATA_COLLECTION("Datos iniciales requeridos")
}

/**
 * Verified deterministic recommendation structure.
 * Every recommendation includes the exact proposal, data used, reason, certainty/limitations,
 * and an optional action that never modifies data without explicit confirmation.
 */
data class CoachRecommendation(
    val id: String,
    val category: RecommendationCategory,
    val proposal: String,
    val dataUsed: List<String>,
    val reason: String,
    val certaintyLevel: MetricDataQuality,
    val certaintyAndLimitations: String,
    val optionalAction: String? = null
) {
    fun formatForDisplay(): String = buildString {
        append("🎯 Propuesta: $proposal\n")
        append("📊 Datos utilizados: ${dataUsed.joinToString("; ")}\n")
        append("💡 Motivo: $reason\n")
        append("⚖ Certeza y limitaciones [${certaintyLevel.displayName}]: $certaintyAndLimitations")
        if (!optionalAction.isNullOrBlank()) {
            append("\n👉 Acción opcional: $optionalAction")
        }
    }
}

object IrisXCoach {

    private const val DAY_MILLIS = 86_400_000L

    /**
     * Generates deterministic, rule-based sports recommendations based on history, profile goals,
     * training frequency, load/rep evolution, volume distribution, and elapsed recovery time.
     * Never emits medical diagnoses or assumes fatigue from isolated rep drops.
     */
    fun generateRecommendations(
        profile: ProfileEntity?,
        sessions: List<WorkoutSessionEntity>,
        allSets: List<WorkoutSetEntity>,
        exercises: List<ExerciseEntity>,
        learnedAliases: Map<String, String> = emptyMap(),
        muscleOverrides: Map<String, String> = emptyMap(),
        confirmedPreferences: Map<String, String> = emptyMap(),
        targetExerciseOrMuscle: String? = null,
        referenceTimeMillis: Long = System.currentTimeMillis()
    ): List<CoachRecommendation> {
        val recommendations = mutableListOf<CoachRecommendation>()
        val goal = confirmedPreferences["objetivo"] ?: profile?.goal ?: "Seguimiento general"

        if (sessions.isEmpty() || allSets.isEmpty()) {
            recommendations.add(
                CoachRecommendation(
                    id = "rec_initial_data",
                    category = RecommendationCategory.DATA_COLLECTION,
                    proposal = "Registrar al menos 2 a 3 sesiones completas para habilitar recomendaciones de progresión.",
                    dataUsed = listOf("Sesiones registradas: 0", "Objetivo en perfil: $goal"),
                    reason = "El motor determinista requiere historial medido para calcular tendencias sin inventar estimaciones.",
                    certaintyLevel = MetricDataQuality.INSUFFICIENT_DATA,
                    certaintyAndLimitations = "Datos insuficientes. No existen registros previos en SQLite.",
                    optionalAction = "Podés usar el Analizador o aplicar una rutina desde la pestaña Rutinas."
                )
            )
            return recommendations
        }

        // 1. Frequency and recovery analysis (last 28 days)
        val freqReport = IrisXAnalytics.calculateTrainingFrequency(
            sessions = sessions,
            windowDays = 28,
            referenceTimeMillis = referenceTimeMillis
        )
        val latestSession = sessions.maxByOrNull { it.startedAtMillis }
        val daysSinceLastSession = latestSession?.let {
            ((referenceTimeMillis - it.startedAtMillis).toDouble() / DAY_MILLIS).roundToInt().coerceAtLeast(0)
        }

        if (targetExerciseOrMuscle == null) {
            when {
                freqReport.totalSessions in 1..3 -> {
                    recommendations.add(
                        CoachRecommendation(
                            id = "rec_frequency_low",
                            category = RecommendationCategory.FREQUENCY_AND_RECOVERY,
                            proposal = "Incrementar gradualmente la constancia hacia 2–3 sesiones por semana según disponibilidad.",
                            dataUsed = listOf(
                                "${freqReport.totalSessions} sesiones en los últimos 28 días (${freqReport.averageSessionsPerWeek}/sem)",
                                "Objetivo declarado: $goal",
                                "Días desde la última sesión: ${daysSinceLastSession ?: 0}"
                            ),
                            reason = "Una frecuencia menor a 1 sesión semanal limita la adaptación neuromuscular y el seguimiento de sobrecarga progresiva.",
                            certaintyLevel = freqReport.dataQuality,
                            certaintyAndLimitations = "Certeza media: basado en registros de las últimas 4 semanas. No contempla actividades fuera de la app.",
                            optionalAction = "Planificar una rutina Full Body o Torso/Pierna en la sección Rutinas."
                        )
                    )
                }
                daysSinceLastSession != null && daysSinceLastSession == 0 && freqReport.averageSessionsPerWeek >= 5.0 -> {
                    recommendations.add(
                        CoachRecommendation(
                            id = "rec_recovery_window",
                            category = RecommendationCategory.FREQUENCY_AND_RECOVERY,
                            proposal = "Verificar ventana de recuperación (24–48 h para los mismos grupos musculares) antes de repetir los músculos trabajados hoy.",
                            dataUsed = listOf(
                                "Frecuencia actual: ${freqReport.averageSessionsPerWeek} sesiones/semana",
                                "Última sesión registrada hace menos de 24 horas (${latestSession.title})"
                            ),
                            reason = "Con alta frecuencia semanal, alternar grupos musculares evita solapar estrés mecánico local.",
                            certaintyLevel = MetricDataQuality.ESTIMATED,
                            certaintyAndLimitations = "Estimación basada solo en fechas de sesiones. Syntra no mide sueño ni marcadores fisiológicos ni emite diagnósticos.",
                            optionalAction = "Seleccionar un grupo muscular distinto para la próxima sesión."
                        )
                    )
                }
            }
        }

        // 2. Muscle balance analysis (last 28 days)
        val cutoff28d = referenceTimeMillis - (28 * DAY_MILLIS)
        val dist = IrisXAnalytics.calculateMuscleDistribution(
            sessions = sessions,
            allSets = allSets,
            exercises = exercises,
            learnedAliases = learnedAliases,
            muscleOverrides = muscleOverrides,
            sinceMillis = cutoff28d,
            untilMillis = referenceTimeMillis
        )

        if (dist.totalAnalyzedSets >= 8 && targetExerciseOrMuscle == null) {
            val pechoSets = dist.setsByMuscle["Pecho"] ?: 0
            val espaldaSets = dist.setsByMuscle["Espalda"] ?: 0
            val piernasSets = (dist.setsByMuscle["Piernas"] ?: 0) + (dist.setsByMuscle["Glúteos"] ?: 0)

            if (pechoSets >= 6 && espaldaSets * 2 < pechoSets) {
                recommendations.add(
                    CoachRecommendation(
                        id = "rec_balance_pull_push",
                        category = RecommendationCategory.MUSCLE_BALANCE,
                        proposal = "Añadir entre 3 y 6 series semanales de tracción (Espalda) para equilibrar la relación empuje/tirón.",
                        dataUsed = listOf(
                            "Series de Pecho (28d): $pechoSets",
                            "Series de Espalda (28d): $espaldaSets",
                            "Total series analizadas: ${dist.totalAnalyzedSets}"
                        ),
                        reason = "El volumen de empuje horizontal duplica con creces al de tracción, lo que descompensa el trabajo del torso.",
                        certaintyLevel = MetricDataQuality.MEASURED,
                        certaintyAndLimitations = "Medido sobre series registradas en los últimos 28 días.",
                        optionalAction = "Incluir Remo con barra o Jalón al pecho en tu próxima sesión de torso."
                    )
                )
            }

            if (piernasSets == 0 && dist.totalAnalyzedSessions >= 3) {
                recommendations.add(
                    CoachRecommendation(
                        id = "rec_balance_legs",
                        category = RecommendationCategory.MUSCLE_BALANCE,
                        proposal = "Incorporar al menos 1 sesión o bloque de tren inferior (Piernas) en la semana.",
                        dataUsed = listOf(
                            "Sesiones últimos 28d: ${dist.totalAnalyzedSessions}",
                            "Series de Piernas/Glúteos registradas: 0"
                        ),
                        reason = "No se registraron series para tren inferior en las últimas ${dist.totalAnalyzedSessions} sesiones.",
                        certaintyLevel = MetricDataQuality.MEASURED,
                        certaintyAndLimitations = "Medido sobre el historial de sesiones guardado.",
                        optionalAction = "Crear o aplicar una rutina de Piernas."
                    )
                )
            }
        }

        // 3. Exercise-specific progression & stagnation recommendations
        val exerciseIdsInHistory = allSets.map { it.exerciseId }.distinct()
        val candidateExercises = exercises.filter { it.id in exerciseIdsInHistory }
            .filter { ex ->
                if (targetExerciseOrMuscle.isNullOrBlank()) true
                else {
                    val normTarget = IrisXAnalytics.normalize(targetExerciseOrMuscle)
                    val normName = IrisXAnalytics.normalize(ex.displayName)
                    val normMuscle = IrisXAnalytics.normalize(ex.muscleGroup)
                    normName == normTarget || normMuscle == normTarget ||
                        learnedAliases[normTarget] == ex.displayName ||
                        learnedAliases[normTarget] == ex.normalizedName
                }
            }

        for (ex in candidateExercises) {
            val report = IrisXAnalytics.analyzeExerciseProgression(
                exerciseName = ex.displayName,
                sessions = sessions,
                allSets = allSets,
                exercises = exercises,
                learnedAliases = learnedAliases,
                targetExerciseId = ex.id
            )
            val modality = report.dominantModality ?: continue
            val modHistory = report.history.filter { it.modality == modality }
            if (modHistory.size < 2) continue

            val lastPoint = modHistory.last()
            val prevPoint = modHistory[modHistory.size - 2]

            // If units cannot be converted, or distinct equipments are involved without confirmed equivalence, skip progression
            if (!IrisXAnalytics.areUnitsConvertible(lastPoint.unit, prevPoint.unit)) continue
            if (!lastPoint.equipment.isNullOrBlank() && !prevPoint.equipment.isNullOrBlank() &&
                !lastPoint.equipment.trim().equals(prevPoint.equipment.trim(), ignoreCase = true)) continue

            val lastLoadStr = com.syntra.app.domain.UnitConverter.formatDisplay(lastPoint.topLoadGrams, lastPoint.unit)
            val prevLoadStr = com.syntra.app.domain.UnitConverter.formatDisplay(prevPoint.topLoadGrams, prevPoint.unit)

            // Case A: Stagnation detected (>= 3 sessions without improvement)
            if (report.stagnation.isStagnant) {
                val microIncStr = when {
                    modality == LoadModality.MACHINE_BLOCKS || lastPoint.unit in listOf("BLOQUES", "PLACAS") -> "+1 bloque/placa"
                    lastPoint.unit == "LBS" -> "+2,5 a +5 lb"
                    else -> "+1,25 a +2,5 kg"
                }
                recommendations.add(
                    CoachRecommendation(
                        id = "rec_stagnation_${ex.id}",
                        category = RecommendationCategory.STAGNATION_BREAKER,
                        proposal = "En ${ex.displayName}, mantener la carga de $lastLoadStr buscando sumar +1 repetición en la primera serie, o realizar un micro-incremento de $microIncStr dentro del rango objetivo.",
                        dataUsed = listOf(
                            "Ejercicio: ${ex.displayName} (${modality.displayName})",
                            "Últimas ${report.stagnation.consecutiveSessionsWithoutProgress} sesiones estables en $lastLoadStr × ${lastPoint.topLoadReps} reps",
                            "Objetivo personal: $goal"
                        ),
                        reason = report.stagnation.explanation,
                        certaintyLevel = report.dataQuality,
                        certaintyAndLimitations = "Regla determinista sobre ${modHistory.size} sesiones de igual modalidad. No asume fatiga sistémica sin datos de recuperación.",
                        optionalAction = "Ajustar objetivo en rutina para ${ex.displayName} (requiere confirmación)."
                    )
                )
                continue
            }

            // Case B: Solid progression / hitting >= 10-12 reps consistently
            if (lastPoint.topLoadReps >= 10 && prevPoint.topLoadReps >= 10 &&
                lastPoint.topLoadGrams == prevPoint.topLoadGrams &&
                modality != LoadModality.BODYWEIGHT && modality != LoadModality.ASSISTED_BODYWEIGHT
            ) {
                val nextLoadGrams = when {
                    modality == LoadModality.MACHINE_BLOCKS || lastPoint.unit in listOf("BLOQUES", "PLACAS") -> {
                        lastPoint.topLoadGrams + 1000 // 1 block / 1 placa
                    }
                    lastPoint.unit == "LBS" -> {
                        lastPoint.topLoadGrams + com.syntra.app.domain.UnitConverter.toGrams(5.0, "LBS")
                    }
                    modality == LoadModality.DUMBBELL_PER_ARM || modality == LoadModality.UNILATERAL -> {
                        lastPoint.topLoadGrams + 1000
                    }
                    else -> {
                        lastPoint.topLoadGrams + 2500
                    }
                }
                val nextLoadStr = com.syntra.app.domain.UnitConverter.formatDisplay(nextLoadGrams, lastPoint.unit)
                recommendations.add(
                    CoachRecommendation(
                        id = "rec_prog_${ex.id}",
                        category = RecommendationCategory.LOAD_PROGRESSION,
                        proposal = "Probar incremento progresivo en ${ex.displayName} a $nextLoadStr manteniendo técnica controlada (rango 8–10 reps).",
                        dataUsed = listOf(
                            "Dos sesiones alcanzando ≥10 reps con $lastLoadStr (${prevPoint.topLoadReps} y ${lastPoint.topLoadReps} reps)",
                            "Modalidad: ${modality.displayName}",
                            "Objetivo: $goal"
                        ),
                        reason = "Se consolidó el extremo superior de repeticiones con la misma carga en sesiones consecutivas.",
                        certaintyLevel = MetricDataQuality.MEASURED,
                        certaintyAndLimitations = "Basado en registros medidos de carga y repeticiones. Sujeto a sensación de esfuerzo percibido (RIR) del día.",
                        optionalAction = "Actualizar carga sugerida de ${ex.displayName} a $nextLoadStr en tu rutina."
                    )
                )
            } else if (report.status == ProgressionStatus.REGRESSING) {
                recommendations.add(
                    CoachRecommendation(
                        id = "rec_consolidate_${ex.id}",
                        category = RecommendationCategory.LOAD_PROGRESSION,
                        proposal = "Consolidar la carga actual ($lastLoadStr) en ${ex.displayName} durante la próxima sesión antes de subir peso.",
                        dataUsed = listOf(
                            "Sesión previa: $prevLoadStr × ${prevPoint.topLoadReps} reps",
                            "Última sesión: $lastLoadStr × ${lastPoint.topLoadReps} reps"
                        ),
                        reason = "Se observó una disminución puntual de rendimiento respecto a la sesión inmediata anterior.",
                        certaintyLevel = MetricDataQuality.ESTIMATED,
                        certaintyAndLimitations = "Limitación importante: una baja puntual de repeticiones o carga NO demuestra fatiga muscular acumulada (puede deberse al orden de ejercicios o descanso entre series). No constituye diagnóstico médico.",
                        optionalAction = "Mantener carga de $lastLoadStr en la siguiente sesión."
                    )
                )
            }
        }

        // If no specific exercise matched or produced a rule, add a goal alignment recommendation
        if (recommendations.isEmpty()) {
            recommendations.add(
                CoachRecommendation(
                    id = "rec_goal_general",
                    category = RecommendationCategory.GOAL_ALIGNMENT,
                    proposal = "Continuar registrando series con cargas y repeticiones para evaluar progresión por bloque.",
                    dataUsed = listOf(
                        "Sesiones totales: ${sessions.size}",
                        "Series totales: ${allSets.size}",
                        "Objetivo activo: $goal"
                    ),
                    reason = "Las sesiones actuales muestran progresión estable sin alertas de estancamiento ni desbalance crítico.",
                    certaintyLevel = MetricDataQuality.MEASURED,
                    certaintyAndLimitations = "Basado en ${sessions.size} sesiones registradas localmente.",
                    optionalAction = null
                )
            )
        }

        return recommendations
    }
}
