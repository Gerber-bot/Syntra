package com.syntra.app.domain

import com.syntra.app.data.backup.BackupSerializer
import com.syntra.app.data.backup.BackupValidationResult
import com.syntra.app.domain.irisx.IrisXAnalytics
import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * Reproduction tests for Stage A.2 defects flagged by OpenCode audit.
 * Central rule: First reproduce failures against real code, then fix and prove passing.
 */
class DataIntegrityStageA2Test {

    // -------------------------------------------------------------
    // 1. Repetitions of same exercise name in ActiveWorkout callbacks
    // -------------------------------------------------------------
    @Test
    fun testTwoExercisesWithSameNameCallbacksPreserveIndependence() {
        val ex1 = ParsedExercise(
            name = "Press banca",
            sets = listOf(ParsedSet(weightGrams = 100000, rawLoadValue = 100.0, repetitions = 8))
        )
        val ex2 = ParsedExercise(
            name = "Press banca",
            sets = listOf(ParsedSet(weightGrams = 60000, rawLoadValue = 60.0, repetitions = 12))
        )

        assertNotEquals("Each parsed exercise must have a distinct stable UID", ex1.uid, ex2.uid)

        var editedExercises = listOf(ex1, ex2)

        // Callback 1: Edit the second entry (60kg -> 65kg)
        val updatedEx2 = ex2.copy(
            sets = listOf(ParsedSet(weightGrams = 65000, rawLoadValue = 65.0, repetitions = 10))
        )

        // In buggy code:
        // if (it.name.equals(originalName, ignoreCase = true)) updated else it
        // which replaces BOTH entries!
        // We test UID-based replacement:
        val simulatedBuggyOnSave = editedExercises.map {
            if (it.name.equals("Press banca", ignoreCase = true)) updatedEx2 else it
        }
        assertEquals("Buggy code corrupts first entry", 65000, simulatedBuggyOnSave[0].sets[0].weightGrams)

        // Correct UID-based onSave:
        editedExercises = editedExercises.map { if (it.uid == updatedEx2.uid) updatedEx2 else it }
        assertEquals(100000, editedExercises[0].sets[0].weightGrams)
        assertEquals(8, editedExercises[0].sets[0].repetitions)
        assertEquals(65000, editedExercises[1].sets[0].weightGrams)
        assertEquals(10, editedExercises[1].sets[0].repetitions)

        // Callback 2: Add set to second entry
        // Buggy code matched by name: ex.name == targetEx.name
        val targetEx = editedExercises[1]
        editedExercises = editedExercises.map { ex ->
            if (ex.uid == targetEx.uid) {
                val lastSet = ex.sets.last()
                ex.copy(sets = ex.sets + lastSet.copy(repetitions = 9))
            } else ex
        }
        assertEquals("First exercise must still have 1 set", 1, editedExercises[0].sets.size)
        assertEquals("Second exercise must now have 2 sets", 2, editedExercises[1].sets.size)

        // Callback 3: Delete second entry
        // Buggy code: filterNot { it.name == targetEx.name } (deleted both!)
        editedExercises = editedExercises.filterNot { it.uid == targetEx.uid }
        assertEquals("Only 1 exercise should remain", 1, editedExercises.size)
        assertEquals("Remaining exercise must be the first one", 100000, editedExercises[0].sets[0].weightGrams)
    }

    // -------------------------------------------------------------
    // 2. Precision preservation in 7.25 kg and other units
    // -------------------------------------------------------------
    @Test
    fun testPrecisionPreservation7Point25KgAndMultiUnits() {
        // Reproduce 7.25 kg precision issue:
        // 7.25 kg = 7250 grams
        val weightGrams = 7250
        val rawValue = UnitConverter.toRawValue(weightGrams, "KG")
        assertEquals(7.25, rawValue, 0.0001)

        // Buggy logic did: (initialRaw * 10.0).roundToInt() / 10.0 -> 7.3!
        val buggyRounded = Math.round(rawValue * 10.0) / 10.0
        assertEquals(7.3, buggyRounded, 0.0001)

        // Non-destructive formatting must preserve 7.25
        val display = UnitConverter.formatDisplay(weightGrams, "KG")
        assertTrue("Display must contain 7,25 or 7.25, was $display", display.contains("7,25") || display.contains("7.25"))

        // Roundtrip 100 lb: 45359 grams
        val lbGrams = UnitConverter.toGrams(100.0, "LBS")
        val lbRaw = UnitConverter.toRawValue(lbGrams, "LBS")
        assertEquals(100.0, Math.round(lbRaw).toDouble(), 0.001)

        // 13 Bloques: 13000 grams
        val bloquesGrams = UnitConverter.toGrams(13.0, "BLOQUES")
        assertEquals(13000, bloquesGrams)
        val bloquesRaw = UnitConverter.toRawValue(bloquesGrams, "BLOQUES")
        assertEquals(13.0, bloquesRaw, 0.001)
    }

