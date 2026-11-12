package dev.carplaymate.demo

import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual bundled PCM/AudioTrack checks; no microphone, child voice or listening-quality verdict. */
@RunWith(AndroidJUnit4::class)
class AndroidScenePlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun requireExplicitRun() = assumeTrue(InstrumentationRegistry.getArguments().getString("storyAudioTests") == "true")
    private fun manifest() = context.assets.open("story_audio/manifest.json").bufferedReader(Charsets.UTF_8)
        .use { JSONObject(it.readText()) }
    private fun audio(value: JSONObject) = BundledAudio(value.getString("id"), value.getString("asset"),
        value.getString("sha256"), value.getLong("duration_ms"))
    private fun pairs(): List<Pair<BundledAudio, Pair<BundledAudio, String>>> {
        val document = manifest()
        val cues = document.getJSONArray("cues").let { values ->
            (0 until values.length()).map { audio(values.getJSONObject(it)) }.associateBy { it.id }
        }
        val clips = document.getJSONArray("clips")
        return (0 until clips.length()).map { clips.getJSONObject(it) }.filter { it.has("cue_before") }
            .map { checkNotNull(cues[it.getString("cue_before")]) to (audio(it) to it.getString("text")) }
    }

    private fun sendOpeningPreview(name: String, bytes: ByteArray) {
        val rawChunkSize = 9 * 1024 // Each encoded payload is at most 12 KiB.
        val count = (bytes.size + rawChunkSize - 1) / rawChunkSize
        val sha = BundledNarration.sha256(bytes)
        repeat(count) { index ->
            val offset = index * rawChunkSize
            val length = minOf(rawChunkSize, bytes.size - offset)
            val part = JSONObject().put("file", name).put("index", index).put("count", count)
                .put("bytes", bytes.size).put("sha256", sha)
                .put("data", Base64.encodeToString(bytes, offset, length, Base64.NO_WRAP))
            instrumentation.sendStatus(2, Bundle().apply { putString("onuOpeningPreview", part.toString()) })
        }
    }

    @Test fun everyAuthoredCuePairMixesWithHeadroomAndExportsTheActualPcm() {
        requireExplicitRun()
        val exportOpening = InstrumentationRegistry.getArguments().getString("previewOpening") == "true"
        val pairs = pairs()
        assertEquals("Preserved authored cue links", 8, pairs.size)
        assertEquals(setOf("cue_dino_arrival", "cue_forest", "cue_stream", "cue_discovery"), pairs.map { it.first.id }.toSet())
        val directory = File(context.getExternalFilesDir(null), "scene-audio-review").apply { mkdirs() }
        val evidence = JSONArray()
        pairs.forEach { (cue, narrated) ->
            val voice = narrated.first
            val cueBytes = context.assets.open(cue.asset).use { it.readBytes() }
            val voiceBytes = context.assets.open(voice.asset).use { it.readBytes() }
            val scene = StorySceneMixer.mix(cue, cueBytes, voice, voiceBytes)
            val expectedLeadMillis = when (cue.id) {
                "cue_dino_arrival" -> 1000
                "cue_discovery" -> 400
                "cue_forest", "cue_stream" -> 200
                else -> error("Unexpected authored cue: ${cue.id}")
            }
            val sourceCue = reviewPcm(cueBytes)
            val sourceVoice = reviewPcm(voiceBytes)
            assertEquals(maxOf(sourceCue.frames48, scene.narrationOffsetFrame + sourceVoice.frames48),
                scene.samples.size)
            assertEquals("Current authored sounds must not lower the voice level", 1.0, scene.voiceGain, 0.0)
            val identityStart = when (cue.id) {
                "cue_dino_arrival" -> 900
                "cue_discovery" -> 200
                else -> 80
            } * 48
            val identityEnd = when (cue.id) {
                "cue_dino_arrival" -> 1000
                "cue_discovery" -> 300
                else -> 180
            } * 48
            var sourceIdentityEnergy = 0.0
            var outputIdentityEnergy = 0.0
            for (frame in identityStart until identityEnd) {
                val original = sourceCue.at48(frame)
                val voiceFrame = frame - scene.narrationOffsetFrame
                val originalVoice = if (voiceFrame in 0 until sourceVoice.frames48) sourceVoice.at48(voiceFrame) else 0.0
                // Quiet narration may now precede its spoken onset underneath this cue.
                val actual = scene.samples[frame].toDouble() - originalVoice * scene.voiceGain
                assertEquals("${cue.id} identifying sound must be preserved before voice/ducking",
                    original * scene.voiceGain, actual, .50001)
                sourceIdentityEnergy += original * original
                outputIdentityEnergy += actual * actual / (scene.voiceGain * scene.voiceGain)
            }
            assertTrue(sourceIdentityEnergy > 0)
            val identityEnergyRatio = outputIdentityEnergy / sourceIdentityEnergy
            assertEquals(1.0, identityEnergyRatio, .01)
            var voiceEnergy = 0.0
            var activeVoiceFrames = 0
            var firstVoiceFrame = -1
            var lastVoiceFrame = -1
            for (frame in 0 until sourceVoice.frames48) {
                val sample = sourceVoice.at48(frame)
                if (abs(sample) / 32768.0 >= 0.005623413251903491) {
                    if (firstVoiceFrame < 0) firstVoiceFrame = frame
                    lastVoiceFrame = frame
                    voiceEnergy += sample * sample
                    activeVoiceFrames++
                }
            }
            assertTrue(activeVoiceFrames > 0)
            val desiredSpeechStart = expectedLeadMillis * 48 + minOf(firstVoiceFrame, 120 * 48)
            assertEquals(maxOf(0, desiredSpeechStart - firstVoiceFrame), scene.narrationOffsetFrame)
            assertEquals(scene.narrationOffsetFrame + firstVoiceFrame, scene.speechStartFrame)
            assertEquals(scene.narrationOffsetFrame + lastVoiceFrame + 1, scene.speechEndFrame)
            val voiceActiveRms = sqrt(voiceEnergy / activeVoiceFrames)
            val overlapEnd = minOf(sourceCue.frames48, scene.speechEndFrame)
            assertTrue("Authored sound must really overlap speech", overlapEnd > scene.speechStartFrame)
            var residualEnergy = 0.0
            var sourceCueEnergyDuringActiveVoice = 0.0
            var renderedCueEnergyDuringActiveVoice = 0.0
            var activeOverlapFrames = 0
            var contributingCueOverlapFrames = 0
            for (frame in scene.speechStartFrame until overlapEnd) {
                val originalVoice = sourceVoice.at48(frame - scene.narrationOffsetFrame)
                // Compare against the quantized voice-only counterfactual. Interpolation
                // rounding alone must never masquerade as a non-muted cue contribution.
                val residual = scene.samples[frame].toDouble() - (originalVoice * scene.voiceGain).roundToInt()
                residualEnergy += residual * residual
                if (abs(originalVoice) / 32768.0 >= 0.005623413251903491) {
                    val originalCue = sourceCue.at48(frame)
                    sourceCueEnergyDuringActiveVoice += originalCue * originalCue
                    renderedCueEnergyDuringActiveVoice += residual * residual
                    activeOverlapFrames++
                    if (originalCue != 0.0 && residual != 0.0) contributingCueOverlapFrames++
                }
            }
            val cueOverlapRms = sqrt(residualEnergy / (overlapEnd - scene.speechStartFrame))
            assertTrue("Actual cue overlap must stay at least 18 dB below active voice RMS",
                cueOverlapRms <= voiceActiveRms * .12589254117941673 * scene.voiceGain + 1.0)
            assertTrue("Source cue must have nonzero energy during active speech", sourceCueEnergyDuringActiveVoice > 0)
            assertTrue("Rendered cue must contribute during active speech, beyond voice quantization",
                activeOverlapFrames > 0 && contributingCueOverlapFrames > 0 && renderedCueEnergyDuringActiveVoice > 0)
            assertEquals(48000, scene.sampleRate)
            assertTrue(scene.speechStartFrame > 0 && scene.speechEndFrame > scene.speechStartFrame)
            assertTrue(scene.speechEndFrame <= scene.samples.size)
            assertTrue("One mixed scene must be shorter than serial cue plus narration",
                scene.samples.size.toLong() * 1000 < (cue.durationMillis + voice.durationMillis) * scene.sampleRate)
            val peak = scene.samples.maxOf { abs(it.toInt()) }
            assertTrue("No clipping and at least 2 dB sample-peak headroom", peak > 0 && 20 * log10(peak / 32768.0) <= -2.0)
            assertTrue(scene.voiceGain > 0 && scene.voiceGain <= 1)
            assertTrue(scene.cueGainDuringSpeech >= 0 && scene.cueGainDuringSpeech <= .35)
            assertTrue("Cue must remain audible in the lead", scene.samples.take(scene.speechStartFrame).any { it.toInt() != 0 })
            val rendered = wav(scene)
            val file = File(directory, "${voice.id}-mixed.wav").apply { writeBytes(rendered) }
            evidence.put(JSONObject().put("narration_id", voice.id).put("cue_id", cue.id)
                .put("text", narrated.second).put("narration_source_sha256", voice.sha256)
                .put("cue_source_sha256", cue.sha256).put("rendered_file", file.name)
                .put("rendered_sha256", BundledNarration.sha256(rendered))
                .put("frames", scene.samples.size).put("sample_rate", scene.sampleRate)
                .put("speech_start_frame", scene.speechStartFrame).put("speech_end_frame", scene.speechEndFrame)
                .put("narration_offset_frame", scene.narrationOffsetFrame).put("cue_lead_ms", expectedLeadMillis)
                .put("cue_identity_window_start_frame", identityStart).put("cue_identity_window_end_frame", identityEnd)
                .put("cue_identity_energy_ratio", identityEnergyRatio)
                .put("cue_overlap_rms", cueOverlapRms).put("voice_active_rms", voiceActiveRms)
                .put("source_cue_energy_during_active_voice", sourceCueEnergyDuringActiveVoice)
                .put("rendered_cue_energy_during_active_voice", renderedCueEnergyDuringActiveVoice)
                .put("active_overlap_frames", activeOverlapFrames).put("contributing_cue_overlap_frames", contributingCueOverlapFrames)
                .put("voice_gain", scene.voiceGain).put("cue_gain_during_speech", scene.cueGainDuringSpeech)
                .put("sample_peak", peak).put("source_bytes_preserved", true))
            assertEquals(cue.sha256, BundledNarration.sha256(cueBytes))
            assertEquals(voice.sha256, BundledNarration.sha256(voiceBytes))
            if (exportOpening && voice.id == "dino_3160815e826a2f33") {
                sendOpeningPreview(file.name, rendered)
                val row = evidence.getJSONObject(evidence.length() - 1)
                sendOpeningPreview("opening-manifest-row.json", row.toString(2).toByteArray(Charsets.UTF_8))
            }
        }
        File(directory, "manifest.json").writeText(JSONObject().put("schema_version", 1)
            .put("meaning", "PCM generated by production mixer from APK assets; not a microphone recording or human listening approval")
            .put("clips", evidence).toString(2), Charsets.UTF_8)
    }

    @Test fun productionMixedOutputCompletesOnceAndStoppedPlaybackNeverAcknowledges() {
        requireExplicitRun()
        val selected = pairs().first { it.first.id == "cue_dino_arrival" }
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val holder = AtomicReference<SpeechOutput>()
        val ended = CountDownLatch(1)
        val nextStarted = CountDownLatch(1)
        val completions = AtomicInteger()
        val failures = AtomicInteger()
        val starts = AtomicInteger()
        val base = NoTtsExpected()
        val interrupts = AtomicInteger()
        val lastFailure = AtomicReference<String>("none")
        val since = SystemClock.elapsedRealtime()
        fun diagnostic() = "elapsedMs=${SystemClock.elapsedRealtime() - since} starts=${starts.get()} " +
            "complete=${completions.get()} interrupted=${interrupts.get()} failure=${lastFailure.get()} ttsCalls=${base.calls.get()}"
        val plan = SpeechPlan("real-mixed", "turn", 1, listOf(SpeechSegment("story", selected.second.second, true)))
        try {
            main {
                holder.set(ComposedSpeechOutput.create(context, {
                    interrupts.incrementAndGet()
                    ended.countDown()
                    nextStarted.countDown()
                }) { _, _ -> base })
                holder.get().speak(plan, 1, { completions.incrementAndGet(); ended.countDown() },
                    { lastFailure.set(it.cause.name); failures.incrementAndGet(); ended.countDown() },
                    { starts.incrementAndGet() })
            }
            val firstEnded = ended.await(30, TimeUnit.SECONDS)
            assertTrue("Actual mixed AudioTrack must finish. ${diagnostic()}", firstEnded)
            assertEquals(diagnostic(), 0, failures.get() + interrupts.get() + base.calls.get())
            assertEquals(1, starts.get())
            assertEquals(1, completions.get())
            main {
                assertNull(checkNotNull(holder.get().narrationState).value)
                holder.get().speak(plan, 2, { completions.incrementAndGet() },
                    { lastFailure.set(it.cause.name); failures.incrementAndGet(); nextStarted.countDown() },
                    { nextStarted.countDown() })
            }
            assertTrue("Second scene speech must start", nextStarted.await(20, TimeUnit.SECONDS))
            main { holder.get().stop(); assertNull(checkNotNull(holder.get().narrationState).value) }
            // Wait beyond this clip's remaining duration to catch a late real completion callback.
            SystemClock.sleep(narratedSceneMillis(selected.second.first.durationMillis) + 500L)
            assertEquals(1, completions.get())
            assertEquals(diagnostic(), 0, failures.get() + interrupts.get() + base.calls.get())
        } finally {
            main { holder.get()?.shutdown() }
            activity.close()
        }
    }

    @Test fun firstSceneAndRepeatRequestTheSamePitchPreservingPaceOnTheNativeTrack() {
        requireExplicitRun()
        val (cue, narrated) = pairs().first { it.first.id == "cue_stream" }
        val voice = narrated.first
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val focus = AndroidPlaybackAudioFocus(context)
        val requests = mutableListOf<Pair<Float, Float>>() // One preparation worker.
        val readbacks = mutableListOf<Triple<Boolean, Float, Float>>()
        val failures = AtomicInteger()
        val player = AndroidScenePlayer(context) { track, params ->
            requests += params.speed to params.pitch
            var accepted = false
            try {
                track.playbackParams = params
                accepted = true
            } finally {
                val applied = track.playbackParams
                readbacks += Triple(accepted, applied.speed, applied.pitch)
                Log.i("OnuAudioTest", "narration sampleRate=${track.sampleRate} requested=${params.speed}/${params.pitch} " +
                    "readback=${applied.speed}/${applied.pitch}")
            }
        }
        try {
            main { assertTrue("Audio focus denied", focus.acquire { failures.incrementAndGet() }) }
            for (first in listOf(true, false)) {
                val ended = CountDownLatch(1)
                val edges = mutableListOf<Boolean>()
                val done = { ended.countDown() }
                val error = { failures.incrementAndGet(); ended.countDown() }
                main {
                    if (first) player.play(cue, voice, done, error) { edges.add(it) }
                    else player.playNarration(voice, done, error) { edges.add(it) }
                }
                assertTrue("Native track must end", ended.await(25, TimeUnit.SECONDS))
                assertEquals(0, failures.get())
                assertEquals(listOf(true, false), edges)
            }
            assertEquals(listOf(.94f to 1f, .94f to 1f), requests)
            assertEquals(2, readbacks.size)
            for ((accepted, speed, pitch) in readbacks) {
                assertEquals(if (accepted) .94f else 1f, speed, .001f)
                assertEquals(1f, pitch, .001f)
            }
        } finally {
            main { player.shutdown(); focus.release() }
            activity.close()
        }
    }

    @Test fun invalidHashAndCanceledPreparationCannotSpeakOrCompleteAndNextSceneStillWorks() {
        requireExplicitRun()
        val selected = pairs().first { it.first.id == "cue_stream" }
        val cue = selected.first
        val voice = selected.second.first
        val player = AtomicReference<AndroidScenePlayer>()
        val focus = AtomicReference<PlaybackAudioFocus>()
        val failures = AtomicInteger()
        val completions = AtomicInteger()
        val speechStarts = AtomicInteger()
        val failed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            main {
                focus.set(AndroidPlaybackAudioFocus(context))
                assertTrue(focus.get().acquire { failures.incrementAndGet() })
                player.set(AndroidScenePlayer(context))
                player.get().play(cue.copy(sha256 = "0".repeat(64)), voice,
                    { completions.incrementAndGet(); failed.countDown() },
                    { failures.incrementAndGet(); failed.countDown() }, { if (it) speechStarts.incrementAndGet() })
            }
            assertTrue(failed.await(20, TimeUnit.SECONDS))
            assertEquals(1, failures.get())
            assertEquals(0, completions.get() + speechStarts.get())
            main {
                player.get().play(cue, voice, { completions.incrementAndGet() }, { failures.incrementAndGet() },
                    { if (it) speechStarts.incrementAndGet() })
                player.get().stop() // Cancel before the main thread can accept any prepared track.
                player.get().play(cue, voice, { completions.incrementAndGet(); finished.countDown() },
                    { failures.incrementAndGet(); finished.countDown() }, { if (it) speechStarts.incrementAndGet() })
            }
            assertTrue("Fresh scene must survive canceled worker preparation", finished.await(30, TimeUnit.SECONDS))
            assertEquals(1, failures.get())
            assertEquals(1, completions.get())
            assertEquals(1, speechStarts.get())
        } finally {
            main { player.get()?.shutdown(); focus.get()?.release() }
            activity.close()
        }
    }

    @Test fun repeatedCurrentScenesExportNarrationWithoutTheirEntranceCue() {
        requireExplicitRun()
        val wanted = setOf("dino_3160815e826a2f33", "dino_350bcbfbd123f73b", "dino_e92826c9a6ad84c9")
        val selected = pairs().filter { it.second.first.id in wanted }
        assertEquals(wanted, selected.map { it.second.first.id }.toSet())
        val directory = File(context.getExternalFilesDir(null), "repeat-audio-review").apply { mkdirs() }
        val evidence = JSONArray()
        selected.forEach { (cue, narrated) ->
            val voice = narrated.first
            val source = context.assets.open(voice.asset).use { it.readBytes() }
            val original = sourcePcm(source)
            val repeated = StorySceneMixer.narration(voice, source)
            val rendered = wav(repeated)
            val actual = sourcePcm(rendered)
            assertEquals(original.first, repeated.sampleRate)
            assertArrayEquals("Repeat contains every native voice sample and no repeated cue", original.second, actual.second)
            assertEquals(0, repeated.narrationOffsetFrame)
            assertEquals(0.0, repeated.cueGainDuringSpeech, 0.0)
            assertEquals(1.0, repeated.voiceGain, 0.0)
            assertTrue(repeated.speechStartFrame > 0 && repeated.speechEndFrame < repeated.samples.size)
            val file = File(directory, "${voice.id}-repeat.wav").apply { writeBytes(rendered) }
            evidence.put(JSONObject().put("narration_id", voice.id).put("omitted_cue_id", cue.id)
                .put("text", narrated.second).put("source_asset", voice.asset)
                .put("source_sha256", voice.sha256)
                .put("source_pcm_sha256", BundledNarration.sha256(original.second))
                .put("output_pcm_sha256", BundledNarration.sha256(actual.second))
                .put("rendered_file", file.name).put("rendered_sha256", BundledNarration.sha256(rendered))
                .put("frames", repeated.samples.size).put("sample_rate", repeated.sampleRate)
                .put("speech_start_frame", repeated.speechStartFrame).put("speech_end_frame", repeated.speechEndFrame)
                .put("narration_offset_frame", 0).put("voice_gain", repeated.voiceGain)
                .put("cue_gain_during_speech", repeated.cueGainDuringSpeech).put("pcm_unchanged", true))
        }
        File(directory, "manifest.json").writeText(JSONObject().put("schema_version", 1)
            .put("meaning", "Production narration PCM used after completed same-turn cue delivery; not microphone audio or human listening approval")
            .put("clips", evidence).toString(2), Charsets.UTF_8)
    }

    private fun standalone(): List<BundledAudio> = manifest().getJSONArray("clips").let { clips ->
        (0 until clips.length()).map { clips.getJSONObject(it) }
            .filter { !it.has("cue_before") }.map(::audio)
    }

    @Test fun everyStandaloneNarrationRetainsExactNativePcmAndExportsItsVerifiedBounds() {
        requireExplicitRun()
        val clips = standalone()
        // Independent 0.46 IDs plus eight common prompts and one welcome; audio timbre may change.
        val expectedIds = setOf(
            "dino_00dbe4f2313f6ae8", "dino_08a517ef648e0b9d", "dino_09abbdcf15614e68",
            "dino_1bc65dc63e7a639e", "dino_21070815a2b7637f", "dino_2fe8f75b763cf176",
            "dino_32fcb4b6f679647b", "dino_382e0da11d641004", "dino_3c169544bd866d05",
            "dino_3c284e943b0ee75e", "dino_3d8ac55e5e46fafb", "dino_3dbb193344056c44",
            "dino_42de5c0d7a8d6365", "dino_4a318149b75c90de", "dino_4f1c5768d15dd855",
            "dino_4fdac28db0a99adb", "dino_520207372643609d", "dino_5eeb02a4b6b92c12",
            "dino_63dfd30158419890", "dino_6d1af0571fbb38aa", "dino_741013816237ecb6",
            "dino_7d91d8bc0a744ed7", "dino_823088a509da2235", "dino_838fe3e372b1270b",
            "dino_8ea2246b880d3f27", "dino_98a5f9eff626721e", "dino_9d7c579e227e5115",
            "dino_a1329bedadd84032", "dino_a2feba86cc363503", "dino_a5dae647ab4a6993",
            "dino_a70d680c2a1c7ad0", "dino_b60583bb4ad0b558", "dino_c0649c5b57a424c1",
            "dino_d1f01756b2b39712", "dino_d50f7ee068802340", "dino_e566c68f0d20b5e0",
            "dino_e7f22b727e22e3c6", "dino_ea738eaf324cb2b1", "dino_fac7a4fade3d402b",
            "dino_fcb1e1f2a1cd6567", "dino_fcbf9927c305e23a", "dino_fcd97438d8bc56b2",
            "onu_02f1703fbae8c937", "onu_2b7b75a34f51025e", "onu_592e066f5753dfcf",
            "onu_8688d8bb463f9b8e", "onu_8a2aa0dc157d243e", "onu_8f8aac8ebce45da4",
            "onu_a29b4330cfc58198", "onu_c89f2f9319ad37d2", "onu_welcome_b2",
        )
        assertEquals("Every reviewed standalone asset is present exactly once", 51, clips.size)
        assertEquals(expectedIds, clips.map { it.id }.toSet())
        val directory = File(context.getExternalFilesDir(null), "native-narration-review").apply { mkdirs() }
        val evidence = JSONArray()
        clips.forEach { voice ->
            val source = context.assets.open(voice.asset).use { it.readBytes() }
            val original = sourcePcm(source)
            val output = StorySceneMixer.narration(voice, source)
            val rendered = sourcePcm(wav(output))
            assertEquals(original.first, output.sampleRate)
            assertEquals(original.first, rendered.first)
            assertArrayEquals("Every PCM byte, including quiet edges, must remain unchanged", original.second, rendered.second)
            assertTrue(output.speechStartFrame > 0)
            assertTrue(output.speechEndFrame < output.samples.size)
            assertTrue(output.speechStartFrame < output.speechEndFrame)
            assertEquals(1.0, output.voiceGain, 0.0)
            assertEquals(0.0, output.cueGainDuringSpeech, 0.0)
            assertEquals(0, output.narrationOffsetFrame)
            val sourcePcmHash = BundledNarration.sha256(original.second)
            val outputPcmHash = BundledNarration.sha256(rendered.second)
            assertEquals(sourcePcmHash, outputPcmHash)
            evidence.put(JSONObject().put("narration_id", voice.id).put("source_asset", voice.asset)
                .put("source_sha256", voice.sha256).put("source_pcm_sha256", sourcePcmHash)
                .put("output_pcm_sha256", outputPcmHash).put("pcm_unchanged", true)
                .put("frames", output.samples.size).put("sample_rate", output.sampleRate)
                .put("speech_start_frame", output.speechStartFrame).put("speech_end_frame", output.speechEndFrame)
                .put("leading_quiet_ms", output.speechStartFrame * 1000.0 / output.sampleRate)
                .put("trailing_quiet_ms", (output.samples.size - output.speechEndFrame) * 1000.0 / output.sampleRate))
        }
        File(directory, "manifest.json").writeText(JSONObject().put("schema_version", 1)
            .put("meaning", "Native output PCM equals APK PCM byte for byte; bounds are amplitude edges, not phoneme recognition or human listening approval")
            .put("clips", evidence).toString(2), Charsets.UTF_8)
    }

    @Test fun actualStandaloneSpeechBoundsPrecedeCompletionAndStopNeverAcknowledges() {
        requireExplicitRun()
        val selected = standalone().first { it.id == "dino_b60583bb4ad0b558" }
        val pcm = StorySceneMixer.narration(selected, context.assets.open(selected.asset).use { it.readBytes() })
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val player = AtomicReference<AndroidScenePlayer>()
        val focus = AtomicReference<PlaybackAudioFocus>()
        val completed = CountDownLatch(1)
        val nextStarted = CountDownLatch(1)
        val failures = AtomicInteger()
        val completions = AtomicInteger()
        val speechStarts = AtomicInteger()
        val events = mutableListOf<String>() // Only main-thread callbacks mutate it.
        var requestedAt = 0L
        var speechAt = 0L
        var completedAt = 0L
        try {
            main {
                focus.set(AndroidPlaybackAudioFocus(context))
                assertTrue(focus.get().acquire { failures.incrementAndGet() })
                player.set(AndroidScenePlayer(context))
                requestedAt = SystemClock.elapsedRealtime()
                player.get().playNarration(selected, {
                    completions.incrementAndGet(); completedAt = SystemClock.elapsedRealtime()
                    events += "complete"; completed.countDown()
                }, { failures.incrementAndGet(); completed.countDown() }, { speaking ->
                    if (speaking) { speechStarts.incrementAndGet(); speechAt = SystemClock.elapsedRealtime() }
                    events += if (speaking) "speech-start" else "speech-end"
                    assertEquals("Speech ending alone must not acknowledge the question", 0, completions.get())
                })
            }
            assertTrue("Native narration must finish", completed.await(25, TimeUnit.SECONDS))
            assertEquals(0, failures.get())
            assertEquals(1, speechStarts.get())
            assertEquals(1, completions.get())
            assertEquals(listOf("speech-start", "speech-end", "complete"), events)
            assertTrue("Leading quiet must not animate the mouth early",
                speechAt - requestedAt >= pcm.speechStartFrame * 1000L / pcm.sampleRate - 40)
            assertTrue("Completion must retain the entire native clip including quiet tail",
                completedAt - requestedAt >= pcm.samples.size * 1000L / pcm.sampleRate - 80)
            main {
                player.get().playNarration(selected, { completions.incrementAndGet() },
                    { failures.incrementAndGet(); nextStarted.countDown() }, { if (it) nextStarted.countDown() })
            }
            assertTrue(nextStarted.await(20, TimeUnit.SECONDS))
            main { player.get().stop() }
            SystemClock.sleep(narratedSceneMillis(selected.durationMillis) + 300L)
            assertEquals(1, completions.get())
            assertEquals(0, failures.get())
        } finally {
            main { player.get()?.shutdown(); focus.get()?.release() }
            activity.close()
        }
    }

    @Test fun standaloneBadHashAndCanceledPreparationCannotLeakIntoTheNextPlayback() {
        requireExplicitRun()
        val selected = standalone().first { it.id == "dino_32fcb4b6f679647b" }
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val player = AtomicReference<AndroidScenePlayer>()
        val focus = AtomicReference<PlaybackAudioFocus>()
        val failed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failures = AtomicInteger()
        val completions = AtomicInteger()
        val starts = AtomicInteger()
        try {
            main {
                focus.set(AndroidPlaybackAudioFocus(context))
                assertTrue(focus.get().acquire { failures.incrementAndGet() })
                player.set(AndroidScenePlayer(context))
                player.get().playNarration(selected.copy(sha256 = "0".repeat(64)),
                    { completions.incrementAndGet(); failed.countDown() },
                    { failures.incrementAndGet(); failed.countDown() }, { if (it) starts.incrementAndGet() })
            }
            assertTrue(failed.await(20, TimeUnit.SECONDS))
            assertEquals(1, failures.get())
            assertEquals(0, completions.get() + starts.get())
            main {
                player.get().playNarration(selected, { completions.incrementAndGet() },
                    { failures.incrementAndGet() }, { if (it) starts.incrementAndGet() })
                player.get().stop() // Cancel before a worker-prepared track can be accepted.
                player.get().playNarration(selected, { completions.incrementAndGet(); finished.countDown() },
                    { failures.incrementAndGet(); finished.countDown() }, { if (it) starts.incrementAndGet() })
            }
            assertTrue(finished.await(25, TimeUnit.SECONDS))
            assertEquals(1, failures.get())
            assertEquals(1, completions.get())
            assertEquals(1, starts.get())
        } finally {
            main { player.get()?.shutdown(); focus.get()?.release() }
            activity.close()
        }
    }

    /** Independent source samples for measuring the actual rendered cue/voice balance. */
    private data class ReviewPcm(val rate: Int, val values: ShortArray) {
        val frames48 = values.size * (48_000 / rate)
        fun at48(frame: Int): Double {
            if (rate == 48_000) return values[frame].toDouble()
            val index = frame / 2
            return if (frame % 2 == 0) values[index].toDouble()
                else (values[index].toInt() + values[minOf(index + 1, values.lastIndex)].toInt()) / 2.0
        }
    }

    private fun reviewPcm(bytes: ByteArray): ReviewPcm {
        val (rate, data) = sourcePcm(bytes)
        require(rate == 24_000 || rate == 48_000)
        val samples = ShortArray(data.size / 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return ReviewPcm(rate, samples)
    }

    /** Independent data-chunk extraction for known APK WAVs, to compare actual PCM bytes. */
    private fun sourcePcm(bytes: ByteArray): Pair<Int, ByteArray> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var position = 12
        var rate = 0
        while (position + 8 <= bytes.size) {
            val tag = String(bytes, position, 4, Charsets.US_ASCII)
            val size = input.getInt(position + 4)
            val body = position + 8
            require(size >= 0 && body.toLong() + size <= bytes.size)
            if (tag == "fmt ") rate = input.getInt(body + 4)
            if (tag == "data") {
                require(rate > 0)
                return rate to bytes.copyOfRange(body, body + size)
            }
            position = body + size + (size and 1)
        }
        error("Expected a native PCM data chunk")
    }

    private fun wav(scene: ScenePcm): ByteArray {
        val output = ByteArrayOutputStream(scene.samples.size * 2 + 44)
        fun text(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
        fun little(value: Int, bytes: Int) { repeat(bytes) { output.write(value ushr (it * 8) and 255) } }
        text("RIFF"); little(36 + scene.samples.size * 2, 4); text("WAVEfmt "); little(16, 4)
        little(1, 2); little(1, 2); little(scene.sampleRate, 4); little(scene.sampleRate * 2, 4)
        little(2, 2); little(16, 2); text("data"); little(scene.samples.size * 2, 4)
        scene.samples.forEach { little(it.toInt(), 2) }
        return output.toByteArray()
    }

    private class NoTtsExpected : SpeechOutput {
        val calls = AtomicInteger()
        override val status = MutableStateFlow("fixture")
        override val readyState = MutableStateFlow(true)
        override fun refresh() = Unit
        override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
                           failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) {
            calls.incrementAndGet()
            failure(SpeechOutputFailure("Unexpected TTS in bundled scene"))
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
    }
}