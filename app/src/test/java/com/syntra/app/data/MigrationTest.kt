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
}
