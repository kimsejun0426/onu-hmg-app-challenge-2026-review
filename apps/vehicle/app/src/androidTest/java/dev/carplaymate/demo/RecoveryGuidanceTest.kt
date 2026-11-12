package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitialization
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real ViewModel/storage boundaries; deterministic ports do not claim physical audio capture. */
@RunWith(AndroidJUnit4::class)
class RecoveryGuidanceTest {
    @Test fun choosingAnAudioModeDuringPleosPreparationDoesNotCancelReadinessOrActivateAudio() = runBlocking {
        for (voiceInput in listOf(true, false)) {
            val engines = mutableListOf<PendingOutputEngine>()
            var focusAcquisitions = 0
            val playbackFocus = object : PleosPlaybackFocus {
                override fun acquire(onLost: () -> Unit): Boolean { focusAcquisitions++; return true }
                override fun release() = Unit
            }
            val h = Harness(false, outputOverride = { lost ->
                PleosSpeechOutput({ PendingOutputEngine().also(engines::add) }, { null }, playbackFocus,
                    lost, timer = PendingTimer())
            })
            try {
                h.prepareVoice()
                val preparing = h.await { !it.busy && !it.speechReady }
                assertFalse(canFinishInteractionSetup(preparing))
                // The real selection calls pause -> SpeechOutput.stop before saving the preference.
                h.main { h.model.useVoiceInput(voiceInput) }
                val selected = h.await { !it.busy && it.voiceInput == voiceInput && it.quiet }
                assertFalse(selected.speechReady)
                assertEquals(0, engines.single().releases)
                h.main { engines.single().initialization.onSuccess() }
                val prepared = h.await { !it.busy && it.speechReady }
                assertTrue(canFinishInteractionSetup(prepared))
                assertNull(prepared.snapshot)
                assertNull(prepared.speechProblem)
                assertNull(prepared.recoveryCause)
                assertEquals(if (voiceInput) InteractionMode.VOICE else InteractionMode.LISTEN_AND_SELECT,
                    InteractionPreferences(h.application).read().mode)
                assertEquals(1, engines.single().releases)
                assertEquals(0, engines.single().speaks)
                assertEquals(0, engines.single().stops)
                assertEquals(0, focusAcquisitions)
                assertTrue(h.input.attempts.isEmpty())
                h.assertQuiet()
            } finally { h.close() }
        }
    }

    @Test fun failedFirstCreationHasNoResumeTargetAndOnlyAnExplicitStartActivates() = runBlocking {
        scoped(remote = true) { h ->
            h.prepareVoice()
            val remote = requireNotNull(h.remote)
            remote.createFailure = true
            h.main { h.model.start("별", null) }
            val failed = h.await { !it.busy && it.quiet && it.recoveryCause == RecoveryCause.CONNECTION }
            assertNull(failed.snapshot)
            assertFalse(failed.hasSavedSession)
            assertNull(LocalCheckpoint(h.application).sessionId)
            val notice = failed.recoveryCause!!.notice(hasResumeTarget = false)
            assertTrue(notice.returnHome)
            assertTrue(notice.detail.contains("아직 이어갈 이야기가 없어요"))
            assertNoRawMessage(failed)
            h.assertQuiet()
            h.main { h.model.pause(); h.model.retrySpeech() }
            assertTrue(h.output.attempts.isEmpty() && h.input.attempts.isEmpty())
            assertEquals(1, remote.createCalls)
            assertEquals(RecoveryCause.CONNECTION, h.main { h.model.ui.value.recoveryCause })
            remote.createFailure = false
            h.main { h.model.start("별", null) }
            val started = h.await { !it.busy && !it.quiet && it.snapshot?.status == "ACTIVE" }
            assertNull(started.recoveryCause)
            assertEquals(started.snapshot!!.sessionId, LocalCheckpoint(h.application).sessionId)
            assertEquals(2, remote.createCalls)
            assertEquals(1, h.output.attempts.size)
        }
    }

