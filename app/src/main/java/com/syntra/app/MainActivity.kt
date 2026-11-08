package com.syntra.app

import android.os.Bundle
import androidx.activity.ComponentActivity
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
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color.Black,
                surface = Color(0xFF141414),
                surfaceVariant = Color(0xFF1E1E1E),
                primary = Color(0xFF75D9C1),
                onPrimary = Color.Black,
                onBackground = Color.White,
                onSurface = Color.White,
                onSurfaceVariant = Color(0xFFAAAAAA),
                error = Color(0xFFFF6B6B)
            )) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black,
                    contentColor = Color.White
                ) {
                    TrainerApp(database)
                }
            }
        }
    }
}

private fun weightGrams(input: String): Int? = InputValidators.parseWeightGrams(input)

private fun displayKg(grams: Int): String = InputValidators.formatWeightKg(grams)

@Composable
private fun TrainerApp(db: AppDatabase) {
    val scope = rememberCoroutineScope()
    val profileFlow = remember(db) { db.profileDao().observe() }
    val profile by profileFlow.collectAsState(initial = null)
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("Inicio") }
    var isEditingProfile by remember { mutableStateOf(false) }

    LaunchedEffect(db) { profileFlow.first(); loaded = true }

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

    Scaffold(
        containerColor = Color.Black,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF141414),
                contentColor = Color.White
            ) {
                listOf("Inicio", "Historial", "Analizar", "Peso", "Perfil").forEach { destination ->
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
                                    "Analizar" -> "✎"
                                    "Peso" -> "⚖"
                                    else -> "⚙"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        },
                        label = { Text(destination) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
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
                    onNavigate = { targetPage -> page = targetPage }
                )
                "Historial" -> HistoryScreen(
                    db = db,
                    onNavigateToAnalyze = { page = "Analizar" }
                )
                "Analizar" -> ParserScreen(
                    db = db,
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
                            onStartEdit = { isEditingProfile = true }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileViewScreen(
    profile: ProfileEntity,
    db: AppDatabase,
    onStartEdit: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupManager = remember(db) { BackupManager(db) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingRestorePayload by remember { mutableStateOf<SyntraBackupPayload?>(null) }

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
        val memories by remember(db) { db.irisxMemoryDao().observeAll() }.collectAsState(initial = emptyList())
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
            }
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
    onNavigate: (String) -> Unit
) {
    val history by remember(db) { db.weighInDao().observeAll() }.collectAsState(initial = emptyList())
    val sessions by remember(db) { db.workoutSessionDao().observeAll() }.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text(
                text = "Hola, ${profile.preferredName}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Syntra v0.2.0 • Asistente IrisX",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Summary Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("ÚLTIMO PESO", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(
                        text = history.firstOrNull()?.let { displayKg(it.weightGrams) + " kg" } ?: "Sin datos",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("ENTRENAMIENTOS", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(
                        text = "${sessions.size} sesiones",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Quick Actions (Accesos rápidos)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onNavigate("Historial") },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("📋 Historial", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = { onNavigate("Analizar") },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("✎ Analizar", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = { onNavigate("Peso") },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("⚖ Peso", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (sessions.isNotEmpty()) {
            val latest = sessions.first()
            val dateStr = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(latest.startedAtMillis))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "ÚLTIMA SESIÓN",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                        Text(
                            text = latest.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Button(
                        onClick = { onNavigate("Historial") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222))
                    ) {
                        Text("Ver detalle", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121A18)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "IrisX Core",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Tu copiloto privado procesa texto de entrenamiento y mantiene tu historial local sin conexión.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
            color = Color.White
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = weightInput,
                    onValueChange = { weightInput = it },
                    label = { Text("Nuevo pesaje (kg)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        save(weightInput)
                        if (weightGrams(weightInput) != null) weightInput = ""
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Registrar pesaje", fontWeight = FontWeight.Bold)
                }
                if (error != null) {
                    Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text(
            text = "Historial (${history.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(history, key = { it.id }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${displayKg(item.weightGrams)} kg",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (item.note.isNotBlank()) {
                                Text(item.note, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                        Text(
                            text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.measuredAtMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
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
    onNavigateToHistory: () -> Unit
) {
    var input by remember { mutableStateOf("Piernas\nSentadilla 70kg 12 10 8\nSentadilla 65kg 8\nBuenos días 40kg 12 10 10") }
    var analyzed by remember { mutableStateOf(false) }
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
    val compactExercises = remember(preview) {
        preview?.let { WorkoutPresentation.toCompactList(it.exercises) } ?: emptyList()
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
                        text = "• ${compactExercises.size} ejercicios • ${preview.seriesCount} series en total",
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
                                    preview.exercises.forEach { ex ->
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
        Column {
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
    onNavigateToAnalyze: () -> Unit
) {
    val sessions by remember(db) { db.workoutSessionDao().observeAll() }.collectAsState(initial = emptyList())
    val exercises by remember(db) { db.exerciseDao().observeAll() }.collectAsState(initial = emptyList())
    val exerciseNameMap = remember(exercises) { exercises.associate { it.id to it.displayName } }
    val scope = rememberCoroutineScope()
    var sessionToDelete by remember { mutableStateOf<WorkoutSessionEntity?>(null) }

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
                    text = "${sessions.size} sesiones registradas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            OutlinedButton(
                onClick = onNavigateToAnalyze,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("✎ Nueva sesión", style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
        }

        if (sessions.isEmpty()) {
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
                        text = "Aún no tenés entrenamientos guardados",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Escribí o pegá tus ejercicios en el Analizador para guardar tu primera sesión con orden de ejecución y cargas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Button(
                        onClick = onNavigateToAnalyze,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Ir a Analizar sesión", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionHistoryCard(
                        session = session,
                        db = db,
                        exerciseNameMap = exerciseNameMap,
                        onDelete = { sessionToDelete = session }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionHistoryCard(
    session: WorkoutSessionEntity,
    db: AppDatabase,
    exerciseNameMap: Map<Long, String>,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val sets by remember(session.id) { db.workoutSetDao().observeForSession(session.id) }.collectAsState(initial = emptyList())
    val dateStr = remember(session.startedAtMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(session.startedAtMillis))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Ocultar ▲" else "Ver detalle ▼", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = onDelete) {
                        Text("🗑", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Text(
                text = "${sets.size} series registradas",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
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
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (compEx.plannedTarget != null) {
                                Text(
                                    text = "Plan: ${compEx.plannedTarget}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.LightGray
                                )
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
