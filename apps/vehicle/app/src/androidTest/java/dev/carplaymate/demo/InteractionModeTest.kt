package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
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

/** Real encrypted preferences/ViewModel lifecycle; speech and focus are deterministic test ports. */
@RunWith(AndroidJUnit4::class)
class InteractionModeTest {
    @Test fun firstChoiceIsRequiredEvenForDefaultAndRetryNeverChoosesIt() = runBlocking {
        scoped { h ->
            h.create()
            val fresh = h.await { !it.busy }
            assertFalse(fresh.interactionConfigured)
            assertFalse(canFinishInteractionSetup(fresh))
            assertFalse(InteractionPreferences(h.application).read().configured)
            h.main { h.model.microphonePermissionResult(false); h.model.retrySpeech() }
            assertFalse(h.model.ui.value.interactionConfigured)
            assertFalse(h.application.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
                .contains(InteractionPreferences.KEY))
            // Selecting the initial default still creates a deliberate, valid preference.
            h.main { h.model.useSimulation(false) }
            assertTrue(h.model.ui.value.interactionConfigured)
            assertTrue(InteractionPreferences(h.application).read().configured)
            assertEquals(InteractionMode.LISTEN_AND_SELECT, InteractionPreferences(h.application).read().mode)
            h.recreate()
            assertTrue(h.await { !it.busy }.interactionConfigured)
            assertTrue(h.model.ui.value.quiet)
            assertEquals(0, h.output.speaks)
            assertTrue(h.input.attempts.isEmpty())
        }
    }

    @Test fun failedFirstChoiceCannotBecomeConfiguredAfterRetryOrRecreation() = runBlocking {
        scoped { h ->
            h.create()
            h.await { !it.busy }
            h.application.failNextCommit = true
            h.main { h.model.useSimulation(true) }
            assertTrue(h.model.ui.value.failure)
            assertFalse(h.model.ui.value.interactionConfigured)
            assertFalse(h.model.ui.value.simulation)
            assertFalse(InteractionPreferences(h.application).read().configured)
            h.main { h.model.retrySpeech() }
            assertFalse(h.model.ui.value.interactionConfigured)
            h.recreate()
            assertFalse(h.await { !it.busy }.interactionConfigured)
            assertEquals(0, h.output.speaks)
            assertTrue(h.input.attempts.isEmpty())
        }
    }

    @Test fun exactInputAndOutputProblemsRecoverQuietlyWithoutChangingSavedMode() = runBlocking {
        scoped { h ->
            assertTrue(InteractionPreferences(h.application).save(InteractionMode.VOICE))
            h.input.problem = "시험: 음성 서비스 버전이 맞지 않아요."
            h.output.status.value = "시험: 음성 안내 권한이 필요해요."
            h.output.readyState.value = false
            h.create()
            val unavailable = h.await { !it.busy && it.speechStatus == h.output.status.value }
            assertTrue(unavailable.interactionConfigured && unavailable.voiceInput)
            assertEquals(h.input.problem, unavailable.voiceInputProblem)
            assertFalse(unavailable.speechReady)
            assertFalse(canFinishInteractionSetup(unavailable))
            h.main { h.model.retrySpeech() }
            assertEquals(h.input.problem, h.model.ui.value.voiceInputProblem)
            h.input.problem = null
            h.output.status.value = "시험: 기기 음성이 준비됐어요."
            h.output.readyState.value = true
            h.main { h.model.retrySpeech() }
            val recovered = h.await { it.speechReady && it.speechStatus == h.output.status.value }
            assertNull(recovered.voiceInputProblem)
            assertTrue(recovered.voiceInput && recovered.interactionConfigured && recovered.quiet)
            assertTrue(canFinishInteractionSetup(recovered))
            assertEquals(InteractionMode.VOICE, InteractionPreferences(h.application).read().mode)
            assertEquals(0, h.output.speaks)
            assertTrue(h.input.attempts.isEmpty())
            val intent = familyAppPermissionsIntent(h.application.packageName)
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            assertEquals("package:${h.application.packageName}", intent.data.toString())
        }
    }

