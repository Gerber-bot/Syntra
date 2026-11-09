package com.syntra.app.ui.workout

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.syntra.app.data.ActiveWorkoutDraftEntity
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.ExerciseAliasEntity
import com.syntra.app.data.IrisxMemoryEntity
import com.syntra.app.domain.*
import com.syntra.app.ui.parser.EditExercisePreviewDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun ActiveWorkoutScreen(
    db: AppDatabase,
    suggestedNextPlan: NextSessionPlan,
    routinesWithExercises: List<RoutineWithExercises>,
    onBackToHome: () -> Unit,
    onCompletedNavigateToHistory: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val port = remember(db) { RoomActiveWorkoutPersistencePort(db) }
    val manager = remember(port) { ActiveWorkoutManager(port) }

    val draftFlow = remember(db) { db.activeWorkoutDraftDao().observeCurrentDraft() }
    val persistedDraft by draftFlow.collectAsState(initial = null)

    var editorValue by remember { mutableStateOf(TextFieldValue("")) }
    var titleValue by remember { mutableStateOf("") }
    var initializedForUuid by remember { mutableStateOf<String?>(null) }

    var saveStatus by remember { mutableStateOf(DraftSaveStatus.SAVED) }
    var lastSavedTimeFormatted by remember { mutableStateOf<String?>(null) }
    var timerFormatted by remember { mutableStateOf("00:00:00") }
    var isTimerPaused by remember { mutableStateOf(false) }

    var reviewBundle by remember { mutableStateOf<ActiveWorkoutReviewBundle?>(null) }
    var editedExercises by remember { mutableStateOf<List<ParsedExercise>>(emptyList()) }
    var exerciseToEdit by remember { mutableStateOf<ParsedExercise?>(null) }
    var commitErrorMessage by remember { mutableStateOf<String?>(null) }
    var isCommitting by remember { mutableStateOf(false) }
    var showCancelConfirmDialog by remember { mutableStateOf(false) }

    // Recover or synchronize editor state when a persisted draft is loaded
    LaunchedEffect(persistedDraft?.sessionUuid, persistedDraft?.state) {
        val d = persistedDraft ?: return@LaunchedEffect
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        if (initializedForUuid != d.sessionUuid) {
            val normalized = manager.getRecoverableDraft(nowWall, nowElapsed) ?: d
            val safeCursor = normalized.cursorPosition.coerceIn(0, normalized.rawNotesText.length)
            editorValue = TextFieldValue(
                text = normalized.rawNotesText,
                selection = TextRange(safeCursor)
            )
            titleValue = normalized.title
            lastSavedTimeFormatted = ActiveWorkoutTimer.formatSavedTimestamp(normalized.lastSavedAtWallMillis)
            initializedForUuid = normalized.sessionUuid
            commitErrorMessage = normalized.lastErrorMessage
        }

        val currentState = WorkoutSessionState.fromString(d.state)
        if (currentState == WorkoutSessionState.REVIEW_PENDING && reviewBundle == null) {
            val bundle = manager.beginFinalizationReview(nowWall, nowElapsed)
            reviewBundle = bundle
            editedExercises = bundle?.parsePreview?.exercises.orEmpty()
        }
    }

    // 1-second timer ticker ONLY while screen is active in foreground
    LaunchedEffect(persistedDraft) {
        val d = persistedDraft ?: return@LaunchedEffect
        while (isActive) {
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val snap = ActiveWorkoutTimer.computeSnapshot(d, nowWall, nowElapsed)
            timerFormatted = snap.formattedHms
            isTimerPaused = snap.isPaused
            delay(1000L)
        }
    }

    // Debounced non-blocking autosave on text or cursor changes
    LaunchedEffect(editorValue.text, editorValue.selection.start, titleValue, initializedForUuid) {
        val currentDraft = persistedDraft ?: return@LaunchedEffect
        if (initializedForUuid != currentDraft.sessionUuid) return@LaunchedEffect
        if (editorValue.text == currentDraft.rawNotesText &&
            editorValue.selection.start == currentDraft.cursorPosition &&
            titleValue == currentDraft.title
        ) {
            return@LaunchedEffect
        }
        saveStatus = DraftSaveStatus.SAVING
        delay(350L)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val (saved, status) = manager.autosaveNotes(
            rawNotesText = editorValue.text,
            cursorPosition = editorValue.selection.start,
            title = titleValue,
            nowWallMillis = nowWall,
            nowElapsedRealtimeMillis = nowElapsed
        )
        saveStatus = status
        if (saved != null) {
            lastSavedTimeFormatted = ActiveWorkoutTimer.formatSavedTimestamp(saved.lastSavedAtWallMillis)
        }
    }

    // Immediate autosave when app goes to background (ON_PAUSE / ON_STOP)
    DisposableEffect(lifecycleOwner, persistedDraft?.sessionUuid) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                val activeDraft = persistedDraft
                if (activeDraft != null && initializedForUuid == activeDraft.sessionUuid) {
                    scope.launch {
                        val nowWall = System.currentTimeMillis()
                        val nowElapsed = SystemClock.elapsedRealtime()
                        manager.autosaveNotes(
                            rawNotesText = editorValue.text,
                            cursorPosition = editorValue.selection.start,
                            title = titleValue,
                            nowWallMillis = nowWall,
                            nowElapsedRealtimeMillis = nowElapsed
                        )
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Cancel confirmation dialog
    if (showCancelConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = {
                Text("Descartar sesión activa", fontWeight = FontWeight.Bold, color = Color.White)
            },
            text = {
                Text(
                    "¿Estás seguro de que querés descartar el entrenamiento en curso? Se eliminará el borrador actual sin registrarlo en el historial ni avanzar tu secuencia.",
                    color = Color.White
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            manager.cancelSession(confirmed = true)
                            showCancelConfirmDialog = false
                            onBackToHome()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text("Descartar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCancelConfirmDialog = false }) {
                    Text("Seguir entrenando", color = Color.White)
                }
            }
        )
    }

    // Exercise preview edit dialog during REVIEW_PENDING
    if (exerciseToEdit != null) {
        EditExercisePreviewDialog(
            exercise = exerciseToEdit!!,
            onDismiss = { exerciseToEdit = null },
            onSave = { updated, learnAlias, originalName ->
                editedExercises = editedExercises.map { if (it.name == originalName) updated else it }
                if (learnAlias) {
                    scope.launch {
                        val normAlias = WorkoutTextParser.normalize(originalName)
                        val normCanonical = WorkoutTextParser.normalize(updated.name)
                        val now = System.currentTimeMillis()
                        db.irisxMemoryDao().insert(
                            IrisxMemoryEntity(
                                memoryType = "ALIAS",
                                sourceKey = normAlias,
                                targetValue = updated.name,
                                confidence = 1.0,
                                createdAtMillis = now,
                                updatedAtMillis = now
                            )
                        )
                        val ex = db.exerciseDao().findByNormalizedName(normCanonical)
                        if (ex != null) {
                            db.exerciseAliasDao().insert(
                                ExerciseAliasEntity(
                                    exerciseId = ex.id,
                                    normalizedAlias = normAlias
                                )
                            )
                        }
                    }
                }
                exerciseToEdit = null
            }
        )
    }

    val activeDraft = persistedDraft
    if (activeDraft == null) {
        // IDLE state: Start Session Launcher
        StartWorkoutLauncherView(
            suggestedNextPlan = suggestedNextPlan,
            routinesWithExercises = routinesWithExercises,
            onStartBlank = { initialTitle, category ->
                scope.launch {
                    val nowWall = System.currentTimeMillis()
                    val nowElapsed = SystemClock.elapsedRealtime()
                    val starterText = if (!initialTitle.isNullOrBlank()) "$initialTitle\n" else ""
                    manager.startSession(
                        title = initialTitle.orEmpty(),
                        initialNotesText = starterText,
                        plannedCategory = category,
                        forceReplaceExisting = true,
                        nowWallMillis = nowWall,
                        nowElapsedRealtimeMillis = nowElapsed
                    )
                }
            },
            onStartFromRoutine = { routineWithEx ->
                scope.launch {
                    val nowWall = System.currentTimeMillis()
                    val nowElapsed = SystemClock.elapsedRealtime()
                    manager.startFromRoutine(
                        routineWithExercises = routineWithEx,
                        forceReplaceExisting = true,
                        nowWallMillis = nowWall,
                        nowElapsedRealtimeMillis = nowElapsed
                    )
                }
            },
            onBack = onBackToHome
        )
        return
    }

    val currentState = WorkoutSessionState.fromString(activeDraft.state)

    if (currentState == WorkoutSessionState.REVIEW_PENDING && reviewBundle != null) {
        // REVIEW_PENDING state: Editable preview before atomic SQLite commit
        ActiveWorkoutReviewView(
            draft = activeDraft,
            frozenTimerFormatted = reviewBundle!!.frozenTimer.formattedHms,
            sessionTitle = titleValue.ifBlank { reviewBundle!!.parsePreview.sessionTitle ?: "Entrenamiento" },
            onTitleChange = { titleValue = it },
            exercises = editedExercises,
            warnings = reviewBundle!!.parsePreview.warnings,
            commitError = commitErrorMessage ?: activeDraft.lastErrorMessage,
            isCommitting = isCommitting,
            onEditExercise = { ex -> exerciseToEdit = ex },
            onCancelReview = {
                scope.launch {
                    val nowWall = System.currentTimeMillis()
                    val nowElapsed = SystemClock.elapsedRealtime()
                    manager.cancelReviewAndResume(nowWall, nowElapsed)
                    reviewBundle = null
                    commitErrorMessage = null
                }
            },
            onConfirmCommit = {
                if (isCommitting) return@ActiveWorkoutReviewView
                isCommitting = true
                scope.launch {
                    try {
                        val nowWall = System.currentTimeMillis()
                        val nowElapsed = SystemClock.elapsedRealtime()
                        val effectiveTitle = titleValue.trim().ifEmpty {
                            reviewBundle?.parsePreview?.sessionTitle ?: "Entrenamiento"
                        }
                        when (val res = manager.confirmAndCommitSession(
                            editedTitle = effectiveTitle,
                            editedExercises = editedExercises,
                            nowWallMillis = nowWall,
                            nowElapsedRealtimeMillis = nowElapsed
                        )) {
                            is FinalizationResult.Success -> {
                                commitErrorMessage = null
                                reviewBundle = null
                                onCompletedNavigateToHistory()
                            }
                            is FinalizationResult.Failure -> {
                                commitErrorMessage = res.errorMessage
                            }
                        }
                    } finally {
                        isCommitting = false
                    }
                }
            }
        )
        return
    }

    // ACTIVE / ERROR state: Persistent Timer + Auto-saved Free-text Notepad
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Header + Back button (keeps session active)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onBackToHome,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("← Inicio", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }

            // Discrete save status badge
            Surface(
                color = Color(0xFF1A2220),
                shape = RoundedCornerShape(12.dp)
            ) {
                val statusText = when (saveStatus) {
                    DraftSaveStatus.SAVING -> "● Guardando..."
                    DraftSaveStatus.ERROR -> "⚠ Error de guardado"
                    DraftSaveStatus.SAVED -> lastSavedTimeFormatted?.let { "✓ Guardado $it" } ?: "✓ Autoguardado activo"
                }
                val statusColor = when (saveStatus) {
                    DraftSaveStatus.SAVING -> Color(0xFFFFD54F)
                    DraftSaveStatus.ERROR -> MaterialTheme.colorScheme.error
                    DraftSaveStatus.SAVED -> MaterialTheme.colorScheme.primary
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // Persistent Chronometer Card (HH:MM:SS)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121A18)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isTimerPaused) "TIEMPO EN PAUSA (REVISIÓN)" else "ENTRENAMIENTO ACTIVO",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = timerFormatted,
                        style = MaterialTheme.typography.headlineLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                if (activeDraft.plannedCategory != null) {
                    Surface(
                        color = Color(0xFF1E2A26),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "🎯 ${activeDraft.plannedCategory}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // Session Title Field
        OutlinedTextField(
            value = titleValue,
            onValueChange = { titleValue = it },
            label = { Text("Título o enfoque de la sesión (ej. Pecho, Espalda)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Integrated Free-Text Notepad with Cursor & Line-break preservation
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Bloc de notas de entrenamiento",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Texto libre • Persistencia SQLite",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }

                OutlinedTextField(
                    value = editorValue,
                    onValueChange = { newValue ->
                        editorValue = newValue
                        commitErrorMessage = null
                    },
                    placeholder = {
                        Text(
                            "Ejemplo:\nPress de banca 3 de 8 a 10: 70kg 10 9 8\nAperturas con mancuernas 14kg 12 12 10\nFondos en paralelas -15kg asistidas 10 10",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.DarkGray
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF2C2C2C),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        }

        val displayedError = commitErrorMessage ?: activeDraft.lastErrorMessage
        if (!displayedError.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1414)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "⚠ $displayedError",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Primary Finalize Button + Secondary Discard Button (leaves space on bottom-end for floating IrisX button)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 68.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        val nowWall = System.currentTimeMillis()
                        val nowElapsed = SystemClock.elapsedRealtime()
                        manager.autosaveNotes(
                            rawNotesText = editorValue.text,
                            cursorPosition = editorValue.selection.start,
                            title = titleValue,
                            nowWallMillis = nowWall,
                            nowElapsedRealtimeMillis = nowElapsed
                        )
                        val bundle = manager.beginFinalizationReview(nowWall, nowElapsed)
                        if (bundle != null) {
                            reviewBundle = bundle
                            editedExercises = bundle.parsePreview.exercises
                            if (titleValue.isBlank() && !bundle.parsePreview.sessionTitle.isNullOrBlank()) {
                                titleValue = bundle.parsePreview.sessionTitle
                            }
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("⏹ Finalizar y revisar", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { showCancelConfirmDialog = true },
                modifier = Modifier.height(50.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Descartar", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(72.dp))
    }
}

@Composable
private fun StartWorkoutLauncherView(
    suggestedNextPlan: NextSessionPlan,
    routinesWithExercises: List<RoutineWithExercises>,
    onStartBlank: (initialTitle: String?, plannedCategory: String?) -> Unit,
    onStartFromRoutine: (RoutineWithExercises) -> Unit,
    onBack: () -> Unit
) {
    var customBlankTitle by remember {
        mutableStateOf(suggestedNextPlan.nextCategoryName.orEmpty())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Iniciar entrenamiento",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Elegí cómo comenzar tu sesión activa",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("← Volver", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }

        // Option 1: Suggested next session / routine
        if (suggestedNextPlan.nextCategoryName != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF14221E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "PRÓXIMA SESIÓN SUGERIDA",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Próxima sesión: ${suggestedNextPlan.nextCategoryName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = suggestedNextPlan.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                    if (suggestedNextPlan.suggestedRoutine != null) {
                        val r = suggestedNextPlan.suggestedRoutine
                        Button(
                            onClick = { onStartFromRoutine(r) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.Black
                            )
                        ) {
                            Text("▶ Iniciar con rutina '${r.routine.name}'", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                onStartBlank(suggestedNextPlan.nextCategoryName, suggestedNextPlan.nextCategoryName)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.Black
                            )
                        ) {
                            Text("▶ Iniciar sesión de ${suggestedNextPlan.nextCategoryName}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Option 2: Start blank session
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Comenzar en blanco",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                OutlinedTextField(
                    value = customBlankTitle,
                    onValueChange = { customBlankTitle = it },
                    label = { Text("Título opcional (ej. Pecho, Espalda, Piernas)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val clean = customBlankTitle.trim().takeIf { it.isNotEmpty() }
                        onStartBlank(clean, clean)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF263238),
                        contentColor = Color.White
                    )
                ) {
                    Text("▶ Comenzar bloc de notas en blanco", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Option 3: Select an existing routine
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Seleccionar una rutina existente",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (routinesWithExercises.isEmpty()) {
                    Text(
                        text = "Todavía no tenés rutinas guardadas. Podés crearlas desde la sección Rutinas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                } else {
                    routinesWithExercises.forEach { rw ->
                        Surface(
                            color = Color(0xFF1F1F1F),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = rw.routine.name,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "${rw.routine.targetCategory} • ${rw.exercises.size} ejercicios",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                OutlinedButton(
                                    onClick = { onStartFromRoutine(rw) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Usar rutina", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(64.dp))
    }
}

@Composable
private fun ActiveWorkoutReviewView(
    draft: ActiveWorkoutDraftEntity,
    frozenTimerFormatted: String,
    sessionTitle: String,
    onTitleChange: (String) -> Unit,
    exercises: List<ParsedExercise>,
    warnings: List<String>,
    commitError: String?,
    isCommitting: Boolean,
    onEditExercise: (ParsedExercise) -> Unit,
    onCancelReview: () -> Unit,
    onConfirmCommit: () -> Unit
) {
    val compactExercises = remember(exercises) {
        WorkoutPresentation.toCompactList(exercises)
    }
    val totalSets = remember(exercises) {
        exercises.sumOf { it.sets.size }
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
            Column {
                Text(
                    text = "Revisión previa al guardado",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Duración congelada: $frozenTimerFormatted • Estado: Revisión pendiente",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        OutlinedTextField(
            value = sessionTitle,
            onValueChange = onTitleChange,
            label = { Text("Título de la sesión a registrar") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (!commitError.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1414)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "⚠ $commitError",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (warnings.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF241E12)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Observaciones de IrisX al interpretar tus notas:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD54F)
                    )
                    warnings.forEach { w ->
                        Text(
                            text = "• $w",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Text(
            text = "${compactExercises.size} ejercicios detectados • $totalSets series totales",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        if (compactExercises.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1616)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "No se detectaron ejercicios con peso en kg. Volvé al bloc de notas para ajustar el formato (ej. 'Sentadilla 70kg 10 10 8'). Tus notas originales están intactas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            compactExercises.forEach { exercise ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = exercise.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (exercise.isNewExercise) {
                                    Text(
                                        text = "✨ Nuevo",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF81C784)
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    exercises.find { it.name == exercise.name }?.let(onEditExercise)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("✎ Corregir", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        if (exercise.plannedTarget != null) {
                            Text(
                                text = "🎯 Plan: ${exercise.plannedTarget}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.LightGray
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        exercise.groups.forEach { grp ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${grp.setCount} series", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                Text(grp.formattedWeight(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                Text("${grp.formattedReps()} reps", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 68.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onCancelReview,
                enabled = !isCommitting,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("← Volver a notas", color = Color.White, fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = onConfirmCommit,
                enabled = !isCommitting && compactExercises.isNotEmpty(),
                modifier = Modifier
                    .weight(1.2f)
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = if (isCommitting) "Guardando..." else "✓ Confirmar y guardar",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(72.dp))
    }
}
