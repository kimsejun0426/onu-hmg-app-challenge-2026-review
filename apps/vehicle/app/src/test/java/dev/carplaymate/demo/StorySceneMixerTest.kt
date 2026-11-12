package dev.carplaymate.demo

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

/** Synthetic PCM only: verifies bounded parsing, mixing, and cancellation, not listening quality. */
class StorySceneMixerTest {
    private data class Encoded(val audio: BundledAudio, val bytes: ByteArray)

    private fun encoded(
        id: String,
        samples: ShortArray,
        rate: Int = 48_000,
        declaredMillis: Long = (samples.size * 1000.0 / rate).roundToLong().coerceAtLeast(1),
    ): Encoded = asset(id, wav(samples, rate), declaredMillis)

    private fun asset(id: String, bytes: ByteArray, millis: Long) = Encoded(
        BundledAudio(id, "story_audio/" + id + ".wav", BundledNarration.sha256(bytes), millis), bytes,
    )

    private fun quietCue() = encoded("cue", ShortArray(4800))
    private fun voice() = encoded("voice", ShortArray(48_000) { 4000 })
    private fun mix(cue: Encoded = quietCue(), narration: Encoded = voice()) =
        StorySceneMixer.mix(cue.audio, cue.bytes, narration.audio, narration.bytes)

    private fun rejectVoice(bytes: ByteArray, millis: Long = 1000) {
        assertThrows(IllegalArgumentException::class.java) { mix(narration = asset("voice", bytes, millis)) }
    }

    @Test fun rejectsChangedBytesEvenWhenWavAndDurationAreOtherwiseValid() {
        val original = voice()
        val changed = original.bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val cue = quietCue()
        val error = assertThrows(IllegalArgumentException::class.java) {
            StorySceneMixer.mix(cue.audio, cue.bytes, original.audio, changed)
        }
        assertTrue(error.message.orEmpty().contains("hash mismatch"))
    }

    @Test fun rejectsOverflowTruncationDuplicateChunksAndMissingOddPadding() {
        val valid = voice().bytes
        rejectVoice(valid.copyOf().also { putU32(it, 40, 0xffff_ffffL) })
        rejectVoice(valid.copyOf(valid.size - 1))
        rejectVoice(riff(chunk("fmt ", format()), chunk("fmt ", format()), chunk("data", pcm(ShortArray(48_000) { 4000 }))))
        rejectVoice(riff(chunk("fmt ", format()), chunk("data", pcm(ShortArray(48_000) { 4000 })),
            chunk("data", pcm(ShortArray(48_000) { 4000 }))))
        rejectVoice(riff(chunk("fmt ", format()), chunk("data", pcm(ShortArray(48_000) { 4000 })),
            chunk("JUNK", byteArrayOf(1), pad = false)))
        rejectVoice(riff(chunk("data", pcm(ShortArray(48_000) { 4000 })), chunk("fmt ", format())))
    }

    @Test fun rejectsUnsupportedOrInconsistentPcmBeforeDecodingSamples() {
        val valid = voice().bytes
        listOf(
            valid.copyOf().also { putU16(it, 20, 3) }, // Floating point.
            valid.copyOf().also { putU16(it, 22, 2) }, // Stereo.
            valid.copyOf().also { putU32(it, 24, 44_100) },
            valid.copyOf().also { putU32(it, 28, 1) },
            valid.copyOf().also { putU16(it, 32, 4) },
            valid.copyOf().also { putU16(it, 34, 24) },
            riff(chunk("fmt ", format()), chunk("data", byteArrayOf(1))),
            riff(chunk("fmt ", format()), chunk("data", byteArrayOf())),
        ).forEach { rejectVoice(it) }
    }

