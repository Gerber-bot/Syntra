package com.syntra.app.domain

import com.syntra.app.domain.WorkoutPresentation
import com.syntra.app.domain.WorkoutTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class WorkoutPreviewEditAndPersistenceTest {

    @Test
    fun `complete flow interpretation - preview - edit - transactional SQLite persistence`() {
        val rawInput = """
            Torso
            Press banca 80kg 10 8
            Inclinado 60kg 10
        """.trimIndent()

        // 1. Interpretación
        val preview = WorkoutTextParser.parse(rawInput)
        assertNotNull(preview)
        assertEquals(2, preview.exercises.size)
        assertEquals("Press banca", preview.exercises[0].name)
        assertEquals(80000, preview.exercises[0].sets[0].weightGrams)

        // 2. Vista previa compacta
        val compactInitial = WorkoutPresentation.toCompactList(preview.exercises)
        assertEquals(2, compactInitial.size)

        // 3. Edición en memoria (simulando EditExercisePreviewDialog)
        val editedExercises = preview.exercises.map { ex ->
            if (ex.name == "Press banca") {
                val updatedSets = listOf(
                    ParsedSet(weightGrams = 82500, repetitions = 10),
                    ParsedSet(weightGrams = 82500, repetitions = 8)
                )
                ex.copy(name = "Press de banca plano", sets = updatedSets, plannedTarget = "2x8-10")
            } else {
                ex
            }
        }

        // Cancelar una edición: verificar que una copia no guardada no altere la lista
        val cancelledExercise = editedExercises[1].copy(name = "Nombre descartado")
        // No aplicamos 'cancelledExercise'
        assertEquals("Inclinado", editedExercises[1].name)

        // 4. Confirmación y guardado transaccional en SQLite
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()

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

        // Transacción
        conn.autoCommit = false
        val now = 1700000000000L

        stmt.execute("INSERT INTO workout_sessions (id, title, startedAtMillis) VALUES (1, 'Torso', $now)")

        var execOrder = 1
        var exIdCounter = 1L

        for (ex in editedExercises) {
            val norm = WorkoutTextParser.normalize(ex.name)
            stmt.execute("""
                INSERT OR IGNORE INTO exercises (id, displayName, normalizedName, muscleGroup, status, createdAtMillis)
                VALUES ($exIdCounter, '${ex.name}', '$norm', 'Pecho', 'CONFIRMED', $now)
            """.trimIndent())

            val targetExId = exIdCounter
            exIdCounter++

            ex.sets.forEachIndexed { idx, s ->
                val planVal = if (ex.plannedTarget != null) "'${ex.plannedTarget}'" else "NULL"
                stmt.execute("""
                    INSERT INTO workout_sets (sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions, plannedTarget)
                    VALUES (1, $targetExId, ${idx + 1}, $execOrder, ${s.weightGrams}, ${s.repetitions}, $planVal)
                """.trimIndent())
                execOrder++
            }
        }
        conn.commit()
        conn.autoCommit = true

        // 5. Historial: verificar persistencia exacta
        val rs = stmt.executeQuery("""
            SELECT s.orderInExercise, s.executionOrder, s.loadGrams, s.repetitions, s.plannedTarget, e.displayName
            FROM workout_sets s
            JOIN exercises e ON s.exerciseId = e.id
            WHERE s.sessionId = 1
            ORDER BY s.executionOrder ASC
        """.trimIndent())

        val retrieved = mutableListOf<String>()
        while (rs.next()) {
            val exName = rs.getString("displayName")
            val grams = rs.getInt("loadGrams")
            val reps = rs.getInt("repetitions")
            val order = rs.getInt("executionOrder")
            val plan = rs.getString("plannedTarget")
            retrieved.add("$order: $exName $grams g x $reps ($plan)")
        }
        rs.close()

        assertEquals(3, retrieved.size)
        // Set 1: Press de banca plano 82500 g x 10 (2x8-10)
        assertEquals("1: Press de banca plano 82500 g x 10 (2x8-10)", retrieved[0])
        // Set 2: Press de banca plano 82500 g x 8 (2x8-10)
        assertEquals("2: Press de banca plano 82500 g x 8 (2x8-10)", retrieved[1])
        // Set 3: Inclinado 60000 g x 10 (null)
        assertEquals("3: Inclinado 60000 g x 10 (null)", retrieved[2])

        stmt.close()
        conn.close()
    }
}
