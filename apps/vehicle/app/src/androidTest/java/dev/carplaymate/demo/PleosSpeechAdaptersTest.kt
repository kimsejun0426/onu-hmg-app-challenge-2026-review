package dev.carplaymate.demo

import android.media.AudioAttributes
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitialization
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitialization
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real adapter state machines with fake SDK callbacks; no vehicle microphone or sound is tested. */
@RunWith(AndroidJUnit4::class)
class PleosSpeechAdaptersTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun drain() = instrumentation.waitForIdleSync()
    private fun ticket(generation: Long) = CaptureTicket(generation,
        CaptureContext("session", generation, "turn", generation, "window-$generation", generation))
    private fun plan(id: String) = SpeechPlan(id, "turn", 1,
        listOf(SpeechSegment("one", "첫 안내", true), SpeechSegment("two", "다음 안내", true)))

    @Test fun inputRejectsLateInitializationAndFinalsAfterClose() {
        val engines = mutableListOf<FakeInput>()
        val finals = mutableListOf<Pair<CaptureTicket, String>>()
        val failures = mutableListOf<SpeechInputFailure>()
        lateinit var input: PleosSpeechInput
        main { input = PleosSpeechInput({ FakeInput().also(engines::add) }, { null }) }
        try {
            main {
                input.open(ticket(1), { _, _ -> }, { t, s -> finals.add(t to s) }, { _, f -> failures.add(f) })
                input.close()
                engines[0].initialization.onSuccess()
            }
            drain()
            assertEquals(0, engines[0].requests)
            assertEquals(1, engines[0].releases)
            main {
                input.open(ticket(2), { _, _ -> }, { t, s -> finals.add(t to s) }, { _, f -> failures.add(f) })
                engines[1].initialization.onSuccess()
            }
            drain()
            assertEquals(Locale.KOREA, engines[1].lastLocale)
            assertEquals(1, engines[1].requests)
            main {
                engines[1].listener!!.onUpdated("현재 선택", true)
                engines[1].listener!!.onUpdated("중복 결과", true)
            }
            drain()
            assertEquals(listOf(ticket(2) to "현재 선택"), finals)
            assertTrue(failures.isEmpty())
            assertEquals(1, engines[1].stops)
            assertEquals(1, engines[1].releases)
        } finally { main { input.close() } }
    }

    @Test fun inputOldCaptureCannotCommitIntoNewCaptureAndTimeoutIsNotSilence() {
        val engines = mutableListOf<FakeInput>()
        val finals = mutableListOf<Pair<CaptureTicket, String>>()
        val failures = mutableListOf<SpeechInputFailure>()
        lateinit var input: PleosSpeechInput
        main { input = PleosSpeechInput({ FakeInput().also(engines::add) }, { null }) }
        try {
            for (generation in 1L..2L) {
                main {
                    input.open(ticket(generation), { _, _ -> }, { t, s -> finals.add(t to s) },
                        { _, f -> failures.add(f) })
                    engines.last().initialization.onSuccess()
                }
                drain()
            }
            main {
                engines[0].listener!!.onUpdated("이전 차례", true)
                engines[0].listener!!.onError(SttError.TIMEOUT)
                engines[1].listener!!.onError(SttError.TIMEOUT)
            }
            drain()
            assertTrue(finals.isEmpty())
            assertEquals(1, failures.size)
            assertEquals("USER", failures.single().pauseReason)
            assertEquals(RecoveryCause.INPUT_TIMEOUT, failures.single().cause)
            assertEquals(1, engines[0].stops)
            assertEquals(1, engines[1].stops)
        } finally { main { input.close() } }
    }

    @Test fun missingPermissionDoesNotConstructSdkOrActivateMicrophone() {
        var constructions = 0
        val failures = mutableListOf<SpeechInputFailure>()
        main {
            val input = PleosSpeechInput({ constructions++; FakeInput() },
                { check -> if (check) "Pleos 서비스 권한 없음" else null },
                typedCapability = { check -> if (check) SpeechInputFailure(
                    "Pleos 서비스 권한 없음", "MIC_PERMISSION", RecoveryCause.INPUT_PERMISSION,
                ) else null })
            assertFalse(input.available)
            assertNull(input.unavailableReason(false))
            input.open(ticket(1), { _, _ -> }, { _, _ -> fail("Unexpected final") },
                { _, error -> failures.add(error) })
            input.close()
        }
        assertEquals(0, constructions)
        assertEquals("MIC_PERMISSION", failures.single().pauseReason)
        assertEquals(RecoveryCause.INPUT_PERMISSION, failures.single().cause)
    }

    @Test fun inputInitializationAndRuntimeFailuresKeepTheirCauseAndFenceLateResults() {
        val engines = mutableListOf<FakeInput>()
        val failures = mutableListOf<SpeechInputFailure>()
        lateinit var input: PleosSpeechInput
        main { input = PleosSpeechInput({ FakeInput().also(engines::add) }, { null }) }
        try {
            main {
                input.open(ticket(1), { _, _ -> }, { _, _ -> fail("Unexpected final") },
                    { _, failure -> failures.add(failure) })
                engines[0].initialization.onFailure(SttError.UNAVAILABLE_LANGUAGE)
                engines[0].initialization.onSuccess()
            }
            drain()
            assertEquals(RecoveryCause.INPUT_LANGUAGE, failures.single().cause)
            assertEquals(0, engines[0].requests)
            assertEquals(1, engines[0].releases)
            main {
                input.open(ticket(2), { _, _ -> }, { _, _ -> fail("Unexpected final") },
                    { _, failure -> failures.add(failure) })
                engines[1].initialization.onSuccess()
            }
            drain()
            main {
                engines[1].listener!!.onError(SttError.PERMISSION_DENIED)
                engines[1].listener!!.onUpdated("늦은 선택", true)
                engines[1].listener!!.onError(SttError.UNAVAILABLE_LANGUAGE)
            }
            drain()
            assertEquals(listOf(RecoveryCause.INPUT_LANGUAGE, RecoveryCause.INPUT_PERMISSION), failures.map { it.cause })
            assertEquals("MIC_PERMISSION", failures.last().pauseReason)
            assertEquals(1, engines[1].stops)
            assertEquals(1, engines[1].releases)
        } finally { main { input.close() } }
    }

    @Test fun outputRefreshIsSilentAndOnlyCurrentWholePlanCanComplete() {
        val engines = mutableListOf<FakeOutput>()
        val focus = FakeFocus()
        var completions = 0
        var interruptions = 0
        val errors = mutableListOf<SpeechOutputFailure>()
        lateinit var output: PleosSpeechOutput
        main { output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus,
            { interruptions++ }) }
        try {
            main { engines[0].initialization.onSuccess() }
            drain()
            assertTrue(output.readyState.value)
            assertEquals(0, focus.acquisitions)
            assertTrue(engines[0].texts.isEmpty())
            main {
                output.speak(plan("old"), 1, { completions++ }, errors::add)
                engines[1].initialization.onSuccess()
            }
            drain()
            main {
                output.stop()
                output.speak(plan("new"), 2, { completions++ }, errors::add)
                engines[2].initialization.onSuccess()
            }
            drain()
            assertEquals(listOf("첫 안내\n다음 안내"), engines[2].texts)
            assertEquals(Locale.KOREA, engines[2].lastLocale)
            main { engines[1].listener!!.onDone(); engines[1].listener!!.onStop() }
            drain()
            assertEquals(0, completions)
            assertEquals(0, interruptions)
            main { engines[2].listener!!.onDone(); engines[2].listener!!.onDone() }
            drain()
            assertEquals(1, completions)
            assertTrue(errors.isEmpty())
            assertEquals(1, engines[1].stops)
            assertEquals(1, engines[2].releases)
            assertFalse(focus.held)
        } finally { main { output.shutdown() } }
    }

    @Test fun outputFocusLossStopsBeforeInterruptionAndPlatformStopNeverCompletes() {
        val engines = mutableListOf<FakeOutput>()
        val focus = FakeFocus()
        var completions = 0
        var interruptions = 0
        val failures = mutableListOf<SpeechOutputFailure>()
        lateinit var output: PleosSpeechOutput
        main {
            output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus, {
                assertFalse(focus.held)
                assertTrue(engines.last().releases > 0)
                interruptions++
            })
            engines[0].initialization.onSuccess()
        }
        drain()
        try {
            main {
                output.speak(plan("focus"), 1, { completions++ }, { fail(it.message) })
                engines[1].initialization.onSuccess()
            }
            drain()
            val oldFocusCallback = focus.lost!!
            main { oldFocusCallback() }
            drain()
            assertEquals(1, interruptions)
            assertEquals(1, engines[1].stops)
            main {
                output.speak(plan("platform"), 2, { completions++ }, {
                    assertFalse(focus.held)
                    assertTrue(engines.last().releases > 0)
                    failures.add(it)
                })
                engines[2].initialization.onSuccess()
            }
            drain()
            main { oldFocusCallback(); engines[2].listener!!.onStop() }
            drain()
            assertEquals(1, interruptions)
            assertEquals(RecoveryCause.OUTPUT_FAILED, failures.single().cause)
            assertEquals(0, completions)
        } finally { main { output.shutdown() } }
    }

    @Test fun stoppingSilentPreparationKeepsItsSuccessSilentAndReleasesTheEngine() {
        val engines = mutableListOf<FakeOutput>()
        val focus = FakeFocus()
        val timer = FakeTimer()
        lateinit var output: PleosSpeechOutput
        main {
            output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus,
                { fail("Silent preparation cannot lose playback focus") }, timer = timer)
            output.refresh()
            assertEquals(1, engines[0].releases)
            output.stop()
            output.stop()
            assertEquals(0, engines[1].releases)
            assertEquals(1, timer.pendingCount)
            engines[1].initialization.onSuccess()
        }
        try {
            drain()
            assertTrue(output.readyState.value)
            assertNull(output.availabilityFailure)
            assertEquals(Locale.KOREA, engines[1].lastLocale)
            assertEquals(1, engines[1].releases)
            assertEquals(0, engines[1].stops)
            assertEquals(0, focus.acquisitions)
            assertEquals(0, timer.pendingCount)
            assertTrue(engines.all { it.texts.isEmpty() })
            main { engines[1].initialization.onSuccess(); timer.advance(10_000) }
            drain()
            assertTrue(output.readyState.value)
            assertEquals(1, engines[1].releases)
            assertEquals(2, engines.size)
        } finally { main { output.shutdown() } }
    }

    @Test fun stoppingSilentPreparationKeepsItsDeadlineAndLateSuccessCannotReviveIt() {
        val engines = mutableListOf<FakeOutput>()
        val focus = FakeFocus()
        val timer = FakeTimer()
        lateinit var output: PleosSpeechOutput
        main {
            output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus,
                { fail("Silent preparation cannot interrupt playback") }, timer = timer)
            output.stop()
            timer.advance(9_999)
            assertFalse(output.readyState.value)
            assertEquals(0, engines[0].releases)
            assertEquals(1, timer.pendingCount)
            timer.advance(1)
            assertEquals(1, engines[0].releases)
            assertEquals(0, timer.pendingCount)
            assertEquals(RecoveryCause.OUTPUT_FAILED, output.availabilityFailure!!.cause)
            engines[0].initialization.onSuccess()
        }
        try {
            drain()
            assertFalse(output.readyState.value)
            assertEquals(1, engines.size) // No implicit retry after timeout.
            assertEquals(0, focus.acquisitions)
            assertEquals(0, engines[0].stops)
            assertTrue(engines[0].texts.isEmpty())
            main {
                output.refresh() // Only an explicit refresh starts another preparation.
                engines[1].initialization.onSuccess()
            }
            drain()
            assertTrue(output.readyState.value)
            assertNull(output.availabilityFailure)
            assertEquals(0, timer.pendingCount)
            assertEquals(1, engines[1].releases)
            assertTrue(engines.all { it.texts.isEmpty() })
        } finally { main { output.shutdown() } }
    }

    @Test fun refreshReplacementAndShutdownFenceSilentInitializationAndOldTimers() {
        val engines = mutableListOf<FakeOutput>()
        val focus = FakeFocus()
        val timer = FakeTimer()
        lateinit var output: PleosSpeechOutput
        main {
            output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus,
                { fail("Silent preparation cannot interrupt playback") }, timer = timer)
            val oldTimeout = timer.singleCallback()
            output.refresh()
            assertEquals(1, engines[0].releases)
            assertEquals(1, timer.pendingCount)
            oldTimeout.run() // Emulate an already queued callback after cancellation.
            engines[0].initialization.onSuccess()
            engines[0].initialization.onFailure(TtsError.PERMISSION_DENIED)
        }
        try {
            drain()
            assertFalse(output.readyState.value)
            assertEquals(0, engines[1].releases)
            assertEquals(RecoveryCause.OUTPUT_UNAVAILABLE, output.availabilityFailure!!.cause)
            main { engines[1].initialization.onSuccess() }
            drain()
            assertTrue(output.readyState.value)
            main {
                output.refresh()
                val shutdownTimeout = timer.singleCallback()
                output.shutdown()
                assertEquals(1, engines[2].releases)
                assertEquals(0, timer.pendingCount)
                shutdownTimeout.run()
                engines[2].initialization.onSuccess()
                engines[2].initialization.onFailure(TtsError.UNAVAILABLE_LANGUAGE)
                output.refresh()
            }
            drain()
            assertFalse(output.readyState.value)
            assertEquals(RecoveryCause.OUTPUT_UNAVAILABLE, output.availabilityFailure!!.cause)
            assertEquals(3, engines.size)
            assertTrue(engines.all { it.releases == 1 && it.stops == 0 && it.texts.isEmpty() })
            assertEquals(0, focus.acquisitions)
        } finally { main { output.shutdown() } }
    }

    @Test fun explicitPlaybackReplacesSilentPreparationAndStopStillCancelsPlaybackImmediately() {
        val engines = mutableListOf<FakeOutput>()
        val focus = FakeFocus()
        val timer = FakeTimer()
        var completions = 0
        val failures = mutableListOf<SpeechOutputFailure>()
        lateinit var output: PleosSpeechOutput
        main {
            output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus,
                { fail("No focus-loss event was sent") }, timer = timer)
            val oldTimeout = timer.singleCallback()
            output.speak(plan("explicit"), 1, { completions++ }, failures::add)
            assertEquals(1, engines[0].releases)
            assertEquals(1, timer.pendingCount)
            oldTimeout.run()
            engines[0].initialization.onSuccess()
            engines[1].initialization.onSuccess()
        }
        try {
            drain()
            assertTrue(engines[0].texts.isEmpty())
            assertEquals(listOf("첫 안내\n다음 안내"), engines[1].texts)
            assertEquals(1, focus.acquisitions)
            assertTrue(focus.held)
            main {
                output.stop()
                assertEquals(1, engines[1].stops)
                assertEquals(1, engines[1].releases)
                assertFalse(focus.held)
                assertEquals(0, timer.pendingCount)
                engines[1].listener!!.onDone()
                engines[1].listener!!.onError(TtsError.UNAVAILABLE_LANGUAGE)
                timer.advance(120_000)
            }
            drain()
            assertEquals(0, completions)
            assertTrue(failures.isEmpty())
            assertEquals(2, engines.size)
        } finally { main { output.shutdown() } }
    }

    @Test fun outputAvailabilityInitializationAndFocusFailureRemainTypedWithoutImplicitRetry() {
        val engines = mutableListOf<FakeOutput>()
        val focus = FakeFocus()
        val failures = mutableListOf<SpeechOutputFailure>()
        var unavailable: SpeechOutputFailure? = SpeechOutputFailure(
            "Pleos 서비스 권한 없음", RecoveryCause.OUTPUT_PERMISSION)
        lateinit var output: PleosSpeechOutput
        main {
            output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus,
                { fail("No focus-loss event was sent") }, typedCapability = { unavailable })
            assertEquals(RecoveryCause.OUTPUT_PERMISSION, output.availabilityFailure!!.cause)
            output.speak(plan("permission"), 1, { fail("Unexpected completion") }, failures::add)
        }
        try {
            assertTrue(engines.isEmpty())
            assertEquals(RecoveryCause.OUTPUT_PERMISSION, failures.single().cause)
            main {
                unavailable = null
                output.refresh()
                engines[0].initialization.onFailure(TtsError.UNAVAILABLE_LANGUAGE)
                engines[0].initialization.onSuccess()
            }
            drain()
            assertFalse(output.readyState.value)
            assertEquals(RecoveryCause.OUTPUT_LANGUAGE, output.availabilityFailure!!.cause)
            assertEquals(1, engines.size)
            assertEquals(1, engines[0].releases)
            assertTrue(engines[0].texts.isEmpty())
            assertEquals(0, focus.acquisitions)
            main {
                output.refresh()
                engines[1].initialization.onSuccess()
            }
            drain()
            assertTrue(output.readyState.value)
            assertNull(output.availabilityFailure)
            main {
                focus.grant = false
                output.speak(plan("focus-denied"), 2, { fail("Unexpected completion") }, failures::add)
                engines[2].initialization.onSuccess()
            }
            drain()
            assertEquals(RecoveryCause.AUDIO_FOCUS, failures.last().cause)
            assertTrue(engines[2].texts.isEmpty())
            assertFalse(focus.held)
            main {
                focus.grant = true
                output.speak(plan("runtime"), 3, { fail("Unexpected completion") }, failures::add)
                engines[3].initialization.onSuccess()
            }
            drain()
            main {
                engines[3].listener!!.onError(TtsError.UNAVAILABLE_LANGUAGE)
                engines[3].listener!!.onDone()
                engines[3].listener!!.onError(TtsError.PERMISSION_DENIED)
            }
            drain()
            assertEquals(listOf(RecoveryCause.OUTPUT_PERMISSION, RecoveryCause.AUDIO_FOCUS,
                RecoveryCause.OUTPUT_LANGUAGE), failures.map { it.cause })
            assertEquals(1, engines[3].stops)
            assertEquals(1, engines[3].releases)
            assertFalse(focus.held)
        } finally { main { output.shutdown() } }
    }

    @Test fun outputStartRequiresRequestedSpeechIsOnceAndDoesNotGateCompletion() {
        val engines = mutableListOf<FakeOutput>()
        val focus = FakeFocus()
        var starts = 0
        var completions = 0
        val failures = mutableListOf<SpeechOutputFailure>()
        lateinit var output: PleosSpeechOutput
        main {
            output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus,
                { fail("No focus loss was requested") })
            engines[0].initialization.onSuccess()
        }
        drain()
        try {
            assertEquals(0, starts)
            assertEquals(0, focus.acquisitions)
            main {
                output.speak(plan("started"), 1, { completions++ }, failures::add, {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    starts++
                })
                // A registration callback precedes the SDK speak call, even if dispatch is queued.
                engines[1].startOnRegistration = true
                engines[1].initialization.onSuccess()
            }
            drain()
            assertEquals(0, starts)
            main { engines[1].listener!!.onStart(); engines[1].listener!!.onStart() }
            drain()
            assertEquals(1, starts)
            main { engines[1].listener!!.onDone(); engines[1].listener!!.onStart() }
            drain()
            assertEquals(1, completions)
            assertEquals(1, starts)
            main {
                output.speak(plan("no-start-event"), 2, { completions++ }, failures::add, { starts++ })
                engines[2].initialization.onSuccess()
            }
            drain()
            main { engines[2].listener!!.onDone(); engines[2].listener!!.onStart() }
            drain()
            assertEquals("A missing start observation must not prevent the existing completion.", 2, completions)
            assertEquals(1, starts)
            assertTrue(failures.isEmpty())
        } finally { main { output.shutdown() } }
    }

    @Test fun outputStartCannotSurviveStopReplacementFailureRefreshOrShutdown() {
        for (boundary in listOf("stop", "replace", "refresh", "shutdown", "failure", "timeout", "platform-stop", "focus-loss")) {
            val engines = mutableListOf<FakeOutput>()
            val focus = FakeFocus()
            val timer = FakeTimer()
            var starts = 0
            var completions = 0
            var interruptions = 0
            val failures = mutableListOf<SpeechOutputFailure>()
            lateinit var output: PleosSpeechOutput
            main {
                output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus,
                    { interruptions++ }, timer = timer)
                engines[0].initialization.onSuccess()
            }
            drain()
            try {
                main {
                    output.speak(plan("same"), 1, { completions++ }, failures::add, { starts++ })
                    engines[1].initialization.onSuccess()
                }
                drain()
                val old = engines[1].listener!!
                main {
                    // Direct invalidations also reject a start already waiting on the main queue.
                    if (boundary !in setOf("failure", "platform-stop")) old.onStart()
                    when (boundary) {
                        "stop" -> output.stop()
                        "replace" -> output.speak(plan("same"), 1, { completions++ }, failures::add, { starts++ })
                        "refresh" -> output.refresh()
                        "shutdown" -> output.shutdown()
                        "failure" -> old.onError(TtsError.UNAVAILABLE_LANGUAGE)
                        "timeout" -> timer.advance(120_000)
                        "platform-stop" -> old.onStop()
                        "focus-loss" -> focus.lost!!()
                    }
                    old.onStart()
                    old.onDone()
                }
                drain()
                assertEquals("Stale start after $boundary.", 0, starts)
                assertEquals("Stale completion after $boundary.", 0, completions)
                assertEquals(if (boundary in setOf("failure", "timeout", "platform-stop")) 1 else 0, failures.size)
                assertEquals(if (boundary == "focus-loss") 1 else 0, interruptions)
                if (boundary == "replace") {
                    main { engines[2].initialization.onSuccess() }
                    drain()
                    main { old.onStart(); engines[2].listener!!.onStart() }
                    drain()
                    assertEquals("Only the replacement attempt can announce its start.", 1, starts)
                }
            } finally { main { output.shutdown() } }
        }
    }

    @Test fun inputRegistrationCallbacksCannotBecomeCurrentAfterRequest() {
        for (worker in listOf(false, true)) {
            val engine = FakeInput()
            val timer = FakeTimer()
            val stages = mutableListOf<CaptureStage>()
            val finals = mutableListOf<String>()
            val failures = mutableListOf<SpeechInputFailure>()
            lateinit var input: PleosSpeechInput
            main {
                engine.onRegistration = { listener -> callback(worker) {
                    listener.onReady()
                    listener.onStartedRecognition()
                    listener.onEndedRecognition()
                    listener.onUpdated("등록 중 부분 결과", false)
                    listener.onUpdated("등록 중 최종 결과", true)
                } }
                input = PleosSpeechInput({ engine }, { null }, timer = timer)
                input.open(ticket(1), { _, stage -> stages.add(stage) }, { _, value -> finals.add(value) },
                    { _, error -> failures.add(error) })
                engine.initialization.onSuccess()
            }
            try {
                drain()
                assertEquals(1, engine.requests)
                assertTrue(stages.isEmpty())
                assertTrue(finals.isEmpty())
                assertTrue(failures.isEmpty())
                assertEquals(0, engine.releases)
                // An early end must not shorten the real request to the two-second result grace.
                main { timer.advance(2_001) }
                assertTrue(failures.isEmpty())
                main {
                    engine.listener!!.onReady()
                    engine.listener!!.onEndedRecognition()
                    engine.listener!!.onUpdated("현재 선택", true)
                }
                drain()
                assertEquals(listOf(CaptureStage.LISTENING, CaptureStage.PROCESSING), stages)
                assertEquals(listOf("현재 선택"), finals)
                assertTrue(failures.isEmpty())
                assertEquals(1, engine.stops)
                assertEquals(1, engine.releases)
            } finally { main { input.close() } }
        }
    }

    @Test fun inputCallbacksInsideRequestRemainValidOnMainAndWorkerThreads() {
        for (worker in listOf(false, true)) {
            val engine = FakeInput()
            val stages = mutableListOf<CaptureStage>()
            val finals = mutableListOf<String>()
            val failures = mutableListOf<SpeechInputFailure>()
            lateinit var input: PleosSpeechInput
            main {
                engine.onRequest = { listener -> callback(worker) {
                    listener.onReady()
                    listener.onEndedRecognition()
                    listener.onUpdated("요청 안의 결과", true)
                    listener.onUpdated("중복 결과", true)
                } }
                input = PleosSpeechInput({ engine }, { null })
                input.open(ticket(1), { _, stage ->
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    stages.add(stage)
                }, { _, value ->
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    finals.add(value)
                }, { _, error -> failures.add(error) })
                engine.initialization.onSuccess()
            }
            try {
                drain()
                assertEquals(listOf(CaptureStage.LISTENING, CaptureStage.PROCESSING), stages)
                assertEquals(listOf("요청 안의 결과"), finals)
                assertTrue(failures.isEmpty())
                assertEquals(1, engine.requests)
                assertEquals(1, engine.stops)
                assertEquals(1, engine.releases)
            } finally { main { input.close() } }
        }
    }

    @Test fun inputRegistrationErrorStillFailsAndFencesRequestResult() {
        val engine = FakeInput()
        val failures = mutableListOf<SpeechInputFailure>()
        lateinit var input: PleosSpeechInput
        main {
            engine.onRegistration = { it.onError(SttError.UNAVAILABLE_LANGUAGE) }
            engine.onRequest = { it.onUpdated("오류 뒤 결과", true) }
            input = PleosSpeechInput({ engine }, { null })
            input.open(ticket(1), { _, _ -> fail("Unexpected stage") },
                { _, _ -> fail("Unexpected final") }, { _, error -> failures.add(error) })
            engine.initialization.onSuccess()
        }
        try {
            drain()
            assertEquals(RecoveryCause.INPUT_LANGUAGE, failures.single().cause)
            assertEquals(1, engine.releases)
        } finally { main { input.close() } }
    }

    @Test fun inputQueuedFinalIsRejectedAfterCloseOrReplacement() {
        for (replace in listOf(false, true)) {
            val engines = mutableListOf<FakeInput>()
            val finals = mutableListOf<Pair<CaptureTicket, String>>()
            lateinit var input: PleosSpeechInput
            main {
                input = PleosSpeechInput({ FakeInput().also(engines::add) }, { null })
                input.open(ticket(1), { _, _ -> }, { t, value -> finals.add(t to value) },
                    { _, _ -> fail("Unexpected failure") })
                engines[0].initialization.onSuccess()
            }
            try {
                drain()
                main {
                    engines[0].listener!!.onUpdated("대기 중 이전 결과", true)
                    if (replace) {
                        input.open(ticket(2), { _, _ -> }, { t, value -> finals.add(t to value) },
                            { _, _ -> fail("Unexpected failure") })
                        engines[1].initialization.onSuccess()
                    } else input.close()
                }
                drain()
                assertTrue(finals.isEmpty())
                if (replace) {
                    main { engines[1].listener!!.onUpdated("새 결과", true) }
                    drain()
                    assertEquals(listOf(ticket(2) to "새 결과"), finals)
                }
            } finally { main { input.close() } }
        }
    }

    @Test fun outputRegistrationDoneAndStopCannotFinishOrInterruptRequestedSpeech() {
        for (early in listOf("done", "stop")) for (worker in listOf(false, true)) {
            val engines = mutableListOf<FakeOutput>()
            val focus = FakeFocus()
            val failures = mutableListOf<SpeechOutputFailure>()
            var starts = 0
            var completions = 0
            lateinit var output: PleosSpeechOutput
            main {
                output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus, {})
                output.speak(plan("registration-$early"), 1, { completions++ }, failures::add, { starts++ })
                engines[1].onRegistration = { listener -> callback(worker) {
                    listener.onStart()
                    if (early == "done") listener.onDone() else listener.onStop()
                } }
                engines[1].initialization.onSuccess()
            }
            try {
                drain()
                assertEquals(1, engines[1].texts.size)
                assertEquals(0, starts)
                assertEquals(0, completions)
                assertTrue(failures.isEmpty())
                assertEquals(0, engines[1].stops)
                assertEquals(0, engines[1].releases)
                assertTrue(focus.held)
                main {
                    engines[1].listener!!.onStart()
                    engines[1].listener!!.onDone()
                    engines[1].listener!!.onStop()
                }
                drain()
                assertEquals(1, starts)
                assertEquals(1, completions)
                assertTrue(failures.isEmpty())
                assertEquals(1, engines[1].releases)
                assertFalse(focus.held)
            } finally { main { output.shutdown() } }
        }
    }

    @Test fun outputCallbacksInsideSpeakRetainCompletionOrInterruption() {
        for (terminal in listOf("done", "stop")) for (worker in listOf(false, true)) {
            val engines = mutableListOf<FakeOutput>()
            val focus = FakeFocus()
            val failures = mutableListOf<SpeechOutputFailure>()
            var starts = 0
            var completions = 0
            lateinit var output: PleosSpeechOutput
            main {
                output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus, {})
                output.speak(plan("synchronous-$terminal"), 1, {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    completions++
                }, failures::add, {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    starts++
                })
                engines[1].onSpeak = { listener -> callback(worker) {
                    listener.onStart()
                    listener.onStart()
                    if (terminal == "done") listener.onDone() else listener.onStop()
                    listener.onDone()
                } }
                engines[1].initialization.onSuccess()
            }
            try {
                drain()
                assertEquals(1, starts)
                assertEquals(if (terminal == "done") 1 else 0, completions)
                assertEquals(if (terminal == "stop") 1 else 0, failures.size)
                if (terminal == "stop") assertEquals(RecoveryCause.OUTPUT_FAILED, failures.single().cause)
                assertEquals(1, engines[1].texts.size)
                assertEquals(1, engines[1].releases)
                assertFalse(focus.held)
            } finally { main { output.shutdown() } }
        }
    }

    @Test fun outputRegistrationErrorStillFailsAndFencesSynchronousCompletion() {
        val engines = mutableListOf<FakeOutput>()
        val focus = FakeFocus()
        val failures = mutableListOf<SpeechOutputFailure>()
        lateinit var output: PleosSpeechOutput
        main {
            output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus, {})
            output.speak(plan("registration-error"), 1, { fail("Unexpected completion") }, failures::add,
                { fail("Unexpected start") })
            engines[1].onRegistration = { it.onError(TtsError.UNAVAILABLE_LANGUAGE) }
            engines[1].onSpeak = { it.onStart(); it.onDone() }
            engines[1].initialization.onSuccess()
        }
        try {
            drain()
            assertEquals(RecoveryCause.OUTPUT_LANGUAGE, failures.single().cause)
            assertFalse(output.readyState.value)
            assertEquals(1, engines[1].releases)
            assertFalse(focus.held)
        } finally { main { output.shutdown() } }
    }

    @Test fun outputQueuedTerminalCannotSurviveStopOrReplacement() {
        for (replace in listOf(false, true)) for (terminal in listOf("done", "stop")) {
            val engines = mutableListOf<FakeOutput>()
            val focus = FakeFocus()
            val failures = mutableListOf<SpeechOutputFailure>()
            var completions = 0
            lateinit var output: PleosSpeechOutput
            main {
                output = PleosSpeechOutput({ FakeOutput().also(engines::add) }, { null }, focus, {})
                output.speak(plan("old"), 1, { completions++ }, failures::add)
                engines[1].initialization.onSuccess()
            }
            try {
                drain()
                main {
                    if (terminal == "done") engines[1].listener!!.onDone() else engines[1].listener!!.onStop()
                    if (replace) {
                        output.speak(plan("new"), 2, { completions++ }, failures::add)
                        engines[2].initialization.onSuccess()
                    } else output.stop()
                }
                drain()
                assertEquals(0, completions)
                assertTrue(failures.isEmpty())
                if (replace) {
                    main { engines[2].listener!!.onDone() }
                    drain()
                    assertEquals(1, completions)
                    assertTrue(failures.isEmpty())
                }
            } finally { main { output.shutdown() } }
        }
    }

    /** Deterministic cross-thread receipt while the calling main-thread SDK operation is pending. */
    private fun callback(onWorker: Boolean, action: () -> Unit) {
        if (!onWorker) { action(); return }
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread({
            try { action() } catch (error: Throwable) { failure.set(error) }
        }, "synthetic-pleos-callback")
        worker.start()
        worker.join(1_000)
        assertFalse("Synthetic callback must not wait for the main thread.", worker.isAlive)
        failure.get()?.let { throw it }
    }

    private class FakeInput : PleosSttEngine {
        lateinit var initialization: SttInitialization
        var listener: ResultListener? = null
        var lastLocale: Locale? = null
        var requests = 0
        var stops = 0
        var releases = 0
        var onRegistration: ((ResultListener) -> Unit)? = null
        var onRequest: ((ResultListener) -> Unit)? = null
        override fun initialize(listener: SttInitialization) { initialization = listener }
        override fun addListener(listener: ResultListener) {
            this.listener = listener
            onRegistration?.invoke(listener)
        }
        override fun removeListener(listener: ResultListener) = Unit // Retain to emulate queued old callbacks.
        override fun setLocale(locale: Locale) { lastLocale = locale }
        override fun request() { requests++; onRequest?.invoke(checkNotNull(listener)) }
        override fun stop() { stops++; listener?.onEndedRecognition() }
        override fun release() { releases++ }
    }

    private class FakeOutput : PleosTtsEngine {
        lateinit var initialization: TtsInitialization
        var listener: EventListener? = null
        var lastLocale: Locale? = null
        val texts = mutableListOf<String>()
        var stops = 0
        var releases = 0
        var startOnRegistration = false
        var onRegistration: ((EventListener) -> Unit)? = null
        var onSpeak: ((EventListener) -> Unit)? = null
        override fun initialize(listener: TtsInitialization) { initialization = listener }
        override fun addListener(listener: EventListener) {
            this.listener = listener
            if (startOnRegistration) listener.onStart()
            onRegistration?.invoke(listener)
        }
        override fun removeListener(listener: EventListener) = Unit
        override fun setLocale(locale: Locale) { lastLocale = locale }
        override fun setAudioAttributes(attributes: AudioAttributes) = Unit
        override fun speak(text: String) { texts.add(text); onSpeak?.invoke(checkNotNull(listener)) }
        override fun stop() { stops++; listener?.onStop() }
        override fun release() { releases++ }
    }

    private class FakeFocus : PleosPlaybackFocus {
        var acquisitions = 0
        var grant = true
        var held = false
        var lost: (() -> Unit)? = null
        override fun acquire(onLost: () -> Unit): Boolean {
            acquisitions++
            held = grant
            lost = onLost
            return grant
        }
        override fun release() { held = false }
    }

    private class FakeTimer : CaptureTimer {
        private var now = 0L
        private val pending = linkedMapOf<Runnable, Long>()
        val pendingCount: Int get() = pending.size
        override fun nowMillis(): Long = now
        override fun postDelayed(callback: Runnable, delayMillis: Long) { pending[callback] = now + delayMillis }
        override fun remove(callback: Runnable) { pending.remove(callback) }
        fun singleCallback(): Runnable = pending.keys.single()
        fun advance(milliseconds: Long) {
            now += milliseconds
            val due = pending.filterValues { it <= now }.keys.toList()
            due.forEach { pending.remove(it); it.run() }
        }
    }
}