    @Test fun typedInputReasonsSurviveSettingsPauseAndDeviceRefreshWithoutRawMessages() = runBlocking {
        val cases = listOf(
            RecoveryCause.INPUT_PERMISSION to "마이크 권한을 확인해 주세요",
            RecoveryCause.INPUT_LANGUAGE to "한국어 음성 입력이 필요해요",
            RecoveryCause.INPUT_BUSY to "음성 입력이 사용 중이에요",
        )
        for ((cause, title) in cases) scoped { h ->
            h.startVoice()
            val attempt = h.finishPrompt()
            h.main { attempt.fail(SpeechInputFailure(RAW, cause = cause)) }
            val stopped = h.paused()
            assertEquals(cause, stopped.recoveryCause)
            assertEquals(title, stopped.recoveryCause!!.notice().title)
            assertTrue(stopped.failure)
            h.assertQuiet()
            assertNoRawMessage(stopped)
            val outputCount = h.output.attempts.size
            val inputCount = h.input.attempts.size

            // Opening settings calls pause again; refreshing capabilities is not an activation.
            h.main { h.model.pause() }
            assertEquals(cause, h.paused().recoveryCause)
            h.main { h.model.retrySpeech() }
            val refreshed = h.paused()
            assertEquals(cause, refreshed.recoveryCause)
            assertEquals(title, refreshed.recoveryCause!!.notice().title)
            assertEquals(1, h.output.refreshes)
            assertEquals(outputCount, h.output.attempts.size)
            assertEquals(inputCount, h.input.attempts.size)
            assertNoRawMessage(refreshed)
            h.assertQuiet()
        }
    }

    @Test fun onlyConfirmedActivationClearsReasonAndOldCallbacksCannotRestoreIt() = runBlocking {
        scoped(remote = true) { h ->
            h.startVoice()
            val oldOutput = h.output.attempts.single()
            val oldInput = h.finishPrompt()
            h.main { oldInput.fail(SpeechInputFailure(RAW, cause = RecoveryCause.INPUT_BUSY)) }
            h.paused()
            val remote = requireNotNull(h.remote)
            val held = remote.holdNextResume()
            h.main { h.model.resume() }
            withTimeout(12_000) { held.arrived.await() }
            val pending = h.main { h.model.ui.value }
            assertTrue(pending.busy && pending.quiet)
            assertEquals(RecoveryCause.INPUT_BUSY, pending.recoveryCause)
            h.assertQuiet()
            // A successful transport response containing PAUSED is not consent to activate.
            held.result.complete(ResumeResult.PAUSED)
            assertEquals(RecoveryCause.INPUT_BUSY, h.paused().recoveryCause)

            val failing = remote.holdNextResume()
            h.main { h.model.resume() }
            withTimeout(12_000) { failing.arrived.await() }
            assertEquals(RecoveryCause.INPUT_BUSY, h.main { h.model.ui.value.recoveryCause })
            failing.result.complete(ResumeResult.FAIL)
            val disconnected = h.await { !it.busy && it.quiet && it.recoveryCause == RecoveryCause.CONNECTION }
            assertEquals("연결을 다시 확인해 주세요", disconnected.recoveryCause!!.notice().title)
            assertNoRawMessage(disconnected)

            h.main { h.model.resume() }
            val activated = h.await { !it.busy && !it.quiet && it.snapshot?.status == "ACTIVE" }
            assertNull(activated.recoveryCause)
            assertFalse(activated.failure)
            val version = activated.snapshot!!.version
            val outputCount = h.output.attempts.size
            val inputCount = h.input.attempts.size
            h.main {
                oldInput.stage(CaptureStage.LISTENING)
                oldInput.result(RAW)
                oldInput.fail(SpeechInputFailure(RAW, cause = RecoveryCause.INPUT_PERMISSION))
                oldOutput.fail(SpeechOutputFailure(RAW, RecoveryCause.OUTPUT_FAILED))
                oldOutput.complete()
            }
            val afterLate = h.main { h.model.ui.value }
            assertNull(afterLate.recoveryCause)
            assertFalse(afterLate.quiet || afterLate.failure || afterLate.microphoneListening)
            assertEquals(version, afterLate.snapshot!!.version)
            assertEquals(0, afterLate.snapshot.game.getJSONArray("history").length())
            assertEquals(outputCount, h.output.attempts.size)
            assertEquals(inputCount, h.input.attempts.size)
            assertNoRawMessage(afterLate)
        }
    }

