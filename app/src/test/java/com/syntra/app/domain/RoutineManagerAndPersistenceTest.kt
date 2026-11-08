package com.syntra.app.domain

import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.RoutineEntity
import com.syntra.app.data.RoutineExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class RoutineManagerAndPersistenceTest {

    @Test
    fun `validates routine structure and rejects invalid ranges`() {
        val validSpec = listOf(
            RoutineExerciseSpec(
                exerciseName = "Sentadilla",
                orderIndex = 1,
                targetSets = 4,
                minReps = 6,
                maxReps = 8,
                suggestedLoadGrams = 80000
            )
        )
        assertNull(RoutineManager.validateRoutine("Piernas Fuerza", validSpec))
        assertNotNull(RoutineManager.validateRoutine("", validSpec))
        assertNotNull(RoutineManager.validateRoutine("Vacía", emptyList()))

        val invalidReps = listOf(
            RoutineExerciseSpec(
                exerciseName = "Sentadilla",
                orderIndex = 1,
                targetSets = 4,
                minReps = 12,
                maxReps = 8
            )
        )
        assertNotNull(RoutineManager.validateRoutine("Rango Inválido", invalidReps))
    }

    @Test
    fun `applies routine to new session preserving planning vs execution without overwriting history`() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()

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

        // 1. Seed a historical workout session
        stmt.execute("""
            INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis, notes)
            VALUES (1, 'Tirón Histórico', 1700000000000, 1700003600000, 'Registro histórico inmutable')
        """.trimIndent())
        stmt.execute("""
            INSERT INTO workout_sets (sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions, plannedTarget)
            VALUES (1, 10, 1, 1, 65000, 8, '3 series de 8–10 repeticiones')
        """.trimIndent())

        // 2. Define a Routine with 4x8-10 @ 70kg suggested
        val routine = RoutineEntity(id = 5, name = "Tirón A", description = "Espalda y bíceps")
        val items = listOf(
            RoutineExerciseEntity(
                id = 50,
                routineId = 5,
                exerciseId = 10,
                exerciseName = "Remo con barra",
                orderIndex = 1,
                targetSets = 3,
                minReps = 8,
                maxReps = 10,
                suggestedLoadGrams = 70000
            )
        )
        val routineWithEx = RoutineManager.fromEntities(routine, items)

        // 3. Apply routine with custom execution (user actually lifted 72.5kg for 9, 9, 8 reps)
        val customExec = mapOf(
            1 to listOf(
                ExecutedRoutineSetInput(loadGrams = 72500, repetitions = 9),
                ExecutedRoutineSetInput(loadGrams = 72500, repetitions = 9),
                ExecutedRoutineSetInput(loadGrams = 72500, repetitions = 8)
            )
        )
        val draft = RoutineManager.createSessionDraftFromRoutine(
            routineWithExercises = routineWithEx,
            startedAtMillis = 1710000000000L,
            customExecutionByOrder = customExec
        )

        // Persist the applied session as a new session (id = 2)
        stmt.execute("""
            INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis, notes)
            VALUES (2, '${draft.sessionTitle}', ${draft.startedAtMillis}, ${draft.startedAtMillis}, '${draft.notes}')
        """.trimIndent())
        draft.exercises.forEach { ex ->
            ex.executedSets.forEachIndexed { idx, s ->
                stmt.execute("""
                    INSERT INTO workout_sets (sessionId, exerciseId, orderInExercise, executionOrder, loadGrams, repetitions, plannedTarget)
                    VALUES (2, ${ex.exerciseId ?: 10}, ${idx + 1}, ${idx + 1}, ${s.loadGrams}, ${s.repetitions}, '${ex.plannedTarget}')
                """.trimIndent())
            }
        }

        // 4. Verify historical session #1 was NOT overwritten
        val histRs = stmt.executeQuery("SELECT loadGrams, repetitions FROM workout_sets WHERE sessionId = 1")
        assertTrue(histRs.next())
        assertEquals(65000, histRs.getInt("loadGrams"))
        assertEquals(8, histRs.getInt("repetitions"))
        histRs.close()

        // 5. Verify new session #2 preserves both plannedTarget (3 series de 8–10 repeticiones) and actual execution (72500g)
        val newRs = stmt.executeQuery("SELECT loadGrams, repetitions, plannedTarget FROM workout_sets WHERE sessionId = 2 ORDER BY orderInExercise ASC")
        assertTrue(newRs.next())
        assertEquals(72500, newRs.getInt("loadGrams"))
        assertEquals(9, newRs.getInt("repetitions"))
        assertEquals("3 series de 8–10 repeticiones", newRs.getString("plannedTarget"))
        newRs.close()

        // 6. Verify parser template generation works seamlessly with WorkoutTextParser
        val template = RoutineManager.toParserTextTemplate(routineWithEx)
        val parsed = WorkoutTextParser.parse(template)
        assertEquals("Tirón A", parsed.sessionTitle)
        assertEquals(1, parsed.exercises.size)
        assertEquals("3 series de 8–10 repeticiones", parsed.exercises[0].plannedTarget)
        assertEquals(70000, parsed.exercises[0].sets[0].weightGrams)

        stmt.close()
        conn.close()
    }

    @Test
    fun `coach suggests load increment for routine when user hits top of rep range`() {
        val routine = RoutineManager.fromEntities(
            RoutineEntity(id = 1, name = "Empuje"),
            listOf(
                RoutineExerciseEntity(
                    id = 1,
                    routineId = 1,
                    exerciseId = 2,
                    exerciseName = "Press de banca",
                    orderIndex = 1,
                    targetSets = 3,
                    minReps = 8,
                    maxReps = 10,
                    suggestedLoadGrams = 80000
                )
            )
        )
        val sessions = listOf(
            WorkoutSessionEntity(id = 10, title = "Empuje", startedAtMillis = 1710000000000L)
        )
        val sets = listOf(
            WorkoutSetEntity(sessionId = 10, exerciseId = 2, orderInExercise = 1, loadGrams = 80000, repetitions = 10)
        )
        val exercises = listOf(
            ExerciseEntity(id = 2, displayName = "Press de banca", normalizedName = "press de banca", muscleGroup = "Pecho", equipment = "Barra")
        )

        val suggestions = RoutineManager.suggestCoachAdjustments(routine, sessions, sets, exercises)
        assertEquals(1, suggestions.size)
        assertEquals(82500, suggestions[0].recommendedLoadGrams)
    }
}
