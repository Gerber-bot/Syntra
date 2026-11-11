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
 * Records every write so duplicated turns and conversation misrouting are caught, not guessed.
 */
private class RecordingIrisXRepository : IrisXRepository {
    val appended = mutableListOf<IrisxMessageEntity>()
    val stateUpdates = mutableListOf<IrisxConversationEntity>()
    var activeConversationToReturn = IrisxConversationEntity(id = 1L, title = "Activa")
    var sessionsReads = 0

    override suspend fun getLatestSession(): WorkoutSessionEntity? = null
    override suspend fun getSetsForSession(sessionId: Long): List<WorkoutSetEntity> = emptyList()
    override suspend fun getExerciseNameMap(): Map<Long, String> = emptyMap()
    override suspend fun findExerciseByNormalized(normalized: String): ExerciseEntity? = null
    override suspend fun updateExercise(exercise: ExerciseEntity) {}
    override suspend fun getLearnedAliases(): Map<String, String> = emptyMap()
    override suspend fun saveLearnedAlias(alias: String, canonical: String) {}
    override suspend fun deleteLearnedAlias(alias: String) {}
    override suspend fun saveMemory(memoryType: String, sourceKey: String, targetValue: String) {}

    override suspend fun getAllSessions(): List<WorkoutSessionEntity> {
        sessionsReads++
        return emptyList()
    }

    override suspend fun getOrCreateActiveConversation(): IrisxConversationEntity =
        activeConversationToReturn

    override suspend fun appendMessage(message: IrisxMessageEntity): Long {
        appended.add(message)
        return appended.size.toLong()
    }

    override suspend fun updateConversationState(conversation: IrisxConversationEntity) {
        stateUpdates.add(conversation)
    }
}

class IrisXTurnPersistenceAndRoutingTest {

    @Test
    fun `one deterministic turn persists exactly one user and one reply`() = runBlocking {
        val repo = RecordingIrisXRepository()
        val core = IrisXCore(repo)

        core.ask("recomendaciones generales", referenceTimeMillis = 1_700_000_000_000L)

        val userTurns = repo.appended.filter { it.senderRole == "USER" }
        val replyTurns = repo.appended.filter { it.senderRole == "IRISX" }
        assertEquals("a deterministic turn must be written once", 1, userTurns.size)
        assertEquals(1, replyTurns.size)
        assertEquals("recomendaciones generales", userTurns.single().content)
        assertEquals(2, repo.appended.size)
    }

    @Test
    fun `two consecutive turns are stored without duplicating either`() = runBlocking {
        val repo = RecordingIrisXRepository()
        val core = IrisXCore(repo)

        core.ask("recomendaciones generales", referenceTimeMillis = 1_700_000_000_000L)
        core.ask("recomendaciones generales", referenceTimeMillis = 1_700_000_001_000L)

        // A legitimately repeated question produces two independent turns, not four bubbles.
        assertEquals(4, repo.appended.size)
        assertEquals(2, repo.appended.count { it.senderRole == "USER" })
        assertEquals(2, repo.appended.count { it.senderRole == "IRISX" })
    }

    @Test
    fun `ask writes to the conversation on screen instead of the most recently updated one`() =
        runBlocking {
            val repo = RecordingIrisXRepository()
            // getOrCreateActiveConversation() is ordered by updatedAtMillis, so it may point to a
            // different chat than the one the user is looking at.
            repo.activeConversationToReturn = IrisxConversationEntity(id = 99L, title = "Otra")
            val onScreen = IrisxConversationEntity(id = 7L, title = "En pantalla")
            val core = IrisXCore(repo)

            core.ask("recomendaciones generales", conversation = onScreen)

            assertTrue(repo.appended.isNotEmpty())
            assertTrue(
                "all writes must land in the conversation passed by the UI",
                repo.appended.all { it.conversationId == onScreen.id }
            )
            assertEquals(onScreen.id, repo.stateUpdates.single().id)
        }

    @Test
    fun `a general recommendation is classified as general and does not read history`() =
        runBlocking {
            val repo = RecordingIrisXRepository()
            val core = IrisXCore(repo)

            val intent = core.parseInput("recomendaciones de que hacer un dia de piernas")
            assertTrue(intent is IrisXIntent.QueryRecommendations)
            assertTrue((intent as IrisXIntent.QueryRecommendations).general)

            val response = core.ask("recomendaciones de que hacer un dia de piernas")
            assertTrue(response is IrisXResponse.Success)
            val success = response as IrisXResponse.Success
            assertEquals("QUERY_RECOMMENDATIONS_GENERAL", success.actionTaken)
            assertTrue(success.message.contains("Guía general"))
            assertFalse(success.message.contains("Recomendaciones verificables"))
            // The general answer must never depend on the athlete's sessions.
            assertEquals(0, repo.sessionsReads)
        }

    @Test
    fun `a personal recommendation still uses history`() = runBlocking {
        val repo = RecordingIrisXRepository()
        val core = IrisXCore(repo)

        val intent = core.parseInput("recomendaciones para mejorar mi press banca")
        assertTrue(intent is IrisXIntent.QueryRecommendations)
        assertFalse((intent as IrisXIntent.QueryRecommendations).general)

        val response = core.ask("recomendaciones para mejorar mi press banca")
        assertTrue(response is IrisXResponse.Success)
        val success = response as IrisXResponse.Success
        assertEquals("QUERY_RECOMMENDATIONS", success.actionTaken)
        assertTrue(success.message.contains("Recomendaciones verificables"))
        assertEquals(1, repo.sessionsReads)
    }

    @Test
    fun `bare recommendations keep the personalized behaviour`() = runBlocking {
        val repo = RecordingIrisXRepository()
        val core = IrisXCore(repo)

        val intent = core.parseInput("recomendaciones")
        assertTrue(intent is IrisXIntent.QueryRecommendations)
        assertFalse((intent as IrisXIntent.QueryRecommendations).general)

        val response = core.ask("recomendaciones")
        assertTrue(response is IrisXResponse.Success)
        assertEquals("QUERY_RECOMMENDATIONS", (response as IrisXResponse.Success).actionTaken)
    }
}
