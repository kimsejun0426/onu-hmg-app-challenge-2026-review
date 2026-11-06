package dev.carplaymate.demo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Keeps competing media out of a capture and pauses on interruptions; never auto-resumes. */
class InputAudioFocus(context: Context, private val onLost: () -> Unit) {
    private val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
    private var active: AudioFocusRequest? = null
    fun acquire(): Boolean {
        release()
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change ->
            if (active === request && change != AudioManager.AUDIOFOCUS_GAIN) {
                release()
                onLost()
            }
        }, Handler(Looper.getMainLooper()))
        .build()
        active = request
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) release()
        return granted
    }
    fun release() {
        val previous = active
        active = null
        if (previous != null) audio.abandonAudioFocusRequest(previous)
    }
}
