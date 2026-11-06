package dev.carplaymate.demo

import java.util.UUID

interface SpeechInput {
    val available: Boolean
    val source: String
    fun close()
}
/** Explicit developer text injection; it neither records nor accesses a microphone. */
class MockSpeechInput : SpeechInput {
    override val available = true
    override val source = "mock"
    private var window: String? = null
    fun open(listenWindowId: String) { window = listenWindowId }
    fun capture(text: String): FinalUtterance {
        val current = checkNotNull(window) { "No current listening window." }
        require(text.isNotBlank() && text.length <= 512)
        return FinalUtterance(UUID.randomUUID().toString(), text, current)
    }
    override fun close() { window = null }
}