    @Test fun focusAcquisitionAndEitherFocusLossStopBothPortsWithTheSameReason() = runBlocking {
        for (boundary in listOf("input-acquire", "input-loss", "output-loss")) scoped { h ->
            h.startVoice()
            when (boundary) {
                "input-acquire" -> {
                    h.focus.allowAcquire = false
                    h.main { h.output.attempts.last().complete() }
                }
                "input-loss" -> {
                    h.finishPrompt()
                    assertTrue(h.focus.held)
                    h.main { h.focus.lose() }
                }
                else -> {
                    assertTrue(h.output.playing)
                    h.main { h.output.loseFocus() }
                }
            }
            val stopped = h.paused()
            assertEquals(boundary, RecoveryCause.AUDIO_FOCUS, stopped.recoveryCause)
            assertEquals("준비되면 이어하기를 눌러 주세요.", stopped.recoveryCause!!.notice().detail)
            assertTrue(stopped.failure)
            h.assertQuiet()
            if (boundary == "input-acquire") assertTrue(h.input.attempts.isEmpty())
        }
    }

    @Test fun outputAndConnectionReasonsStayDistinctAndActualStorageDamageTakesPriority() = runBlocking {
        scoped(remote = true) { h ->
            h.startVoice()
            h.main { h.output.attempts.last().fail(SpeechOutputFailure(RAW, RecoveryCause.OUTPUT_PERMISSION)) }
            val outputFailure = h.paused()
            assertEquals(RecoveryCause.OUTPUT_PERMISSION, outputFailure.recoveryCause)
            assertEquals("음성 안내 권한을 확인해 주세요", outputFailure.recoveryCause!!.notice().title)
            assertFalse(outputFailure.localStorageProblem)
            assertNoRawMessage(outputFailure)
            h.main { h.output.readyState.value = false }
            h.await { !it.speechReady && it.speechProblem == RecoveryCause.OUTPUT_UNAVAILABLE }
            h.main {
                h.output.problem = SpeechOutputFailure(RAW, RecoveryCause.OUTPUT_LANGUAGE)
                h.output.status.value = "시험: 한국어 안내 준비 실패"
            }
            // ready remains false: a new status must still publish its precise current cause.
            val language = h.await { !it.speechReady && it.speechProblem == RecoveryCause.OUTPUT_LANGUAGE }
            assertEquals(RecoveryCause.OUTPUT_PERMISSION, language.recoveryCause)
            assertEquals("한국어 안내 음성이 필요해요", language.speechProblem!!.notice().title)
            assertNoRawMessage(language)
            h.assertQuiet()
            h.remote!!.snapshotFailure = true
            h.main { h.model.pause() }
            val disconnected = h.await { !it.busy && it.quiet && it.recoveryCause == RecoveryCause.CONNECTION }
            assertFalse(disconnected.localStorageProblem)
            assertEquals(RecoveryCause.OUTPUT_LANGUAGE, disconnected.speechProblem)
            assertEquals("연결을 다시 확인해 주세요", disconnected.recoveryCause!!.notice().title)
            assertNoRawMessage(disconnected)
            h.assertQuiet()
        }
        scoped { h ->
            h.startVoice()
            h.main { h.output.attempts.last().fail(SpeechOutputFailure(RAW, RecoveryCause.OUTPUT_FAILED)) }
            val stopped = h.paused()
            assertEquals(RecoveryCause.OUTPUT_FAILED, stopped.recoveryCause)
            // Corrupt a real encrypted session's model, not a UI flag or a mocked storage error.
            val document = readRecordStoreDocument(h.backend)
            document.getJSONObject("sessions").getJSONObject(stopped.snapshot!!.sessionId)
                .getJSONObject("state").remove("game")
            writeRecordStoreDocument(h.backend, document)
            val damagedBytes = recordStoreCiphertextSnapshot(h.backend)
            val outputCount = h.output.attempts.size
            val inputCount = h.input.attempts.size
            h.main { h.model.resume() }
            val blocked = h.await { !it.busy && it.localStorageProblem }
            assertEquals(RecoveryCause.OUTPUT_FAILED, blocked.recoveryCause)
            assertEquals("저장된 기록을 안전하게 읽지 못했어요. 기록을 덮어쓰거나 새 가족으로 바꾸지 않았어요.", blocked.message)
            assertTrue(blocked.failure)
            // The storage guard must remain stronger than a recovery-mode choice or refresh.
            h.main {
                h.model.retrySpeech()
                h.model.useSimulation(true)
                h.model.resume()
                h.model.start("별", null)
            }
            assertTrue(h.model.ui.value.localStorageProblem)
            assertFalse(h.model.ui.value.simulation)
            assertEquals(blocked.message, h.model.ui.value.message)
            assertEquals(damagedBytes, recordStoreCiphertextSnapshot(h.backend))
            assertEquals(outputCount, h.output.attempts.size)
            assertEquals(inputCount, h.input.attempts.size)
            assertNoRawMessage(h.model.ui.value)
            h.assertQuiet()
        }
    }

