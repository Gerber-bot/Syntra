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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
    // Single-flight authority for the console: one turn owns the dialog at a time, and a
    // cancelled/superseded turn can no longer append or persist anything.
    val turnLedger = remember { IrisXTurnLedger() }
    // Serializes "resolve conversation + read stored messages + render" against a new send,
    // so the initial load can never wipe a live bubble nor create a second conversation.
    val conversationMutex = remember { Mutex() }
    var streamingMessageId by remember { mutableStateOf<String?>(null) }
    // Visible, non-persisted diagnosis for model loading problems: a silent degradation to
    // the deterministic engine is indistinguishable from "IrisX no funciona".
    var neuralNotice by remember { mutableStateOf<String?>(null) }

    fun refreshContextBadge() {
        val st = irisXCore.getCurrentContextState()
        activeTopicLabel = st.activeExerciseName ?: st.activeMuscleGroup
    }

    fun replaceMessage(id: String, update: (ChatMessage) -> ChatMessage) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = update(messages[index])
    }

    fun stopGeneration(markAsStopped: Boolean = true) {
        turnLedger.cancelActive()
        currentGenerationJob?.cancel()
        currentGenerationJob = null
        effectiveNeuralEngine.cancel()
        isGenerating = false
        val streamingId = streamingMessageId
        streamingMessageId = null
        if (markAsStopped && streamingId != null) {
            replaceMessage(streamingId) { msg ->
                if (msg.text.isBlank() || msg.text == IrisXReplyAccumulator.PLACEHOLDER) {
                    msg.copy(text = "⏹ Generación detenida por el usuario.", kind = ChatMessageKind.NOTICE)
                } else {
                    msg
                }
            }
        }
    }

    // Keep the streamed answer visible while it grows.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    // Auto-load preferred neural model if not loaded yet
    LaunchedEffect(Unit) {
        if (effectiveNeuralEngine.isModelLoaded()) return@LaunchedEffect

        val prefModelName = ModelManager.getActiveModelPreference(context)
        val installed = ModelManager.listInstalledModels(context)
        val modelToLoad = if (!prefModelName.isNullOrBlank()) {
            installed.firstOrNull { it.fileName == prefModelName }?.file
                ?: installed.firstOrNull()?.file
        } else {
            installed.firstOrNull()?.file
        } ?: return@LaunchedEffect

        if (!modelToLoad.exists()) return@LaunchedEffect

        val guardian = IrisXGuardian.evaluateResources(context, modelToLoad.length())
        if (!guardian.canProceed) {
            neuralNotice = "IrisX Guardian: inferencia neuronal pausada (${guardian.reason}). " +
                "IrisX responde con el motor determinista."
            return@LaunchedEffect
        }

        val result = withContext(Dispatchers.IO) {
            effectiveNeuralEngine.loadModel(modelToLoad, threads = 4)
        }
        if (result.isFailure) {
            val reason = result.exceptionOrNull()?.message ?: "causa desconocida"
            neuralNotice = "No se pudo cargar ${modelToLoad.name}: $reason"
        }
    }

    // Load active conversation & messages.
    var loadedConversationId by remember { mutableStateOf<Long?>(null) }

    /**
     * Resolves the conversation on screen and renders its stored turns exactly once.
     *
     * Everything runs under [conversationMutex] so a send that starts while the first load is
     * still in flight waits for it instead of creating a second conversation or losing its
     * live bubble to a late `messages.clear()`.
     */
    suspend fun ensureConversationLoaded(): IrisxConversationEntity = conversationMutex.withLock {
        val conv = activeConversation ?: repo.getOrCreateActiveConversation().also { activeConversation = it }
        if (loadedConversationId == conv.id) return@withLock conv

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
        // Mark as loaded only after the turns are rendered: a cancelled load must be retried,
        // never leave the console empty.
        loadedConversationId = conv.id
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
        conv
    }

    LaunchedEffect(activeConversation?.id) {
        ensureConversationLoaded()
    }

    fun loadConversations() {
        scope.launch {
            conversationsList = repo.getAllConversations()
        }
    }

    fun switchConversation(target: IrisxConversationEntity) {
        // A running generation belongs to the conversation being left behind.
        stopGeneration(markAsStopped = false)
        activeConversation = target
        showConversationsDialog = false
    }

    fun startNewConversation() {
        stopGeneration(markAsStopped = false)
        scope.launch {
            val count = repo.getAllConversations().size
            val created = repo.createNewConversation("Conversación #${count + 1}")
            activeConversation = created
            showConversationsDialog = false
        }
    }

    suspend fun persistTurn(conversationId: Long, role: String, content: String) {
        try {
            repo.appendMessage(
                IrisxMessageEntity(
                    conversationId = conversationId,
                    senderRole = role,
                    content = content,
                    createdAtMillis = System.currentTimeMillis()
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A storage failure must not swallow the reply the user already sees.
        }
    }

    suspend fun appendDeterministicReply(
        conversation: IrisxConversationEntity,
        userMessage: String,
        neuralAvailable: Boolean,
        turnId: String
    ) {
        if (!turnLedger.isActive(turnId)) return

        var replyText: String
        var failed = false
        try {
            // IrisXCore.ask() is the single writer of deterministic turns: it persists the USER
            // message and the IRISX reply. Persisting here too created the duplicated pairs.
            val resp = irisXCore.ask(userMessage, conversation = conversation)
            replyText = when (resp) {
                is IrisXResponse.Success -> resp.message
                is IrisXResponse.RequiresConfirmation -> "${resp.question}\nPara confirmar escribí: ${resp.confirmCommand}"
                is IrisXResponse.Unrecognized ->
                    if (neuralAvailable) {
                        resp.message
                    } else {
                        "No entendí la consulta '$userMessage'. El motor neuronal no está cargado (podés activarlo en tu Perfil). Escribí 'ayuda' para ver comandos deterministas disponibles."
                    }
            }
            refreshContextBadge()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            failed = true
            replyText = "No pude procesar la consulta: ${e.message ?: e::class.simpleName}"
        }

        if (!turnLedger.isActive(turnId)) return
        messages.add(
            ChatMessage(
                text = replyText,
                kind = if (failed) ChatMessageKind.ERROR else ChatMessageKind.ASSISTANT
            )
        )
    }

    suspend fun streamNeuralReply(
        conversation: IrisxConversationEntity,
        userMessage: String,
        history: List<Pair<String, String>>,
        turnId: String
    ) {
        if (!turnLedger.isActive(turnId)) return
        val messageId = UUID.randomUUID().toString()
        streamingMessageId = messageId
        messages.add(
            ChatMessage(
                id = messageId,
                text = IrisXReplyAccumulator.PLACEHOLDER,
                kind = ChatMessageKind.ASSISTANT
            )
        )

        val accumulator = IrisXReplyAccumulator()
        try {
            val prompt = IrisXContextBuilder.buildPrompt(
                userMessage = userMessage,
                db = db,
                conversationHistory = history
            )

            effectiveNeuralEngine.generate(prompt, InferenceParams(maxTokens = 256)).collect { chunk ->
                if (!turnLedger.isActive(turnId)) {
                    throw CancellationException("Generación reemplazada por otra más reciente.")
                }
                accumulator.append(chunk)
                val visible = accumulator.contentSoFar().let { partial ->
                    if (partial.isBlank()) {
                        IrisXReplyAccumulator.PLACEHOLDER
                    } else {
                        IrisXContextBuilder.stripChatMlTokens(partial)
                    }
                }
                replaceMessage(messageId) { it.copy(text = visible) }
            }

            if (!turnLedger.isActive(turnId)) return

            val metrics = effectiveNeuralEngine.metrics
            val footer = if (metrics.tokensPerSecond > 0.0) {
                "⚡ ${String.format("%.1f", metrics.tokensPerSecond)} t/s • ${metrics.lastInferenceDurationMillis} ms • ${effectiveNeuralEngine.currentModelFile?.name}"
            } else {
                null
            }

            if (accumulator.isPersistable) {
                val finalText = accumulator.finalText()
                replaceMessage(messageId) {
                    it.copy(text = finalText, kind = ChatMessageKind.ASSISTANT, metricsFooter = footer)
                }
                // Only real model output is stored; the metrics footer stays in the UI.
                persistTurn(conversation.id, "IRISX", finalText)
            } else {
                // A failed turn is shown but never stored nor replayed as conversation content.
                replaceMessage(messageId) {
                    it.copy(
                        text = accumulator.errorMessage ?: "IrisX no produjo una respuesta.",
                        kind = ChatMessageKind.ERROR
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            replaceMessage(messageId) {
                it.copy(
                    text = "Error inesperado en IrisX: ${e.message ?: e::class.simpleName}",
                    kind = ChatMessageKind.ERROR
                )
            }
        }
    }

    /**
     * Single recovery attempt when the native session was lost.
     * Without it, one fatal native error degrades IrisX to the deterministic engine until the
     * user reloads the model by hand, which reads as "IrisX stopped working".
     */
    suspend fun ensureNeuralReady(): Boolean {
        if (effectiveNeuralEngine.isModelLoaded()) return true
        if (effectiveNeuralEngine.state != EngineState.ERROR) return false

        val modelFile = effectiveNeuralEngine.currentModelFile
            ?: ModelManager.listInstalledModels(context).firstOrNull()?.file
            ?: return false
        if (!modelFile.exists()) return false

        val guardian = IrisXGuardian.evaluateResources(context, modelFile.length())
        if (!guardian.canProceed) return false

        neuralNotice = "Motor neuronal caído. Reiniciando ${modelFile.name}…"
        val result = withContext(Dispatchers.IO) {
            effectiveNeuralEngine.loadModel(modelFile, threads = 4)
        }
        return if (result.isSuccess) {
            neuralNotice = null
            true
        } else {
            neuralNotice = "No se pudo reiniciar el motor neuronal: ${result.exceptionOrNull()?.message ?: "causa desconocida"}"
            false
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        // Single-flight: a double tap, a recomposition or a repeated click cannot start two
        // turns for the same send. Sending identical text later is allowed because that
        // would be a brand new turn with its own identifier.
        val turnId = turnLedger.beginTurn() ?: return

        inputText = ""

        currentGenerationJob = scope.launch {
            try {
                // Resolve + render under the same lock the initial load uses, so the first
                // message is never dropped nor duplicated by a late reload.
                val conversation = ensureConversationLoaded()
                if (!turnLedger.isActive(turnId)) return@launch

                // History is captured BEFORE appending the new turn: buildPrompt renders the
                // history and then the current question, so including it here would duplicate it.
                val historyForPrompt = IrisXChatHistory.forPrompt(messages)
                messages.add(ChatMessage(text = trimmed, isUser = true))

                val neuralReady = ensureNeuralReady()
                val guardian = if (neuralReady) {
                    IrisXGuardian.evaluateResources(context, effectiveNeuralEngine.currentModelFile?.length() ?: 0L)
                } else {
                    null
                }
                val guardianBlocked = guardian != null && !guardian.canProceed
                if (guardianBlocked) {
                    neuralNotice = "IrisX Guardian: inferencia neuronal pausada por protección del dispositivo (${guardian.reason}). Respondiendo con el motor determinista."
                }

                val isKnownDeterministic = neuralReady && !guardianBlocked &&
                    irisXCore.parseInput(trimmed) !is com.syntra.app.domain.irisx.IrisXIntent.GeneralQuery

                if (!turnLedger.isActive(turnId)) return@launch

                if (neuralReady && !guardianBlocked && !isKnownDeterministic) {
                    // ask() is not involved on the neural path, so the USER turn is stored here.
                    persistTurn(conversation.id, "USER", trimmed)
                    isGenerating = true
                    streamNeuralReply(conversation, trimmed, historyForPrompt, turnId)
                } else {
                    // ask() persists the USER turn and the IRISX reply: single writer per turn.
                    appendDeterministicReply(conversation, trimmed, neuralReady, turnId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                if (turnLedger.isActive(turnId)) {
                    messages.add(
                        ChatMessage(
                            text = "Error inesperado en IrisX: ${e.message ?: e::class.simpleName}",
                            kind = ChatMessageKind.ERROR
                        )
                    )
                }
            } finally {
                // Never leave the console locked in "Parar" mode, and never let a stale turn
                // release the lock of a newer generation.
                if (turnLedger.finishTurn(turnId)) {
                    streamingMessageId = null
                    isGenerating = false
                }
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
                            if (activeConversation?.id == target.id) {
                                stopGeneration(markAsStopped = false)
                            }
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
        onDismissRequest = {
            stopGeneration(markAsStopped = false)
            onDismiss()
        },
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
                        IconButton(
                            onClick = {
                                stopGeneration(markAsStopped = false)
                                onDismiss()
                            }
                        ) {
                            Text("✕", color = SyntraColors.TextMuted, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = SyntraColors.BorderSubtle
                )

                neuralNotice?.let { notice ->
                    Surface(
                        color = SyntraColors.SurfaceHighlight,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.Error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "⚠ $notice",
                                style = MaterialTheme.typography.labelSmall,
                                color = SyntraColors.TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { neuralNotice = null },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text("OK", style = MaterialTheme.typography.labelSmall, color = SyntraColors.NeonMint)
                            }
                        }
                    }
                }

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
                            onClick = { stopGeneration() },
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
    val align = if (message.kind == ChatMessageKind.USER) Alignment.End else Alignment.Start
    val background = when (message.kind) {
        ChatMessageKind.USER -> SyntraColors.SurfaceHighlight
        ChatMessageKind.ERROR -> SyntraColors.SurfaceCardElevated
        ChatMessageKind.NOTICE -> SyntraColors.SurfaceHighlight
        ChatMessageKind.ASSISTANT -> Color(0xFF071210)
    }
    val border = when (message.kind) {
        ChatMessageKind.USER -> SyntraColors.BorderSubtle
        ChatMessageKind.ERROR -> SyntraColors.Error
        ChatMessageKind.NOTICE -> SyntraColors.BorderSubtle
        ChatMessageKind.ASSISTANT -> SyntraColors.DarkMint
    }
    val textColor = when (message.kind) {
        ChatMessageKind.ERROR -> SyntraColors.Error
        ChatMessageKind.NOTICE -> SyntraColors.TextMuted
        else -> SyntraColors.TextPrimary
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Surface(
            color = background,
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
        // UI-only decoration: never part of the text sent back to the model.
        message.metricsFooter?.let { footer ->
            Text(
                text = footer,
                style = MaterialTheme.typography.labelSmall,
                color = SyntraColors.TextMuted,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}
