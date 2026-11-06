package dev.carplaymate.demo

/** Runtime evidence supplied by the session host, never a preference or a UXR inference. */
internal data class VoiceHostState(
    val uiVisible: Boolean = false,
    val foregroundServiceRunning: Boolean = false,
    val explicitlyStartedSession: Boolean = false,
    val voiceModeSelected: Boolean = false,
    /** The app/host's restricted-state voice execution path must be established separately. */
    val restrictedVoiceSupported: Boolean = false,
)

internal data class VehicleInteractionPolicy(
    val allowsFullUi: Boolean,
    val allowsVoiceSession: Boolean,
    val requiresMinimalUi: Boolean,
) {
    companion object {
        private val BLOCKED = VehicleInteractionPolicy(false, false, false)
        // Public AAOS UXR bits through NO_VOICE_TRANSCRIPTION. Unknown bits are not permission.
        private const val KNOWN_RESTRICTIONS = 0x1ff

        fun evaluate(snapshot: VehicleUxSnapshot, host: VoiceHostState): VehicleInteractionPolicy {
            if (snapshot.access == VehicleAccessState.UNKNOWN) return BLOCKED
            val restrictions = snapshot.restrictions
            if (restrictions != null) {
                val expected = if (restrictions.requiresDistractionOptimization)
                    VehicleAccessState.RESTRICTED else VehicleAccessState.UNRESTRICTED
                if (snapshot.access != expected || restrictions.activeRestrictions < 0 ||
                    restrictions.activeRestrictions and KNOWN_RESTRICTIONS.inv() != 0 ||
                    restrictions.maxStringLength < 0 || restrictions.maxCumulativeContentItems < 0 ||
                    restrictions.maxContentDepth < 0) return BLOCKED
            }
            val unrestricted = snapshot.access.allowsInteraction
            // The existing full screen does not implement each restricted visual layout.
            val fullUi = unrestricted && (restrictions == null || restrictions.activeRestrictions == 0)
            val ownedVoice = host.foregroundServiceRunning && host.explicitlyStartedSession &&
                host.voiceModeSelected
            val voice = when {
                fullUi -> host.uiVisible || ownedVoice
                restrictions != null ->
                    ownedVoice && host.restrictedVoiceSupported
                else -> false
            }
            return VehicleInteractionPolicy(fullUi, voice, !fullUi && voice)
        }
    }
}
