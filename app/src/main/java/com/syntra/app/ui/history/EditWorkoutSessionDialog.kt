package com.syntra.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.room.withTransaction
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.ui.theme.*
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
    val allExercises by remember(db) { db.exerciseDao().observeAll() }.collectAsState(initial = emptyList())
    val setsFlow = remember(session.id) { db.workoutSetDao().observeForSession(session.id) }
    val initialSets by setsFlow.collectAsState(initial = emptyList())

    var editableSets by remember { mutableStateOf<List<WorkoutSetEntity>>(emptyList()) }
    var setsLoaded by remember { mutableStateOf(false) }
    var tempIdCounter by remember { mutableLongStateOf(-1L) }

    // Sub-dialog states
    var setBeingEdited by remember { mutableStateOf<WorkoutSetEntity?>(null) }
    var exerciseToDelete by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var showAddExerciseDialog by remember { mutableStateOf(false) }

    LaunchedEffect(initialSets) {
        if (!setsLoaded && initialSets.isNotEmpty()) {
            editableSets = initialSets
            setsLoaded = true
        }
    }

    val dynamicExerciseMap = remember(exerciseNameMap, allExercises) {
        val map = exerciseNameMap.toMutableMap()
        allExercises.forEach { ex ->
            map[ex.id] = ex.displayName
        }
        map
    }

    val dateFormatted = remember(session.startedAtMillis) {
        SimpleDateFormat("d 'de' MMMM yyyy, HH:mm", Locale.forLanguageTag("es-AR")).format(Date(session.startedAtMillis))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = SyntraColors.SurfaceCardElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .imePadding(),
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
                            text = "Editar sesión deportiva",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SyntraColors.TextPrimary
                        )
                        Text(
                            text = dateFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = SyntraColors.NeonMint
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Text("✕", color = SyntraColors.TextMuted, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = SyntraColors.BorderSubtle)

                // Main Form & Exercise Sets
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Session Metadata: Title, Category, Notes
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("Título de la sesión", color = SyntraColors.TextMuted) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = SyntraColors.TextPrimary,
                                    unfocusedTextColor = SyntraColors.TextPrimary,
                                    focusedBorderColor = SyntraColors.NeonMint,
                                    unfocusedBorderColor = SyntraColors.BorderSubtle
                                )
                            )

                            Text(
                                text = "Categoría organizativa:",
                                style = MaterialTheme.typography.labelSmall,
                                color = SyntraColors.TextMuted
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
                                            selectedContainerColor = SyntraColors.DarkMint,
                                            selectedLabelColor = SyntraColors.NeonMint,
                                            containerColor = SyntraColors.SurfaceHighlight,
                                            labelColor = SyntraColors.TextSecondary
                                        )
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = notes,
                                onValueChange = { notes = it },
                                label = { Text("Notas de la sesión", color = SyntraColors.TextMuted) },
                                maxLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = SyntraColors.TextPrimary,
                                    unfocusedTextColor = SyntraColors.TextPrimary,
                                    focusedBorderColor = SyntraColors.NeonMint,
                                    unfocusedBorderColor = SyntraColors.BorderSubtle
                                )
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Ejercicios y series (${editableSets.size}):",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = SyntraColors.TextPrimary
                            )
                            TextButton(
                                onClick = { showAddExerciseDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("＋ Ejercicio", color = SyntraColors.NeonMint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    if (editableSets.isEmpty()) {
                        item {
                            Surface(
                                color = SyntraColors.SurfaceDark,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "No hay series registradas en esta sesión. Tocá '+ Ejercicio' para agregar uno.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SyntraColors.TextMuted,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    } else {
                        // Group sets by exercise while preserving order of appearance
                        val exerciseGroups = mutableListOf<Pair<Long, MutableList<WorkoutSetEntity>>>()
                        editableSets.forEach { s ->
                            val currentGroup = exerciseGroups.lastOrNull()
                            if (currentGroup != null && currentGroup.first == s.exerciseId) {
                                currentGroup.second.add(s)
                            } else {
                                val existing = exerciseGroups.firstOrNull { it.first == s.exerciseId }
                                if (existing != null) {
                                    existing.second.add(s)
                                } else {
                                    exerciseGroups.add(s.exerciseId to mutableListOf(s))
                                }
                            }
                        }

                        exerciseGroups.forEach { (exId, setsForEx) ->
                            val exName = dynamicExerciseMap[exId] ?: "Ejercicio #$exId"
                            item(key = "ex_header_$exId") {
                                Surface(
                                    color = SyntraColors.SurfaceDark,
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        // Exercise header bar
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = exName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = SyntraColors.NeonMint,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                // Add set to this exercise
                                                TextButton(
                                                    onClick = {
                                                        val lastSet = setsForEx.lastOrNull()
                                                        val newSet = WorkoutSetEntity(
                                                            id = tempIdCounter,
                                                            sessionId = session.id,
                                                            exerciseId = exId,
                                                            orderInExercise = setsForEx.size + 1,
                                                            executionOrder = editableSets.size + 1,
                                                            loadGrams = lastSet?.loadGrams ?: 0,
                                                            repetitions = lastSet?.repetitions ?: 10,
                                                            loadUnit = lastSet?.loadUnit ?: "KG",
                                                            isToFailure = false,
                                                            note = ""
                                                        )
                                                        tempIdCounter--
                                                        // Insert right after the last set of this exercise
                                                        val lastIndex = editableSets.indexOfLast { it.exerciseId == exId }
                                                        editableSets = if (lastIndex >= 0 && lastIndex < editableSets.size - 1) {
                                                            editableSets.take(lastIndex + 1) + listOf(newSet) + editableSets.drop(lastIndex + 1)
                                                        } else {
                                                            editableSets + listOf(newSet)
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text("＋ Serie", color = SyntraColors.SoftMint, style = MaterialTheme.typography.labelSmall)
                                                }
                                                // Delete whole exercise
                                                IconButton(
                                                    onClick = { exerciseToDelete = exId to exName },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Text("🗑", color = SyntraColors.Error, fontSize = 14.sp)
                                                }
                                            }
                                        }

                                        // Set rows
                                        setsForEx.forEachIndexed { setIdx, currentSet ->
                                            val formattedLoad = formatSetLoadDisplay(currentSet)
                                            Surface(
                                                color = SyntraColors.SurfaceCardElevated,
                                                shape = RoundedCornerShape(8.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Text(
                                                            text = "${setIdx + 1}.",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = SyntraColors.TextMuted,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = formattedLoad,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = SyntraColors.TextPrimary
                                                        )
                                                        Text(
                                                            text = "× ${currentSet.repetitions} reps",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = SyntraColors.TextSecondary
                                                        )
                                                        if (currentSet.isToFailure) {
                                                            Surface(
                                                                color = SyntraColors.DarkMint,
                                                                shape = CircleShape,
                                                                modifier = Modifier.padding(horizontal = 2.dp)
                                                            ) {
                                                                Text(
                                                                    text = "FALLO",
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = SyntraColors.NeonMint,
                                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                        }
                                                        if (currentSet.note.isNotBlank()) {
                                                            Text(
                                                                text = "(${currentSet.note})",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = SyntraColors.TextMuted
                                                            )
                                                        }
                                                    }

                                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        IconButton(
                                                            onClick = { setBeingEdited = currentSet },
                                                            modifier = Modifier.size(32.dp)
                                                        ) {
                                                            Text("✏️", fontSize = 12.sp)
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                editableSets = editableSets.filter { it.id != currentSet.id }
                                                            },
                                                            modifier = Modifier.size(32.dp)
                                                        ) {
                                                            Text("✕", color = SyntraColors.Error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                }

                // Action Buttons Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SyntraSecondaryButton(
                        text = "Cancelar",
                        onClick = onDismiss
                    )
                    Spacer(Modifier.width(10.dp))
                    SyntraButton(
                        text = "Guardar cambios",
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
                                    // 1. Delete removed sets
                                    val currentSetIds = editableSets.map { it.id }.toSet()
                                    initialSets.forEach { origSet ->
                                        if (origSet.id !in currentSetIds) {
                                            db.workoutSetDao().delete(origSet)
                                        }
                                    }
                                    // 2. Insert or update sets with re-indexed order
                                    var globalExecOrder = 1
                                    val groupedByExercise = editableSets.groupBy { it.exerciseId }
                                    groupedByExercise.forEach { (_, setsForEx) ->
                                        setsForEx.forEachIndexed { idxInEx, set ->
                                            val adjusted = set.copy(
                                                orderInExercise = idxInEx + 1,
                                                executionOrder = globalExecOrder++
                                            )
                                            if (adjusted.id <= 0L) {
                                                db.workoutSetDao().insert(adjusted.copy(id = 0L))
                                            } else {
                                                db.workoutSetDao().update(adjusted)
                                            }
                                        }
                                    }
                                }
                                onDismiss()
                            }
                        }
                    )
                }
            }
        }
    }

    // Modal Dialog: Edit Individual Set
    if (setBeingEdited != null) {
        val s = setBeingEdited!!
        val currentExName = dynamicExerciseMap[s.exerciseId] ?: "Ejercicio #${s.exerciseId}"
        EditIndividualSetDialog(
            targetSet = s,
            exerciseName = currentExName,
            onDismiss = { setBeingEdited = null },
            onSave = { updated ->
                editableSets = editableSets.map { if (it.id == updated.id) updated else it }
                setBeingEdited = null
            }
        )
    }

    // Modal Dialog: Confirm Delete Exercise
    if (exerciseToDelete != null) {
        val (delExId, delExName) = exerciseToDelete!!
        AlertDialog(
            onDismissRequest = { exerciseToDelete = null },
            title = {
                Text("Eliminar ejercicio", color = SyntraColors.TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "¿Seguro que querés eliminar '$delExName' y todas sus series de esta sesión deportiva?",
                    color = SyntraColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        editableSets = editableSets.filter { it.exerciseId != delExId }
                        exerciseToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SyntraColors.Error),
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Eliminar", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { exerciseToDelete = null }) {
                    Text("Cancelar", color = SyntraColors.TextMuted)
                }
            },
            containerColor = SyntraColors.SurfaceCardElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Modal Dialog: Add Exercise from catalog
    if (showAddExerciseDialog) {
        AddExerciseCatalogDialog(
            exercises = allExercises,
            onDismiss = { showAddExerciseDialog = false },
            onSelectExercise = { selectedEx ->
                val newSet = WorkoutSetEntity(
                    id = tempIdCounter,
                    sessionId = session.id,
                    exerciseId = selectedEx.id,
                    orderInExercise = 1,
                    executionOrder = editableSets.size + 1,
                    loadGrams = 0,
                    repetitions = 10,
                    loadUnit = "KG",
                    isToFailure = false,
                    note = ""
                )
                tempIdCounter--
                editableSets = editableSets + listOf(newSet)
                showAddExerciseDialog = false
            }
        )
    }
}

/**
 * Helper to format set load according to its unit.
 */
private fun formatSetLoadDisplay(s: WorkoutSetEntity): String {
    val raw = s.loadGrams / 1000.0
    return when (s.loadUnit.uppercase()) {
        "BW" -> "BW"
        "BLOQUES" -> "${raw.toInt()} bloques"
        "PLACAS" -> "${raw.toInt()} placas"
        "LBS", "LB" -> "${raw.toInt()} lb"
        else -> "${if (raw % 1.0 == 0.0) raw.toInt().toString() else String.format(Locale.US, "%.1f", raw)} kg"
    }
}

@Composable
private fun EditIndividualSetDialog(
    targetSet: WorkoutSetEntity,
    exerciseName: String,
    onDismiss: () -> Unit,
    onSave: (WorkoutSetEntity) -> Unit
) {
    val initialRaw = targetSet.loadGrams / 1000.0
    var loadText by remember {
        mutableStateOf(if (targetSet.loadUnit.uppercase() == "BW") "" else if (initialRaw % 1.0 == 0.0) initialRaw.toInt().toString() else initialRaw.toString())
    }
    var selectedUnit by remember { mutableStateOf(targetSet.loadUnit.ifBlank { "KG" }) }
    var repsText by remember { mutableStateOf(targetSet.repetitions.toString()) }
    var isToFailure by remember { mutableStateOf(targetSet.isToFailure) }
    var noteText by remember { mutableStateOf(targetSet.note) }

    val units = listOf("KG", "BLOQUES", "PLACAS", "LBS", "BW")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = SyntraColors.SurfaceCardElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Editar serie • $exerciseName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SyntraColors.TextPrimary
                )

                // Unit Selector
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Unidad de carga:", style = MaterialTheme.typography.labelSmall, color = SyntraColors.TextMuted)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        units.forEach { u ->
                            val isSel = selectedUnit.equals(u, ignoreCase = true)
                            FilterChip(
                                selected = isSel,
                                onClick = {
                                    selectedUnit = u
                                    if (u == "BW") loadText = ""
                                },
                                label = { Text(u, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SyntraColors.DarkMint,
                                    selectedLabelColor = SyntraColors.NeonMint,
                                    containerColor = SyntraColors.SurfaceHighlight,
                                    labelColor = SyntraColors.TextSecondary
                                )
                            )
                        }
                    }
                }

                // Load and Reps input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = loadText,
                        onValueChange = { loadText = it },
                        label = { Text("Carga (${selectedUnit})", color = SyntraColors.TextMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        enabled = selectedUnit != "BW",
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SyntraColors.TextPrimary,
                            unfocusedTextColor = SyntraColors.TextPrimary,
                            focusedBorderColor = SyntraColors.NeonMint,
                            unfocusedBorderColor = SyntraColors.BorderSubtle
                        )
                    )

                    OutlinedTextField(
                        value = repsText,
                        onValueChange = { repsText = it },
                        label = { Text("Reps", color = SyntraColors.TextMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SyntraColors.TextPrimary,
                            unfocusedTextColor = SyntraColors.TextPrimary,
                            focusedBorderColor = SyntraColors.NeonMint,
                            unfocusedBorderColor = SyntraColors.BorderSubtle
                        )
                    )
                }

                // Failure toggle chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = isToFailure,
                        onClick = { isToFailure = !isToFailure },
                        label = { Text(if (isToFailure) "✓ Al fallo muscular (f)" else "○ Serie normal (no fallo)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SyntraColors.DarkMint,
                            selectedLabelColor = SyntraColors.NeonMint,
                            containerColor = SyntraColors.SurfaceHighlight,
                            labelColor = SyntraColors.TextSecondary
                        )
                    )
                }

                // Note
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Nota opcional (ej: unilateral, drop, etc.)", color = SyntraColors.TextMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SyntraColors.TextPrimary,
                        unfocusedTextColor = SyntraColors.TextPrimary,
                        focusedBorderColor = SyntraColors.NeonMint,
                        unfocusedBorderColor = SyntraColors.BorderSubtle
                    )
                )

                // Dialog buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar", color = SyntraColors.TextMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    SyntraButton(
                        text = "Listo",
                        onClick = {
                            val parsedLoad = if (selectedUnit == "BW") 0.0 else loadText.toDoubleOrNull() ?: 0.0
                            val parsedReps = repsText.toIntOrNull() ?: 1
                            val loadGrams = (parsedLoad * 1000).toInt()
                            val updated = targetSet.copy(
                                loadGrams = loadGrams,
                                repetitions = parsedReps,
                                loadUnit = selectedUnit,
                                isToFailure = isToFailure,
                                note = noteText.trim()
                            )
                            onSave(updated)
                        },
                        modifier = Modifier.height(44.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AddExerciseCatalogDialog(
    exercises: List<ExerciseEntity>,
    onDismiss: () -> Unit,
    onSelectExercise: (ExerciseEntity) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(searchQuery, exercises) {
        if (searchQuery.isBlank()) {
            exercises
        } else {
            val q = searchQuery.trim().lowercase(Locale.ROOT)
            exercises.filter {
                it.displayName.lowercase(Locale.ROOT).contains(q) ||
                    it.muscleGroup.lowercase(Locale.ROOT).contains(q)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(16.dp),
            color = SyntraColors.SurfaceCardElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Seleccionar ejercicio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SyntraColors.TextPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Text("✕", color = SyntraColors.TextMuted, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar por nombre o músculo...", color = SyntraColors.TextMuted, style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SyntraColors.TextPrimary,
                        unfocusedTextColor = SyntraColors.TextPrimary,
                        focusedBorderColor = SyntraColors.NeonMint,
                        unfocusedBorderColor = SyntraColors.BorderSubtle
                    )
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = "No se encontraron ejercicios con '$searchQuery'.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SyntraColors.TextMuted,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    } else {
                        items(filtered, key = { it.id }) { ex ->
                            Surface(
                                color = SyntraColors.SurfaceDark,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectExercise(ex) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ex.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = SyntraColors.TextPrimary
                                        )
                                        Text(
                                            text = ex.muscleGroup,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SyntraColors.SoftMint
                                        )
                                    }
                                    Text("＋", color = SyntraColors.NeonMint, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