    @Test fun enforcesByteDurationAndActualCueLimitsWithoutTrustingTheManifest() {
        val tooLarge = ByteArray(StorySceneMixer.MAX_FILE_BYTES + 1)
        val cue = quietCue()
        val error = assertThrows(IllegalArgumentException::class.java) {
            StorySceneMixer.mix(cue.audio, cue.bytes,
                BundledAudio("voice", "story_audio/voice.wav", "0".repeat(64), 1), tooLarge)
        }
        assertTrue(error.message.orEmpty().contains("byte limit"))
        val goodVoice = voice()
        assertThrows(IllegalArgumentException::class.java) {
            mix(narration = goodVoice.copy(audio = goodVoice.audio.copy(durationMillis = 60_001)))
        }
        // Declared 3 s is valid, and the 1 ms difference is within manifest tolerance:
        // the actual cue ceiling must still reject it.
        assertThrows(IllegalArgumentException::class.java) {
            mix(cue = encoded("cue", ShortArray(144_048), declaredMillis = 3000))
        }
        mix(narration = goodVoice.copy(audio = goodVoice.audio.copy(durationMillis = 1002)))
        assertThrows(IllegalArgumentException::class.java) {
            mix(narration = goodVoice.copy(audio = goodVoice.audio.copy(durationMillis = 1003)))
        }
    }

    @Test fun safelySkipsBoundedMetadataWithoutChangingTheDecodedVoice() {
        val samples = ShortArray(48_000) { if (it % 2 == 0) 1000 else -1000 }
        val ordinary = mix(narration = encoded("voice", samples))
        val withMetadata = riff(
            chunk("JUNK", byteArrayOf(1, 2, 3)),
            chunk("fmt ", format()),
            chunk("LIST", "INFO".toByteArray(Charsets.US_ASCII)),
            chunk("data", pcm(samples)),
        )
        val result = mix(narration = asset("voice", withMetadata, 1000))
        assertArrayEquals(ordinary.samples, result.samples)
        assertEquals(ordinary.speechStartFrame, result.speechStartFrame)
        assertEquals(ordinary.speechEndFrame, result.speechEndFrame)
    }

    @Test fun resamples24kLinearlyAndRetainsDurationWithoutRepeatingAudio() {
        val samples = ShortArray(240)
        samples[100] = 2000
        samples[101] = 4000
        val result = mix(narration = encoded("voice", samples, 24_000))
        assertEquals(48_000, result.sampleRate)
        assertEquals(9600 + 480, result.samples.size)
        val offset = result.narrationOffsetFrame
        assertEquals(2000, result.samples[offset + 200].toInt())
        assertEquals(3000, result.samples[offset + 201].toInt())
        assertEquals(4000, result.samples[offset + 202].toInt())
        assertEquals(2000, result.samples[offset + 203].toInt())
        assertEquals(0, result.samples[offset + 204].toInt())
        val constant24 = mix(narration = encoded("voice", ShortArray(2400) { 2000 }, 24_000))
        val constant48 = mix(narration = encoded("voice", ShortArray(4800) { 2000 }))
        assertArrayEquals(constant48.samples, constant24.samples)
        assertEquals(9600 + 4800, constant24.samples.size)
    }

    @Test fun overlapsTheCueWithSpeechAndDucksItsRmsWithoutLoopingTheTail() {
        val result = mix(
            cue = encoded("cue", ShortArray(96_000) { 12_000 }),
            narration = encoded("voice", ShortArray(48_000) { 4000 }),
        )
        assertEquals(96_000, result.samples.size) // Full cue tail is retained, with no repetition.
        assertEquals(9600, result.speechStartFrame)
        assertEquals(57_600, result.speechEndFrame)
        assertEquals(1.0, result.voiceGain, 0.0)
        assertTrue(result.samples[3840] > 10_000) // Foreground cue precedes narration.
        val differences = (24_000 until 48_000).map { result.samples[it].toInt() - 4000 }
        assertTrue(differences.all { it > 0 }) // Real overlap, not serial playback or a muted cue.
        val cueRms = sqrt(differences.sumOf { it.toDouble() * it } / differences.size)
        assertTrue(cueRms <= 4000 * 0.12589254117941673 + 1.0) // One quantization step.
        assertTrue(result.cueGainDuringSpeech in 0.0..0.35)
        assertTrue(result.samples[72_000] > 0) // Cue-only tail after speech has stopped.
        assertEquals(0, result.samples.last().toInt())
    }

