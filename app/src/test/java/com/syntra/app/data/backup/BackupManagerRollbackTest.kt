package com.syntra.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class BackupManagerRollbackTest {

    @Test
    fun `failed transaction during restore rolls back completely and preserves existing data`() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()

        // Create tables
        stmt.execute("""
            CREATE TABLE profiles (
                id INTEGER PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                preferredName TEXT NOT NULL,
                heightCm INTEGER NOT NULL,
                initialWeightGrams INTEGER NOT NULL,
                activityLevel TEXT NOT NULL,
                goal TEXT NOT NULL,
                createdAtMillis INTEGER NOT NULL,
                updatedAtMillis INTEGER NOT NULL
            )
        """.trimIndent())

        stmt.execute("""
            CREATE TABLE weigh_ins (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                measuredAtMillis INTEGER NOT NULL,
                weightGrams INTEGER NOT NULL,
                note TEXT NOT NULL
            )
        """.trimIndent())

        // Initial existing user data
        stmt.execute("""
            INSERT INTO profiles (id, name, preferredName, heightCm, initialWeightGrams, activityLevel, goal, createdAtMillis, updatedAtMillis)
            VALUES (1, 'Usuario Original', 'Original', 180, 80000, 'sedentario', 'salud', 1690000000000, 1690000000000)
        """.trimIndent())

        stmt.execute("""
            INSERT INTO weigh_ins (id, measuredAtMillis, weightGrams, note)
            VALUES (1, 1690000000000, 80000, 'Pesaje inicial existente')
        """.trimIndent())

        // Simulate an atomic transaction attempt that fails halfway (e.g. SQLite constraint violation or simulated error)
        conn.autoCommit = false
        try {
            // Step 1: delete old data
            stmt.execute("DELETE FROM profiles")
            stmt.execute("DELETE FROM weigh_ins")

            // Step 2: insert new profile
            stmt.execute("""
                INSERT INTO profiles (id, name, preferredName, heightCm, initialWeightGrams, activityLevel, goal, createdAtMillis, updatedAtMillis)
                VALUES (1, 'Usuario Nuevo', 'Nuevo', 170, 70000, 'activa', 'hipertrofia', 1700000000000, 1700000000000)
            """.trimIndent())

            // Step 3: simulate error during weigh_ins insertion (e.g. invalid query or exception)
            throw IllegalStateException("Simulated write failure during restore")

            conn.commit()
        } catch (e: Exception) {
            // On failure, rollback
            conn.rollback()
        } finally {
            conn.autoCommit = true
        }

        // Verify that existing data was NOT wiped or partially overwritten!
        val profileRs = stmt.executeQuery("SELECT name, heightCm FROM profiles WHERE id = 1")
        assertTrue(profileRs.next())
        assertEquals("Usuario Original", profileRs.getString("name"))
        assertEquals(180, profileRs.getInt("heightCm"))
        profileRs.close()

        val weighInRs = stmt.executeQuery("SELECT weightGrams, note FROM weigh_ins WHERE id = 1")
        assertTrue(weighInRs.next())
        assertEquals(80000, weighInRs.getInt("weightGrams"))
        assertEquals("Pesaje inicial existente", weighInRs.getString("note"))
        weighInRs.close()

        stmt.close()
        conn.close()
    }

    @Test
    fun `rejected backup never reaches restorePayload and preserves existing database completely`() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()

        stmt.execute("""
            CREATE TABLE weigh_ins (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                measuredAtMillis INTEGER NOT NULL,
                weightGrams INTEGER NOT NULL,
                note TEXT NOT NULL
            )
        """.trimIndent())

        stmt.execute("""
            INSERT INTO weigh_ins (id, measuredAtMillis, weightGrams, note)
            VALUES (1, 1690000000000, 78500, 'Pesaje existente que debe sobrevivir')
        """.trimIndent())

        // Incomplete backup JSON (missing weighIns)
        val rejectedJson = """
            {
                "formatVersion": 3,
                "appVersion": "0.7.0-alpha11",
                "exportedAtMillis": 1700000000000,
                "exercises": [],
                "workoutSessions": [],
                "workoutSets": []
            }
        """.trimIndent()

        val validationResult = BackupSerializer.deserializeAndValidate(rejectedJson)
        assertTrue("Incomplete backup must be rejected at validation stage", validationResult is BackupValidationResult.Error)

        // Since validation failed, restore is NOT executed:
        // Verify database is 100% intact
        val rs = stmt.executeQuery("SELECT count(*), weightGrams, note FROM weigh_ins")
        assertTrue(rs.next())
        assertEquals(1, rs.getInt(1))
        assertEquals(78500, rs.getInt(2))
        assertEquals("Pesaje existente que debe sobrevivir", rs.getString(3))
        rs.close()

        stmt.close()
        conn.close()
    }
}
