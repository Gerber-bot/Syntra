package com.syntra.app.domain.irisx

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.IrisxConversationEntity
import com.syntra.app.data.IrisxMessageEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.irisx.tools.IrisXLocalTools
import com.syntra.app.domain.irisx.tools.LocalToolId
import com.syntra.app.domain.irisx.tools.LocalToolOutcome
import com.syntra.app.domain.irisx.tools.LocalToolPresenter
import com.syntra.app.domain.irisx.tools.LocalToolRequest
import com.syntra.app.domain.irisx.tools.LocalToolExerciseHistory
import com.syntra.app.domain.irisx.tools.LocalToolExerciseSnapshot
import com.syntra.app.domain.irisx.tools.LocalToolWorkout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeToolRepository(
    var sessions: List<WorkoutSessionEntity> = emptyList(),
    var sets: List<WorkoutSetEntity> = emptyList(),
    var exercises: List<ExerciseEntity> = emptyList(),
    var aliases: Map<String, String> = emptyMap(),
    var getAllSessionsError: Throwable? = null,
    var blockSessions: Boolean = false
) : IrisXRepository {

    override suspend fun getLatestSession(): WorkoutSessionEntity? =
        sessions.maxByOrNull { it.startedAtMillis }

    override suspend fun getAllSessions(): List<WorkoutSessionEntity> {
        getAllSessionsError?.let { throw it }
        if (blockSessions) awaitCancellation()
        return sessions
    }

    override suspend fun getSetsForSession(sessionId: Long): List<WorkoutSetEntity> =
        sets.filter { it.sessionId == sessionId }

    override suspend fun getAllSets(): List<WorkoutSetEntity> = sets

    override suspend fun getExerciseNameMap(): Map<Long, String> =
        exercises.associate { it.id to it.displayName }

    override suspend fun getAllExercises(): List<ExerciseEntity> = exercises

    override suspend fun findExerciseByNormalized(normalized: String): ExerciseEntity? =
        exercises.firstOrNull { it.normalizedName == normalized }

    override suspend fun updateExercise(exercise: ExerciseEntity) {}

    override suspend fun getLearnedAliases(): Map<String, String> = aliases

    override suspend fun saveLearnedAlias(alias: String, canonical: String) {
        aliases = aliases + (IrisXAnalytics.normalize(alias) to canonical)
    }

    override suspend fun deleteLearnedAlias(alias: String) {
        aliases = aliases - IrisXAnalytics.normalize(alias)
    }

    override suspend fun saveMemory(memoryType: String, sourceKey: String, targetValue: String) {}

    override suspend fun getOrCreateActiveConversation(): IrisxConversationEntity =
        IrisxConversationEntity(id = 1L, title = "Conversación activa")

    override suspend fun appendMessage(message: IrisxMessageEntity): Long = 1L
}

class IrisXLocalToolsAndHistoryTest {

    private val squat = exercise(1L, "Sentadilla", "Barra")
    private val bench = exercise(2L, "Press de banca", "Barra")
    private val inclineBench = exercise(3L, "Press banca inclinado", "Barra")

    private val legsHeavy = WorkoutSessionEntity(
        id = 1L,
        title = "Piernas Pesado",
        startedAtMillis = 1_700_000_000_000L
    )
    private val legsVolume = WorkoutSessionEntity(
        id = 2L,
        title = "Piernas Volumen",
        startedAtMillis = 1_700_600_000_000L
    )
    private val chestDay = WorkoutSessionEntity(
        id = 3L,
        title = "Pecho Hipertrofia",
        startedAtMillis = 1_701_000_000_000L
    )

    private val squatHeavy = listOf(
        set(1L, 1L, squat.id, 95_000, 6, 1),
        set(2L, 1L, squat.id, 90_000, 8, 2)
    )
    private val squatVolume = listOf(
        set(3L, 2L, squat.id, 100_000, 5, 1),
        set(4L, 2L, squat.id, 95_000, 7, 2)
    )
    private val benchSets = listOf(
        set(5L, 3L, bench.id, 80_000, 8, 1),
        set(6L, 3L, bench.id, 80_000, 7, 2)
    )

    // 1. Historial vacío ---------------------------------------------------------------

    @Test
    fun `empty database reports missing data for every tool`() = runBlocking {
        val tools = IrisXLocalTools(FakeToolRepository())

        assertTrue(tools.execute(LocalToolRequest.LastWorkout) is LocalToolOutcome.NoData)
        assertTrue(
            tools.execute(LocalToolRequest.LastExerciseSession("sentadilla")) is LocalToolOutcome.NoData
        )
        assertTrue(
            tools.execute(LocalToolRequest.ExerciseHistory("sentadilla")) is LocalToolOutcome.NoData
        )
    }

