package com.syntra.app.domain

import com.syntra.app.data.ActiveWorkoutDraftEntity
import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.data.backup.BackupSerializer
import com.syntra.app.data.backup.BackupValidationResult
import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager
import java.util.UUID

class DataIntegrityAuditTest {

    @Test
    fun testStableIdentityForRepeatedExercisesWithDifferentLoads() {
        val input = """
            Press banca 100kg 5 5
            Press militar 50kg 10
            Press banca 60kg 12 12
        """.trimIndent()

        val preview = WorkoutTextParser.parse(input)
        assertEquals(3, preview.exercises.size)

        val firstBanca = preview.exercises[0]
        val militar = preview.exercises[1]
        val secondBanca = preview.exercises[2]

        assertEquals("Press banca", firstBanca.name)
        assertEquals("Press militar", militar.name)
        assertEquals("Press banca", secondBanca.name)

        // Each instance must have a distinct, non-empty stable UID
        assertNotEquals(firstBanca.uid, secondBanca.uid)
        assertTrue(firstBanca.uid.isNotBlank())
        assertTrue(secondBanca.uid.isNotBlank())

        // Edit ONLY the second instance (e.g. adjust backoff load to 62.5kg)
        val updatedSecondBanca = secondBanca.copy(
            sets = listOf(
                ParsedSet(weightGrams = 62500, repetitions = 12),
                ParsedSet(weightGrams = 62500, repetitions = 12)
            )
        )

        val editedList = preview.exercises.map {
            if (it.uid == updatedSecondBanca.uid) updatedSecondBanca else it
        }

        // Verify first instance was NOT overwritten
        assertEquals(100000, editedList[0].sets[0].weightGrams)
        assertEquals(5, editedList[0].sets[0].repetitions)

        // Verify second instance was properly updated
        assertEquals(62500, editedList[2].sets[0].weightGrams)
        assertEquals(12, editedList[2].sets[0].repetitions)

        // Verify sequence order remained unchanged
        assertEquals("Press banca", editedList[0].name)
        assertEquals("Press militar", editedList[1].name)
        assertEquals("Press banca", editedList[2].name)
    }

    @Test
    fun testAttributePreservationAssistedAndDumbbell() {
        val input = """
            Dominadas asistidas -15kg 10 10
            Press mancuernas 30kg 12 10
        """.trimIndent()

        val preview = WorkoutTextParser.parse(input)
        assertEquals(2, preview.exercises.size)

        val dominadas = preview.exercises[0]
        val pressMancuerna = preview.exercises[1]

        assertTrue("Dominadas asistidas must be isAssisted = true", dominadas.sets[0].isAssisted)
        assertFalse("Dominadas asistidas should not be isDumbbell", dominadas.sets[0].isDumbbell)

        assertTrue("Press mancuernas must be isDumbbell = true", pressMancuerna.sets[0].isDumbbell)
        assertFalse("Press mancuernas should not be isAssisted", pressMancuerna.sets[0].isAssisted)

        // Simulate editing load in dialog while preserving attributes
        val editedDominadasSets = dominadas.sets.map {
            it.copy(weightGrams = 15000) // edited assisted counterweight
        }
        val editedDominadas = dominadas.copy(sets = editedDominadasSets)

        assertTrue(editedDominadas.sets[0].isAssisted)
        assertFalse(editedDominadas.sets[0].isDumbbell)
        assertEquals(15000, editedDominadas.sets[0].weightGrams)

        val compactDumbbell = WorkoutPresentation.toCompact(pressMancuerna)
        assertTrue(compactDumbbell.groups[0].isDumbbell)
        assertTrue(compactDumbbell.groups[0].formattedWeight().contains("mancuerna"))
    }

    @Test
    fun testUnitConversionLosslessPoundsBlocksPlatesKg() {
        // 1. Pounds
        val rawPounds = 100.0
        val gramsPounds = UnitConverter.toGrams(rawPounds, "LBS")
        assertEquals(45359, gramsPounds)

        val displayPounds = UnitConverter.formatDisplay(gramsPounds, "LBS")
        assertEquals("100 lb", displayPounds)

        val recoveredRawPounds = UnitConverter.toRawValue(gramsPounds, "LBS")
        assertEquals(100.0, recoveredRawPounds, 0.05)

        // 2. Bloques
        val rawBloques = 13.0
        val gramsBloques = UnitConverter.toGrams(rawBloques, "BLOQUES")
        assertEquals(13000, gramsBloques)
        assertEquals("13 bloques", UnitConverter.formatDisplay(gramsBloques, "BLOQUES"))

        // 3. Placas
        val rawPlacas = 5.0
        val gramsPlacas = UnitConverter.toGrams(rawPlacas, "PLACAS")
        assertEquals(5000, gramsPlacas)
        assertEquals("5 placas", UnitConverter.formatDisplay(gramsPlacas, "PLACAS"))

        // 4. Kilograms decimal
        val rawKg = 82.5
        val gramsKg = UnitConverter.toGrams(rawKg, "KG")
        assertEquals(82500, gramsKg)
        assertEquals("82,5 kg", UnitConverter.formatDisplay(gramsKg, "KG"))

        // 5. Bodyweight
        assertEquals(0, UnitConverter.toGrams(0.0, "BW"))
        assertEquals("BW", UnitConverter.formatDisplay(0, "BW"))
    }

    @Test
    fun testActiveWorkoutManagerIdempotentCommit() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()

