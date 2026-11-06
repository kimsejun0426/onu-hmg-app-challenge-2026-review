package dev.carplaymate.demo

/** A live paused turn, never a saved checkpoint or a new-session authorization. */
internal data class MediaPausedSession(
    val sessionId: String,
    val version: Long,
    val turnEpoch: Long,
    val stopGeneration: Long,
)

/** Process-local short-pause authority. Binding cannot change or extend an existing lease. */
internal data class MediaPauseLease(
    val token: String,
    val sessionId: String,
    val stopGeneration: Long,
    val expiresAtElapsed: Long,
    val paused: MediaPausedSession? = null,
) {
    fun validAt(nowElapsed: Long): Boolean = nowElapsed < expiresAtElapsed

    fun bind(target: MediaPausedSession, nowElapsed: Long): MediaPauseLease? {
        if (!validAt(nowElapsed) || target.sessionId != sessionId ||
            target.stopGeneration != stopGeneration || (paused != null && paused != target)) return null
        return if (paused == target) this else copy(paused = target)
    }

    fun permits(receivedToken: String, target: MediaPausedSession, nowElapsed: Long): Boolean =
        receivedToken == token && validAt(nowElapsed) && paused == target && target.sessionId == sessionId &&
            target.stopGeneration == stopGeneration

    companion object {
        // A bounded product timeout, not a measured child-development or microphone safety limit.
        const val DURATION_MILLIS = 120_000L
    }
}
