package dev.carplaymate.demo

import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit synthetic fixture collection, excluded from the ordinary smoke class list.
 * Enable with -e class dev.carplaymate.demo.AuthoredPcmFixtureTest
 * -e authoredPcmFixtureTests true. No text argument, microphone, speak(), or audio focus is used.
 *
 * Android's File overload is asynchronous: onDone guarantees that file output is written.
 * https://developer.android.com/reference/android/speech/tts/TextToSpeech#synthesizeToFile(java.lang.CharSequence,android.os.Bundle,java.io.File,java.lang.String)
 * https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener#onDone(java.lang.String)
 * https://developer.android.com/reference/android/speech/tts/Voice
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AuthoredPcmFixtureTest {
    @Test(timeout = 90_000)
    fun installedOfflineKoreanVoiceProducesOnlyTheFixedAuthoredPcmFixture() {
        assumeTrue(
            "Explicitly opt in on the prepared ordinary Android AVD",
            InstrumentationRegistry.getArguments().getString("authoredPcmFixtureTests") == "true",
        )
        assertNotSame(Looper.getMainLooper(), Looper.myLooper())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val runId = UUID.randomUUID().toString()
        val utteranceId = "onu-authored-pcm-$runId"
        val cacheRoot = context.cacheDir.canonicalFile
        val file = File.createTempFile("onu-authored-pcm-", ".wav", cacheRoot)
        val holder = AtomicReference<TextToSpeech?>()
        val closed = AtomicBoolean(false)
        val initialized = CountDownLatch(1)
        val initStatus = AtomicInteger(TextToSpeech.ERROR)
        val finished = CountDownLatch(1)
        val terminal = AtomicReference<String?>()
        val callbackFormat = AtomicReference<SynthesisFormat?>()
        val callbackBytes = AtomicLong()
        var primaryFailure: Throwable? = null
        val result: Pair<ByteArray, JSONObject>

        fun finish(id: String?, outcome: String) {
            if (!closed.get() && id == utteranceId && terminal.compareAndSet(null, outcome)) {
                finished.countDown()
            }
        }

        try {
            assertEquals("Use exactly one private cache file", cacheRoot, file.canonicalFile.parentFile)
            onMain {
                check(!closed.get())
                val tts = TextToSpeech(context) { status ->
                    if (!closed.get()) {
                        initStatus.set(status)
                        initialized.countDown()
                    }
                }
                holder.set(tts)
                // A constructor returning after the bounded main-thread wait must not leak an engine.
                if (closed.get()) {
                    holder.compareAndSet(tts, null)
                    try { tts.stop() } finally { tts.shutdown() }
                }
            }
            assertTrue("TTS initialization timed out", initialized.await(15, TimeUnit.SECONDS))
            assertEquals("TTS initialization failed", TextToSpeech.SUCCESS, initStatus.get())

            val voiceMetadata = onMain {
                check(!closed.get())
                val tts = checkNotNull(holder.get())
                // Do not call setLanguage: selecting missing voice data can trigger a download.
                val voices = tts.voices.orEmpty().filter(::installedOfflineKorean)
                val voice = voices.sortedWith(compareBy<Voice>(
                    { if (it.locale.country == "KR") 0 else 1 }, { it.name },
                )).firstOrNull() ?: throw AssertionError("Install an offline Korean voice before this opt-in test")
                assertEquals(TextToSpeech.SUCCESS, tts.setVoice(voice))
                val actual = checkNotNull(tts.voice)
                assertEquals("The engine must select the requested installed voice", voice.name, actual.name)
                assertTrue("Reject network or uninstalled voice fallback", installedOfflineKorean(actual))
                assertEquals(TextToSpeech.SUCCESS, tts.setSpeechRate(1f))
                assertEquals(TextToSpeech.SUCCESS, tts.setPitch(1f))
                assertEquals(TextToSpeech.SUCCESS, tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit // File start, not audible sound.
                        override fun onDone(utteranceId: String?) = finish(utteranceId, "done")
                        @Deprecated("Required compatibility callback")
                        override fun onError(utteranceId: String?) = finish(utteranceId, "error")
                        override fun onError(utteranceId: String?, errorCode: Int) =
                            finish(utteranceId, "error_code_$errorCode")
                        override fun onStop(utteranceId: String?, interrupted: Boolean) =
                            finish(utteranceId, "stopped")
                        override fun onBeginSynthesis(
                            id: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int,
                        ) {
                            if (!closed.get() && id == utteranceId) {
                                callbackFormat.set(SynthesisFormat(sampleRateInHz, audioFormat, channelCount))
                            }
                        }
                        override fun onAudioAvailable(id: String?, audio: ByteArray?) {
                            if (!closed.get() && id == utteranceId && audio != null &&
                                callbackBytes.addAndGet(audio.size.toLong()) > MAX_WAV_BYTES) {
                                finish(id, "audio_size_limit")
                            }
                        }
                    },
                ))
                JSONObject()
                    .put("engine", tts.defaultEngine)
                    .put("name", actual.name)
                    .put("locale", actual.locale.toLanguageTag())
                    .put("network_required", actual.isNetworkConnectionRequired)
                    .put("not_installed", actual.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
            }

            val start = SystemClock.elapsedRealtime()
            onMain {
                check(!closed.get())
                assertEquals("File synthesis was not queued", TextToSpeech.SUCCESS,
                    checkNotNull(holder.get()).synthesizeToFile(FIXED_TEXT, Bundle(), file, utteranceId))
            }
            val deadline = start + SYNTHESIS_TIMEOUT_MS
            while (!finished.await(100, TimeUnit.MILLISECONDS)) {
                assertTrue("Synthetic WAV exceeded the size cap", file.length() <= MAX_WAV_BYTES)
                assertTrue("File synthesis timed out", SystemClock.elapsedRealtime() < deadline)
            }
            assertEquals("File synthesis did not complete successfully", "done", terminal.get())
            assertTrue("Synthetic WAV exceeded the size cap", file.length() in 12..MAX_WAV_BYTES)
            val bytes = file.readBytes() // Only after onDone; the engine has finished writing this file.
            val wav = validateWav(bytes)
            callbackFormat.get()?.let { format ->
                assertEquals(AudioFormat.ENCODING_PCM_16BIT, format.encoding)
                assertEquals(wav.getInt("sample_rate_hz"), format.sampleRate)
                assertEquals(wav.getInt("channels"), format.channels)
            }
            result = bytes to JSONObject()
                .put("schema_version", 1)
                .put("status", "passed")
                .put("run_id", runId)
                .put("fixed_text", FIXED_TEXT)
                .put("synthetic", true)
                .put("actual_mic", false)
                .put("network_tts_requested", false)
                .put("speaker_playback_requested", false)
                .put("source", "installed_offline_android_tts_synthesize_to_file")
                .put("api_level", Build.VERSION.SDK_INT)
                .put("voice", voiceMetadata)
                .put("wav", wav)
                .put("on_done_received", true)
                .put("audio_callback_bytes", callbackBytes.get())
                .put("resampled", false)
                .put("max_wav_bytes", MAX_WAV_BYTES)
                .put("max_audio_duration_ms", MAX_DURATION_MS)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closed.set(true)
            var cleanupFailure: Throwable? = null
            try {
                // Leave a timed-out cleanup queued so it can still run if the main thread recovers.
                onMain(cancelOnTimeout = false) {
                    holder.getAndSet(null)?.let { tts ->
                        try { tts.stop() } finally { tts.shutdown() }
                    }
                }
            } catch (failure: Throwable) {
                cleanupFailure = failure
            }
            try {
                assertEquals(cacheRoot, file.canonicalFile.parentFile)
                assertTrue("Private synthetic WAV cleanup failed", !file.exists() || file.delete())
            } catch (failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
            }
            cleanupFailure?.let { failure ->
                if (primaryFailure == null) throw failure else primaryFailure.addSuppressed(failure)
            }
        }

        val (bytes, metadata) = result
        metadata.put("cache_deleted", true)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val chunks = encoded.chunked(BASE64_CHUNK_CHARS)
        val sha256 = metadata.getJSONObject("wav").getString("sha256")
        // A single 1 MB WAV Base64 Bundle can exceed Binder's shared transaction limit.
        // Reassemble only a complete, ordered run and verify its WAV hash plus final JUnit success.
        chunks.forEachIndexed { index, chunk ->
            instrumentation.sendStatus(2, Bundle().apply {
                putString("authored_pcm_run_id", runId)
                putInt("authored_pcm_chunk_index", index)
                putInt("authored_pcm_chunk_count", chunks.size)
                putString("authored_pcm_wav_sha256", sha256)
                putString("authored_pcm_wav_b64_chunk", chunk)
            })
        }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("authored_pcm_status", "complete")
            putString("authored_pcm_run_id", runId)
            putInt("authored_pcm_chunk_count", chunks.size)
            putString("authored_pcm_wav_sha256", sha256)
            putString("authored_pcm_metadata_json_b64",
                Base64.encodeToString(metadata.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            putString("stream", "AUTHORED_PCM_FIXTURE_COMPLETE run=$runId sha256=$sha256 chunks=${chunks.size}\n")
        })
    }

    private fun installedOfflineKorean(voice: Voice): Boolean =
        voice.locale.language == "ko" && !voice.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()

    private fun <T> onMain(cancelOnTimeout: Boolean = true, block: () -> T): T {
        val task = FutureTask(Callable { block() })
        check(Handler(Looper.getMainLooper()).post(task))
        return try {
            task.get(10, TimeUnit.SECONDS)
        } catch (failure: TimeoutException) {
            if (cancelOnTimeout) task.cancel(false)
            throw AssertionError("Main-thread TTS operation timed out", failure)
        }
    }

    private fun validateWav(bytes: ByteArray): JSONObject {
        assertTrue("WAV size outside the fixture bounds", bytes.size.toLong() in 12..MAX_WAV_BYTES)
        fun ascii(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        fun u16(offset: Int): Int = (bytes[offset].toInt() and 255) or
            ((bytes[offset + 1].toInt() and 255) shl 8)
        fun u32(offset: Int): Long = (0..3).sumOf { (bytes[offset + it].toLong() and 255) shl (it * 8) }
        assertEquals("RIFF", ascii(0))
        assertEquals("WAVE", ascii(8))
        assertEquals("RIFF length must describe the complete file", bytes.size.toLong() - 8, u32(4))
        var offset = 12
        var fmtOffset = -1
        var dataOffset = -1
        var dataBytes = 0
        while (offset < bytes.size) {
            assertTrue("Truncated WAV chunk header", offset.toLong() + 8 <= bytes.size)
            val type = ascii(offset)
            val size = u32(offset + 4)
            val start = offset.toLong() + 8
            val end = start + size
            val paddedEnd = end + (size and 1)
            assertTrue("Truncated or oversized WAV chunk", paddedEnd <= bytes.size.toLong())
            when (type) {
                "fmt " -> {
                    assertEquals("Multiple fmt chunks are not supported", -1, fmtOffset)
                    assertTrue("Short PCM format chunk", size >= 16)
                    fmtOffset = start.toInt()
                }
                "data" -> {
                    assertEquals("Multiple data chunks are not supported", -1, dataOffset)
                    dataOffset = start.toInt()
                    dataBytes = size.toInt()
                }
            }
            offset = paddedEnd.toInt()
        }
        assertTrue("WAV must contain fmt and nonempty data", fmtOffset >= 0 && dataOffset >= 0 && dataBytes > 0)
        assertEquals("Only integer PCM is supported; do not silently convert", 1, u16(fmtOffset))
        val channels = u16(fmtOffset + 2)
        val sampleRate = u32(fmtOffset + 4)
        val byteRate = u32(fmtOffset + 8)
        val blockAlign = u16(fmtOffset + 12)
        val bits = u16(fmtOffset + 14)
        assertEquals("This fixture requires mono PCM", 1, channels)
        assertEquals("This fixture requires PCM16", 16, bits)
        assertTrue("Invalid sample rate", sampleRate in 8_000L..192_000L)
        assertEquals(channels * 2, blockAlign)
        assertEquals(sampleRate * blockAlign, byteRate)
        assertEquals("Partial PCM frame", 0, dataBytes % blockAlign)
        val frames = dataBytes.toLong() / blockAlign
        assertTrue("Synthetic audio exceeded 10 seconds", frames * 1_000 <= sampleRate * MAX_DURATION_MS)
        val pcm = bytes.copyOfRange(dataOffset, dataOffset + dataBytes)
        assertTrue("Synthetic speech must not be an entirely silent buffer", pcm.any { it != 0.toByte() })
        return JSONObject()
            .put("container", "RIFF/WAVE")
            .put("encoding", "PCM_S16LE")
            .put("bytes", bytes.size)
            .put("sha256", sha256(bytes))
            .put("channels", channels)
            .put("bits_per_sample", bits)
            .put("sample_rate_hz", sampleRate)
            .put("data_offset", dataOffset)
            .put("data_bytes", dataBytes)
            .put("pcm_sha256", sha256(pcm))
            .put("frames", frames)
            .put("duration_ms", frames * 1_000.0 / sampleRate)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class SynthesisFormat(val sampleRate: Int, val encoding: Int, val channels: Int)

    private companion object {
        const val FIXED_TEXT = "물가로 갈래"
        const val MAX_WAV_BYTES = 1_000_000L
        const val MAX_DURATION_MS = 10_000L
        const val SYNTHESIS_TIMEOUT_MS = 30_000L
        const val BASE64_CHUNK_CHARS = 16_384
    }
}
