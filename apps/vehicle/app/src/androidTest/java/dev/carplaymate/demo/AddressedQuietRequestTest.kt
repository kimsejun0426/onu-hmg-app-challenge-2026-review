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
import kotlinx.coroutines.CompletableDeferred
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

/** Synthetic final results only: no real microphone, audio focus or sound output. */
@RunWith(AndroidJUnit4::class)
class AddressedQuietRequestTest {
    @Test fun addressedStopBecomesQuietBeforeSyncAndOldCallbacksCannotReopenInput() = runBlocking {
        for ((text, second) in listOf("오누야 쉬자" to null,
                "서윤, 오누야 하기 싫어" to "서윤", "오누야 서윤, 멈춰" to "서윤")) {
            val h = Harness()
            try {
                h.startVoice(second)
                val playback = h.main { h.output.attempts.single() }
                h.main { playback.started(); playback.complete() }
                val listening = h.await { !it.busy && it.microphoneListening }
                val session = requireNotNull(listening.snapshot).sessionId
                val capture = h.main { h.input.attempts.single() }
                h.main {
                    h.client.holdSync = true
                    capture(text)
                    // Check before the suspended protocol can acknowledge any stop.
                    h.assertQuietPorts()
                    assertEquals(text, 0, h.client.actionSubmits)
                    capture("민준, 물가")
                    playback.started()
                    playback.complete()
                    h.assertQuietPorts()
                    assertEquals(1, h.input.attempts.size)
                    assertEquals(1, h.output.attempts.size)
                }
                withTimeout(12_000) { h.client.syncEntered.await() }
                assertEquals("ACTIVE", h.backend.snapshot(session).status)
                assertEquals(0, h.backend.snapshot(session).game.getJSONArray("history").length())
                h.main { h.client.releaseSync.complete(Unit) }
                h.await { !it.busy && it.quiet && it.snapshot?.status == "PAUSED" }
                h.main {
                    h.assertQuietPorts()
                    capture("오누야 계속")
                    assertEquals(1, h.input.attempts.size)
                    assertEquals(1, h.output.attempts.size)
                    assertEquals(0, h.client.actionSubmits)
                }
                assertEquals(0, h.backend.snapshot(session).game.getJSONArray("history").length())
            } finally { h.close() }
        }
    }

    private class Harness {
        val application = ScopedApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val backend = LocalGameClient(application)
        val client = HeldClient(backend)
        val output = Output()
        val input = Input()
        val focus = Focus()
        val hostJob = SupervisorJob()
        lateinit var controller: VoiceSessionController

        suspend fun startVoice(second: String?) {
            check(InteractionPreferences(application).save(InteractionMode.VOICE))
            controller = main {
                VoiceSessionController(application, client, outputFactory = { output },
                    vehicleAccessProvider = Vehicle, inputFactory = { input },
                    captureSignalOutput = Signals, inputFocusFactory = { focus },
                    sessionScope = CoroutineScope(Dispatchers.Main.immediate + hostJob))
                    .also { it.foreground(application) }
            }
            await { !it.busy && it.speechReady }
            main { controller.start("민준", second) }
            await { !it.busy && !it.quiet && it.snapshot?.phase == "PROMPTING" }
        }

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

    private class HeldClient(private val delegate: LocalGameClient) : GameClient by delegate {
        var holdSync = false
        var actionSubmits = 0
        val syncEntered = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        override suspend fun snapshot(id: String): Snapshot {
            if (holdSync) {
                syncEntered.complete(Unit)
                releaseSync.await()
            }
            return delegate.snapshot(id)
        }
        override suspend fun command(id: String, body: JSONObject): JSONObject {
            if (body.getString("type") == "action.submit") {
                actionSubmits++
                // A regression cannot race a fast local response into a passing assertion.
                releaseSync.await()
            }
            return delegate.command(id, body)
        }
    }

    private object Vehicle : VehicleAccessProvider {
        override val initialState = VehicleAccessState.NON_AUTOMOTIVE_DEMO
        override fun start(context: Context, changed: (VehicleAccessState) -> Unit) = changed(initialState)
        override fun close() = Unit
    }

    private class Output : SpeechOutput {
        override val status = MutableStateFlow("controlled-output-ready")
        override val readyState = MutableStateFlow(true)
        val attempts = mutableListOf<Attempt>()
        var playing = false
        data class Attempt(val started: () -> Unit, val complete: () -> Unit)
        override fun refresh() = Unit
        override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
                           failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) {
            attempts += Attempt(started, { playing = false; complete() })
            playing = true
        }
        override fun stop() { playing = false }
        override fun shutdown() = stop()
    }

    private class Input : SpeechCaptureInput {
        override val available = true
        override val source = "android_on_device"
        var active = false
        val attempts = mutableListOf<(String) -> Unit>()
        override fun unavailableReason(checkPermission: Boolean): String? = null
        override fun open(ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
                          onFinal: (CaptureTicket, String) -> Unit,
                          onFailure: (CaptureTicket, SpeechInputFailure) -> Unit) {
            attempts += { onFinal(ticket, it) }
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

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "addressed_quiet_${UUID.randomUUID()}"
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
}
