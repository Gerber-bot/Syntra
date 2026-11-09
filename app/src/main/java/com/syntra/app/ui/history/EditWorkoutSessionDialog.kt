package com.syntra.app.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.room.withTransaction
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditWorkoutSessionDialog(
    session: WorkoutSessionEntity,
    db: AppDatabase,
    exerciseNameMap: Map<Long, String>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(session.title) }
    var category by remember { mutableStateOf(session.category) }
    var notes by remember { mutableStateOf(session.notes) }

    val categories by remember(db) { db.categoryDao().observeAllActive() }.collectAsState(initial = emptyList())
    val setsFlow = remember(session.id) { db.workoutSetDao().observeForSession(session.id) }
    val initialSets by setsFlow.collectAsState(initial = emptyList())

    var editableSets by remember { mutableStateOf<List<WorkoutSetEntity>>(emptyList()) }
    var setsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(initialSets) {
        if (!setsLoaded && initialSets.isNotEmpty()) {
            editableSets = initialSets
            setsLoaded = true
        }
    }

    val dateFormatted = remember(session.startedAtMillis) {
        SimpleDateFormat("d 'de' MMMM yyyy, HH:mm", Locale.forLanguageTag("es-AR")).format(Date(session.startedAtMillis))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF141414)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Editar sesión",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = dateFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Text("✕", color = Color.Gray, style = MaterialTheme.typography.titleMedium)
                    }
                }

                // Title input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título de la sesión") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Category selector chips
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Categoría organizativa:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val availableCategoryNames = categories.map { it.name }.ifEmpty {
                            listOf("Pecho", "Espalda", "Piernas", "Hombros", "Brazos", "Full Body")
                        }
                        availableCategoryNames.take(5).forEach { catName ->
                            val isSelected = category.equals(catName, ignoreCase = true)
                            FilterChip(
                                selected = isSelected,
                                onClick = { category = if (isSelected) "" else catName },
                                label = { Text(catName, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }
                }

                // Notes input
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notas de la sesión") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                // Sets List
                Text(
                    text = "Series registradas (${editableSets.size}):",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(editableSets, key = { _, s -> s.id }) { index, s ->
                        val exName = exerciseNameMap[s.exerciseId] ?: "Ejercicio #${s.exerciseId}"
                        val loadKg = s.loadGrams / 1000.0
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "${index + 1}. $exName",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${String.format("%.1f", loadKg)} kg • ${s.repetitions} reps${if (s.isDumbbell) " (mancuernas)" else ""}${if (s.isAssisted) " (asistida)" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.LightGray
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        editableSets = editableSets.filterIndexed { i, _ -> i != index }
                                    }
                                ) {
                                    Text("🗑", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancelar", color = Color.White)
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                db.withTransaction {
                                    db.workoutSessionDao().update(
                                        session.copy(
                                            title = title.trim().ifEmpty { "Entrenamiento" },
                                            category = category.trim(),
                                            notes = notes.trim()
                                        )
                                    )
                                    // If sets were removed, delete them from db
                                    val currentSetIds = editableSets.map { it.id }.toSet()
                                    initialSets.forEach { origSet ->
                                        if (origSet.id !in currentSetIds) {
                                            db.workoutSetDao().delete(origSet)
                                        }
                                    }
                                }
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Guardar cambios", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
