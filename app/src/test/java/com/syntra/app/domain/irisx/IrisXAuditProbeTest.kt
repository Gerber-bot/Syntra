package com.syntra.app.domain.irisx

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.IrisxConversationEntity
import com.syntra.app.data.IrisxMessageEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit probes. Each test states the CORRECT behavior; a failure proves a defect.
 */
class IrisXAuditProbeTest {

    /** Models Room semantics: deleting a conversation removes it; messages have no FK. */
    private class ConversationAwareRepo : IrisXRepository {
        val conversations = mutableMapOf(1L to IrisxConversationEntity(id = 1L, title = "Activa"))
        val messages = mutableListOf<IrisxMessageEntity>()
        var sessions: List<WorkoutSessionEntity> = emptyList()
        var sets: List<WorkoutSetEntity> = emptyList()
        var exercises: List<ExerciseEntity> = emptyList()

        override suspend fun getLatestSession() = sessions.maxByOrNull { it.startedAtMillis }
        override suspend fun getAllSessions() = sessions
        override suspend fun getAllSets() = sets
        override suspend fun getAllExercises() = exercises
        override suspend fun getSetsForSession(sessionId: Long) = sets.filter { it.sessionId == sessionId }
        override suspend fun getExerciseNameMap() = exercises.associate { it.id to it.displayName }
        override suspend fun findExerciseByNormalized(normalized: String) = exercises.firstOrNull { it.normalizedName == normalized }
        override suspend fun updateExercise(exercise: ExerciseEntity) {}
        override suspend fun getLearnedAliases(): Map<String, String> = emptyMap()
        override suspend fun saveLearnedAlias(alias: String, canonical: String) {}
        override suspend fun deleteLearnedAlias(alias: String) {}
        override suspend fun saveMemory(memoryType: String, sourceKey: String, targetValue: String) {}
        override suspend fun getAllConversations() = conversations.values.toList()
        override suspend fun createNewConversation(title: String): IrisxConversationEntity {
            val id = (conversations.keys.maxOrNull() ?: 0L) + 1
            return IrisxConversationEntity(id = id, title = title).also { conversations[id] = it }
        }
        override suspend fun getOrCreateActiveConversation() =
            conversations.values.firstOrNull() ?: createNewConversation("Principal")
        override suspend fun clearAllConversations() {
            messages.clear(); conversations.clear()
        }
        override suspend fun appendMessage(message: IrisxMessageEntity): Long {
            messages.add(message); return messages.size.toLong()
        }
    }

    // AUD-IRX-01: clear-chat must not leave the UI writing into a deleted conversation.
    @Test
    fun `clearing conversations must not persist turns into the deleted conversation`() = runBlocking {
        val repo = ConversationAwareRepo()
        val core = IrisXCore(repo)
        val conv = repo.getOrCreateActiveConversation()

        core.ask("borrar conversacion", conversation = conv)
        val orphans = repo.messages.filter { it.conversationId !in repo.conversations.keys }
        assertTrue(
            "messages persisted to a conversation that no longer exists: ${orphans.map { it.senderRole }}",
            orphans.isEmpty()
        )
    }

    @Test
    fun `after clearing, the response hands the UI a live conversation to continue in`() = runBlocking {
        val repo = ConversationAwareRepo()
        val core = IrisXCore(repo)
        val conv = repo.getOrCreateActiveConversation()

        val resp = core.ask("borrar conversacion", conversation = conv) as IrisXResponse.Success
        val fresh = resp.payload as? IrisxConversationEntity
        assertTrue("clear must return a live replacement conversation", fresh != null && fresh.id in repo.conversations.keys)
    }

    // AUD-IRX-02: questions about a period must not be parsed as "last session of <exercise>".
    @Test
    fun `period questions are not misparsed as exercise lookups`() {
        val core = IrisXCore(ConversationAwareRepo())
        listOf(
            "¿Cuántas series hice la semana pasada?",
            "¿Cuántas series hice esta semana?",
            "¿Cuánto peso debo usar en sentadilla?"
        ).forEach { q ->
            val intent = core.parseInput(q)
            val target = (intent as? IrisXIntent.QueryLastExerciseLoad)?.exerciseName
            assertFalse("'$q' -> $intent (target='$target')", intent is IrisXIntent.QueryLastExerciseLoad && target != null && target.contains("semana"))
        }
    }

    // AUD-IRX-03: a catalog alias (exact, not fuzzy) must resolve to the stored exercise.
    @Test
    fun `catalog alias resolves to the stored canonical exercise`() = runBlocking {
        val repo = ConversationAwareRepo()
        repo.exercises = listOf(
            ExerciseEntity(id = 2L, displayName = "Press de banca", normalizedName = "press de banca", muscleGroup = "Pecho", equipment = "Barra")
        )
        repo.sessions = listOf(WorkoutSessionEntity(id = 1L, title = "Pecho", startedAtMillis = 1_700_000_000_000L))
        repo.sets = listOf(WorkoutSetEntity(id = 1L, sessionId = 1L, exerciseId = 2L, orderInExercise = 1, executionOrder = 1, loadGrams = 80_000, repetitions = 8))
        val core = IrisXCore(repo)
        val r = core.ask("¿Con cuánto peso hice press banca?", conversation = repo.getOrCreateActiveConversation()) as IrisXResponse.Success
        assertEquals("catalog alias 'press banca' must resolve", "QUERY_LAST_EXERCISE_SESSION", r.actionTaken)
    }
}
