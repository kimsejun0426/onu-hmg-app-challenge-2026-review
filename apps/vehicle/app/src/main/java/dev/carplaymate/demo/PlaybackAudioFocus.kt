package dev.carplaymate.demo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** One output plan owns this lease, including local cues and narrated clips. */
internal interface PlaybackAudioFocus {
    fun acquire(onLost: () -> Unit): Boolean
    fun release()
}

internal fun storyAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_GAME)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

internal class AndroidPlaybackAudioFocus(context: Context) : PlaybackAudioFocus {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var active: AudioFocusRequest? = null

    override fun acquire(onLost: () -> Unit): Boolean {
        release()
        val manager = audio ?: return false
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(storyAudioAttributes())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (active === request && change != AudioManager.AUDIOFOCUS_GAIN) onLost()
            }, handler)
            .build()
        active = request
        val granted = runCatching {
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
        if (!granted) release()
        return granted
    }

    override fun release() {
        val previous = active
        active = null
        if (previous != null) runCatching { audio?.abandonAudioFocusRequest(previous) }
    }
}