    @Test fun authoredNameConfirmationSurvivesPlaybackCompletionButNeverCopiesCapturedSpeech() = runBlocking {
        scoped { h ->
            h.create()
            h.await { !it.busy }
            h.main { h.model.useSimulation(true); h.model.start("민준", "서윤") }
            h.await { !it.busy && it.snapshot?.speechPlan != null }
            h.main { h.model.simulatePlaybackCompletion() }
            h.await { !it.busy && it.snapshot?.phase == "LISTENING" }
            h.main { h.model.submit("물가") }
            val naming = h.await { !it.busy && it.snapshot?.speechPlan != null }
            val authoredName = naming.snapshot!!.speechPlan!!.segments.joinToString("\n") { it.text }
            assertTrue(authoredName.contains("이름만"))
            assertEquals(authoredName, naming.authoredDialogue)
            h.main { h.model.simulatePlaybackCompletion() }
            val waitingForName = h.await { !it.busy && it.snapshot?.phase == "LISTENING" }
            assertNull(waitingForName.snapshot!!.speechPlan)
            assertEquals(authoredName, waitingForName.authoredDialogue)
            assertNotEquals(waitingForName.snapshot.presentation.getString("prompt"), waitingForName.authoredDialogue)
            h.main { h.model.submit("민준") }
            val nextTurn = h.await { !it.busy && it.snapshot?.game?.optInt("turn_index") == 1 }
            assertNotEquals(authoredName, nextTurn.authoredDialogue)
            assertEquals(nextTurn.snapshot!!.speechPlan!!.segments.joinToString("\n") { it.text }, nextTurn.authoredDialogue)
            h.main { h.model.simulatePlaybackCompletion() }
            h.await { !it.busy && it.snapshot?.phase == "LISTENING" }
            val captured = "개인 대화 원문 검증용 고래가 춤춘다"
            h.main { h.model.submit(captured) }
            val repaired = h.await { !it.busy && it.snapshot?.phase != "LISTENING" }
            assertFalse(repaired.authoredDialogue.contains(captured))
            h.main { h.model.pause() }
            h.await { !it.busy && it.quiet }
            h.main {
                assertTrue(h.model.selectAdventure("starlight-post-office"))
                h.model.start("민준", "서윤")
            }
            val other = h.await { !it.busy && !it.quiet && it.snapshot?.game?.optString("pack_id") == "starlight-post-office" }
            assertEquals(other.snapshot!!.speechPlan!!.segments.joinToString("\n") { it.text }, other.authoredDialogue)
            assertNotEquals(authoredName, other.authoredDialogue)
            h.main { h.model.pause() }
            h.await { !it.busy && it.quiet }
            h.recreate()
            val restored = h.await { !it.busy && it.snapshot != null }
            assertEquals(restored.snapshot!!.presentation.getString("prompt"), restored.authoredDialogue)
            assertTrue(restored.quiet)
            h.main { h.model.forgetFamily() }
            h.await { !it.busy && !it.familyDeletionPending && it.snapshot == null }
            assertEquals("", h.model.ui.value.authoredDialogue)
            assertEquals(0, h.output.speaks) // Authored text is not evidence of audible playback.
        }
    }

