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
                    note = "RPE 8"
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

        assertEquals(1, restored.irisxMemories.size)
        assertEquals("rdl", restored.irisxMemories[0].sourceKey)
        assertEquals("Peso muerto rumano", restored.irisxMemories[0].targetValue)
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
}
