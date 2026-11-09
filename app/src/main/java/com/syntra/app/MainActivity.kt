package com.syntra.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.syntra.app.data.*
import com.syntra.app.data.backup.BackupManager
import com.syntra.app.data.backup.BackupValidationResult
import com.syntra.app.data.backup.SyntraBackupPayload
import com.syntra.app.domain.*
import com.syntra.app.domain.neural.DeterministicInferenceEngine
import com.syntra.app.domain.neural.LlamaCppInferenceEngine
import com.syntra.app.domain.neural.NeuralInferenceEngine
import com.syntra.app.ui.analytics.AnalyticsAndCoachScreen
import com.syntra.app.ui.categories.ManageCategoriesDialog
import com.syntra.app.ui.exercises.ExerciseLibraryDialog
import com.syntra.app.ui.history.EditWorkoutSessionDialog
import com.syntra.app.ui.irisx.IrisXConsoleDialog
import com.syntra.app.ui.irisx.ManageNeuralModelDialog
import com.syntra.app.ui.parser.EditExercisePreviewDialog
import com.syntra.app.ui.routines.RoutinesScreen
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.sp
import com.syntra.app.ui.week.WeekScreen
import com.syntra.app.ui.workout.ActiveWorkoutScreen
import com.syntra.app.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val database = AppDatabase.get(applicationContext)
        setContent {
            SyntraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SyntraColors.BackgroundBlack,
                    contentColor = SyntraColors.TextPrimary
                ) {
                    TrainerApp(database)
                }
            }
        }
    }
}

private fun weightGrams(input: String): Int? = InputValidators.parseWeightGrams(input)

private fun displayKg(grams: Int): String = InputValidators.formatWeightKg(grams)

private fun getAppVersionName(context: android.content.Context): String {
    return try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        pInfo.versionName ?: "0.7.0-alpha2"
    } catch (_: Exception) {
        "0.7.0-alpha2"
    }
}

private val SUB_PAGES_WITH_BACK_TO_HOME = setOf("Semana", "Peso", "Estadísticas", "Rutinas", "Analizar")
private val BOTTOM_NAV_DESTINATIONS = listOf("Inicio", "Historial", "Semana", "Peso", "Perfil")