    @Test fun allModesSurviveRecreationButNeverStartAudioOrInput() = runBlocking {
        scoped { h ->
            h.create()
            h.await { !it.busy }
            h.main { assertTrue(h.model.saveFamily("별", null)) }
            val family = FamilyPreferences(h.application).profile
            for (mode in InteractionMode.entries) {
                h.main {
                    when (mode) {
                        InteractionMode.VOICE -> h.model.useVoiceInput(true)
                        InteractionMode.READING -> h.model.useSimulation(true)
                        InteractionMode.LISTEN_AND_SELECT -> h.model.useVoiceInput(false)
                    }
                }
                assertEquals(mode, InteractionPreferences(h.application).read().mode)
                assertTrue(InteractionPreferences(h.application).read().configured)
                val raw = h.application.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
                    .getString(InteractionPreferences.KEY, null)!!
                assertFalse(raw.contains(mode.name))
                h.recreate()
                val restored = h.await { !it.busy }
                assertEquals(mode == InteractionMode.VOICE, restored.voiceInput)
                assertTrue(restored.interactionConfigured)
                assertEquals(mode == InteractionMode.READING, restored.simulation)
                assertTrue(restored.quiet)
                assertFalse(restored.microphoneListening)
                assertNull(restored.captureStage)
                assertEquals(0, h.output.speaks)
                assertTrue(h.input.attempts.isEmpty())
                assertTrue(h.signals.events.none { it == "ready" || it == "processing" })
                assertEquals(family, FamilyPreferences(h.application).profile)
            }
        }
    }

