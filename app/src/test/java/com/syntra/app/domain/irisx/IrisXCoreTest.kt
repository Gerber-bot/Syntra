package com.syntra.app.domain.irisx

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeIrisXRepository : IrisXRepository {
    var latestSession: WorkoutSessionEntity? = null
    val sets = mutableListOf<WorkoutSetEntity>()
    val exerciseMap = mutableMapOf<Long, String>()
    val exercisesByNorm = mutableMapOf<String, ExerciseEntity>()
    val learnedAliases = mutableMapOf<String, String>()
    val memories = mutableListOf<Triple<String, String, String>>()

    override suspend fun getLatestSession(): WorkoutSessionEntity? = latestSession

    override suspend fun getSetsForSession(sessionId: Long): List<WorkoutSetEntity> =
        sets.filter { it.sessionId == sessionId }

    override suspend fun getExerciseNameMap(): Map<Long, String> = exerciseMap

    override suspend fun findExerciseByNormalized(normalized: String): ExerciseEntity? =
        exercisesByNorm[normalized]

    override suspend fun updateExercise(exercise: ExerciseEntity) {
        exercisesByNorm[exercise.normalizedName] = exercise
        exerciseMap[exercise.id] = exercise.displayName
    }

    override suspend fun getLearnedAliases(): Map<String, String> = learnedAliases

    override suspend fun saveLearnedAlias(alias: String, canonical: String) {
        learnedAliases[alias] = canonical
    }

    override suspend fun deleteLearnedAlias(alias: String) {
        learnedAliases.remove(alias)
    }

    override suspend fun saveMemory(memoryType: String, sourceKey: String, targetValue: String) {
        memories.add(Triple(memoryType, sourceKey, targetValue))
    }
}

class IrisXCoreTest {

    private lateinit var fakeRepo: FakeIrisXRepository
    private lateinit var irisXCore: IrisXCore

    @Before
    fun setUp() {
        fakeRepo = FakeIrisXRepository()
        irisXCore = IrisXCore(fakeRepo)
    }

    @Test
    fun `parseInput correctly identifies intents`() {
        val intent1 = irisXCore.parseInput("¿cuál fue mi último entrenamiento?")
        assertTrue(intent1 is IrisXIntent.QueryLastWorkout)

        val intent2 = irisXCore.parseInput("alias camilla adelante = Extension de cuadriceps en maquina")
        assertTrue(intent2 is IrisXIntent.LearnAlias)
        val learnAlias = intent2 as IrisXIntent.LearnAlias
        assertEquals("camilla adelante", learnAlias.alias)
        assertEquals("Extension de cuadriceps en maquina", learnAlias.canonical)

        val intent3 = irisXCore.parseInput("info sentadilla")
        assertTrue(intent3 is IrisXIntent.QueryExercise)
        assertEquals("sentadilla", (intent3 as IrisXIntent.QueryExercise).exerciseName)

        val intent4 = irisXCore.parseInput("clasificar: press banca, aperturas, fondos")
        assertTrue(intent4 is IrisXIntent.ClassifySession)
        assertEquals(3, (intent4 as IrisXIntent.ClassifySession).exerciseNames.size)

        val intent5 = irisXCore.parseInput("grupo sentadilla = Piernas")
        assertTrue(intent5 is IrisXIntent.UpdateExerciseMuscle)
        val updateMuscle = intent5 as IrisXIntent.UpdateExerciseMuscle
        assertEquals("sentadilla", updateMuscle.exerciseName)
        assertEquals("Piernas", updateMuscle.muscleGroup)
    }

    @Test
    fun `execute learnAlias saves to memories and resolves subsequently`() = runBlocking {
        fakeRepo.exercisesByNorm["extension de cuadriceps"] = ExerciseEntity(
            id = 1L,
            displayName = "Extensión de cuádriceps",
            normalizedName = "extension de cuadriceps",
            muscleGroup = "Piernas",
            secondaryMuscleGroups = "Cuádriceps"
        )

        val learnIntent = IrisXIntent.LearnAlias(
            alias = "camilla adelante",
            canonical = "Extensión de cuádriceps"
        )
        val response = irisXCore.execute(learnIntent)

        assertTrue(response is IrisXResponse.Success)
        val memories = irisXCore.getLearnedAliases()
        assertEquals("Extensión de cuádriceps", memories["camilla adelante"])

        // Test querying with the learned alias in IrisXClassifier
        val classification = IrisXClassifier.classifyWorkout(
            sessionTitle = "Día de patas",
            exerciseNames = listOf("camilla adelante"),
            learnedAliases = memories
        )
        assertEquals("Piernas", classification.muscleBreakdown["Piernas"]?.let { "Piernas" })
    }