    @Test fun silenceAndUserPauseAreNotErrorsAndReadingChoiceWaitsForExplicitStart() = runBlocking {
        scoped { h ->
            h.startVoice()
            val silentInput = h.finishPrompt()
            h.main { silentInput.fail(SpeechInputFailure(RAW, pauseReason = "NO_RESPONSE", cause = null)) }
            val silence = h.paused()
            assertNull(silence.recoveryCause)
            assertFalse(silence.failure)
            h.main { h.model.pause() }
            val voluntary = h.paused()
            assertNull(voluntary.recoveryCause)
            assertFalse(voluntary.failure)
            h.assertQuiet()

            h.main { h.model.resume() }
            h.await { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            val failedInput = h.finishPrompt()
            h.main { failedInput.fail(SpeechInputFailure(RAW, cause = RecoveryCause.INPUT_PERMISSION)) }
            h.paused()
            val outputCount = h.output.attempts.size
            val inputCount = h.input.attempts.size
            h.main { h.model.useSimulation(true) }
            val reading = h.await { !it.busy && it.quiet && it.simulation }
            assertTrue(reading.interactionConfigured)
            assertFalse(reading.failure || reading.voiceInput)
            assertTrue(canFinishInteractionSetup(reading))
            assertEquals(InteractionMode.READING, InteractionPreferences(h.application).read().mode)
            assertEquals(RecoveryCause.INPUT_PERMISSION, reading.recoveryCause)
            h.assertQuiet()
            assertEquals(outputCount, h.output.attempts.size)
            assertEquals(inputCount, h.input.attempts.size)

            h.main { h.model.start("별", null) }
            val started = h.await { !it.busy && !it.quiet && it.simulation && it.snapshot?.speechPlan != null }
            assertNull(started.recoveryCause)
            assertFalse(started.failure || started.microphoneListening)
            assertNotEquals(reading.snapshot!!.sessionId, started.snapshot!!.sessionId)
            assertEquals(outputCount, h.output.attempts.size)
            assertEquals(inputCount, h.input.attempts.size)
            assertFalse(h.output.playing || h.input.active || h.focus.held)
            assertNoRawMessage(started)
        }
    }

    private fun assertNoRawMessage(state: DeveloperUiState) {
        val notice = state.recoveryCause?.notice()
        val currentOutput = state.speechProblem?.notice()
        for (text in listOfNotNull(state.message, state.inputStatus, state.voiceInputProblem,
                state.speechStatus, state.authoredDialogue, notice?.title, notice?.detail,
                currentOutput?.title, currentOutput?.detail))
            assertFalse("Provider diagnostic/captured text escaped into UI", text.contains(RAW))
    }

    private suspend fun scoped(remote: Boolean = false, block: suspend (Harness) -> Unit) {
        val h = Harness(remote)
        try { block(h) } finally { h.close() }
    }

    private enum class ResumeResult { PAUSED, FAIL }
    private class HeldResume {
        val arrived = CompletableDeferred<Unit>()
        val result = CompletableDeferred<ResumeResult>()
    }

    /** Controls only remote response timing/outcome; game operations still use the real engine. */
    private class ControlledClient(private val delegate: LocalGameClient) : GameClient by delegate {
        var snapshotFailure = false
        var createFailure = false
        var createCalls = 0
        private var nextResume: HeldResume? = null
        fun holdNextResume() = HeldResume().also { nextResume = it }
        override suspend fun createAdventure(players: JSONObject, packId: String): Snapshot {
            createCalls++
            if (createFailure) throw IOException(RAW)
            return delegate.createAdventure(players, packId)
        }
        override suspend fun snapshot(id: String): Snapshot {
            if (snapshotFailure) throw IOException(RAW)
            return delegate.snapshot(id)
        }
        override suspend fun command(id: String, body: JSONObject): JSONObject {
            val held = if (body.getString("type") == "session.resume") nextResume else null
            if (held == null) return delegate.command(id, body)
            nextResume = null
            held.arrived.complete(Unit)
            return when (held.result.await()) {
                ResumeResult.PAUSED -> JSONObject().put("command_status", "APPLIED")
                    .put("snapshot", delegate.snapshotDocument(id))
                ResumeResult.FAIL -> throw IOException(RAW)
            }
        }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "recovery_guidance_" + UUID.randomUUID()
        private val directory = File(context.noBackupFilesDir, prefix).also { check(it.mkdirs()) }
        private val names = mutableSetOf<String>()
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "${prefix}_$name"
            synchronized(names) { names += scoped }
            return super.getSharedPreferences(scoped, mode)
        }
        fun clean() {
            synchronized(names) { names.forEach { check(super.deleteSharedPreferences(it)) } }
            check(directory.deleteRecursively())
        }
    }

