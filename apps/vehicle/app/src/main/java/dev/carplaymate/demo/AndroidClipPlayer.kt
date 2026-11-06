package dev.carplaymate.demo

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal interface StoryClipPlayer {
    fun play(audio: BundledAudio, complete: () -> Unit, failure: () -> Unit, started: () -> Unit)
    fun stop()
    fun shutdown()
}

/** Only APK-bundled PCM/WAV files; hash verification happens off the main thread. */
internal class AndroidClipPlayer(context: Context) : StoryClipPlayer {
    private val assets = context.applicationContext.assets
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "onu-bundled-audio").apply { isDaemon = true }
    }
    private var verification: Future<*>? = null
    private var sequence = 0L
    private var closed = false
    private var player: MediaPlayer? = null
    private val verified = mutableSetOf<String>()

    override fun play(audio: BundledAudio, complete: () -> Unit, failure: () -> Unit, started: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        stop()
        if (closed) { failure(); return }
        val request = sequence
        val cacheKey = "${audio.asset}:${audio.sha256}"
        fun prepare() {
            if (closed || request != sequence) return
            var current: MediaPlayer? = null
            try {
                val media = MediaPlayer()
                current = media
                player = media
                media.setAudioAttributes(storyAudioAttributes())
                media.setLooping(false)
                media.setVolume(1f, 1f)
                media.setOnPreparedListener {
                    if (closed || sequence != request || player !== media) return@setOnPreparedListener
                    try {
                        // A manifest must describe the actual asset within encoding/rounding tolerance.
                        require(kotlin.math.abs(media.duration.toLong() - audio.durationMillis) <= 750L)
                        media.start()
                        started()
                    } catch (_: Exception) {
                        stop()
                        failure()
                    }
                }
                media.setOnCompletionListener {
                    if (!closed && request == sequence && player === media) {
                        stop()
                        complete()
                    }
                }
                media.setOnErrorListener { _, _, _ ->
                    if (!closed && request == sequence && player === media) {
                        stop()
                        failure()
                    }
                    true
                }
                assets.openFd(audio.asset).use { fd ->
                    media.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
                media.prepareAsync()
            } catch (_: Exception) {
                if (request == sequence) { stop(); failure() }
                else runCatching { current?.release() }
            }
        }
        if (cacheKey in verified) { prepare(); return }
        verification = worker.submit {
            val valid = runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                assets.open(audio.asset).use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) return@submit
                        val read = stream.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= 12L * 1024L * 1024L)
                        digest.update(buffer, 0, read)
                    }
                }
                count > 44 && digest.digest().joinToString("") { "%02x".format(it) } == audio.sha256
            }.getOrDefault(false)
            handler.post {
                if (closed || request != sequence) return@post
                verification = null
                if (valid) { verified += cacheKey; prepare() }
                else { stop(); failure() }
            }
        }
    }

    override fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        sequence++
        verification?.cancel(true)
        verification = null
        val previous = player
        player = null
        // Detach callbacks before releasing; a queued callback also checks identity above.
        runCatching { previous?.setOnPreparedListener(null) }
        runCatching { previous?.setOnCompletionListener(null) }
        runCatching { previous?.setOnErrorListener(null) }
        runCatching { previous?.stop() }
        runCatching { previous?.release() }
    }

    override fun shutdown() {
        stop()
        closed = true
        worker.shutdownNow()
        verified.clear()
    }
}