    @Test
    fun `a session without sets is insufficient data, not invented sets`() = runBlocking {
        val repo = FakeToolRepository(sessions = listOf(legsHeavy), exercises = listOf(squat))
        val outcome = IrisXLocalTools(repo).execute(LocalToolRequest.LastWorkout)

        assertTrue(outcome is LocalToolOutcome.InsufficientData)
        assertTrue((outcome as LocalToolOutcome.InsufficientData).reason.contains("no tiene series"))
    }

    // 2. Una sesión y múltiples sesiones ------------------------------------------------

    @Test
    fun `last workout returns the newest session with real set count`() = runBlocking {
        val repo = FakeToolRepository(
            sessions = listOf(legsHeavy, legsVolume, chestDay),
            sets = squatHeavy + squatVolume + benchSets,
            exercises = listOf(squat, bench)
        )
        val outcome = IrisXLocalTools(repo).execute(LocalToolRequest.LastWorkout)

        assertTrue(outcome is LocalToolOutcome.Success)
        val workout = (outcome as LocalToolOutcome.Success).data as LocalToolWorkout
        assertEquals(chestDay.id, workout.sessionId)
        assertEquals("Pecho Hipertrofia", workout.title)
        assertEquals(2, workout.totalSets)
        assertEquals(listOf("Press de banca"), workout.exerciseNames)
        assertEquals(listOf("80 kg"), workout.sets.map { it.displayLoad }.distinct())
    }

    @Test
    fun `one session history is available but not comparable`() = runBlocking {
        val repo = FakeToolRepository(
            sessions = listOf(legsHeavy),
            sets = squatHeavy,
            exercises = listOf(squat)
        )
        val outcome = IrisXLocalTools(repo).execute(LocalToolRequest.ExerciseHistory("sentadilla"))

        assertTrue(outcome is LocalToolOutcome.Success)
        val history = (outcome as LocalToolOutcome.Success).data as LocalToolExerciseHistory
        assertEquals(1, history.entries.size)
        assertFalse(history.comparisonAvailable)
    }

    // 3. Ejercicios repetidos en fechas diferentes -------------------------------------

    @Test
    fun `history lists repeated exercise sessions newest first with verified sets`() = runBlocking {
        val repo = FakeToolRepository(
            sessions = listOf(legsHeavy, legsVolume),
            sets = squatHeavy + squatVolume,
            exercises = listOf(squat)
        )
        val outcome = IrisXLocalTools(repo).execute(LocalToolRequest.ExerciseHistory("sentadilla"))

        assertTrue(outcome is LocalToolOutcome.Success)
        val history = (outcome as LocalToolOutcome.Success).data as LocalToolExerciseHistory
        assertEquals(2, history.entries.size)
        assertEquals(legsVolume.id, history.entries[0].sessionId)
        assertEquals(legsHeavy.id, history.entries[1].sessionId)
        assertEquals("100 kg", history.entries[0].topLoadDisplay)
        assertEquals("95 kg", history.entries[1].topLoadDisplay)
        assertEquals(2, history.entries[0].setsCount)
        assertTrue(history.comparisonAvailable)
    }

    // 4. Alias y nombres similares -----------------------------------------------------

    @Test
    fun `a learned alias resolves to exactly one exercise`() = runBlocking {
        val repo = FakeToolRepository(
            sessions = listOf(chestDay),
            sets = benchSets,
            exercises = listOf(bench, inclineBench),
            aliases = mapOf("banca" to "Press de banca")
        )
        val outcome = IrisXLocalTools(repo).execute(LocalToolRequest.LastExerciseSession("banca"))

        assertTrue(outcome is LocalToolOutcome.Success)
        val snapshot = (outcome as LocalToolOutcome.Success).data as LocalToolExerciseSnapshot
        assertEquals(bench.id, snapshot.exerciseId)
        assertEquals("Press de banca", snapshot.exerciseName)
        assertEquals("80 kg", snapshot.topLoadDisplay)
        assertEquals(2, snapshot.setsCount)
        assertEquals(15, snapshot.totalReps)
    }

    @Test
    fun `a similar but different name is not resolved by approximation`() = runBlocking {
        val repo = FakeToolRepository(
            sessions = listOf(chestDay),
            sets = benchSets,
            exercises = listOf(bench, inclineBench)
        )
        val outcome = IrisXLocalTools(repo).execute(
            LocalToolRequest.LastExerciseSession("press banca inclinada")
        )

        assertTrue(outcome is LocalToolOutcome.NoData)
        assertEquals(
            "press banca inclinada",
            (outcome as LocalToolOutcome.NoData).subject
        )
    }

