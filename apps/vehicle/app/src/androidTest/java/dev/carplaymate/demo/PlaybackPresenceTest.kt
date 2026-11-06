package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real local protocol/storage with controlled callbacks; no microphone or physical audio. */
@RunWith(AndroidJUnit4::class)
class PlaybackPresenceTest {
    @Test fun preparingBecomesStartedOnlyOnCallbackAndCompletionOpensInput() = runBlocking {
        scoped { h ->
            val preparing = h.startVoice()
            val before = requireNotNull(preparing.snapshot)
            val attempt = h.main { h.output.attempts.single() }
            h.main {
                assertEquals(OutputStage.PREPARING, h.model.ui.value.outputStage)
                assertEquals(OnuMood.THINKING, onuMood(h.model.ui.value))
                h.assertNoInput()
                attempt.started()
                assertEquals(OutputStage.STARTED, h.model.ui.value.outputStage)
                assertEquals(OnuMood.STORYTELLING, onuMood(h.model.ui.value))
                h.assertNoInput()
                // A start notification is presentation only: it cannot finish the protocol plan.
                assertEquals(before.version, h.model.ui.value.snapshot?.version)
                assertEquals(before.phase, h.model.ui.value.snapshot?.phase)
            }
            assertEquals(before.version, h.backend.snapshot(before.sessionId).version)
            h.main {
                attempt.complete()
                // Clear immediately, including while playback.completed is still being persisted.
                assertNull(h.model.ui.value.outputStage)
            }
            val listening = h.listening()
            h.assertListening(listening, before)
        }
    }

    @Test fun pauseBeforeStartRejectsLateStartAndCompletionWithoutReopeningInput() = runBlocking {
        scoped { h ->
            h.startVoice()
            val attempt = h.main { h.output.attempts.single() }
            h.main {
                h.model.pause()
                assertTrue(h.model.ui.value.quiet)
                assertNull(h.model.ui.value.outputStage)
                h.assertNoInput()
                // Callback races with the durable pause, not just after the server has caught up.
                attempt.started()
                attempt.complete()
                assertNull(h.model.ui.value.outputStage)
                assertEquals(OnuMood.RESTING, onuMood(h.model.ui.value))
            }
            val paused = requireNotNull(h.paused().snapshot)
            h.main {
                attempt.started()
                attempt.complete()
                h.assertQuiet()
                assertEquals(paused.version, h.model.ui.value.snapshot?.version)
                assertEquals(1, h.output.attempts.size)
            }
            val stored = h.backend.snapshot(paused.sessionId)
            assertEquals("PAUSED", stored.status)
            assertEquals(paused.version, stored.version)
            assertEquals(paused.turnEpoch, stored.turnEpoch)
        }
    }

    @Test fun replacedAndDuplicateStartCallbacksCannotChangeAnotherPlanOrListening() = runBlocking {
        scoped { h ->
            h.startVoice()
            val old = h.main { h.output.attempts.single() }
            h.main { h.model.pause() }
            h.paused()
            h.main { h.model.resume() }
            val resumed = h.await {
                !it.busy && !it.quiet && it.outputStage == OutputStage.PREPARING
            }
            val before = requireNotNull(resumed.snapshot)
            val current = h.main { h.output.attempts.last() }
            assertNotEquals(old.plan.playbackId, current.plan.playbackId)
            assertNotEquals(old.generation, current.generation)
            h.main {
                assertEquals(2, h.output.attempts.size)
                old.started()
                old.complete()
                assertEquals(OutputStage.PREPARING, h.model.ui.value.outputStage)
                assertEquals(OnuMood.THINKING, onuMood(h.model.ui.value))
                h.assertNoInput()
                current.started()
                val once = h.model.ui.value
                current.started()
                old.started()
                assertEquals(once, h.model.ui.value)
                assertEquals(OutputStage.STARTED, h.model.ui.value.outputStage)
                current.complete()
                current.started()
                assertNull(h.model.ui.value.outputStage)
            }
            val listening = h.listening()
            h.assertListening(listening, before)
            h.main {
                // Even this generation is obsolete once its listen window is open.
                current.started()
                old.started()
                current.complete()
                old.complete()
                assertNull(h.model.ui.value.outputStage)
                assertEquals(listening.snapshot?.version, h.model.ui.value.snapshot?.version)
                assertEquals(OnuMood.LISTENING, onuMood(h.model.ui.value))
                assertEquals(1, h.input.opens)
                assertEquals(2, h.output.attempts.size)
            }
            assertEquals(listening.snapshot?.version, h.backend.snapshot(before.sessionId).version)
        }
    }

