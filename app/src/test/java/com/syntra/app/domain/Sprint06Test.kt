package com.syntra.app.domain

import com.syntra.app.data.CategoryEntity
import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.data.backup.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class Sprint06Test {

    @Test
    fun testResolveSessionCategoryPriority() {
        // Priority 1: Explicit confirmed category on session overrides routine and title
        val p1 = WorkoutCategoryClassifier.resolveSessionCategory(
            explicitCategory = "Pecho",
            routineCategory = "Espalda",
            title = "Sentadilla pesada",
            exerciseNames = listOf("Sentadilla con barra")
        )
        assertEquals("Pecho", p1)

        // Priority 2: Routine category when explicit is empty or generic
        val p2 = WorkoutCategoryClassifier.resolveSessionCategory(
            explicitCategory = "",
            routineCategory = "Espalda",
            title = "Entrenamiento del lunes",
            exerciseNames = listOf("Remo con barra")
        )
        assertEquals("Espalda", p2)

        // Priority 3: IrisX / title classification when explicit and routine are blank
        val p3 = WorkoutCategoryClassifier.resolveSessionCategory(
            explicitCategory = null,
            routineCategory = null,
            title = "Día de Piernas intensas",
            exerciseNames = listOf("Prensa de piernas")
        )
        assertEquals("Piernas", p3)

        // Priority 4: Fallback to empty string for unclassified
        val p4 = WorkoutCategoryClassifier.resolveSessionCategory(
            explicitCategory = "",
            routineCategory = "General",
            title = "Sesión 12345",
            exerciseNames = emptyList()
        )
        assertEquals("", p4)
    }

    @Test
    fun testDynamicCategorySummariesPreservesExercisesUnderSession() {
        // A user trains "Pecho" which includes pectoral, shoulders, and triceps
        val now = System.currentTimeMillis()
        val sessionPecho = WorkoutSessionEntity(
            id = 100,
            title = "Pecho y accesorios",
            category = "Pecho",
            startedAtMillis = now,
            finishedAtMillis = now + 3600000L
        )

        val categories = listOf(
            CategoryEntity(id = 1, name = "Pecho", icon = "🛡️", orderIndex = 0),
            CategoryEntity(id = 2, name = "Espalda", icon = "🏹", orderIndex = 1),
            CategoryEntity(id = 3, name = "Piernas", icon = "🦵", orderIndex = 2)
        )

        val sessionCategoryMap = mapOf(100L to "Pecho")

        val summaries = WorkoutCategoryClassifier.buildDynamicSummaries(
            sessions = listOf(sessionPecho),
            configuredCategories = categories,
            sessionCategoryMap = sessionCategoryMap
        )

        // Total "Todas" has 1 session
        val todas = summaries.first { it.isAll }
        assertEquals(1, todas.sessionCount)

        // "Pecho" has 1 session
        val pechoSummary = summaries.first { it.categoryName == "Pecho" }
        assertEquals(1, pechoSummary.sessionCount)

        // "Espalda" and "Piernas" have 0 sessions
        assertEquals(0, summaries.first { it.categoryName == "Espalda" }.sessionCount)
        assertEquals(0, summaries.first { it.categoryName == "Piernas" }.sessionCount)

        // "Otras / Sin clasificar" has 0 sessions
        assertEquals(0, summaries.first { it.categoryName == "Otras / Sin clasificar" }.sessionCount)
    }

    @Test
    fun testWorkoutTextParserContextCategoryWithoutDuplicateHeader() {
        val raw = """
            Press de banca plano 80kg 10 9 8
            Aperturas inclinadas 16kg 12 12
        """.trimIndent()

        val preview = WorkoutTextParser.parse(
            raw = raw,
            contextCategory = "Pecho"
        )

        // Context category provides the sessionTitle without requiring a header line
        assertEquals("Pecho", preview.sessionTitle)
        // Two exercises parsed cleanly
        assertEquals(2, preview.exercises.size)
        assertEquals("Press de banca plano", preview.exercises[0].name)
        assertEquals(3, preview.exercises[0].sets.size)
        assertEquals("Aperturas inclinadas", preview.exercises[1].name)
    }

    @Test
    fun testNextSessionPlannerWithExplicitSessionCategory() {
        val config = TrainingSequenceConfig(
            steps = listOf("Pecho", "Espalda", "Piernas"),
            manualOverrideCategory = null
        )

        val completedSession = WorkoutSessionEntity(
            id = 1,
            title = "Torso fuerza",
            category = "Pecho",
            startedAtMillis = System.currentTimeMillis() - 86400000L,
            finishedAtMillis = System.currentTimeMillis() - 82800000L
        )

        val plan = NextSessionPlanner.computeNextSession(
            config = config,
            completedSessions = listOf(completedSession),
            allSets = emptyList(),
            exercises = emptyList()
        )

        // Since last session was categorized as Pecho, next planned is Espalda
        assertEquals("Espalda", plan.nextCategoryName)
        assertFalse(plan.manualOverrideActive)
    }

    @Test
    fun testNextSessionPlannerManualOverrideIsolation() {
        val configManual = TrainingSequenceConfig(
            steps = listOf("Pecho", "Espalda", "Piernas"),
            manualOverrideCategory = "Piernas"
        )

        val planManual = NextSessionPlanner.computeNextSession(
            config = configManual,
            completedSessions = emptyList(),
            allSets = emptyList(),
            exercises = emptyList()
        )

        assertEquals("Piernas", planManual.nextCategoryName)
        assertTrue(planManual.manualOverrideActive)

        // If manual override is cleared
        val configNormal = configManual.copy(manualOverrideCategory = null)
        val planNormal = NextSessionPlanner.computeNextSession(
            config = configNormal,
            completedSessions = emptyList(),
            allSets = emptyList(),
            exercises = emptyList()
        )

        assertEquals("Pecho", planNormal.nextCategoryName)
        assertFalse(planNormal.manualOverrideActive)
    }

    @Test
    fun testBackupSerializationWithCategoriesAndV3Format() {
        val now = System.currentTimeMillis()
        val payload = SyntraBackupPayload(
            formatVersion = 3,
            appVersion = "0.6.0",
            exportedAtMillis = now,
            profile = ProfileBackupDto(
                name = "Federico",
                preferredName = "Fede",
                heightCm = 178,
                initialWeightGrams = 76000,
                activityLevel = "Moderada",
                goal = "Fuerza",
                createdAtMillis = now,
                updatedAtMillis = now
            ),
            workoutSessions = listOf(
                WorkoutSessionBackupDto(
                    id = 1,
                    title = "Pecho pesado",
                    category = "Pecho",
                    startedAtMillis = now - 3600000L,
                    finishedAtMillis = now,
                    notes = "Excelente sesión"
                )
            ),
            categories = listOf(
                CategoryBackupDto(
                    id = 1,
                    name = "Pecho",
                    icon = "🛡️",
                    orderIndex = 0,
                    isHidden = false,
                    colorHex = "#75D9C1"
                ),
                CategoryBackupDto(
                    id = 2,
                    name = "Piernas",
                    icon = "🦵",
                    orderIndex = 1,
                    isHidden = false,
                    colorHex = "#75D9C1"
                )
            )
        )

        val json = BackupSerializer.serialize(payload)
        assertTrue(json.contains("\"workoutCategories\""))
        assertTrue(json.contains("\"category\": \"Pecho\""))
        assertTrue(json.contains("\"formatVersion\": 3"))

        val validation = BackupSerializer.deserializeAndValidate(json)
        assertTrue(validation is BackupValidationResult.Success)
        val deserialized = (validation as BackupValidationResult.Success).payload

        assertEquals(3, deserialized.formatVersion)
        assertEquals("0.6.0", deserialized.appVersion)
        assertEquals(1, deserialized.workoutSessions.size)
        assertEquals("Pecho", deserialized.workoutSessions[0].category)
        assertEquals(2, deserialized.categories.size)
        assertEquals("Pecho", deserialized.categories[0].name)
        assertEquals("🛡️", deserialized.categories[0].icon)
    }

    @Test
    fun testBackupBackwardCompatibilityWithV2Payload() {
        // v2 JSON without workoutCategories and without session category
        val v2Json = """
            {
                "formatVersion": 2,
                "appVersion": "0.5.0",
                "exportedAtMillis": 1710000000000,
                "profile": {
                    "name": "Fede",
                    "preferredName": "Fede",
                    "heightCm": 178,
                    "initialWeightGrams": 76000,
                    "activityLevel": "Moderada",
                    "goal": "Fuerza",
                    "createdAtMillis": 1710000000000,
                    "updatedAtMillis": 1710000000000
                },
                "workoutSessions": [
                    {
                        "id": 1,
                        "title": "Entrenamiento de Pecho",
                        "startedAtMillis": 1710000000000,
                        "finishedAtMillis": 1710003600000,
                        "notes": "Notas"
                    }
                ],
                "weighIns": [],
                "exercises": [],
                "exerciseAliases": [],
                "workoutSets": [],
                "irisxMemories": [],
                "irisxConversations": [],
                "irisxMessages": [],
                "routines": [],
                "routineExercises": []
            }
        """.trimIndent()

        val validation = BackupSerializer.deserializeAndValidate(v2Json)
        assertTrue(validation is BackupValidationResult.Success)
        val deserialized = (validation as BackupValidationResult.Success).payload

        assertEquals(2, deserialized.formatVersion)
        assertEquals(1, deserialized.workoutSessions.size)
        // Missing category defaults gracefully to empty string
        assertEquals("", deserialized.workoutSessions[0].category)
        // Missing workoutCategories defaults gracefully to empty list
        assertTrue(deserialized.categories.isEmpty())
    }

    @Test
    fun testWeekBoundariesMath() {
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val mondayStart = cal.timeInMillis

        cal.add(Calendar.DAY_OF_WEEK, 6)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val sundayEnd = cal.timeInMillis

        assertTrue(sundayEnd > mondayStart)
        // Exact 7 days span (7 * 24 * 3600 * 1000 - 1 ms)
        val span = sundayEnd - mondayStart + 1
        assertEquals(7 * 24 * 60 * 60 * 1000L, span)
    }
}
