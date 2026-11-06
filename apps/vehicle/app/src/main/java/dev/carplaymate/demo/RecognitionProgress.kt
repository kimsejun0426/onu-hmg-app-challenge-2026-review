package dev.carplaymate.demo

/** A service failure must not be classified as the child choosing not to answer. */
class RecognitionProgress {
    private var ready = false
    private var speechStarted = false
    private var processing = false

    fun readyForSpeech() { ready = true }
    fun beginningOfSpeech() { speechStarted = true }
    fun endOfSpeech() { processing = true }

    val waitingWithoutSpeech: Boolean get() = ready && !speechStarted && !processing
}
