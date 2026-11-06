package dev.carplaymate.demo

import org.junit.Assert.*
import org.junit.Test

class VehicleInteractionPolicyTest {
    private val owned = VoiceHostState(foregroundServiceRunning = true,
        explicitlyStartedSession = true, voiceModeSelected = true, restrictedVoiceSupported = true)
    private fun restricted(flags: Int = 0x1ff) = VehicleUxSnapshot(VehicleAccessState.RESTRICTED,
        VehicleUxRestrictions(true, flags, 120, 6, 2))

    @Test fun visualRestrictionsAndAnEstablishedVoiceHostHaveSeparateDecisions() {
        val policy = VehicleInteractionPolicy.evaluate(restricted(), owned)
        assertFalse(policy.allowsFullUi)
        assertTrue(policy.allowsVoiceSession)
        assertTrue(policy.requiresMinimalUi)
    }

    @Test fun restrictionsCannotGrantVoiceWithoutEveryHostPrerequisite() {
        val absent = listOf(owned.copy(foregroundServiceRunning = false),
            owned.copy(explicitlyStartedSession = false), owned.copy(voiceModeSelected = false),
            owned.copy(restrictedVoiceSupported = false), VoiceHostState(uiVisible = true))
        absent.forEach { assertFalse(VehicleInteractionPolicy.evaluate(restricted(0), it).allowsVoiceSession) }
    }

    @Test fun unknownConnectionOrFutureFlagsNeverInheritAPreviousVoiceGrant() {
        assertFalse(VehicleInteractionPolicy.evaluate(VehicleUxSnapshot(VehicleAccessState.UNKNOWN), owned).allowsVoiceSession)
        assertFalse(VehicleInteractionPolicy.evaluate(restricted(0x200), owned).allowsVoiceSession)
        assertFalse(VehicleInteractionPolicy.evaluate(restricted(-1), owned).allowsVoiceSession)
        assertFalse(VehicleInteractionPolicy.evaluate(VehicleUxSnapshot(VehicleAccessState.RESTRICTED), owned).allowsVoiceSession)
    }

    @Test fun theForegroundScreenAndBackgroundAudioRequireDifferentEvidence() {
        val plain = VehicleUxSnapshot(VehicleAccessState.NON_AUTOMOTIVE_DEMO)
        assertTrue(VehicleInteractionPolicy.evaluate(plain, VoiceHostState(uiVisible = true)).allowsVoiceSession)
        assertFalse(VehicleInteractionPolicy.evaluate(plain, VoiceHostState()).allowsVoiceSession)
        assertTrue(VehicleInteractionPolicy.evaluate(plain, owned).allowsVoiceSession)
    }

    @Test fun restrictionsAndLimitsAreNotDiscardedWhenOptimizationIsFalse() {
        val snapshot = VehicleUxSnapshot(VehicleAccessState.UNRESTRICTED,
            VehicleUxRestrictions(false, 64, 120, 6, 2))
        val policy = VehicleInteractionPolicy.evaluate(snapshot, owned)
        assertFalse(policy.allowsFullUi)
        assertTrue(policy.allowsVoiceSession)
        assertFalse(VehicleInteractionPolicy.evaluate(snapshot.copy(restrictions =
            snapshot.restrictions!!.copy(maxStringLength = -1)), owned).allowsVoiceSession)
        assertFalse(VehicleInteractionPolicy.evaluate(snapshot.copy(access = VehicleAccessState.RESTRICTED), owned).allowsVoiceSession)
    }
}
