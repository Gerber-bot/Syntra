package com.syntra.app.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.syntra.app.data.*
import com.syntra.app.domain.*
import com.syntra.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun RoutinesScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onApplyToAnalyzer: (String) -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val routines by remember(db) { db.routineDao().observeAllRoutines() }.collectAsState(initial = emptyList())
    val allRoutineItems by remember(db) { db.routineDao().observeAllRoutineExercises() }.collectAsState(initial = emptyList())
    val sessions by remember(db) { db.workoutSessionDao().observeAll() }.collectAsState(initial = emptyList())
    val allSets by remember(db) { db.workoutSetDao().observeAll() }.collectAsState(initial = emptyList())
    val exercises by remember(db) { db.exerciseDao().observeAll() }.collectAsState(initial = emptyList())
    val memories by remember(db) { db.irisxMemoryDao().observeAll() }.collectAsState(initial = emptyList())

    val learnedAliases = remember(memories) {
        memories.filter { it.memoryType == "ALIAS" }.associate { it.sourceKey to it.targetValue }
    }

    val routinesWithExercises = remember(routines, allRoutineItems) {
        val grouped = allRoutineItems.groupBy { it.routineId }
        routines.map { r -> RoutineManager.fromEntities(r, grouped[r.id].orEmpty()) }
    }

    var editingRoutine by remember { mutableStateOf<RoutineWithExercises?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var routineToConfirmApply by remember { mutableStateOf<RoutineWithExercises?>(null) }
    var routineToDelete by remember { mutableStateOf<RoutineWithExercises?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    if (isCreatingNew || editingRoutine != null) {
        RoutineEditorDialog(
            initial = editingRoutine,
            availableExercises = exercises.map { it.displayName },
            onDismiss = {
                isCreatingNew = false
                editingRoutine = null
            },
            onSave = { name, description, category, specs ->
                scope.launch {
                    val now = System.currentTimeMillis()
                    db.withTransaction {
                        val rId = if (editingRoutine != null) {
                            val updated = editingRoutine!!.routine.copy(
                                name = name,
                                description = description,
                                targetCategory = category,
                                updatedAtMillis = now
                            )
                            db.routineDao().updateRoutine(updated)
                            db.routineDao().deleteExercisesForRoutine(updated.id)
                            updated.id
                        } else {
                            db.routineDao().insertRoutine(
                                RoutineEntity(
                                    name = name,
                                    description = description,
                                    targetCategory = category,
                                    createdAtMillis = now,
                                    updatedAtMillis = now
                                )
                            )
                        }

                        val entities = specs.mapIndexed { idx, s ->
                            val matchedEx = exercises.firstOrNull {
                                WorkoutTextParser.normalize(it.displayName) == WorkoutTextParser.normalize(s.exerciseName)
                            }
                            RoutineExerciseEntity(
                                routineId = rId,
                                exerciseId = matchedEx?.id,
                                exerciseName = s.exerciseName.trim(),
                                orderIndex = idx + 1,
                                targetSets = s.targetSets,
                                minReps = s.minReps,
                                maxReps = s.maxReps,
                                suggestedLoadGrams = s.suggestedLoadGrams,
                                isDumbbell = s.isDumbbell,
                                isAssisted = s.isAssisted,
                                notes = s.notes
                            )
                        }
                        db.routineDao().insertAllRoutineExercises(entities)
                    }
                    statusMessage = if (editingRoutine != null) "Rutina '$name' actualizada." else "Rutina '$name' creada."
                    isCreatingNew = false
                    editingRoutine = null
                }
            }
        )
    }

    if (routineToConfirmApply != null) {
        val target = routineToConfirmApply!!
        AlertDialog(
            onDismissRequest = { routineToConfirmApply = null },
            containerColor = Color(0xFF1E1E1E),
            title = {
                Text("Aplicar rutina a nueva sesión", fontWeight = FontWeight.Bold, color = Color.White)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Rutina: ${target.routine.name} (${target.exercises.size} ejercicios)",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Se creará una sesión nueva conservando cada objetivo planificado ('plannedTarget') separado de las series ejecutadas. Tus registros históricos no se modifican.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val now = System.currentTimeMillis()
                            val draft = RoutineManager.createSessionDraftFromRoutine(target, now)
                            db.withTransaction {
                                val sId = db.workoutSessionDao().insert(
                                    WorkoutSessionEntity(
                                        title = draft.sessionTitle,
                                        startedAtMillis = draft.startedAtMillis,
                                        finishedAtMillis = draft.startedAtMillis,
                                        notes = draft.notes
                                    )
                                )
                                var execOrder = 1
                                draft.exercises.forEach { exDraft ->
                                    val norm = WorkoutTextParser.normalize(exDraft.exerciseName)
                                    var exEntity = db.exerciseDao().findByNormalizedName(norm)
                                    if (exEntity == null) {
                                        val newExId = db.exerciseDao().insert(
                                            ExerciseEntity(
                                                displayName = exDraft.exerciseName,
                                                normalizedName = norm,
                                                muscleGroup = "General",
                                                createdAtMillis = now
                                            )
                                        )
                                        exEntity = ExerciseEntity(
                                            id = newExId,
                                            displayName = exDraft.exerciseName,
                                            normalizedName = norm,
                                            muscleGroup = "General",
                                            createdAtMillis = now
                                        )
                                    }
                                    exDraft.executedSets.forEachIndexed { idx, setInput ->
                                        db.workoutSetDao().insert(
                                            WorkoutSetEntity(
                                                sessionId = sId,
                                                exerciseId = exEntity.id,
                                                orderInExercise = idx + 1,
                                                executionOrder = execOrder++,
                                                loadGrams = setInput.loadGrams,
                                                repetitions = setInput.repetitions,
                                                rir = setInput.rir,
                                                isAssisted = setInput.isAssisted,
                                                isDumbbell = setInput.isDumbbell,
                                                note = setInput.note,
                                                plannedTarget = exDraft.plannedTarget
                                            )
                                        )
                                    }
                                }
                            }
                            routineToConfirmApply = null
                            statusMessage = "Sesión creada a partir de '${target.routine.name}'."
                            onNavigateToHistory()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Confirmar registro", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { routineToConfirmApply = null }) {
                    Text("Cancelar", color = Color.White)
                }
            }
        )
    }

    if (routineToDelete != null) {
        val target = routineToDelete!!
        AlertDialog(
            onDismissRequest = { routineToDelete = null },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Eliminar rutina", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Text(
                    "¿Deseás eliminar la rutina '${target.routine.name}'? Las sesiones históricas ya registradas no se verán afectadas.",
                    color = Color.White
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            db.withTransaction {
                                db.routineDao().deleteExercisesForRoutine(target.routine.id)
                                db.routineDao().deleteRoutineById(target.routine.id)
                            }
                            routineToDelete = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Eliminar", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { routineToDelete = null }) {
                    Text("Cancelar", color = Color.White)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                        text = "Gestión de Rutinas",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SyntraColors.TextPrimary
                    )
                    Text(
                        text = "Planificación separada • IrisX Coach",
                        style = MaterialTheme.typography.labelSmall,
                        color = SyntraColors.SoftMint
                    )
                }
            }
            SyntraButton(
                text = "+ Nueva",
                onClick = { isCreatingNew = true },
                modifier = Modifier.height(38.dp)
            )
        }

        if (statusMessage != null) {
            Text(
                text = statusMessage!!,
                color = SyntraColors.NeonMint,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (routinesWithExercises.isEmpty()) {
            SyntraCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Todavía no tenés rutinas planificadas",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SyntraColors.TextPrimary
                    )
                    Text(
                        text = "Creá tus rutinas con series objetivo, rangos de repeticiones y cargas sugeridas. Podrás aplicarlas en el Analizador o como sesiones nuevas sin sobrescribir tu historial.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SyntraColors.TextSecondary
                    )
                    SyntraButton(
                        text = "+ Crear primera rutina",
                        onClick = { isCreatingNew = true }
                    )
                }
            }
        } else {
            routinesWithExercises.forEach { rw ->
                val coachSuggestions = remember(rw, sessions, allSets, exercises, learnedAliases) {
                    RoutineManager.suggestCoachAdjustments(rw, sessions, allSets, exercises, learnedAliases)
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16181A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = rw.routine.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (rw.routine.description.isNotBlank()) {
                                    Text(
                                        text = rw.routine.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                            Row {
                                TextButton(onClick = { editingRoutine = rw }) {
                                    Text("Editar", style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { routineToDelete = rw }) {
                                    Text("🗑", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        // Exercises list in routine
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            rw.exercises.forEach { spec ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF1E2224), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = "${spec.orderIndex}. ${spec.exerciseName}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Objetivo: ${spec.targetSets} series × ${spec.formattedRepRange()}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.LightGray
                                        )
                                    }
                                    val loadLabel = spec.formattedSuggestedLoad() ?: "Libre"
                                    Text(
                                        text = loadLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // Coach suggestions for this routine
                        if (coachSuggestions.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF14221E), RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "✦ Sugerencias de IrisX Coach:",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                coachSuggestions.forEach { sug ->
                                    Text(
                                        text = "• ${sug.exerciseName}: ${sug.reason}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val template = RoutineManager.toParserTextTemplate(rw)
                                    onApplyToAnalyzer(template)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("✎ Abrir en Analizador", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                            Button(
                                onClick = { routineToConfirmApply = rw },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("▶ Aplicar sesión", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineEditorDialog(
    initial: RoutineWithExercises?,
    availableExercises: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, List<RoutineExerciseSpec>) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.routine?.name ?: "") }
    var description by remember(initial) { mutableStateOf(initial?.routine?.description ?: "") }
    val items = remember(initial) {
        mutableStateListOf<RoutineExerciseSpec>().apply {
            if (initial != null && initial.exercises.isNotEmpty()) {
                addAll(initial.exercises)
            } else {
                add(
                    RoutineExerciseSpec(
                        exerciseName = availableExercises.firstOrNull() ?: "Sentadilla",
                        orderIndex = 1,
                        targetSets = 4,
                        minReps = 8,
                        maxReps = 10,
                        suggestedLoadGrams = 60_000
                    )
                )
            }
        }
    }

    var newExName by remember { mutableStateOf("") }
    var newSets by remember { mutableStateOf("3") }
    var newMinReps by remember { mutableStateOf("8") }
    var newMaxReps by remember { mutableStateOf("12") }
    var newLoadKg by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF181A1C),
        title = {
            Text(
                text = if (initial == null) "Nueva rutina" else "Editar rutina",
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre de la rutina (ej. Tirón A)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción opcional") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Ejercicios de la rutina (${items.size}):",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                items.forEachIndexed { index, spec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF22262A), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${index + 1}. ${spec.exerciseName}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            val loadStr = spec.formattedSuggestedLoad()?.let { " • $it" } ?: ""
                            Text(
                                text = "${spec.targetSets} series × ${spec.formattedRepRange()}$loadStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.LightGray
                            )
                        }
                        IconButton(
                            onClick = { items.removeAt(index) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text("✕", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF2E3236))

                Text(
                    text = "Agregar ejercicio a la rutina:",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.LightGray
                )
                OutlinedTextField(
                    value = newExName,
                    onValueChange = { newExName = it },
                    label = { Text("Ejercicio (ej. Remo con barra)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = newSets,
                        onValueChange = { newSets = it },
                        label = { Text("Series") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = newMinReps,
                        onValueChange = { newMinReps = it },
                        label = { Text("Mín reps") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = newMaxReps,
                        onValueChange = { newMaxReps = it },
                        label = { Text("Máx reps") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newLoadKg,
                        onValueChange = { newLoadKg = it },
                        label = { Text("Carga sugerida kg (opcional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            val exClean = newExName.trim()
                            val sCount = newSets.toIntOrNull() ?: 3
                            val minR = newMinReps.toIntOrNull() ?: 8
                            val maxR = newMaxReps.toIntOrNull() ?: minR
                            val sugGrams = newLoadKg.trim().replace(',', '.').toDoubleOrNull()?.let { (it * 1000).roundToInt() }
                            if (exClean.isBlank()) {
                                validationError = "Ingresá el nombre del ejercicio para agregarlo."
                            } else {
                                items.add(
                                    RoutineExerciseSpec(
                                        exerciseName = exClean,
                                        orderIndex = items.size + 1,
                                        targetSets = sCount.coerceIn(1, 20),
                                        minReps = minR.coerceIn(1, 100),
                                        maxReps = maxR.coerceAtLeast(minR).coerceIn(1, 100),
                                        suggestedLoadGrams = sugGrams
                                    )
                                )
                                newExName = ""
                                newLoadKg = ""
                                validationError = null
                            }
                        }
                    ) {
                        Text("+ Añadir", color = MaterialTheme.colorScheme.primary)
                    }
                }

                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val indexed = items.mapIndexed { idx, item -> item.copy(orderIndex = idx + 1) }
                    val err = RoutineManager.validateRoutine(name, indexed)
                    if (err != null) {
                        validationError = err
                    } else {
                        onSave(name.trim(), description.trim(), "General", indexed)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.Black
                )
            ) {
                Text("Guardar rutina", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar", color = Color.White)
            }
        }
    )
}
