package com.syntra.app.ui.irisx

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.syntra.app.data.AppDatabase
import com.syntra.app.domain.irisx.IrisXCore
import com.syntra.app.domain.irisx.IrisXResponse
import com.syntra.app.domain.irisx.RoomIrisXRepository
import com.syntra.app.domain.neural.*
import com.syntra.app.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestampMillis: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IrisXConsoleDialog(
    db: AppDatabase,
    neuralEngine: NeuralInferenceEngine? = null,
    activeWorkoutTitle: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(db) { RoomIrisXRepository(db) }
    val irisXCore = remember(repo) { IrisXCore(repo) }

    val fallbackEngine = remember(db) { DeterministicInferenceEngine(db) }
    val effectiveNeuralEngine = neuralEngine ?: remember(fallbackEngine) { LlamaCppInferenceEngine(fallbackEngine) }

    var inputText by remember { mutableStateOf("") }
    var activeTopicLabel by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()

    var showModelManagerDialog by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var currentGenerationJob by remember { mutableStateOf<Job?>(null) }

    fun refreshContextBadge() {
        val st = irisXCore.getCurrentContextState()
        activeTopicLabel = st.activeExerciseName ?: st.activeMuscleGroup
    }

    LaunchedEffect(repo) {
        val conv = repo.getOrCreateActiveConversation()
        val stored = repo.getMessages(conv.id)
        messages.clear()
        if (stored.isEmpty()) {
            messages.add(
                ChatMessage(
                    text = "Hola. Soy IrisX, tu copiloto deportiva y núcleo inteligente offline.\n" +
                        "Podés consultar progresión ('¿cómo progresé en espalda?'), recomendaciones o cargar un modelo GGUF (ej. Qwen3-0.6B) para respuestas neuronales locales.",
                    isUser = false
                )
            )
        } else {
            stored.forEach { m ->
                messages.add(
                    ChatMessage(
                        text = m.content,
                        isUser = m.senderRole == "USER",
                        timestampMillis = m.createdAtMillis
                    )
                )
            }
        }
        activeTopicLabel = conv.activeExerciseName ?: conv.activeMuscleGroup
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || isGenerating) return
        messages.add(ChatMessage(text = trimmed, isUser = true))
        inputText = ""

        if (effectiveNeuralEngine.isModelLoaded()) {
            // Neural Engine Path
            val guardian = IrisXGuardian.evaluateResources(context, effectiveNeuralEngine.currentModelFile?.length() ?: 0L)
            if (!guardian.canProceed) {
                messages.add(
                    ChatMessage(
                        text = "⚠️ IrisX Guardian: Inferencia pausada por protección del dispositivo (${guardian.reason}). Recurriendo al motor determinista...",
                        isUser = false
                    )
                )
                scope.launch {
                    val resp = irisXCore.ask(trimmed)
                    val reply = when (resp) {
                        is IrisXResponse.Success -> resp.message
                        is IrisXResponse.RequiresConfirmation -> "${resp.question}\nPara confirmar escribí: ${resp.confirmCommand}"
                        is IrisXResponse.Unrecognized -> resp.message
                    }
                    messages.add(ChatMessage(text = "[Motor determinista]\n$reply", isUser = false))
                }
                return
            }

            // Neural progressive streaming
            isGenerating = true
            val botMessageIndex = messages.size
            messages.add(ChatMessage(text = "Pensando...", isUser = false))

            currentGenerationJob = scope.launch {
                val prompt = IrisXContextBuilder.buildPrompt(trimmed, db)
                val responseBuilder = StringBuilder()

                effectiveNeuralEngine.generate(prompt, InferenceParams(maxTokens = 256)).collect { chunk ->
                    if (chunk.errorMessage != null) {
                        responseBuilder.append("\n[Error: ${chunk.errorMessage}]")
                    } else {
                        if (responseBuilder.isEmpty() && chunk.text.isNotBlank()) {
                            // First token received
                        }
                        responseBuilder.append(chunk.text)
                    }

                    val currentText = if (responseBuilder.isBlank()) "Pensando..." else responseBuilder.toString()
                    if (botMessageIndex < messages.size) {
                        messages[botMessageIndex] = ChatMessage(text = currentText, isUser = false)
                    }
                    if (messages.isNotEmpty()) {
                        listState.scrollToItem(messages.size - 1)
                    }
                }

                // Add metrics footer
                val m = effectiveNeuralEngine.metrics
                if (m.tokensPerSecond > 0.0) {
                    val finalMsg = "${responseBuilder.toString().trim()}\n\n───\n⚡ ${String.format("%.1f", m.tokensPerSecond)} t/s • ${m.lastInferenceDurationMillis} ms • ${effectiveNeuralEngine.currentModelFile?.name}"
                    if (botMessageIndex < messages.size) {
                        messages[botMessageIndex] = ChatMessage(text = finalMsg, isUser = false)
                    }
                }

                isGenerating = false
                currentGenerationJob = null
            }
        } else {
            // Deterministic Engine Path
            scope.launch {
                val response = irisXCore.ask(trimmed)
                val replyText = when (response) {
                    is IrisXResponse.Success -> response.message
                    is IrisXResponse.RequiresConfirmation -> "${response.question}\nPara confirmar escribí: ${response.confirmCommand}"
                    is IrisXResponse.Unrecognized -> {
                        if (response.suggestions.isNotEmpty()) {
                            "${response.message}\n\n${response.suggestions.joinToString("\n")}"
                        } else {
                            response.message
                        }
                    }
                }
                refreshContextBadge()
                messages.add(ChatMessage(text = "[Motor determinista]\n$replyText", isUser = false))
                if (messages.isNotEmpty()) {
                    listState.scrollToItem(messages.size - 1)
                }
            }
        }
    }

    if (showModelManagerDialog) {
        ManageNeuralModelDialog(
            engine = effectiveNeuralEngine,
            onDismiss = { showModelManagerDialog = false }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(20.dp),
            color = SyntraColors.SurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle)
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
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IrisXOrb(size = 36.dp, glowing = true)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "IrisX Neural Console",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = SyntraColors.TextPrimary
                            )
                            Text(
                                text = if (effectiveNeuralEngine.isModelLoaded())
                                    "● Motor activo: ${effectiveNeuralEngine.currentModelFile?.name}"
                                else
                                    "○ Motor determinista",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (effectiveNeuralEngine.isModelLoaded()) SyntraColors.NeonMint else SyntraColors.TextMuted
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SyntraSecondaryButton(
                            text = "🧠 Modelos",
                            onClick = { showModelManagerDialog = true },
                            modifier = Modifier.height(36.dp)
                        )
                        IconButton(onClick = onDismiss) {
                            Text("✕", color = SyntraColors.TextMuted, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                HorizontalDivider(color = SyntraColors.BorderSubtle)

                if (!effectiveNeuralEngine.isModelLoaded()) {
                    SyntraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Motor determinista (offline)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = SyntraColors.TextPrimary
                                )
                                Text(
                                    text = "Cargá Qwen3-0.6B para inferencia neuronal.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SyntraColors.TextMuted
                                )
                            }
                            SyntraButton(
                                text = "Cargar GGUF",
                                onClick = { showModelManagerDialog = true },
                                modifier = Modifier.height(34.dp)
                            )
                        }
                    }
                }

                // Suggestion chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val suggestions = if (activeWorkoutTitle != null) {
                        listOf(
                            "¿Cuánto levanté la última vez en sentadilla?",
                            "¿Qué ejercicio puedo hacer en lugar de press de banca?",
                            "Última sesión de pecho",
                            "Recomendaciones",
                            "¿Cómo progresé en espalda?",
                            "Último entreno"
                        )
                    } else {
                        listOf(
                            "¿Cómo progresé en espalda?",
                            "¿Y el mes pasado?",
                            "¿Cuánto levanté la última vez en sentadilla?",
                            "Recomendaciones",
                            "Último entreno",
                            "Mis rutinas"
                        )
                    }
                    suggestions.forEach { chipText ->
                        SyntraFilterChip(
                            selected = false,
                            onClick = { sendMessage(chipText) },
                            label = chipText
                        )
                    }
                }

                // Messages list
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
                            verticalAlignment = Alignment.Top
                        ) {
                            if (!msg.isUser) {
                                IrisXOrb(size = 28.dp, glowing = false)
                                Spacer(Modifier.width(8.dp))
                            }
                            Surface(
                                shape = RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = if (msg.isUser) 16.dp else 4.dp,
                                    bottomEnd = if (msg.isUser) 4.dp else 16.dp
                                ),
                                color = if (msg.isUser) SyntraColors.DarkMint else SyntraColors.SurfaceCardElevated,
                                border = if (msg.isUser) null else androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle),
                                modifier = Modifier.widthIn(max = 300.dp)
                            ) {
                                Text(
                                    text = msg.text,
                                    color = SyntraColors.TextPrimary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    }
                }

                // Input field and send / stop button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Escribe a IrisX...", style = MaterialTheme.typography.bodySmall, color = SyntraColors.TextMuted) },
                        maxLines = 3,
                        enabled = !isGenerating,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SyntraColors.NeonMint,
                            unfocusedBorderColor = SyntraColors.BorderSubtle,
                            focusedTextColor = SyntraColors.TextPrimary,
                            unfocusedTextColor = SyntraColors.TextPrimary
                        )
                    )

                    if (isGenerating) {
                        Button(
                            onClick = {
                                effectiveNeuralEngine.cancel()
                                currentGenerationJob?.cancel()
                                isGenerating = false
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SyntraColors.Error),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text("⏹ Detener", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        SyntraButton(
                            text = "➤",
                            onClick = { sendMessage(inputText) },
                            modifier = Modifier.height(48.dp)
                        )
                    }
                }
            }
        }
    }
}
