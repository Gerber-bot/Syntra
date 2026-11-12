package com.syntra.app.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.ProfileEntity
import com.syntra.app.domain.SportsMetricsCalculator
import com.syntra.app.domain.irisx.ChartDataPoint
import com.syntra.app.domain.irisx.IrisXAnalytics
import com.syntra.app.domain.irisx.IrisXCoach
import com.syntra.app.ui.theme.*
import java.util.Locale

@Composable
fun AnalyticsAndCoachScreen(
    db: AppDatabase,
    profile: ProfileEntity,
    onBack: () -> Unit,
    onOpenRoutines: () -> Unit
) {
    val sessions by remember(db) { db.workoutSessionDao().observeAll() }.collectAsState(initial = emptyList())
    val allSets by remember(db) { db.workoutSetDao().observeAll() }.collectAsState(initial = emptyList())
    val exercises by remember(db) { db.exerciseDao().observeAll() }.collectAsState(initial = emptyList())
    val memories by remember(db) { db.irisxMemoryDao().observeAll() }.collectAsState(initial = emptyList())

    val learnedAliases = remember(memories) {
        memories.filter { it.memoryType == "ALIAS" }.associate { it.sourceKey to it.targetValue }
    }
    val muscleOverrides = remember(memories, exercises) {
        val fromEx = exercises.associate { it.normalizedName to it.muscleGroup }
        val fromMem = memories.filter { it.memoryType == "CLASSIFICATION_OVERRIDE" }.associate { it.sourceKey to it.targetValue }
        fromEx + fromMem
    }
    val confirmedPrefs = remember(memories) {
        memories.filter { it.memoryType == "PREFERENCE" }.associate { it.sourceKey to it.targetValue }
    }

    var activeTab by remember { mutableIntStateOf(0) } // 0: Progresión, 1: Tendencias, 2: IrisX Coach

    val exercisesWithHistory = remember(exercises, allSets) {
        val usedIds = allSets.map { it.exerciseId }.toSet()
        val withSets = exercises.filter { it.id in usedIds }
        if (withSets.isNotEmpty()) withSets else exercises.take(6)
    }

    var selectedExerciseName by remember(exercisesWithHistory) {
        mutableStateOf(exercisesWithHistory.firstOrNull()?.displayName ?: "Sentadilla")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SyntraSecondaryButton(
                    text = "← Inicio",
                    onClick = onBack,
                    modifier = Modifier.height(38.dp)
                )
                Column {
                    Text(
                        text = "IrisX Analytics & Coach",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SyntraColors.TextPrimary
                    )
                    Text(
                        text = "Análisis determinista • Métricas deportivas reales",
                        style = MaterialTheme.typography.labelSmall,
                        color = SyntraColors.SoftMint
                    )
                }
            }
        }

        // Section Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Progresión", "Tendencias", "IrisX Coach").forEachIndexed { index, title ->
                val selected = activeTab == index
                SyntraFilterChip(
                    selected = selected,
                    onClick = { activeTab = index },
                    label = title,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        when (activeTab) {
            0 -> ProgressionTabContent(
                selectedExerciseName = selectedExerciseName,
                onSelectExercise = { selectedExerciseName = it },
                availableExercises = exercisesWithHistory.map { it.displayName },
                sessions = sessions,
                allSets = allSets,
                exercises = exercises,
                learnedAliases = learnedAliases
            )
            1 -> TrendsAndDistributionTabContent(
                sessions = sessions,
                allSets = allSets,
                exercises = exercises,
                learnedAliases = learnedAliases,
                muscleOverrides = muscleOverrides
            )
            2 -> CoachTabContent(
                profile = profile,
                sessions = sessions,
                allSets = allSets,
                exercises = exercises,
                learnedAliases = learnedAliases,
                muscleOverrides = muscleOverrides,
                confirmedPreferences = confirmedPrefs,
                onOpenRoutines = onOpenRoutines
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ProgressionTabContent(
    selectedExerciseName: String,
    onSelectExercise: (String) -> Unit,
    availableExercises: List<String>,
    sessions: List<com.syntra.app.data.WorkoutSessionEntity>,
    allSets: List<com.syntra.app.data.WorkoutSetEntity>,
    exercises: List<com.syntra.app.data.ExerciseEntity>,
    learnedAliases: Map<String, String>
) {
    // Exercise selector chips
    Text(
        text = "Seleccioná un ejercicio:",
        style = MaterialTheme.typography.labelMedium,
        color = Color.Gray
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        availableExercises.forEach { exName ->
            val isSelected = exName == selectedExerciseName
            FilterChip(
                selected = isSelected,
                onClick = { onSelectExercise(exName) },
                label = { Text(exName, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.Black,
                    containerColor = Color(0xFF1A1C1E),
                    labelColor = Color.White
                )
            )
        }
    }

    val report = remember(selectedExerciseName, sessions, allSets, exercises, learnedAliases) {
        IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = selectedExerciseName,
            sessions = sessions,
            allSets = allSets,
            exercises = exercises,
            learnedAliases = learnedAliases
        )
    }

    // Summary & Status Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16181A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = report.exerciseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    color = Color(0xFF24282C),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "${report.status.displayName} • ${report.dataQuality.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (report.dominantModality != null) {
                Text(
                    text = "Modalidad analizada: ${report.dominantModality.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
            }

            Text(
                text = report.summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
        }
    }

    // Load Evolution Line Chart
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141618)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Evolución de carga tope por sesión",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (report.loadChartPoints.isEmpty()) {
                Text(
                    text = "Sin datos suficientes para graficar este ejercicio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            } else {
                DeterministicLineChart(
                    points = report.loadChartPoints,
                    accentColor = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    // Personal Records by Modality Card
    if (report.personalRecordsByModality.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16181A)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🏆 Récords personales (separados por modalidad)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                report.personalRecordsByModality.forEach { (modality, pr) ->
                    val kgStr = SportsMetricsCalculator.formatKg(pr.maxLoadGrams / 1000.0)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E2224), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = modality.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.LightGray
                            )
                            Text(
                                text = "Máx. reps en serie: ${pr.maxRepsInSingleSet} reps (${SportsMetricsCalculator.formatKg(pr.loadAtMaxRepsGrams / 1000.0)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Text(
                            text = "$kgStr × ${pr.repsAtMaxLoad}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendsAndDistributionTabContent(
    sessions: List<com.syntra.app.data.WorkoutSessionEntity>,
    allSets: List<com.syntra.app.data.WorkoutSetEntity>,
    exercises: List<com.syntra.app.data.ExerciseEntity>,
    learnedAliases: Map<String, String>,
    muscleOverrides: Map<String, String>
) {
    val freq = remember(sessions) { IrisXAnalytics.calculateTrainingFrequency(sessions, windowDays = 28) }
    val weeklyTrends = remember(sessions, allSets, exercises) {
        IrisXAnalytics.calculateTrends(sessions, allSets, exercises, bucketDays = 7, bucketCount = 4)
    }
    val dist = remember(sessions, allSets, exercises, learnedAliases, muscleOverrides) {
        IrisXAnalytics.calculateMuscleDistribution(sessions, allSets, exercises, learnedAliases, muscleOverrides)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16181A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Frecuencia de entrenamiento (28 días)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(text = freq.summaryText, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Text(
                text = "Calidad del dato: ${freq.dataQuality.displayName}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141618)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Tendencia semanal de volumen externo (últimas 4 semanas)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (weeklyTrends.isEmpty() || weeklyTrends.all { it.externalVolumeKg == 0.0 }) {
                Text(
                    text = "Sin volumen externo registrado en las últimas 4 semanas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            } else {
                DeterministicBarChart(
                    labels = weeklyTrends.map { it.bucketLabel },
                    values = weeklyTrends.map { it.externalVolumeKg },
                    unit = "kg",
                    barColor = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16181A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Distribución por grupo muscular",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (dist.setsByMuscle.isEmpty()) {
                Text("Sin series registradas para calcular distribución.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                dist.setsByMuscle.entries.sortedByDescending { it.value }.forEach { (muscle, setCount) ->
                    val pct = dist.percentagesBySets[muscle] ?: 0.0
                    val vol = dist.volumeKgByMuscle[muscle] ?: 0.0
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = muscle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                text = "$setCount series ($pct%) • ${SportsMetricsCalculator.formatKg(vol)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color(0xFF262A2E)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoachTabContent(
    profile: ProfileEntity,
    sessions: List<com.syntra.app.data.WorkoutSessionEntity>,
    allSets: List<com.syntra.app.data.WorkoutSetEntity>,
    exercises: List<com.syntra.app.data.ExerciseEntity>,
    learnedAliases: Map<String, String>,
    muscleOverrides: Map<String, String>,
    confirmedPreferences: Map<String, String>,
    onOpenRoutines: () -> Unit
) {
    val recommendations = remember(profile, sessions, allSets, exercises, learnedAliases, muscleOverrides, confirmedPreferences) {
        IrisXCoach.generateRecommendations(
            profile = profile,
            sessions = sessions,
            allSets = allSets,
            exercises = exercises,
            learnedAliases = learnedAliases,
            muscleOverrides = muscleOverrides,
            confirmedPreferences = confirmedPreferences
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121A18)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "IrisX Coach — Motor de Reglas Explícitas",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Cada recomendación expone los datos utilizados, el motivo técnico y sus limitaciones. IrisX no realiza diagnósticos médicos ni modifica entrenamientos sin tu confirmación.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray
            )
        }
    }

    recommendations.forEach { rec ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16181A)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = rec.category.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        color = Color(0xFF24282C),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = rec.certaintyLevel.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Text(
                    text = rec.proposal,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E2224), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "📊 Datos utilizados: ${rec.dataUsed.joinToString(" • ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                    Text(
                        text = "💡 Motivo: ${rec.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                    Text(
                        text = "⚖ Certeza / Limitaciones: ${rec.certaintyAndLimitations}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }

                if (!rec.optionalAction.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "👉 ${rec.optionalAction}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onOpenRoutines) {
                            Text("Ver Rutinas →", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeterministicLineChart(
    points: List<ChartDataPoint>,
    accentColor: Color
) {
    val maxVal = (points.maxOfOrNull { it.value } ?: 1.0).coerceAtLeast(1.0)
    val minVal = (points.minOfOrNull { it.value } ?: 0.0).coerceAtMost(maxVal - 1.0).coerceAtLeast(0.0)
    val range = (maxVal - minVal).coerceAtLeast(1.0)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Color(0xFF1C2024), RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            val w = size.width
            val h = size.height

            if (points.size == 1) {
                drawCircle(
                    color = accentColor,
                    radius = 6.dp.toPx(),
                    center = Offset(w / 2f, h / 2f)
                )
            } else {
                val path = Path()
                val stepX = w / (points.size - 1).coerceAtLeast(1)

                points.forEachIndexed { index, pt ->
                    val normalizedY = ((pt.value - minVal) / range).toFloat()
                    val x = index * stepX
                    val y = h - (normalizedY * h)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(
                    path = path,
                    color = accentColor,
                    style = Stroke(width = 3.dp.toPx())
                )

                points.forEachIndexed { index, pt ->
                    val normalizedY = ((pt.value - minVal) / range).toFloat()
                    val x = index * stepX
                    val y = h - (normalizedY * h)
                    drawCircle(
                        color = accentColor,
                        radius = 4.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val first = points.first()
            val last = points.last()
            Text(
                text = "Inicio (${first.label}): ${String.format(Locale.ROOT, "%.1f", first.value)} ${first.unit}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
            Text(
                text = "Actual (${last.label}): ${String.format(Locale.ROOT, "%.1f", last.value)} ${last.unit}",
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DeterministicBarChart(
    labels: List<String>,
    values: List<Double>,
    unit: String,
    barColor: Color
) {
    val maxVal = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .background(Color(0xFF1C2024), RoundedCornerShape(8.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            val w = size.width
            val h = size.height
            val count = values.size.coerceAtLeast(1)
            val slotWidth = w / count
            val barWidth = (slotWidth * 0.55f).coerceAtLeast(8f)

            values.forEachIndexed { index, value ->
                val ratio = (value / maxVal).toFloat().coerceIn(0.04f, 1f)
                val barHeight = h * ratio
                val left = (index * slotWidth) + (slotWidth - barWidth) / 2f
                val top = h - barHeight
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            labels.zip(values).forEach { (label, v) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(
                        text = "${v.toInt()} $unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
