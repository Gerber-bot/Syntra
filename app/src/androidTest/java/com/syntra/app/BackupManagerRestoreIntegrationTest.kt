package com.syntra.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syntra.app.data.*
import com.syntra.app.data.backup.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented integration test for BackupManager.restorePayload() against a real Room database.
 * Tests:
 * 1. Full valid restore replacing all records cleanly.
 * 2. In-transaction failure triggering complete rollback and preserving original records.
 */
@RunWith(AndroidJUnit4::class)
class BackupManagerRestoreIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backupManager = BackupManager(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testRealRestorePayloadSucceedsAndReplacesData() = runBlocking {
        // 1. Populate initial dummy records
        db.profileDao().save(
            ProfileEntity(
                id = 1,
                name = "Original User",
                preferredName = "Original",
                heightCm = 175,
                initialWeightGrams = 75000,
                activityLevel = "Moderada",
                goal = "Fuerza",
                createdAtMillis = 1000L,
                updatedAtMillis = 1000L
            )
        )
        db.weighInDao().insert(WeighInEntity(id = 1, measuredAtMillis = 1000, weightGrams = 75000, note = "Initial"))
        db.exerciseDao().insert(ExerciseEntity(id = 1, displayName = "Sentadilla", normalizedName = "sentadilla", muscleGroup = "Piernas", equipment = "Barra"))
        db.workoutSessionDao().insert(WorkoutSessionEntity(id = 1, title = "Original Session", startedAtMillis = 1000, notes = "", category = "Piernas", sessionUuid = "orig-uuid-1"))
        db.workoutSetDao().insert(WorkoutSetEntity(id = 1, sessionId = 1, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 100000, repetitions = 10, note = "", loadUnit = "KG"))

        // 2. Prepare replacement payload
        val replacementPayload = SyntraBackupPayload(
            formatVersion = 3,
            exportedAtMillis = 2000,
            appVersion = "0.7.0-alpha13",
            profile = ProfileBackupDto(name = "Restored User", preferredName = "Restored", heightCm = 180, initialWeightGrams = 80000, activityLevel = "Alta", goal = "Hipertrofia", createdAtMillis = 2000L, updatedAtMillis = 2000L),
            exercises = listOf(ExerciseBackupDto(id = 10, displayName = "Prensa", normalizedName = "prensa", muscleGroup = "Piernas", equipment = "Máquina")),
            workoutSessions = listOf(WorkoutSessionBackupDto(id = 10, title = "Restored Session", startedAtMillis = 2000, notes = "", category = "Piernas", sessionUuid = "restored-uuid-10")),
            workoutSets = listOf(WorkoutSetBackupDto(id = 10, sessionId = 10, exerciseId = 10, orderInExercise = 1, executionOrder = 1, loadGrams = 120000, repetitions = 8, note = "", loadUnit = "KG")),
            weighIns = listOf(WeighInBackupDto(id = 10, measuredAtMillis = 2000, weightGrams = 80000, note = "Restored")),
            exerciseAliases = emptyList(),
            irisxMemories = emptyList(),
            routines = emptyList(),
            routineExercises = emptyList(),
            irisxConversations = emptyList(),
            irisxMessages = emptyList(),
            categories = emptyList()
        )

        // 3. Execute real restorePayload
        val result = backupManager.restorePayload(replacementPayload)
        assertTrue("Real restorePayload must return Success", result.isSuccess)

        // 4. Verify that data was replaced
        val profile = db.profileDao().get()
        assertNotNull(profile)
        assertEquals("Restored User", profile?.name)

        val sessions = db.workoutSessionDao().getAll()
        assertEquals(1, sessions.size)
        assertEquals("Restored Session", sessions.first().title)
        assertEquals("restored-uuid-10", sessions.first().sessionUuid)

        val weighIns = db.weighInDao().getAll()
        assertEquals(1, weighIns.size)
        assertEquals(80000, weighIns.first().weightGrams)
    }

    @Test
    fun testRealRestorePayloadRollbackOnTransactionFailurePreservesOriginalRecords() = runBlocking {
        // 1. Populate initial dummy records
        db.profileDao().save(
            ProfileEntity(
                id = 1,
                name = "Protected User",
                preferredName = "Protected",
                heightCm = 175,
                initialWeightGrams = 75000,
                activityLevel = "Moderada",
                goal = "Fuerza",
                createdAtMillis = 1000L,
                updatedAtMillis = 1000L
            )
        )
        db.weighInDao().insert(WeighInEntity(id = 1, measuredAtMillis = 1000, weightGrams = 75000, note = "Protected WeighIn"))
        db.exerciseDao().insert(ExerciseEntity(id = 1, displayName = "Sentadilla", normalizedName = "sentadilla", muscleGroup = "Piernas", equipment = "Barra"))
        db.workoutSessionDao().insert(WorkoutSessionEntity(id = 1, title = "Protected Session", startedAtMillis = 1000, notes = "", category = "Piernas", sessionUuid = "protected-uuid-1"))
        db.workoutSetDao().insert(WorkoutSetEntity(id = 1, sessionId = 1, exerciseId = 1, orderInExercise = 1, executionOrder = 1, loadGrams = 100000, repetitions = 10, note = "", loadUnit = "KG"))

        // 2. Prepare payload that will fail inside transaction due to UNIQUE constraint violation on sessionUuid:
        // Two sessions with identical duplicate UUID
        val failingPayload = SyntraBackupPayload(
            formatVersion = 3,
            exportedAtMillis = 2000,
            appVersion = "0.7.0-alpha13",
            profile = ProfileBackupDto(name = "Failed User", preferredName = "Failed", heightCm = 180, initialWeightGrams = 80000, activityLevel = "Alta", goal = "Hipertrofia", createdAtMillis = 2000L, updatedAtMillis = 2000L),
            exercises = listOf(ExerciseBackupDto(id = 10, displayName = "Prensa", normalizedName = "prensa", muscleGroup = "Piernas", equipment = "Máquina")),
            workoutSessions = listOf(
                WorkoutSessionBackupDto(id = 10, title = "Session Dup 1", startedAtMillis = 2000, notes = "", category = "Piernas", sessionUuid = "duplicate-uuid"),
                WorkoutSessionBackupDto(id = 11, title = "Session Dup 2", startedAtMillis = 3000, notes = "", category = "Piernas", sessionUuid = "duplicate-uuid")
            ),
            workoutSets = emptyList(),
            weighIns = listOf(WeighInBackupDto(id = 10, measuredAtMillis = 2000, weightGrams = 80000, note = "")),
            exerciseAliases = emptyList(),
            irisxMemories = emptyList(),
            routines = emptyList(),
            routineExercises = emptyList(),
            irisxConversations = emptyList(),
            irisxMessages = emptyList(),
            categories = emptyList()
        )

        // 3. Execute real restorePayload -> must fail transaction
        val result = backupManager.restorePayload(failingPayload)
        assertTrue("Real restorePayload must fail when payload violates constraints", result.isFailure)

        // 4. Verify 100% ROLLBACK and original data preservation
        val profile = db.profileDao().get()
        assertNotNull(profile)
        assertEquals("Protected User", profile?.name)

        val weighIns = db.weighInDao().getAll()
        assertEquals(1, weighIns.size)
        assertEquals("Protected WeighIn", weighIns.first().note)

        val sessions = db.workoutSessionDao().getAll()
        assertEquals(1, sessions.size)
        assertEquals("Protected Session", sessions.first().title)
        assertEquals("protected-uuid-1", sessions.first().sessionUuid)

        val sets = db.workoutSetDao().getAll()
        assertEquals(1, sets.size)
        assertEquals(100000, sets.first().loadGrams)
    }
}
