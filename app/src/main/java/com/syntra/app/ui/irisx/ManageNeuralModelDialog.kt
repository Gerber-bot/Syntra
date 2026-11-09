package com.syntra.app.ui.irisx

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.syntra.app.domain.neural.GgufModelInfo
import com.syntra.app.domain.neural.ModelImportResult
import com.syntra.app.domain.neural.ModelManager
import com.syntra.app.domain.neural.NeuralInferenceEngine
import com.syntra.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ManageNeuralModelDialog(
    engine: NeuralInferenceEngine,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installedModels by remember {
        mutableStateOf(ModelManager.listInstalledModels(context, engine.currentModelFile?.name))
    }
    var isImporting by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf<String?>(null) }
    var isLoadingModel by remember { mutableStateOf(false) }

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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = SyntraColors.SurfaceCardElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
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
                    IconButton(onClick = onDismiss) {
                        Text("✕", color = SyntraColors.TextMuted, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = SyntraColors.BorderSubtle)

                // Active engine status card
                SyntraCard(
                    modifier = Modifier.fillMaxWidth(),
                    glowingBorder = engine.isModelLoaded()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (engine.isModelLoaded()) "● Motor activo: ${engine.currentModelFile?.name}" else "○ Motor determinista (sin red neuronal cargada)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (engine.isModelLoaded()) SyntraColors.NeonMint else SyntraColors.TextMuted
                        )
                        if (engine.isModelLoaded()) {
                            Text(
                                text = "Tamaño: ${String.format("%.1f", (engine.currentModelFile?.length() ?: 0) / (1024.0 * 1024.0))} MB • TPS previo: ${String.format("%.1f", engine.metrics.tokensPerSecond)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SyntraColors.TextSecondary
                            )
                        }
                    }
                }

                // Import button
                SyntraButton(
                    text = if (isImporting) "Importando archivo..." else "📁 Importar modelo (.gguf)",
                    onClick = {
                        safLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImporting && !isLoadingModel
                )

                if (importStatusMessage != null) {
                    Text(
                        text = importStatusMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (importStatusMessage?.contains("éxito") == true) MaterialTheme.colorScheme.primary else Color(0xFFFF8A80)
                    )
                }

                Text(
                    text = "Modelos instalados en almacenamiento privado:",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray
                )

                if (installedModels.isEmpty()) {
                    Text(
                        text = "No hay modelos GGUF importados aún. Podés seleccionar tu archivo Qwen3-0.6B-Q4_K_M.gguf descargado.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                                            installedModels = ModelManager.listInstalledModels(context, model.fileName)
                                        } else {
                                            importStatusMessage = "Error al cargar: ${res.exceptionOrNull()?.message}"
                                        }
                                    }
                                },
                                onUnload = {
                                    scope.launch {
                                        engine.unloadModel()
                                        installedModels = ModelManager.listInstalledModels(context, null)
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        if (engine.currentModelFile?.name == model.fileName) {
                                            engine.unloadModel()
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
                    text = "${String.format("%.1f", model.sizeBytes / (1024.0 * 1024.0))} MB • GGUF v${model.ggufVersion}${if (isQwen3) " • ✦ Qwen3" else ""}",
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