@Composable
private fun TrainerApp(db: AppDatabase) {
    val scope = rememberCoroutineScope()
    val fallbackEngine = remember(db) { DeterministicInferenceEngine(db) }
    val neuralEngine = remember(fallbackEngine) { LlamaCppInferenceEngine(fallbackEngine) }
    var showManageNeuralModelDialog by remember { mutableStateOf(false) }

    val profileFlow = remember(db) { db.profileDao().observe() }
    val profile by profileFlow.collectAsState(initial = null)
    val activeDraftFlow = remember(db) { db.activeWorkoutDraftDao().observeCurrentDraft() }
    val activeDraft by activeDraftFlow.collectAsState(initial = null)
    val sessions by remember(db) { db.workoutSessionDao().observeAll() }.collectAsState(initial = emptyList())
    val allSets by remember(db) { db.workoutSetDao().observeAll() }.collectAsState(initial = emptyList())
    val exercises by remember(db) { db.exerciseDao().observeAll() }.collectAsState(initial = emptyList())
    val memories by remember(db) { db.irisxMemoryDao().observeAll() }.collectAsState(initial = emptyList())
    val routines by remember(db) { db.routineDao().observeAllRoutines() }.collectAsState(initial = emptyList())
    val routineExercises by remember(db) { db.routineDao().observeAllRoutineExercises() }.collectAsState(initial = emptyList())

    val routinesWithExercises = remember(routines, routineExercises) {
        val grouped = routineExercises.groupBy { it.routineId }
        routines.map { r -> RoutineManager.fromEntities(r, grouped[r.id].orEmpty()) }
    }
    val learnedAliases = remember(memories) {
        memories.filter { it.memoryType == "ALIAS" }.associate { it.sourceKey to it.targetValue }
    }
    val muscleOverrides = remember(memories, exercises) {
        val exGroups = exercises.associate { it.normalizedName to it.muscleGroup }
        val memOverrides = memories.filter { it.memoryType == "CLASSIFICATION_OVERRIDE" }.associate { it.sourceKey to it.targetValue }
        exGroups + memOverrides
    }
    val sequenceConfig = remember(memories) {
        NextSessionPlanner.extractConfigFromMemories(memories)
    }
    val nextSessionPlan = remember(sequenceConfig, sessions, allSets, exercises, routinesWithExercises, learnedAliases, muscleOverrides) {
        NextSessionPlanner.computeNextSession(
            config = sequenceConfig,
            completedSessions = sessions,
            allSets = allSets,
            exercises = exercises,
            routines = routinesWithExercises,
            learnedAliases = learnedAliases,
            muscleOverrides = muscleOverrides
        )
    }

    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("Inicio") }
    var isEditingProfile by remember { mutableStateOf(false) }
    var showIrisXConsole by remember { mutableStateOf(false) }
    var analyzerDraftText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(db) {
        val existing = db.exerciseDao().getAll()
        if (existing.isEmpty()) {
            db.exerciseDao().insertAll(ExerciseCatalog.toEntities())
            for (catEx in ExerciseCatalog.DEFAULT_CATALOG) {
                val entity = db.exerciseDao().findByNormalizedName(catEx.normalizedName)
                if (entity != null) {
                    val aliases = catEx.aliases.map {
                        ExerciseAliasEntity(
                            exerciseId = entity.id,
                            normalizedAlias = WorkoutTextParser.normalize(it)
                        )
                    }
                    db.exerciseAliasDao().insertAll(aliases)
                }
            }
        }
        profileFlow.first()
        loaded = true
    }

    if (!loaded) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    // Initial Onboarding flow (only when no profile exists in database)
    if (profile == null) {
        Scaffold(
            containerColor = Color.Black,
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            ProfileForm(
                current = null,
                error = error,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                onCancel = {},
                onSave = { name, preferred, height, grams, activity, goal ->
                    scope.launch {
                        try {
                            val now = System.currentTimeMillis()
                            db.withTransaction {
                                db.profileDao().save(ProfileEntity(
                                    name = name, preferredName = preferred, heightCm = height,
                                    initialWeightGrams = grams,
                                    activityLevel = activity, goal = goal,
                                    createdAtMillis = now,
                                    updatedAtMillis = now
                                ))
                                db.weighInDao().insert(WeighInEntity(measuredAtMillis = now, weightGrams = grams))
                            }
                            error = null
                            page = "Inicio"
                        } catch (e: Exception) {
                            error = "No se pudieron guardar los datos iniciales. Reintentá."
                        }
                    }
                }
            )
        }
        return
    }

    BackHandler(enabled = showIrisXConsole) {
        showIrisXConsole = false
    }

    BackHandler(enabled = !showIrisXConsole && page in SUB_PAGES_WITH_BACK_TO_HOME) {
        page = "Inicio"
    }

    Scaffold(
        containerColor = SyntraColors.BackgroundBlack,
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            if (!isEditingProfile) {
                FloatingActionButton(
                    onClick = { showIrisXConsole = true },
                    containerColor = SyntraColors.DarkMint,
                    contentColor = SyntraColors.NeonMint,
                    shape = CircleShape,
                    modifier = Modifier.size(56.dp)
                ) {
                    IrisXOrb(size = 38.dp, glowing = true)
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = SyntraColors.SurfaceDark,
                contentColor = SyntraColors.TextPrimary,
                tonalElevation = 6.dp
            ) {
                BOTTOM_NAV_DESTINATIONS.forEach { destination ->
                    NavigationBarItem(
                        selected = page == destination,
                        onClick = {
                            page = destination
                            if (destination != "Perfil") isEditingProfile = false
                        },
                        icon = {
                            Text(
                                text = when(destination) {
                                    "Inicio" -> "⌂"
                                    "Historial" -> "📋"
                                    "Semana" -> "📅"
                                    "Peso" -> "⚖"
                                    else -> "👤"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        },
                        label = {
                            Text(
                                text = destination,
                                fontSize = 11.sp,
                                fontWeight = if (page == destination) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF05100E),
                            selectedTextColor = SyntraColors.NeonMint,
                            indicatorColor = SyntraColors.NeonMint,
                            unselectedIconColor = SyntraColors.TextMuted,
                            unselectedTextColor = SyntraColors.TextMuted
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when(page) {
                "Inicio" -> HomeScreen(
                    profile = profile!!,
                    db = db,
                    sessions = sessions,
                    allSets = allSets,
                    exercises = exercises,
                    learnedAliases = learnedAliases,
                    muscleOverrides = muscleOverrides,
                    nextSessionPlan = nextSessionPlan,
                    activeDraft = activeDraft,
                    onNavigate = { targetPage -> page = targetPage },
                    onOpenIrisX = { showIrisXConsole = true }
                )
                "EntrenamientoActivo" -> ActiveWorkoutScreen(
                    db = db,
                    suggestedNextPlan = nextSessionPlan,
                    routinesWithExercises = routinesWithExercises,
                    onBackToHome = { page = "Inicio" },
                    onCompletedNavigateToHistory = { page = "Historial" }
                )
                "Historial" -> HistoryScreen(
                    db = db,
                    onNavigateToAnalyze = { page = "Analizar" },
                    onNavigateToHome = { page = "Inicio" }
                )
                "Semana" -> WeekScreen(
                    db = db,
                    nextSessionPlan = nextSessionPlan,
                    onNavigateToActiveWorkout = { page = "EntrenamientoActivo" },
                    onNavigateToHistory = { page = "Historial" }
                )
                "Analizar" -> ParserScreen(
                    db = db,
                    initialDraft = analyzerDraftText,
                    onDraftConsumed = { analyzerDraftText = null },
                    onNavigateToHistory = { page = "Historial" },
                    onOpenRoutines = { page = "Rutinas" }
                )
                "Estadísticas" -> AnalyticsAndCoachScreen(
                    db = db,
                    profile = profile!!,
                    onBack = { page = "Inicio" },
                    onOpenRoutines = { page = "Rutinas" }
                )
                "Rutinas" -> RoutinesScreen(
                    db = db,
                    onBack = { page = "Inicio" },
                    onApplyToAnalyzer = { template ->
                        analyzerDraftText = template
                        page = "Analizar"
                    },
                    onNavigateToHistory = { page = "Historial" }
                )
                "Peso" -> WeightScreen(db, error) { input ->
                    val grams = weightGrams(input)
                    if (grams == null) {
                        error = "Introducí un peso válido entre 20 y 400 kg."
                    } else {
                        scope.launch {
                            try {
                                db.weighInDao().insert(WeighInEntity(weightGrams = grams, measuredAtMillis = System.currentTimeMillis()))
                                error = null
                            } catch (e: Exception) {
                                error = "No se pudo registrar el pesaje."
                            }
                        }
                    }
                }
                "Perfil" -> {
                    BackHandler(enabled = isEditingProfile) {
                        isEditingProfile = false
                    }
                    if (isEditingProfile) {
                        ProfileForm(
                            current = profile,
                            error = error,
                            modifier = Modifier.fillMaxSize(),
                            onCancel = { isEditingProfile = false },
                            onSave = { name, preferred, height, grams, activity, goal ->
                                scope.launch {
                                    try {
                                        val now = System.currentTimeMillis()
                                        db.profileDao().save(ProfileEntity(
                                            name = name, preferredName = preferred, heightCm = height,
                                            initialWeightGrams = profile!!.initialWeightGrams,
                                            activityLevel = activity, goal = goal,
                                            createdAtMillis = profile!!.createdAtMillis,
                                            updatedAtMillis = now
                                        ))
                                        error = null
                                        isEditingProfile = false
                                    } catch (e: Exception) {
                                        error = "No se pudieron actualizar los datos del perfil."
                                    }
                                }
                            }
                        )
                    } else {
                        ProfileViewScreen(
                            profile = profile!!,
                            db = db,
                            neuralEngine = neuralEngine,
                            onStartEdit = { isEditingProfile = true },
                            onOpenIrisX = { showIrisXConsole = true },
                            onOpenManageModels = { showManageNeuralModelDialog = true },
                            onBackToHome = { page = "Inicio" }
                        )
                    }
                }
            }
        }
    }

    if (showIrisXConsole) {
        val activeWorkoutTitle = activeDraft?.let { d ->
            d.title.ifBlank { d.plannedCategory ?: "Sesión en curso" }
        }
        IrisXConsoleDialog(
            db = db,
            neuralEngine = neuralEngine,
            activeWorkoutTitle = activeWorkoutTitle,
            onDismiss = { showIrisXConsole = false }
        )
    }

    if (showManageNeuralModelDialog) {
        ManageNeuralModelDialog(
            engine = neuralEngine,
            onDismiss = { showManageNeuralModelDialog = false }
        )
    }
}

@Composable
private fun ProfileViewScreen(
    profile: ProfileEntity,
    db: AppDatabase,
    neuralEngine: NeuralInferenceEngine,
    onStartEdit: () -> Unit,
    onOpenIrisX: () -> Unit,
    onOpenManageModels: () -> Unit,
    onBackToHome: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupManager = remember(db) { BackupManager(db) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingRestorePayload by remember { mutableStateOf<SyntraBackupPayload?>(null) }
    var showExerciseLibrary by remember { mutableStateOf(false) }
    var showManageCategoriesDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = showExerciseLibrary) {
        showExerciseLibrary = false
    }

    BackHandler(enabled = showManageCategoriesDialog) {
        showManageCategoriesDialog = false
    }

    BackHandler(enabled = !showExerciseLibrary && !showManageCategoriesDialog) {
        onBackToHome()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = backupManager.exportToFile(context, uri)
                result.onSuccess { payload ->
                    val count = payload.weighIns.size
                    statusMessage = "Copia guardada con éxito ($count pesajes incluidos)."
                    errorMessage = null
                }.onFailure { err ->
                    errorMessage = "Error al exportar copia: ${err.message}"
                    statusMessage = null
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            when (val validation = backupManager.readAndValidateFromUri(context, uri)) {
                is BackupValidationResult.Success -> {
                    pendingRestorePayload = validation.payload
                    errorMessage = null
                }
                is BackupValidationResult.Error -> {
                    errorMessage = "Archivo no válido: ${validation.message}"
                    statusMessage = null
                }
            }
        }
    }

    if (pendingRestorePayload != null) {
        val payload = pendingRestorePayload!!
        val dateStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(payload.exportedAtMillis))
        val profileName = payload.profile?.name ?: "Sin perfil"
        val weighInsCount = payload.weighIns.size
        AlertDialog(
            onDismissRequest = { pendingRestorePayload = null },
            containerColor = Color(0xFF1E1E1E),
            title = {
                Text(
                    text = "Restaurar copia de seguridad",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Se restaurará el respaldo del $dateStr.",
                        color = Color.White
                    )
                    Text(
                        text = "• Perfil: $profileName\n• Registros de peso: $weighInsCount",
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "ADVERTENCIA: Esta acción reemplazará los datos actuales de la aplicación. Se ejecutará de forma atómica y segura.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val restoreResult = backupManager.restorePayload(payload)
                            restoreResult.onSuccess {
                                statusMessage = "Datos restaurados exitosamente."
                                errorMessage = null
                                pendingRestorePayload = null
                            }.onFailure { err ->
                                errorMessage = "Error al restaurar: ${err.message}"
                                statusMessage = null
                                pendingRestorePayload = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text("Restaurar ahora", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingRestorePayload = null }
                ) {
                    Text("Cancelar", color = Color.White)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text(
                text = "Mi perfil",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Datos y configuración guardados localmente",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Card 1: Datos personales
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
                    text = "Datos personales",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                ProfileFieldDisplay("Nombre completo", profile.name)
                ProfileFieldDisplay("Nombre para IrisX", profile.preferredName)
                ProfileFieldDisplay("Estatura", "${profile.heightCm} cm")
                ProfileFieldDisplay("Peso inicial", "${displayKg(profile.initialWeightGrams)} kg")
            }
        }

        // Card 2: Tu plan y enfoque (movido aquí desde Inicio)
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
                    text = "Tu plan y enfoque",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                ProfileFieldDisplay("Objetivo principal", profile.goal)
                ProfileFieldDisplay("Actividad cotidiana", profile.activityLevel)
            }
        }

        Button(
            onClick = onStartEdit,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Editar perfil", fontWeight = FontWeight.Bold)
        }

        // Card: Preferencias de entrenamiento
        val memories by remember(db) { db.irisxMemoryDao().observeAll() }.collectAsState(initial = emptyList())
        val keepScreenOnPref = remember(memories) {
            memories.firstOrNull { it.memoryType == "PREFERENCE" && it.sourceKey == "keep_screen_on_during_workout" }?.targetValue == "true"
        }
        val orgModePref = remember(memories) {
            memories.firstOrNull { it.memoryType == "PREFERENCE" && it.sourceKey == "workout_organization_mode" }?.targetValue ?: "BY_TRAINING_DAY"
        }

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
                    text = "Preferencias de entrenamiento",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Organización en Historial:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = orgModePref == "BY_TRAINING_DAY",
                        onClick = {
                            scope.launch {
                                val now = System.currentTimeMillis()
                                val existing = db.irisxMemoryDao().findByTypeAndKey("PREFERENCE", "workout_organization_mode")
                                db.irisxMemoryDao().insert(
                                    IrisxMemoryEntity(
                                        id = existing?.id ?: 0,
                                        memoryType = "PREFERENCE",
                                        sourceKey = "workout_organization_mode",
                                        targetValue = "BY_TRAINING_DAY",
                                        confidence = 1.0,
                                        createdAtMillis = existing?.createdAtMillis ?: now,
                                        updatedAtMillis = now
                                    )
                                )
                            }
                        },
                        label = { Text("Por día", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.Black
                        )
                    )
                    FilterChip(
                        selected = orgModePref == "BY_MUSCLE_GROUP",
                        onClick = {
                            scope.launch {
                                val now = System.currentTimeMillis()
                                val existing = db.irisxMemoryDao().findByTypeAndKey("PREFERENCE", "workout_organization_mode")
                                db.irisxMemoryDao().insert(
                                    IrisxMemoryEntity(
                                        id = existing?.id ?: 0,
                                        memoryType = "PREFERENCE",
                                        sourceKey = "workout_organization_mode",
                                        targetValue = "BY_MUSCLE_GROUP",
                                        confidence = 1.0,
                                        createdAtMillis = existing?.createdAtMillis ?: now,
                                        updatedAtMillis = now
                                    )
                                )
                            }
                        },
                        label = { Text("Por grupo muscular", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.Black
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Mantener pantalla encendida",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Text(
                            text = "Solo durante la sesión activa (cero wake locks)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = keepScreenOnPref,
                        onCheckedChange = { isChecked ->
                            scope.launch {
                                val now = System.currentTimeMillis()
                                val existing = db.irisxMemoryDao().findByTypeAndKey("PREFERENCE", "keep_screen_on_during_workout")
                                db.irisxMemoryDao().insert(
                                    IrisxMemoryEntity(
                                        id = existing?.id ?: 0,
                                        memoryType = "PREFERENCE",
                                        sourceKey = "keep_screen_on_during_workout",
                                        targetValue = isChecked.toString(),
                                        confidence = 1.0,
                                        createdAtMillis = existing?.createdAtMillis ?: now,
                                        updatedAtMillis = now
                                    )
                                )
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                OutlinedButton(
                    onClick = { showManageCategoriesDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📁 Administrar categorías de entrenamiento", style = MaterialTheme.typography.bodySmall, color = Color.White)
                }
            }
        }

        // Card 3: Seguridad y copias de seguridad (FASE 1)
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
                    text = "Seguridad y copias de seguridad",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Exportá o restaurá tus registros en formato JSON mediante Storage Access Framework, sin permisos invasivos ni conexión a internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val defaultFileName = "syntra_backup_${System.currentTimeMillis()}.json"
                            exportLauncher.launch(defaultFileName)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Exportar JSON", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Restaurar", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (statusMessage != null) {
                    Text(
                        text = statusMessage!!,
                        color = Color(0xFF4CAF50),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Card 4: IrisX Memory (FASE 7)
        val aliases = remember(memories) { memories.filter { it.memoryType == "ALIAS" } }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141818)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "IrisX Memory",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Estructuras de conocimiento desacopladas del historial deportivo. IrisX recuerda tus equivalencias y correcciones confirmadas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (aliases.isEmpty()) {
                    Text(
                        text = "Aún no tenés equivalencias personalizadas guardadas (ej. RDL → Peso muerto rumano). Podés crearlas o corregirlas cuando surjan ambigüedades.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        aliases.forEach { mem ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1E2422), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${mem.sourceKey.uppercase()} → ${mem.targetValue}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            db.irisxMemoryDao().deleteById(mem.id)
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Text("✕", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = onOpenIrisX,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("✦ Abrir consola de IrisX", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Card 5: Biblioteca de ejercicios (FASE 5)
        val exerciseCount by remember(db) { db.exerciseDao().observeAll() }.collectAsState(initial = emptyList())
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
                    text = "Biblioteca de ejercicios",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Explorá el catálogo de ${exerciseCount.size} ejercicios, consultá grupos musculares, creá nuevos o editá su estado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { showExerciseLibrary = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Explorar biblioteca", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Card 6: Modelos neuronales locales (IrisX GGUF)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
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
                    Text(
                        text = "Modelos neuronales GGUF",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (neuralEngine.isModelLoaded()) "● Activo" else "○ Inactivo",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (neuralEngine.isModelLoaded()) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
                Text(
                    text = if (neuralEngine.isModelLoaded())
                        "Modelo activo: ${neuralEngine.currentModelFile?.name} (${String.format("%.1f", (neuralEngine.currentModelFile?.length() ?: 0) / (1024.0 * 1024.0))} MB). Inferencia offline lista."
                    else
                        "Importá y administrá modelos como Qwen3-0.6B Q4_K_M para ejecutar inferencia neuronal privada 100% offline con llama.cpp en tu teléfono.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onOpenManageModels,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🧠 Administrar modelos GGUF", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (showExerciseLibrary) {
            ExerciseLibraryDialog(
                db = db,
                onDismiss = { showExerciseLibrary = false }
            )
        }

        if (showManageCategoriesDialog) {
            ManageCategoriesDialog(
                db = db,
                onDismiss = { showManageCategoriesDialog = false }
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProfileFieldDisplay(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
private fun SelectableOptionRow(
    text: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton
            )
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = Color.Gray
            )
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}

@Composable
private fun ProfileForm(
    current: ProfileEntity?,
    error: String?,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit,
    onSave: (String, String, Int, Int, String, String) -> Unit
) {
    var name by remember(current) { mutableStateOf(current?.name ?: "") }
    var preferred by remember(current) { mutableStateOf(current?.preferredName ?: "") }
    var height by remember(current) { mutableStateOf(current?.heightCm?.toString() ?: "") }
    var weight by remember(current) { mutableStateOf(current?.initialWeightGrams?.let(::displayKg) ?: "") }
    var activity by remember(current) { mutableStateOf(current?.activityLevel ?: "Sedentario") }
    var goal by remember(current) { mutableStateOf(current?.goal ?: "Seguimiento general") }
    var validation by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text(
                text = if (current == null) "Bienvenido a Syntra" else "Editar perfil",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Tus datos se guardan exclusivamente en este dispositivo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Card 1: Datos Personales
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
                    text = "Datos personales",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre completo") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = preferred,
                    onValueChange = { preferred = it },
                    label = { Text("¿Cómo querés que te llame IrisX?") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it },
                    label = { Text("Estatura (cm)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                if (current == null) {
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { weight = it },
                        label = { Text("Peso inicial (kg)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                } else {
                    Text(
                        text = "El peso inicial queda registrado como primer pesaje. Podés registrar pesajes actualizados en la pestaña Peso.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Card 2: Actividad
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Actividad cotidiana",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                listOf("Sedentario", "Ligero", "Moderado", "Alto").forEach { item ->
                    SelectableOptionRow(
                        text = item,
                        selected = (activity == item),
                        onSelect = { activity = item }
                    )
                }
            }
        }

        // Card 3: Objetivo
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Objetivo principal",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                listOf(
                    "Seguimiento general",
                    "Perder grasa",
                    "Ganar masa muscular",
                    "Mejorar fuerza",
                    "Mantener peso"
                ).forEach { item ->
                    SelectableOptionRow(
                        text = item,
                        selected = (goal == item),
                        onSelect = { goal = item }
                    )
                }
            }
        }

        if (validation != null || error != null) {
            Text(
                text = validation ?: error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Button(
            onClick = {
                val w = if (current == null) weightGrams(weight) else current.initialWeightGrams
                val errorMsg = InputValidators.validateProfile(name, preferred, height, w)
                if (errorMsg != null) {
                    validation = errorMsg
                } else {
                    validation = null
                    onSave(name.trim(), preferred.trim(), height.trim().toInt(), w!!, activity, goal)
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(if (current == null) "Guardar perfil" else "Guardar cambios", fontWeight = FontWeight.Bold)
        }

        if (current != null) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Volver sin cambios", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HomeScreen(
    profile: ProfileEntity,
    db: AppDatabase,
    sessions: List<WorkoutSessionEntity>,
    allSets: List<WorkoutSetEntity>,
    exercises: List<ExerciseEntity>,
    learnedAliases: Map<String, String>,
    muscleOverrides: Map<String, String>,
    nextSessionPlan: NextSessionPlan,
    activeDraft: ActiveWorkoutDraftEntity?,
    onNavigate: (String) -> Unit,
    onOpenIrisX: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appVersionName = remember(context) { getAppVersionName(context) }
    val history by remember(db) { db.weighInDao().observeAll() }.collectAsState(initial = emptyList())
    val exercisesById = remember(exercises) { exercises.associateBy { it.id } }

    var showSequenceConfigDialog by remember { mutableStateOf(false) }
    var sequenceInputText by remember(nextSessionPlan.sequenceSteps) {
        mutableStateOf(
            if (nextSessionPlan.sequenceSteps.isNotEmpty()) {
                nextSessionPlan.sequenceSteps.joinToString(", ")
            } else {
                "Pecho, Espalda, Piernas"
            }
        )
    }
    var manualStepInput by remember(nextSessionPlan.nextCategoryName, nextSessionPlan.manualOverrideActive) {
        mutableStateOf(if (nextSessionPlan.manualOverrideActive) nextSessionPlan.nextCategoryName.orEmpty() else "")
    }

    if (showSequenceConfigDialog) {
        AlertDialog(
            onDismissRequest = {
                manualStepInput = ""
                showSequenceConfigDialog = false
            },
            containerColor = SyntraColors.SurfaceCardElevated,
            title = {
                Text(
                    text = "Configurar secuencia de entrenamiento",
                    fontWeight = FontWeight.Bold,
                    color = SyntraColors.TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "1. Secuencia rotativa persistente (separada por comas):",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SyntraColors.NeonMint
                    )
                    OutlinedTextField(
                        value = sequenceInputText,
                        onValueChange = { sequenceInputText = it },
                        label = { Text("Secuencia rotativa") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "2. Selección manual de próxima sesión:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SyntraColors.NeonMint
                    )

                    if (nextSessionPlan.manualOverrideActive && nextSessionPlan.nextCategoryName != null) {
                        Surface(
                            color = SyntraColors.DarkMint.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, SyntraColors.NeonMint.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Turno manual activo:", style = MaterialTheme.typography.labelSmall, color = SyntraColors.TextMuted)
                                    Text(nextSessionPlan.nextCategoryName.orEmpty(), fontWeight = FontWeight.Bold, color = SyntraColors.NeonMint)
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            val existingManual = db.irisxMemoryDao().findByTypeAndKey(
                                                NextSessionPlanner.MEMORY_TYPE_SEQUENCE,
                                                NextSessionPlanner.KEY_MANUAL_NEXT
                                            )
                                            if (existingManual != null) {
                                                db.irisxMemoryDao().deleteById(existingManual.id)
                                            }
                                            manualStepInput = ""
                                        }
                                    }
                                ) {
                                    Text("Quitar", color = SyntraColors.Error)
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = manualStepInput,
                        onValueChange = { manualStepInput = it },
                        label = { Text("Asignar turno manual para hoy (ej. Piernas)") },
                        placeholder = { Text("Dejar vacío para seguir rotación") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                SyntraButton(
                    text = "Guardar cambios",
                    onClick = {
                        scope.launch {
                            val now = System.currentTimeMillis()
                            val steps = NextSessionPlanner.parseSequenceSteps(sequenceInputText)
                            val serialized = NextSessionPlanner.serializeSequenceSteps(steps)
                            val existingSteps = db.irisxMemoryDao().findByTypeAndKey(
                                NextSessionPlanner.MEMORY_TYPE_SEQUENCE,
                                NextSessionPlanner.KEY_STEPS
                            )
                            if (serialized.isNotBlank()) {
                                db.irisxMemoryDao().insert(
                                    IrisxMemoryEntity(
                                        id = existingSteps?.id ?: 0,
                                        memoryType = NextSessionPlanner.MEMORY_TYPE_SEQUENCE,
                                        sourceKey = NextSessionPlanner.KEY_STEPS,
                                        targetValue = serialized,
                                        confidence = 1.0,
                                        createdAtMillis = existingSteps?.createdAtMillis ?: now,
                                        updatedAtMillis = now
                                    )
                                )
                            } else if (existingSteps != null) {
                                db.irisxMemoryDao().deleteById(existingSteps.id)
                            }

                            val existingManual = db.irisxMemoryDao().findByTypeAndKey(
                                NextSessionPlanner.MEMORY_TYPE_SEQUENCE,
                                NextSessionPlanner.KEY_MANUAL_NEXT
                            )
                            val cleanManual = manualStepInput.trim()
                            if (cleanManual.isNotBlank()) {
                                db.irisxMemoryDao().insert(
                                    IrisxMemoryEntity(
                                        id = existingManual?.id ?: 0,
                                        memoryType = NextSessionPlanner.MEMORY_TYPE_SEQUENCE,
                                        sourceKey = NextSessionPlanner.KEY_MANUAL_NEXT,
                                        targetValue = cleanManual,
                                        confidence = 1.0,
                                        createdAtMillis = existingManual?.createdAtMillis ?: now,
                                        updatedAtMillis = now
                                    )
                                )
                            } else if (existingManual != null && manualStepInput.isNotEmpty()) {
                                db.irisxMemoryDao().deleteById(existingManual.id)
                            }
                            manualStepInput = ""
                            showSequenceConfigDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                SyntraSecondaryButton(
                    text = "Cancelar",
                    onClick = {
                        manualStepInput = ""
                        showSequenceConfigDialog = false
                    }
                )
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Saludo personalizado con versión dinámica
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Hola, ${profile.preferredName} 👋",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = SyntraColors.TextPrimary
                )
                Text(
                    text = "Syntra v$appVersionName • Listo para entrenar hoy?",
                    style = MaterialTheme.typography.bodySmall,
                    color = SyntraColors.SoftMint
                )
            }
            Surface(
                color = SyntraColors.SurfaceCardElevated,
                shape = CircleShape,
                border = BorderStroke(1.dp, SyntraColors.BorderSubtle)
            ) {
                Text(
                    text = history.firstOrNull()?.let { "⚖ ${displayKg(it.weightGrams)} kg" } ?: "⚖ Sin peso",
                    style = MaterialTheme.typography.labelMedium,
                    color = SyntraColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // 2. Tarjeta de última sesión (Sin redundancia de título, estilo Neural)
        SyntraCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { if (sessions.isNotEmpty()) onNavigate("Historial") }
        ) {
            if (sessions.isNotEmpty()) {
                val latest = sessions.first()
                val latestSets = remember(latest.id, allSets) { allSets.filter { it.sessionId == latest.id } }
                val latestExerciseCount = remember(latestSets) { latestSets.map { it.exerciseId }.distinct().size }
                val latestCategory = remember(latest, latestSets, exercisesById, learnedAliases, muscleOverrides) {
                    NextSessionPlanner.classifyCompletedSession(
                        session = latest,
                        sessionSets = latestSets,
                        exercisesById = exercisesById,
                        learnedAliases = learnedAliases,
                        muscleOverrides = muscleOverrides
                    )
                }
                val dateStr = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(latest.startedAtMillis))

                val cleanTitle = if (latest.title.trim().equals(latestCategory.displayName.trim(), ignoreCase = true) ||
                    latest.title.trim().contains(latestCategory.displayName.trim(), ignoreCase = true)) {
                    latest.title.trim()
                } else {
                    "${latestCategory.displayName} — ${latest.title.trim()}"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ÚLTIMA SESIÓN",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SyntraColors.TextMuted
                    )
                    SyntraBadge(
                        text = "Ver historial ›",
                        containerColor = SyntraColors.SurfaceHighlight,
                        contentColor = SyntraColors.NeonMint
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SyntraColors.SurfaceHighlight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = latestCategory.icon.ifBlank { "🏋" },
                            fontSize = 22.sp
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = cleanTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SyntraColors.TextPrimary
                        )
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.bodySmall,
                            color = SyntraColors.SoftMint
                        )
                        Text(
                            text = "$latestExerciseCount ejercicios • ${latestSets.size} series",
                            style = MaterialTheme.typography.bodySmall,
                            color = SyntraColors.TextSecondary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(SyntraColors.NeonMint.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✓", color = SyntraColors.NeonMint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "ÚLTIMA SESIÓN",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SyntraColors.TextMuted
                    )
                    Text(
                        text = "Aún no registraste sesiones completadas",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = SyntraColors.TextPrimary
                    )
                    Text(
                        text = "Iniciá tu primer entrenamiento activo para ver acá tu categoría, ejercicios y series.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SyntraColors.TextSecondary
                    )
                }
            }
        }

        // 3. Tarjeta de próxima sesión (Secuencia configurable)
        SyntraCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PRÓXIMA SESIÓN",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SyntraColors.NeonMint
                    )
                    TextButton(
                        onClick = { showSequenceConfigDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (nextSessionPlan.isConfigured) "⚙ Secuencia" else "Configurar",
                            style = MaterialTheme.typography.labelSmall,
                            color = SyntraColors.SoftMint
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SyntraColors.SurfaceHighlight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📅", fontSize = 22.sp)
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = nextSessionPlan.nextCategoryName ?: "Sin secuencia definida",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SyntraColors.TextPrimary
                        )
                        Text(
                            text = nextSessionPlan.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = SyntraColors.TextSecondary
                        )
                    }
                }

                if (nextSessionPlan.manualOverrideActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SyntraBadge(
                            text = "★ Selección manual activa",
                            containerColor = SyntraColors.DarkMint,
                            contentColor = SyntraColors.NeonMint
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val existingManual = db.irisxMemoryDao().findByTypeAndKey(
                                        NextSessionPlanner.MEMORY_TYPE_SEQUENCE,
                                        NextSessionPlanner.KEY_MANUAL_NEXT
                                    )
                                    if (existingManual != null) {
                                        db.irisxMemoryDao().deleteById(existingManual.id)
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Volver a rotación ↺", style = MaterialTheme.typography.labelSmall, color = SyntraColors.SoftMint)
                        }
                    }
                }

                if (nextSessionPlan.suggestedRoutine != null) {
                    Text(
                        text = "🗂 Rutina vinculada: ${nextSessionPlan.suggestedRoutine.routine.name} (${nextSessionPlan.suggestedRoutine.exercises.size} ejercicios)",
                        style = MaterialTheme.typography.labelSmall,
                        color = SyntraColors.SoftMint
                    )
                }
            }
        }

        // 4. Botón principal destacado: ▶ Iniciar entrenamiento
        val hasRecoverableDraft = activeDraft != null &&
            WorkoutSessionState.fromString(activeDraft.state) in setOf(
                WorkoutSessionState.ACTIVE,
                WorkoutSessionState.REVIEW_PENDING,
                WorkoutSessionState.ERROR
            )

        val btnLabel = if (hasRecoverableDraft) {
            val draftTitle = activeDraft.title.takeIf { it.isNotBlank() }
                ?: activeDraft.plannedCategory
                ?: "en curso"
            "▶ Continuar ($draftTitle)"
        } else {
            "▶ Iniciar entrenamiento"
        }

        SyntraButton(
            text = btnLabel,
            onClick = { onNavigate("EntrenamientoActivo") },
            modifier = Modifier.fillMaxWidth()
        )

        // 5. Tarjeta IrisX con resplandor neón menta y orbe bioluminiscente
        SyntraCard(
            modifier = Modifier.fillMaxWidth(),
            glowingBorder = true,
            onClick = onOpenIrisX
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IrisXOrb(size = 46.dp, glowing = true)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "IrisX",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SyntraColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        SyntraBadge(
                            text = "Copiloto IA",
                            containerColor = SyntraColors.DarkMint,
                            contentColor = SyntraColors.NeonMint
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Tu asistente de entrenamiento con IA",
                        style = MaterialTheme.typography.bodySmall,
                        color = SyntraColors.TextSecondary
                    )
                }
                Text(
                    text = "›",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SyntraColors.NeonMint,
                    fontWeight = FontWeight.Light
                )
            }
        }

        // 6. Accesos secundarios limpios a Estadísticas y Rutinas
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SyntraSecondaryButton(
                text = "📊 Estadísticas & Coach",
                onClick = { onNavigate("Estadísticas") },
                modifier = Modifier.weight(1f)
            )
            SyntraSecondaryButton(
                text = "🗂 Rutinas",
                onClick = { onNavigate("Rutinas") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun WeightScreen(db: AppDatabase, error: String?, save: (String) -> Unit) {
    val history by remember(db) { db.weighInDao().observeAll() }.collectAsState(initial = emptyList())
    var weightInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Seguimiento de peso",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = SyntraColors.TextPrimary
        )

        SyntraCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = weightInput,
                    onValueChange = { weightInput = it },
                    label = { Text("Nuevo pesaje (kg)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SyntraColors.NeonMint,
                        unfocusedBorderColor = SyntraColors.BorderSubtle,
                        focusedTextColor = SyntraColors.TextPrimary,
                        unfocusedTextColor = SyntraColors.TextPrimary
                    )
                )
                SyntraButton(
                    text = "Registrar pesaje",
                    onClick = {
                        save(weightInput)
                        if (weightGrams(weightInput) != null) weightInput = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(text = error, color = SyntraColors.Error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text(
            text = "Historial (${history.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = SyntraColors.TextPrimary
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(history, key = { it.id }) { item ->
                SyntraCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${displayKg(item.weightGrams)} kg",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = SyntraColors.NeonMint
                            )
                            if (item.note.isNotBlank()) {
                                Text(item.note, style = MaterialTheme.typography.bodySmall, color = SyntraColors.TextMuted)
                            }
                        }
                        Text(
                            text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.measuredAtMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = SyntraColors.TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ParserScreen(
    db: AppDatabase,
    initialDraft: String? = null,
    onDraftConsumed: () -> Unit = {},
    onNavigateToHistory: () -> Unit,
    onOpenRoutines: () -> Unit = {}
) {
    var input by remember { mutableStateOf("Piernas\nSentadilla 70kg 12 10 8\nSentadilla 65kg 8\nBuenos días 40kg 12 10 10") }
    var analyzed by remember { mutableStateOf(false) }

    LaunchedEffect(initialDraft) {
        if (!initialDraft.isNullOrBlank()) {
            input = initialDraft
            analyzed = true
            onDraftConsumed()
        }
    }
    var showSaveConfirmation by remember { mutableStateOf(false) }
    var saveSuccess by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val dbExercises by remember(db) { db.exerciseDao().observeAll() }.collectAsState(initial = emptyList())
    val knownNames = remember(dbExercises) { dbExercises.map { it.normalizedName }.toSet() }
    val dbMemories by remember(db) { db.irisxMemoryDao().observeAll() }.collectAsState(initial = emptyList())
    val memoryAliases = remember(dbMemories) {
        dbMemories.filter { it.memoryType == "ALIAS" }.associate { it.sourceKey to it.targetValue }
    }

    val sampleAliases = remember {
        mapOf(
            "rdl" to "Peso muerto rumano",
            "press banca" to "Press de banca",
            "buenos dias" to "Buenos días con barra",
            "laterales" to "Elevaciones laterales"
        )
    }
    val mergedAliases = remember(memoryAliases) { sampleAliases + memoryAliases }

    val preview = remember(input, analyzed, knownNames, mergedAliases) {
        if (analyzed) WorkoutTextParser.parse(input, aliases = mergedAliases, knownExercises = knownNames) else null
    }
    val rawParsedExercises = remember(preview) { preview?.exercises ?: emptyList() }
    var editedExercises by remember(rawParsedExercises) { mutableStateOf(rawParsedExercises) }
    var exerciseToEdit by remember { mutableStateOf<ParsedExercise?>(null) }

    val currentExercises = editedExercises
    val compactExercises = remember(currentExercises) {
        WorkoutPresentation.toCompactList(currentExercises)
    }

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

    // Modal de confirmación para guardado atómico (FASE 5)
    if (showSaveConfirmation && preview != null) {
        val newExercises = compactExercises.filter { it.isNewExercise }
        AlertDialog(
            onDismissRequest = { showSaveConfirmation = false },
            containerColor = Color(0xFF1E1E1E),
            title = {
                Text(
                    text = "Confirmar y guardar entrenamiento",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Sesión: ${preview.sessionTitle ?: "Entrenamiento"}",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "• ${compactExercises.size} ejercicios • ${currentExercises.sumOf { it.sets.size }} series en total",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (newExercises.isNotEmpty()) {
                        Text(
                            text = "✨ Se incorporarán a tu biblioteca los siguientes ejercicios nuevos: ${newExercises.joinToString { it.name }}.",
                            color = Color(0xFF81C784),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (preview.warnings.isNotEmpty()) {
                        Text(
                            text = "⚠ Se detectaron advertencias en el texto. Verificalas antes de guardar.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val now = System.currentTimeMillis()
                                val title = preview.sessionTitle ?: "Entrenamiento"
                                db.withTransaction {
                                    val sId = db.workoutSessionDao().insert(
                                        WorkoutSessionEntity(
                                            title = title,
                                            startedAtMillis = now,
                                            finishedAtMillis = now,
                                            notes = ""
                                        )
                                    )
                                    var execOrder = 1
                                    currentExercises.forEach { ex ->
                                        val norm = WorkoutTextParser.normalize(ex.name)
                                        var exEntity = db.exerciseDao().findByNormalizedName(norm)
                                        if (exEntity == null) {
                                            val newId = db.exerciseDao().insert(
                                                ExerciseEntity(
                                                    displayName = ex.name,
                                                    normalizedName = norm,
                                                    muscleGroup = "General",
                                                    secondaryMuscleGroups = "",
                                                    equipment = null,
                                                    status = if (ex.isNewExercise) "PENDING_CONFIRMATION" else "CONFIRMED",
                                                    createdAtMillis = now
                                                )
                                            )
                                            exEntity = db.exerciseDao().findById(newId) ?: ExerciseEntity(
                                                id = newId,
                                                displayName = ex.name,
                                                normalizedName = norm,
                                                muscleGroup = "General",
                                                createdAtMillis = now
                                            )
                                        }
                                        ex.sets.forEachIndexed { idx, s ->
                                            db.workoutSetDao().insert(
                                                WorkoutSetEntity(
                                                    sessionId = sId,
                                                    exerciseId = exEntity.id,
                                                    orderInExercise = idx + 1,
                                                    executionOrder = execOrder++,
                                                    loadGrams = s.weightGrams,
                                                    repetitions = s.repetitions,
                                                    rir = null,
                                                    isAssisted = s.isAssisted,
                                                    isDumbbell = s.isDumbbell,
                                                    note = s.note,
                                                    plannedTarget = ex.plannedTarget
                                                )
                                            )
                                        }
                                    }
                                }
                                saveSuccess = true
                                saveError = null
                                showSaveConfirmation = false
                            } catch (e: Exception) {
                                saveError = "Error al guardar sesión: ${e.message}"
                                showSaveConfirmation = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Confirmar y guardar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSaveConfirmation = false }) {
                    Text("Cancelar", color = Color.White)
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Analizador de entrenamientos",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Interpretación avanzada • Tarjeta única • Guardado persistente",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            OutlinedButton(
                onClick = onOpenRoutines,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("🗂 Rutinas", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it; analyzed = false; saveSuccess = false },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            label = { Text("Escribí tu sesión") }
        )

        Button(
            onClick = { analyzed = true; saveSuccess = false },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Interpretar sesión", fontWeight = FontWeight.Bold)
        }

        if (saveSuccess) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF14241B)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("¡Entrenamiento guardado!", fontWeight = FontWeight.Bold, color = Color(0xFF81C784))
                        Text("Quedó registrado de forma atómica en SQLite.", style = MaterialTheme.typography.bodySmall, color = Color.White)
                    }
                    Button(
                        onClick = onNavigateToHistory,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black)
                    ) {
                        Text("Ver historial", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (saveError != null) {
            Text(text = saveError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (preview != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${compactExercises.size} ejercicios • ${preview.seriesCount} series totales",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                preview.sessionTitle?.let {
                    Text(
                        text = "Sesión: $it",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Exactly ONE card per exercise, with a table containing multiple rows
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
                                    exerciseToEdit = currentExercises.find { it.name == exercise.name }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("✎ Editar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        if (exercise.plannedTarget != null) {
                            Text(
                                text = "🎯 Plan: ${exercise.plannedTarget}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.LightGray
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        // Table Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF222222), RoundedCornerShape(6.dp))
                                .padding(vertical = 7.dp, horizontal = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Series",
                                fontWeight = FontWeight.SemiBold,
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "Peso",
                                fontWeight = FontWeight.SemiBold,
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1.2f)
                            )
                            Text(
                                text = "Repeticiones",
                                fontWeight = FontWeight.SemiBold,
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(2f)
                            )
                        }

                        // Table Rows
                        exercise.groups.forEachIndexed { index, group ->
                            val rowBg = if (index % 2 == 1) Color(0xFF1C1C1C) else Color.Transparent
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(rowBg, RoundedCornerShape(4.dp))
                                    .padding(vertical = 8.dp, horizontal = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${group.setCount}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = group.formattedWeight(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1.2f)
                                )
                                Text(
                                    text = group.formattedReps(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    modifier = Modifier.weight(2f)
                                )
                            }
                            if (index < exercise.groups.size - 1) {
                                HorizontalDivider(color = Color(0xFF262626), thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }

            if (compactExercises.isNotEmpty()) {
                Button(
                    onClick = { showSaveConfirmation = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("💾 Guardar entrenamiento en historial", fontWeight = FontWeight.Bold)
                }
            }

            if (preview.assistantCommands.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101C18)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Directivas para IrisX:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        preview.assistantCommands.forEach { directive ->
                            Text(
                                text = "• Línea ${directive.line}: \"${directive.command}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            preview.warnings.forEach { warning ->
                Text(
                    text = "⚠ $warning",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HistoryScreen(
    db: AppDatabase,
    onNavigateToAnalyze: () -> Unit,
    onNavigateToHome: () -> Unit
) {
    val sessions by remember(db) { db.workoutSessionDao().observeAll() }.collectAsState(initial = emptyList())
    val exercises by remember(db) { db.exerciseDao().observeAll() }.collectAsState(initial = emptyList())
    val allSets by remember(db) { db.workoutSetDao().observeAll() }.collectAsState(initial = emptyList())
    val memories by remember(db) { db.irisxMemoryDao().observeAll() }.collectAsState(initial = emptyList())
    val configuredCategories by remember(db) { db.categoryDao().observeAllActive() }.collectAsState(initial = emptyList())

    val exerciseNameMap = remember(exercises) { exercises.associate { it.id to it.displayName } }
    val memoryAliases = remember(memories) {
        memories.filter { it.memoryType == "ALIAS" }.associate { it.sourceKey to it.targetValue }
    }
    val sessionExerciseNames = remember(allSets, exerciseNameMap) {
        allSets.groupBy { it.sessionId }.mapValues { entry ->
            entry.value.mapNotNull { exerciseNameMap[it.exerciseId] }.distinct()
        }
    }
    val scope = rememberCoroutineScope()
    var sessionToDelete by remember { mutableStateOf<WorkoutSessionEntity?>(null) }
    var sessionToEdit by remember { mutableStateOf<WorkoutSessionEntity?>(null) }
    var selectedCategorySummary by remember { mutableStateOf<DynamicCategorySummary?>(null) }

    val sessionCategoryMap = remember(sessions, sessionExerciseNames, memoryAliases) {
        sessions.associate { s ->
            val exNames = sessionExerciseNames[s.id] ?: emptyList()
            val resolved = WorkoutCategoryClassifier.resolveSessionCategory(
                explicitCategory = s.category,
                title = s.title,
                exerciseNames = exNames,
                learnedAliases = memoryAliases
            )
            s.id to resolved
        }
    }
    val dynamicSummaries = remember(sessions, configuredCategories, sessionCategoryMap) {
        WorkoutCategoryClassifier.buildDynamicSummaries(sessions, configuredCategories, sessionCategoryMap)
    }

    BackHandler(enabled = sessionToEdit != null) {
        sessionToEdit = null
    }

    BackHandler(enabled = sessionToEdit == null && selectedCategorySummary != null) {
        selectedCategorySummary = null
    }

    BackHandler(enabled = sessionToEdit == null && selectedCategorySummary == null) {
        onNavigateToHome()
    }

    if (sessionToEdit != null) {
        EditWorkoutSessionDialog(
            session = sessionToEdit!!,
            db = db,
            exerciseNameMap = exerciseNameMap,
            onDismiss = { sessionToEdit = null }
        )
    }

    if (sessionToDelete != null) {
        val s = sessionToDelete!!
        val dateStr = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startedAtMillis))
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            containerColor = Color(0xFF1E1E1E),
            title = {
                Text("Eliminar sesión", fontWeight = FontWeight.Bold, color = Color.White)
            },
            text = {
                Text(
                    "¿Seguro que deseás eliminar el entrenamiento '${s.title}' del $dateStr? Esta acción borrará todas sus series de forma permanente.",
                    color = Color.White
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            db.withTransaction {
                                db.workoutSetDao().deleteForSession(s.id)
                                db.workoutSessionDao().deleteById(s.id)
                            }
                            sessionToDelete = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Eliminar", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { sessionToDelete = null }) {
                    Text("Cancelar", color = Color.White)
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (selectedCategorySummary == null) {
            // Nivel 1: Categorías
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Historial deportivo",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Categorías • ${sessions.size} sesiones en total",
                        style = MaterialTheme.typography.bodySmall,
                        color = SyntraColors.SoftMint
                    )
                }
                SyntraSecondaryButton(
                    text = "✎ Nueva",
                    onClick = onNavigateToAnalyze,
                    modifier = Modifier.height(38.dp)
                )
            }

            if (sessions.isEmpty()) {
                SyntraCard(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Aún no tenés entrenamientos guardados",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SyntraColors.TextPrimary
                        )
                        Text(
                            text = "Escribí o pegá tus ejercicios en el Analizador para guardar tu primera sesión con orden de ejecución y cargas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SyntraColors.TextSecondary
                        )
                        SyntraButton(
                            text = "Ir a Analizar sesión",
                            onClick = onNavigateToAnalyze
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(dynamicSummaries, key = { it.categoryName }) { summary ->
                        SyntraCard(
                            onClick = { selectedCategorySummary = summary },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(SyntraColors.SurfaceHighlight),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = summary.icon, fontSize = 20.sp)
                                    }
                                    Column {
                                        Text(
                                            text = summary.categoryName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = SyntraColors.TextPrimary
                                        )
                                        Text(
                                            text = if (summary.sessionCount > 0) "${summary.sessionCount} sesiones" else "Sin registros",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (summary.sessionCount > 0) SyntraColors.NeonMint else SyntraColors.TextMuted
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    if (summary.latestSessionMillis != null) {
                                        Text(
                                            text = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(summary.latestSessionMillis)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SyntraColors.TextMuted
                                        )
                                    }
                                    Text("›", color = SyntraColors.NeonMint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Nivel 2: Sesiones por fecha de la categoría seleccionada
            val currentSummary = selectedCategorySummary!!
            val categorySessions = remember(sessions, currentSummary, sessionCategoryMap) {
                if (currentSummary.isAll) {
                    sessions
                } else if (currentSummary.categoryName == "Otras / Sin clasificar") {
                    sessions.filter {
                        val c = sessionCategoryMap[it.id].orEmpty()
                        c.isEmpty() || c.equals("Sin clasificar", ignoreCase = true) || c.equals("Otras / Sin clasificar", ignoreCase = true)
                    }
                } else {
                    sessions.filter {
                        sessionCategoryMap[it.id].equals(currentSummary.categoryName, ignoreCase = true)
                    }
                }.sortedByDescending { it.startedAtMillis }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { selectedCategorySummary = null },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("← Categorías", style = MaterialTheme.typography.bodySmall, color = Color.White)
                    }
                    Column {
                        Text(
                            text = "${currentSummary.icon} ${currentSummary.categoryName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${categorySessions.size} sesiones",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                OutlinedButton(
                    onClick = onNavigateToAnalyze,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("+ Nueva", style = MaterialTheme.typography.bodySmall, color = Color.White)
                }
            }

            if (categorySessions.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "No hay sesiones de ${currentSummary.categoryName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Registrá tu primer entrenamiento para esta categoría desde el Analizador.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Button(
                            onClick = onNavigateToAnalyze,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Registrar entrenamiento", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(categorySessions, key = { it.id }) { session ->
                        SessionHistoryCard(
                            session = session,
                            categoryKey = null,
                            db = db,
                            exerciseNameMap = exerciseNameMap,
                            onEdit = { sessionToEdit = session },
                            onDelete = { sessionToDelete = session }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHistoryCard(
    session: WorkoutSessionEntity,
    categoryKey: WorkoutCategoryKey? = null,
    db: AppDatabase,
    exerciseNameMap: Map<Long, String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val sets by remember(session.id) { db.workoutSetDao().observeForSession(session.id) }.collectAsState(initial = emptyList())
    val dateStr = remember(session.startedAtMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(session.startedAtMillis))
    }

    val isCategoryRedundant = remember(session.title, session.category, categoryKey) {
        val titleNorm = session.title.trim().lowercase()
        val catNorm = session.category.trim().lowercase().ifEmpty { categoryKey?.displayName?.trim()?.lowercase() ?: "" }
        titleNorm == catNorm || titleNorm == "entrenamiento" || titleNorm == "rutina" || titleNorm.isBlank()
    }

    SyntraCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    if (isCategoryRedundant) {
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SyntraColors.TextPrimary
                        )
                    } else {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SyntraColors.TextPrimary
                        )
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = SyntraColors.SoftMint
                        )
                    }
                    if (session.category.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        SyntraBadge(
                            text = session.category,
                            containerColor = SyntraColors.DarkMint,
                            contentColor = SyntraColors.NeonMint
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onEdit) {
                        Text("✎", color = SyntraColors.NeonMint, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Ocultar ▲" else "Ver detalle ▼", style = MaterialTheme.typography.labelSmall, color = SyntraColors.SoftMint)
                    }
                    IconButton(onClick = onDelete) {
                        Text("🗑", color = SyntraColors.Error)
                    }
                }
            }

            Text(
                text = "${sets.size} series registradas",
                style = MaterialTheme.typography.bodySmall,
                color = SyntraColors.TextSecondary
            )

            if (expanded) {
                Spacer(Modifier.height(4.dp))
                val exerciseGroups: List<CompactExercise> = remember(sets, exerciseNameMap) {
                    val grouped = sets.groupBy { it.exerciseId }
                    grouped.map { entry ->
                        val exId = entry.key
                        val exSets = entry.value
                        val exName = exerciseNameMap[exId] ?: "Ejercicio #$exId"
                        val parsedSets = exSets.map { s ->
                            ParsedSet(
                                weightGrams = s.loadGrams,
                                repetitions = s.repetitions,
                                isAssisted = s.isAssisted,
                                isDumbbell = s.isDumbbell,
                                note = s.note
                            )
                        }
                        val planned = exSets.firstOrNull { it.plannedTarget != null }?.plannedTarget
                        CompactExercise(
                            name = exName,
                            groups = WorkoutPresentation.groupConsecutiveSets(parsedSets),
                            plannedTarget = planned
                        )
                    }
                }

                exerciseGroups.forEach { compEx ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = compEx.name,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (compEx.plannedTarget != null) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = Color(0xFF262626),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "🎯 ${compEx.plannedTarget}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.LightGray,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        compEx.groups.forEach { grp ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
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
    }
}
