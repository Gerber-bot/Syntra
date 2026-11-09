package com.syntra.app.ui.exercises

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.ExerciseEntity
import com.syntra.app.domain.WorkoutTextParser
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseLibraryDialog(
    db: AppDatabase,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val exercises by remember(db) { db.exerciseDao().observeAll() }.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var selectedMuscleFilter by remember { mutableStateOf("Todos") }
    var exerciseToEdit by remember { mutableStateOf<ExerciseEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val muscleFilters = listOf("Todos", "Piernas", "Pecho", "Espalda", "Hombros", "Brazos", "Glúteos", "General")

    val filteredExercises = remember(exercises, searchQuery, selectedMuscleFilter) {
        exercises.filter { ex ->
            val matchesSearch = searchQuery.isBlank() ||
                    ex.displayName.contains(searchQuery, ignoreCase = true) ||
                    ex.secondaryMuscleGroups.contains(searchQuery, ignoreCase = true) ||
                    (ex.equipment?.contains(searchQuery, ignoreCase = true) == true)
            val matchesMuscle = selectedMuscleFilter == "Todos" ||
                    ex.muscleGroup.equals(selectedMuscleFilter, ignoreCase = true)
            matchesSearch && matchesMuscle
        }
    }

    if (showAddDialog) {
        AddExerciseDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, muscle, secondary, equipment ->
                scope.launch {
                    val norm = WorkoutTextParser.normalize(name)
                    db.exerciseDao().insert(
                        ExerciseEntity(
                            displayName = name.trim(),
                            normalizedName = norm,
                            muscleGroup = muscle.trim(),
                            secondaryMuscleGroups = secondary.trim(),
                            equipment = equipment?.takeIf { it.isNotBlank() },
                            status = "CONFIRMED"
                        )
                    )
                    showAddDialog = false
                }
            }
        )
    }

    if (exerciseToEdit != null) {
        val ex = exerciseToEdit!!
        EditExerciseDialog(
            exercise = ex,
            onDismiss = { exerciseToEdit = null },
            onSave = { updated ->
                scope.launch {
                    db.exerciseDao().update(updated)
                    exerciseToEdit = null
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF141414),
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
                    Column {
                        Text(
                            text = "Biblioteca de Ejercicios",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${filteredExercises.size} de ${exercises.size} ejercicios",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("+ Nuevo", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = onDismiss) {
                            Text("✕", color = Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Buscar por nombre, músculo o equipo...", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF333333),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                // Muscle filter chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    muscleFilters.forEach { muscle ->
                        FilterChip(
                            selected = selectedMuscleFilter == muscle,
                            onClick = { selectedMuscleFilter = muscle },
                            label = { Text(muscle, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF1E1E1E),
                                labelColor = Color.LightGray
                            )
                        )
                    }
                }

                // Exercise list
                if (filteredExercises.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No se encontraron ejercicios con ese criterio.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredExercises, key = { it.id }) { ex ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1C)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ex.displayName,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                color = Color(0xFF283230),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = ex.muscleGroup,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            if (ex.equipment != null) {
                                                Text(
                                                    text = ex.equipment,
                                                    color = Color.Gray,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                            if (ex.status == "PENDING_CONFIRMATION") {
                                                Surface(
                                                    color = Color(0xFF3E3615),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "Pendiente",
                                                        color = Color(0xFFFFD54F),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    IconButton(onClick = { exerciseToEdit = ex }) {
                                        Text("✎", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddExerciseDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, muscle: String, secondary: String, equipment: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var muscle by remember { mutableStateOf("Pecho") }
    var secondary by remember { mutableStateOf("") }
    var equipment by remember { mutableStateOf("") }

    val muscleOptions = listOf("Pecho", "Espalda", "Piernas", "Hombros", "Brazos", "Glúteos", "General")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Nuevo ejercicio", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del ejercicio") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Grupo muscular principal:", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    muscleOptions.forEach { opt ->
                        FilterChip(
                            selected = muscle == opt,
                            onClick = { muscle = opt },
                            label = { Text(opt, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = secondary,
                    onValueChange = { secondary = it },
                    label = { Text("Músculos secundarios (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = equipment,
                    onValueChange = { equipment = it },
                    label = { Text("Equipamiento (Barra, Mancuerna, etc.)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSave(name, muscle, secondary, equipment) },
                enabled = name.isNotBlank()
            ) {
                Text("Guardar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun EditExerciseDialog(
    exercise: ExerciseEntity,
    onDismiss: () -> Unit,
    onSave: (ExerciseEntity) -> Unit
) {
    var displayName by remember { mutableStateOf(exercise.displayName) }
    var muscleGroup by remember { mutableStateOf(exercise.muscleGroup) }
    var equipment by remember { mutableStateOf(exercise.equipment ?: "") }
    var isConfirmed by remember { mutableStateOf(exercise.status == "CONFIRMED") }

    val muscleOptions = listOf("Pecho", "Espalda", "Piernas", "Hombros", "Brazos", "Glúteos", "General")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Editar ejercicio", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Grupo muscular:", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    muscleOptions.forEach { opt ->
                        FilterChip(
                            selected = muscleGroup == opt,
                            onClick = { muscleGroup = opt },
                            label = { Text(opt, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = equipment,
                    onValueChange = { equipment = it },
                    label = { Text("Equipamiento") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Estado confirmado:", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = isConfirmed,
                        onCheckedChange = { isConfirmed = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        exercise.copy(
                            displayName = displayName.trim(),
                            normalizedName = WorkoutTextParser.normalize(displayName),
                            muscleGroup = muscleGroup.trim(),
                            equipment = equipment.takeIf { it.isNotBlank() },
                            status = if (isConfirmed) "CONFIRMED" else "PENDING_CONFIRMATION"
                        )
                    )
                }
            ) {
                Text("Guardar cambios", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
