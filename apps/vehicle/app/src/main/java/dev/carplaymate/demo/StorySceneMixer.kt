package dev.carplaymate.demo

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One finite, verified output. Speech bounds exclude the narration's quiet edge padding. */
internal data class ScenePcm(
    val samples: ShortArray,
    val sampleRate: Int = 48_000,
    val speechStartFrame: Int,
    val speechEndFrame: Int,
    val voiceGain: Double,
    val cueGainDuringSpeech: Double,
    val narrationOffsetFrame: Int,
)

/** APK PCM only; no Android dependencies, caching, time stretching, or audio loops. */
internal object StorySceneMixer {
    const val SAMPLE_RATE = 48_000
    const val CUE_LEAD_MILLIS = 200
    const val MAX_FILE_BYTES = 12 * 1024 * 1024
    private const val VOICE_MAX_MILLIS = 60_000L
    private const val CUE_MAX_MILLIS = 3_000L
    private const val MAX_CUE_LEAD_MILLIS = 1000
    private const val MAX_OUTPUT_FRAMES = SAMPLE_RATE * 60 + SAMPLE_RATE * MAX_CUE_LEAD_MILLIS / 1000
    private const val FADE_IN_FRAMES = SAMPLE_RATE * 60 / 1000
    private const val FADE_OUT_FRAMES = SAMPLE_RATE * 350 / 1000
    private const val DUCK_RAMP_FRAMES = SAMPLE_RATE * 120 / 1000
    private const val ACTIVE_THRESHOLD = 0.005623413251903491 // -45 dBFS
    private const val CUE_RMS_RATIO = 0.12589254117941673 // -18 dB
    private const val MAX_DUCK_GAIN = 0.35
    private const val PEAK_CEILING = 0.7943282347242815 // -2 dBFS
    private val maximumSample = floor(PEAK_CEILING * 32768.0).toInt()

