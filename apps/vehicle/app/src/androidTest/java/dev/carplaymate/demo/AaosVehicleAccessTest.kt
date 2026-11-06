package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real AAOS UXR handshake. The host changes the emulator driving state after each marker.
 * Audio remains fake and family/game stores are isolated. This verifies the host image's actual
 * AAOS restriction provider, not Pleos speech, physical audio, or a real vehicle.
 * The scoped model intentionally relies on its actual UXR listener, not the host Activity's
 * onPause.
 */
@RunWith(AndroidJUnit4::class)
class AaosVehicleAccessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val capture = SilentCapture()

    @Test
    fun actualVehicleServiceRestrictsOutputAndClearingItNeverAutomaticallyResumes() = runBlocking {
        assumeTrue(
            "Enable aaosUxTests=true only on the dedicated AAOS emulator with host coordination.",
            InstrumentationRegistry.getArguments().getString("aaosUxTests") == "true",
        )
        assertTrue(
            instrumentation.targetContext.packageManager.hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE
            )
        )
        val application = ScopedApplication(instrumentation.targetContext)
        val backend = LocalGameClient(application)
        val store = ViewModelStore()
        val output = SilentOutput()
        val runId = UUID.randomUUID().toString()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val model = main {
                ViewModelProvider(
                    store,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            DeveloperViewModel(application, null, outputFactory = { output },
                                inputFactory = { capture }, captureSignalOutput = capture,
                                inputFocusFactory = { capture }) as T
                    },
                )[DeveloperViewModel::class.java]
            }
            assertEquals(VehicleAccessState.UNKNOWN, model.ui.value.vehicleAccess)
            marker(runId, "connecting", model, output)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { model.foreground(it) }
            await(model) { !it.busy && it.vehicleAccess == VehicleAccessState.UNRESTRICTED }
            assertTrue(model.ui.value.quiet)
            assertEquals(0, output.playbacks.size)
            main {
                assertTrue(model.saveFamily("시험별", null))
                assertTrue(model.skipInterests())
                model.startFamilyAdventure()
            }
            await(model) { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            assertEquals(1, output.playbacks.size)
            val oldPlayback = output.playbacks.single()
            marker(runId, "ready-for-restriction", model, output)

            // The host must cause a real platform event; a missed event times out and fails.
            await(model) {
                !it.busy && it.vehicleAccess == VehicleAccessState.RESTRICTED && it.quiet &&
                    it.snapshot?.status == "PAUSED"
            }
            assertFalse(model.ui.value.microphoneListening)
            assertEquals("PAUSED", model.ui.value.snapshot?.status)
            // localStop publishes quiet before the asynchronous pause transaction finishes.
            // Require its committed snapshot as well, rather than accepting that interim UI.
            val pausedSnapshot = checkNotNull(main { model.ui.value.snapshot })
            val durablePaused = withTimeout(10_000) { backend.snapshot(pausedSnapshot.sessionId) }
            assertEquals("PAUSED", durablePaused.status)
            assertEquals(pausedSnapshot.version, durablePaused.version)
            main {
                model.resume()
                model.startFamilyAdventure()
                oldPlayback.complete()
                oldPlayback.failure(SpeechOutputFailure("Synthetic stale completion"))
            }
            assertTrue(model.ui.value.quiet)
            assertEquals(1, output.playbacks.size)
            assertEquals(pausedSnapshot.version, model.ui.value.snapshot?.version)
            marker(runId, "restricted-and-quiet", model, output)

            await(model) { it.vehicleAccess == VehicleAccessState.UNRESTRICTED }
            main { oldPlayback.complete() }
            assertTrue(model.ui.value.quiet)
            assertFalse(model.ui.value.microphoneListening)
            assertNull(model.ui.value.snapshot?.speechPlan)
            assertEquals(1, output.playbacks.size)
            assertEquals("PAUSED", model.ui.value.snapshot?.status)
            assertEquals(pausedSnapshot.version, model.ui.value.snapshot?.version)
            marker(runId, "unrestricted-still-quiet", model, output)

            // Capability becoming available is insufficient; only this explicit call may replay.
            main { model.resume() }
            await(model) { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            assertEquals(2, output.playbacks.size)
            marker(runId, "complete", model, output)
        } finally {
            // Leave a timed-out cleanup queued: cancelling it could retain the real UXR listener.
            // Never delete storage while its ViewModel may still be using it.
            try { main(cancelIfQueued = false) { store.clear() } }
            finally { scenario?.close() }
            withTimeout(10_000) { backend.deleteAll() }
            application.clean()
        }
    }

    private suspend fun await(model: DeveloperViewModel, condition: (DeveloperUiState) -> Boolean) {
        withTimeout(90_000) { model.ui.first(condition) }
        main { /* Complete the publishing operation before checking the silent-output probe. */ }
    }

    private fun marker(
        runId: String,
        stage: String,
        model: DeveloperViewModel,
        output: SilentOutput,
    ) {
        assertEquals("This UXR-only experiment must never request microphone capture.", 0, capture.opens.get())
        assertEquals("This UXR-only experiment must never request input audio focus.", 0, capture.focusRequests.get())
        assertEquals("This UXR-only experiment must never play capture signals.", 0, capture.signals.get())
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("aaos-ux"))
        check(directory.isDirectory || directory.mkdirs())
        val snapshot = model.ui.value.snapshot
        val value =
            JSONObject()
                .put("schema_version", 2)
                .put("run_id", runId)
                .put("stage", stage)
                .put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
                .put("vehicle_access", model.ui.value.vehicleAccess.name)
                .put("quiet", model.ui.value.quiet)
                .put("session_status", snapshot?.status ?: JSONObject.NULL)
                .put("snapshot_version", snapshot?.version ?: JSONObject.NULL)
                .put("fake_playback_count", output.playbacks.size)
                .put("actual_uxr_provider", true)
                .put("actual_audio", false)
                .put("actual_microphone", false)
                .put("capture_requests", capture.opens.get())
                .put("input_focus_requests", capture.focusRequests.get())
                .put("capture_signal_requests", capture.signals.get())
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 8_192)
        val status = File(directory, "status.json")
        for (file in listOf(status, File(directory, "$stage.json"))) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
            check(file.readBytes().contentEquals(bytes))
        }
        // AAOS may use headless user 0 and foreground user 10; report the actual app path.
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString("aaos_ux_stage", stage)
                putString("aaos_ux_status_path", status.absolutePath)
                putString("aaos_ux_run_id", runId)
                putInt("aaos_ux_json_bytes", bytes.size)
                putString("aaos_ux_json_sha256", sha(bytes))
                putString("aaos_ux_json_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            },
        )
    }

    private fun <T> main(cancelIfQueued: Boolean = true, action: () -> T): T {
        val task = FutureTask(Callable { action() })
        check(Handler(Looper.getMainLooper()).post(task))
        try { return task.get(35, TimeUnit.SECONDS) }
        catch (error: Throwable) {
            if (cancelIfQueued) task.cancel(false)
            throw error
        }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "aaos_ux_${UUID.randomUUID()}"
        private val parent = context.noBackupFilesDir.canonicalFile
        private val directory = File(parent, prefix).also { check(it.mkdirs()) }
        private val preferences = mutableSetOf<String>()

        init {
            attachBaseContext(context)
        }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = directory

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "${prefix}_$name"
            synchronized(preferences) { preferences += scoped }
            return super.getSharedPreferences(scoped, mode)
        }

        fun clean() {
            check(directory.canonicalFile.parentFile == parent && directory.name == prefix)
            synchronized(preferences) {
                preferences.forEach { check(super.deleteSharedPreferences(it)) }
            }
            val alias = "onu.records.v1." + sha(directory.canonicalPath.toByteArray(Charsets.UTF_8)).take(32)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(directory.deleteRecursively())
        }
    }

    /** No SDK, AudioRecord, audio focus, or tone generator can be reached through these ports. */
    private class SilentCapture : SpeechCaptureInput, CaptureSignalOutput, CaptureFocus {
        val opens = AtomicInteger()
        val focusRequests = AtomicInteger()
        val signals = AtomicInteger()
        override val available = true
        override val source = "mock"
        override fun unavailableReason(checkPermission: Boolean): String? = null
        override fun open(ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
            onFinal: (CaptureTicket, String) -> Unit, onFailure: (CaptureTicket, SpeechInputFailure) -> Unit) {
            opens.incrementAndGet()
            onFailure(ticket, SpeechInputFailure("Capture is disabled for this UXR-only experiment."))
        }
        override fun acquire(): Boolean { focusRequests.incrementAndGet(); return false }
        override fun ready() { signals.incrementAndGet() }
        override fun processing() { signals.incrementAndGet() }
        override fun close() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    private companion object {
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private class SilentOutput : SpeechOutput {
        data class Playback(val complete: () -> Unit, val failure: (SpeechOutputFailure) -> Unit)

        override val status = MutableStateFlow("무음 시험 출력")
        override val readyState = MutableStateFlow(true)
        val playbacks = mutableListOf<Playback>()

        override fun speak(
            plan: SpeechPlan,
            generation: Long,
            complete: () -> Unit,
            failure: (SpeechOutputFailure) -> Unit,
            started: () -> Unit,
        ) {
            playbacks += Playback(complete, failure)
        }

        override fun refresh() = Unit

        override fun stop() = Unit

        override fun shutdown() = Unit
    }
}