    @Test fun authoredCueProfilesPreserveTheirIdentityBeforeSpeechWithoutStretchingVoice() {
        val voiceSamples = ShortArray(144_000) { if (it in 7200 until 136_800) 4000 else 0 }
        val narration = encoded("voice", voiceSamples)
        listOf("cue_dino_arrival" to 1000, "cue_discovery" to 400,
            "cue_forest" to 200, "cue_stream" to 200, "unknown_cue" to 200).forEach { (id, leadMillis) ->
            val result = mix(cue = encoded(id, ShortArray(96_000) { 6000 }), narration = narration)
            val offset = leadMillis * 48 - 1440 // Move 30 ms of the 150 ms quiet prefix under the cue.
            assertEquals(id, offset, result.narrationOffsetFrame)
            assertEquals(offset + 7200, result.speechStartFrame)
            assertEquals(offset + 136_800, result.speechEndFrame)
            assertEquals(offset + voiceSamples.size, result.samples.size)
            assertEquals(1.0, result.voiceGain, 0.0)
            val identityStart = when (id) { "cue_dino_arrival" -> 900; "cue_discovery" -> 200; else -> 80 } * 48
            val identityEnd = when (id) { "cue_dino_arrival" -> 1000; "cue_discovery" -> 300; else -> 180 } * 48
            assertTrue("$id retains its identifying window before ducking",
                (identityStart until identityEnd).all { result.samples[it].toInt() == 6000 })
            // After the cue ends, every original voice sample and its quiet tail remain aligned.
            (96_000 until result.samples.size).forEach { frame ->
                assertEquals(voiceSamples[frame - offset], result.samples[frame])
            }
        }
    }

    @Test fun additionalQuietMovesTheWholeNarrationEarlierWithoutLosingPcmOrCueIdentity() {
        // Different quiet prefixes must reach the same spoken onset after discovery's lead.
        for (quietMillis in listOf(150, 420, 480)) {
            val first = quietMillis * 48
            val samples = ShortArray(144_000) { frame ->
                when {
                    frame < first -> if (frame % 2 == 0) 15 else -15
                    frame < 139_200 -> 4000
                    else -> -12 // A real quiet tail must also survive unchanged.
                }
            }
            val source = encoded("voice", samples)
            val silentCue = encoded("cue_discovery", ShortArray(38_400))
            val isolated = mix(cue = silentCue, narration = source)
            val offset = isolated.narrationOffsetFrame
            assertEquals(24_960, isolated.speechStartFrame) // 400 ms lead + 120 ms quiet.
            assertEquals(24_960 - first, offset)
            assertEquals(offset + samples.size, isolated.samples.size)
            assertTrue(isolated.samples.take(offset).all { it.toInt() == 0 })
            assertArrayEquals(samples, isolated.samples.copyOfRange(offset, offset + samples.size))

            val audible = mix(cue = encoded("cue_discovery", ShortArray(38_400) { 6000 }), narration = source)
            assertEquals(isolated.speechStartFrame, audible.speechStartFrame)
            assertEquals(1.0, audible.voiceGain, 0.0)
            // Quiet narration can already be playing here. Remove its known contribution
            // and verify that the cue's entire identifying window is still at original gain.
            for (frame in 9600 until 14_400) {
                val voiceFrame = frame - offset
                val originalVoice = if (voiceFrame in samples.indices) samples[voiceFrame].toInt() else 0
                assertEquals(6000, audible.samples[frame].toInt() - originalVoice)
            }
            val overlap = (24_960 until 38_400).map { audible.samples[it].toInt() - 4000 }
            assertTrue(overlap.any { it != 0 })
            val cueRms = sqrt(overlap.sumOf { it.toDouble() * it } / overlap.size)
            assertTrue(cueRms <= 4000 * 0.12589254117941673 + 1.0)
            for (frame in 38_400 until audible.samples.size) {
                assertEquals(samples[frame - offset], audible.samples[frame])
            }
        }
    }

