package com.syntra.app.ui.workout

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RestTimerTest {

    @Before
    fun setUp() {
        RestTimerDiagnostics.clear()
    }

    @Test
    fun restTimerDiagnostics_recordsEventsUpToLimit() {
        assertEquals(0, RestTimerDiagnostics.getEvents().size)

        RestTimerDiagnostics.log("TIMER_SCHEDULED", "duration=90s exact=true")
        RestTimerDiagnostics.log("TIMER_RECEIVED", "driftMillis=12ms")
        RestTimerDiagnostics.log("VIBRATION_REQUESTED", "dual-path USAGE_ALARM")

        val events = RestTimerDiagnostics.getEvents()
        assertEquals(3, events.size)
        assertEquals("TIMER_SCHEDULED", events[0].type)
        assertEquals("TIMER_RECEIVED", events[1].type)
        assertEquals("VIBRATION_REQUESTED", events[2].type)
        assertTrue(events[0].details.contains("duration=90s"))
    }

    @Test
    fun restTimerDiagnostics_ringBufferCapsAt30() {
        for (i in 1..40) {
            RestTimerDiagnostics.log("EVENT_$i", "index=$i")
        }

        val events = RestTimerDiagnostics.getEvents()
        assertEquals(30, events.size)
        // Earliest event should now be EVENT_11
        assertEquals("EVENT_11", events.first().type)
        assertEquals("EVENT_40", events.last().type)
    }

    @Test
    fun restTimerDiagnostics_formatsWithoutPersonalData() {
        RestTimerDiagnostics.log("TIMER_SCHEDULED", "duration=120s exact=true targetElapsed=999999")
        val event = RestTimerDiagnostics.getEvents().first()
        val formatted = event.format()

        assertTrue(formatted.contains("TIMER_SCHEDULED"))
        assertTrue(formatted.contains("duration=120s"))
        assertFalse(formatted.contains("athlete"))
        assertFalse(formatted.contains("workout"))
    }

    @Test
    fun restTimerDiagnostics_clearEmptiesBuffer() {
        RestTimerDiagnostics.log("TEST", "details")
        assertEquals(1, RestTimerDiagnostics.getEvents().size)

        RestTimerDiagnostics.clear()
        assertEquals(0, RestTimerDiagnostics.getEvents().size)
    }

    @Test
    fun restTimerDiagnostics_recordsScreenAndExactAlarmStatus() {
        RestTimerDiagnostics.log("TIMER_RECEIVED", "driftMillis=45ms screenOn=false canExact=true")
        val event = RestTimerDiagnostics.getEvents().first()
        assertEquals("TIMER_RECEIVED", event.type)
        assertTrue(event.details.contains("screenOn=false"))
        assertTrue(event.details.contains("canExact=true"))
    }
}
