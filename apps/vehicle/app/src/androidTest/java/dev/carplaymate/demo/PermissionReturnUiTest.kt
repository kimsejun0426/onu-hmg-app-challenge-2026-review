package dev.carplaymate.demo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real launcher callbacks/FamilyScreen/VM, with controlled lifecycle and silent scoped ports.
 * No OS permission dialog, microphone, car service, or speech service is opened by this harness. */
@RunWith(AndroidJUnit4::class)
class PermissionReturnUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun requireOptIn() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Enable permissionReturnUiTests=true or familyUiTests=true on a dedicated emulator.",
            args.getString("permissionReturnUiTests").equals("true", true) ||
                args.getString("familyUiTests").equals("true", true))
    }

    @Test fun voiceGrantWaitsForResumedAndUnrestrictedThenAppliesExactlyOnce() = runBlocking {
        withHarness { h ->
            val request = h.requestVoice()
            h.background()
            h.permissionResult(request, true)
            h.assertQuiet(0)
            h.foreground()
            h.assertQuiet(0)
            h.emit(VehicleAccessState.RESTRICTED)
            h.assertQuiet(0)
            h.emit(VehicleAccessState.UNRESTRICTED)
            h.await { it.voiceInput && it.speechReady && !it.busy }
            assertEquals(InteractionMode.VOICE, InteractionPreferences(h.app).read().mode)
            h.assertQuiet(1)
            h.background()
            h.foreground()
            h.emit(VehicleAccessState.UNRESTRICTED)
            h.assertQuiet(1)
        }
    }

    @Test fun deniedVoiceResultSurvivesTheSameBoundaryWithoutChangingTheSavedMode() = runBlocking {
        withHarness(InteractionMode.READING) { h ->
            val request = h.requestVoice()
            h.background()
            h.permissionResult(request, false)
            h.foreground()
            h.assertQuiet(0)
            h.emit(VehicleAccessState.UNRESTRICTED)
            h.await { !it.busy && it.recoveryCause == RecoveryCause.INPUT_PERMISSION && it.failure }
            assertTrue(h.model.ui.value.simulation)
            assertEquals(InteractionMode.READING, InteractionPreferences(h.app).read().mode)
            h.assertQuiet(1)
        }
    }

    @Test fun settingsReturnRefreshesOnlyAfterBothGatesAndNeverSelectsOrStarts() = runBlocking {
        withHarness { h ->
            val request = h.openSettings()
            h.background()
            main { h.app.permissionsGranted = true; h.registry.settingsResult(request) }
            compose.waitForIdle()
            h.assertQuiet(0)
            // A usable vehicle callback alone cannot consume a background result.
            h.foreground(resumed = false)
            h.emit(VehicleAccessState.UNRESTRICTED)
            h.assertQuiet(0)
            main { h.lifecycle.registry.currentState = Lifecycle.State.RESUMED }
            // The pending result is consumed by a Compose effect, so drive its frame before waiting on the VM.
            compose.waitForIdle()
            h.await("settings return after both gates") { it.speechReady && !it.busy }
            assertFalse(h.model.ui.value.interactionConfigured)
            assertFalse(h.model.ui.value.voiceInput)
            h.assertQuiet(1)
        }
    }

    @Test fun newerReadingChoiceDiscardsOldGrantAndItsSlotCanServeAFreshRequest() = runBlocking {
        withHarness { h ->
            val old = h.requestVoice()
            h.choose("읽으며 체험")
            h.await { it.simulation && !it.busy }
            h.permissionResult(old, true)
            h.assertQuiet(0)
            assertEquals(InteractionMode.READING, InteractionPreferences(h.app).read().mode)
            // Keep the same OS permission launcher; a fresh request must have a new owner generation.
            main { h.app.permissionsGranted = false }
            val fresh = h.requestVoice()
            assertEquals(2, h.registry.launches.count { it.input is Array<*> })
            h.background()
            h.permissionResult(fresh, true)
            h.foreground()
            h.emit(VehicleAccessState.UNRESTRICTED)
            h.await { it.voiceInput && it.speechReady && !it.busy }
            h.assertQuiet(1)
        }
    }

    @Test fun newerSettingsRequestSupersedesAnOlderVoiceResult() = runBlocking {
        withHarness { h ->
            val voice = h.requestVoice()
            val settings = h.openSettings()
            h.background()
            h.permissionResult(voice, true)
            main { h.registry.settingsResult(settings) }
            compose.waitForIdle()
            h.foreground()
            h.emit(VehicleAccessState.UNRESTRICTED)
            h.await { it.speechReady && !it.busy }
            assertFalse(h.model.ui.value.voiceInput || h.model.ui.value.interactionConfigured)
            h.assertQuiet(1)
        }
    }

    @Test fun queuedGrantCannotCrossDeletionAndAReplacementFamily() = runBlocking {
        withHarness { h ->
            val originalIds = h.model.ui.value.children.map { it.id }
            val request = h.requestVoice()
            h.background()
            h.permissionResult(request, true)
            // Retain STARTED: even a now-allowed vehicle cannot consume the pending result.
            h.foreground(resumed = false)
            h.emit(VehicleAccessState.UNRESTRICTED)
            main { h.model.forgetFamily() }
            h.await { !it.busy && !it.onboarded && !it.familyDeletionPending }
            main {
                assertTrue(h.model.saveFamily("새별", null))
                assertTrue(h.model.saveInterests(setOf("ocean"), emptySet()))
                h.lifecycle.registry.currentState = Lifecycle.State.RESUMED
            }
            h.await { !it.busy && it.onboarded && !it.interestSetupNeeded }
            assertNotEquals(originalIds, h.model.ui.value.children.map { it.id })
            assertFalse(h.model.ui.value.voiceInput || h.model.ui.value.interactionConfigured)
            h.assertQuiet(0)
        }
    }

    private suspend fun withHarness(mode: InteractionMode? = null, block: suspend (Harness) -> Unit) {
        val h = Harness()
        try { h.prepare(mode); block(h) }
        finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            h.close()
        }
    }

    private inner class Harness {
        val app = ScopedApplication(instrumentation.targetContext)
        private val backend = LocalGameClient(app)
        val registry = RecordingRegistry()
        val lifecycle = TestLifecycle()
        private val vehicle = FakeVehicle()
        private val output = SilentOutput()
        private val input = SilentInput { app.permissionsGranted }
        private val capture = SilentCapture()
        private val store = ViewModelStore()
        lateinit var model: DeveloperViewModel

        suspend fun prepare(mode: InteractionMode?) {
            val family = FamilyPreferences(app)
            assertTrue(family.save(FamilyProfile("시험별", null)))
            assertTrue(family.saveInterests(setOf("space"), emptySet()))
            if (mode != null) assertTrue(InteractionPreferences(app).save(mode))
            model = main {
                // The production host owns the RECORD_AUDIO request even when the installed
                // Pleos SDK permissions do not require a runtime dialog.
                app.testHost = VoiceSessionHost(app) { scope ->
                    VoiceSessionController(app, null, outputFactory = { output },
                        vehicleAccessProvider = vehicle, inputFactory = { input },
                        captureSignalOutput = capture, inputFocusFactory = { capture },
                        requireForegroundHostForVoice = true, sessionScope = scope)
                }
                ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        DeveloperViewModel(app) as T
                })[DeveloperViewModel::class.java]
            }
            withTimeout(15_000) { model.ui.first { !it.busy && it.onboarded && !it.interestSetupNeeded } }
            main {
                lifecycle.registry.currentState = Lifecycle.State.RESUMED
                app.voiceSessionHost.activityResumed(compose.activity)
                model.foreground(compose.activity)
                vehicle.emit(VehicleAccessState.UNRESTRICTED)
                compose.activity.setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry,
                        LocalLifecycleOwner provides lifecycle) {
                        PlaymateTheme { FamilyScreen(model, onHide = {}) }
                    }
                }
            }
            compose.waitForIdle()
            if (mode != null) {
                compose.onNodeWithTag("family.settingsNav").performClick()
                await { !it.busy && it.quiet }
            }
        }

        suspend fun choose(label: String) {
            await { !it.busy }
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.waitForIdle()
        }

        suspend fun requestVoice(): Launch {
            val before = registry.launches.size
            choose("음성으로 함께")
            assertEquals("The real UI must launch a permission contract, not bypass it.",
                before + 1, registry.launches.size)
            return registry.launches.last().also {
                val permissions = it.input as? Array<*>
                assertTrue("The real host must request the denied microphone grant.",
                    permissions != null && Manifest.permission.RECORD_AUDIO in permissions)
            }
        }

        suspend fun openSettings(): Launch {
            await { !it.busy }
            compose.onNodeWithTag("interaction.appPermissions").performScrollTo().performClick()
            compose.waitForIdle()
            return registry.launches.last().also {
                assertTrue(it.input is Intent)
                assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    (it.input as Intent).action)
            }
        }

        suspend fun permissionResult(request: Launch, granted: Boolean) {
            main { app.permissionsGranted = granted; registry.permissionsResult(request, granted) }
            compose.waitForIdle()
        }

        suspend fun background() {
            main {
                lifecycle.registry.currentState = Lifecycle.State.STARTED
                app.voiceSessionHost.activityPaused(compose.activity)
                model.background()
            }
            await { !it.busy && it.quiet && it.vehicleAccess == VehicleAccessState.UNKNOWN }
        }

        suspend fun foreground(resumed: Boolean = true) {
            main {
                model.foreground(compose.activity)
                lifecycle.registry.currentState = if (resumed) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
                if (resumed) app.voiceSessionHost.activityResumed(compose.activity)
            }
            await { !it.busy && it.vehicleAccess == VehicleAccessState.UNKNOWN }
        }

        suspend fun emit(state: VehicleAccessState) {
            main { vehicle.emit(state) }
            await { it.vehicleAccess == state && !it.busy }
        }

        suspend fun await(stage: String = "UI state", predicate: (DeveloperUiState) -> Boolean) {
            try {
                withTimeout(15_000) { model.ui.first(predicate) }
            } catch (error: TimeoutCancellationException) {
                val state = model.ui.value
                throw AssertionError("$stage: lifecycle=${lifecycle.registry.currentState}, " +
                    "vehicle=${state.vehicleAccess}, busy=${state.busy}, speechReady=${state.speechReady}, " +
                    "refreshes=${output.refreshes}, speaks=${output.speaks}, " +
                    "inputOpens=${input.opens}, captureActivations=${capture.activations}").apply { initCause(error) }
            }
            main { /* Finish the publishing main action. */ }
            compose.waitForIdle()
        }

        fun assertQuiet(refreshes: Int) {
            compose.waitForIdle()
            assertEquals(refreshes, output.refreshes)
            assertEquals(0, output.speaks)
            assertEquals(0, input.opens)
            assertEquals(0, capture.activations)
            assertTrue(model.ui.value.quiet)
            assertFalse(model.ui.value.microphoneListening)
            assertNull(model.ui.value.snapshot)
        }

        suspend fun close() {
            main {
                store.clear()
                app.testHost?.close()
                app.testHost = null
                lifecycle.registry.currentState = Lifecycle.State.DESTROYED
            }
            backend.deleteAll()
            withContext(Dispatchers.IO) { app.clean() }
        }
    }

    private data class Launch(val requestCode: Int, val input: Any?)
    private class RecordingRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        val launches = mutableListOf<Launch>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
            input: I, options: ActivityOptionsCompat?) {
            launches += Launch(requestCode, input)
        }
        fun permissionsResult(request: Launch, granted: Boolean) {
            val names = (request.input as Array<*>).map { it as String }
            check(dispatchResult(request.requestCode, names.associateWith { granted }))
        }
        fun settingsResult(request: Launch) {
            check(dispatchResult(request.requestCode, ActivityResult(Activity.RESULT_CANCELED, null)))
        }
    }

    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class FakeVehicle : VehicleAccessProvider {
        override val initialState = VehicleAccessState.UNKNOWN
        private var callback: ((VehicleAccessState) -> Unit)? = null
        override fun start(context: Context, changed: (VehicleAccessState) -> Unit) { callback = changed }
        override fun close() { callback = null }
        fun emit(value: VehicleAccessState) { checkNotNull(callback)(value) }
    }
    private class SilentOutput : SpeechOutput {
        override val status = MutableStateFlow("시험: 준비 확인")
        override val readyState = MutableStateFlow(false)
        var refreshes = 0
        var speaks = 0
        override fun refresh() { refreshes++; readyState.value = true }
        override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
            failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) { speaks++; error("No playback") }
        override fun stop() = Unit
        override fun shutdown() = Unit
    }
    private class SilentInput(private val granted: () -> Boolean) : SpeechCaptureInput {
        override val available = true
        override val source = "mock"
        var opens = 0
        override fun unavailableReason(checkPermission: Boolean) =
            if (checkPermission && !granted()) "시험: 권한 확인 필요" else null
        override fun open(ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
            onFinal: (CaptureTicket, String) -> Unit, onFailure: (CaptureTicket, SpeechInputFailure) -> Unit) {
            opens++; error("No microphone")
        }
        override fun close() = Unit
    }
    private class SilentCapture : CaptureFocus, CaptureSignalOutput {
        var activations = 0
        override fun acquire(): Boolean { activations++; return false }
        override fun ready() { activations++ }
        override fun processing() { activations++ }
        override fun stop() = Unit
        override fun release() = Unit
    }
    private class ScopedApplication(context: Context) : OnuApplication() {
        var testHost: VoiceSessionHost? = null
        internal override val voiceSessionHost: VoiceSessionHost
            get() = checkNotNull(testHost)
        private val prefix = "permission_return_" + UUID.randomUUID()
        private val base = context.noBackupFilesDir.canonicalFile
        private val directory = File(base, prefix).also { check(it.mkdir()) }
        private val preferences = mutableSetOf<String>()
        private val keyAlias = "onu.records.v1." + MessageDigest.getInstance("SHA-256")
            .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)
        var permissionsGranted = false
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun checkSelfPermission(permission: String): Int =
            if (permission in setOf(Manifest.permission.RECORD_AUDIO, PleosSpeechRuntime.STT_PERMISSION,
                PleosSpeechRuntime.TTS_PERMISSION)) {
                if (permissionsGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            } else super.checkSelfPermission(permission)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "${prefix}_$name"
            synchronized(preferences) { preferences += scoped }
            return super.getSharedPreferences(scoped, mode)
        }
        fun clean() {
            check(directory.canonicalFile.parentFile == base && directory.name == prefix)
            synchronized(preferences) { preferences.forEach { check(super.deleteSharedPreferences(it)) } }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(keyAlias) }
            check(directory.deleteRecursively())
        }
    }
    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable { action() })
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