    @Test fun immediateAndVeryLateSpeechKeepIdentityAllPcmAndFiniteOutput() {
        // End speech before the cue's final 350 ms fade: the cue is constant during
        // this overlap, so its RMS limit also bounds the first-frame zero-ramp check.
        val immediate = mix(cue = encoded("cue_dino_arrival", ShortArray(96_000) { 6000 }),
            narration = encoded("voice", ShortArray(24_000) { 4000 }))
        assertEquals(48_000, immediate.speechStartFrame)
        assertEquals(72_000, immediate.speechEndFrame)
        assertEquals(96_000, immediate.samples.size) // Preserve the complete cue tail.
        assertTrue((43_200 until 48_000).all { immediate.samples[it].toInt() == 6000 })
        assertTrue(immediate.samples[48_000].toInt() - 4000 in 1..505) // Zero-length duck ramp.

        // The complete quiet prefix can exceed the desired onset: never use a negative
        // offset or remove PCM just to force alignment with a short cue.
        val samples = ShortArray(48_000) { if (it < 33_600) 15 else 4000 }
        val late = mix(cue = encoded("cue_discovery", ShortArray(38_400)),
            narration = encoded("voice", samples))
        assertEquals(0, late.narrationOffsetFrame)
        assertEquals(33_600, late.speechStartFrame)
        assertEquals(samples.size, late.samples.size)
        assertArrayEquals(samples, late.samples)
    }

    @Test fun longerProfileLeadsStillDuckBeforeImmediateSpeechAndKeepTheFullCueTail() {
        listOf("cue_dino_arrival" to 1000, "cue_discovery" to 400).forEach { (id, leadMillis) ->
            val result = mix(cue = encoded(id, ShortArray(96_000) { 12_000 }),
                narration = encoded("voice", ShortArray(9600) { 4000 }))
            val offset = leadMillis * 48
            assertEquals(offset, result.speechStartFrame)
            assertEquals(offset + 9600, result.speechEndFrame)
            assertEquals(96_000, result.samples.size)
            val overlap = (offset until offset + 9600).map { result.samples[it].toInt() - 4000 }
            assertTrue(overlap.all { it in 1..505 }) // Full -18 dB duck is already active at the first word.
            assertTrue(result.samples[72_000] > 0) // The remaining cue still completes before acknowledgement.
            assertEquals(0, result.samples.last().toInt())
        }
    }

    @Test fun maximumNarrationRetainsAllSixtySecondsAfterTheLongestLead() {
        val narration = encoded("voice", ShortArray(60 * 48_000) { 4000 })
        val cue = encoded("cue_dino_arrival", ShortArray(48_000) { 1000 })
        val result = mix(cue, narration)
        assertEquals(48_000, result.narrationOffsetFrame)
        assertEquals(61 * 48_000, result.samples.size)
        assertEquals(61 * 48_000, result.speechEndFrame)
        assertEquals(4000, result.samples.last().toInt())
        assertThrows(IllegalArgumentException::class.java) {
            mix(cue, narration.copy(audio = narration.audio.copy(durationMillis = 60_001)))
        }
    }

    @Test fun capsCueGainAndOnlyUsesOneLinearGainToPreventPeakClipping() {
        val capped = mix(
            cue = encoded("cue", ShortArray(48_000) { 1000 }),
            narration = encoded("voice", ShortArray(96_000) { 20_000 }),
        )
        assertEquals(0.35, capped.cueGainDuringSpeech, 1e-12)
        assertEquals(1.0, capped.voiceGain, 0.0)

        val samples = ShortArray(96_000) { if (it < 48_000) 30_000 else -30_000 }
        val loud = mix(cue = encoded("cue", ShortArray(24_000) { 30_000 }),
            narration = encoded("voice", samples))
        assertTrue(loud.voiceGain > 0 && loud.voiceGain < 1)
        assertTrue(loud.samples.all { abs(it.toInt()) <= 26_028 })
        assertEquals((30_000 * loud.voiceGain).roundToInt(), loud.samples[48_000].toInt())
        assertEquals((-30_000 * loud.voiceGain).roundToInt(), loud.samples[84_000].toInt())
        assertTrue(loud.cueGainDuringSpeech / loud.voiceGain <= 0.35)
    }

    @Test fun reportsActualSpeechBoundsAndPreservesQuietPadsAndCueOnlyTail() {
        val samples = ShortArray(48_000) { if (it in 5760 until 40_320) 3000 else 0 }
        val result = mix(cue = encoded("cue", ShortArray(96_000) { 5000 }),
            narration = encoded("voice", samples))
        assertEquals(9600, result.narrationOffsetFrame)
        assertEquals(9600 + 5760, result.speechStartFrame)
        assertEquals(9600 + 40_320, result.speechEndFrame)
        assertEquals(96_000, result.samples.size)
    }