    @Test
    fun `execute queryLastWorkout returns summary of last session`() = runBlocking {
        fakeRepo.exerciseMap[10L] = "Press de banca"
        fakeRepo.latestSession = WorkoutSessionEntity(
            id = 1L,
            title = "Pecho pesado",
            startedAtMillis = 1700000000000L
        )
        fakeRepo.sets.add(
            WorkoutSetEntity(
                id = 100L,
                sessionId = 1L,
                exerciseId = 10L,
                orderInExercise = 1,
                executionOrder = 1,
                loadGrams = 80000,
                repetitions = 10
            )
        )

        val response = irisXCore.execute(IrisXIntent.QueryLastWorkout)
        assertTrue(response is IrisXResponse.Success)
        val success = response as IrisXResponse.Success
        assertTrue(success.message.contains("Pecho pesado"))
        assertTrue(success.message.contains("Press de banca"))
        assertTrue(success.message.contains("Pecho"))
    }

    @Test
    fun `execute queryExercise returns details from catalog`() = runBlocking {
        val response = irisXCore.execute(IrisXIntent.QueryExercise("Press militar"))
        assertTrue(response is IrisXResponse.Success)
        val msg = (response as IrisXResponse.Success).message
        assertTrue(msg.contains("Press militar"))
        assertTrue(msg.contains("Hombros"))
    }

    @Test
    fun `detects conflict when learning alias that already points to different exercise`() = runBlocking {
        fakeRepo.learnedAliases["rdl"] = "Peso muerto rumano"

        // User attempts to assign rdl to Sentadilla without force
        val response = irisXCore.execute(IrisXIntent.LearnAlias("rdl", "Sentadilla", force = false))
        assertTrue("Should require confirmation", response is IrisXResponse.RequiresConfirmation)
        val conf = response as IrisXResponse.RequiresConfirmation
        assertTrue(conf.question.contains("Peso muerto rumano"))
        assertEquals("confirmar alias rdl = Sentadilla", conf.confirmCommand)

        // Alias in repository must NOT have been overwritten
        assertEquals("Peso muerto rumano", fakeRepo.learnedAliases["rdl"])

        // Now user confirms with force
        val forceResponse = irisXCore.execute(IrisXIntent.LearnAlias("rdl", "Sentadilla", force = true))
        assertTrue(forceResponse is IrisXResponse.Success)
        assertEquals("Sentadilla", fakeRepo.learnedAliases["rdl"])
    }

    @Test
    fun `interprets natural equivalent queries like que hice la ultima vez`() = runBlocking {
        fakeRepo.latestSession = WorkoutSessionEntity(
            id = 1,
            title = "Pecho y Tríceps",
            category = "Pecho",
            startedAtMillis = 1000L,
            finishedAtMillis = 2000L
        )

        val intent = irisXCore.parseInput("¿Qué hice la última vez?")
        assertTrue(intent is IrisXIntent.QueryLastWorkout)

        val res = irisXCore.execute(intent)
        assertTrue(res is IrisXResponse.Success)
        val msg = (res as IrisXResponse.Success).message
        assertTrue(msg.contains("Pecho y Tríceps"))
    }

    @Test
    fun `explains RIR and training concepts deterministically`() = runBlocking {
        val resRir = irisXCore.ask("¿Qué es RIR 1?")
        assertTrue(resRir is IrisXResponse.Success)
        val msgRir = (resRir as IrisXResponse.Success).message
        assertTrue(msgRir.contains("RIR"))
        assertTrue(msgRir.contains("repetición más"))

        val resSobrecarga = irisXCore.ask("¿Qué es sobrecarga progresiva?")
        assertTrue(resSobrecarga is IrisXResponse.Success)
        val msgSobrecarga = (resSobrecarga as IrisXResponse.Success).message
        assertTrue(msgSobrecarga.contains("sobrecarga progresiva"))
    }

    @Test
    fun `delete alias removes equivalence from memory`() = runBlocking {
        fakeRepo.learnedAliases["camilla"] = "Extensión de cuádriceps"
        val response = irisXCore.execute(IrisXIntent.DeleteAlias("camilla"))
        assertTrue(response is IrisXResponse.Success)
        assertTrue(fakeRepo.learnedAliases.isEmpty())
    }
}
