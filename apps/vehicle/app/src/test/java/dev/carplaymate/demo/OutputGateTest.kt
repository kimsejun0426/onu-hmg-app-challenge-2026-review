package dev.carplaymate.demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputGateTest {
    @Test fun processStartRequiresExplicitResume() {
        val gate = OutputGate()
        assertFalse(gate.accepts(gate.generation))
        assertTrue(gate.accepts(gate.permitExplicitly()))
    }
    @Test fun stoppingFencesEveryEarlierCallback() {
        val gate = OutputGate()
        val started = gate.permitExplicitly()
        val playback = gate.replacePlan()
        gate.stop()
        assertFalse(gate.accepts(started))
        assertFalse(gate.accepts(playback))
        assertFalse(gate.accepts(gate.generation))
    }
    @Test fun oldPlaybackCannotReopenAfterResume() {
        val gate = OutputGate()
        val old = gate.permitExplicitly()
        gate.stop()
        val resumed = gate.permitExplicitly()
        assertFalse(gate.accepts(old))
        assertTrue(gate.accepts(resumed))
    }
}
