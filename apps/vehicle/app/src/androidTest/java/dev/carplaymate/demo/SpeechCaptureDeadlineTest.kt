package dev.carplaymate.demo

import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Adapter callbacks and app timers only: this does not test physical microphones or SDK services. */
@RunWith(AndroidJUnit4::class)
class SpeechCaptureDeadlineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun drain() = instrumentation.waitForIdleSync()
    private fun ticket(generation: Long) = CaptureTicket(generation,
        CaptureContext("session", generation, "turn", generation, "window-$generation", generation))
    private fun result(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    @Test fun androidRepeatedStagesAndPartialResultsCannotExtendThirtySeconds() {
        val timer = FakeTimer()
        val engine = FakeAndroid()
        val failures = mutableListOf<Pair<CaptureTicket, SpeechInputFailure>>()
        var finals = 0
        main {
            val input = AndroidOnDeviceSpeechInput({ engine }, { null }, timer)
            input.open(ticket(1), { _, _ -> }, { _, _ -> finals++ }, { t, f -> failures.add(t to f) })
            timer.advanceTo(9_000)
            engine.listener.onReadyForSpeech(null)
            timer.advanceTo(18_000)
            engine.listener.onBeginningOfSpeech()
            timer.advanceTo(25_000)
            engine.listener.onReadyForSpeech(null)
            engine.listener.onPartialResults(result("아직 말하는 중"))
            timer.advanceTo(29_000)
            engine.listener.onEndOfSpeech()
            assertTrue(failures.isEmpty())
            timer.advanceTo(30_000)
            engine.listener.onResults(result("늦은 선택"))
            assertEquals(1, failures.size)
            assertEquals(ticket(1), failures.single().first)
            assertEquals("USER", failures.single().second.pauseReason)
            assertEquals(RecoveryCause.INPUT_TIMEOUT, failures.single().second.cause)
            assertEquals(0, finals)
            assertEquals(1, engine.starts)
            assertEquals(1, engine.cancels)
            assertEquals(1, engine.destroys)
            assertTrue(timer.pending.isEmpty())
            input.close()
        }
    }

    @Test fun androidUnavailableServiceIsDistinctFromPermissionAndNeverConstructsRecognizer() {
        main {
            for (expected in listOf(
                SpeechInputFailure("지원하지 않는 기기", cause = RecoveryCause.INPUT_UNAVAILABLE),
                SpeechInputFailure("마이크 권한 없음", "MIC_PERMISSION", RecoveryCause.INPUT_PERMISSION),
            )) {
                var constructions = 0
                val failures = mutableListOf<SpeechInputFailure>()
                val input = AndroidOnDeviceSpeechInput({ constructions++; FakeAndroid() }, { null },
                    FakeTimer(), typedCapability = { check ->
                        if (!check && expected.cause == RecoveryCause.INPUT_PERMISSION) null else expected
                    })
                assertFalse(input.available)
                assertEquals(expected.message, input.unavailableReason())
                if (expected.cause == RecoveryCause.INPUT_PERMISSION) assertNull(input.unavailableFailure(false))
                else assertEquals(expected, input.unavailableFailure(false))
                input.open(ticket(1), { _, _ -> }, { _, _ -> fail("Unexpected final") },
                    { _, failure -> failures.add(failure) })
                assertEquals(listOf(expected), failures)
                assertEquals(0, constructions)
                input.close()
            }
        }
    }

    @Test fun androidPlatformFailuresPreserveCauseAndOnlyGenuineSilenceHasNoCause() {
        main {
            val cases = listOf(
                Triple(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, false, RecoveryCause.INPUT_PERMISSION),
                Triple(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, false, RecoveryCause.INPUT_LANGUAGE),
                Triple(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, false, RecoveryCause.INPUT_BUSY),
                Triple(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, true, RecoveryCause.INPUT_TIMEOUT),
                Triple(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, false, null),
            )
            for ((error, spoke, cause) in cases) {
                val engine = FakeAndroid()
                val timer = FakeTimer()
                val failures = mutableListOf<SpeechInputFailure>()
                val input = AndroidOnDeviceSpeechInput({ engine }, { null }, timer)
                input.open(ticket(1), { _, _ -> }, { _, _ -> fail("Unexpected final") },
                    { _, failure -> failures.add(failure) })
                engine.listener.onReadyForSpeech(null)
                if (spoke) engine.listener.onBeginningOfSpeech()
                engine.listener.onError(error)
                engine.listener.onResults(result("이미 닫힌 입력"))
                engine.listener.onError(SpeechRecognizer.ERROR_CLIENT)
                assertEquals(cause, failures.single().cause)
                assertEquals(if (cause == null) "NO_RESPONSE" else if (cause == RecoveryCause.INPUT_PERMISSION)
                    "MIC_PERMISSION" else "USER", failures.single().pauseReason)
                assertEquals(1, engine.cancels)
                assertEquals(1, engine.destroys)
                assertTrue(timer.pending.isEmpty())
                input.close()
            }
        }
    }

    @Test fun androidCancelAndReopenFenceOldTimersAndResults() {
        val timer = FakeTimer()
        val engines = mutableListOf<FakeAndroid>()
        val finals = mutableListOf<CaptureTicket>()
        var failures = 0
        main {
            val input = AndroidOnDeviceSpeechInput({ FakeAndroid().also(engines::add) }, { null }, timer)
            input.open(ticket(1), { _, _ -> }, { t, _ -> finals.add(t) }, { _, _ -> failures++ })
            val oldTimers = timer.pending.map { it.callback }
            input.close()
            timer.advanceTo(29_000)
            input.open(ticket(2), { _, _ -> }, { t, _ -> finals.add(t) }, { _, _ -> failures++ })
            timer.advanceTo(30_000)
            oldTimers.forEach(Runnable::run)
            engines[0].listener.onReadyForSpeech(null)
            engines[0].listener.onResults(result("이전 선택"))
            assertEquals(0, engines[1].cancels)
            assertEquals(0, failures)
            engines[1].listener.onReadyForSpeech(null)
            engines[1].listener.onResults(result("현재 선택"))
            assertEquals(listOf(ticket(2)), finals)
            assertEquals(1, engines[1].cancels)
            assertTrue(timer.pending.isEmpty())
            timer.advanceTo(90_000)
            assertEquals(0, failures)
            input.close()
        }
    }

    @Test fun androidLateFinalIsRejectedEvenBeforeTimerDispatch() {
        val timer = FakeTimer()
        val engine = FakeAndroid()
        var failures = 0
        var finals = 0
        main {
            val input = AndroidOnDeviceSpeechInput({ engine }, { null }, timer)
            input.open(ticket(1), { _, _ -> }, { _, _ -> finals++ }, { _, _ -> failures++ })
            timer.now = 30_001 // Timer callback has not run, as with a delayed main loop.
            engine.listener.onResults(result("늦은 선택"))
            assertEquals(1, failures)
            assertEquals(0, finals)
            assertEquals(1, engine.destroys)
            assertTrue(timer.pending.isEmpty())
            input.close()
        }
    }

    @Test fun androidFactorySetupTimeCountsAndExistingPreparationTimeoutRemains() {
        val timer = FakeTimer()
        val slowEngine = FakeAndroid()
        var failures = 0
        main {
            val slow = AndroidOnDeviceSpeechInput({ timer.now = 30_000; slowEngine }, { null }, timer)
            slow.open(ticket(1), { _, _ -> }, { _, _ -> fail("Unexpected final") }, { _, _ -> failures++ })
            assertEquals(0, slowEngine.starts)
            assertEquals(1, slowEngine.destroys)
            assertEquals(1, failures)
            val normalEngine = FakeAndroid()
            val normal = AndroidOnDeviceSpeechInput({ normalEngine }, { null }, timer)
            normal.open(ticket(2), { _, _ -> }, { _, _ -> fail("Unexpected final") }, { _, _ -> failures++ })
            timer.advanceTo(40_000)
            assertEquals(2, failures)
            assertEquals(1, normalEngine.destroys)
            assertTrue(timer.pending.isEmpty())
            normal.close()
        }
    }

    @Test fun pleosInitializationRepeatedReadyAndPartialsShareOneBudget() {
        val timer = FakeTimer()
        val engine = FakePleos()
        val failures = mutableListOf<Pair<CaptureTicket, SpeechInputFailure>>()
        var finals = 0
        lateinit var input: PleosSpeechInput
        main {
            input = PleosSpeechInput({ engine }, { null }, timer)
            input.open(ticket(1), { _, _ -> }, { _, _ -> finals++ }, { t, f -> failures.add(t to f) })
            timer.advanceTo(9_000)
            engine.initialization.onSuccess()
        }
        drain()
        main {
            timer.advanceTo(18_000)
            engine.listener!!.onReady()
        }
        drain()
        main {
            timer.advanceTo(25_000)
            engine.listener!!.onStartedRecognition()
            engine.listener!!.onUpdated("중간 결과", false)
        }
        drain()
        main {
            timer.advanceTo(29_000)
            engine.listener!!.onReady()
            engine.listener!!.onEndedRecognition()
        }
        drain()
        main {
            assertTrue(failures.isEmpty())
            timer.advanceTo(30_000)
            engine.listener!!.onUpdated("늦은 선택", true)
        }
        drain()
        assertEquals(1, failures.size)
        assertEquals(ticket(1), failures.single().first)
        assertEquals("USER", failures.single().second.pauseReason)
        assertEquals(0, finals)
        assertEquals(1, engine.requests)
        assertEquals(1, engine.stops)
        assertEquals(1, engine.releases)
        main { assertTrue(timer.pending.isEmpty()); input.close() }
    }

    @Test fun pleosCancelAndReopenFenceOldTimersAndCallbacks() {
        val timer = FakeTimer()
        val engines = mutableListOf<FakePleos>()
        val finals = mutableListOf<CaptureTicket>()
        var failures = 0
        lateinit var input: PleosSpeechInput
        lateinit var oldTimers: List<Runnable>
        main {
            input = PleosSpeechInput({ FakePleos().also(engines::add) }, { null }, timer)
            input.open(ticket(1), { _, _ -> }, { t, _ -> finals.add(t) }, { _, _ -> failures++ })
            oldTimers = timer.pending.map { it.callback }
            input.close()
            timer.advanceTo(29_000)
            input.open(ticket(2), { _, _ -> }, { t, _ -> finals.add(t) }, { _, _ -> failures++ })
            engines[0].initialization.onSuccess()
            engines[1].initialization.onSuccess()
        }
        drain()
        main {
            timer.advanceTo(30_000)
            oldTimers.forEach(Runnable::run)
            assertEquals(0, engines[0].requests)
            assertEquals(0, engines[1].stops)
            assertEquals(0, failures)
            engines[1].listener!!.onUpdated("현재 선택", true)
        }
        drain()
        assertEquals(listOf(ticket(2)), finals)
        assertEquals(1, engines[1].stops)
        main {
            assertTrue(timer.pending.isEmpty())
            timer.advanceTo(90_000)
            assertEquals(0, failures)
            input.close()
        }
    }

    @Test fun pleosLateFinalIsRejectedBeforeDelayedTimerAndCleanupRunsOnce() {
        val timer = FakeTimer()
        val engine = FakePleos()
        var failures = 0
        var finals = 0
        lateinit var input: PleosSpeechInput
        main {
            input = PleosSpeechInput({ engine }, { null }, timer)
            input.open(ticket(1), { _, _ -> }, { _, _ -> finals++ }, { _, _ -> failures++ })
            engine.initialization.onSuccess()
        }
        drain()
        main {
            timer.now = 30_001
            engine.listener!!.onUpdated("늦은 선택", true)
        }
        drain()
        assertEquals(1, failures)
        assertEquals(0, finals)
        assertEquals(1, engine.stops)
        assertEquals(1, engine.releases)
        main { assertTrue(timer.pending.isEmpty()); input.close() }
    }

    @Test fun pleosExistingInitializationTimeoutIsStillTenSeconds() {
        val timer = FakeTimer()
        val engine = FakePleos()
        var failures = 0
        lateinit var input: PleosSpeechInput
        main {
            input = PleosSpeechInput({ engine }, { null }, timer)
            input.open(ticket(1), { _, _ -> }, { _, _ -> fail("Unexpected final") }, { _, _ -> failures++ })
            timer.advanceTo(10_000)
            engine.initialization.onSuccess()
        }
        drain()
        assertEquals(1, failures)
        assertEquals(0, engine.requests)
        assertEquals(1, engine.releases)
        main { assertTrue(timer.pending.isEmpty()); input.close() }
    }

    private class FakeTimer : CaptureTimer {
        data class Pending(val callback: Runnable, val at: Long)
        var now = 0L
        val pending = mutableListOf<Pending>()
        override fun nowMillis() = now
        override fun postDelayed(callback: Runnable, delayMillis: Long) {
            pending.add(Pending(callback, now + delayMillis))
        }
        override fun remove(callback: Runnable) {
            pending.removeAll { it.callback === callback }
        }
        fun advanceTo(target: Long) {
            require(target >= now)
            while (true) {
                val next = pending.minByOrNull { it.at }?.takeIf { it.at <= target } ?: break
                pending.remove(next)
                now = next.at
                next.callback.run()
            }
            now = target
        }
    }

    private class FakeAndroid : AndroidSpeechRecognizerEngine {
        lateinit var listener: RecognitionListener
        var starts = 0
        var cancels = 0
        var destroys = 0
        override fun setRecognitionListener(listener: RecognitionListener) { this.listener = listener }
        override fun startListening(intent: Intent) { starts++ }
        override fun cancel() { cancels++; if (::listener.isInitialized) listener.onError(SpeechRecognizer.ERROR_CLIENT) }
        override fun destroy() { destroys++ }
    }

    private class FakePleos : PleosSttEngine {
        lateinit var initialization: OnInitializeListener
        var listener: ResultListener? = null
        var requests = 0
        var stops = 0
        var releases = 0
        override fun initialize(listener: OnInitializeListener) { initialization = listener }
        override fun addListener(listener: ResultListener) { this.listener = listener }
        override fun removeListener(listener: ResultListener) = Unit
        override fun setLocale(locale: Locale) = Unit
        override fun request() { requests++ }
        override fun stop() { stops++; listener?.onEndedRecognition() }
        override fun release() { releases++ }
    }
}
