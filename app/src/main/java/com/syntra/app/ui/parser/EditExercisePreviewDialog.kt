package com.syntra.app.ui.parser

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.syntra.app.domain.ParsedExercise
import com.syntra.app.domain.ParsedSet
import com.syntra.app.domain.UnitConverter
import com.syntra.app.ui.theme.*
import java.util.Locale

class MutablePairSet(
    weight: Double,
    repetitions: Int,
    unit: String = "KG",
    toFailure: Boolean = false,
    note: String = "",
    isAssisted: Boolean = false,
    isDumbbell: Boolean = false
) {
    var loadText by mutableStateOf(if (unit.uppercase() == "BW") "" else UnitConverter.formatRawForInput(weight))
    var repsText by mutableStateOf(if (repetitions <= 0) "" else repetitions.toString())
    var unit by mutableStateOf(unit)
    var toFailure by mutableStateOf(toFailure)
    var note by mutableStateOf(note)
    var isAssisted by mutableStateOf(isAssisted)
    var isDumbbell by mutableStateOf(isDumbbell)

    val parsedLoad: Double?
        get() = if (unit.uppercase() == "BW") 0.0 else loadText.trim().replace(',', '.').toDoubleOrNull()

    val isLoadValid: Boolean
        get() = unit.uppercase() == "BW" || (parsedLoad != null && UnitConverter.isValidLoad(parsedLoad!!, unit))

    val parsedReps: Int?
        get() = repsText.trim().toIntOrNull()

    val isRepsValid: Boolean
        get() = parsedReps != null && parsedReps!! in 1..200
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditExercisePreviewDialog(
    exercise: ParsedExercise,
    onDismiss: () -> Unit,
    onSave: (updated: ParsedExercise, learnAlias: Boolean, originalName: String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(exercise.name) }
    var plannedTarget by remember { mutableStateOf(exercise.plannedTarget ?: "") }
    val sets = remember {
        mutableStateListOf<MutablePairSet>().apply {
            if (exercise.sets.isEmpty()) {
                add(MutablePairSet(20.0, 10, "KG", false))
            } else {
                addAll(exercise.sets.map {
                    MutablePairSet(
                        weight = it.rawLoadValue,
                        repetitions = it.repetitions,
                        unit = it.loadUnit,
                        toFailure = it.isToFailure,
                        note = it.note,
                        isAssisted = it.isAssisted,
                        isDumbbell = it.isDumbbell
                    )
                })
            }
        }
    }
    var learnAlias by remember { mutableStateOf(true) }

    val nameChanged = name.trim().lowercase(Locale.ROOT) != exercise.name.trim().lowercase(Locale.ROOT)
    val isCreatingNew = exercise.name.isBlank()
    val availableUnits = listOf("KG", "BLOQUES", "PLACAS", "LBS", "BW")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .imePadding(),
            shape = RoundedCornerShape(20.dp),
            color = SyntraColors.SurfaceDark,
            border = BorderStroke(1.dp, SyntraColors.BorderSubtle)
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
                        text = if (isCreatingNew) "Agregar ejercicio" else "Editar ejercicio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SyntraColors.TextPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Text("✕", color = SyntraColors.TextMuted, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = SyntraColors.BorderSubtle)

                // Exercise name field
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del ejercicio (ej. Femoral sentado)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SyntraColors.NeonMint,
                        unfocusedBorderColor = SyntraColors.BorderSubtle,
                        focusedTextColor = SyntraColors.TextPrimary,
                        unfocusedTextColor = SyntraColors.TextPrimary
                    )
                )

                // If name changed on existing exercise, offer IrisX alias learning
                if (!isCreatingNew && nameChanged && name.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = learnAlias,
                            onCheckedChange = { learnAlias = it },
                            colors = CheckboxDefaults.colors(checkedColor = SyntraColors.NeonMint)
                        )
                        Text(
                            text = "Enseñar a IrisX que '${exercise.name}' equivale a '$name'",
                            style = MaterialTheme.typography.bodySmall,
                            color = SyntraColors.SoftMint
                        )
                    }
                }

                // Planned target field
                OutlinedTextField(
                    value = plannedTarget,
                    onValueChange = { plannedTarget = it },
                    label = { Text("Objetivo / Plan (ej. 3 de 10 a 12)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SyntraColors.NeonMint,
                        unfocusedBorderColor = SyntraColors.BorderSubtle,
                        focusedTextColor = SyntraColors.TextPrimary,
                        unfocusedTextColor = SyntraColors.TextPrimary
                    )
                )

                // Sets section header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Series ejecutadas (${sets.size})",
                        fontWeight = FontWeight.Bold,
                        color = SyntraColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    SyntraSecondaryButton(
                        text = "+ Serie",
                        onClick = {
                            val lastWeight = sets.lastOrNull()?.parsedLoad ?: 20.0
                            val lastReps = sets.lastOrNull()?.parsedReps ?: 10
                            val lastUnit = sets.lastOrNull()?.unit ?: "KG"
                            sets.add(MutablePairSet(lastWeight, lastReps, lastUnit, false))
                        },
                        modifier = Modifier.height(34.dp)
                    )
                }

                // Sets list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(sets) { idx, setItem ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SyntraColors.SurfaceCardElevated,
                            border = BorderStroke(1.dp, SyntraColors.BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "#${idx + 1}",
                                    color = SyntraColors.SoftMint,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(22.dp)
                                )

                                // Load field
                                if (setItem.unit != "BW") {
                                    OutlinedTextField(
                                        value = setItem.loadText,
                                        onValueChange = { setItem.loadText = it },
                                        label = { Text("Carga") },
                                        isError = !setItem.isLoadValid,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        singleLine = true,
                                        modifier = Modifier.weight(1.1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = SyntraColors.NeonMint,
                                            unfocusedBorderColor = if (!setItem.isLoadValid) SyntraColors.Error else SyntraColors.BorderSubtle,
                                            errorBorderColor = SyntraColors.Error
                                        )
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.weight(1.1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Corporal", style = MaterialTheme.typography.labelSmall, color = SyntraColors.NeonMint)
                                    }
                                }

                                // Unit selector button (cycles through units on tap)
                                Button(
                                    onClick = {
                                        val nextIdx = (availableUnits.indexOf(setItem.unit) + 1) % availableUnits.size
                                        setItem.unit = availableUnits[nextIdx]
                                        if (setItem.unit == "BW") setItem.loadText = ""
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = SyntraColors.SurfaceHighlight),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = setItem.unit.lowercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = SyntraColors.NeonMint
                                    )
                                }

                                // Reps field
                                OutlinedTextField(
                                    value = setItem.repsText,
                                    onValueChange = { setItem.repsText = it },
                                    label = { Text("Reps") },
                                    isError = !setItem.isRepsValid,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1.0f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = SyntraColors.NeonMint,
                                        unfocusedBorderColor = if (!setItem.isRepsValid) SyntraColors.Error else SyntraColors.BorderSubtle,
                                        errorBorderColor = SyntraColors.Error
                                    )
                                )

                                // Mancuerna toggle chip
                                FilterChip(
                                    selected = setItem.isDumbbell,
                                    onClick = { setItem.isDumbbell = !setItem.isDumbbell },
                                    label = { Text("manc", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SyntraColors.DarkMint,
                                        selectedLabelColor = SyntraColors.NeonMint,
                                        containerColor = SyntraColors.SurfaceHighlight,
                                        labelColor = SyntraColors.TextMuted
                                    ),
                                    border = BorderStroke(1.dp, if (setItem.isDumbbell) SyntraColors.NeonMint else SyntraColors.BorderSubtle)
                                )

                                // Asistida toggle chip
                                FilterChip(
                                    selected = setItem.isAssisted,
                                    onClick = { setItem.isAssisted = !setItem.isAssisted },
                                    label = { Text("asist", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SyntraColors.DarkMint,
                                        selectedLabelColor = SyntraColors.NeonMint,
                                        containerColor = SyntraColors.SurfaceHighlight,
                                        labelColor = SyntraColors.TextMuted
                                    ),
                                    border = BorderStroke(1.dp, if (setItem.isAssisted) SyntraColors.NeonMint else SyntraColors.BorderSubtle)
                                )

                                // Fallo toggle button
                                FilterChip(
                                    selected = setItem.toFailure,
                                    onClick = { setItem.toFailure = !setItem.toFailure },
                                    label = { Text("f", fontWeight = FontWeight.Bold) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SyntraColors.DarkMint,
                                        selectedLabelColor = SyntraColors.NeonMint,
                                        containerColor = SyntraColors.SurfaceHighlight,
                                        labelColor = SyntraColors.TextMuted
                                    ),
                                    border = BorderStroke(1.dp, if (setItem.toFailure) SyntraColors.NeonMint else SyntraColors.BorderSubtle)
                                )

                                IconButton(
                                    onClick = {
                                        if (sets.size > 1) {
                                            sets.removeAt(idx)
                                        }
                                    },
                                    enabled = sets.size > 1,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("🗑", color = if (sets.size > 1) SyntraColors.Error else Color.DarkGray, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onDelete != null && !isCreatingNew) {
                        OutlinedButton(
                            onClick = {
                                onDelete()
                                onDismiss()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SyntraColors.Error),
                            border = BorderStroke(1.dp, SyntraColors.Error)
                        ) {
                            Text("Eliminar", color = SyntraColors.Error, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    val allSetsValid = sets.isNotEmpty() && sets.all { it.isLoadValid && it.isRepsValid }
                    val canSave = allSetsValid && (isCreatingNew || name.isNotBlank())

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SyntraSecondaryButton(
                            text = "Cancelar",
                            onClick = onDismiss
                        )
                        SyntraButton(
                            text = if (isCreatingNew) "Agregar" else "Guardar cambios",
                            enabled = canSave,
                            onClick = {
                                if (!canSave) return@SyntraButton
                                val newSets = sets.map {
                                    val safeLoad = it.parsedLoad ?: 0.0
                                    val safeReps = it.parsedReps ?: 1
                                    val grams = UnitConverter.toGrams(safeLoad, it.unit)
                                    ParsedSet(
                                        weightGrams = grams,
                                        repetitions = safeReps,
                                        isAssisted = it.isAssisted,
                                        isDumbbell = it.isDumbbell,
                                        isToFailure = it.toFailure,
                                        loadUnit = it.unit,
                                        rawLoadValue = safeLoad,
                                        note = it.note.trim()
                                    )
                                }
                                val updated = exercise.copy(
                                    name = name.trim().ifBlank { if (isCreatingNew) "Ejercicio manual" else exercise.name },
                                    sets = newSets,
                                    plannedTarget = plannedTarget.trim().takeIf { it.isNotBlank() }
                                )
                                onSave(updated, !isCreatingNew && nameChanged && learnAlias, exercise.name)
                            }
                        )
                    }
                }
            }
        }
    }
}
