package com.syntra.app.ui.week

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.IrisxMemoryEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.domain.NextSessionPlan
import com.syntra.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale

@Composable
fun WeekScreen(
    db: AppDatabase,
    nextSessionPlan: NextSessionPlan,
    onNavigateToActiveWorkout: () -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var weekOffset by remember { mutableIntStateOf(0) }
    var showGoalDialog by remember { mutableStateOf(false) }

    val sessions by remember(db) { db.workoutSessionDao().observeAll() }.collectAsState(initial = emptyList())
    val allSets by remember(db) { db.workoutSetDao().observeAll() }.collectAsState(initial = emptyList())
    val memories by remember(db) { db.irisxMemoryDao().observeAll() }.collectAsState(initial = emptyList())

    val weeklyGoal = remember(memories) {
        val pref = memories.firstOrNull { it.memoryType == "PREFERENCE" && it.sourceKey == "weekly_workout_goal" }
        pref?.targetValue?.toIntOrNull() ?: 4
    }

    val (startMillis, endMillis) = remember(weekOffset) { getWeekRange(weekOffset) }

    val setsCountBySession = remember(allSets) {
        allSets.groupBy { it.sessionId }.mapValues { it.value.size }
    }

    // Include completed sessions AND legacy imported sessions that have recorded sets
    val weekSessions = remember(sessions, setsCountBySession, startMillis, endMillis) {
        sessions.filter { s ->
            val isCompleted = s.finishedAtMillis != null || (setsCountBySession[s.id] ?: 0) > 0
            isCompleted && s.startedAtMillis in startMillis..endMillis
        }.sortedByDescending { it.startedAtMillis }
    }

    val totalDurationMillis = remember(weekSessions) {
        weekSessions.sumOf { s ->
            val end = s.finishedAtMillis ?: s.startedAtMillis
            (end - s.startedAtMillis).coerceAtLeast(0L)
        }
    }

    val categoriesTrained = remember(weekSessions) {
        weekSessions.map { it.category.trim().ifEmpty { "General" } }.distinct()
    }

    val weekHeaderLabel = remember(startMillis, endMillis) {
        formatWeekLabel(startMillis, endMillis)
    }

    if (showGoalDialog) {
        var tempGoal by remember { mutableIntStateOf(weeklyGoal) }
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = {
                Text("Objetivo semanal", fontWeight = FontWeight.Bold, color = Color.White)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Definí cuántas sesiones de entrenamiento querés completar por semana.",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { if (tempGoal > 1) tempGoal-- },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("-", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        }
                        Text(
                            text = "$tempGoal",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        OutlinedButton(
                            onClick = { if (tempGoal < 7) tempGoal++ },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("+", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val now = System.currentTimeMillis()
                            val existing = db.irisxMemoryDao().findByTypeAndKey("PREFERENCE", "weekly_workout_goal")
                            db.irisxMemoryDao().insert(
                                IrisxMemoryEntity(
                                    id = existing?.id ?: 0,
                                    memoryType = "PREFERENCE",
                                    sourceKey = "weekly_workout_goal",
                                    targetValue = tempGoal.toString(),
                                    confidence = 1.0,
                                    createdAtMillis = existing?.createdAtMillis ?: now,
                                    updatedAtMillis = now
                                )
                            )
                            showGoalDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Guardar", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showGoalDialog = false }) {
                    Text("Cancelar", color = Color.White)
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Navigation Bar between weeks
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { weekOffset-- },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("← Anterior", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (weekOffset == 0) "Esta semana" else weekHeaderLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (weekOffset != 0) {
                    TextButton(
                        onClick = { weekOffset = 0 },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Volver a hoy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            OutlinedButton(
                onClick = { weekOffset++ },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("Siguiente →", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }

        // Summary Card: Goal & Time
        SyntraCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Progreso semanal",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SyntraColors.TextPrimary
                        )
                        Text(
                            text = "${weekSessions.size} de $weeklyGoal sesiones completadas",
                            style = MaterialTheme.typography.bodySmall,
                            color = SyntraColors.NeonMint
                        )
                    }
                    SyntraBadge(
                        text = "Meta: $weeklyGoal ⚙",
                        containerColor = SyntraColors.SurfaceHighlight,
                        contentColor = SyntraColors.SoftMint
                    )
                }

                val progress = if (weeklyGoal > 0) (weekSessions.size.toFloat() / weeklyGoal.toFloat()).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = SyntraColors.NeonMint,
                    trackColor = SyntraColors.SurfaceHighlight
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Tiempo entrenado", style = MaterialTheme.typography.labelSmall, color = SyntraColors.TextMuted)
                        Text(
                            text = formatDuration(totalDurationMillis),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = SyntraColors.TextPrimary
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Categorías", style = MaterialTheme.typography.labelSmall, color = SyntraColors.TextMuted)
                        Text(
                            text = if (categoriesTrained.isEmpty()) "Ninguna aún" else categoriesTrained.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = SyntraColors.NeonMint
                        )
                    }
                }
            }
        }

        // Suggested Next Session Banner (only on current week)
        if (weekOffset == 0 && nextSessionPlan.nextCategoryName != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF111D1A),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🎯 Próximo turno: ${nextSessionPlan.nextCategoryName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = onNavigateToActiveWorkout,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Entrenar →", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // List of workouts in the week
        if (weekSessions.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Sin sesiones registradas en esta semana",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "IrisX: \"La constancia vence a la intensidad esporádica. Cuando estés listo, arrancá tu entrenamiento.\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    if (weekOffset == 0) {
                        Button(
                            onClick = onNavigateToActiveWorkout,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Iniciar entrenamiento activo", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(weekSessions, key = { it.id }) { session ->
                    WeekSessionCard(
                        session = session,
                        setsCount = setsCountBySession[session.id] ?: 0,
                        onViewInHistory = onNavigateToHistory
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekSessionCard(
    session: WorkoutSessionEntity,
    setsCount: Int,
    onViewInHistory: () -> Unit
) {
    val dayName = remember(session.startedAtMillis) {
        SimpleDateFormat("EEEE d 'de' MMMM", Locale.forLanguageTag("es-AR")).format(Date(session.startedAtMillis)).replaceFirstChar { it.uppercase() }
    }
    val durationText = remember(session.startedAtMillis, session.finishedAtMillis) {
        val dur = ((session.finishedAtMillis ?: session.startedAtMillis) - session.startedAtMillis).coerceAtLeast(0L)
        formatDuration(dur)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = dayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                val showCategoryBadge = session.category.isNotBlank() &&
                    !session.title.equals(session.category, ignoreCase = true) &&
                    !session.title.contains(session.category, ignoreCase = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showCategoryBadge) {
                        Surface(
                            color = Color(0xFF222222),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = session.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "⏱ $durationText • $setsCount series",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                }
            }
            TextButton(onClick = onViewInHistory) {
                Text("Detalle →", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

internal fun computeWeekRange(
    weekOffset: Int,
    referenceDate: LocalDate = LocalDate.now(),
    zoneId: ZoneId = ZoneId.systemDefault()
): Pair<Long, Long> {
    val monday = referenceDate
        .plusWeeks(weekOffset.toLong())
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val sunday = monday.plusDays(6)
    val startMillis = monday.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val endMillis = sunday.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
    return Pair(startMillis, endMillis)
}

private fun getWeekRange(weekOffset: Int): Pair<Long, Long> = computeWeekRange(weekOffset)

private fun formatWeekLabel(startMillis: Long, endMillis: Long): String {
    val sdf = SimpleDateFormat("d MMM", Locale.forLanguageTag("es-AR"))
    return "${sdf.format(Date(startMillis))} – ${sdf.format(Date(endMillis))}"
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes} min"
        else -> "< 1 min"
    }
}
