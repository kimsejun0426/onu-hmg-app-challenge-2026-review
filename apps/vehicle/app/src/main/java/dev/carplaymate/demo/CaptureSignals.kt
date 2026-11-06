package dev.carplaymate.demo

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

/** Short local, non-verbal feedback. The caller owns input audio focus and the capture lifetime. */
internal interface CaptureSignalOutput {
    fun ready()
    fun processing()
    fun stop()
    fun release()
}

internal interface CaptureFocus {
    fun acquire(): Boolean
    fun release()
}

internal class AndroidCaptureFocus(context: Context, onLost: () -> Unit) : CaptureFocus {
    private val delegate = InputAudioFocus(context, onLost)
    override fun acquire() = delegate.acquire()
    override fun release() = delegate.release()
}

internal class AndroidCaptureSignals : CaptureSignalOutput {
    private var tone: ToneGenerator? = null
    private fun play(type: Int, durationMs: Int) {
        // Missing/muted output cannot turn a recognizer result into a crash or a retry loop.
        runCatching {
            val current = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 30).also { tone = it }
            current.stopTone()
            current.startTone(type, durationMs)
        }
    }
    override fun ready() = play(ToneGenerator.TONE_PROP_BEEP, 65)
    override fun processing() = play(ToneGenerator.TONE_PROP_ACK, 65)
    override fun stop() { runCatching { tone?.stopTone() } }
    override fun release() {
        val previous = tone
        tone = null
        runCatching { previous?.stopTone() }
        runCatching { previous?.release() }
    }
}

/** One ready/end-stage signal at most; close invalidates even late SDK callbacks. */
internal class CaptureSignals(private val output: CaptureSignalOutput) {
    private var active: CaptureTicket? = null
    private var ready = false
    private var processing = false

    fun open(ticket: CaptureTicket) {
        close()
        active = ticket
    }

    fun stage(ticket: CaptureTicket, stage: CaptureStage) {
        if (active != ticket) return
        when (stage) {
            CaptureStage.LISTENING -> if (!ready && !processing) {
                ready = true
                output.ready()
            }
            CaptureStage.PROCESSING -> if (!processing) {
                processing = true
                if (ready) output.processing()
            }
        }
    }

    fun close() {
        active = null
        ready = false
        processing = false
        output.stop()
    }
    fun release() {
        close()
        output.release()
    }
}
