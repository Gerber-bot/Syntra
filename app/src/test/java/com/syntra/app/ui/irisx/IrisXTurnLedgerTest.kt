package com.syntra.app.ui.irisx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrisXTurnLedgerTest {

    @Test
    fun `a second send while a turn is in flight is rejected`() {
        val ledger = IrisXTurnLedger()

        val first = ledger.beginTurn()
        assertNotNull(first)
        assertNull(ledger.beginTurn())
        assertTrue(ledger.hasActiveTurn)
    }

    @Test
    fun `only the current turn is active`() {
        val ledger = IrisXTurnLedger()
        val first = ledger.beginTurn()!!
        val stale = "turno-anterior"

        assertTrue(ledger.isActive(first))
        assertFalse(ledger.isActive(stale))
        assertFalse(ledger.isActive(null))
    }

    @Test
    fun `a stale turn cannot release the console owned by a newer one`() {
        val ledger = IrisXTurnLedger()
        val first = ledger.beginTurn()!!
        ledger.finishTurn(first)
        val second = ledger.beginTurn()!!

        assertFalse(ledger.finishTurn(first))
        assertTrue(ledger.isActive(second))
        assertTrue(ledger.hasActiveTurn)
    }

    @Test
    fun `cancelling releases the console and returns the interrupted turn`() {
        val ledger = IrisXTurnLedger()
        val first = ledger.beginTurn()!!

        assertEquals(first, ledger.cancelActive())
        assertNull(ledger.cancelActive())
        assertFalse(ledger.isActive(first))
        assertNotNull(ledger.beginTurn())
    }

    @Test
    fun `sending the same text again later is a brand new turn`() {
        val ledger = IrisXTurnLedger()
        val first = ledger.beginTurn()!!
        assertTrue(ledger.finishTurn(first))

        val second = ledger.beginTurn()
        assertNotNull(second)
        assertNotEquals(first, second)
    }

    @Test
    fun `recomposition keeps the in-flight turn locked`() {
        // The ledger is remembered across recompositions, so reading it again must not open a
        // second turn for the same tap.
        val ledger = IrisXTurnLedger()
        val turn = ledger.beginTurn()!!

        repeat(3) { assertFalse(ledger.isActive("otro-turno")) }
        assertTrue(ledger.isActive(turn))
        assertNull(ledger.beginTurn())
    }

    @Test
    fun `reopening the console with a fresh ledger accepts a new turn`() {
        val previous = IrisXTurnLedger()
        previous.beginTurn()

        val reopened = IrisXTurnLedger()
        assertFalse(reopened.hasActiveTurn)
        assertNotNull(reopened.beginTurn())
    }
}
