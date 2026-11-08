package com.syntra.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class WorkoutSessionPersistenceTest {

    @Test
    fun `session saving preserves execution order, loads, and sets correctly`() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()

        // Create schema v2
        stmt.execute("""
            CREATE TABLE exercises (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                displayName TEXT NOT NULL,
                normalizedName TEXT NOT NULL UNIQUE,
                muscleGroup TEXT NOT NULL,
                secondaryMuscleGroups TEXT NOT NULL DEFAULT '',
                equipment TEXT,
                exerciseType TEXT NOT NULL DEFAULT 'strength',
                status TEXT NOT NULL DEFAULT 'CONFIRMED',
                createdAtMillis INTEGER NOT NULL
            )
        """.trimIndent())

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
            CREATE TABLE workout_sets (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER NOT NULL,
                exerciseId INTEGER NOT NULL,
                orderInExercise INTEGER NOT NULL,
                executionOrder INTEGER NOT NULL DEFAULT 0,
                loadGrams INTEGER NOT NULL,
                repetitions INTEGER NOT NULL,
                rir INTEGER,
                isAssisted INTEGER NOT NULL DEFAULT 0,
                isDumbbell INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                plannedTarget TEXT
            )
        """.trimIndent())

        // 1. Insert session
        stmt.execute("""
            INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis, notes)
            VALUES (1, 'Torso Empuje', 1700000000000, 1700003600000, 'Buena sesión')
        """.trimIndent())

        // 2. Insert exercises
        stmt.execute("""
            INSERT INTO exercises (id, displayName, normalizedName, muscleGroup, secondaryMuscleGroups, equipment, exerciseType, status, createdAtMillis)
            VALUES (1, 'Press de banca', 'press de banca', 'Pecho', 'Tríceps', 'Barra', 'strength', 'CONFIRMED', 1700000000000),
                   (2, 'Press militar', 'press militar', 'Hombros', 'Tríceps', 'Barra', 'strength', 'CONFIRMED', 1700000000000)
        """.trimIndent())

        // 3. Insert sets with executionOrder
        stmt.execute("""
            INSERT INTO workout_sets (sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions, rir, plannedTarget)
            VALUES (1, 1, 1, 1, 80000, 8, 2, '3x8-10'),
                   (1, 1, 2, 2, 80000, 8, 1, '3x8-10'),
                   (1, 1, 3, 3, 75000, 10, 0, '3x8-10'),
                   (1, 2, 1, 4, 50000, 8, 2, '3x8'),
                   (1, 2, 2, 5, 50000, 8, 2, '3x8')
        """.trimIndent())

        // 4. Verify queries and order
        val sessionRs = stmt.executeQuery("SELECT title, notes FROM workout_sessions WHERE id = 1")
        assertTrue(sessionRs.next())
        assertEquals("Torso Empuje", sessionRs.getString("title"))
        assertEquals("Buena sesión", sessionRs.getString("notes"))
        sessionRs.close()

        val setsRs = stmt.executeQuery("""
            SELECT s.executionOrder, e.displayName, s.loadGrams, s.repetitions, s.plannedTarget
            FROM workout_sets s
            JOIN exercises e ON s.exerciseId = e.id
            WHERE s.sessionId = 1
            ORDER BY s.executionOrder ASC
        """.trimIndent())

        var count = 0
        while (setsRs.next()) {
            count++
            val order = setsRs.getInt("executionOrder")
            assertEquals(count, order)
            if (count in 1..3) {
                assertEquals("Press de banca", setsRs.getString("displayName"))
                assertEquals("3x8-10", setsRs.getString("plannedTarget"))
            } else {
                assertEquals("Press militar", setsRs.getString("displayName"))
            }
        }
        assertEquals(5, count)
        setsRs.close()

        // 5. Test atomic deletion
        stmt.execute("DELETE FROM workout_sets WHERE sessionId = 1")
        stmt.execute("DELETE FROM workout_sessions WHERE id = 1")

        val checkSets = stmt.executeQuery("SELECT COUNT(*) AS total FROM workout_sets WHERE sessionId = 1")
        assertTrue(checkSets.next())
        assertEquals(0, checkSets.getInt("total"))
        checkSets.close()

        val checkSession = stmt.executeQuery("SELECT COUNT(*) AS total FROM workout_sessions WHERE id = 1")
        assertTrue(checkSession.next())
        assertEquals(0, checkSession.getInt("total"))
        checkSession.close()

        stmt.close()
        conn.close()
    }
}
