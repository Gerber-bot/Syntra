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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.IrisxConversationEntity
import com.syntra.app.data.IrisxMessageEntity
import com.syntra.app.domain.irisx.IrisXCore
import com.syntra.app.domain.irisx.IrisXResponse
import com.syntra.app.domain.irisx.RoomIrisXRepository
import com.syntra.app.domain.neural.*
import com.syntra.app.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
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

    var activeConversation by remember { mutableStateOf<IrisxConversationEntity?>(null) }
    var showConversationsDialog by remember { mutableStateOf(false) }
    var conversationsList by remember { mutableStateOf<List<IrisxConversationEntity>>(emptyList()) }
    var conversationToDelete by remember { mutableStateOf<IrisxConversationEntity?>(null) }
    var conversationToRename by remember { mutableStateOf<IrisxConversationEntity?>(null) }

    var isGenerating by remember { mutableStateOf(false) }
    var currentGenerationJob by remember { mutableStateOf<Job?>(null) }

    fun refreshContextBadge() {
        val st = irisXCore.getCurrentContextState()
        activeTopicLabel = st.activeExerciseName ?: st.activeMuscleGroup
    }

    // Auto-load preferred neural model if not loaded yet
    LaunchedEffect(Unit) {
        if (!effectiveNeuralEngine.isModelLoaded()) {
            val prefModelName = ModelManager.getActiveModelPreference(context)
            val installed = ModelManager.listInstalledModels(context)
            val modelToLoad = if (!prefModelName.isNullOrBlank()) {
                installed.firstOrNull { it.fileName == prefModelName }?.file
            } else {
                installed.firstOrNull()?.file
            }
            if (modelToLoad != null && modelToLoad.exists()) {
                val guardian = IrisXGuardian.evaluateResources(context, modelToLoad.length())
                if (guardian.canProceed) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        effectiveNeuralEngine.loadModel(modelToLoad, threads = 4)
                    }
                }
            }
        }
    }

    // Load active conversation & messages
    LaunchedEffect(activeConversation?.id) {
        val conv = activeConversation ?: repo.getOrCreateActiveConversation().also { activeConversation = it }
        val stored = repo.getMessages(conv.id)
        messages.clear()
        if (stored.isEmpty()) {
            messages.add(
                ChatMessage(
                    text = "Hola. Soy IrisX, tu copiloto deportiva y núcleo inteligente offline.\n" +
                        "Podés consultar progresión ('¿cómo progresé en espalda?'), conceptos ('¿qué es RIR 1?'), recomendaciones o tu último entreno.",
                    isUser = false
                )
            )
        } else {
            stored.forEach { m ->
                val cleanContent = if (m.senderRole == "USER") {
                    IrisXContextBuilder.extractUserMessage(m.content)
                } else {
                    IrisXContextBuilder.stripChatMlTokens(m.content)
                }
                messages.add(
                    ChatMessage(
                        id = "msg_${m.id}",
                        text = cleanContent,
                        isUser = m.senderRole == "USER",
                        timestampMillis = m.createdAtMillis
                    )
                )
            }
        }
        activeTopicLabel = conv.activeExerciseName ?: conv.activeMuscleGroup
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    fun loadConversations() {
        scope.launch {
            conversationsList = repo.getAllConversations()
        }
    }

    fun switchConversation(target: IrisxConversationEntity) {
        activeConversation = target
        showConversationsDialog = false
    }

    fun startNewConversation() {
        scope.launch {
            val count = repo.getAllConversations().size
            val created = repo.createNewConversation("Conversación #${count + 1}")
            activeConversation = created
            showConversationsDialog = false
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || isGenerating) return
        val currentConv = activeConversation ?: return

        messages.add(ChatMessage(text = trimmed, isUser = true))
        inputText = ""

        scope.launch {
            listState.animateScrollToItem(messages.size - 1)
        }

        val historyForPrompt = messages.takeLast(6).map {
            (if (it.isUser) "user" else "assistant") to it.text
        }

        if (effectiveNeuralEngine.isModelLoaded()) {
            // Neural Engine Path: Persist clean human turn to SQLite
            scope.launch {
                repo.appendMessage(
                    IrisxMessageEntity(
                        conversationId = currentConv.id,
                        senderRole = "USER",
                        content = trimmed,
                        createdAtMillis = System.currentTimeMillis()
                    )
                )
            }

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
                    listState.animateScrollToItem(messages.size - 1)
                }
                return
            }

            // Check if deterministic response is an exact known concept/workout query
            val deterministicCheck = irisXCore.parseInput(trimmed)
            val isKnownDeterministic = deterministicCheck !is com.syntra.app.domain.irisx.IrisXIntent.GeneralQuery

            if (isKnownDeterministic) {
                scope.launch {
                    val resp = irisXCore.ask(trimmed)
                    val replyText = when (resp) {
                        is IrisXResponse.Success -> resp.message
                        is IrisXResponse.RequiresConfirmation -> "${resp.question}\nPara confirmar escribí: ${resp.confirmCommand}"
                        is IrisXResponse.Unrecognized -> resp.message
                    }
                    refreshContextBadge()
                    messages.add(ChatMessage(text = replyText, isUser = false))
                    repo.appendMessage(
                        IrisxMessageEntity(
                            conversationId = currentConv.id,
                            senderRole = "IRISX",
                            content = replyText,
                            createdAtMillis = System.currentTimeMillis()
                        )
                    )
                    listState.animateScrollToItem(messages.size - 1)
                }
                return
            }

            // Neural progressive streaming for free queries
            isGenerating = true
            val botMessageId = UUID.randomUUID().toString()
            val botMessageIndex = messages.size
            messages.add(ChatMessage(id = botMessageId, text = "Pensando...", isUser = false))

            currentGenerationJob = scope.launch {
                val prompt = IrisXContextBuilder.buildPrompt(
                    userMessage = trimmed,
                    db = db,
                    conversationHistory = historyForPrompt
                )
                val responseBuilder = StringBuilder()

                effectiveNeuralEngine.generate(prompt, InferenceParams(maxTokens = 256)).collect { chunk ->
                    if (chunk.errorMessage != null) {
                        responseBuilder.append("\n[Error: ${chunk.errorMessage}]")
                    } else {
                        responseBuilder.append(chunk.text)
                    }

                    val currentText = if (responseBuilder.isBlank()) "Pensando..." else responseBuilder.toString()
                    if (botMessageIndex < messages.size) {
                        messages[botMessageIndex] = ChatMessage(id = botMessageId, text = currentText, isUser = false)
                    }
                }

                // Clean final response and persist to SQLite
                val cleanFinal = IrisXContextBuilder.stripChatMlTokens(responseBuilder.toString())
                repo.appendMessage(
                    IrisxMessageEntity(
                        conversationId = currentConv.id,
                        senderRole = "IRISX",
                        content = cleanFinal,
                        createdAtMillis = System.currentTimeMillis()
                    )
                )

                // Add metrics footer for UI display
                val m = effectiveNeuralEngine.metrics
                if (m.tokensPerSecond > 0.0) {
                    val finalMsg = "${cleanFinal.trim()}\n\n───\n⚡ ${String.format("%.1f", m.tokensPerSecond)} t/s • ${m.lastInferenceDurationMillis} ms • ${effectiveNeuralEngine.currentModelFile?.name}"
                    if (botMessageIndex < messages.size) {
                        messages[botMessageIndex] = ChatMessage(id = botMessageId, text = finalMsg, isUser = false)
                    }
                }

                isGenerating = false
                currentGenerationJob = null
                listState.animateScrollToItem(messages.size - 1)
            }
        } else {
            // Deterministic Engine Path
            scope.launch {
                val response = irisXCore.ask(trimmed)
                val replyText = when (response) {
                    is IrisXResponse.Success -> response.message
                    is IrisXResponse.RequiresConfirmation -> "${response.question}\nPara confirmar escribí: ${response.confirmCommand}"
                    is IrisXResponse.Unrecognized -> {
                        "No entendí la consulta '$trimmed'. El motor neuronal no está cargado (podés activarlo en tu Perfil). Escribí 'ayuda' para ver comandos deterministas disponibles."
                    }
                }
                refreshContextBadge()
                messages.add(ChatMessage(text = replyText, isUser = false))
                repo.appendMessage(
                    IrisxMessageEntity(
                        conversationId = currentConv.id,
                        senderRole = "IRISX",
                        content = replyText,
                        createdAtMillis = System.currentTimeMillis()
                    )
                )
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    // Dialog for managing conversations
    if (showConversationsDialog) {
        AlertDialog(
            onDismissRequest = { showConversationsDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Conversaciones",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SyntraColors.TextPrimary
                    )
                    IconButton(onClick = { startNewConversation() }) {
                        Text("＋", style = MaterialTheme.typography.titleLarge, color = SyntraColors.NeonMint)
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (conversationsList.isEmpty()) {
                        Text("No hay conversaciones previas.", color = SyntraColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(conversationsList, key = { it.id }) { conv ->
                                val isCurrent = conv.id == activeConversation?.id
                                Surface(
                                    color = if (isCurrent) SyntraColors.DarkMint else SyntraColors.SurfaceCardElevated,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isCurrent) SyntraColors.NeonMint else SyntraColors.BorderSubtle),
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { switchConversation(conv) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = conv.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isCurrent) SyntraColors.NeonMint else SyntraColors.TextPrimary
                                            )
                                            val dateStr = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(conv.updatedAtMillis))
                                            Text(
                                                text = dateStr,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = SyntraColors.TextMuted
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            IconButton(
                                                onClick = { conversationToRename = conv },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Text("✏", fontSize = 12.sp, color = SyntraColors.SoftMint)
                                            }
                                            IconButton(
                                                onClick = { conversationToDelete = conv },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Text("🗑", fontSize = 12.sp, color = SyntraColors.Error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConversationsDialog = false }) {
                    Text("Cerrar", color = SyntraColors.NeonMint)
                }
            },
            containerColor = SyntraColors.SurfaceCardElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Rename Dialog
    if (conversationToRename != null) {
        val target = conversationToRename!!
        var renameText by remember { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { conversationToRename = null },
            title = { Text("Renombrar conversación", color = SyntraColors.TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SyntraColors.TextPrimary,
                        unfocusedTextColor = SyntraColors.TextPrimary,
                        focusedBorderColor = SyntraColors.NeonMint,
                        unfocusedBorderColor = SyntraColors.BorderSubtle
                    )
                )
            },
            confirmButton = {
                SyntraButton(
                    text = "Guardar",
                    onClick = {
                        val clean = renameText.trim().ifEmpty { target.title }
                        scope.launch {
                            repo.renameConversation(target.id, clean)
                            if (activeConversation?.id == target.id) {
                                activeConversation = target.copy(title = clean)
                            }
                            conversationToRename = null
                            loadConversations()
                        }
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { conversationToRename = null }) {
                    Text("Cancelar", color = SyntraColors.TextMuted)
                }
            },
            containerColor = SyntraColors.SurfaceCardElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Delete Confirmation Dialog
    if (conversationToDelete != null) {
        val target = conversationToDelete!!
        AlertDialog(
            onDismissRequest = { conversationToDelete = null },
            title = { Text("Eliminar conversación", color = SyntraColors.TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "¿Seguro que querés eliminar '${target.title}'? Se borrará el historial de este chat. Tus datos y memorias deportivas no se verán afectados.",
                    color = SyntraColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repo.deleteConversation(target.id)
                            conversationToDelete = null
                            loadConversations()
                            if (activeConversation?.id == target.id) {
                                activeConversation = repo.getOrCreateActiveConversation()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SyntraColors.Error),
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Eliminar", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationToDelete = null }) {
                    Text("Cancelar", color = SyntraColors.TextMuted)
                }
            },
            containerColor = SyntraColors.SurfaceCardElevated,
            shape = RoundedCornerShape(16.dp)
        )
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
                    .imePadding()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IrisXOrb(size = 28.dp, glowing = true)
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "IrisX",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = SyntraColors.TextPrimary
                                )
                                SyntraBadge(
                                    text = if (effectiveNeuralEngine.isModelLoaded()) "● Qwen3" else "○ Determinista",
                                    containerColor = if (effectiveNeuralEngine.isModelLoaded()) SyntraColors.DarkMint else SyntraColors.SurfaceHighlight,
                                    contentColor = if (effectiveNeuralEngine.isModelLoaded()) SyntraColors.NeonMint else SyntraColors.TextMuted
                                )
                            }
                            Text(
                                text = activeConversation?.title ?: "Conversación activa",
                                style = MaterialTheme.typography.labelSmall,
                                color = SyntraColors.SoftMint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                loadConversations()
                                showConversationsDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("💬 Chats", style = MaterialTheme.typography.labelSmall, color = SyntraColors.SoftMint)
                        }
                        IconButton(onClick = onDismiss) {
                            Text("✕", color = SyntraColors.TextMuted, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = SyntraColors.BorderSubtle
                )

                // Message List
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatBubble(message = msg)
                    }
                }

                // Quick Shortcut Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Último entreno", "¿Qué es RIR 1?", "¿Cómo progresé en espalda?", "Recomendaciones").forEach { chip ->
                        OutlinedButton(
                            onClick = { sendMessage(chip) },
                            shape = RoundedCornerShape(50),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = SyntraColors.SurfaceHighlight)
                        ) {
                            Text(
                                text = chip,
                                style = MaterialTheme.typography.labelSmall,
                                color = SyntraColors.SoftMint
                            )
                        }
                    }
                }

                // Input Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Escribe a IrisX...", style = MaterialTheme.typography.bodySmall, color = SyntraColors.TextMuted) },
                        shape = RoundedCornerShape(50),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SyntraColors.TextPrimary,
                            unfocusedTextColor = SyntraColors.TextPrimary,
                            focusedBorderColor = SyntraColors.NeonMint,
                            unfocusedBorderColor = SyntraColors.BorderSubtle,
                            focusedContainerColor = SyntraColors.SurfaceCardElevated,
                            unfocusedContainerColor = SyntraColors.SurfaceCardElevated
                        )
                    )

                    if (isGenerating) {
                        Button(
                            onClick = {
                                currentGenerationJob?.cancel()
                                effectiveNeuralEngine.cancel()
                                isGenerating = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SyntraColors.Error),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("⏹ Parar", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = { sendMessage(inputText) },
                            colors = ButtonDefaults.buttonColors(containerColor = SyntraColors.NeonMint),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Enviar", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF05100E))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val align = if (message.isUser) Alignment.End else Alignment.Start
    val bg = if (message.isUser) SyntraColors.SurfaceHighlight else Color(0xFF071210)
    val border = if (message.isUser) SyntraColors.BorderSubtle else SyntraColors.DarkMint
    val textColor = if (message.isUser) SyntraColors.TextPrimary else SyntraColors.TextPrimary

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, border),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