    @Test fun completionWithoutStartStillOpensExactlyOneInputWindow() = runBlocking {
        scoped { h ->
            val preparing = h.startVoice()
            val before = requireNotNull(preparing.snapshot)
            val attempt = h.main { h.output.attempts.single() }
            h.main {
                assertEquals(OutputStage.PREPARING, h.model.ui.value.outputStage)
                attempt.complete()
                assertNull(h.model.ui.value.outputStage)
                // A duplicated completion cannot enqueue a second playback.completed command.
                attempt.complete()
                assertNull(h.model.ui.value.outputStage)
            }
            val listening = h.listening()
            h.assertListening(listening, before)
            h.main {
                attempt.started()
                attempt.complete()
                assertNull(h.model.ui.value.outputStage)
                assertEquals(1, h.input.opens)
                assertEquals(listening.snapshot?.listenWindowId, h.model.ui.value.snapshot?.listenWindowId)
            }
            assertEquals(before.version + 1, h.backend.snapshot(before.sessionId).version)
        }
    }

    @Test fun currentNarrationIntervalsAnimateWithoutAcknowledgingThePlan() = runBlocking {
        scoped { h ->
            val before = requireNotNull(h.startVoice().snapshot)
            val attempt = h.main { h.output.attempts.single() }
            h.main {
                assertFalse(h.model.ui.value.narrationActive)
                attempt.started()
                assertFalse(h.model.ui.value.narrationActive) // STARTED alone is not current speech.
                h.output.report(attempt, true)
            }
            h.await { it.narrationActive }
            h.main { h.assertNoInput(); h.output.report(attempt, false) }
            h.await { !it.narrationActive }
            h.main {
                assertEquals(OutputStage.STARTED, h.model.ui.value.outputStage)
                assertEquals(before.version, h.model.ui.value.snapshot?.version)
                h.assertNoInput()
                h.output.report(attempt, true)
            }
            h.await { it.narrationActive }
            h.main {
                attempt.complete()
                assertFalse(h.model.ui.value.narrationActive)
            }
            h.listening()
            h.main {
                h.output.report(attempt, false)
                h.output.report(attempt, true)
            }
            h.main { assertFalse(h.model.ui.value.narrationActive); assertEquals(1, h.input.opens) }
        }
    }

    @Test fun staleNarrationIdsAndGenerationsCannotAnimateOrClearAnotherPlan() = runBlocking {
        scoped { h ->
            h.startVoice()
            val old = h.main { h.output.attempts.single() }
            h.main { old.started(); h.output.report(old, true) }
            h.await { it.narrationActive }
            h.main { h.model.pause(); assertFalse(h.model.ui.value.narrationActive); h.output.report(old, true) }
            h.paused()
            h.main { assertFalse(h.model.ui.value.narrationActive); h.model.resume() }
            h.await { !it.busy && !it.quiet && it.outputStage == OutputStage.PREPARING }
            val current = h.main { h.output.attempts.last() }
            h.main { h.output.report(old, true) }
            h.main { assertFalse(h.model.ui.value.narrationActive) }
            h.main { current.started(); h.output.report(current, true) }
            h.await { it.narrationActive }
            h.main {
                // A delayed false signal with the new ID but old generation cannot stop animation.
                h.output.narrationState!!.value = SpeechNarrationState(current.plan.playbackId, old.generation, false)
            }
            h.main { assertTrue(h.model.ui.value.narrationActive); h.output.report(old, false) }
            h.main { assertTrue(h.model.ui.value.narrationActive); h.output.report(current, false) }
            h.await { !it.narrationActive }
            h.main { h.assertNoInput() }
        }
    }

