package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real local protocol/storage with an explicitly test-only supported host and controlled UXR.
 * No Android/Pleos microphone, audio focus or physical output is opened. This does not establish
 * that a platform permits restricted voice or that a real foreground service is running.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSessionHostPolicyInstrumentedTest {
    @Test
    fun supportedOwnedHostContinuesVoiceAfterRestrictionAndBackground() = runBlocking {
        scoped { h ->
            val first = h.startVoice()
            val session = requireNotNull(first.snapshot).sessionId
            val playback = h.main { h.output.attempts.single() }
            h.restrictAndBackground()
            h.main {
                val state = h.controller.ui.value
                assertFalse(state.fullUiAllowed)
                assertTrue(state.voiceSessionAllowed)
                assertTrue(state.minimalUiRequired)
                assertFalse(state.quiet)
                assertTrue(h.output.playing)
                val registrations = h.provider.observers.size
                // Reattaching the same display must not reset a live observer to UNKNOWN.
                h.controller.foreground(h.application)
                assertEquals(registrations, h.provider.observers.size)
                assertEquals(state.snapshot, h.controller.ui.value.snapshot)
                assertEquals(state.vehicleUx, h.controller.ui.value.vehicleUx)
                assertFalse(h.controller.ui.value.quiet)
                h.controller.background()
                playback.started()
                playback.complete()
            }
            val listening = h.listening()
            val label = requireNotNull(listening.snapshot).presentation
                .getJSONArray("actions").getJSONObject(0).getString("label")
            val capture = h.main { h.input.attempts.single() }
            h.main { capture.deliverFinal("별, $label") }
            val feedback = h.await {
                // A non-final choice prepares the next scene; FEEDBACK is the ending phase.
                !it.busy && !it.quiet && it.snapshot?.phase == "PROMPTING" &&
                    it.snapshot?.game?.getJSONArray("history")?.length() == 1
            }
            val committed = h.backend.snapshot(session)
            assertEquals(1, committed.game.getJSONArray("history").length())
            assertNotEquals(listening.snapshot.game.getString("scene_id"), committed.game.getString("scene_id"))
            assertNotNull(feedback.snapshot?.speechPlan)
            assertEquals(feedback.snapshot?.version, committed.version)
            h.main {
                assertEquals(2, h.output.attempts.size)
                assertEquals(1, h.client.commands.count { it.getString("type") == "action.submit" })
                assertFalse(h.input.active)
                // A duplicated service final must not commit or queue another answer.
                capture.deliverFinal("별, $label")
                assertEquals(1, h.client.commands.count { it.getString("type") == "action.submit" })
                assertEquals(2, h.output.attempts.size)
            }
        }
    }

    @Test
    fun rawFlagsAndLimitsUpdatePolicyWithoutChangingRequiresOptimization() = runBlocking {
        scoped { h ->
            h.startVoice()
            val before = h.main { requireNotNull(h.controller.ui.value.snapshot) }
            val changed = VehicleUxSnapshot(VehicleAccessState.UNRESTRICTED,
                VehicleUxRestrictions(false, 9, 12, 4, 1))
            h.main {
                h.controller.setForegroundHost(true, restrictedVoiceSupported = true)
                assertTrue(h.controller.ui.value.fullUiAllowed)
                h.provider.emit(changed)
                val state = h.controller.ui.value
                assertEquals(VehicleAccessState.UNRESTRICTED, state.vehicleAccess)
                assertEquals(changed, state.vehicleUx)
                assertFalse(state.fullUiAllowed)
                assertTrue(state.minimalUiRequired)
                assertTrue(state.voiceSessionAllowed)
                assertFalse(state.quiet)
                assertEquals(before.version, state.snapshot?.version)
                assertEquals(1, h.output.attempts.size)
                h.provider.emit(unrestricted())
                assertTrue(h.controller.ui.value.fullUiAllowed)
                assertFalse(h.controller.ui.value.minimalUiRequired)
                assertFalse(h.controller.ui.value.quiet)
                assertEquals(1, h.output.attempts.size)
            }
            assertEquals(before.version, h.backend.snapshot(before.sessionId).version)
        }
    }

    @Test
    fun unknownAndHostLossEachStopImmediatelyAndRejectLateCallbacks() = runBlocking {
        for (loseHost in listOf(false, true)) scoped { h ->
            h.startVoice()
            h.restrictAndBackground()
            val playback = h.main { h.output.attempts.single() }
            h.main { playback.complete() }
            val listening = h.listening()
            val capture = h.main { h.input.attempts.single() }
            val label = requireNotNull(listening.snapshot).presentation
                .getJSONArray("actions").getJSONObject(0).getString("label")
            h.main {
                if (loseHost) h.controller.setForegroundHost(false)
                else h.provider.emit(VehicleUxSnapshot(VehicleAccessState.UNKNOWN))
                h.assertQuietPorts()
                capture.deliverFinal("별, $label")
                playback.started()
                playback.complete()
                h.assertQuietPorts()
            }
            val paused = requireNotNull(h.paused().snapshot)
            val work = h.main { h.client.calls.size }
            h.main {
                h.controller.setForegroundHost(true, restrictedVoiceSupported = true)
                h.provider.emit(unrestricted())
                capture.deliverFinal("별, $label")
                playback.started()
                playback.complete()
                h.assertQuietPorts()
                assertEquals(work, h.client.calls.size)
                assertEquals(1, h.output.attempts.size)
                assertEquals(1, h.input.attempts.size)
            }
            val stored = h.backend.snapshot(paused.sessionId)
            assertEquals("PAUSED", stored.status)
            assertEquals(paused.version, stored.version)
            assertEquals(0, stored.game.getJSONArray("history").length())
        }
    }

    @Test
    fun restrictedVoiceDoesNotPermitManualChoiceSubmitOrConfiguration() = runBlocking {
        scoped { h ->
            h.startVoice()
            h.restrictAndBackground()
            h.main { h.output.attempts.single().complete() }
            val listening = h.listening()
            val before = requireNotNull(listening.snapshot)
            val label = before.presentation.getJSONArray("actions").getJSONObject(0).getString("label")
            h.main {
                val state = h.controller.ui.value
                val work = h.client.calls.size
                h.controller.chooseAction(label)
                assertEquals(state, h.controller.ui.value)
                h.controller.submit("별, $label")
                assertEquals(state, h.controller.ui.value)
                // ApiClient construction is local only. A wrongly admitted call clears the session,
                // which fails immediately; this test never starts an HTTP request.
                h.controller.configure("https://example.invalid", "synthetic-test-only")
                assertEquals(state, h.controller.ui.value)
                h.controller.useSimulation(true)
                assertEquals(state, h.controller.ui.value)
                assertEquals(work, h.client.calls.size)
                assertTrue(h.input.active)
                assertTrue(h.focus.held)
            }
            assertEquals(before.version, h.backend.snapshot(before.sessionId).version)
        }
    }

    @Test
    fun aHostWithoutValidatedRestrictedSupportStillPauses() = runBlocking {
        scoped { h ->
            h.startVoice()
            h.main {
                h.controller.setForegroundHost(true) // The production-safe default is false.
                h.provider.emit(restricted())
                h.controller.background()
                h.assertQuietPorts()
                assertFalse(h.controller.ui.value.voiceSessionAllowed)
            }
            val paused = requireNotNull(h.paused().snapshot)
            assertEquals("PAUSED", h.backend.snapshot(paused.sessionId).status)
            assertEquals(1, h.main { h.output.attempts.size })
            assertTrue(h.main { h.input.attempts.isEmpty() })
        }
    }

    @Test
    fun closeRejectsQueuedCallbacksAndNewWorkWithoutCancellingTheHostsScope() = runBlocking {
        scoped { h ->
            h.startVoice()
            h.restrictAndBackground()
            val playback = h.main { h.output.attempts.single() }
            h.main { playback.complete() }
            val before = requireNotNull(h.listening().snapshot)
            val capture = h.main { h.input.attempts.single() }
            val callback = h.main { h.provider.observers.last() }
            h.main {
                h.controller.close()
                h.assertQuietPorts()
                assertTrue(h.hostJob.isActive)
                val stopped = h.controller.ui.value
                val work = h.client.calls.size
                val registrations = h.provider.observers.size
                callback(unrestricted())
                playback.started()
                playback.complete()
                capture.stage(CaptureStage.LISTENING)
                capture.deliverFinal("별, 숲길")
                h.output.readyState.value = false
                h.output.status.value = "closed-provider-update"
                h.controller.setForegroundHost(true, restrictedVoiceSupported = true)
                h.controller.foreground(h.application)
                h.controller.start("다른별", null)
                h.controller.resume()
                h.controller.close()
                assertEquals(stopped, h.controller.ui.value)
                assertEquals(work, h.client.calls.size)
                assertEquals(registrations, h.provider.observers.size)
                assertEquals(1, h.output.shutdowns)
                assertEquals(1, h.output.attempts.size)
                assertEquals(1, h.input.attempts.size)
                assertTrue(h.hostJob.isActive)
            }
            // Drain any already queued StateFlow collector continuation after close.
            h.main { h.assertQuietPorts() }
            assertEquals(before.version, h.backend.snapshot(before.sessionId).version)
        }
    }

    private suspend fun scoped(block: suspend (Harness) -> Unit) {
        val h = Harness()
        try { block(h) } finally { h.close() }
    }

    private class Harness {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ScopedApplication(instrumentation.targetContext)
        val backend = LocalGameClient(application)
        val client = CountingClient(backend)
        val provider = Vehicle()
        val output = Output()
        val input = Input()
        val focus = Focus()
        val hostJob = SupervisorJob()
        private val scope = CoroutineScope(Dispatchers.Main.immediate + hostJob)
        lateinit var controller: VoiceSessionController

        suspend fun startVoice(): DeveloperUiState {
            check(InteractionPreferences(application).save(InteractionMode.VOICE))
            controller = main {
                VoiceSessionController(
                    application, client, outputFactory = { output },
                    vehicleAccessProvider = provider, inputFactory = { input },
                    captureSignalOutput = Signals, inputFocusFactory = { focus },
                    requireForegroundHostForVoice = true,
                    sessionScope = scope,
                ).also { it.foreground(application) }
            }
            await { !it.busy && it.speechReady }
            main {
                provider.emit(unrestricted())
                assertTrue(controller.ui.value.fullUiAllowed)
                controller.setForegroundHost(true)
                controller.start("별", null)
            }
            return await { !it.busy && !it.quiet && it.snapshot?.phase == "PROMPTING" }
                .also { assertEquals(1, main { output.attempts.size }) }
        }

        fun restrictAndBackground() = main {
            controller.setForegroundHost(true, restrictedVoiceSupported = true)
            provider.emit(restricted())
            controller.background()
        }

        suspend fun listening() = await {
            !it.busy && !it.quiet && it.snapshot?.phase == "LISTENING" && it.microphoneListening
        }

        suspend fun paused() = await { !it.busy && it.quiet && it.snapshot?.status == "PAUSED" }

        suspend fun await(predicate: (DeveloperUiState) -> Boolean): DeveloperUiState = withTimeout(12_000) {
            var settled: DeveloperUiState
            do {
                controller.ui.first(predicate)
                settled = main { controller.ui.value }
            } while (!predicate(settled))
            settled
        }

        fun assertQuietPorts() {
            assertTrue(controller.ui.value.quiet)
            assertFalse(controller.ui.value.microphoneListening)
            assertNull(controller.ui.value.outputStage)
            assertFalse(input.active)
            assertFalse(focus.held)
            assertFalse(output.playing)
        }

        fun <T> main(action: () -> T): T {
            val task = FutureTask(Callable { action() })
            check(Handler(Looper.getMainLooper()).post(task))
            return try { task.get(12, TimeUnit.SECONDS) }
            finally { if (!task.isDone) task.cancel(false) }
        }

        suspend fun close() {
            main {
                if (::controller.isInitialized) controller.close()
                hostJob.cancel()
            }
            withTimeout(12_000) { hostJob.join() }
            backend.deleteAll()
            application.clean()
        }
    }

    private class CountingClient(private val delegate: LocalGameClient) : GameClient {
        val calls = mutableListOf<String>()
        val commands = mutableListOf<JSONObject>()
        override suspend fun create(players: JSONObject): Snapshot {
            calls += "create"
            return delegate.create(players)
        }
        override suspend fun createAdventure(players: JSONObject, packId: String): Snapshot {
            calls += "createAdventure"
            return delegate.createAdventure(players, packId)
        }
        override suspend fun snapshot(id: String): Snapshot {
            calls += "snapshot"
            return delegate.snapshot(id)
        }
        override suspend fun command(id: String, body: JSONObject): JSONObject {
            calls += body.getString("type")
            commands += JSONObject(body.toString())
            return delegate.command(id, body)
        }
        override suspend fun receipt(session: String, command: String): JSONObject {
            calls += "receipt"
            return delegate.receipt(session, command)
        }
    }

    private class Vehicle : VehicleAccessProvider {
        override val initialState = VehicleAccessState.UNKNOWN
        val observers = mutableListOf<(VehicleUxSnapshot) -> Unit>()
        override fun start(context: Context, changed: (VehicleAccessState) -> Unit) =
            startObserving(context) { changed(it.access) }
        override fun startObserving(context: Context, changed: (VehicleUxSnapshot) -> Unit) {
            observers += changed
        }
        override fun close() = Unit // Retain queued callbacks to exercise the controller fence.
        fun emit(value: VehicleUxSnapshot) = observers.last()(value)
    }

    private class Output : SpeechOutput {
        override val status = MutableStateFlow("controlled-output-ready")
        override val readyState = MutableStateFlow(true)
        val attempts = mutableListOf<Attempt>()
        var playing = false
        var shutdowns = 0
        private var current: Attempt? = null
        data class Attempt(val started: () -> Unit, val complete: () -> Unit)
        override fun refresh() = Unit
        override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
                           failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) {
            lateinit var attempt: Attempt
            attempt = Attempt(started, {
                if (current === attempt) { current = null; playing = false }
                complete()
            })
            current = attempt
            attempts += attempt
            playing = true
        }
        override fun stop() { current = null; playing = false }
        override fun shutdown() { shutdowns++; stop() }
    }

    private class Input : SpeechCaptureInput {
        override val available = true
        override val source = "android_on_device"
        var active = false
        val attempts = mutableListOf<Attempt>()
        data class Attempt(val stage: (CaptureStage) -> Unit, val deliverFinal: (String) -> Unit)
        override fun unavailableReason(checkPermission: Boolean): String? = null
        override fun open(ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
                          onFinal: (CaptureTicket, String) -> Unit,
                          onFailure: (CaptureTicket, SpeechInputFailure) -> Unit) {
            val attempt = Attempt({ onStage(ticket, it) }, { onFinal(ticket, it) })
            attempts += attempt
            active = true
            attempt.stage(CaptureStage.LISTENING)
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

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "voice_host_policy_${UUID.randomUUID()}"
        private val directory = File(context.noBackupFilesDir, prefix).also { check(it.mkdirs()) }
        private val preferences = mutableSetOf<String>()
        private val keyAlias = "onu.records.v1." + MessageDigest.getInstance("SHA-256")
            .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "${prefix}_$name"
            synchronized(preferences) { preferences += scoped }
            return super.getSharedPreferences(scoped, mode)
        }
        fun clean() {
            synchronized(preferences) { preferences.forEach { check(super.deleteSharedPreferences(it)) } }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(keyAlias) }
            check(directory.deleteRecursively())
        }
    }

    private companion object {
        fun unrestricted() = VehicleUxSnapshot(VehicleAccessState.UNRESTRICTED,
            VehicleUxRestrictions(false, 0, 120, 20, 4))
        fun restricted(flags: Int = 1, stringLength: Int = 40,
                       cumulativeItems: Int = 12, depth: Int = 3) =
            VehicleUxSnapshot(VehicleAccessState.RESTRICTED,
                VehicleUxRestrictions(true, flags, stringLength, cumulativeItems, depth))
    }
}
