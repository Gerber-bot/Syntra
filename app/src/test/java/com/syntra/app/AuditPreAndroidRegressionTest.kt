package com.syntra.app

import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.data.backup.BackupManager
import com.syntra.app.domain.SportsMetricsCalculator
import com.syntra.app.domain.UnitConverter
import com.syntra.app.domain.WorkoutTextParser
import com.syntra.app.ui.week.computeWeekRange
import com.syntra.app.ui.workout.RestTimerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Targeted regression suite covering Data/Backup, Parser, SportsMetrics, RestTimer and WeekScreen
 * fixes verified during the pre-device integral audit.
 */
class AuditPreAndroidRegressionTest {

    @Test
    fun `workout text parser does not tag set separator plus as lastre`() {
        val preview = WorkoutTextParser.parse("Sentadilla 100kg 5 + 100kg 5")
        assertEquals(1, preview.exercises.size)
        val sets = preview.exercises.first().sets
        assertEquals(2, sets.size)
        assertTrue(sets.all { !it.note.contains("[LASTRE]") })
        assertEquals(100_000, sets[0].weightGrams)
        assertEquals(100_000, sets[1].weightGrams)
    }

    @Test
    fun `workout text parser supports pounds ballast with explicit plus`() {
        val preview = WorkoutTextParser.parse("Dominadas +20lb 8")
        assertEquals(1, preview.exercises.size)
        val set = preview.exercises.first().sets.single()
        assertEquals("LBS", set.loadUnit)
        assertEquals(UnitConverter.toGrams(20.0, "LBS"), set.weightGrams)
        assertTrue(set.note.contains("[LASTRE]"))
    }

    @Test
    fun `workout text parser mixes explicit plus separator with negative assisted load safely`() {
        val preview = WorkoutTextParser.parse("Dominadas BW 8 + -15kg 6")
        assertEquals(1, preview.exercises.size)
        val sets = preview.exercises.first().sets
        assertEquals(2, sets.size)
        assertFalse(sets[0].note.contains("[LASTRE]"))
        assertTrue(sets[1].isAssisted)
        assertFalse(sets[1].note.contains("[LASTRE]"))
        assertEquals(15_000, sets[1].weightGrams)
    }

    @Test
    fun `sports metrics keeps machine blocks out of barbell regularSets count`() {
        val sets = listOf(
            WorkoutSetEntity(sessionId = 1, exerciseId = 1, orderInExercise = 1, loadGrams = 80_000, repetitions = 8, loadUnit = "KG"),
            WorkoutSetEntity(sessionId = 1, exerciseId = 1, orderInExercise = 2, loadGrams = 80_000, repetitions = 8, loadUnit = "KG"),
            WorkoutSetEntity(sessionId = 1, exerciseId = 2, orderInExercise = 1, loadGrams = 6_000, repetitions = 12, loadUnit = "BLOQUES"),
            WorkoutSetEntity(sessionId = 1, exerciseId = 2, orderInExercise = 2, loadGrams = 7_000, repetitions = 10, loadUnit = "BLOQUES"),
            WorkoutSetEntity(sessionId = 1, exerciseId = 2, orderInExercise = 3, loadGrams = 8_000, repetitions = 8, loadUnit = "BLOQUES")
        )

        val metrics = SportsMetricsCalculator.calculateSessionVolume(sets)
        assertEquals(5, metrics.totalSetsCount)
        assertEquals(2, metrics.regularSetsCount)
        assertTrue(metrics.summaryFormatted.contains("barra/máquina (2 series)"))
        assertTrue(metrics.summaryFormatted.contains("3 series en bloques/placas"))
    }

    @Test
    fun `backup bounded reader accepts valid payload and rejects oversized input`() {
        val smallJson = """{"formatVersion":3}"""
        val read = BackupManager.readBoundedUtf8Text(
            ByteArrayInputStream(smallJson.toByteArray(StandardCharsets.UTF_8)),
            maxBytes = 1024
        )
        assertEquals(smallJson, read)

        val oversized = ByteArray(2048) { 'a'.code.toByte() }
        var rejected = false
        try {
            BackupManager.readBoundedUtf8Text(ByteArrayInputStream(oversized), maxBytes = 1024)
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Oversized backup stream must be rejected", rejected)
    }

    @Test
    fun `backup restore policy preserves extended tables on v1 empty payload and replaces on v2 plus`() {
        assertFalse(BackupManager.shouldReplaceOptionalSnapshotTable(formatVersion = 1, hasIncomingData = false))
        assertTrue(BackupManager.shouldReplaceOptionalSnapshotTable(formatVersion = 1, hasIncomingData = true))
        assertTrue(BackupManager.shouldReplaceOptionalSnapshotTable(formatVersion = 2, hasIncomingData = false))
        assertTrue(BackupManager.shouldReplaceOptionalSnapshotTable(formatVersion = 3, hasIncomingData = false))
    }

    @Test
    fun `rest timer rejects stale target timestamps exceeding duration across reboot`() {
        assertTrue(RestTimerManager.isElapsedTargetPlausible(remMillis = 90_000L, totalSec = 90))
        assertTrue(RestTimerManager.isElapsedTargetPlausible(remMillis = 15_000L, totalSec = 90))
        assertFalse(RestTimerManager.isElapsedTargetPlausible(remMillis = 3_600_000L, totalSec = 90))
    }

    @Test
    fun `week range anchored on monday works reliably on sunday boundaries`() {
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        val sunday = LocalDate.of(2026, 11, 8) // Sunday
        val (startMillis, endMillis) = computeWeekRange(0, referenceDate = sunday, zoneId = zone)

        val startDate = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
        assertEquals(LocalDate.of(2026, 11, 2), startDate) // Monday
        assertEquals(LocalDate.of(2026, 11, 8), endDate)   // Sunday

        val (prevStart, prevEnd) = computeWeekRange(-1, referenceDate = sunday, zoneId = zone)
        assertEquals(LocalDate.of(2026, 10, 26), Instant.ofEpochMilli(prevStart).atZone(zone).toLocalDate())
        assertEquals(LocalDate.of(2026, 11, 1), Instant.ofEpochMilli(prevEnd).atZone(zone).toLocalDate())
    }
}
