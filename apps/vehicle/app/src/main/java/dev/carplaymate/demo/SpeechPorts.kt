package dev.carplaymate.demo

import kotlinx.coroutines.flow.StateFlow

interface SpeechCaptureInput : SpeechInput {
    fun unavailableReason(checkPermission: Boolean = true): String?
    /** Compatibility fallback for simple test ports; platform adapters classify at the source. */
    fun unavailableFailure(checkPermission: Boolean = true): SpeechInputFailure? =
        unavailableReason(checkPermission)?.let {
            SpeechInputFailure(it, cause = RecoveryCause.INPUT_UNAVAILABLE)
        }
    fun open(ticket: CaptureTicket,
             onStage: (CaptureTicket, CaptureStage) -> Unit,
             onFinal: (CaptureTicket, String) -> Unit,
             onFailure: (CaptureTicket, SpeechInputFailure) -> Unit)
}

/** Current engine-reported spoken interval, never a waveform/lip-sync or microphone signal. */
data class SpeechNarrationState(val playbackId: String, val generation: Long, val active: Boolean)

interface SpeechOutput {
    val status: StateFlow<String>
    val readyState: StateFlow<Boolean>
    /** Optional presentation only. Null port/state is unknown/idle and must render inactive. */
    val narrationState: StateFlow<SpeechNarrationState?>? get() = null
    val availabilityFailure: SpeechOutputFailure?
        get() = if (readyState.value) null else SpeechOutputFailure(
            "기기 음성 준비를 확인한 뒤 이어 주세요.", RecoveryCause.OUTPUT_UNAVAILABLE,
        )
    fun refresh()
    /**
     * started reports a current request's first spoken SDK/engine start on main, at most once.
     * It is a presentation hint, not physical first-sound evidence or permission to open input.
     * Completion remains valid when the engine does not deliver a start callback.
     */
    fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
              failure: (SpeechOutputFailure) -> Unit, started: () -> Unit = {})
    fun stop()
    fun shutdown()
}
