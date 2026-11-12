package dev.carplaymate.demo

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val NARRATED_SCENE_SPEED = 0.94f

// Conservative even on devices that decline stretching and play at 1x.
internal fun narratedSceneMillis(assetMillis: Long): Long =
    kotlin.math.ceil(assetMillis / NARRATED_SCENE_SPEED.toDouble()).toLong()

/** One sample-aligned scene; speech state excludes its cue-only lead and tail. */
internal interface StoryScenePlayer {
    fun play(cue: BundledAudio, narration: BundledAudio, complete: () -> Unit,
             failure: () -> Unit, speechState: (Boolean) -> Unit)
    fun playNarration(narration: BundledAudio, complete: () -> Unit,
                      failure: () -> Unit, speechState: (Boolean) -> Unit)
    fun stop()
    fun shutdown()
}

/**
 * Reads only validated APK assets. Hashing, PCM mixing, AudioTrack allocation and writing
 * happen on one bounded worker. The main thread owns starting, stopping and all callbacks.
 * A written buffer is not completion: its final frame must be consumed and the track released.
 */
internal class AndroidScenePlayer(
    context: Context,
    private val setPlaybackParams: (AudioTrack, PlaybackParams) -> Unit = AudioTrack::setPlaybackParams,
) : StoryScenePlayer {
    private val assets = context.applicationContext.assets
    private val handler = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(1), { task ->
            Thread(task, "onu-scene-mix").apply { isDaemon = true }
        })
    private var closed = false
    private var active: Attempt? = null

    private class Prepared(
        val track: AudioTrack,
        val frames: Int,
        val speechStartFrame: Int,
        val speechEndFrame: Int,
    )
    private class Attempt(
        val complete: () -> Unit,
        val failure: () -> Unit,
        val speechState: (Boolean) -> Unit,
        val expiresAt: Long,
    ) {
        var work: Future<*>? = null
        var prepared: Prepared? = null
        var poll: Runnable? = null
        var timeout: Runnable? = null
        var speechStarted = false
        var speechEnded = false
    }

    override fun play(cue: BundledAudio, narration: BundledAudio, complete: () -> Unit,
                      failure: () -> Unit, speechState: (Boolean) -> Unit) =
        prepare(cue, narration, complete, failure, speechState)

    override fun playNarration(narration: BundledAudio, complete: () -> Unit,
                               failure: () -> Unit, speechState: (Boolean) -> Unit) =
        prepare(null, narration, complete, failure, speechState)

    private fun prepare(cue: BundledAudio?, narration: BundledAudio, complete: () -> Unit,
                        failure: () -> Unit, speechState: (Boolean) -> Unit) {
        checkMain()
        stop()
        if (closed) { failure(); return }
        try {
            cue?.let { BundledNarration.validateAudio(it, 3_000L) }
            BundledNarration.validateAudio(narration, 60_000L)
        } catch (_: Exception) { failure(); return }
        // The entire mixed track shares one clock. Its serial durations bound any overlap.
        val playbackMillis = narratedSceneMillis((cue?.durationMillis ?: 0L) + narration.durationMillis)
        val ttl = (playbackMillis + 15_000L).coerceIn(25_000L, 120_000L)
        val request = Attempt(complete, failure, speechState, SystemClock.elapsedRealtime() + ttl)
        active = request
        request.timeout = Runnable { fail(request) }.also { handler.postDelayed(it, ttl) }
        try {
            request.work = worker.submit {
                var owned: AudioTrack? = null
                try {
                    val pcm = if (cue == null) StorySceneMixer.narration(narration, read(narration))
                        else StorySceneMixer.mix(cue, read(cue), narration, read(narration))
                    interrupted()
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(storyAudioAttributes())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(pcm.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(Math.multiplyExact(pcm.samples.size, 2))
                        .build()
                    owned = track
                    check(track.state != AudioTrack.STATE_UNINITIALIZED)
                    // This bounded, single write copies the prepared clip; it never starts playback.
                    check(track.write(pcm.samples, 0, pcm.samples.size, AudioTrack.WRITE_BLOCKING) == pcm.samples.size)
                    check(track.state == AudioTrack.STATE_INITIALIZED)
                    // Both the first scene and its cue-free repeat use the same pitch-preserving pace.
                    // Speech boundaries stay in source frames and follow the actual playback head.
                    try {
                        setPlaybackParams(track, PlaybackParams().allowDefaults()
                            .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
                            .setPitch(1.0f).setSpeed(NARRATED_SCENE_SPEED))
                    } catch (_: IllegalArgumentException) {
                        // A renderer can decline time stretching. Play at its original rate.
                    } catch (_: IllegalStateException) {
                        // Some routes reject parameters in this state. play() still checks the track.
                    }
                    interrupted()
                    val prepared = Prepared(track, pcm.samples.size, pcm.speechStartFrame, pcm.speechEndFrame)
                    // Keep local ownership until a closure has actually accepted responsibility.
                    if (handler.post { accept(request, prepared) }) owned = null
                } catch (_: InterruptedException) {
                    // stop/replacement owns notification. A canceled preparation cannot fail a newer one.
                } catch (_: Exception) {
                    handler.post { fail(request) }
                } finally {
                    owned?.let(::release)
                }
            }
        } catch (_: Exception) { fail(request) }
    }

    private fun read(audio: BundledAudio): ByteArray = assets.open(audio.asset).use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            interrupted()
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= 12L * 1024L * 1024L)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun accept(request: Attempt, prepared: Prepared) {
        checkMain()
        if (closed || active !== request) { release(prepared.track); return }
        request.work = null
        request.prepared = prepared
        if (SystemClock.elapsedRealtime() >= request.expiresAt) { fail(request); return }
        try {
            prepared.track.play()
            check(prepared.track.playState == AudioTrack.PLAYSTATE_PLAYING)
            val poll = object : Runnable {
                override fun run() {
                    if (closed || active !== request || request.prepared !== prepared) return
                    if (SystemClock.elapsedRealtime() >= request.expiresAt) { fail(request); return }
                    try {
                        val played = prepared.track.playbackHeadPosition.toLong() and 0xffffffffL
                        if (!request.speechStarted && played >= prepared.speechStartFrame) {
                            request.speechStarted = true
                            request.speechState(true)
                            if (active !== request) return
                        }
                        if (!request.speechEnded && played >= prepared.speechEndFrame) {
                            request.speechEnded = true
                            request.speechState(false)
                            if (active !== request) return
                        }
                        if (played >= prepared.frames) {
                            // Invalidate every source/callback and close the real track before input.
                            if (detach(request)) request.complete() else request.failure()
                            return
                        }
                        if (prepared.track.playState != AudioTrack.PLAYSTATE_PLAYING) { fail(request); return }
                        handler.postDelayed(this, 20L)
                    } catch (_: Exception) { fail(request) }
                }
            }
            request.poll = poll
            handler.post(poll)
        } catch (_: Exception) { fail(request) }
    }

    private fun fail(request: Attempt) {
        checkMain()
        if (active !== request) return
        detach(request)
        request.failure()
    }

    /** Return true only when the owned foreground track was released successfully. */
    private fun detach(request: Attempt): Boolean {
        if (active !== request) return false
        active = null
        request.timeout?.let(handler::removeCallbacks)
        request.poll?.let(handler::removeCallbacks)
        request.work?.cancel(true)
        request.work = null
        worker.purge() // Replaced queued jobs do not accumulate behind a slow native allocation.
        val previous = request.prepared
        request.prepared = null
        return previous == null || release(previous.track)
    }

    override fun stop() {
        checkMain()
        active?.let(::detach)
    }

    override fun shutdown() {
        checkMain()
        closed = true
        stop()
        worker.shutdownNow()
    }

    private fun release(track: AudioTrack): Boolean {
        // pause/flush discards pending output rather than draining it after an explicit stop.
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        return runCatching { track.release() }.isSuccess
    }
    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("scene canceled")
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}