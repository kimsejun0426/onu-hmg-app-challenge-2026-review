package dev.carplaymate.demo

import org.junit.Assert.*
import org.junit.Test

class VehicleRestrictionGateTest {
    @Test
    fun aVehicleStartsUnknownAndOnlyPlatformFalseAllowsInteraction() {
        val gate = VehicleRestrictionGate {}
        assertEquals(VehicleAccessState.UNKNOWN, gate.state)
        assertFalse(gate.state.allowsInteraction)
        val connection = gate.newConnection()
        gate.result(checkNotNull(gate.query(connection)), null)
        assertEquals(VehicleAccessState.UNKNOWN, gate.state)
        gate.event(connection, true)
        assertEquals(VehicleAccessState.RESTRICTED, gate.state)
        assertFalse(gate.state.allowsInteraction)
        gate.event(connection, false)
        assertEquals(VehicleAccessState.UNRESTRICTED, gate.state)
        assertTrue(gate.state.allowsInteraction)
        assertTrue(VehicleAccessState.NON_AUTOMOTIVE_DEMO.allowsInteraction)
    }

    @Test
    fun anOlderQueryCannotUndoANewerRestrictionEvent() {
        val gate = VehicleRestrictionGate {}
        val connection = gate.newConnection()
        val oldQuery = checkNotNull(gate.query(connection))
        gate.event(connection, true)
        gate.result(oldQuery, false)
        assertEquals(VehicleAccessState.RESTRICTED, gate.state)
    }

    @Test
    fun disconnectAndReconnectFenceOldCallbacksAndQueries() {
        val gate = VehicleRestrictionGate {}
        val previous = gate.newConnection()
        val query = checkNotNull(gate.query(previous))
        gate.event(previous, false)
        gate.disconnect()
        assertFalse(gate.state.allowsInteraction)
        val current = gate.newConnection()
        gate.event(previous, false)
        gate.result(query, false)
        assertEquals(VehicleAccessState.UNKNOWN, gate.state)
        assertNull(gate.query(previous))
        gate.event(current, true)
        assertEquals(VehicleAccessState.RESTRICTED, gate.state)
    }

    @Test
    fun aBriefRestrictionIsDeliveredEvenWhenItClearsInTheSameDispatch() {
        val seen = mutableListOf<VehicleAccessState>()
        val gate = VehicleRestrictionGate(seen::add)
        val connection = gate.newConnection()
        gate.event(connection, false)
        gate.event(connection, true)
        gate.event(connection, false)
        assertEquals(
            listOf(
                VehicleAccessState.UNKNOWN,
                VehicleAccessState.UNRESTRICTED,
                VehicleAccessState.RESTRICTED,
                VehicleAccessState.UNRESTRICTED,
            ),
            seen,
        )
    }

    @Test
    fun failedQueryStaysUnknownAndCannotEraseANewerListenerResult() {
        val gate = VehicleRestrictionGate {}
        val connection = gate.newConnection()
        gate.result(checkNotNull(gate.query(connection)), null)
        assertEquals(VehicleAccessState.UNKNOWN, gate.state)
        val oldQuery = checkNotNull(gate.query(connection))
        gate.event(connection, false)
        gate.result(oldQuery, null)
        assertEquals(VehicleAccessState.UNRESTRICTED, gate.state)
    }
}