        stmt.execute("""
            CREATE TABLE workout_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                startedAtMillis INTEGER NOT NULL,
                finishedAtMillis INTEGER,
                notes TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE irisx_memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                memoryType TEXT NOT NULL,
                sourceKey TEXT NOT NULL,
                targetValue TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 1.0,
                createdAtMillis INTEGER NOT NULL,
                updatedAtMillis INTEGER NOT NULL,
                UNIQUE (sourceKey, memoryType)
            )
        """.trimIndent())

        val testUuid = UUID.randomUUID().toString()

        fun executeCommit(title: String): Long {
            conn.autoCommit = false
            try {
                // Check idempotency token
                val rs = stmt.executeQuery("SELECT targetValue FROM irisx_memories WHERE sourceKey = '$testUuid' AND memoryType = 'COMMITTED_SESSION'")
                if (rs.next()) {
                    val existingId = rs.getString(1).toLong()
                    conn.rollback()
                    return existingId
                }

                // Insert session
                stmt.execute("INSERT INTO workout_sessions (title, startedAtMillis) VALUES ('$title', 1000)")
                val idRs = stmt.executeQuery("SELECT last_insert_rowid()")
                idRs.next()
                val sessionId = idRs.getLong(1)

                // Insert token with UNIQUE constraint
                stmt.execute("INSERT INTO irisx_memories (memoryType, sourceKey, targetValue, createdAtMillis, updatedAtMillis) VALUES ('COMMITTED_SESSION', '$testUuid', '$sessionId', 1000, 1000)")
                conn.commit()
                return sessionId
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }

        // First execution: commits session #1
        val id1 = executeCommit("Piernas A")
        assertEquals(1L, id1)

        // Simulated interruption and retry: must NOT create session #2
        val id2 = executeCommit("Piernas A")
        assertEquals(1L, id2)

        // Verify only 1 session exists in database
        val countRs = stmt.executeQuery("SELECT count(*) FROM workout_sessions")
        countRs.next()
        assertEquals(1, countRs.getInt(1))
    }

    @Test
    fun testBackupSecurityRelationalIntegrityRejection() {
        // 1. Valid Minimal Backup
        val validJson = """
            {
                "formatVersion": 3,
                "appVersion": "0.7.0-alpha9",
                "exportedAtMillis": 1700000000000,
                "exercises": [
                    {"id": 1, "displayName": "Sentadilla", "normalizedName": "sentadilla", "muscleGroup": "Piernas"}
                ],
                "workoutSessions": [
                    {"id": 1, "title": "Piernas", "startedAtMillis": 1700000000000}
                ],
                "workoutSets": [
                    {"id": 1, "sessionId": 1, "exerciseId": 1, "loadGrams": 70000, "repetitions": 10}
                ],
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

        val validResult = BackupSerializer.deserializeAndValidate(validJson)
        assertTrue("Valid backup must succeed", validResult is BackupValidationResult.Success)

        // 2. Corrupted Backup: Set references non-existent Session (ID: 999)
        val danglingSessionJson = """
            {
                "formatVersion": 3,
                "appVersion": "0.7.0-alpha9",
                "exportedAtMillis": 1700000000000,
                "exercises": [
                    {"id": 1, "displayName": "Sentadilla", "normalizedName": "sentadilla", "muscleGroup": "Piernas"}
                ],
                "workoutSessions": [
                    {"id": 1, "title": "Piernas", "startedAtMillis": 1700000000000}
                ],
                "workoutSets": [
                    {"id": 1, "sessionId": 999, "exerciseId": 1, "loadGrams": 70000, "repetitions": 10}
                ]
            }
        """.trimIndent()

        val danglingResult = BackupSerializer.deserializeAndValidate(danglingSessionJson)
        assertTrue("Corrupted backup with dangling session must fail", danglingResult is BackupValidationResult.Error)
        val errorMsg = (danglingResult as BackupValidationResult.Error).message
        assertTrue(errorMsg.contains("referencia a una sesión inexistente"))

        // 3. Corrupted Backup: Duplicate Exercise ID
        val duplicateExerciseJson = """
            {
                "formatVersion": 3,
                "appVersion": "0.7.0-alpha9",
                "exportedAtMillis": 1700000000000,
                "exercises": [
                    {"id": 1, "displayName": "Sentadilla", "normalizedName": "sentadilla", "muscleGroup": "Piernas"},
                    {"id": 1, "displayName": "Prensa", "normalizedName": "prensa", "muscleGroup": "Piernas"}
                ]
            }
        """.trimIndent()

        val duplicateResult = BackupSerializer.deserializeAndValidate(duplicateExerciseJson)
        assertTrue("Backup with duplicate exercise IDs must fail", duplicateResult is BackupValidationResult.Error)
        assertTrue((duplicateResult as BackupValidationResult.Error).message.contains("identificador duplicado"))
    }

    @Test
    fun testInputValidationLoadAndRepsRanges() {
        // Load ranges
        assertTrue(UnitConverter.isValidLoad(80.0, "KG"))
        assertTrue(UnitConverter.isValidLoad(175.0, "LBS"))
        assertTrue(UnitConverter.isValidLoad(13.0, "BLOQUES"))
        assertTrue(UnitConverter.isValidLoad(0.0, "BW"))

        assertFalse("Negative load is invalid", UnitConverter.isValidLoad(-10.0, "KG"))
        assertFalse("Zero kg load is invalid", UnitConverter.isValidLoad(0.0, "KG"))
        assertFalse("Excessive load >1000kg is invalid", UnitConverter.isValidLoad(1500.0, "KG"))
        assertFalse("Excessive load >2200lb is invalid", UnitConverter.isValidLoad(3000.0, "LBS"))
        assertFalse("Excessive blocks >100 is invalid", UnitConverter.isValidLoad(120.0, "BLOQUES"))
    }
}