    @Throws(InterruptedException::class)
    fun mix(
        cue: BundledAudio,
        cueBytes: ByteArray,
        narration: BundledAudio,
        narrationBytes: ByteArray,
    ): ScenePcm {
        cancellationPoint()
        val cuePcm = decode(cue, cueBytes, CUE_MAX_MILLIS)
        val voicePcm = decode(narration, narrationBytes, VOICE_MAX_MILLIS)
        // Keep each authored sound's identifying moment before the voice joins it.
        // Ambient and unrecognized cues retain the original short lead; no source is stretched.
        val leadMillis = when (cue.id) {
            "cue_dino_arrival" -> MAX_CUE_LEAD_MILLIS
            "cue_discovery" -> 400
            else -> CUE_LEAD_MILLIS
        }
        val leadFrames = SAMPLE_RATE * leadMillis / 1000
        val bounds = speechBounds(voicePcm.outputFrames, voicePcm::at)
        // Anchor the spoken onset, not the file's quiet prefix, to the authored cue lead.
        // Keep up to 120 ms of quiet after that lead. Earlier quiet PCM is still played in
        // full underneath the cue; neither source is trimmed, stretched, or repeated.
        val desiredSpeechStart = leadFrames + min(bounds.first, DUCK_RAMP_FRAMES)
        val narrationOffset = max(0, desiredSpeechStart - bounds.first)
        val voiceEnd = narrationOffset + voicePcm.outputFrames
        val outputFrames = max(cuePcm.outputFrames, voiceEnd)
        require(outputFrames in 1..MAX_OUTPUT_FRAMES) { "Scene exceeds its finite output limit" }

        val voiceRms = bounds.rms
        val speechStart = narrationOffset + bounds.first
        val speechEnd = narrationOffset + bounds.end
        // Duck immediately before actual speech, while preserving the identifying cue lead.
        // Immediate speech has a zero-length ramp and receives full ducking at its first frame.
        val duckEnd = speechStart
        val duckStart = max(leadFrames, duckEnd - DUCK_RAMP_FRAMES)
        val fadeIn = min(FADE_IN_FRAMES, max(1, cuePcm.outputFrames / 2))
        val fadeOut = min(FADE_OUT_FRAMES, max(1, cuePcm.outputFrames / 2))
        fun cueEnvelope(frame: Int): Double {
            val attack = smooth(frame.toDouble() / fadeIn)
            val release = smooth((cuePcm.outputFrames - 1 - frame).toDouble() / fadeOut)
            return attack * release
        }

        // Bound steady cue RMS over the actual voice overlap, including its fade envelope.
        var cueEnergy = 0.0
        var cueCount = 0
        for (frame in speechStart until min(cuePcm.outputFrames, speechEnd)) {
            periodicCancellation(frame)
            val sample = cuePcm.at(frame) * cueEnvelope(frame)
            cueEnergy += sample * sample
            cueCount++
        }
        val cueRms = if (cueCount == 0) 0.0 else sqrt(cueEnergy / cueCount)
        val duckGain = when {
            cueRms == 0.0 -> MAX_DUCK_GAIN
            else -> min(MAX_DUCK_GAIN, voiceRms * CUE_RMS_RATIO / cueRms)
        }
        fun mixedAt(frame: Int): Double {
            val voiceFrame = frame - narrationOffset
            val voice = if (voiceFrame in 0 until voicePcm.outputFrames) voicePcm.at(voiceFrame) else 0.0
            val effect = if (frame < cuePcm.outputFrames) {
                val progress = if (duckEnd == duckStart) {
                    if (frame < duckEnd) 0.0 else 1.0
                } else smooth((frame - duckStart).toDouble() / (duckEnd - duckStart))
                val gain = 1.0 + (duckGain - 1.0) * progress
                cuePcm.at(frame) * cueEnvelope(frame) * gain
            } else 0.0
            return voice + effect
        }

        var peak = 0.0
        for (frame in 0 until outputFrames) {
            periodicCancellation(frame)
            peak = max(peak, abs(mixedAt(frame)))
        }
        val ceiling = maximumSample / 32768.0
        val overallGain = if (peak > ceiling) ceiling / peak else 1.0
        cancellationPoint()
        val output = ShortArray(outputFrames)
        for (frame in output.indices) {
            periodicCancellation(frame)
            val value = (mixedAt(frame) * overallGain * 32768.0).roundToInt()
            // The first pass establishes headroom; do not hide clipping with sample-by-sample limiting.
            check(value in -maximumSample..maximumSample) { "Linear scene gain exceeded the peak ceiling" }
            output[frame] = value.toShort()
        }
        cancellationPoint()
        return ScenePcm(
            samples = output,
            speechStartFrame = speechStart,
            speechEndFrame = speechEnd,
            voiceGain = overallGain,
            cueGainDuringSpeech = duckGain * overallGain,
            narrationOffsetFrame = narrationOffset,
        )
    }

    /** Standalone narration keeps every native PCM sample, including its natural quiet edges. */
    @Throws(InterruptedException::class)
    fun narration(audio: BundledAudio, bytes: ByteArray): ScenePcm {
        cancellationPoint()
        val pcm = decode(audio, bytes, VOICE_MAX_MILLIS)
        val bounds = speechBounds(pcm.samples.size) { pcm.samples[it] / 32768.0 }
        cancellationPoint()
        return ScenePcm(pcm.samples, pcm.rate, bounds.first, bounds.end,
            voiceGain = 1.0, cueGainDuringSpeech = 0.0, narrationOffsetFrame = 0)
    }

    private data class SpeechBounds(val first: Int, val end: Int, val rms: Double)

    private fun speechBounds(frames: Int, sampleAt: (Int) -> Double): SpeechBounds {
        var energy = 0.0
        var activeCount = 0
        var first = -1
        var last = -1
        for (frame in 0 until frames) {
            periodicCancellation(frame)
            val sample = sampleAt(frame)
            if (abs(sample) >= ACTIVE_THRESHOLD) {
                if (first < 0) first = frame
                last = frame
                energy += sample * sample
                activeCount++
            }
        }
        require(activeCount > 0) { "Narration has no usable speech samples" }
        return SpeechBounds(first, last + 1, sqrt(energy / activeCount))
    }

    private class Pcm(val samples: ShortArray, val rate: Int) {
        val outputFrames: Int = samples.size * (SAMPLE_RATE / rate)
        fun at(frame: Int): Double {
            if (rate == SAMPLE_RATE) return samples[frame] / 32768.0
            val source = frame / 2
            val first = samples[source].toInt()
            // The final half-frame holds the endpoint; the source's duration is retained exactly.
            return if ((frame and 1) == 0) first / 32768.0
            else (first + samples[min(source + 1, samples.lastIndex)].toInt()) / 65536.0
        }
    }