    @Test
    fun `duplicate normalized names are reported as an ambiguous query, not guessed`() = runBlocking {
        val duplicate = exercise(99L, "Sentadilla", "Barra")
        val repo = FakeToolRepository(
            sessions = listOf(legsHeavy),
            sets = squatHeavy,
            exercises = listOf(squat, duplicate)
        )
        val outcome = IrisXLocalTools(repo).execute(LocalToolRequest.LastExerciseSession("sentadilla"))

        assertTrue(outcome is LocalToolOutcome.QueryError)
        assertTrue((outcome as LocalToolOutcome.QueryError).reason.contains("coincide con varios"))
    }

    // 5. Conteo correcto de series -----------------------------------------------------

    @Test
    fun `set counts come from stored rows and never from the session title`() = runBlocking {
        val repo = FakeToolRepository(
            sessions = listOf(legsVolume),
            sets = squatVolume,
            exercises = listOf(squat)
        )
        val outcome = IrisXLocalTools(repo).execute(LocalToolRequest.LastExerciseSession("Sentadilla"))

        val snapshot = (outcome as LocalToolOutcome.Success).data as LocalToolExerciseSnapshot
        assertEquals(2, snapshot.setsCount)
        assertEquals(12, snapshot.totalReps)
        assertEquals(2, snapshot.sets.size)
        assertEquals(listOf(5, 7), snapshot.sets.map { it.repetitions })
    }

    // 6. Datos insuficientes y errores recuperables ------------------------------------

    @Test
    fun `explicit comparison with a single session is insufficient data`() = runBlocking {
        val repo = FakeToolRepository(
            sessions = listOf(legsHeavy),
            sets = squatHeavy,
            exercises = listOf(squat)
        )
        val outcome = IrisXLocalTools(repo).execute(
            LocalToolRequest.ExerciseHistory("sentadilla", requireComparison = true)
        )

        assertTrue(outcome is LocalToolOutcome.InsufficientData)
        val reason = (outcome as LocalToolOutcome.InsufficientData).reason
        assertTrue(reason.contains("Solo hay una sesión"))
        assertTrue(reason.contains("95 kg"))
    }

    @Test
    fun `explicit comparison across incompatible modalities is insufficient data`() = runBlocking {
        val dumbbellSquat = set(20L, 2L, squat.id, 20_000, 10, 1).copy(isDumbbell = true)
        val repo = FakeToolRepository(
            sessions = listOf(legsHeavy, legsVolume),
            sets = squatHeavy + dumbbellSquat,
            exercises = listOf(squat)
        )
        val outcome = IrisXLocalTools(repo).execute(
            LocalToolRequest.ExerciseHistory("sentadilla", requireComparison = true)
        )

        assertTrue(outcome is LocalToolOutcome.InsufficientData)
        assertTrue(
            (outcome as LocalToolOutcome.InsufficientData).reason.contains("modalidades de carga")
        )
    }

    @Test
    fun `a repository failure becomes a query error, not fabricated data`() = runBlocking {
        val repo = FakeToolRepository(getAllSessionsError = IllegalStateException("sqlite unavailable"))
        val outcome = IrisXLocalTools(repo).execute(LocalToolRequest.LastWorkout)

        assertTrue(outcome is LocalToolOutcome.QueryError)
        assertTrue((outcome as LocalToolOutcome.QueryError).reason.contains("sqlite unavailable"))
    }

    // 7. Conversaciones concurrentes ---------------------------------------------------

    @Test
    fun `concurrent conversations keep their own history and never mix kg values`() = runBlocking {
        val repoA = FakeToolRepository(
            sessions = listOf(legsHeavy),
            sets = squatHeavy,
            exercises = listOf(squat)
        )
        val repoB = FakeToolRepository(
            sessions = listOf(legsVolume),
            sets = squatVolume,
            exercises = listOf(squat)
        )
        val coreA = IrisXCore(repoA)
        val coreB = IrisXCore(repoB)

        val (responseA, responseB) = coroutineScope {
            val first = async { coreA.ask("historial de sentadilla") }
            val second = async { coreB.ask("historial de sentadilla") }
            first.await() to second.await()
        }

        val messageA = (responseA as IrisXResponse.Success).message
        val messageB = (responseB as IrisXResponse.Success).message
        assertTrue(messageA.contains("95 kg"))
        assertTrue(messageB.contains("100 kg"))
        assertFalse(messageA.contains("100 kg"))
        assertFalse(messageB.contains("95 kg"))
    }

    // 8. Cancelación -------------------------------------------------------------------

    @Test
    fun `cancellation propagates through the tool instead of becoming a query error`() = runBlocking {
        val repo = FakeToolRepository(blockSessions = true)
        val tools = IrisXLocalTools(repo)

        val job = launch { tools.execute(LocalToolRequest.LastWorkout) }
        yield()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
    }

    // 9. Integridad de la respuesta ----------------------------------------------------

