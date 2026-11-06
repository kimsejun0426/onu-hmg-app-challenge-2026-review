package dev.carplaymate.demo

import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.media.AudioAttributes
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fake engine callbacks and virtual deadlines; output-start tests use foreground platform focus. */
@RunWith(AndroidJUnit4::class)
class SpeechAdapterRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun drain() = instrumentation.waitForIdleSync()

    private fun ticket(generation: Long) =
        CaptureTicket(
            generation,
            CaptureContext(
                "session",
                generation,
                "turn",
                generation,
                "window-$generation",
                generation,
            ),
        )

    @Test
    fun pleosPartialResultsKeepListeningUntilTheCaptureActuallyEnds() {
        val engine = FakeInput()
        val stages = mutableListOf<CaptureStage>()
        val finals = mutableListOf<Pair<CaptureTicket, String>>()
        lateinit var input: PleosSpeechInput
        main {
            input = PleosSpeechInput({ engine }, { null })
            input.open(
                ticket(1),
                { _, stage -> stages.add(stage) },
                { capture, text -> finals.add(capture to text) },
                { _, failure -> fail(failure.message) },
            )
            engine.initialization.onSuccess()
        }
        drain()
        try {
            main {
                engine.listener!!.onReady()
                engine.listener!!.onStartedRecognition()
            }
            drain()
            assertEquals(CaptureStage.LISTENING, stages.last())
            main {
                engine.listener!!.onUpdated("합성 중간 입력", false)
                engine.listener!!.onUpdated("합성 중간 입력 둘", false)
            }
            drain()
            assertTrue(
                "Interim results must not switch off the listening indicator.",
                stages.all { it == CaptureStage.LISTENING },
            )
            assertEquals(0, engine.stops)
            assertEquals(0, engine.releases)
            assertTrue(finals.isEmpty())

            main {
                engine.listener!!.onEndedRecognition()
                engine.listener!!.onUpdated("늦은 중간 입력", false)
                engine.listener!!.onReady()
                engine.listener!!.onStartedRecognition()
                engine.listener!!.onEndedRecognition()
            }
            drain()
            assertEquals(CaptureStage.PROCESSING, stages.last())
            assertEquals(
                "An ended request cannot reopen listening or extend its grace period.",
                1,
                stages.count { it == CaptureStage.PROCESSING },
            )
            main {
                engine.listener!!.onUpdated("합성 최종 선택", true)
                engine.listener!!.onUpdated("중복 결과", true)
            }
            drain()
            assertEquals(listOf(ticket(1) to "합성 최종 선택"), finals)
            assertEquals(1, engine.stops)
            assertEquals(1, engine.releases)
        } finally {
            main { input.close() }
        }
    }

    @Test
    fun pleosCancelledCallbacksAndAnEndAfterFinalCannotChangeTheCurrentCapture() {
        val engines = mutableListOf<FakeInput>()
        val stages = mutableListOf<Pair<CaptureTicket, CaptureStage>>()
        val finals = mutableListOf<Pair<CaptureTicket, String>>()
        lateinit var input: PleosSpeechInput
        main { input = PleosSpeechInput({ FakeInput().also(engines::add) }, { null }) }
        try {
            for (generation in 1L..2L) {
                main {
                    input.open(
                        ticket(generation),
                        { capture, stage -> stages.add(capture to stage) },
                        { capture, text -> finals.add(capture to text) },
                        { _, failure -> fail(failure.message) },
                    )
                    engines.last().initialization.onSuccess()
                }
                drain()
                main { engines.last().listener!!.onReady() }
                drain()
            }
            val beforeLateCallbacks = stages.toList()
            main {
                engines[0].listener!!.onReady()
                engines[0].listener!!.onUpdated("취소된 중간 입력", false)
                engines[0].listener!!.onEndedRecognition()
                engines[0].listener!!.onUpdated("취소된 최종 입력", true)
            }
            drain()
            assertEquals(beforeLateCallbacks, stages)
            assertTrue(finals.isEmpty())
            main {
                engines[1].listener!!.onUpdated("현재 선택", true)
                engines[1].listener!!.onEndedRecognition()
                engines[1].listener!!.onUpdated("최종 뒤 중간 입력", false)
            }
            drain()
            assertEquals(listOf(ticket(2) to "현재 선택"), finals)
            assertEquals(beforeLateCallbacks, stages)
            assertEquals(1, engines[1].stops)
            assertEquals(1, engines[1].releases)
        } finally {
            main { input.close() }
        }
    }

    @Test
    fun androidMissingInitializationTimesOutAndOnlyAnExplicitSilentRetryCanRecover() {
        val engines = mutableListOf<FakeTts>()
        val clock = VirtualDeadlines()
        lateinit var output: AndroidTtsOutput
        main {
            output = fakeOutput(engines, clock)
            clock.advanceBy(9_999)
        }
        try {
            assertFalse(output.readyState.value)
            assertEquals(0, engines[0].shutdowns)
            main { clock.advanceBy(1) }
            assertFalse(output.readyState.value)
            assertEquals(RecoveryCause.OUTPUT_UNAVAILABLE, output.availabilityFailure!!.cause)
            assertEquals(1, engines[0].shutdowns)
            assertTrue(output.status.value.contains("읽으며 체험"))
            assertTrue(output.status.value.contains("다시 확인"))
            assertTrue(clock.pendingTasks.isEmpty())
            val timedOutStatus = output.status.value

            main { engines[0].initialized(TextToSpeech.SUCCESS) }
            drain()
            assertFalse("A timed-out engine must not revive readiness.", output.readyState.value)
            assertEquals(timedOutStatus, output.status.value)
            assertEquals(RecoveryCause.OUTPUT_UNAVAILABLE, output.availabilityFailure!!.cause)
            assertEquals(0, engines[0].preparations)
            assertEquals("Timeout must not implicitly construct another engine.", 1, engines.size)

            main {
                output.refresh()
                engines[0].initialized(TextToSpeech.ERROR)
                engines[1].initialized(TextToSpeech.SUCCESS)
            }
            drain()
            assertTrue(output.readyState.value)
            assertNull(output.availabilityFailure)
            assertEquals(1, engines[1].preparations)
            assertTrue(clock.pendingTasks.isEmpty())
            main {
                clock.advanceBy(30_000)
                output.stop()
                engines[1].initialized(TextToSpeech.ERROR) // Duplicate initialization is stale too.
            }
            drain()
            assertTrue(output.readyState.value)
            assertEquals(0, engines[1].shutdowns)
            assertTrue(engines.all { it.texts.isEmpty() && it.silences.isEmpty() })
        } finally {
            main { output.shutdown() }
        }
    }

    @Test
    fun androidRefreshAndShutdownFenceBothQueuedCallbacksAndOldDeadlines() {
        val engines = mutableListOf<FakeTts>()
        val clock = VirtualDeadlines()
        lateinit var output: AndroidTtsOutput
        lateinit var oldDeadline: Runnable
        main {
            output = fakeOutput(engines, clock)
            oldDeadline = clock.pendingTasks.single()
            output.refresh()
            oldDeadline.run() // Emulate a cancelled timeout already queued by the platform.
            engines[0].initialized(TextToSpeech.SUCCESS)
        }
        drain()
        try {
            assertFalse(output.readyState.value)
            assertEquals(1, engines[0].shutdowns)
            assertEquals(0, engines[0].preparations)
            assertEquals(0, engines[1].shutdowns)
            assertEquals(1, clock.pendingTasks.size)
            main {
                output.shutdown()
                engines[1].initialized(TextToSpeech.SUCCESS)
                oldDeadline.run()
                clock.advanceBy(20_000)
                output.refresh()
            }
            drain()
            assertFalse(output.readyState.value)
            assertEquals(1, engines[1].shutdowns)
            assertEquals(0, engines[1].preparations)
            assertEquals(2, engines.size)
            assertTrue(clock.pendingTasks.isEmpty())
            assertTrue(engines.all { it.texts.isEmpty() && it.silences.isEmpty() })
        } finally {
            main { output.shutdown() }
        }
    }

    @Test
    fun androidInitializationFailureOrMissingOfflineVoiceSettlesWithoutAutomaticRetry() {
        for (initResult in listOf(TextToSpeech.ERROR, TextToSpeech.SUCCESS)) {
            val engines = mutableListOf<FakeTts>()
            val clock = VirtualDeadlines()
            lateinit var output: AndroidTtsOutput
            main {
                output = fakeOutput(engines, clock)
                engines.single().canPrepareOfflineVoice = false
                engines.single().initialized(initResult)
            }
            drain()
            try {
                assertFalse(output.readyState.value)
                val expectedCause = if (initResult == TextToSpeech.SUCCESS) RecoveryCause.OUTPUT_LANGUAGE
                    else RecoveryCause.OUTPUT_UNAVAILABLE
                assertEquals(expectedCause, output.availabilityFailure!!.cause)
                assertEquals(1, engines.single().shutdowns)
                assertTrue(clock.pendingTasks.isEmpty())
                val failureStatus = output.status.value
                assertFalse(failureStatus.contains("확인 중"))
                main {
                    engines.single().initialized(TextToSpeech.SUCCESS)
                    clock.advanceBy(60_000)
                }
                drain()
                assertFalse(output.readyState.value)
                assertEquals(failureStatus, output.status.value)
                assertEquals(expectedCause, output.availabilityFailure!!.cause)
                assertEquals(1, engines.size)
                assertTrue(engines.single().texts.isEmpty())
                assertTrue(engines.single().silences.isEmpty())
            } finally {
                main { output.shutdown() }
            }
        }
    }

    @Test fun androidStartIgnoresPrerollAndOnlyFirstSpokenSegmentAnnouncesThePlan() {
        withReadyAndroidOutput { output, engines, _ ->
            val engine = engines.single()
            var starts = 0
            var completions = 0
            val failures = mutableListOf<SpeechOutputFailure>()
            main {
                output.speak(playbackPlan("spoken"), 1, { completions++ }, failures::add, {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    starts++
                })
            }
            val ids = engine.spokenIds.toList()
            assertEquals(2, ids.size)
            main {
                engine.progress.onStart(engine.silentIds.single())
                engine.progress.onStart(null)
                engine.progress.onStart("unknown-request")
            }
            drain()
            assertEquals("The 180ms silent preroll is not spoken playback.", 0, starts)
            main {
                engine.progress.onStart(ids.first())
                engine.progress.onStart(ids.first())
                engine.progress.onStart(ids.last())
            }
            drain()
            assertEquals(1, starts)
            assertEquals(0, completions)
            main {
                engine.progress.onDone(engine.silentIds.single())
                ids.forEach(engine.progress::onDone)
                engine.progress.onStart(ids.last())
            }
            drain()
            assertEquals(1, starts)
            assertEquals(1, completions)
            main {
                output.speak(playbackPlan("without-start"), 2, { completions++ }, failures::add, { starts++ })
                engine.progress.onDone(engine.silentIds.last())
                engine.spokenIds.takeLast(2).forEach(engine.progress::onDone)
            }
            drain()
            assertEquals("Completion must not depend on the optional start observation.", 2, completions)
            assertEquals(1, starts)
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun androidStartsAreFencedAcrossStopSamePlanReplacementFailureAndTimeout() {
        withReadyAndroidOutput { output, engines, clock ->
            val engine = engines.single()
            val plan = playbackPlan("reused-plan")
            var starts = 0
            var completions = 0
            val failures = mutableListOf<SpeechOutputFailure>()
            fun request() = output.speak(plan, 7, { completions++ }, failures::add, { starts++ })
            main { request() }
            val oldIds = engine.spokenIds.takeLast(2)
            main {
                engine.progress.onStart(oldIds.first()) // Already queued when stop invalidates it.
                output.stop()
            }
            drain()
            assertEquals(0, starts)
            main { request() }
            val replacedIds = engine.spokenIds.takeLast(2)
            assertTrue("Every request must own unique platform IDs.", oldIds.toSet().intersect(replacedIds.toSet()).isEmpty())
            main {
                engine.progress.onStart(oldIds.first())
                engine.progress.onStart(replacedIds.first())
                request() // Same plan and caller generation, but a new actual request.
                engine.progress.onStart(replacedIds.last())
            }
            drain()
            assertEquals(0, starts)
            val currentIds = engine.spokenIds.takeLast(2)
            main { engine.progress.onStart(currentIds.first()) }
            drain()
            assertEquals(1, starts)
            main {
                request()
                val failedId = engine.spokenIds.last()
                engine.progress.onError(failedId, TextToSpeech.ERROR)
                engine.progress.onStart(failedId)
            }
            drain()
            assertEquals(1, starts)
            assertEquals(1, failures.size)
            main {
                request()
                val timedOutId = engine.spokenIds.last()
                clock.advanceBy(120_000)
                engine.progress.onStart(timedOutId)
            }
            drain()
            assertEquals(1, starts)
            assertEquals(2, failures.size)
            assertEquals(0, completions)
        }
    }

    @Test fun androidStartFromAnOldEngineCannotAnnounceANewRequestOrSurviveShutdown() {
        withReadyAndroidOutput { output, engines, _ ->
            val oldEngine = engines.single()
            var starts = 0
            var completions = 0
            val failures = mutableListOf<SpeechOutputFailure>()
            main {
                output.speak(playbackPlan("same"), 1, { completions++ }, failures::add, { starts++ })
                oldEngine.progress.onStart(oldEngine.spokenIds.first())
                output.refresh()
                engines[1].initialized(TextToSpeech.SUCCESS)
            }
            drain()
            assertEquals(0, starts)
            val currentEngine = engines[1]
            assertTrue("A capability refresh must remain silent.", currentEngine.spokenIds.isEmpty())
            main { output.speak(playbackPlan("same"), 1, { completions++ }, failures::add, { starts++ }) }
            val currentId = currentEngine.spokenIds.first()
            main {
                // Matching text/id alone is insufficient: this callback belongs to a retired engine.
                oldEngine.progress.onStart(currentId)
            }
            drain()
            assertEquals(0, starts)
            main { currentEngine.progress.onStart(currentId) }
            drain()
            assertEquals(1, starts)
            main {
                output.shutdown()
                oldEngine.progress.onStart(currentId)
                currentEngine.progress.onStart(currentId)
                currentEngine.progress.onDone(currentId)
            }
            drain()
            assertEquals(1, starts)
            assertEquals(0, completions)
            assertTrue(failures.isEmpty())
        }
    }

    private fun playbackPlan(id: String) = SpeechPlan(id, "spoken-turn", 1,
        listOf(SpeechSegment("first", "첫 안내", true), SpeechSegment("second", "다음 안내", true)))

    private fun withReadyAndroidOutput(action: (AndroidTtsOutput, MutableList<FakeTts>, VirtualDeadlines) -> Unit) {
        // API 35 permits this production AudioManager focus request only while the app is foreground.
        // The injected engine never produces sound or requests input; no adventure is activated.
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val engines = mutableListOf<FakeTts>()
        val clock = VirtualDeadlines()
        var output: AndroidTtsOutput? = null
        try {
            main {
                output = fakeOutput(engines, clock)
                engines.single().initialized(TextToSpeech.SUCCESS)
            }
            drain()
            val actual = checkNotNull(output)
            assertTrue(actual.readyState.value)
            action(actual, engines, clock)
        } finally {
            try { main { output?.shutdown() } } finally { activity.close() }
        }
    }

    private fun fakeOutput(engines: MutableList<FakeTts>, clock: VirtualDeadlines) =
        AndroidTtsOutput(
            instrumentation.targetContext,
            { fail("Capability checks must never request playback or interruption.") },
            { _, initialized -> FakeTts(initialized).also(engines::add) },
            clock,
        )

    private class FakeInput : PleosSttEngine {
        lateinit var initialization: OnInitializeListener
        var listener: ResultListener? = null
        var stops = 0
        var releases = 0

        override fun initialize(listener: OnInitializeListener) {
            initialization = listener
        }

        override fun addListener(listener: ResultListener) {
            this.listener = listener
        }

        override fun removeListener(listener: ResultListener) = Unit // Retain queued old callbacks.

        override fun setLocale(locale: Locale) {
            assertEquals(Locale.KOREA, locale)
        }

        override fun request() = Unit

        override fun stop() {
            stops++
            listener?.onEndedRecognition()
        }

        override fun release() {
            releases++
        }
    }

    private class FakeTts(val initialized: (Int) -> Unit) : AndroidTtsEngine {
        var canPrepareOfflineVoice = true
        var preparations = 0
        var shutdowns = 0
        val texts = mutableListOf<String>()
        val silences = mutableListOf<Long>()
        val silentIds = mutableListOf<String>()
        val spokenIds = mutableListOf<String>()
        lateinit var progress: UtteranceProgressListener

        override fun prepareOfflineKoreanVoice(attributes: AudioAttributes): Boolean {
            preparations++
            return canPrepareOfflineVoice
        }

        override fun setListener(listener: UtteranceProgressListener) { this.progress = listener }

        override fun playSilence(milliseconds: Long, utteranceId: String): Boolean {
            silences.add(milliseconds)
            silentIds.add(utteranceId)
            return true
        }

        override fun speak(text: String, utteranceId: String): Boolean {
            texts.add(text)
            spokenIds.add(utteranceId)
            return true
        }

        override fun stop() = Unit

        override fun shutdown() {
            shutdowns++
        }
    }

    private class VirtualDeadlines : AndroidTtsDeadlines {
        private var now = 0L
        private val tasks = linkedMapOf<Runnable, Long>()
        val pendingTasks: List<Runnable>
            get() = tasks.keys.toList()

        override fun schedule(task: Runnable, milliseconds: Long) {
            tasks[task] = now + milliseconds
        }

        override fun cancel(task: Runnable) {
            tasks.remove(task)
        }

        fun advanceBy(milliseconds: Long) {
            now += milliseconds
            while (true) {
                val next =
                    tasks.entries.filter { it.value <= now }.minByOrNull { it.value } ?: return
                tasks.remove(next.key)
                next.key.run()
            }
        }
    }
}
