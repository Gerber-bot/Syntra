package com.syntra.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSerializerTest {

    @Test
    fun `serialization and deserialization roundtrip preserves all data`() {
        val payload = SyntraBackupPayload(
            formatVersion = 1,
            appVersion = "0.2.0",
            exportedAtMillis = 1700000000000L,
            profile = ProfileBackupDto(
                name = "Federico",
                preferredName = "Fede",
                heightCm = 175,
                initialWeightGrams = 75000,
                activityLevel = "moderada",
                goal = "fuerza e hipertrofia",
                createdAtMillis = 1690000000000L,
                updatedAtMillis = 1695000000000L
            ),
            weighIns = listOf(
                WeighInBackupDto(id = 1, measuredAtMillis = 1690000000000L, weightGrams = 75000, note = "Inicio"),
                WeighInBackupDto(id = 2, measuredAtMillis = 1695000000000L, weightGrams = 74500, note = "Control")
            ),
            exercises = listOf(
                ExerciseBackupDto(
                    id = 1,
                    displayName = "Remo con barra",
                    normalizedName = "remo con barra",
                    muscleGroup = "Espalda",
                    secondaryMuscleGroups = "Bíceps, Core",
                    equipment = "Barra",
                    exerciseType = "fuerza",
                    status = "CONFIRMED",
                    createdAtMillis = 1690000000000L
                )
            ),
            exerciseAliases = listOf(
                ExerciseAliasBackupDto(id = 1, exerciseId = 1, normalizedAlias = "barbell row", createdAtMillis = 1690000000000L)
            ),
            workoutSessions = listOf(
                WorkoutSessionBackupDto(id = 10, title = "Torso", startedAtMillis = 1696000000000L, finishedAtMillis = 1696003600000L, notes = "Buena sesión")
            ),
            workoutSets = listOf(
                WorkoutSetBackupDto(
                    id = 100,
                    sessionId = 10,
                    exerciseId = 1,
                    orderInExercise = 1,
                    executionOrder = 1,
                    loadGrams = 70000,
                    repetitions = 10,
                    rir = 2,
                    isAssisted = false,
                    isDumbbell = false,
                    note = "RPE 8",
                    plannedTarget = "4x10-12"
                )
            ),
            irisxMemories = listOf(
                IrisxMemoryBackupDto(
                    id = 1,
                    memoryType = "ALIAS",
                    sourceKey = "rdl",
                    targetValue = "Peso muerto rumano",
                    confidence = 1.0,
                    createdAtMillis = 1690000000000L,
                    updatedAtMillis = 1690000000000L
                ),
                IrisxMemoryBackupDto(
                    id = 2,
                    memoryType = "PREFERENCE",
                    sourceKey = "warmup_rest_sec",
                    targetValue = "90",
                    confidence = 1.0,
                    createdAtMillis = 1690000000000L,
                    updatedAtMillis = 1690000000000L
                )
            )
        )

        val json = BackupSerializer.serialize(payload)
        val result = BackupSerializer.deserializeAndValidate(json)

        assertTrue(result is BackupValidationResult.Success)
        val restored = (result as BackupValidationResult.Success).payload

        assertEquals(1, restored.formatVersion)
        assertEquals("0.2.0", restored.appVersion)
        assertEquals(1700000000000L, restored.exportedAtMillis)

        assertNotNull(restored.profile)
        assertEquals("Federico", restored.profile?.name)
        assertEquals("Fede", restored.profile?.preferredName)
        assertEquals(175, restored.profile?.heightCm)
        assertEquals(75000, restored.profile?.initialWeightGrams)

        assertEquals(2, restored.weighIns.size)
        assertEquals(74500, restored.weighIns[1].weightGrams)
        assertEquals("Control", restored.weighIns[1].note)

        assertEquals(1, restored.exercises.size)
        assertEquals("Remo con barra", restored.exercises[0].displayName)
        assertEquals("Bíceps, Core", restored.exercises[0].secondaryMuscleGroups)

        assertEquals(1, restored.exerciseAliases.size)
        assertEquals("barbell row", restored.exerciseAliases[0].normalizedAlias)

        assertEquals(1, restored.workoutSessions.size)
        assertEquals("Torso", restored.workoutSessions[0].title)

        assertEquals(1, restored.workoutSets.size)
        assertEquals(70000, restored.workoutSets[0].loadGrams)
        assertEquals(10, restored.workoutSets[0].repetitions)
        assertEquals(2, restored.workoutSets[0].rir)
        assertEquals("4x10-12", restored.workoutSets[0].plannedTarget)

        assertEquals(2, restored.irisxMemories.size)
        assertEquals("rdl", restored.irisxMemories[0].sourceKey)
        assertEquals("Peso muerto rumano", restored.irisxMemories[0].targetValue)
        assertEquals("PREFERENCE", restored.irisxMemories[1].memoryType)
        assertEquals("90", restored.irisxMemories[1].targetValue)
    }

    @Test
    fun `roundtrip works without profile and with empty collections`() {
        val payload = SyntraBackupPayload(
            formatVersion = 1,
            appVersion = "0.2.0",
            exportedAtMillis = 1700000000000L,
            profile = null,
            weighIns = emptyList()
        )

        val json = BackupSerializer.serialize(payload)
        val result = BackupSerializer.deserializeAndValidate(json)

        assertTrue(result is BackupValidationResult.Success)
        val restored = (result as BackupValidationResult.Success).payload
        assertNull(restored.profile)
        assertTrue(restored.weighIns.isEmpty())
        assertTrue(restored.exercises.isEmpty())
    }

    @Test
    fun `deserialization rejects empty or corrupt json`() {
        val emptyResult = BackupSerializer.deserializeAndValidate("")
        assertTrue(emptyResult is BackupValidationResult.Error)

        val corruptResult = BackupSerializer.deserializeAndValidate("{ invalid json [")
        assertTrue(corruptResult is BackupValidationResult.Error)
    }

    @Test
    fun `deserialization rejects missing or unsupported format version`() {
        val noVersionJson = """{"appVersion": "0.2.0", "exportedAtMillis": 1700000000000}"""
        val noVersionResult = BackupSerializer.deserializeAndValidate(noVersionJson)
        assertTrue(noVersionResult is BackupValidationResult.Error)

        val futureVersionJson = """{"formatVersion": 99, "appVersion": "0.2.0", "exportedAtMillis": 1700000000000}"""
        val futureResult = BackupSerializer.deserializeAndValidate(futureVersionJson)
        assertTrue(futureResult is BackupValidationResult.Error)
    }

    @Test
    fun `deserialization rejects invalid profile data`() {
        val invalidHeightJson = """
            {
                "formatVersion": 1,
                "exportedAtMillis": 1700000000000,
                "profile": {
                    "name": "Fede",
                    "heightCm": 0,
                    "initialWeightGrams": 75000
                }
            }
        """.trimIndent()
        val result = BackupSerializer.deserializeAndValidate(invalidHeightJson)
        assertTrue(result is BackupValidationResult.Error)
    }

    @Test
    fun `deserialization rejects invalid weigh in entries`() {
        val invalidWeighInJson = """
            {
                "formatVersion": 1,
                "exportedAtMillis": 1700000000000,
                "weighIns": [
                    { "measuredAtMillis": 0, "weightGrams": -50 }
                ]
            }
        """.trimIndent()
        val result = BackupSerializer.deserializeAndValidate(invalidWeighInJson)
        assertTrue(result is BackupValidationResult.Error)
    }

    @Test
    fun `formatVersion 2 roundtrip preserves routines and conversations and keeps v1 compatibility`() {
        val v2Payload = SyntraBackupPayload(
            formatVersion = 2,
            appVersion = "0.4.0",
            exportedAtMillis = 1710000000000L,
            profile = null,
            exercises = listOf(
                ExerciseBackupDto(
                    id = 2,
                    displayName = "Press de banca",
                    normalizedName = "press de banca"
                )
            ),
            irisxConversations = listOf(
                IrisxConversationBackupDto(
                    id = 1,
                    title = "Conversación principal",
                    activeMuscleGroup = "Espalda",
                    activePeriodScope = "LAST_MONTH",
                    createdAtMillis = 1710000000000L,
                    updatedAtMillis = 1710000005000L
                )
            ),
            irisxMessages = listOf(
                IrisxMessageBackupDto(
                    id = 10,
                    conversationId = 1,
                    senderRole = "USER",
                    content = "¿Cómo progresé en espalda?",
                    referencedMuscleGroup = "Espalda",
                    createdAtMillis = 1710000000000L
                )
            ),
            routines = listOf(
                RoutineBackupDto(
                    id = 5,
                    name = "Empuje A",
                    description = "Pecho y hombros",
                    targetCategory = "Pecho",
                    createdAtMillis = 1710000000000L,
                    updatedAtMillis = 1710000000000L
                )
            ),
            routineExercises = listOf(
                RoutineExerciseBackupDto(
                    id = 50,
                    routineId = 5,
                    exerciseId = 2,
                    exerciseName = "Press de banca",
                    orderIndex = 1,
                    targetSets = 4,
                    minReps = 6,
                    maxReps = 8,
                    suggestedLoadGrams = 80000
                )
            )
        )

        val json = BackupSerializer.serialize(v2Payload)
        val validated = BackupSerializer.deserializeAndValidate(json)
        assertTrue(validated is BackupValidationResult.Success)
        val restored = (validated as BackupValidationResult.Success).payload

        assertEquals(2, restored.formatVersion)
        assertEquals(1, restored.irisxConversations.size)
        assertEquals("Espalda", restored.irisxConversations[0].activeMuscleGroup)
        assertEquals(1, restored.irisxMessages.size)
        assertEquals("¿Cómo progresé en espalda?", restored.irisxMessages[0].content)
        assertEquals(1, restored.routines.size)
        assertEquals("Empuje A", restored.routines[0].name)
        assertEquals(1, restored.routineExercises.size)
        assertEquals(80000, restored.routineExercises[0].suggestedLoadGrams)
    }

    @Test
    fun `deserializing older backup JSON without loadUnit and isToFailure applies safe defaults`() {
        val legacyJson = """
            {
              "formatVersion": 1,
              "appVersion": "0.6.0",
              "exportedAtMillis": 1715000000000,
              "profile": null,
              "weighIns": [],
              "exercises": [
                {
                  "id": 1,
                  "displayName": "Sentadilla",
                  "normalizedName": "sentadilla",
                  "muscleGroup": "Piernas",
                  "createdAtMillis": 1715000000000
                }
              ],
              "exerciseAliases": [],
              "workoutSessions": [
                {
                  "id": 10,
                  "title": "Piernas",
                  "startedAtMillis": 1715000000000,
                  "finishedAtMillis": 1715003600000,
                  "notes": ""
                }
              ],
              "workoutSets": [
                {
                  "id": 101,
                  "sessionId": 10,
                  "exerciseId": 1,
                  "orderInExercise": 1,
                  "executionOrder": 1,
                  "loadGrams": 80000,
                  "repetitions": 8,
                  "rir": 1,
                  "isAssisted": false,
                  "isDumbbell": false,
                  "note": ""
                }
              ],
              "irisxMemories": []
            }
        """.trimIndent()

        val result = BackupSerializer.deserializeAndValidate(legacyJson)
        assertTrue(result is BackupValidationResult.Success)
        val payload = (result as BackupValidationResult.Success).payload

        assertEquals(1, payload.workoutSets.size)
        val set = payload.workoutSets.first()
        assertEquals(80000, set.loadGrams)
        assertEquals(8, set.repetitions)
        assertEquals("KG", set.loadUnit)
        assertEquals(false, set.isToFailure)
    }

    @Test
    fun `roundtrip preserves multi-unit loads and failure sets accurately`() {
        val setDto = WorkoutSetBackupDto(
            id = 200,
            sessionId = 10,
            exerciseId = 1,
            orderInExercise = 1,
            executionOrder = 1,
            loadGrams = 13000,
            repetitions = 9,
            rir = 0,
            loadUnit = "BLOQUES",
            isToFailure = true,
            note = "[FALLO]"
        )

        val payload = SyntraBackupPayload(
            formatVersion = 2,
            appVersion = "0.7.0-alpha4",
            exportedAtMillis = 1716000000000L,
            profile = null,
            exercises = listOf(
                ExerciseBackupDto(
                    id = 1,
                    displayName = "Femoral sentado",
                    normalizedName = "femoral sentado"
                )
            ),
            workoutSessions = listOf(
                WorkoutSessionBackupDto(
                    id = 10,
                    title = "Piernas",
                    startedAtMillis = 1716000000000L
                )
            ),
            workoutSets = listOf(setDto)
        )

        val json = BackupSerializer.serialize(payload)
        val result = BackupSerializer.deserializeAndValidate(json)
        assertTrue(result is BackupValidationResult.Success)
        val restored = (result as BackupValidationResult.Success).payload

        assertEquals(1, restored.workoutSets.size)
        val restoredSet = restored.workoutSets.first()
        assertEquals("BLOQUES", restoredSet.loadUnit)
        assertEquals(true, restoredSet.isToFailure)
        assertEquals(13000, restoredSet.loadGrams)
        assertEquals(9, restoredSet.repetitions)
    }
}
