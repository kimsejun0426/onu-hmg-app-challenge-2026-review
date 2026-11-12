package dev.carplaymate.demo

/** Output and bounded input are distinct; listening is never reported as media playback. */
internal enum class LetterTransportPhase { STOPPED, PREPARING, PLAYING, INPUT, PROCESSING, ERROR }

/**
 * Process-local transport facts only. No child speech, family identity or game checkpoint is exposed.
 * revision advances on every session revoke; sessionId is stable only for this live LetterSession.
 * positionMs is a native sample, not an elapsed-time estimate. Poll transportPositionMillis for a
 * fresh sample while PLAYING; cue/duration/position are absent outside output.
 */
internal data class LetterTransportSnapshot(
    val sessionId: String,
    val revision: Long,
    val phase: LetterTransportPhase,
    val cue: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
)