    @Test fun unsupportedNarrationPortNeverInfersSpeechFromStarted() = runBlocking {
        scoped(reportNarration = false) { h ->
            h.startVoice()
            h.main {
                val attempt = h.output.attempts.single()
                attempt.started()
                assertEquals(OutputStage.STARTED, h.model.ui.value.outputStage)
                assertFalse(h.model.ui.value.narrationActive)
                attempt.complete()
                assertFalse(h.model.ui.value.narrationActive)
            }
            h.listening()
            h.main { assertFalse(h.model.ui.value.narrationActive) }
        }
    }

    @Test fun readingRestrictedAndUnknownModesClearNarrationAndRejectLateSignals() = runBlocking {
        for (mode in listOf("reading", "restricted", "unknown")) {
            scoped { h ->
                h.startVoice()
                val attempt = h.main { h.output.attempts.single() }
                h.main { attempt.started(); h.output.report(attempt, true) }
                h.await { it.narrationActive }
                when (mode) {
                    "reading" -> h.main { h.model.useSimulation(true) }
                    "restricted" -> h.restrictTo(VehicleAccessState.RESTRICTED)
                    else -> h.restrictTo(VehicleAccessState.UNKNOWN)
                }
                h.await { !it.busy && it.quiet && !it.narrationActive }
                h.main { h.output.report(attempt, true) }
                h.main { assertFalse(h.model.ui.value.narrationActive); h.assertNoInput() }
            }
        }
    }

    private suspend fun scoped(reportNarration: Boolean = true, block: suspend (Harness) -> Unit) {
        val harness = Harness(reportNarration)
        try { block(harness) } finally { harness.close() }
    }

    private class Harness(reportNarration: Boolean) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val application = ScopedApplication(instrumentation.targetContext)
        private val store = ViewModelStore()
        val backend = LocalGameClient(application)
        val output = Output(reportNarration)
        private val vehicle = Vehicle()
        val input = Input()
        private val focus = Focus()
        lateinit var model: DeveloperViewModel

