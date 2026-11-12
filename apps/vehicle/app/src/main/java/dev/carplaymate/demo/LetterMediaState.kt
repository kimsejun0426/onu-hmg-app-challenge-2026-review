package dev.carplaymate.demo

/** Process-local capabilities. They are neither a saved preference nor permission for a new capture. */
internal data class LetterMediaLease(
    val token: String, val sessionId: String, val revision: Long, val expiresAt: Long,
) {
    fun matches(transport: LetterTransportSnapshot, now: Long) = now < expiresAt &&
        transport.sessionId == sessionId && transport.revision == revision &&
        transport.phase == LetterTransportPhase.STOPPED
    companion object { const val DURATION_MS = 120_000L }
}

internal data class LetterMediaState(
    val owned: Boolean = false,
    val episodeId: String? = null,
    val title: String = "오누 소리 편지",
    val transport: LetterTransportSnapshot? = null,
    val resumeToken: String? = null,
)
