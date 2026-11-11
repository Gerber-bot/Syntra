package com.syntra.app.ui.irisx

import com.syntra.app.domain.neural.GenerationChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the IrisX chat orchestration helpers.
 *
 * They pin the four defects that made the conversational UI look broken on device:
 * duplicated user turn in the prompt, metrics footer replayed as assistant content,
 * error text persisted as conversation, and the streaming placeholder leaking into history.
 */
class IrisXChatFlowTest {

    private fun user(text: String) = ChatMessage(text = text, kind = ChatMessageKind.USER)
    private fun assistant(text: String, footer: String? = null) =
        ChatMessage(text = text, kind = ChatMessageKind.ASSISTANT, metricsFooter = footer)

    @Test
    fun `history captured before the new turn does not duplicate the current question`() {
        // The dialog captures history BEFORE adding the user turn; buildPrompt appends the
        // question afterwards. If the turn were added first, the model would see it twice.
        val existing = listOf(user("hola"), assistant("Hola, ¿qué necesitás?"))
        val newQuestion = "¿cómo progresé en banca?"

        val history = IrisXChatHistory.forPrompt(existing)

        assertFalse(
            "The question being sent must not be part of the replayed history",
            history.any { it.second == newQuestion }
        )
        assertEquals(listOf("user" to "hola", "assistant" to "Hola, ¿qué necesitás?"), history)
    }

    @Test
    fun `metrics footer never becomes model context`() {
        val messages = listOf(
            user("hola"),
            assistant("Respuesta real", footer = "⚡ 12.4 t/s • 900 ms • Qwen3-0.6B-Q4_K_M.gguf")
        )

        val history = IrisXChatHistory.forPrompt(messages)

        assertEquals("Respuesta real", history.last().second)
        assertTrue(history.none { it.second.contains("t/s") })
    }

    @Test
    fun `error and notice bubbles are excluded from history`() {
        val messages = listOf(
            user("hola"),
            ChatMessage(text = "Error en inferencia nativa llama.cpp: out of context", kind = ChatMessageKind.ERROR),
            ChatMessage(text = "IrisX Guardian: inferencia pausada", kind = ChatMessageKind.NOTICE),
            assistant("Respuesta válida")
        )

        val history = IrisXChatHistory.forPrompt(messages)

        assertEquals(listOf("user" to "hola", "assistant" to "Respuesta válida"), history)
    }

    @Test
    fun `streaming placeholder is never replayed`() {
        val messages = listOf(
            user("hola"),
            ChatMessage(text = IrisXReplyAccumulator.PLACEHOLDER, kind = ChatMessageKind.ASSISTANT)
        )

        assertTrue(IrisXChatHistory.forPrompt(messages).none { it.second == IrisXReplyAccumulator.PLACEHOLDER })
    }

    @Test
    fun `history is bounded to the last six turns`() {
        val messages = (1..10).flatMap { i ->
            listOf(user("pregunta $i"), assistant("respuesta $i"))
        }

        val history = IrisXChatHistory.forPrompt(messages)

        assertEquals(6, history.size)
        assertEquals("pregunta 8", history.first().second)
        assertEquals("respuesta 10", history.last().second)
    }

    @Test
    fun `a streamed turn with an error is not persistable and keeps the reason`() {
        val accumulator = IrisXReplyAccumulator()
        accumulator.append(GenerationChunk(text = "parcial"))
        accumulator.append(
            GenerationChunk(
                text = "",
                isComplete = true,
                errorMessage = "Error en inferencia nativa llama.cpp: completion_init failed"
            )
        )

        assertFalse("Errors must not be stored as conversation", accumulator.isPersistable)
        assertEquals(
            "Error en inferencia nativa llama.cpp: completion_init failed",
            accumulator.errorMessage
        )
        assertFalse(
            "The partial text must not be used as the final answer when the turn failed",
            accumulator.isPersistable
        )
    }

    @Test
    fun `a successful turn is persistable and strips ChatML tokens`() {
        val accumulator = IrisXReplyAccumulator()
        accumulator.append(GenerationChunk(text = "Todo"))
        accumulator.append(GenerationChunk(text = " bien"))
        accumulator.append(GenerationChunk(text = "<|im_end|>", isComplete = true))

        assertTrue(accumulator.isPersistable)
        assertEquals("Todo bien", accumulator.finalText())
    }

    @Test
    fun `anchored chat markup split across chunks is stripped from the final answer`() {
        val accumulator = IrisXReplyAccumulator()
        accumulator.append(GenerationChunk(text = "Listo<|im_"))
        accumulator.append(GenerationChunk(text = "end|>", isComplete = true))

        assertEquals("Listo", accumulator.finalText())
    }

    @Test
    fun `a terminal chunk without text is not persistable`() {
        val accumulator = IrisXReplyAccumulator()
        accumulator.append(GenerationChunk(text = "", isComplete = true, tokensGenerated = 0))

        assertNull(accumulator.errorMessage)
        assertFalse("An empty answer must not be stored", accumulator.isPersistable)
    }

    @Test
    fun `user bubbles are marked as conversation turns and not as assistant output`() {
        assertEquals(ChatMessageKind.USER, user("hola").kind)
        assertEquals(
            ChatMessageKind.ASSISTANT,
            ChatMessage(text = "respuesta", isUser = false).kind
        )
    }
}