        suspend fun startVoice(): DeveloperUiState {
            assertTrue(InteractionPreferences(application).save(InteractionMode.VOICE))
            model = main {
                ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        DeveloperViewModel(
                            application, null,
                            outputFactory = { output },
                            vehicleAccessProvider = vehicle,
                            inputFactory = { input },
                            captureSignalOutput = Signals,
                            inputFocusFactory = { focus },
                        ) as T
                })[DeveloperViewModel::class.java]
            }
            await { !it.busy && it.speechReady && it.adventures.isNotEmpty() }
            main {
                assertTrue(model.ui.value.useLocal)
                assertQuiet()
                model.start("별", null)
            }
            return await {
                !it.busy && !it.quiet && it.outputStage == OutputStage.PREPARING
            }.also {
                assertEquals(1, main { output.attempts.size })
                assertEquals("PROMPTING", it.snapshot?.phase)
            }
        }

        fun restrictTo(access: VehicleAccessState) = main {
            model.foreground(application)
            vehicle.emit(access)
        }

        suspend fun listening(): DeveloperUiState = await {
            !it.busy && !it.quiet && it.snapshot?.phase == "LISTENING" && it.microphoneListening
        }

        suspend fun paused(): DeveloperUiState = await {
            !it.busy && it.quiet && it.snapshot?.status == "PAUSED"
        }

        fun assertListening(state: DeveloperUiState, before: Snapshot) = main {
            val current = requireNotNull(state.snapshot)
            assertNull(state.outputStage)
            assertEquals(OnuMood.LISTENING, onuMood(state))
            assertEquals(before.sessionId, current.sessionId)
            assertEquals(before.turnId, current.turnId)
            assertEquals(before.turnEpoch, current.turnEpoch)
            assertEquals(before.version + 1, current.version)
            assertNotNull(current.listenWindowId)
            assertEquals(1, input.opens)
            assertTrue(input.active)
            assertTrue(focus.held)
            assertFalse(output.playing)
        }

        /** Call on main so port activity and the published UI are observed at the same boundary. */
        fun assertNoInput() {
            assertFalse(model.ui.value.microphoneListening)
            assertNull(model.ui.value.captureStage)
            assertFalse(input.active || focus.held)
            assertEquals(0, input.opens)
        }

        fun assertQuiet() {
            assertTrue(model.ui.value.quiet)
            assertNull(model.ui.value.outputStage)
            assertFalse(output.playing)
            assertNoInput()
        }

        suspend fun await(predicate: (DeveloperUiState) -> Boolean): DeveloperUiState = withTimeout(12_000) {
            var settled: DeveloperUiState
            do {
                model.ui.first(predicate)
                // An emission may precede port activation in the same main-thread callback.
                settled = main { model.ui.value }
            } while (!predicate(settled))
            settled
        }

        fun <T> main(action: () -> T): T {
            val task = FutureTask(Callable { action() })
            instrumentation.runOnMainSync(task)
            return task.get()
        }

        fun close() {
            main { store.clear() }
            application.clean()
        }
    }

    private class Output(reportNarration: Boolean) : SpeechOutput {
        override val narrationState = if (reportNarration) MutableStateFlow<SpeechNarrationState?>(null) else null
        fun report(attempt: Attempt, active: Boolean) {
            checkNotNull(narrationState).value = SpeechNarrationState(attempt.plan.playbackId, attempt.generation, active)
        }
        override val status = MutableStateFlow("시험용 음성 준비됨")
        override val readyState = MutableStateFlow(true)
        var playing = false
        private var current: Attempt? = null
        val attempts = mutableListOf<Attempt>()
        data class Attempt(
            val plan: SpeechPlan,
            val generation: Long,
            val started: () -> Unit,
            val complete: () -> Unit,
        )
        override fun refresh() = Unit
        override fun speak(
            plan: SpeechPlan, generation: Long, complete: () -> Unit,
            failure: (SpeechOutputFailure) -> Unit, started: () -> Unit,
        ) {
            lateinit var attempt: Attempt
            attempt = Attempt(plan, generation, started, {
                if (current === attempt) { current = null; playing = false }
                complete()
            })
            current = attempt
            attempts += attempt
            playing = true
        }
        // Deliberately retain all callbacks: the ViewModel must reject delayed provider events.
        override fun stop() { current = null; playing = false; narrationState?.value = null }
        override fun shutdown() = stop()
    }

    private class Input : SpeechCaptureInput {
        override val available = true
        override val source = "android_on_device"
        var active = false
        var opens = 0
        override fun unavailableReason(checkPermission: Boolean): String? = null
        override fun open(
            ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
            onFinal: (CaptureTicket, String) -> Unit,
            onFailure: (CaptureTicket, SpeechInputFailure) -> Unit,
        ) {
            opens++
            active = true
            onStage(ticket, CaptureStage.LISTENING)
        }
        override fun close() { active = false }
    }

    private class Focus : CaptureFocus {
        var held = false
        override fun acquire(): Boolean { held = true; return true }
        override fun release() { held = false }
    }

    private object Signals : CaptureSignalOutput {
        override fun ready() = Unit
        override fun processing() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    private class Vehicle : VehicleAccessProvider {
        override val initialState = VehicleAccessState.NON_AUTOMOTIVE_DEMO
        private var changed: ((VehicleAccessState) -> Unit)? = null
        override fun start(context: Context, changed: (VehicleAccessState) -> Unit) {
            this.changed = changed
            changed(initialState)
        }
        fun emit(access: VehicleAccessState) = checkNotNull(changed).invoke(access)
        override fun close() { changed = null }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "playback_presence_" + UUID.randomUUID()
        private val directory = File(context.noBackupFilesDir, prefix).also { check(it.mkdirs()) }
        private val names = mutableSetOf<String>()
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = prefix + "_" + name
            synchronized(names) { names += scoped }
            return super.getSharedPreferences(scoped, mode)
        }
        fun clean() {
            synchronized(names) { names.forEach { check(super.deleteSharedPreferences(it)) } }
            check(directory.deleteRecursively())
        }
    }
}
