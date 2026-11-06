package dev.carplaymate.demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGateTest {
    private val context = CaptureContext("session-1", 4, "turn-2", 1, "window-2", 7)

    @Test fun oneFinalResultCannotSubmitTwiceOrDeliverAnErrorAfterwards() {
        val gate = CaptureGate()
        val ticket = gate.open(context)
        assertTrue(gate.consume(ticket, context))
        assertFalse(gate.consume(ticket, context))
        assertFalse(gate.accepts(ticket, context))
    }

    @Test fun stopInvalidatesCallbacksEvenBeforeServerStateChanges() {
        val gate = CaptureGate()
        val ticket = gate.open(context)
        gate.close()
        assertFalse(gate.consume(ticket, context))
    }

    @Test fun oldAttemptCannotClaimReopenedSameWindow() {
        val gate = CaptureGate()
        val old = gate.open(context)
        gate.close()
        val resumed = gate.open(context)
        assertFalse(gate.consume(old, context))
        assertTrue(gate.consume(resumed, context))
    }

    @Test fun changedServerOrOutputContextRejectsCapturedAnswer() {
        val changed = listOf(
            context.copy(sessionId = "session-2"),
            context.copy(version = 5),
            context.copy(turnId = "turn-3"),
            context.copy(turnEpoch = 2),
            context.copy(listenWindowId = "window-3"),
            context.copy(outputGeneration = 8),
        )
        for (current in changed) {
            val gate = CaptureGate()
            val ticket = gate.open(context)
            assertFalse(gate.consume(ticket, current))
        }
    }

    @Test fun latePreviousTurnResultDoesNotConsumeTheNewTurn() {
        val gate = CaptureGate()
        val old = gate.open(context)
        val next = context.copy(version = 6, turnId = "turn-3", listenWindowId = "window-3")
        val current = gate.open(next)
        assertFalse(gate.consume(old, next))
        assertFalse(gate.consume(current, null))
        assertTrue(gate.consume(current, next))
    }
}
