package com.syntra.app.ui.parser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.syntra.app.domain.ParsedExercise
import com.syntra.app.domain.ParsedSet
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditExercisePreviewDialog(
    exercise: ParsedExercise,
    onDismiss: () -> Unit,
    onSave: (updated: ParsedExercise, learnAlias: Boolean, originalName: String) -> Unit
) {
    var name by remember { mutableStateOf(exercise.name) }
    var plannedTarget by remember { mutableStateOf(exercise.plannedTarget ?: "") }
    val sets = remember {
        mutableStateListOf<MutablePairSet>().apply {
            addAll(exercise.sets.map { MutablePairSet(it.weightGrams / 1000.0, it.repetitions) })
        }
    }
    var learnAlias by remember { mutableStateOf(true) }

    val nameChanged = name.trim().lowercase(Locale.ROOT) != exercise.name.trim().lowercase(Locale.ROOT)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF161616),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Editar ejercicio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Text("✕", color = Color.Gray, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = Color(0xFF2A2A2A))

                // Exercise name field
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del ejercicio") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                // If name changed, offer IrisX alias learning
                if (nameChanged && name.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = learnAlias,
                            onCheckedChange = { learnAlias = it }
                        )
                        Text(
                            text = "Enseñar a IrisX que '${exercise.name}' equivale a '$name'",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Planned target field
                OutlinedTextField(
                    value = plannedTarget,
                    onValueChange = { plannedTarget = it },
                    label = { Text("Objetivo / Plan (ej. 4x10-12, RIR 2)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                // Sets section header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Series (${sets.size})",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = {
                            val lastWeight = sets.lastOrNull()?.weightKg ?: 20.0
                            val lastReps = sets.lastOrNull()?.reps ?: 10
                            sets.add(MutablePairSet(lastWeight, lastReps))
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("+ Agregar serie", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Sets list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(sets) { idx, setItem ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "#${idx + 1}",
                                color = Color.Gray,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(28.dp)
                            )
                            OutlinedTextField(
                                value = if (setItem.weightKg == 0.0) "" else setItem.weightKg.toString().removeSuffix(".0"),
                                onValueChange = { input ->
                                    val parsed = input.toDoubleOrNull() ?: 0.0
                                    setItem.weightKg = parsed
                                },
                                label = { Text("kg") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = if (setItem.reps == 0) "" else setItem.reps.toString(),
                                onValueChange = { input ->
                                    val parsed = input.toIntOrNull() ?: 0
                                    setItem.reps = parsed
                                },
                                label = { Text("reps") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    if (sets.size > 1) {
                                        sets.removeAt(idx)
                                    }
                                },
                                enabled = sets.size > 1
                            ) {
                                Text("🗑", color = if (sets.size > 1) MaterialTheme.colorScheme.error else Color.DarkGray)
                            }
                        }
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancelar", color = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val newSets = sets.map {
                                ParsedSet(
                                    weightGrams = (it.weightKg * 1000).toInt(),
                                    repetitions = it.reps
                                )
                            }
                            val updated = exercise.copy(
                                name = name.trim().ifBlank { exercise.name },
                                sets = newSets,
                                plannedTarget = plannedTarget.trim().takeIf { it.isNotBlank() }
                            )
                            onSave(updated, nameChanged && learnAlias, exercise.name)
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

class MutablePairSet(
    weight: Double,
    repetitions: Int
) {
    var weightKg by mutableStateOf(weight)
    var reps by mutableStateOf(repetitions)
}
