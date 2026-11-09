package com.syntra.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class MigrationTest {

    @Test
    fun `migration from version 1 to 2 preserves profile and weigh ins non-destructively`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = connection.createStatement()

        // 1. Create Schema Version 1 (extracted from 1.json)
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `profiles` (
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `preferredName` TEXT NOT NULL,
                `heightCm` INTEGER NOT NULL,
                `initialWeightGrams` INTEGER NOT NULL,
                `activityLevel` TEXT NOT NULL,
                `goal` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `weigh_ins` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `measuredAtMillis` INTEGER NOT NULL,
                `weightGrams` INTEGER NOT NULL,
                `note` TEXT NOT NULL
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `exercises` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `displayName` TEXT NOT NULL,
                `normalizedName` TEXT NOT NULL,
                `muscleGroup` TEXT NOT NULL,
                `equipment` TEXT,
                `createdAtMillis` INTEGER NOT NULL
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `exercise_aliases` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `exerciseId` INTEGER NOT NULL,
                `normalizedAlias` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `workout_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `startedAtMillis` INTEGER NOT NULL,
                `finishedAtMillis` INTEGER
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `workout_sets` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `exerciseId` INTEGER NOT NULL,
                `orderInExercise` INTEGER NOT NULL,
                `loadGrams` INTEGER NOT NULL,
                `repetitions` INTEGER NOT NULL,
                `rir` INTEGER
            )
        """.trimIndent())

        // 2. Insert existing user data from Sprint 01
        stmt.execute("""
            INSERT INTO profiles (id, name, preferredName, heightCm, initialWeightGrams, activityLevel, goal, createdAtMillis, updatedAtMillis)
            VALUES (1, 'Federico', 'Fede', 178, 76200, 'moderada', 'fuerza', 1690000000000, 1690000000000)
        """.trimIndent())

        stmt.execute("""
            INSERT INTO weigh_ins (id, measuredAtMillis, weightGrams, note)
            VALUES (1, 1690000000000, 76200, 'Inicio'),
                   (2, 1690500000000, 75800, 'Seguimiento')
        """.trimIndent())

        // 3. Execute Migration 1 to 2 SQL statements
        stmt.execute("ALTER TABLE exercises ADD COLUMN secondaryMuscleGroups TEXT NOT NULL DEFAULT ''")
        stmt.execute("ALTER TABLE exercises ADD COLUMN exerciseType TEXT NOT NULL DEFAULT 'strength'")
        stmt.execute("ALTER TABLE exercises ADD COLUMN status TEXT NOT NULL DEFAULT 'CONFIRMED'")

        stmt.execute("ALTER TABLE workout_sessions ADD COLUMN notes TEXT NOT NULL DEFAULT ''")

        stmt.execute("ALTER TABLE workout_sets ADD COLUMN executionOrder INTEGER NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE workout_sets ADD COLUMN isAssisted INTEGER NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE workout_sets ADD COLUMN isDumbbell INTEGER NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE workout_sets ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        stmt.execute("ALTER TABLE workout_sets ADD COLUMN plannedTarget TEXT")

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS irisx_memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                memoryType TEXT NOT NULL,
                sourceKey TEXT NOT NULL,
                targetValue TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 1.0,
                createdAtMillis INTEGER NOT NULL,
                updatedAtMillis INTEGER NOT NULL
            )
        """.trimIndent())
        stmt.execute("CREATE INDEX IF NOT EXISTS index_irisx_memories_memoryType ON irisx_memories (memoryType)")
        stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS index_irisx_memories_sourceKey_memoryType ON irisx_memories (sourceKey, memoryType)")

        // 4. Verify existing profile is 100% preserved
        val profileRs = stmt.executeQuery("SELECT name, preferredName, heightCm, initialWeightGrams FROM profiles WHERE id = 1")
        assertTrue(profileRs.next())
        assertEquals("Federico", profileRs.getString("name"))
        assertEquals("Fede", profileRs.getString("preferredName"))
        assertEquals(178, profileRs.getInt("heightCm"))
        assertEquals(76200, profileRs.getInt("initialWeightGrams"))
        profileRs.close()

        // 5. Verify existing weigh-ins are 100% preserved
        val weighInsRs = stmt.executeQuery("SELECT COUNT(*) AS total, MAX(weightGrams) AS maxW, MIN(weightGrams) AS minW FROM weigh_ins")
        assertTrue(weighInsRs.next())
        assertEquals(2, weighInsRs.getInt("total"))
        assertEquals(76200, weighInsRs.getInt("maxW"))
        assertEquals(75800, weighInsRs.getInt("minW"))
        weighInsRs.close()

        // 6. Verify new table irisx_memories functions properly
        stmt.execute("""
            INSERT INTO irisx_memories (memoryType, sourceKey, targetValue, confidence, createdAtMillis, updatedAtMillis)
            VALUES ('ALIAS', 'rdl', 'Peso muerto rumano', 1.0, 1690000000000, 1690000000000)
        """.trimIndent())
        val memoryRs = stmt.executeQuery("SELECT targetValue FROM irisx_memories WHERE sourceKey = 'rdl' AND memoryType = 'ALIAS'")
        assertTrue(memoryRs.next())
        assertEquals("Peso muerto rumano", memoryRs.getString("targetValue"))
        memoryRs.close()

        stmt.close()
        connection.close()
    }

    @Test
    fun `migration with populated workouts and exercises preserves all data and applies defaults`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = connection.createStatement()

        // 1. Create Schema Version 1
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `exercises` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `displayName` TEXT NOT NULL,
                `normalizedName` TEXT NOT NULL,
                `muscleGroup` TEXT NOT NULL,
                `equipment` TEXT,
                `createdAtMillis` INTEGER NOT NULL
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `workout_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `startedAtMillis` INTEGER NOT NULL,
                `finishedAtMillis` INTEGER
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `workout_sets` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `exerciseId` INTEGER NOT NULL,
                `orderInExercise` INTEGER NOT NULL,
                `loadGrams` INTEGER NOT NULL,
                `repetitions` INTEGER NOT NULL,
                `rir` INTEGER
            )
        """.trimIndent())

        // Insert v1 data
        stmt.execute("""
            INSERT INTO exercises (id, displayName, normalizedName, muscleGroup, equipment, createdAtMillis)
            VALUES (10, 'Sentadilla', 'sentadilla', 'Piernas', 'Barra', 1690000000000)
        """.trimIndent())

        stmt.execute("""
            INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis)
            VALUES (20, 'Pierna pesada', 1690000000000, 1690003600000)
        """.trimIndent())

        stmt.execute("""
            INSERT INTO workout_sets (id, sessionId, exerciseId, orderInExercise, loadGrams, repetitions, rir)
            VALUES (100, 20, 10, 1, 100000, 5, 2)
        """.trimIndent())

        // Run Migration
        stmt.execute("ALTER TABLE exercises ADD COLUMN secondaryMuscleGroups TEXT NOT NULL DEFAULT ''")
        stmt.execute("ALTER TABLE exercises ADD COLUMN exerciseType TEXT NOT NULL DEFAULT 'strength'")
        stmt.execute("ALTER TABLE exercises ADD COLUMN status TEXT NOT NULL DEFAULT 'CONFIRMED'")

        stmt.execute("ALTER TABLE workout_sessions ADD COLUMN notes TEXT NOT NULL DEFAULT ''")

        stmt.execute("ALTER TABLE workout_sets ADD COLUMN executionOrder INTEGER NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE workout_sets ADD COLUMN isAssisted INTEGER NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE workout_sets ADD COLUMN isDumbbell INTEGER NOT NULL DEFAULT 0")
        stmt.execute("ALTER TABLE workout_sets ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        stmt.execute("ALTER TABLE workout_sets ADD COLUMN plannedTarget TEXT")

        // Verify exercise preserved and new columns have defaults
        val exRs = stmt.executeQuery("SELECT displayName, secondaryMuscleGroups, exerciseType, status FROM exercises WHERE id = 10")
        assertTrue(exRs.next())
        assertEquals("Sentadilla", exRs.getString("displayName"))
        assertEquals("", exRs.getString("secondaryMuscleGroups"))
        assertEquals("strength", exRs.getString("exerciseType"))
        assertEquals("CONFIRMED", exRs.getString("status"))
        exRs.close()

        // Verify session preserved and notes default to empty
        val sessRs = stmt.executeQuery("SELECT title, notes FROM workout_sessions WHERE id = 20")
        assertTrue(sessRs.next())
        assertEquals("Pierna pesada", sessRs.getString("title"))
        assertEquals("", sessRs.getString("notes"))
        sessRs.close()

        // Verify set preserved and new fields have defaults
        val setRs = stmt.executeQuery("SELECT loadGrams, repetitions, executionOrder, isAssisted, isDumbbell, note, plannedTarget FROM workout_sets WHERE id = 100")
        assertTrue(setRs.next())
        assertEquals(100000, setRs.getInt("loadGrams"))
        assertEquals(5, setRs.getInt("repetitions"))
        assertEquals(0, setRs.getInt("executionOrder"))
        assertEquals(0, setRs.getInt("isAssisted"))
        assertEquals(0, setRs.getInt("isDumbbell"))
        assertEquals("", setRs.getString("note"))
        assertEquals(null, setRs.getString("plannedTarget"))
        setRs.close()

        stmt.close()
        connection.close()
    }

    @Test
    fun `migration from version 2 to 3 adds conversations and routines without touching existing records`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = connection.createStatement()

        // Create v2 tables and populate
        stmt.execute("""
            CREATE TABLE workout_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                startedAtMillis INTEGER NOT NULL,
                finishedAtMillis INTEGER,
                notes TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        stmt.execute("""
            INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis, notes)
            VALUES (5, 'Espalda v2', 1700000000000, 1700003600000, 'Intacta')
        """.trimIndent())

        // Execute Migration 2 -> 3 SQL
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS irisx_conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                activeMuscleGroup TEXT,
                activeExerciseName TEXT,
                activeSessionId INTEGER,
                activePeriodScope TEXT,
                createdAtMillis INTEGER NOT NULL,
                updatedAtMillis INTEGER NOT NULL
            )
        """.trimIndent())
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS irisx_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                conversationId INTEGER NOT NULL,
                senderRole TEXT NOT NULL,
                content TEXT NOT NULL,
                intentType TEXT,
                referencedExercise TEXT,
                referencedMuscleGroup TEXT,
                referencedSessionId INTEGER,
                createdAtMillis INTEGER NOT NULL
            )
        """.trimIndent())
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS workout_routines (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                targetCategory TEXT NOT NULL DEFAULT 'General',
                createdAtMillis INTEGER NOT NULL,
                updatedAtMillis INTEGER NOT NULL
            )
        """.trimIndent())
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS routine_exercises (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                routineId INTEGER NOT NULL,
                exerciseId INTEGER,
                exerciseName TEXT NOT NULL,
                orderIndex INTEGER NOT NULL,
                targetSets INTEGER NOT NULL,
                minReps INTEGER NOT NULL,
                maxReps INTEGER NOT NULL,
                suggestedLoadGrams INTEGER,
                isDumbbell INTEGER NOT NULL DEFAULT 0,
                isAssisted INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())

        // Verify v2 workout session is untouched
        val rs = stmt.executeQuery("SELECT title, notes FROM workout_sessions WHERE id = 5")
        assertTrue(rs.next())
        assertEquals("Espalda v2", rs.getString("title"))
        assertEquals("Intacta", rs.getString("notes"))
        rs.close()

        // Verify v3 routine & conversation tables accept inserts
        stmt.execute("INSERT INTO workout_routines (id, name, createdAtMillis, updatedAtMillis) VALUES (1, 'Tirón A', 1700000000000, 1700000000000)")
        val rRs = stmt.executeQuery("SELECT name FROM workout_routines WHERE id = 1")
        assertTrue(rRs.next())
        assertEquals("Tirón A", rRs.getString("name"))
        rRs.close()

        stmt.close()
        connection.close()
    }

    @Test
    fun `direct migration chain from Syntra v0_2_1 Room v2 to v0_5_0 Room v4 preserves profiles, weigh-ins, exercises, sessions, sets, aliases, and memories`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = connection.createStatement()

        // 1. Create exact Room v2 schema (as installed in Syntra v0.2.1 on the phone)
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `profiles` (
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `preferredName` TEXT NOT NULL,
                `heightCm` INTEGER NOT NULL,
                `initialWeightGrams` INTEGER NOT NULL,
                `activityLevel` TEXT NOT NULL,
                `goal` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `weigh_ins` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `measuredAtMillis` INTEGER NOT NULL,
                `weightGrams` INTEGER NOT NULL,
                `note` TEXT NOT NULL
            )
        """.trimIndent())
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `exercises` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `displayName` TEXT NOT NULL,
                `normalizedName` TEXT NOT NULL,
                `muscleGroup` TEXT NOT NULL,
                `secondaryMuscleGroups` TEXT NOT NULL DEFAULT '',
                `equipment` TEXT,
                `exerciseType` TEXT NOT NULL DEFAULT 'strength',
                `status` TEXT NOT NULL DEFAULT 'CONFIRMED',
                `createdAtMillis` INTEGER NOT NULL
            )
        """.trimIndent())
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `exercise_aliases` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `exerciseId` INTEGER NOT NULL,
                `normalizedAlias` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL
            )
        """.trimIndent())
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `workout_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `startedAtMillis` INTEGER NOT NULL,
                `finishedAtMillis` INTEGER,
                `notes` TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `workout_sets` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `exerciseId` INTEGER NOT NULL,
                `orderInExercise` INTEGER NOT NULL,
                `executionOrder` INTEGER NOT NULL DEFAULT 0,
                `loadGrams` INTEGER NOT NULL,
                `repetitions` INTEGER NOT NULL,
                `rir` INTEGER,
                `isAssisted` INTEGER NOT NULL DEFAULT 0,
                `isDumbbell` INTEGER NOT NULL DEFAULT 0,
                `note` TEXT NOT NULL DEFAULT '',
                `plannedTarget` TEXT
            )
        """.trimIndent())
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS `irisx_memories` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `memoryType` TEXT NOT NULL,
                `sourceKey` TEXT NOT NULL,
                `targetValue` TEXT NOT NULL,
                `confidence` REAL NOT NULL DEFAULT 1.0,
                `createdAtMillis` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL
            )
        """.trimIndent())
        stmt.execute("CREATE INDEX IF NOT EXISTS index_irisx_memories_memoryType ON irisx_memories (memoryType)")
        stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS index_irisx_memories_sourceKey_memoryType ON irisx_memories (sourceKey, memoryType)")

        // 2. Populate v0.2.1 user data across all 7 tables
        stmt.execute("""
            INSERT INTO profiles VALUES (1, 'Federico', 'Fede', 178, 76000, 'Moderado', 'Mejorar fuerza', 1700000000000, 1700000000000)
        """.trimIndent())
        stmt.execute("""
            INSERT INTO weigh_ins (id, measuredAtMillis, weightGrams, note)
            VALUES (1, 1700000000000, 76000, 'Inicial'), (2, 1700500000000, 75400, 'Semana 2')
        """.trimIndent())
        stmt.execute("""
            INSERT INTO exercises (id, displayName, normalizedName, muscleGroup, secondaryMuscleGroups, equipment, exerciseType, status, createdAtMillis)
            VALUES (1, 'Press de banca', 'press de banca', 'Pecho', 'Tríceps, Hombro anterior', 'Barra', 'strength', 'CONFIRMED', 1700000000000)
        """.trimIndent())
        stmt.execute("""
            INSERT INTO exercise_aliases (id, exerciseId, normalizedAlias, createdAtMillis)
            VALUES (1, 1, 'banca plana', 1700000000000)
        """.trimIndent())
        stmt.execute("""
            INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis, notes)
            VALUES (10, 'Pecho v0.2.1', 1700600000000, 1700603600000, 'Sesión en Redmi Note 13')
        """.trimIndent())
        stmt.execute("""
            INSERT INTO workout_sets (id, sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions, rir, isAssisted, isDumbbell, note, plannedTarget)
            VALUES (100, 10, 1, 1, 1, 75000, 8, 1, 0, 0, '', '3 series de 8–10 repeticiones')
        """.trimIndent())
        stmt.execute("""
            INSERT INTO irisx_memories (id, memoryType, sourceKey, targetValue, confidence, createdAtMillis, updatedAtMillis)
            VALUES (1, 'ALIAS', 'banca plana', 'Press de banca', 1.0, 1700000000000, 1700000000000)
        """.trimIndent())

        // 3. Apply MIGRATION_2_3
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS irisx_conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                activeMuscleGroup TEXT,
                activeExerciseName TEXT,
                activeSessionId INTEGER,
                activePeriodScope TEXT,
                createdAtMillis INTEGER NOT NULL,
                updatedAtMillis INTEGER NOT NULL
            )
        """.trimIndent())
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS irisx_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                conversationId INTEGER NOT NULL,
                senderRole TEXT NOT NULL,
                content TEXT NOT NULL,
                intentType TEXT,
                referencedExercise TEXT,
                referencedMuscleGroup TEXT,
                referencedSessionId INTEGER,
                createdAtMillis INTEGER NOT NULL
            )
        """.trimIndent())
        stmt.execute("CREATE INDEX IF NOT EXISTS index_irisx_messages_conversationId ON irisx_messages (conversationId)")
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS workout_routines (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                targetCategory TEXT NOT NULL DEFAULT 'General',
                createdAtMillis INTEGER NOT NULL,
                updatedAtMillis INTEGER NOT NULL
            )
        """.trimIndent())
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS routine_exercises (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                routineId INTEGER NOT NULL,
                exerciseId INTEGER,
                exerciseName TEXT NOT NULL,
                orderIndex INTEGER NOT NULL,
                targetSets INTEGER NOT NULL,
                minReps INTEGER NOT NULL,
                maxReps INTEGER NOT NULL,
                suggestedLoadGrams INTEGER,
                isDumbbell INTEGER NOT NULL DEFAULT 0,
                isAssisted INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        stmt.execute("CREATE INDEX IF NOT EXISTS index_routine_exercises_routineId ON routine_exercises (routineId)")
        stmt.execute("CREATE INDEX IF NOT EXISTS index_routine_exercises_exerciseId ON routine_exercises (exerciseId)")

        // 4. Apply MIGRATION_3_4
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS active_workout_drafts (
                slotId INTEGER PRIMARY KEY NOT NULL,
                sessionUuid TEXT NOT NULL,
                state TEXT NOT NULL,
                title TEXT NOT NULL,
                rawNotesText TEXT NOT NULL,
                cursorPosition INTEGER NOT NULL DEFAULT 0,
                sourceRoutineId INTEGER,
                plannedCategory TEXT,
                startedAtWallMillis INTEGER NOT NULL,
                startedAtElapsedRealtimeMillis INTEGER NOT NULL,
                accumulatedPausedMillis INTEGER NOT NULL DEFAULT 0,
                reviewStartedAtWallMillis INTEGER,
                reviewStartedAtElapsedMillis INTEGER,
                finalizedAtWallMillis INTEGER,
                lastSavedAtWallMillis INTEGER NOT NULL,
                lastKnownElapsedRealtimeMillis INTEGER NOT NULL,
                lastPersistedDurationMillis INTEGER NOT NULL DEFAULT 0,
                committedSessionId INTEGER,
                lastErrorMessage TEXT
            )
        """.trimIndent())

        // 5. Verify 100% of v0.2.1 records are intact after v2 -> v3 -> v4 upgrade
        stmt.executeQuery("SELECT preferredName, initialWeightGrams FROM profiles WHERE id = 1").use { rs ->
            assertTrue(rs.next())
            assertEquals("Fede", rs.getString("preferredName"))
            assertEquals(76000, rs.getInt("initialWeightGrams"))
        }
        stmt.executeQuery("SELECT COUNT(*) FROM weigh_ins").use { rs ->
            assertTrue(rs.next())
            assertEquals(2, rs.getInt(1))
        }
        stmt.executeQuery("SELECT displayName, muscleGroup FROM exercises WHERE id = 1").use { rs ->
            assertTrue(rs.next())
            assertEquals("Press de banca", rs.getString("displayName"))
            assertEquals("Pecho", rs.getString("muscleGroup"))
        }
        stmt.executeQuery("SELECT normalizedAlias FROM exercise_aliases WHERE id = 1").use { rs ->
            assertTrue(rs.next())
            assertEquals("banca plana", rs.getString("normalizedAlias"))
        }
        stmt.executeQuery("SELECT title, notes FROM workout_sessions WHERE id = 10").use { rs ->
            assertTrue(rs.next())
            assertEquals("Pecho v0.2.1", rs.getString("title"))
            assertEquals("Sesión en Redmi Note 13", rs.getString("notes"))
        }
        stmt.executeQuery("SELECT loadGrams, repetitions, plannedTarget FROM workout_sets WHERE id = 100").use { rs ->
            assertTrue(rs.next())
            assertEquals(75000, rs.getInt("loadGrams"))
            assertEquals(8, rs.getInt("repetitions"))
            assertEquals("3 series de 8–10 repeticiones", rs.getString("plannedTarget"))
        }
        stmt.executeQuery("SELECT targetValue FROM irisx_memories WHERE sourceKey = 'banca plana'").use { rs ->
            assertTrue(rs.next())
            assertEquals("Press de banca", rs.getString("targetValue"))
        }

        // 6. Verify v4 active_workout_drafts is ready for use
        stmt.execute("""
            INSERT INTO active_workout_drafts (
                slotId, sessionUuid, state, title, rawNotesText, cursorPosition,
                startedAtWallMillis, startedAtElapsedRealtimeMillis, lastSavedAtWallMillis, lastKnownElapsedRealtimeMillis
            ) VALUES (1, 'uuid-v4', 'ACTIVE', 'Espalda', 'Dominadas 10kg 8', 16, 1710000000000, 50000, 1710000000000, 50000)
        """.trimIndent())
        stmt.executeQuery("SELECT sessionUuid, state FROM active_workout_drafts WHERE slotId = 1").use { rs ->
            assertTrue(rs.next())
            assertEquals("uuid-v4", rs.getString("sessionUuid"))
            assertEquals("ACTIVE", rs.getString("state"))
        }

        // 7. Apply MIGRATION_4_5 (v4 -> v5)
        stmt.execute("ALTER TABLE workout_sessions ADD COLUMN category TEXT NOT NULL DEFAULT ''")
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS workout_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                icon TEXT NOT NULL DEFAULT '📁',
                orderIndex INTEGER NOT NULL DEFAULT 0,
                isHidden INTEGER NOT NULL DEFAULT 0,
                colorHex TEXT NOT NULL DEFAULT '#75D9C1'
            )
        """.trimIndent())
        stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS index_workout_categories_name ON workout_categories (name)")

        val defaultCategories = listOf(
            Triple("Pecho", "🛡️", 0),
            Triple("Espalda", "🏹", 1),
            Triple("Piernas", "🦵", 2),
            Triple("Hombros", "🏋️", 3),
            Triple("Brazos", "💪", 4),
            Triple("Full Body", "⚡", 5),
            Triple("Torso", "🏛️", 6),
            Triple("Push", "↗️", 7),
            Triple("Pull", "↙️", 8)
        )
        for ((name, icon, order) in defaultCategories) {
            stmt.execute("INSERT OR IGNORE INTO workout_categories (name, icon, orderIndex, isHidden, colorHex) VALUES ('$name', '$icon', $order, 0, '#75D9C1')")
        }

        stmt.execute("UPDATE workout_sessions SET category = 'Pecho' WHERE category = '' AND (title LIKE '%Pecho%' OR title LIKE '%Chest%')")

        // Verify category backfill on session 10 ("Pecho v0.2.1")
        stmt.executeQuery("SELECT title, category FROM workout_sessions WHERE id = 10").use { rs ->
            assertTrue(rs.next())
            assertEquals("Pecho v0.2.1", rs.getString("title"))
            assertEquals("Pecho", rs.getString("category"))
        }

        // Verify workout_categories count
        stmt.executeQuery("SELECT COUNT(*) FROM workout_categories WHERE isHidden = 0").use { rs ->
            assertTrue(rs.next())
            assertEquals(9, rs.getInt(1))
        }

        // 8. Apply MIGRATION_5_6 (v5 -> v6: multi-unit loads and failure sets)
        stmt.execute("ALTER TABLE workout_sets ADD COLUMN loadUnit TEXT NOT NULL DEFAULT 'KG'")
        stmt.execute("ALTER TABLE workout_sets ADD COLUMN isToFailure INTEGER NOT NULL DEFAULT 0")

        // Verify pre-existing v5 set (id = 100) now has default loadUnit="KG" and isToFailure=0
        stmt.executeQuery("SELECT loadGrams, repetitions, loadUnit, isToFailure FROM workout_sets WHERE id = 100").use { rs ->
            assertTrue(rs.next())
            assertEquals(75000, rs.getInt("loadGrams"))
            assertEquals(8, rs.getInt("repetitions"))
            assertEquals("KG", rs.getString("loadUnit"))
            assertEquals(0, rs.getInt("isToFailure"))
        }

        // Insert new v6 sets with different units (BLOQUES, PLACAS) and toFailure=1
        stmt.execute("""
            INSERT INTO workout_sets (
                sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions, rir, note, plannedTarget, loadUnit, isToFailure
            ) VALUES (10, 1, 1, 1, 13000, 9, 0, '[FALLO]', '3 series de 10-12 reps', 'BLOQUES', 1)
        """.trimIndent())

        stmt.executeQuery("SELECT loadGrams, repetitions, loadUnit, isToFailure, note FROM workout_sets WHERE loadUnit = 'BLOQUES'").use { rs ->
            assertTrue(rs.next())
            assertEquals(13000, rs.getInt("loadGrams"))
            assertEquals(9, rs.getInt("repetitions"))
            assertEquals("BLOQUES", rs.getString("loadUnit"))
            assertEquals(1, rs.getInt("isToFailure"))
            assertEquals("[FALLO]", rs.getString("note"))
        }

        stmt.close()
        connection.close()
    }
}