    private class Input : SpeechCaptureInput {
        override val available = true
        override val source = "android_on_device"
        override fun unavailableReason(checkPermission: Boolean): String? = null
        var active = false
        data class Attempt(val stage: (CaptureStage) -> Unit, val result: (String) -> Unit,
            val fail: (SpeechInputFailure) -> Unit)
        val attempts = mutableListOf<Attempt>()
        override fun open(ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
            onFinal: (CaptureTicket, String) -> Unit, onFailure: (CaptureTicket, SpeechInputFailure) -> Unit) {
            active = true
            attempts += Attempt({ onStage(ticket, it) }, { onFinal(ticket, it) }, { onFailure(ticket, it) })
            onStage(ticket, CaptureStage.LISTENING)
        }
        override fun close() { active = false } // Old callbacks intentionally remain callable.
    }

    private class Output : SpeechOutput {
        override val status = MutableStateFlow("시험용 음성 준비됨")
        override val readyState = MutableStateFlow(true)
        var problem: SpeechOutputFailure? = null
        override val availabilityFailure: SpeechOutputFailure?
            get() = if (readyState.value) null else problem ?:
                SpeechOutputFailure("시험: 음성 준비 대기", RecoveryCause.OUTPUT_UNAVAILABLE)
        var playing = false
        var refreshes = 0
        var onFocusLost: () -> Unit = {}
        data class Attempt(val complete: () -> Unit, val fail: (SpeechOutputFailure) -> Unit)
        val attempts = mutableListOf<Attempt>()
        override fun refresh() { refreshes++ }
        override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
            failure: (SpeechOutputFailure) -> Unit,
            started: () -> Unit,
        ) {
            playing = true
            attempts += Attempt({ complete() }, { failure(it) })
        }
        fun loseFocus() { playing = false; onFocusLost() }
        override fun stop() { playing = false }
        override fun shutdown() = stop()
    }

    private class Focus : CaptureFocus {
        var held = false
        var allowAcquire = true
        var onLost: () -> Unit = {}
        override fun acquire(): Boolean { held = allowAcquire; return held }
        override fun release() { held = false }
        fun lose() { held = false; onLost() }
    }

    private object Signals : CaptureSignalOutput {
        override fun ready() = Unit
        override fun processing() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    private class Harness(useRemote: Boolean, private val outputOverride: ((() -> Unit) -> SpeechOutput)? = null) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ScopedApplication(instrumentation.targetContext)
        val backend = LocalGameClient(application)
        val remote = if (useRemote) ControlledClient(backend) else null
        val input = Input()
        val output = Output()
        val focus = Focus()
        private val store = ViewModelStore()
        lateinit var model: DeveloperViewModel
        suspend fun prepareVoice() {
            assertTrue(InteractionPreferences(application).save(InteractionMode.VOICE))
            model = main {
                ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        DeveloperViewModel(application, remote,
                            outputFactory = { lost -> outputOverride?.invoke(lost) ?: output.also { it.onFocusLost = lost } },
                            inputFactory = { input }, captureSignalOutput = Signals,
                            inputFocusFactory = { lost -> focus.also { it.onLost = lost } }) as T
                })[DeveloperViewModel::class.java]
            }
            await { !it.busy }
        }
        suspend fun startVoice() {
            prepareVoice()
            main { model.start("별", null) }
            await { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            assertEquals(1, output.attempts.size)
        }
        suspend fun finishPrompt(): Input.Attempt {
            main { output.playing = false; output.attempts.last().complete() }
            await { !it.busy && it.microphoneListening }
            return main { input.attempts.last() }
        }
        suspend fun paused(): DeveloperUiState = await {
            !it.busy && it.quiet && it.snapshot?.status == "PAUSED"
        }
        fun assertQuiet() = main {
            assertTrue(model.ui.value.quiet)
            assertFalse(model.ui.value.microphoneListening || input.active || output.playing || focus.held)
            assertNull(model.ui.value.captureStage)
        }
        suspend fun await(predicate: (DeveloperUiState) -> Boolean): DeveloperUiState = withTimeout(12_000) {
            var settled: DeveloperUiState
            do {
                model.ui.first(predicate)
                // A published state may precede opening/closing a port in the same main callback.
                settled = main { model.ui.value }
            } while (!predicate(settled))
            settled
        }
        fun close() {
            main { store.clear() }
            application.clean()
        }
        fun <T> main(action: () -> T): T {
            val task = FutureTask(Callable { action() })
            instrumentation.runOnMainSync(task)
            return task.get()
        }
    }

    private class PendingOutputEngine : PleosTtsEngine {
        lateinit var initialization: TtsInitialization
        var speaks = 0
        var stops = 0
        var releases = 0
        override fun initialize(listener: TtsInitialization) { initialization = listener }
        override fun addListener(listener: EventListener) = Unit
        override fun removeListener(listener: EventListener) = Unit
        override fun setLocale(locale: Locale) = Unit
        override fun setAudioAttributes(attributes: AudioAttributes) = Unit
        override fun speak(text: String) { speaks++ }
        override fun stop() { stops++ }
        override fun release() { releases++ }
    }

    /** This regression controls initialization explicitly; deadline behavior is tested in the adapter suite. */
    private class PendingTimer : CaptureTimer {
        private val callbacks = mutableSetOf<Runnable>()
        override fun nowMillis(): Long = 0L
        override fun postDelayed(callback: Runnable, delayMillis: Long) { callbacks += callback }
        override fun remove(callback: Runnable) { callbacks -= callback }
    }

    private companion object {
        const val RAW = "RAW_PROVIDER_SENTINEL_DO_NOT_RENDER_오누_원문"
    }
}