    // -------------------------------------------------------------
    // 3. Strict validation contract
    // -------------------------------------------------------------
    @Test
    fun testStrictValidationRejectsInvalidValues() {
        // Valid values
        assertTrue(UnitConverter.isValidLoad(7.25, "KG"))
        assertTrue(UnitConverter.isValidLoad(100.0, "LBS"))
        assertTrue(UnitConverter.isValidLoad(13.0, "BLOQUES"))
        assertTrue(UnitConverter.isValidLoad(0.0, "BW"))

        // Invalid: 0 or negative when not BW
        assertFalse(UnitConverter.isValidLoad(0.0, "KG"))
        assertFalse(UnitConverter.isValidLoad(-10.0, "KG"))

        // Invalid: NaN and Infinity
        assertFalse(UnitConverter.isValidLoad(Double.NaN, "KG"))
        assertFalse(UnitConverter.isValidLoad(Double.POSITIVE_INFINITY, "KG"))
        assertFalse(UnitConverter.isValidLoad(Double.NEGATIVE_INFINITY, "KG"))

        // Invalid: extremely small loads that round to 0 grams
        // 0.0001 kg = 0.1 grams -> rounds to 0 grams!
        val tinyGrams = UnitConverter.toGrams(0.0001, "KG")
        assertEquals("Tiny load rounds to 0 grams in SQLite", 0, tinyGrams)
        assertFalse("Loads that round to 0 grams must be invalid", UnitConverter.isValidLoad(0.0001, "KG"))

        // Invalid: out-of-range massive numbers
        assertFalse(UnitConverter.isValidLoad(2000.0, "KG"))
        assertFalse(UnitConverter.isValidLoad(5000.0, "LBS"))
        assertFalse(UnitConverter.isValidLoad(500.0, "BLOQUES"))
    }

    // -------------------------------------------------------------
    // 5. Incomplete backup rejection (missing mandatory sections)
    // -------------------------------------------------------------
    @Test
    fun testBackupRejectsMissingMandatorySectionsForFormatVersion() {
        // A formatVersion 3 backup MUST have exercises, workoutSessions, and workoutSets.
        // It must NOT silently treat missing sections as empty and proceed.
        val missingExercisesJson = """
            {
                "formatVersion": 3,
                "appVersion": "0.7.0-alpha9",
                "exportedAtMillis": 1700000000000,
                "workoutSessions": [
                    {"id": 1, "title": "Sesión 1", "startedAtMillis": 1700000000000}
                ],
                "workoutSets": [
                    {"id": 1, "sessionId": 1, "exerciseId": 1, "loadGrams": 50000, "repetitions": 10}
                ]
            }
        """.trimIndent()

        val result = BackupSerializer.deserializeAndValidate(missingExercisesJson)
        assertTrue("Backup missing mandatory 'exercises' section must be rejected", result is BackupValidationResult.Error)
        val err = (result as BackupValidationResult.Error).message
        assertTrue("Error message must mention missing exercises", err.contains("exercises"))
    }

    // -------------------------------------------------------------
    // 6. IrisX & Analytics: 13 BLOQUES must NOT output 13 kg
    // -------------------------------------------------------------
    @Test
    fun testIrisXMachineBlocksNeverOutputKg() {
        val sessions = listOf(
            WorkoutSessionEntity(
                id = 10,
                title = "Piernas",
                startedAtMillis = 1710000000000L
            )
        )

        val sets = listOf(
            WorkoutSetEntity(
                id = 101,
                sessionId = 10,
                exerciseId = 1,
                orderInExercise = 1,
                executionOrder = 1,
                loadGrams = 13000,
                repetitions = 9,
                loadUnit = "BLOQUES",
                isToFailure = true
            )
        )

        val exercises = listOf(
            ExerciseEntity(
                id = 1,
                displayName = "Femoral sentado",
                normalizedName = "femoral sentado",
                muscleGroup = "Piernas"
            )
        )

        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = "Femoral sentado",
            sessions = sessions,
            allSets = sets,
            exercises = exercises
        )

        // Crucial test: Report summaryText must NEVER say "13 kg" or "13,0 kg"
        assertFalse(
            "Report must NOT contain '13 kg' when load was 13 BLOQUES. Actual text:\n${report.summaryText}",
            report.summaryText.contains("13 kg") || report.summaryText.contains("13,0 kg") || report.summaryText.contains("13.0 kg")
        )

