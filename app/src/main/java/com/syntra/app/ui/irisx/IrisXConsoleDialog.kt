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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.syntra.app.data.AppDatabase
import com.syntra.app.domain.irisx.IrisXCore
import com.syntra.app.domain.irisx.IrisXIntent
import com.syntra.app.domain.irisx.IrisXResponse
import com.syntra.app.domain.irisx.RoomIrisXRepository
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
    activeWorkoutTitle: String? = null,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val repo = remember(db) { RoomIrisXRepository(db) }
    val irisXCore = remember(repo) { IrisXCore(repo) }
    var inputText by remember { mutableStateOf("") }
    var activeTopicLabel by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()

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
                    text = "Hola. Soy IrisX, tu núcleo inteligente offline en Syntra.\n" +
                        "Podés consultar progresión ('¿cómo progresé en espalda?'), hacer preguntas de seguimiento ('¿y el mes pasado?'), pedir recomendaciones o gestionar alias.",
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
        if (trimmed.isBlank()) return
        messages.add(ChatMessage(text = trimmed, isUser = true))
        inputText = ""

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
            messages.add(ChatMessage(text = replyText, isUser = false))
            if (messages.isNotEmpty()) {
                listState.scrollToItem(messages.size - 1)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF141618),
            tonalElevation = 8.dp
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✦", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text(
                                text = "IrisX Console",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = when {
                                    activeWorkoutTitle != null && activeTopicLabel != null ->
                                        "En curso: $activeWorkoutTitle • Contexto: $activeTopicLabel"
                                    activeWorkoutTitle != null ->
                                        "Entrenamiento activo: $activeWorkoutTitle (notas preservadas)"
                                    activeTopicLabel != null ->
                                        "Contexto activo: $activeTopicLabel"
                                    else ->
                                        "Núcleo offline • Memoria contextual SQLite"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    irisXCore.execute(IrisXIntent.ClearConversationHistory)
                                    messages.clear()
                                    messages.add(
                                        ChatMessage(
                                            text = "Conversación eliminada y contexto reiniciado.",
                                            isUser = false
                                        )
                                    )
                                    activeTopicLabel = null
                                }
                            }
                        ) {
                            Text("Limpiar", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        IconButton(onClick = onDismiss) {
                            Text("✕", color = Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF2A2A2A))

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
                        SuggestionChip(
                            onClick = { sendMessage(chipText) },
                            label = { Text(chipText, style = MaterialTheme.typography.labelSmall) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF1E2224),
                                labelColor = MaterialTheme.colorScheme.primary
                            ),
                            border = null
                        )
                    }
                }

                // Messages list
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                shape = RoundedCornerShape(
                                    topStart = 12.dp,
                                    topEnd = 12.dp,
                                    bottomStart = if (msg.isUser) 12.dp else 2.dp,
                                    bottomEnd = if (msg.isUser) 2.dp else 12.dp
                                ),
                                color = if (msg.isUser) MaterialTheme.colorScheme.primary else Color(0xFF202428),
                                modifier = Modifier.widthIn(max = 310.dp)
                            ) {
                                Text(
                                    text = msg.text,
                                    color = if (msg.isUser) Color.Black else Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }

                // Input field and send button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Preguntá a IrisX...", style = MaterialTheme.typography.bodySmall) },
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF333333),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Button(
                        onClick = { sendMessage(inputText) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text("Enviar", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
