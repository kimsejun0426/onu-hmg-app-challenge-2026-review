package dev.carplaymate.demo

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real bundled-file/MediaPlayer callbacks. No claim about audible quality, microphone, or children. */
@RunWith(AndroidJUnit4::class)
class NarrationAssetPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun requireExplicitRun() = assumeTrue(InstrumentationRegistry.getArguments().getString("storyAudioTests") == "true")
    private fun manifest(): JSONObject = context.assets.open("story_audio/manifest.json").bufferedReader(Charsets.UTF_8)
        .use { JSONObject(it.readText()) }
    private fun audio(value: JSONObject) = BundledAudio(value.getString("id"), value.getString("asset"),
        value.getString("sha256"), value.getLong("duration_ms"))

    @Test fun verifiedBundledSequenceCompletesOnceAndStoppingNextPlaybackNeverAcknowledges() {
        requireExplicitRun()
        val document = manifest()
        val clips = document.getJSONArray("clips")
        assertTrue(clips.length() > 0)
        val entry = (0 until clips.length()).map { clips.getJSONObject(it) }
            .minBy { it.getLong("duration_ms") }
        val text = entry.getString("text")
        // Check every supplied asset's bytes and uncompressed descriptor without playing every clip.
        listOf(document.getJSONArray("cues"), clips).forEach { values ->
            for (index in 0 until values.length()) {
                val spec = audio(values.getJSONObject(index))
                val bytes = context.assets.open(spec.asset).use { it.readBytes() }
                assertEquals(spec.sha256, BundledNarration.sha256(bytes))
                context.assets.openFd(spec.asset).use { assertEquals(bytes.size.toLong(), it.length) }
            }
        }
        val library = loadNarrationAssets(context)
        assertNull("Bundled manifest must validate", library.failure)
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val holder = AtomicReference<SpeechOutput>()
        val complete = CountDownLatch(1)
        val started = CountDownLatch(1)
        val completions = AtomicInteger()
        val failures = AtomicInteger()
        val starts = AtomicInteger()
        try {
            main {
                holder.set(ComposedSpeechOutput(library, AndroidClipPlayer(context),
                    AndroidPlaybackAudioFocus(context), { failures.incrementAndGet(); complete.countDown() },
                    { _, _ -> NoTtsExpected() }))
                holder.get().speak(SpeechPlan("real-local-1", "turn", 1,
                    listOf(SpeechSegment("narration", text, true))), 1,
                    { completions.incrementAndGet(); complete.countDown() },
                    { failures.incrementAndGet(); complete.countDown() },
                    { starts.incrementAndGet() })
            }
            assertTrue("Bundled output did not complete", complete.await(80, TimeUnit.SECONDS))
            assertEquals(0, failures.get())
            assertEquals(1, starts.get())
            assertEquals(1, completions.get())
            main {
                holder.get().speak(SpeechPlan("real-local-2", "turn", 1,
                    listOf(SpeechSegment("narration", text, true))), 2,
                    { completions.incrementAndGet() }, { failures.incrementAndGet(); started.countDown() },
                    { started.countDown() })
            }
            assertTrue("Second local narration did not start", started.await(20, TimeUnit.SECONDS))
            main { holder.get().stop() }
            SystemClock.sleep(800)
            assertEquals("Stopped playback must not acknowledge the plan", 1, completions.get())
            assertEquals(0, failures.get())
        } finally {
            main { holder.get()?.shutdown() }
            activity.close()
        }
    }

    @Test fun invalidHashAndDurationFailBeforePlaybackAndCannotComplete() {
        requireExplicitRun()
        val entry = manifest().getJSONArray("clips").getJSONObject(0)
        val valid = audio(entry)
        // First fails file integrity; second verifies bytes but rejects the prepared duration.
        listOf(valid.copy(sha256 = "0".repeat(64)), valid.copy(durationMillis = valid.durationMillis + 2000L))
            .forEach { invalid ->
                val failed = CountDownLatch(1)
                val failures = AtomicInteger()
                val completions = AtomicInteger()
                val starts = AtomicInteger()
                val holder = AtomicReference<AndroidClipPlayer>()
                try {
                    main {
                        holder.set(AndroidClipPlayer(context))
                        holder.get().play(invalid, { completions.incrementAndGet(); failed.countDown() },
                            { failures.incrementAndGet(); failed.countDown() }, { starts.incrementAndGet() })
                    }
                    assertTrue("Invalid asset must fail in bounded time", failed.await(20, TimeUnit.SECONDS))
                    main { holder.get().stop() }
                    SystemClock.sleep(150)
                    assertEquals(1, failures.get())
                    assertEquals(0, starts.get())
                    assertEquals(0, completions.get())
                } finally { main { holder.get()?.shutdown() } }
            }
    }

    @Test fun three48KhzSceneCuesPrepareStartAndCompleteThroughRealMediaPlayer() {
        requireExplicitRun()
        val wantedIds = listOf("cue_dino_arrival", "cue_forest", "cue_stream")
        val entries = manifest().getJSONArray("cues")
        val supplied = (0 until entries.length()).map { audio(entries.getJSONObject(it)) }
            .filter { it.id in wantedIds }
        assertEquals("All three replacement cues must be present once", wantedIds.size, supplied.size)
        assertEquals(wantedIds.toSet(), supplied.map { it.id }.toSet())
        val cues = wantedIds.map { id -> supplied.single { it.id == id } }
        // Check the files' actual media format, not a filename or a manifest claim.
        cues.forEach { cue ->
            val extractor = MediaExtractor()
            try {
                context.assets.openFd(cue.asset).use { fd ->
                    extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    assertEquals("${cue.id} must contain one audio track", 1, extractor.trackCount)
                    val format = extractor.getTrackFormat(0)
                    assertEquals("${cue.id} must be 48 kHz", 48_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    assertEquals("${cue.id} must remain mono", 1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
            } finally {
                extractor.release()
            }
        }
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val holder = AtomicReference<AndroidClipPlayer>()
        val events = CopyOnWriteArrayList<String>()
        try {
            main { holder.set(AndroidClipPlayer(context)) }
            cues.forEach { cue ->
                val finished = CountDownLatch(1)
                val starts = AtomicInteger()
                val completions = AtomicInteger()
                val failures = AtomicInteger()
                val startedAt = AtomicLong(-1L)
                val completedAt = AtomicLong(-1L)
                main {
                    holder.get().play(cue,
                        {
                            completedAt.set(SystemClock.elapsedRealtime())
                            completions.incrementAndGet()
                            events += "${cue.id}:completed"
                            finished.countDown()
                        },
                        {
                            failures.incrementAndGet()
                            events += "${cue.id}:failed"
                            finished.countDown()
                        },
                        {
                            startedAt.set(SystemClock.elapsedRealtime())
                            starts.incrementAndGet()
                            events += "${cue.id}:started"
                        })
                }
                assertTrue("${cue.id} did not finish within the playback deadline", finished.await(20, TimeUnit.SECONDS))
                assertEquals("${cue.id} failed actual MediaPlayer playback", 0, failures.get())
                assertEquals("${cue.id} must start once", 1, starts.get())
                assertEquals("${cue.id} must complete once", 1, completions.get())
                assertTrue("${cue.id} completed before its audio duration",
                    completedAt.get() - startedAt.get() >= maxOf(0L, cue.durationMillis - 750L))
            }
            main { holder.get().stop() }
            assertEquals(wantedIds.flatMap { listOf("$it:started", "$it:completed") }, events.toList())
        } finally {
            main { holder.get()?.shutdown() }
            activity.close()
        }
    }

    @Test fun forestReactionAndChosenRiverEndingPlayWithoutFallingBackToDeviceTts() {
        requireExplicitRun()
        val library = loadNarrationAssets(context)
        assertNull(library.failure)
        val bodies = listOf(
            "숲길에 바퀴 자국이 보여. 오누랑 같이 살펴보자.",
            "처음엔 물가에서 바퀴 자국을 봤어. 듣기 장치로 공룡 그림 설명을 들었지. 로봇이 알 모형을 전시대에 돌려놨어. 우리 함께 찾았네.",
        )
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val holder = AtomicReference<SpeechOutput>()
        val events = CopyOnWriteArrayList<String>()
        try {
            main {
                holder.set(ComposedSpeechOutput(library, AndroidClipPlayer(context),
                    AndroidPlaybackAudioFocus(context), { events += "interrupted" },
                    { _, _ -> NoTtsExpected() }))
            }
            bodies.forEachIndexed { index, body ->
                val done = CountDownLatch(1)
                val failures = AtomicInteger()
                val resolved = library.narration.resolve(body)
                assertTrue(resolved.none { it is NarrationPart.Text && it.text.isNotBlank() })
                main {
                    holder.get().speak(SpeechPlan("coverage-$index", "turn", index.toLong() + 1,
                        listOf(SpeechSegment("narration", body, true))), index.toLong() + 1,
                        { events += "$index:completed"; done.countDown() },
                        { failures.incrementAndGet(); done.countDown() },
                        { events += "$index:started" })
                }
                assertTrue("The added narration did not complete", done.await(60, TimeUnit.SECONDS))
                assertEquals(0, failures.get())
            }
            assertEquals(listOf("0:started", "0:completed", "1:started", "1:completed"), events.toList())
        } finally {
            main { holder.get()?.shutdown() }
            activity.close()
        }
    }

    private class NoTtsExpected : SpeechOutput {
        override val status = MutableStateFlow("bundled test")
        override val readyState = MutableStateFlow(true)
        override fun refresh() = Unit
        override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
                           failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) {
            failure(SpeechOutputFailure("Unexpected TTS in exact bundled narration test"))
        }
        override fun stop() = Unit
        override fun shutdown() { readyState.value = false }
    }
}