    @Test
    fun `presenter repeats only stored loads and never invents a heavier set`() = runBlocking {
        val repo = FakeToolRepository(
            sessions = listOf(legsHeavy),
            sets = squatHeavy,
            exercises = listOf(squat)
        )
        val outcome = IrisXLocalTools(repo).execute(LocalToolRequest.LastExerciseSession("sentadilla"))
        val presentation = LocalToolPresenter.present(outcome)

        val kgValues = Regex("(\\d+(?:[.,]\\d+)?)\\s*kg")
            .findAll(presentation.text)
            .map { it.groupValues[1].replace(',', '.') }
            .toSet()
        assertEquals(setOf("95", "90"), kgValues)
        assertFalse(presentation.text.contains("120 kg"))
        assertTrue(presentation.facts.any { it.contains("Mejor serie: 95 kg") })

        val workout = (outcome as LocalToolOutcome.Success).data as LocalToolExerciseSnapshot
        assertEquals(95_000, workout.topLoadGrams)
    }

    // 10. Interpretación y enrutamiento -----------------------------------------------

    @Test
    fun `history phrases route to the typed history tool`() {
        val core = IrisXCore(FakeToolRepository())

        val history = core.parseInput("historial de sentadilla")
        assertTrue(history is IrisXIntent.QueryExerciseHistory)
        assertEquals("sentadilla", (history as IrisXIntent.QueryExerciseHistory).exerciseName)
        assertFalse(history.requireComparison)

        val previous = core.parseInput("sesiones anteriores de press de banca")
        assertTrue(previous is IrisXIntent.QueryExerciseHistory)
        assertEquals("press de banca", (previous as IrisXIntent.QueryExerciseHistory).exerciseName)

        val compare = core.parseInput("comparar sentadilla")
        assertTrue(compare is IrisXIntent.QueryExerciseHistory)
        assertTrue((compare as IrisXIntent.QueryExerciseHistory).requireComparison)
        assertEquals("sentadilla", compare.exerciseName)
    }

    @Test
    fun `history parsing does not steal chat cleanup or progression queries`() {
        val core = IrisXCore(FakeToolRepository())

        assertTrue(core.parseInput("borrar historial de chat") is IrisXIntent.ClearConversationHistory)
        assertTrue(core.parseInput("quiero comparar mi progreso") is IrisXIntent.QueryProgression)
    }

    @Test
    fun `core answers history and cites every stored value`() = runBlocking {
        val repo = FakeToolRepository(
            sessions = listOf(legsHeavy, legsVolume),
            sets = squatHeavy + squatVolume,
            exercises = listOf(squat)
        )
        val response = IrisXCore(repo).ask("historial de sentadilla")

        assertTrue(response is IrisXResponse.Success)
        val success = response as IrisXResponse.Success
        assertEquals("QUERY_EXERCISE_HISTORY", success.actionTaken)
        assertTrue(success.message.contains("Piernas Pesado"))
        assertTrue(success.message.contains("Piernas Volumen"))
        assertTrue(success.message.contains("95 kg"))
        assertTrue(success.message.contains("100 kg"))
        assertTrue(success.message.contains("2 series"))
    }

    @Test
    fun `core surfaces insufficient data with a distinct action`() = runBlocking {
        val repo = FakeToolRepository(
            sessions = listOf(legsHeavy),
            sets = squatHeavy,
            exercises = listOf(squat)
        )
        val response = IrisXCore(repo).ask("comparar sentadilla")

        assertTrue(response is IrisXResponse.Success)
        val success = response as IrisXResponse.Success
        assertEquals("QUERY_EXERCISE_HISTORY_INSUFFICIENT_DATA", success.actionTaken)
        assertTrue(success.message.contains("Datos insuficientes"))
    }

    @Test
    fun `core surfaces missing data with a distinct action`() = runBlocking {
        val response = IrisXCore(FakeToolRepository()).ask("historial de sentadilla")

        assertTrue(response is IrisXResponse.Success)
        val success = response as IrisXResponse.Success
        assertEquals("QUERY_EXERCISE_HISTORY_NO_DATA", success.actionTaken)
        assertTrue(success.message.contains("No tengo ese dato"))
    }

    private fun exercise(id: Long, name: String, equipment: String?): ExerciseEntity =
        ExerciseEntity(
            id = id,
            displayName = name,
            normalizedName = IrisXAnalytics.normalize(name),
            muscleGroup = if (name.contains("Press", ignoreCase = true)) "Pecho" else "Piernas",
            equipment = equipment
        )

    private fun set(
        id: Long,
        sessionId: Long,
        exerciseId: Long,
        loadGrams: Int,
        repetitions: Int,
        order: Int
    ): WorkoutSetEntity = WorkoutSetEntity(
        id = id,
        sessionId = sessionId,
        exerciseId = exerciseId,
        orderInExercise = order,
        executionOrder = order,
        loadGrams = loadGrams,
        repetitions = repetitions,
        loadUnit = "KG"
    )
}