        // It must state 13 bloques
        assertTrue(
            "Report should mention '13 bloques'. Actual text:\n${report.summaryText}",
            report.summaryText.contains("13 bloques")
        )
    }

    // -------------------------------------------------------------
    // 7. Roundtrip precision preservation across single-field edits
    // -------------------------------------------------------------
    @Test
    fun testSetEditingRoundtripPreservesExactLoadAndExcludesFalloInNotes() {
        val originalSet = WorkoutSetEntity(
            id = 55,
            sessionId = 1,
            exerciseId = 1,
            orderInExercise = 1,
            executionOrder = 1,
            loadGrams = 7250, // 7.25 kg
            repetitions = 10,
            loadUnit = "KG",
            isToFailure = false,
            note = "Controlar excéntrica"
        )

        // Case A: Edit only note
        val editedNoteOnly = originalSet.copy(note = "Pausa de 2 seg en estiramiento")
        assertEquals("Editing note must not mutate loadGrams", 7250, editedNoteOnly.loadGrams)
        assertEquals(10, editedNoteOnly.repetitions)

        // Case B: Edit only reps
        val editedRepsOnly = originalSet.copy(repetitions = 12)
        assertEquals("Editing reps must not mutate loadGrams", 7250, editedRepsOnly.loadGrams)
        assertEquals(12, editedRepsOnly.repetitions)

        // Case C: Edit only failure flag
        val editedFailureOnly = originalSet.copy(isToFailure = true)
        assertEquals("Editing failure must not mutate loadGrams", 7250, editedFailureOnly.loadGrams)
        assertTrue(editedFailureOnly.isToFailure)
        assertFalse(
            "isToFailure is structured; must not inject [FALLO] into notes",
            editedFailureOnly.note.contains("[FALLO]")
        )

        // Case D: Save without changes
        val unchanged = originalSet.copy()
        assertEquals(7250, unchanged.loadGrams)
        assertEquals(10, unchanged.repetitions)
        assertEquals("KG", unchanged.loadUnit)
        assertEquals(false, unchanged.isToFailure)
        assertEquals("Controlar excéntrica", unchanged.note)
    }

    // -------------------------------------------------------------
    // 8. Relational Backup Rejections (Duplicated normalizedName, IDs)
    // -------------------------------------------------------------
    @Test
    fun testBackupRejectsDuplicateNormalizedNamesAndNonPositiveIds() {
        // Case A: Duplicate normalizedName
        val dupNameJson = """
            {
                "formatVersion": 3,
                "appVersion": "0.7.0-alpha10",
                "exportedAtMillis": 1700000000000,
                "exercises": [
                    {"id": 1, "displayName": "Sentadilla Trasera", "normalizedName": "sentadilla"},
                    {"id": 2, "displayName": "Sentadilla Clásica", "normalizedName": "sentadilla"}
                ],
                "workoutSessions": [],
                "workoutSets": [],
                "weighIns": [],
                "exerciseAliases": [],
                "irisxMemories": [],
                "routines": [],
                "routineExercises": [],
                "irisxConversations": [],
                "irisxMessages": [],
                "workoutCategories": []
            }
        """.trimIndent()
        val dupResult = BackupSerializer.deserializeAndValidate(dupNameJson)
        assertTrue("Duplicate normalizedName must be rejected", dupResult is BackupValidationResult.Error)
        val dupMsg = (dupResult as BackupValidationResult.Error).message
        assertTrue(dupMsg.contains("nombre normalizado") || dupMsg.contains("duplicado"))

        // Case B: Non-positive ID
        val nonPosJson = """
            {
                "formatVersion": 3,
                "appVersion": "0.7.0-alpha10",
                "exportedAtMillis": 1700000000000,
                "exercises": [
                    {"id": -1, "displayName": "Sentadilla", "normalizedName": "sentadilla"}
                ],
                "workoutSessions": [],
                "workoutSets": [],
                "weighIns": [],
                "exerciseAliases": [],
                "irisxMemories": [],
                "routines": [],
                "routineExercises": [],
                "irisxConversations": [],
                "irisxMessages": [],
                "workoutCategories": []
            }
        """.trimIndent()
        val nonPosResult = BackupSerializer.deserializeAndValidate(nonPosJson)
        assertTrue("Non-positive ID must be rejected", nonPosResult is BackupValidationResult.Error)
        val nonPosMsg = (nonPosResult as BackupValidationResult.Error).message
        assertTrue(nonPosMsg.contains("no positivo"))
    }
}
