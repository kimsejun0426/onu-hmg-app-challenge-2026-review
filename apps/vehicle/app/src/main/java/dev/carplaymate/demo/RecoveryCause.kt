package dev.carplaymate.demo

/** App-defined reasons only; never inferred from a captured utterance or an exception message. */
enum class RecoveryCause {
    INPUT_PERMISSION,
    INPUT_UNAVAILABLE,
    INPUT_LANGUAGE,
    INPUT_BUSY,
    INPUT_TIMEOUT,
    INPUT_FAILED,
    AUDIO_FOCUS,
    OUTPUT_PERMISSION,
    OUTPUT_UNAVAILABLE,
    OUTPUT_LANGUAGE,
    OUTPUT_FAILED,
    CONNECTION,
    PROGRESS,
    SESSION_REPLACED,
    CONTENT_UNAVAILABLE,
    ADVENTURE_UNSUPPORTED,
    PROFILE,
}

/** Messages are authored by the adapter; family recovery UI maps cause to its own fixed copy. */
data class SpeechOutputFailure(
    val message: String,
    val cause: RecoveryCause = RecoveryCause.OUTPUT_FAILED,
)

internal data class SpeechAvailabilityFailure(val message: String, val cause: RecoveryCause)
