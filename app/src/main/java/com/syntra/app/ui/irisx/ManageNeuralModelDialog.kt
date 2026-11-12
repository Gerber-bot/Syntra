package com.syntra.app.ui.irisx

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.syntra.app.domain.neural.*
import com.syntra.app.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ManageNeuralModelDialog(
    engine: NeuralInferenceEngine,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installedModels by remember {
        mutableStateOf<List<GgufModelInfo>>(emptyList())
    }
    LaunchedEffect(Unit) {
        installedModels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ModelManager.listInstalledModels(context, engine.currentModelFile?.name)
        }
    }
    var isImporting by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf<String?>(null) }
    var isLoadingModel by remember { mutableStateOf(false) }

    // Test bench state
    var isTestingInference by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf("") }
    var testMetricsSummary by remember { mutableStateOf<String?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    var activeTestJob by remember { mutableStateOf<Job?>(null) }

    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImporting = true
        importStatusMessage = "Copiando y validando cabecera binaria GGUF..."
        scope.launch {
            val result = ModelManager.importModelFromUri(context, uri)
            isImporting = false
            when (result) {
                is ModelImportResult.Success -> {
                    importStatusMessage = "Modelo '${result.modelInfo.fileName}' importado con éxito (GGUF v${result.modelInfo.ggufVersion})."
                    installedModels = ModelManager.listInstalledModels(context, engine.currentModelFile?.name)
                }
                is ModelImportResult.Error -> {
                    importStatusMessage = result.message
                }
            }
        }
    }

    Dialog(onDismissRequest = {
        activeTestJob?.cancel()
        engine.cancel()
        onDismiss()
    }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(20.dp),
            color = SyntraColors.SurfaceCardElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Modelos Neuronales GGUF",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = SyntraColors.TextPrimary
                            )
                            Text(
                                text = "Snapdragon 685 • llama.cpp ARM64 offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = SyntraColors.SoftMint
                            )
                        }
                        IconButton(onClick = {
                            activeTestJob?.cancel()
                            engine.cancel()
                            onDismiss()
                        }) {
                            Text("✕", color = SyntraColors.TextMuted, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                item {
                    HorizontalDivider(color = SyntraColors.BorderSubtle)
                }

                // Active engine status card
                item {
                    val guardian = remember(engine.currentModelFile, engine.state) {
                        IrisXGuardian.evaluateResources(
                            context,
                            engine.currentModelFile?.length() ?: (400L * 1024L * 1024L)
                        )
                    }

                    SyntraCard(
                        modifier = Modifier.fillMaxWidth(),
                        glowingBorder = engine.isModelLoaded()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (engine.isModelLoaded()) "● Motor activo: ${engine.currentModelFile?.name}" else "○ Motor determinista",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (engine.isModelLoaded()) SyntraColors.NeonMint else SyntraColors.TextMuted
                                )
                                SyntraBadge(
                                    text = engine.state.name,
                                    containerColor = if (engine.isModelLoaded()) SyntraColors.DarkMint else SyntraColors.SurfaceHighlight,
                                    contentColor = if (engine.isModelLoaded()) SyntraColors.NeonMint else SyntraColors.TextMuted
                                )
                            }

                            if (engine.isModelLoaded()) {
                                Text(
                                    text = "Tamaño: ${String.format(java.util.Locale.US, "%.1f", (engine.currentModelFile?.length() ?: 0) / (1024.0 * 1024.0))} MB • TPS previo: ${String.format(java.util.Locale.US, "%.1f", engine.metrics.tokensPerSecond)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SyntraColors.TextSecondary
                                )
                            }

                            Text(
                                text = "Guardián: ${guardian.safetyLevel} (RAM libre: ${String.format(java.util.Locale.US, "%.1f", guardian.availableMemoryBytes / (1024.0 * 1024.0 * 1024.0))} GB, Batería: ${guardian.batteryPct}%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = when (guardian.safetyLevel) {
                                    GuardianSafetyLevel.SAFE -> SyntraColors.NeonMint
                                    GuardianSafetyLevel.WARNING -> Color(0xFFFFB74D)
                                    GuardianSafetyLevel.CRITICAL -> SyntraColors.Error
                                }
                            )

                            val isNative = LlamaCppInferenceEngine.isNativeAvailable()
                            val nativeError = LlamaCppInferenceEngine.getNativeLoadError()
                            Text(
                                text = if (isNative) "Binario JNI: libllama-android.so cargada (ARM64)" else "Binario JNI: No cargada (${nativeError ?: "Falta biblioteca nativa"})",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isNative) SyntraColors.NeonMint else SyntraColors.Error
                            )
                        }
                    }
                }

                // Neural In-App Test Bench Card
                item {
                    val isNative = LlamaCppInferenceEngine.isNativeAvailable()
                    SyntraCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "🧪 Banco de pruebas de inferencia",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = SyntraColors.NeonMint
                            )
                            Text(
                                text = "Ejecuta una consulta controlada directa a llama.cpp sin alterar el historial ni la base deportiva.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SyntraColors.TextSecondary
                            )

                            if (isTestingInference) {
                                Button(
                                    onClick = {
                                        activeTestJob?.cancel()
                                        engine.cancel()
                                        isTestingInference = false
                                        testError = "Prueba cancelada por el usuario."
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = SyntraColors.Error),
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("⏹ Cancelar prueba", fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            } else {
                                SyntraButton(
                                    text = "▶ Probar inferencia neuronal",
                                    onClick = {
                                        isTestingInference = true
                                        testResultText = ""
                                        testMetricsSummary = null
                                        testError = null

                                        val testPrompt = "<|im_start|>system\nSos IrisX, asistente de entrenamiento de Syntra.<|im_end|>\n<|im_start|>user\nHola IrisX, ¿qué es la sobrecarga progresiva?<|im_end|>\n<|im_start|>assistant\n<think>\n</think>\n"
                                        val testStartTime = System.currentTimeMillis()
                                        var firstTokenTime: Long? = null
                                        var count = 0

                                        activeTestJob = scope.launch {
                                            try {
                                                engine.generate(testPrompt, InferenceParams(maxTokens = 64)).collect { chunk ->
                                                    if (chunk.errorMessage != null) {
                                                        testError = chunk.errorMessage
                                                    }
                                                    if (chunk.text.isNotEmpty()) {
                                                        if (firstTokenTime == null) {
                                                            firstTokenTime = System.currentTimeMillis()
                                                        }
                                                        testResultText += chunk.text
                                                        count++
                                                    }
                                                    if (chunk.isComplete) {
                                                        val totalTime = (System.currentTimeMillis() - testStartTime).coerceAtLeast(1L)
                                                        val ttft = firstTokenTime?.let { it - testStartTime } ?: totalTime
                                                        val tps = (count * 1000.0) / totalTime
                                                        testMetricsSummary = "TTFT: ${ttft} ms • $count tokens • ${String.format(java.util.Locale.US, "%.1f", tps)} tok/s • Total: ${totalTime} ms"
                                                        isTestingInference = false
                                                    }
                                                }
                                            } catch (e: Throwable) {
                                                testError = "Error en prueba: ${e.message}"
                                                isTestingInference = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = isNative && engine.isModelLoaded() && !isLoadingModel
                                )
                            }

                            if (!isNative) {
                                Text(
                                    text = "La inferencia neuronal requiere la biblioteca nativa ARM64. En entorno JVM no se ejecuta para evitar métricas simuladas.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SyntraColors.Error
                                )
                            } else if (!engine.isModelLoaded()) {
                                Text(
                                    text = "Cargá un modelo GGUF abajo para habilitar la prueba neuronal.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SyntraColors.TextMuted
                                )
                            }

                            if (testResultText.isNotEmpty()) {
                                Surface(
                                    color = Color(0xFF040B0A),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Respuesta generada:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SyntraColors.SoftMint
                                        )
                                        Text(
                                            text = testResultText,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = SyntraColors.TextPrimary
                                        )
                                    }
                                }
                            }

                            if (testMetricsSummary != null) {
                                Text(
                                    text = "📊 ${testMetricsSummary ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = SyntraColors.NeonMint
                                )
                            }

                            if (testError != null) {
                                Text(
                                    text = "⚠️ ${testError ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SyntraColors.Error
                                )
                            }
                        }
                    }
                }

                // Import button
                item {
                    SyntraButton(
                        text = if (isImporting) "Importando archivo..." else "📁 Importar modelo (.gguf)",
                        onClick = {
                            safLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isImporting && !isLoadingModel
                    )
                }

                if (importStatusMessage != null) {
                    item {
                        Text(
                            text = importStatusMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (importStatusMessage?.contains("éxito") == true) MaterialTheme.colorScheme.primary else Color(0xFFFF8A80)
                        )
                    }
                }

                item {
                    Text(
                        text = "Modelos instalados en almacenamiento privado:",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray
                    )
                }

                if (installedModels.isEmpty()) {
                    item {
                        Text(
                            text = "No hay modelos GGUF importados aún. Podés seleccionar tu archivo Qwen3-0.6B-Q4_K_M.gguf descargado.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.DarkGray
                        )
                    }
                } else {
                    items(installedModels, key = { it.fileName }) { model ->
                        ModelItemCard(
                            model = model,
                            isLoaded = engine.currentModelFile?.name == model.fileName,
                            isLoading = isLoadingModel,
                            onLoad = {
                                isLoadingModel = true
                                scope.launch {
                                    val res = engine.loadModel(model.file, threads = 4)
                                    isLoadingModel = false
                                    if (res.isSuccess) {
                                        ModelManager.setActiveModelPreference(context, model.fileName)
                                        installedModels = ModelManager.listInstalledModels(context, model.fileName)
                                    } else {
                                        importStatusMessage = "Error al cargar: ${res.exceptionOrNull()?.message}"
                                    }
                                }
                            },
                            onUnload = {
                                scope.launch {
                                    engine.unloadModel()
                                    ModelManager.setActiveModelPreference(context, null)
                                    installedModels = ModelManager.listInstalledModels(context, null)
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    if (engine.currentModelFile?.name == model.fileName) {
                                        engine.unloadModel()
                                        ModelManager.setActiveModelPreference(context, null)
                                    }
                                    ModelManager.deleteModel(model.file)
                                    installedModels = ModelManager.listInstalledModels(context, engine.currentModelFile?.name)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelItemCard(
    model: GgufModelInfo,
    isLoaded: Boolean,
    isLoading: Boolean,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = SyntraColors.SurfaceCardElevated,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isLoaded) SyntraColors.NeonMint else SyntraColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = SyntraColors.TextPrimary
                )
                val isQwen3 = model.fileName.contains("qwen3", ignoreCase = true)
                Text(
                    text = "${String.format(java.util.Locale.US, "%.1f", model.sizeBytes / (1024.0 * 1024.0))} MB • GGUF v${model.ggufVersion}${if (isQwen3) " • ✦ Qwen3" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isQwen3) SyntraColors.NeonMint else SyntraColors.TextMuted
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isLoaded) {
                    SyntraSecondaryButton(
                        text = "Descargar",
                        onClick = onUnload,
                        modifier = Modifier.height(36.dp)
                    )
                } else {
                    SyntraButton(
                        text = "Cargar",
                        onClick = onLoad,
                        enabled = !isLoading,
                        modifier = Modifier.height(36.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Text("🗑", color = SyntraColors.Error)
                }
            }
        }
    }
}
