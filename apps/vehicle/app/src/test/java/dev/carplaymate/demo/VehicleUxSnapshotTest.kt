package dev.carplaymate.demo

import org.junit.Assert.*
import org.junit.Test

class VehicleUxSnapshotTest {
    private fun restrictions(
        requiresOptimization: Boolean = true,
        flags: Int = 1,
        stringLength: Int = 40,
        cumulativeItems: Int = 12,
        contentDepth: Int = 3,
    ) = VehicleUxRestrictions(
        requiresOptimization, flags, stringLength, cumulativeItems, contentDepth,
    )

    @Test
    fun changedFlagsWithTheSameOptimizationRequirementReachBothObservers() {
        val states = mutableListOf<VehicleAccessState>()
        val observations = mutableListOf<VehicleUxSnapshot>()
        val gate = VehicleRestrictionGate(states::add, observations::add)
        val connection = gate.newConnection()
        val first = restrictions(flags = 1)
        val second = first.copy(activeRestrictions = 1 or 8)

        gate.eventRestrictions(connection, first)
        gate.eventRestrictions(connection, second)

        assertEquals(listOf(VehicleAccessState.UNKNOWN,
            VehicleAccessState.RESTRICTED, VehicleAccessState.RESTRICTED), states)
        assertEquals(listOf(null, first, second), observations.map { it.restrictions })
        assertEquals(second, gate.snapshot.restrictions)
        assertFalse(gate.state.allowsInteraction)
    }

    @Test
    fun limitsAndUnknownFlagBitsRemainExactAndEarlierSnapshotsDoNotChange() {
        val observations = mutableListOf<VehicleUxSnapshot>()
        val gate = VehicleRestrictionGate({}, observations::add)
        val connection = gate.newConnection()
        val initial = restrictions(flags = Int.MIN_VALUE or 5, stringLength = 0,
            cumulativeItems = Int.MAX_VALUE, contentDepth = -1)
        gate.resultRestrictions(checkNotNull(gate.query(connection)), initial)
        val captured = gate.snapshot
        val next = initial.copy(maxStringLength = 80, maxCumulativeContentItems = 20, maxContentDepth = 2)
        gate.eventRestrictions(connection, next)

        assertEquals(initial, captured.restrictions)
        val capturedRestrictions = checkNotNull(captured.restrictions)
        assertEquals(0, capturedRestrictions.maxStringLength)
        assertEquals(Int.MAX_VALUE, capturedRestrictions.maxCumulativeContentItems)
        assertEquals(-1, capturedRestrictions.maxContentDepth)
        assertEquals(Int.MIN_VALUE or 5, capturedRestrictions.activeRestrictions)
        assertEquals(next, observations.last().restrictions)
        assertEquals(3, observations.size)
    }

    @Test
    fun aStaleQueryCannotEraseNewerFlagsEvenWhenAccessIsUnchanged() {
        val observations = mutableListOf<VehicleUxSnapshot>()
        val gate = VehicleRestrictionGate({}, observations::add)
        val connection = gate.newConnection()
        gate.eventRestrictions(connection, restrictions(flags = 1))
        val query = checkNotNull(gate.query(connection))
        val latest = restrictions(flags = 9, contentDepth = 1)
        gate.eventRestrictions(connection, latest)
        val count = observations.size

        gate.resultRestrictions(query, restrictions(flags = 1, contentDepth = 3))
        gate.resultRestrictions(query, null)

        assertEquals(latest, gate.snapshot.restrictions)
        assertEquals(count, observations.size)
    }

    @Test
    fun reconnectClearsRawValuesAndFencesPreviousConnectionResults() {
        val observations = mutableListOf<VehicleUxSnapshot>()
        val gate = VehicleRestrictionGate({}, observations::add)
        val oldConnection = gate.newConnection()
        gate.eventRestrictions(oldConnection, restrictions(requiresOptimization = false, flags = 0))
        val oldQuery = checkNotNull(gate.query(oldConnection))
        gate.disconnect()
        assertEquals(VehicleUxSnapshot(VehicleAccessState.UNKNOWN), gate.snapshot)
        val currentConnection = gate.newConnection()
        val count = observations.size

        gate.eventRestrictions(oldConnection, restrictions(requiresOptimization = false))
        gate.resultRestrictions(oldQuery, restrictions(requiresOptimization = false))

        assertEquals(VehicleUxSnapshot(VehicleAccessState.UNKNOWN), gate.snapshot)
        assertEquals(count, observations.size)
        assertNull(gate.query(oldConnection))
        val latest = restrictions(flags = 17)
        gate.resultRestrictions(checkNotNull(gate.query(currentConnection)), latest)
        assertEquals(VehicleUxSnapshot(VehicleAccessState.RESTRICTED, latest), gate.snapshot)
    }

    @Test
    fun nullObservationClearsAllLimitsAndDoesNotPermitInteraction() {
        val gate = VehicleRestrictionGate {}
        val connection = gate.newConnection()
        gate.eventRestrictions(connection, restrictions(requiresOptimization = false, flags = 0))
        assertTrue(gate.state.allowsInteraction)
        gate.eventRestrictions(connection, null)
        assertEquals(VehicleUxSnapshot(VehicleAccessState.UNKNOWN), gate.snapshot)
        assertFalse(gate.state.allowsInteraction)
        gate.eventRestrictions(connection, restrictions(flags = 9))
        gate.resultRestrictions(checkNotNull(gate.query(connection)), null)
        assertEquals(VehicleUxSnapshot(VehicleAccessState.UNKNOWN), gate.snapshot)
    }

    @Test
    fun legacyAndRawEventsShareTheSameRevisionAndLegacyDoesNotInventLimits() {
        val gate = VehicleRestrictionGate {}
        val connection = gate.newConnection()
        val rawQuery = checkNotNull(gate.query(connection))
        gate.event(connection, true)
        gate.resultRestrictions(rawQuery, restrictions(requiresOptimization = false))
        assertEquals(VehicleUxSnapshot(VehicleAccessState.RESTRICTED), gate.snapshot)

        val legacyQuery = checkNotNull(gate.query(connection))
        val latest = restrictions(flags = 9)
        gate.eventRestrictions(connection, latest)
        gate.result(legacyQuery, false)
        assertEquals(VehicleUxSnapshot(VehicleAccessState.RESTRICTED, latest), gate.snapshot)

        gate.event(connection, false)
        assertEquals(VehicleUxSnapshot(VehicleAccessState.UNRESTRICTED), gate.snapshot)
        assertTrue(gate.state.allowsInteraction)
    }
}
