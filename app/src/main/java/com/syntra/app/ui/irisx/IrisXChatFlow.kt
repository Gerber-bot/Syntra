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

/**
 * Single-flight coordinator for one conversational turn.
 *
 * Every send receives a unique [turnId]. Only one turn may own the console at a time, so a
 * double tap or a recomposition cannot start two generations for the same tap. A turn that was
 * cancelled or superseded stops being [isActive], which prevents it from appending or persisting
 * anything. Sending the same text again later is allowed: a new turn gets a new identifier.
 */
class IrisXTurnLedger {

    private var activeTurnId: String? = null

    val hasActiveTurn: Boolean
        get() = activeTurnId != null

    /** @return the new turn identifier, or null when another turn is still in flight. */
    fun beginTurn(): String? {
        if (activeTurnId != null) return null
        val id = UUID.randomUUID().toString()
        activeTurnId = id
        return id
    }

    fun isActive(turnId: String?): Boolean = turnId != null && activeTurnId == turnId

    /** @return true when [turnId] was the active turn and the console has been released. */
    fun finishTurn(turnId: String): Boolean {
        if (activeTurnId != turnId) return false
        activeTurnId = null
        return true
    }

    /** Releases the active turn (cancellation) and returns its identifier, if any. */
    fun cancelActive(): String? {
        val current = activeTurnId
        activeTurnId = null
        return current
    }
}