    private fun decode(audio: BundledAudio, bytes: ByteArray, maximumMillis: Long): Pcm {
        cancellationPoint()
        BundledNarration.validateAudio(audio, maximumMillis)
        require(bytes.size in 44..MAX_FILE_BYTES) { "WAV file exceeds its byte limit" }
        val digest = MessageDigest.getInstance("SHA-256")
        var hashed = 0
        while (hashed < bytes.size) {
            cancellationPoint()
            val count = min(8192, bytes.size - hashed)
            digest.update(bytes, hashed, count)
            hashed += count
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        require(actualHash == audio.sha256) { "Bundled WAV hash mismatch" }
        require(tag(bytes, 0, "RIFF") && tag(bytes, 8, "WAVE")) { "Expected RIFF/WAVE" }
        require(u32(bytes, 4) + 8L == bytes.size.toLong()) { "RIFF size does not match the file" }

        var offset = 12L
        var rate = 0
        var formatSeen = false
        var dataStart = -1
        var dataBytes = 0
        while (offset < bytes.size.toLong()) {
            cancellationPoint()
            require(offset + 8L <= bytes.size.toLong()) { "Truncated WAV chunk header" }
            val header = offset.toInt()
            val size = u32(bytes, header + 4)
            val body = offset + 8L
            val end = body + size
            val paddedEnd = end + (size and 1L)
            require(end >= body && paddedEnd <= bytes.size.toLong()) { "WAV chunk exceeds the file" }
            when {
                tag(bytes, header, "fmt ") -> {
                    require(!formatSeen && dataStart < 0) { "Duplicate or misplaced WAV format" }
                    require(size == 16L || size == 18L) { "Unsupported PCM format chunk" }
                    val position = body.toInt()
                    require(u16(bytes, position) == 1 && u16(bytes, position + 2) == 1) { "Only mono integer PCM is accepted" }
                    val sampleRate = u32(bytes, position + 4)
                    require(sampleRate == 24_000L || sampleRate == 48_000L) { "Unsupported PCM sample rate" }
                    require(u32(bytes, position + 8) == sampleRate * 2L &&
                        u16(bytes, position + 12) == 2 && u16(bytes, position + 14) == 16) { "Inconsistent PCM16 format" }
                    if (size == 18L) require(u16(bytes, position + 16) == 0) { "Unexpected PCM extension" }
                    rate = sampleRate.toInt()
                    formatSeen = true
                }
                tag(bytes, header, "data") -> {
                    require(formatSeen && dataStart < 0) { "Missing format or duplicate PCM data" }
                    require(size > 0L && size % 2L == 0L) { "Empty or incomplete PCM frame" }
                    val count = size / 2L
                    require(count * 1000L <= maximumMillis * rate) { "Actual PCM duration exceeds its limit" }
                    require(abs(count * 1000L - audio.durationMillis * rate) <= 2L * rate) { "Manifest duration does not match PCM" }
                    dataStart = body.toInt()
                    dataBytes = size.toInt()
                }
                // Existing APK WAVs contain LIST metadata. Unknown chunks are skipped only after bounds/padding validation.
            }
            offset = paddedEnd
        }
        require(offset == bytes.size.toLong() && formatSeen && dataStart >= 0) { "Incomplete WAV structure" }
        cancellationPoint()
        val samples = ShortArray(dataBytes / 2)
        for (index in samples.indices) {
            periodicCancellation(index)
            samples[index] = u16(bytes, dataStart + index * 2).toShort()
        }
        return Pcm(samples, rate)
    }

    private fun smooth(value: Double): Double {
        val clamped = value.coerceIn(0.0, 1.0)
        return clamped * clamped * (3.0 - 2.0 * clamped)
    }

    private fun tag(bytes: ByteArray, offset: Int, expected: String): Boolean =
        expected.indices.all { (bytes[offset + it].toInt() and 0xff) == expected[it].code }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun periodicCancellation(frame: Int) {
        if ((frame and 4095) == 0) cancellationPoint()
    }

    private fun cancellationPoint() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Story scene mixing was cancelled")
    }
}