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
import com.syntra.app.domain.InputValidators
import com.syntra.app.domain.WorkoutPresentation
import com.syntra.app.domain.WorkoutTextParser
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
                listOf("Inicio", "Peso", "Analizar", "Perfil").forEach { destination ->
                    NavigationBarItem(
                        selected = page == destination,
                        onClick = {
                            page = destination
                            if (destination != "Perfil") isEditingProfile = false
                        },
                        icon = {
                            Text(
                                text = when(destination) { "Inicio" -> "⌂"; "Peso" -> "⚖"; "Analizar" -> "✎"; else -> "⚙" },
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
                "Analizar" -> ParserScreen()
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
                text = "Syntra v0.1.2 • Asistente IrisX",
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
                    Text("REGISTROS", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(
                        text = "${history.size} pesajes",
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
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { onNavigate("Peso") },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("⚖ Registrar peso", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(
                onClick = { onNavigate("Analizar") },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("✎ Analizar sesión", color = Color.White, style = MaterialTheme.typography.bodyMedium)
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
private fun ParserScreen() {
    var input by remember { mutableStateOf("Piernas\nSentadilla 70kg 12 10 8\nSentadilla 65kg 8\nBuenos días 40kg 12 10 10") }
    var analyzed by remember { mutableStateOf(false) }
    val sampleAliases = remember {
        mapOf(
            "rdl" to "Peso muerto rumano",
            "press banca" to "Press de banca",
            "buenos dias" to "Buenos días con barra",
            "laterales" to "Elevaciones laterales"
        )
    }
    val preview = remember(input, analyzed) { if (analyzed) WorkoutTextParser.parse(input, sampleAliases) else null }
    val compactExercises = remember(preview) {
        preview?.let { WorkoutPresentation.toCompactList(it.exercises) } ?: emptyList()
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
                text = "Una tarjeta por ejercicio • Varias filas por carga",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it; analyzed = false },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            label = { Text("Escribí tu sesión") }
        )

        Button(
            onClick = { analyzed = true },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Interpretar sesión", fontWeight = FontWeight.Bold)
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
                        Text(
                            text = exercise.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
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

                        // Table Rows (with subtle shading and dividers for mobile readability)
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
