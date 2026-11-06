package dev.carplaymate.demo

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
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
 * Real Android FGS, Notification PendingIntent and MediaSession dispatch; fixture speech ports only.
 * Run only with MediaPauseTestRunner on the dedicated demo emulator. No microphone, STT, TTS,
 * audio focus or capture tone is opened. Platform permission to run while restricted is not claimed.
 */
@RunWith(AndroidJUnit4::class)
class MediaPauseResumeServiceTest {
    @Test fun notificationPauseAndHiddenResumeKeepTheSameStoryAndFenceLateInput(): Unit = runBlocking {
        fixture { h, scenario ->
            h.start(scenario, twoPlayers = true)
            h.main { h.output.attempts.single().complete() }
            val listening = requireNotNull(h.awaitUi { it.microphoneListening }.snapshot)
            val oldCapture = h.main { h.input.attempts.single() }
            val owner = h.ownedService()
            assertNotNull(owner)
            val mediaToken = h.notification().getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            assertNotNull(mediaToken)
            h.send(h.action("잠깐 쉬기"))
            val paused = requireNotNull(h.paused().snapshot)
            h.main { h.assertQuietPorts(); assertTrue(h.controller.ui.value.voiceSessionAllowed) }
            assertEquals(listening.sessionId, paused.sessionId)
            assertEquals(listening.game.toString(), paused.game.toString())
            val resume = h.action("이어하기")
            assertNotEquals(mediaToken,
                h.notification().getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION))
            assertSame(owner, h.ownedService()) // Token rotates; the already promoted Service remains.
            scenario.moveToState(Lifecycle.State.STARTED)
            h.main {
                h.controller.resume() // The screen-only entry point is not permission to resume here.
                oldCapture.deliverFinal("별, 숲길")
                h.assertQuietPorts()
                assertEquals(1, h.output.attempts.size)
            }
            h.send(resume)
            val active = requireNotNull(h.awaitUi { !it.busy && !it.quiet && it.snapshot?.status == "ACTIVE" }.snapshot)
            assertEquals(paused.sessionId, active.sessionId)
            assertEquals(paused.version + 1, active.version)
            assertEquals(paused.turnEpoch + 1, active.turnEpoch)
            assertNotEquals(paused.turnId, active.turnId)
            assertEquals(paused.game.toString(), active.game.toString())
            assertSame(owner, h.ownedService())
            h.main {
                assertEquals(2, h.output.attempts.size)
                assertEquals(1, h.input.attempts.size)
                assertEquals(1, h.client.commands.count { it.getString("type") == "session.resume" })
                assertEquals(0, h.client.commands.count { it.getString("type") == "action.submit" })
                assertTrue(h.controller.ui.value.voiceSessionAllowed)
            }
        }
    }

    @Test fun consumedResumeIntentCannotSpendANewerPauseLease(): Unit = runBlocking {
        fixture { h, scenario ->
            h.start(scenario, label = "consumed/start first session")
            h.send(h.action("잠깐 쉬기", "consumed/first pause action"), "consumed/first pause send")
            h.paused("consumed/first pause settled")
            val oldResume = h.action("이어하기", "consumed/first resume action")
            h.send(oldResume, "consumed/first resume send")
            h.awaitUi("consumed/first resume active") { !it.busy && !it.quiet }
            h.send(h.action("잠깐 쉬기", "consumed/second pause action"), "consumed/second pause send")
            val currentPaused = requireNotNull(h.paused("consumed/second pause settled").snapshot)
            val freshResume = h.action("이어하기", "consumed/second resume action")
            assertNotEquals(oldResume, freshResume)
            h.send(oldResume, "consumed/obsolete resume send")
            h.main {
                h.assertQuietPorts()
                assertEquals(currentPaused.version, h.controller.ui.value.snapshot?.version)
                assertEquals(2, h.output.attempts.size)
            }
            h.send(freshResume, "consumed/current resume send")
            val active = h.awaitUi("consumed/current resume active") { !it.busy && !it.quiet }
            assertEquals(currentPaused.sessionId, active.snapshot?.sessionId)
            h.main { assertEquals(3, h.output.attempts.size) }
        }
    }

    @Test fun hardStopRevokesOldResumeEvenAfterAFreshForegroundPromotion(): Unit = runBlocking {
        fixture { h, scenario ->
            h.start(scenario, label = "hard-stop/start first session")
            h.send(h.action("잠깐 쉬기", "hard-stop/first pause action"), "hard-stop/first pause send")
            h.paused("hard-stop/first pause settled")
            val oldResume = h.action("이어하기", "hard-stop/first resume action")
            h.send(h.action("끝내기", "hard-stop/stop action"), "hard-stop/stop send")
            h.awaitReleased("hard-stop/owner released")
            h.paused("hard-stop/stop persistence settled")
            h.send(oldResume, "hard-stop/obsolete resume with no owner")
            h.awaitReleased("hard-stop/obsolete resume owner released")
            h.main { h.assertQuietPorts(); assertEquals(1, h.output.attempts.size) }
            h.paused("hard-stop/fresh start persistence settled")
            h.start(scenario, label = "hard-stop/fresh promotion active", requireSettledPause = true)
            h.send(h.action("잠깐 쉬기", "hard-stop/new-owner pause action"), "hard-stop/new-owner pause send")
            val freshPaused = requireNotNull(h.paused("hard-stop/new-owner pause settled").snapshot)
            val freshResume = h.action("이어하기", "hard-stop/new-owner resume action")
            h.send(oldResume, "hard-stop/obsolete resume against new owner")
            h.main {
                h.assertQuietPorts()
                assertEquals(freshPaused.version, h.controller.ui.value.snapshot?.version)
                assertEquals(2, h.output.attempts.size)
            }
            h.send(freshResume, "hard-stop/current resume send")
            h.awaitUi("hard-stop/current resume active") { !it.busy && !it.quiet }
            h.main { assertEquals(3, h.output.attempts.size) }
        }
    }

    @Test fun unprivilegedMediaControllerCannotResumeTheNotificationLease(): Unit = runBlocking {
        fixture { h, scenario ->
            h.start(scenario)
            h.send(h.action("잠깐 쉬기"))
            val paused = requireNotNull(h.paused().snapshot)
            val resume = h.action("이어하기")
            val token = requireNotNull(h.notification().getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION))
            val media = MediaController(h.application, token)
            assertEquals(PlaybackState.STATE_PAUSED, media.playbackState?.state)
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                h.application.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL))
            media.transportControls.play()
            h.drain()
            h.main {
                h.assertQuietPorts()
                assertEquals(paused.version, h.controller.ui.value.snapshot?.version)
                assertEquals(1, h.output.attempts.size)
            }
            // Rejecting an untrusted caller must leave the genuine notification lease usable.
            h.send(resume)
            h.awaitUi { !it.busy && !it.quiet }
            h.main { assertEquals(2, h.output.attempts.size) }
        }
    }

    @Test fun spokenQuietRequestStopsTheServiceWithoutAMediaResumeLease(): Unit = runBlocking {
        fixture { h, scenario ->
            h.start(scenario)
            h.main { h.output.attempts.single().complete() }
            h.awaitUi { it.microphoneListening }
            h.main { h.input.attempts.single().deliverFinal("오누야 쉬자") }
            h.paused()
            h.awaitReleased()
            h.main {
                h.assertQuietPorts()
                assertEquals(1, h.output.attempts.size)
                assertFalse(h.controller.ui.value.voiceSessionAllowed)
            }
        }
    }

    @Test fun automaticQuietAndOutputFailureNeverRetainAMediaResumeLease(): Unit = runBlocking {
        for (failure in listOf(false, true)) fixture { h, scenario ->
            h.start(scenario)
            h.main {
                if (failure) h.output.attempts.single().fail(
                    SpeechOutputFailure("fixture output failed", RecoveryCause.OUTPUT_FAILED))
                else h.controller.pause("NO_RESPONSE") // Existing automatic-quiet path, no clock spoofing.
            }
            h.paused()
            h.awaitReleased()
            h.main { h.assertQuietPorts(); assertEquals(1, h.output.attempts.size) }
        }
    }

    @Test fun unknownAndRestrictedPoliciesRevokeLeaseAndRecoveryDoesNotReviveIt(): Unit = runBlocking {
        for (policy in listOf(VehicleUxSnapshot(VehicleAccessState.UNKNOWN),
                VehicleUxSnapshot(VehicleAccessState.RESTRICTED,
                    VehicleUxRestrictions(true, 1, 40, 12, 3)))) fixture { h, scenario ->
            h.start(scenario)
            h.send(h.action("잠깐 쉬기"))
            h.paused()
            val oldResume = h.action("이어하기")
            h.main { h.vehicle.emit(policy) }
            h.awaitReleased()
            h.main {
                h.assertQuietPorts()
                assertFalse(h.controller.ui.value.voiceSessionAllowed)
                h.vehicle.emit(MediaPauseServiceHarness.unrestricted())
            }
            h.send(oldResume)
            h.awaitReleased()
            h.main { h.assertQuietPorts(); assertEquals(1, h.output.attempts.size) }
        }
    }

    @Test fun modeChangeAndFamilyDeletionEachRevokeThePausedLease(): Unit = runBlocking {
        for (delete in listOf(false, true)) fixture { h, scenario ->
            h.start(scenario)
            h.send(h.action("잠깐 쉬기"))
            h.paused()
            val oldResume = h.action("이어하기")
            h.main {
                if (delete) h.controller.forgetFamily()
                else h.controller.useVoiceInput(false)
            }
            h.awaitUi { !it.busy && !it.familyDeletionPending }
            h.awaitReleased()
            h.send(oldResume)
            h.awaitReleased()
            h.main { h.assertQuietPorts(); assertEquals(1, h.output.attempts.size) }
        }
    }

    @Test fun changedPausedBackendEpochCannotBeResumedByTheOldLease(): Unit = runBlocking {
        fixture { h, scenario ->
            h.start(scenario, twoPlayers = true)
            h.send(h.action("잠깐 쉬기"))
            val paused = requireNotNull(h.paused().snapshot)
            val oldResume = h.action("이어하기")
            val changed = Snapshot.from(h.backend.command(paused.sessionId,
                commandEnvelope(paused, UUID.randomUUID().toString(), "session.pause",
                    JSONObject().put("reason", "USER"))))
            assertEquals(paused.sessionId, changed.sessionId)
            assertEquals("PAUSED", changed.status)
            assertTrue(changed.version > paused.version)
            assertTrue(changed.turnEpoch > paused.turnEpoch)
            h.send(oldResume)
            h.awaitReleased()
            h.main {
                h.assertQuietPorts()
                assertEquals(1, h.output.attempts.size)
                assertEquals(0, h.client.commands.count { it.getString("type") == "session.resume" })
            }
            assertEquals(changed.version, h.backend.snapshot(changed.sessionId).version)
        }
    }

    @Test fun inputPermissionFailureAndTaskRemovalRevokeRetainedPause(): Unit = runBlocking {
        for (removeTask in listOf(false, true)) fixture { h, scenario ->
            h.start(scenario)
            h.send(h.action("잠깐 쉬기"))
            h.paused()
            val oldResume = h.action("이어하기")
            if (removeTask) {
                scenario.onActivity { it.finishAndRemoveTask() }
            } else {
                // Real runtime revocation can kill instrumentation. Exercise the classified port
                // failure without changing OS permissions or constructing a speech recognizer.
                h.main { h.input.problem = SpeechInputFailure("fixture permission denied",
                    cause = RecoveryCause.INPUT_PERMISSION) }
                h.send(oldResume)
            }
            h.awaitReleased()
            h.main { h.input.problem = null }
            h.send(oldResume)
            h.awaitReleased()
            h.main { h.assertQuietPorts(); assertEquals(1, h.output.attempts.size) }
        }
    }

    private suspend fun fixture(block: suspend (MediaPauseServiceHarness, ActivityScenario<MainActivity>) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("familyUiTests") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as? MediaPauseTestApplication
            ?: error("Use the dedicated MediaPauseTestRunner, never the default runner.")
        instrumentation.uiAutomation.grantRuntimePermission(application.packageName, Manifest.permission.RECORD_AUDIO)
        val h = MediaPauseServiceHarness(application)
        h.main { check(application.fixture == null); application.fixture = h }
        var scenario: ActivityScenario<MainActivity>? = null
        var originalFailure: Throwable? = null
        try {
            for (permission in h.main { h.host.requiredVoicePermissions().toList() +
                    h.controller.requiredOutputPermissions().toList() }.distinct()) {
                instrumentation.uiAutomation.grantRuntimePermission(application.packageName, permission)
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            h.awaitUi { !it.busy && it.speechReady && it.fullUiAllowed }
            block(h, scenario)
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            try {
                try {
                    h.main { h.host.pause("USER") }
                    h.awaitReleased("cleanup/service release")
                    h.awaitUi("cleanup/persistence settled") { !it.busy }
                    scenario?.close()
                    h.main { h.host.close() }
                } finally {
                    h.main { application.fixture = null }
                    h.clean()
                }
            } catch (cleanupFailure: Throwable) {
                val original = originalFailure
                if (original != null) original.addSuppressed(cleanupFailure)
                else throw cleanupFailure
            }
        }
    }
}