    @Test fun restoredVoiceChecksCapabilityBeforeNewAdventureAndResumeWithoutDowngrading() = runBlocking {
        scoped { h ->
            assertTrue(InteractionPreferences(h.application).save(InteractionMode.VOICE))
            h.input.problem = "시험: 마이크 권한 없음"
            h.create()
            h.await { !it.busy }
            h.main { h.model.start("별", null) }
            assertTrue(h.model.ui.value.voiceInput)
            assertTrue(h.model.ui.value.quiet && h.model.ui.value.failure)
            assertNull(h.model.ui.value.snapshot)
            assertEquals(0, h.output.speaks)
            assertTrue(h.input.attempts.isEmpty())
            h.input.problem = null
            h.main { h.model.start("별", null) }
            h.await { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            assertEquals(1, h.output.speaks)
            h.main { h.model.pause() }
            h.await { !it.busy && it.quiet && it.snapshot?.status == "PAUSED" }
            h.recreate()
            h.await { !it.busy && it.hasSavedSession }
            assertEquals(1, h.output.speaks)
            assertTrue(h.model.ui.value.voiceInput)
            h.input.problem = "시험: 서비스 사용 불가"
            h.main { h.model.resume() }
            h.await { !it.busy && it.quiet }
            assertTrue(h.model.ui.value.voiceInput)
            assertEquals(RecoveryCause.INPUT_UNAVAILABLE, h.model.ui.value.recoveryCause)
            assertTrue(h.model.ui.value.inputStatus.contains("이 기기의 음성 입력을 사용할 수 없어요."))
            assertFalse(h.model.ui.value.inputStatus.contains("시험: 서비스 사용 불가"))
            assertEquals(1, h.output.speaks)
            assertTrue(h.input.attempts.isEmpty())
            h.input.problem = null
            h.main { h.model.resume() }
            h.await { !it.busy && !it.quiet }
            assertEquals(2, h.output.speaks)
        }
    }

    @Test fun permissionDenialPreservesVoicePreferenceAndGrantDoesNotStartListening() = runBlocking {
        scoped { h ->
            h.create()
            h.await { !it.busy }
            h.main { h.model.useVoiceInput(true); h.model.microphonePermissionResult(false) }
            assertEquals(InteractionMode.VOICE, InteractionPreferences(h.application).read().mode)
            assertTrue(h.model.ui.value.voiceInput)
            assertTrue(h.model.ui.value.quiet)
            h.main { h.model.microphonePermissionResult(true) }
            assertTrue(h.model.ui.value.quiet)
            assertFalse(h.model.ui.value.microphoneListening)
            assertEquals(0, h.output.speaks)
            assertTrue(h.input.attempts.isEmpty())
        }
    }

    @Test fun explicitSameModeReselectionRecoversAfterPermissionDenialWithoutStartingAudio() = runBlocking {
        for (mode in InteractionMode.entries) scoped { h ->
            h.create()
            h.await { !it.busy }
            fun selectMode() {
                when (mode) {
                    InteractionMode.VOICE -> h.model.useVoiceInput(true)
                    InteractionMode.READING -> h.model.useSimulation(true)
                    InteractionMode.LISTEN_AND_SELECT -> h.model.useVoiceInput(false)
                }
            }
            h.main { selectMode() }
            assertEquals(mode, InteractionPreferences(h.application).read().mode)
            h.input.problem = "시험: 마이크 권한 없음"
            h.main { h.model.microphonePermissionResult(false) }
            assertTrue(h.model.ui.value.failure)
            assertFalse(canFinishInteractionSetup(h.model.ui.value))
            assertEquals(mode, InteractionPreferences(h.application).read().mode)
            if (mode == InteractionMode.VOICE) {
                // Re-selecting still-unavailable voice must not pretend it is usable.
                h.main { selectMode() }
                assertTrue(h.model.ui.value.failure)
                assertFalse(canFinishInteractionSetup(h.model.ui.value))
                h.input.problem = null
            }
            // A capability change alone is not a new selection or an activation.
            assertTrue(h.model.ui.value.failure)
            h.main { selectMode() }
            val recovered = h.model.ui.value
            assertTrue(recovered.interactionConfigured && recovered.quiet)
            assertFalse(recovered.failure)
            assertTrue(canFinishInteractionSetup(recovered))
            assertEquals(mode == InteractionMode.READING, recovered.simulation)
            assertEquals(mode == InteractionMode.VOICE, recovered.voiceInput)
            assertEquals(mode, InteractionPreferences(h.application).read().mode)
            assertFalse(recovered.microphoneListening)
            assertNull(recovered.captureStage)
            assertEquals(0, h.output.speaks)
            assertTrue(h.input.attempts.isEmpty())
            assertTrue(h.signals.events.none { it == "ready" || it == "processing" })
        }
    }

    @Test fun realReadyStagesOwnOneCueAndPauseFencesLateStages() = runBlocking {
        scoped { h ->
            h.create()
            h.await { !it.busy }
            h.main { h.model.useVoiceInput(true); h.model.start("별", null) }
            h.await { !it.busy && it.snapshot?.speechPlan != null }
            assertFalse(h.signals.events.contains("ready"))
            h.main { h.output.complete() }
            h.await { !it.busy && it.snapshot?.phase == "LISTENING" }
            val first = h.main { h.input.attempts.single() }
            assertTrue(h.focus.held)
            assertFalse(h.model.ui.value.microphoneListening)
            h.main { first.stage(CaptureStage.LISTENING); first.stage(CaptureStage.LISTENING) }
            assertEquals(1, h.signals.events.count { it == "ready" })
            assertEquals(CaptureStage.LISTENING, h.model.ui.value.captureStage)
            assertTrue(h.model.ui.value.microphoneListening)
            h.main { first.stage(CaptureStage.PROCESSING); first.stage(CaptureStage.PROCESSING) }
            assertEquals(1, h.signals.events.count { it == "processing" })
            assertEquals(CaptureStage.PROCESSING, h.model.ui.value.captureStage)
            assertFalse(h.model.ui.value.microphoneListening)
            h.main { h.model.pause() }
            h.await { !it.busy && it.quiet }
            val count = h.signals.events.count { it == "ready" || it == "processing" }
            h.main { first.stage(CaptureStage.LISTENING); first.stage(CaptureStage.PROCESSING); first.result("별 물가") }
            assertEquals(count, h.signals.events.count { it == "ready" || it == "processing" })
            assertFalse(h.focus.held)
            assertNull(h.model.ui.value.captureStage)
            assertTrue(h.model.ui.value.quiet)
            assertEquals(0, h.model.ui.value.snapshot!!.game.getJSONArray("history").length())
            h.main { h.model.resume() }
            h.await { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            h.main { h.output.complete() }
            h.await { !it.busy && it.snapshot?.phase == "LISTENING" }
            val second = h.main { h.input.attempts.last() }
            h.main { second.stage(CaptureStage.LISTENING); h.focus.lose() }
            h.await { !it.busy && it.quiet }
            val afterLoss = h.signals.events.count { it == "ready" || it == "processing" }
            h.main { second.stage(CaptureStage.PROCESSING) }
            assertEquals(afterLoss, h.signals.events.count { it == "ready" || it == "processing" })
            assertFalse(h.focus.held)
        }
    }

    @Test fun fullFamilyDeletionClearsInteractionPreferenceAndReturnsToQuietDefault() = runBlocking {
        scoped { h ->
            h.create()
            h.await { !it.busy }
            h.main {
                assertTrue(h.model.saveFamily("별", null))
                h.model.useVoiceInput(true)
                h.model.forgetFamily()
            }
            h.await { !it.busy && !it.familyDeletionPending && !it.onboarded }
            assertFalse(h.application.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
                .contains(InteractionPreferences.KEY))
            h.recreate()
            val restored = h.await { !it.busy }
            assertFalse(restored.voiceInput || restored.simulation || restored.microphoneListening)
            assertFalse(restored.interactionConfigured)
            assertFalse(InteractionPreferences(h.application).read().configured)
            assertTrue(restored.quiet)
            assertEquals(0, h.output.speaks)
        }
    }

    @Test fun failedModeCommitPreservesPreviousModeAndReportsFailure() = runBlocking {
        scoped { h ->
            h.create()
            h.await { !it.busy }
            h.main { h.model.useSimulation(true) }
            h.application.failNextCommit = true
            h.main { h.model.useVoiceInput(true) }
            val state = h.model.ui.value
            assertTrue(state.simulation && state.quiet && state.failure)
            assertFalse(state.voiceInput || state.microphoneListening)
            assertTrue(state.message.contains("저장하지 못"))
            assertEquals(InteractionMode.READING, InteractionPreferences(h.application).read().mode)
            // Re-selecting the preserved reading mode must not clear an error if saving
            // that deliberate choice still fails.
            h.application.failNextCommit = true
            h.main { h.model.useSimulation(true) }
            assertFalse(h.application.failNextCommit)
            assertTrue(h.model.ui.value.simulation && h.model.ui.value.quiet && h.model.ui.value.failure)
            assertFalse(canFinishInteractionSetup(h.model.ui.value))
            assertEquals(InteractionMode.READING, InteractionPreferences(h.application).read().mode)
            h.recreate()
            assertTrue(h.await { !it.busy }.simulation)
            assertEquals(0, h.output.speaks)
        }
    }

    @Test fun damagedModeCannotBeOverwrittenAndLegacyAbsenceDefaultsWithoutRewrite() = runBlocking {
        scoped { h ->
            val prefs = h.application.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
            assertTrue(prefs.edit().putString("first", "예전별").commit())
            val before = prefs.all.toMap()
            assertEquals(InteractionMode.LISTEN_AND_SELECT, InteractionPreferences(h.application).read().mode)
            assertFalse(InteractionPreferences(h.application).read().configured)
            assertEquals(before, prefs.all)
            assertTrue(prefs.edit().putString(InteractionPreferences.KEY, "damaged-ciphertext").commit())
            assertTrue(InteractionPreferences(h.application).read().unavailable)
            assertFalse(InteractionPreferences(h.application).save(InteractionMode.VOICE))
            h.create()
            h.await { !it.busy && it.localStorageProblem }
            h.main {
                h.model.useSimulation(true)
                h.model.useVoiceInput(false)
                h.model.useVoiceInput(true)
            }
            assertTrue(h.model.ui.value.quiet)
            assertTrue(h.model.ui.value.localStorageProblem) // Not a fresh setup when decrypting fails.
            assertEquals("damaged-ciphertext", prefs.getString(InteractionPreferences.KEY, null))
            assertEquals(0, h.output.speaks)
        }
    }

    private suspend fun scoped(block: suspend (Harness) -> Unit) {
        val h = Harness()
        try { block(h) } finally { h.close() }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "interaction_" + UUID.randomUUID()
        private val directory = File(context.noBackupFilesDir, prefix).also { check(it.mkdirs()) }
        private val names = mutableSetOf<String>()
        var failNextCommit = false
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = prefix + "_" + name
            synchronized(names) { names += scoped }
            val delegate = super.getSharedPreferences(scoped, mode)
            if (name != "family_v1") return delegate
            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        // Return this proxy so fluent calls keep the injected commit boundary.
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }
                        override fun remove(key: String?): SharedPreferences.Editor {
                            editor.remove(key)
                            return this
                        }
                        override fun commit(): Boolean {
                            if (!failNextCommit) return editor.commit()
                            failNextCommit = false
                            editor.commit() // Models memory updated before a reported failure.
                            return false
                        }
                    }
                }
            }
        }
        fun clean() {
            synchronized(names) { names.forEach { check(super.deleteSharedPreferences(it)) } }
            check(directory.deleteRecursively())
        }
    }

    private class Input : SpeechCaptureInput {
        var problem: String? = null
        override val available get() = problem == null
        override val source = "android_on_device"
        override fun unavailableReason(checkPermission: Boolean) = problem
        data class Attempt(
            val stage: (CaptureStage) -> Unit,
            val result: (String) -> Unit,
        )
        val attempts = mutableListOf<Attempt>()
        override fun open(ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
            onFinal: (CaptureTicket, String) -> Unit, onFailure: (CaptureTicket, SpeechInputFailure) -> Unit) {
            attempts += Attempt({ onStage(ticket, it) }, { onFinal(ticket, it) })
        }
        override fun close() = Unit // Late callbacks deliberately remain callable.
    }

    private class Output : SpeechOutput {
        override val status = MutableStateFlow("시험 출력")
        override val readyState = MutableStateFlow(true)
        var speaks = 0
        private var completion: (() -> Unit)? = null
        override fun refresh() = Unit
        override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit, failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) {
            speaks++
            completion = complete
        }
        fun complete() { val call = checkNotNull(completion); completion = null; call() }
        override fun stop() { completion = null }
        override fun shutdown() = stop()
    }

    private class Focus : CaptureFocus {
        var held = false
        var onLost: () -> Unit = {}
        override fun acquire(): Boolean { held = true; return true }
        override fun release() { held = false }
        fun lose() { held = false; onLost() }
    }

    private class Signals(private val focus: Focus) : CaptureSignalOutput {
        val events = mutableListOf<String>()
        override fun ready() { check(focus.held); events += "ready" }
        override fun processing() { check(focus.held); events += "processing" }
        override fun stop() { events += "stop" }
        override fun release() { events += "release" }
    }

    private class Harness {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ScopedApplication(instrumentation.targetContext)
        val input = Input()
        val output = Output()
        val focus = Focus()
        val signals = Signals(focus)
        private var store = ViewModelStore()
        lateinit var model: DeveloperViewModel
        fun create() {
            store = ViewModelStore()
            model = main {
                ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        DeveloperViewModel(application, null, outputFactory = { output },
                            inputFactory = { input }, captureSignalOutput = signals,
                            inputFocusFactory = { lost -> focus.also { it.onLost = lost } }) as T
                })[DeveloperViewModel::class.java]
            }
        }
        fun recreate() { main { store.clear() }; create() }
        suspend fun await(predicate: (DeveloperUiState) -> Boolean): DeveloperUiState =
            withTimeout(12_000) {
                var settled: DeveloperUiState
                do {
                    model.ui.first(predicate)
                    // accept publishes state before speech/capture in the same main callback.
                    // Let that callback finish, then recheck before inspecting the test ports.
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
}
