package com.syntra.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.syntra.app.data.*
import com.syntra.app.domain.InputValidators
import com.syntra.app.domain.WorkoutTextParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.get(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color.Black,
                surface = Color(0xFF151515),
                primary = Color(0xFF75D9C1),
                onBackground = Color.White
            )) {
                TrainerApp(database)
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
    LaunchedEffect(db) { profileFlow.first(); loaded = true }

    if (!loaded) {
        Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) { CircularProgressIndicator() }
        return
    }
    if (profile == null || page == "Perfil") {
        ProfileForm(profile, error, onCancel = { page = "Inicio" }) { name, preferred, height, grams, activity, goal ->
            scope.launch {
                try {
                    val now = System.currentTimeMillis()
                    val previous = profile
                    db.withTransaction {
                        db.profileDao().save(ProfileEntity(
                            name = name, preferredName = preferred, heightCm = height,
                            initialWeightGrams = previous?.initialWeightGrams ?: grams,
                            activityLevel = activity, goal = goal,
                            createdAtMillis = previous?.createdAtMillis ?: now,
                            updatedAtMillis = now
                        ))
                        if (previous == null) db.weighInDao().insert(WeighInEntity(measuredAtMillis = now, weightGrams = grams))
                    }
                    error = null
                    page = "Inicio"
                } catch (e: Exception) { error = "No se pudieron guardar los datos. Revisá el registro de errores." }
            }
        }
        return
    }
    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF151515)) {
                listOf("Inicio", "Peso", "Analizar", "Perfil").forEach { destination ->
                    NavigationBarItem(
                        selected = page == destination,
                        onClick = { page = destination },
                        icon = { Text(when(destination) { "Inicio" -> "⌂"; "Peso" -> "⚖"; "Analizar" -> "✎"; else -> "⚙" }) },
                        label = { Text(destination) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            when(page) {
                "Peso" -> WeightScreen(db, error) { input ->
                    val grams = weightGrams(input)
                    if (grams == null) error = "Introducí un peso válido entre 20 y 400 kg."
                    else scope.launch {
                        try {
                            db.weighInDao().insert(WeighInEntity(weightGrams = grams, measuredAtMillis = System.currentTimeMillis()))
                            error = null
                        } catch (e: Exception) { error = "No se pudo registrar el pesaje." }
                    }
                }
                "Analizar" -> ParserScreen()
                else -> HomeScreen(profile!!, db)
            }
        }
    }
}

@Composable
private fun ProfileForm(
    current: ProfileEntity?, error: String?, onCancel: () -> Unit,
    onSave: (String, String, Int, Int, String, String) -> Unit
) {
    var name by remember(current) { mutableStateOf(current?.name ?: "") }
    var preferred by remember(current) { mutableStateOf(current?.preferredName ?: "") }
    var height by remember(current) { mutableStateOf(current?.heightCm?.toString() ?: "") }
    var weight by remember(current) { mutableStateOf(current?.initialWeightGrams?.let(::displayKg) ?: "") }
    var activity by remember(current) { mutableStateOf(current?.activityLevel ?: "Sedentario") }
    var goal by remember(current) { mutableStateOf(current?.goal ?: "Seguimiento general") }
    var validation by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if(current == null) "Bienvenido a Syntra" else "Editar perfil", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Los datos se guardan únicamente en este dispositivo.")
        OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(preferred, { preferred = it }, label = { Text("¿Cómo querés que te llame?") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(height, { height = it }, label = { Text("Estatura (cm)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        if (current == null) {
            OutlinedTextField(weight, { weight = it }, label = { Text("Peso inicial (kg)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        } else {
            Text("El peso inicial no cambia al editar el perfil. Podés añadir nuevos pesajes en Peso.")
        }
        Text("Actividad cotidiana", fontWeight = FontWeight.SemiBold)
        listOf("Sedentario", "Ligero", "Moderado", "Alto").forEach { item ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = activity == item, onClick = { activity = item }); Text(item)
            }
        }
        Text("Objetivo principal", fontWeight = FontWeight.SemiBold)
        listOf("Seguimiento general", "Perder grasa", "Ganar masa muscular", "Mejorar fuerza", "Mantener peso").forEach { item ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = goal == item, onClick = { goal = item }); Text(item)
            }
        }
        if (validation != null || error != null) Text(validation ?: error.orEmpty(), color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            val w = if (current == null) weightGrams(weight) else current.initialWeightGrams
            val errorMsg = InputValidators.validateProfile(name, preferred, height, w)
            if (errorMsg != null) {
                validation = errorMsg
            } else {
                validation = null
                onSave(name.trim(), preferred.trim(), height.trim().toInt(), w!!, activity, goal)
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Guardar perfil") }
        if (current != null) TextButton(onClick = onCancel) { Text("Volver") }
    }
}

@Composable
private fun HomeScreen(profile: ProfileEntity, db: AppDatabase) {
    val history by remember(db) { db.weighInDao().observeAll() }.collectAsState(initial = emptyList())
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Hola, ${profile.preferredName}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Tu entrenador inteligente y privado con IrisX. Versión 0.1.0")
        HorizontalDivider()
        Text("Objetivo: ${profile.goal}")
        Text("Actividad cotidiana: ${profile.activityLevel}")
        Text("Último peso: ${history.firstOrNull()?.let { displayKg(it.weightGrams) + " kg" } ?: "Sin datos"}")
        Text("Pesajes registrados: ${history.size}")
        Text("El asistente inteligente IrisX y el registro definitivo de entrenamientos se habilitarán en próximas entregas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WeightScreen(db: AppDatabase, error: String?, save: (String) -> Unit) {
    val history by remember(db) { db.weighInDao().observeAll() }.collectAsState(initial = emptyList())
    var weight by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Seguimiento del peso", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(weight, { weight = it }, label = { Text("Nuevo pesaje (kg)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Button(onClick = { save(weight) }, modifier = Modifier.fillMaxWidth()) { Text("Registrar pesaje") }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        Text("Historial (${history.size})", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${displayKg(item.weightGrams)} kg", fontWeight = FontWeight.Bold)
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.measuredAtMillis)))
                    }
                }
            }
        }
    }
}

@Composable
private fun ParserScreen() {
    var input by remember { mutableStateOf("Piernas\nSentadilla 80kg 8 8 7\nBuenos días 40kg 12 10 10") }
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
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Analizador de entrenamientos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Solo vista previa: esta versión NO guarda entrenamientos.")
        OutlinedTextField(value = input, onValueChange = { input = it; analyzed = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text("Escribí tu sesión") })
        Button(onClick = { analyzed = true }, modifier = Modifier.fillMaxWidth()) { Text("Interpretar texto") }
        if (preview != null) {
            Text("${preview.exercises.size} ejercicios • ${preview.seriesCount} series", fontWeight = FontWeight.Bold)
            preview.sessionTitle?.let { Text("Sesión: $it") }
            preview.exercises.forEach { exercise ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(exercise.name, fontWeight = FontWeight.Bold)
                        Text(exercise.sets.joinToString(" • ") { "${displayKg(it.weightGrams)} kg × ${it.repetitions}" })
                    }
                }
            }
            preview.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}
