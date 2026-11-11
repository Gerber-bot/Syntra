package com.syntra.app.ui.irisx

import com.syntra.app.domain.neural.GenerationChunk
import com.syntra.app.domain.neural.IrisXContextBuilder
import java.util.UUID

/** Visual and semantic role of a chat bubble. */
enum class ChatMessageKind { USER, ASSISTANT, ERROR, NOTICE }

/**
 * A single bubble in the IrisX console.
 *
 * [metricsFooter] is deliberately kept out of [text]: the footer is UI decoration and must
 * never be replayed to the model as if it were part of the assistant answer.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean = false,
    val kind: ChatMessageKind = if (isUser) ChatMessageKind.USER else ChatMessageKind.ASSISTANT,
    val metricsFooter: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Builds the bounded conversation history sent to the neural engine.
 *
 * Only real conversational turns are eligible: error bubbles, notices and the streaming
 * placeholder are excluded so they never become model context.
 */
object IrisXChatHistory {

    const val MAX_TURNS = 6

    fun forPrompt(messages: List<ChatMessage>, maxTurns: Int = MAX_TURNS): List<Pair<String, String>> =
        messages
            .filter { it.kind == ChatMessageKind.USER || it.kind == ChatMessageKind.ASSISTANT }
            .filter { it.text.isNotBlank() && it.text != IrisXReplyAccumulator.PLACEHOLDER }
            .takeLast(maxTurns)
            .map { (if (it.kind == ChatMessageKind.USER) "user" else "assistant") to it.text.trim() }
}

/**
 * Accumulates a streamed reply and keeps generated content separate from failures.
 *
 * A failed turn is not conversation content: it must be shown to the user but never
 * persisted as an IRISX message nor replayed in the next prompt.
 */
class IrisXReplyAccumulator {

    companion object {
        const val PLACEHOLDER = "Pensando…"
    }

    private val content = StringBuilder()

    var errorMessage: String? = null
        private set

    var terminalChunks: Int = 0
        private set

    fun append(chunk: GenerationChunk) {
        if (chunk.isComplete) terminalChunks++
        if (chunk.errorMessage != null) errorMessage = chunk.errorMessage
        if (chunk.text.isNotEmpty()) content.append(chunk.text)
    }

    fun contentSoFar(): String = content.toString()

    fun finalText(): String = IrisXContextBuilder.stripChatMlTokens(content.toString()).trim()

    val isPersistable: Boolean
        get() = errorMessage == null && finalText().isNotBlank()
}