    @Test fun silentNarrationCannotAcknowledgeAQuestionWithOnlyTheCue() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            mix(cue = encoded("cue", ShortArray(48_000) { 5000 }),
                narration = encoded("voice", ShortArray(48_000)))
        }
        assertTrue(error.message.orEmpty().contains("no usable speech"))
    }

    @Test fun interruptedWorkThrowsWithoutClearingTheOwnersCancellationFlag() {
        val cue = quietCue()
        val narration = voice()
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) {
                StorySceneMixer.mix(cue.audio, cue.bytes, narration.audio, narration.bytes)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // This test owns its injected cancellation.
        }
    }

    @Test fun standaloneNarrationPreservesEveryNativeSampleRateAndQuietEdge() {
        for (rate in listOf(24_000, 48_000)) {
            val samples = ShortArray(rate) { index ->
                when {
                    index < rate / 4 -> 184 // Below the same -45 dBFS speech bound.
                    index < rate * 3 / 4 -> if (index % 2 == 0) 30_000 else -30_000
                    else -> -184
                }
            }
            val source = encoded("voice", samples, rate)
            val result = StorySceneMixer.narration(source.audio, source.bytes)
            assertEquals(rate, result.sampleRate)
            assertArrayEquals(samples, result.samples) // No gain, resampling, trim or quiet-edge removal.
            assertEquals(rate / 4, result.speechStartFrame)
            assertEquals(rate * 3 / 4, result.speechEndFrame)
            assertEquals(0, result.narrationOffsetFrame)
            assertEquals(1.0, result.voiceGain, 0.0)
            assertEquals(0.0, result.cueGainDuringSpeech, 0.0)
        }
    }

    @Test fun standaloneSilentNarrationFailsWithoutProducingAPlayableAcknowledgement() {
        val source = encoded("voice", ShortArray(24_000), 24_000)
        val error = assertThrows(IllegalArgumentException::class.java) {
            StorySceneMixer.narration(source.audio, source.bytes)
        }
        assertTrue(error.message.orEmpty().contains("no usable speech"))
    }

    @Test fun standaloneCannotBypassAssetHashFormatOrDurationValidation() {
        val source = voice()
        assertThrows(IllegalArgumentException::class.java) {
            StorySceneMixer.narration(source.audio.copy(sha256 = "0".repeat(64)), source.bytes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorySceneMixer.narration(source.audio.copy(durationMillis = 60_001), source.bytes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorySceneMixer.narration(source.audio.copy(durationMillis = 1003), source.bytes)
        }
        val malformed = source.bytes.copyOf().also { putU16(it, 22, 2) }
        assertThrows(IllegalArgumentException::class.java) {
            StorySceneMixer.narration(source.audio.copy(sha256 = BundledNarration.sha256(malformed)), malformed)
        }
    }

    @Test fun standalonePreparationHonorsCancellationBeforeReturningNativePcm() {
        val source = voice()
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) {
                StorySceneMixer.narration(source.audio, source.bytes)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private fun wav(samples: ShortArray, rate: Int): ByteArray =
        riff(chunk("fmt ", format(rate)), chunk("data", pcm(samples)))

    private fun format(rate: Int = 48_000): ByteArray = ByteArray(16).also {
        putU16(it, 0, 1)
        putU16(it, 2, 1)
        putU32(it, 4, rate.toLong())
        putU32(it, 8, rate.toLong() * 2)
        putU16(it, 12, 2)
        putU16(it, 14, 16)
    }

    private fun pcm(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also {
        for (index in samples.indices) putU16(it, index * 2, samples[index].toInt())
    }

    private fun chunk(name: String, bytes: ByteArray, pad: Boolean = true): ByteArray {
        val result = ByteArray(8 + bytes.size + if (pad) bytes.size % 2 else 0)
        name.toByteArray(Charsets.US_ASCII).copyInto(result)
        putU32(result, 4, bytes.size.toLong())
        bytes.copyInto(result, 8)
        return result
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray(Charsets.US_ASCII))
        chunks.forEach { body.write(it) }
        return ByteArray(8 + body.size()).also {
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(it)
            putU32(it, 4, body.size().toLong())
            body.toByteArray().copyInto(it, 8)
        }
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0..3) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }
}