/** All ports and persistence are isolated; the host alone uses the real Application for FGS. */
internal class MediaPauseServiceHarness(val application: MediaPauseTestApplication) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val storage = ScopedApplication(application)
    val backend = LocalGameClient(storage)
    val client = CountingClient(backend)
    val vehicle = Vehicle()
    val output = Output()
    val input = Input()
    val focus = Focus()
    val host: VoiceSessionHost = main {
        check(InteractionPreferences(storage).save(InteractionMode.VOICE))
        VoiceSessionHost(application, controllerFactory = { scope ->
            VoiceSessionController(storage, client, outputFactory = { output },
                vehicleAccessProvider = vehicle, inputFactory = { input },
                captureSignalOutput = Signals, inputFocusFactory = { focus },
                requireForegroundHostForVoice = true, sessionScope = scope)
        })
    }
    val controller get() = host.controller
    private var startPreflight: String? = null

    suspend fun start(scenario: ActivityScenario<MainActivity>, twoPlayers: Boolean = false,
                      label: String = "initial session active", requireSettledPause: Boolean = false): DeveloperUiState {
        scenario.onActivity {
            if (requireSettledPause) {
                // The family start button is disabled while stop persistence is busy.
                val state = controller.ui.value
                val pauseActive = (VoiceSessionController::class.java.getDeclaredField("pauseJob")
                    .apply { isAccessible = true }.get(controller) as? Job)?.isActive == true
                startPreflight = "status=${state.snapshot?.status}; phase=${state.snapshot?.phase}; " +
                    "version=${state.snapshot?.version}; busy=${state.busy}; quiet=${state.quiet}; " +
                    "pauseJobActive=$pauseActive"
                assertFalse("$label preflight: $startPreflight", state.busy)
                assertFalse("$label preflight: $startPreflight", pauseActive)
            }
            host.runExplicitVoiceAction { controller.start("별", if (twoPlayers) "달" else null) }
        }
        return awaitUi(label) { !it.busy && !it.quiet && it.snapshot?.phase == "PROMPTING" }
    }

    suspend fun paused(label: String = "confirmed pause") =
        awaitUi(label) { !it.busy && it.quiet && it.snapshot?.status == "PAUSED" }

    suspend fun awaitUi(label: String = "UI predicate", predicate: (DeveloperUiState) -> Boolean): DeveloperUiState =
        waitWithDiagnostic(label, 12_000) {
        var value: DeveloperUiState
        do {
            controller.ui.first(predicate)
            value = main { controller.ui.value }
        } while (!predicate(value))
        value
    }

    suspend fun notification(label: String = "notification present"): android.os.Bundle =
        waitWithDiagnostic(label, 8_000) {
        while (true) {
            val note = main { activeNotification() }
            if (note != null) return@waitWithDiagnostic note.extras
            delay(25)
        }
        error("unreachable")
    }

    suspend fun action(title: String, label: String = "notification action $title"): PendingIntent =
        waitWithDiagnostic(label, 8_000) {
        while (true) {
            val action = main { activeNotification()?.actions?.singleOrNull { it.title.toString() == title } }
            if (action != null) {
                val intent = requireNotNull(action.actionIntent)
                assertEquals(application.packageName, intent.creatorPackage)
                assertTrue(intent.isService)
                if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(intent.isImmutable)
                return@waitWithDiagnostic intent
            }
            delay(25)
        }
        error("unreachable")
    }

    private fun activeNotification(): Notification? = application
        .getSystemService(NotificationManager::class.java).activeNotifications
        .singleOrNull { it.id == 2201 && it.packageName == application.packageName }?.notification

    fun ownedService(): VoiceSessionService? = main {
        VoiceSessionHost::class.java.getDeclaredField("service").apply { isAccessible = true }
            .get(host) as? VoiceSessionService
    }

    suspend fun awaitReleased(label: String = "service released") = waitWithDiagnostic(label, 8_000) {
        val owner = VoiceSessionHost::class.java.getDeclaredField("service").apply { isAccessible = true }
        val stopping = VoiceSessionHost::class.java.getDeclaredField("stoppingService").apply { isAccessible = true }
        while (!main { activeNotification() == null && owner.get(host) == null && stopping.get(host) == null }) delay(25)
        drain()
    }

    fun send(intent: PendingIntent, label: String = "PendingIntent dispatch") {
        // OnFinished acknowledges dispatch, not Service.onStartCommand completion. State/notification
        // waits remain the behavioral checks; waitForIdleSync cannot join later Binder deliveries.
        val delivered = CountDownLatch(1)
        intent.send(application, 0, null, { _, _, _, _, _ -> delivered.countDown() }, Handler(Looper.getMainLooper()))
        check(delivered.await(8, TimeUnit.SECONDS)) { "$label dispatch did not complete; ${diagnosticState()}" }
        drain()
    }

    private suspend fun <T> waitWithDiagnostic(label: String, timeoutMillis: Long,
                                               block: suspend CoroutineScope.() -> T): T = try {
        withTimeout(timeoutMillis, block)
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("Media service wait '$label' timed out; ${diagnosticState()}", timeout)
    }

    /** Fixed state labels and counts only: no names, utterances, session IDs, tokens or game JSON. */
    private fun diagnosticState(): String = try {
        main {
            val state = controller.ui.value
            val owner = VoiceSessionHost::class.java.getDeclaredField("service").apply { isAccessible = true }
                .get(host) as? VoiceSessionService
            val stopping = VoiceSessionHost::class.java.getDeclaredField("stoppingService").apply { isAccessible = true }
                .get(host) != null
            val knownTitles = setOf("잠깐 쉬기", "이어하기", "끝내기")
            val actions = activeNotification()?.actions?.map {
                it.title.toString().takeIf(knownTitles::contains) ?: "<other>"
            }.orEmpty()
            "status=${state.snapshot?.status}; phase=${state.snapshot?.phase}; version=${state.snapshot?.version}; " +
                "busy=${state.busy}; quiet=${state.quiet}; outputcount=${output.attempts.size}; " +
                "inputcount=${input.attempts.size}; owned=${owner?.let(host::ownsService) == true}; " +
                "stopping=$stopping; service=${owner != null}; notificationactiontitles=$actions; " +
                "startPreflight=${startPreflight ?: "not-requested"}"
        }
    } catch (_: Exception) { "state-unavailable" }

    fun drain() = instrumentation.waitForIdleSync()

    fun assertQuietPorts() {
        assertTrue(controller.ui.value.quiet)
        assertFalse(controller.ui.value.microphoneListening)
        assertNull(controller.ui.value.outputStage)
        assertFalse(input.active)
        assertFalse(focus.held)
        assertFalse(output.playing)
    }

    fun <T> main(action: () -> T): T {
        val work = FutureTask(Callable { action() })
        check(Handler(Looper.getMainLooper()).post(work))
        return try { work.get(12, TimeUnit.SECONDS) } finally { if (!work.isDone) work.cancel(false) }
    }

    suspend fun clean() {
        backend.deleteAll()
        storage.clean()
    }

    class CountingClient(private val delegate: LocalGameClient) : GameClient {
        val commands = mutableListOf<JSONObject>()
        override suspend fun create(players: JSONObject) = delegate.create(players)
        override suspend fun createAdventure(players: JSONObject, packId: String) = delegate.createAdventure(players, packId)
        override suspend fun snapshot(id: String) = delegate.snapshot(id)
        override suspend fun command(id: String, body: JSONObject): JSONObject {
            commands += JSONObject(body.toString())
            return delegate.command(id, body)
        }
        override suspend fun receipt(session: String, command: String) = delegate.receipt(session, command)
    }

    class Vehicle : VehicleAccessProvider {
        override val initialState = VehicleAccessState.UNRESTRICTED
        override val initialSnapshot = unrestricted()
        private var observer: ((VehicleUxSnapshot) -> Unit)? = null
        override fun start(context: Context, changed: (VehicleAccessState) -> Unit) =
            startObserving(context) { changed(it.access) }
        override fun startObserving(context: Context, changed: (VehicleUxSnapshot) -> Unit) {
            observer = changed
            changed(initialSnapshot)
        }
        override fun close() { observer = null }
        fun emit(value: VehicleUxSnapshot) { observer?.invoke(value) }
    }

    class Output : SpeechOutput {
        override val status = MutableStateFlow("fixture output ready")
        override val readyState = MutableStateFlow(true)
        val attempts = mutableListOf<Attempt>()
        var playing = false
        private var current: Attempt? = null
        data class Attempt(val started: () -> Unit, val complete: () -> Unit,
                           val fail: (SpeechOutputFailure) -> Unit)
        override fun refresh() = Unit
        override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
                           failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) {
            lateinit var attempt: Attempt
            attempt = Attempt(started, {
                if (current === attempt) { current = null; playing = false }
                complete()
            }, { error ->
                if (current === attempt) { current = null; playing = false }
                failure(error)
            })
            current = attempt
            attempts += attempt
            playing = true
            started()
        }
        override fun stop() { current = null; playing = false }
        override fun shutdown() = stop()
    }

    class Input : SpeechCaptureInput {
        override val available = true
        override val source = "android_on_device"
        var active = false
        var problem: SpeechInputFailure? = null
        val attempts = mutableListOf<Attempt>()
        data class Attempt(val deliverFinal: (String) -> Unit)
        override fun unavailableReason(checkPermission: Boolean): String? = problem?.message
        override fun unavailableFailure(checkPermission: Boolean): SpeechInputFailure? = problem
        override fun open(ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
                          onFinal: (CaptureTicket, String) -> Unit,
                          onFailure: (CaptureTicket, SpeechInputFailure) -> Unit) {
            active = true
            attempts += Attempt { onFinal(ticket, it) }
            onStage(ticket, CaptureStage.LISTENING)
        }
        override fun close() { active = false }
    }

    class Focus : CaptureFocus {
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
        private val prefix = "media_pause_service_${UUID.randomUUID()}"
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

    companion object {
        fun unrestricted() = VehicleUxSnapshot(VehicleAccessState.UNRESTRICTED,
            VehicleUxRestrictions(false, 0, 120, 20, 4))
    }
}
