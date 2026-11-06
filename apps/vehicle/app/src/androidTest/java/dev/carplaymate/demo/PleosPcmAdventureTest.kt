package dev.carplaymate.demo

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit synthetic-PCM transport experiment, NOT a microphone/child-voice test.
 * Real PleosSpeechInput, ViewModel, local interpreter/storage and production composed output are used.
 * Composed output can include bundled WAV/cues and real Pleos TTS; two plans do not mean two SDK-only utterances.
 * Only PleosSttEngine.request is substituted: it sends one reviewed fixed PCM fixture,
 * then completeAudioSend(), and never calls SpeechToText.request(). No fake final result
 * can satisfy the recognition/action assertions. The later stale-result probe replays
 * the already received real synthetic result only after the experiment explicitly pauses.
 */
@RunWith(AndroidJUnit4::class)
class PleosPcmAdventureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var deadlineMs = Long.MAX_VALUE

    @Suppress("DEPRECATION")
    @Test fun oneFixedPcmChoiceUsesRealRecognitionGameAndFeedback() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Enable pleosPcmAdventureTests=true explicitly on the prepared Pleos runtime.",
            args.getString("pleosPcmAdventureTests") == "true")
        deadlineMs = SystemClock.elapsedRealtime() + 180_000L
        val target = instrumentation.targetContext
        val runId = UUID.randomUUID().toString()
        val transportMode = args.getString("pleosPcmTransport") ?: "paced-100ms"
        val trace = Trace()
        val scope = AtomicReference<ScopedApplication?>()
        val store = AtomicReference<ViewModelStore?>()
        val model = AtomicReference<DeveloperViewModel?>()
        val engine = AtomicReference<PcmEngine?>()
        val input = AtomicReference<ObservedInput?>()
        val output = AtomicReference<ObservedOutput?>()
        val activityOwner = AtomicReference<MainActivity?>()
        val lifecycleObserver = AtomicReference<DefaultLifecycleObserver?>()
        var scenario: ActivityScenario<MainActivity>? = null
        var stage = "preflight"
        var revision = 0
        val evidence = JSONObject()
            .put("schema_version", 1).put("run_id", runId)
            .put("started_at", Instant.now().toString()).put("android_api", Build.VERSION.SDK_INT)
            .put("scope", "fixed_synthetic_pcm_transport_into_real_pleos_input_vm_local_game_and_composed_output")
            .put("sdk_mode", "ON_DEVICE").put("request_language", "ko-KR")
            .put("fixture_id", "river_choice_v1").put("fixture_asset", FIXTURE)
            .put("pcm_transport_mode", transportMode)
            .put("pcm_format", JSONObject().put("sample_rate_hz", 16_000)
                .put("bits_per_sample", 16).put("channels", 1).put("encoding", "signed_pcm_little_endian"))
            .put("sdk_microphone_request_called", false).put("physical_microphone_measured", false)
            .put("physical_audio_measured", false).put("raw_recognition_text_recorded", false)
            .put("visible_ui_session_exercised", false).put("fictional_fixture_nickname", "별")
            .put("output_scope", "production ComposedSpeechOutput: real bundled MediaPlayer playback and real Pleos TTS where text remains")
            .put("transport_substitution", "PleosSttEngine.request sends fixed PCM; SDK request is never called")
            .put("completion_boundary", "after real production composed feedback completion, explicit VM pause precedes forwarding its now-stale completion; no next listen window is requested")
            .put("late_result_probe_scope", "controlled replay of the real synthetic final into the closed production listener; not a second SDK recognition")
            .put("experiment_deadline_ms", 180_000).put("post_pause_observation_ms", 1_000)

        fun persist(status: String) {
            evidence.put("stage", stage).put("status", status).put("revision", ++revision)
                .put("events", trace.snapshot()).put("dropped_events", trace.dropped)
                .put("transport_call_failures", trace.failureSnapshot())
                .put("input", input.get()?.summary() ?: JSONObject.NULL)
                .put("pcm_transport", engine.get()?.summary() ?: JSONObject.NULL)
                .put("output", output.get()?.summary() ?: JSONObject.NULL)
            save(evidence, runId)
        }

        try {
            require(transportMode in setOf("whole-buffer", "paced-100ms")) { "Unsupported fixed PCM transport mode." }
            val expectedApp = checkedSha(args.getString("pleosPcmExpectedAppSha256"))
            val expectedFixture = checkedSha(args.getString("pleosPcmExpectedFixtureSha256"))
            assertEquals("The host must explicitly attest that its microphone input is disabled.",
                "true", args.getString("pleosPcmHostMicDisabled"))
            assertEquals("The host must explicitly attest that audible host output is disabled.",
                "true", args.getString("pleosPcmHostAudioOutputDisabled"))
            evidence.put("host_microphone_disabled_attestation", true)
                .put("host_audio_output_disabled_attestation", true)
                .put("host_attestation_scope", "runner-supplied precondition; not independently measured inside this test")
                .put("expected_app_sha256", expectedApp).put("expected_fixture_sha256", expectedFixture)
                .put("app_apk", fileIdentity(File(target.applicationInfo.sourceDir)))
                .put("test_apk", fileIdentity(File(instrumentation.context.applicationInfo.sourceDir)))
            assertEquals("Installed app differs from the reviewed candidate.", expectedApp,
                evidence.getJSONObject("app_apk").getString("sha256"))
            val pcm = instrumentation.context.assets.open(FIXTURE).use { stream ->
                val bounded = stream.readBytesBounded(MAX_PCM_BYTES)
                assertTrue("Fixed PCM must contain aligned samples and at most ten seconds.",
                    bounded.size in 3_200..MAX_PCM_BYTES && bounded.size % 2 == 0)
                bounded
            }
            assertEquals("PCM bytes differ from the independently reviewed fixed fixture.", expectedFixture, sha(pcm))
            assertFalse("A WAV container must not be sent as raw PCM.", pcm.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()))
            evidence.put("fixture_sha256", sha(pcm)).put("fixture_bytes", pcm.size)
                .put("fixture_duration_ms", pcm.size.toDouble() / 32.0)
            assertTrue("The actual candidate must enable Pleos.", BuildConfig.PLEOS_ENABLED)
            val services = target.packageManager.queryIntentServices(
                Intent(PleosSpeechRuntime.ACTION).setPackage(PleosSpeechRuntime.PACKAGE), 0)
                .mapNotNull { it.serviceInfo }.filter { it.enabled && it.exported && it.applicationInfo.enabled }
            assertTrue("An enabled exported real Pleos speech service must resolve.", services.isNotEmpty())
            val installed = target.packageManager.getPackageInfo(PleosSpeechRuntime.PACKAGE, 0)
            for (permission in listOf(PleosSpeechRuntime.STT_PERMISSION, PleosSpeechRuntime.TTS_PERMISSION)) {
                assertEquals("Actual speech permission is required.", PackageManager.PERMISSION_GRANTED,
                    target.checkSelfPermission(permission))
                assertNull("Actual production speech capability must pass.",
                    PleosSpeechRuntime.unavailableFailure(target, permission))
            }
            assertTrue("Pleos must be selected without an Android fallback.", SpeechProviders(target).usesPleos)
            evidence.put("service_package", installed.packageName)
                .put("service_version_name", installed.versionName ?: JSONObject.NULL)
                .put("service_version_code", installed.longVersionCode)
                .put("resolved_services", JSONArray(services.map { "${it.packageName}/${it.name}" }.sorted()))
                .put("stt_permission_granted", true).put("tts_permission_granted", true)
            persist("running")

            stage = "foreground-and-isolated-preparation"
            val activity = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
            assertEquals(Lifecycle.State.RESUMED, activity.state)
            val visibleModel = AtomicReference<DeveloperViewModel>()
            activity.onActivity { visibleModel.set(ViewModelProvider(it)[DeveloperViewModel::class.java]) }
            await(25_000) { main { !visibleModel.get().ui.value.busy && visibleModel.get().ui.value.speechReady } }
            main {
                assertTrue("The visible app must remain quiet during the isolated experiment.", visibleModel.get().ui.value.quiet)
                assertFalse(visibleModel.get().ui.value.microphoneListening)
            }
            val app = ScopedApplication(target).also { scope.set(it) }
            assertTrue("Isolated voice preference could not be saved.", InteractionPreferences(app).save(InteractionMode.VOICE))
            main {
                val realInput = PleosSpeechInput(
                    engineFactory = {
                        check(engine.get() == null) { "Only one real PCM transport is permitted." }
                        try { PcmEngine(target, pcm, transportMode, trace).also { engine.set(it) } }
                        catch (error: Throwable) {
                            trace.failed("stt_constructor", error)
                            // Keep test-SDK linkage errors inside the production failure route,
                            // rather than crashing main before safe evidence can be saved.
                            throw IllegalStateException("Fixed PCM transport construction failed.")
                        }
                    },
                    capability = { check -> PleosSpeechRuntime.unavailableReason(target, PleosSpeechRuntime.STT_PERMISSION, check) },
                    typedCapability = { check -> PleosSpeechRuntime.unavailableFailure(target, PleosSpeechRuntime.STT_PERMISSION, check)
                        ?.let { SpeechInputFailure(it.message,
                            if (it.cause == RecoveryCause.INPUT_PERMISSION) "MIC_PERMISSION" else "USER", it.cause) } },
                )
                val observed = ObservedInput(realInput, trace).also { input.set(it) }
                val viewModels = ViewModelStore().also { store.set(it) }
                val vm = ViewModelProvider(viewModels, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = DeveloperViewModel(
                        app, null,
                        inputFactory = { observed },
                        outputFactory = { interrupted ->
                            val actual = SpeechProviders(target).output {
                                trace.mark("output_focus_interrupted")
                                interrupted()
                            }
                            check(actual is ComposedSpeechOutput) { "The current bundled production output is required." }
                            ObservedOutput(actual, trace, beforeFeedbackCompletion = {
                                trace.mark("explicit_pause_after_feedback_completion")
                                checkNotNull(model.get()).pause()
                            }).also { output.set(it) }
                        },
                        // Default production vehicle provider, capture focus and signal output.
                    ) as T
                })[DeveloperViewModel::class.java]
                model.set(vm)
                evidence.put("vehicle_access_before_foreground", vm.ui.value.vehicleAccess.name)
            }
            val vm = checkNotNull(model.get())
            activity.onActivity { owner ->
                val observer = object : DefaultLifecycleObserver {
                    override fun onResume(owner: LifecycleOwner) = vm.foreground(owner as MainActivity)
                    override fun onPause(owner: LifecycleOwner) = vm.background()
                }
                activityOwner.set(owner)
                lifecycleObserver.set(observer)
                // Adding to an already resumed Activity dispatches the current lifecycle.
                // Later onPause reaches the isolated VM just as MainActivity reaches its own VM.
                owner.lifecycle.addObserver(observer)
            }
            await(25_000) { main {
                val state = vm.ui.value
                !state.busy && state.speechReady && state.adventures.isNotEmpty() && state.vehicleAccess == VehicleAccessState.UNRESTRICTED
            } }
            main {
                val state = vm.ui.value
                evidence.put("vehicle_access_at_start", state.vehicleAccess.name)
                assertEquals("Real AAOS interaction permission is required; this is not a gear/transitions test.",
                    VehicleAccessState.UNRESTRICTED, state.vehicleAccess)
                assertTrue(state.quiet && state.voiceInput && !state.simulation)
                assertFalse(state.failure || state.localStorageProblem || state.microphoneListening)
                assertEquals(0, input.get()!!.opens.get())
                assertEquals(0, output.get()!!.requests.get())
            }
            persist("running")

            stage = "question-pcm-choice-feedback"
            trace.mark("explicit_adventure_start")
            main { vm.start("별", null) }
            await(105_000) { main {
                val state = vm.ui.value
                output.get()!!.completions.get() >= 2 || state.failure ||
                    (state.snapshot != null && state.quiet && !state.busy)
            } }
            val observedOutput = checkNotNull(output.get())
            assertEquals("Both production composed question and feedback plans must complete.", 2, observedOutput.completions.get())
            assertEquals(2, observedOutput.requests.get())
            assertEquals(0, observedOutput.failures.get())
            assertTrue("The completed second plan must be the actual ordered RIVER feedback and next question.",
                observedOutput.feedbackPlanMatched.get() == true)
            val observedInput = checkNotNull(input.get())
            assertEquals(1, observedInput.opens.get())
            assertEquals("One real completed recognition must pass the production input gate.", 1, observedInput.finals.get())
            assertEquals(0, observedInput.failures.get())
            val realEngine = checkNotNull(engine.get())
            assertEquals(realEngine.plannedChunks, realEngine.sendCount.get())
            assertEquals(1, realEngine.completeSendCount.get())
            assertEquals(1, realEngine.realFinalCount.get())
            assertEquals(true, realEngine.fixtureMatched.get())
            assertEquals(1, realEngine.stopCount.get())
            assertEquals(1, realEngine.releaseCount.get())
            await(15_000) { main { vm.ui.value.quiet && !vm.ui.value.busy && vm.ui.value.snapshot?.status == "PAUSED" } }
            val paused = main { vm.ui.value }.also { assertFalse(it.failure || it.microphoneListening) }
            val snapshot = checkNotNull(paused.snapshot)
            assertRiver(snapshot)
            val durable = runBlocking { withTimeout(10_000) { LocalGameClient(app).snapshot(snapshot.sessionId) } }
            assertRiver(durable)
            assertEquals(snapshot.version, durable.version)
            assertEquals("PAUSED", durable.status)
            evidence.put("game", JSONObject().put("pack_id", snapshot.game.getString("pack_id"))
                .put("pack_version", snapshot.game.getString("pack_version"))
                .put("scene_id", snapshot.game.getString("scene_id"))
                .put("route", "RIVER").put("clue", "RIVER_ROUTE").put("history_count", 1)
                .put("status", snapshot.status).put("version", snapshot.version)
                .put("durable_snapshot_verified", true))
            persist("running")

            stage = "closed-input-late-result-probe"
            val before = snapshot.game.toString()
            main {
                realEngine.replayReceivedFinalAfterStop()
                realEngine.verifyStoppedTransport()
            }
            val observationEnd = SystemClock.elapsedRealtime() + remaining(1_000)
            while (SystemClock.elapsedRealtime() < observationEnd) SystemClock.sleep(25)
            main {
                val state = vm.ui.value
                assertTrue(state.quiet)
                assertFalse(state.busy || state.failure || state.microphoneListening)
                assertNull(state.captureStage)
                assertEquals(snapshot.version, state.snapshot!!.version)
                assertEquals(before, state.snapshot!!.game.toString())
                assertEquals(1, observedInput.finals.get())
                assertEquals(1, observedInput.opens.get())
                assertEquals(2, observedOutput.requests.get())
                assertEquals(2, observedOutput.completions.get())
                assertEquals(1, realEngine.realFinalCount.get())
                realEngine.assertTransportCompleteAndStopped()
            }
            evidence.put("controlled_late_result_ignored", true)

            stage = "cleanup"
            main {
                lifecycleObserver.getAndSet(null)?.let { activityOwner.get()?.lifecycle?.removeObserver(it) }
                activityOwner.set(null)
                store.getAndSet(null)?.clear()
            }
            runBlocking { withTimeout(10_000) { LocalGameClient(app).deleteAll() } }
            app.clean()
            scope.set(null)
            activity.close()
            scenario = null
            assertEquals(0, trace.count("output_focus_interrupted"))
            assertEquals(0, trace.dropped)
            assertEquals("No swallowed SDK transport/cleanup failure may pass.", 0, trace.failureSnapshot().length())
            evidence.put("completed_at", Instant.now().toString()).put("isolated_storage_removed", true)
            stage = "complete"
            persist("passed")
        } catch (error: Throwable) {
            evidence.put("failure_diagnostic", safeThrowable(error))
            model.get()?.let { vm -> runCatching { main {
                evidence.put("failure_ui", JSONObject().put("quiet", vm.ui.value.quiet)
                    .put("busy", vm.ui.value.busy)
                    .put("snapshot_status", vm.ui.value.snapshot?.status ?: JSONObject.NULL)
                    .put("snapshot_version", vm.ui.value.snapshot?.version ?: JSONObject.NULL)
                    .put("recovery_cause", vm.ui.value.recoveryCause?.name ?: JSONObject.NULL)
                    .put("speech_problem", vm.ui.value.speechProblem?.name ?: JSONObject.NULL)
                    .put("vehicle_access", vm.ui.value.vehicleAccess.name))
            } } }
            runCatching { persist("failed") }
            throw AssertionError("Pleos PCM experiment failed at $stage; inspect safe typed evidence.")
        } finally {
            // Queued cleanup reads ownership after any slow main-thread SDK call has returned.
            val cleanup = FutureTask(Callable {
                lifecycleObserver.getAndSet(null)?.let { activityOwner.get()?.lifecycle?.removeObserver(it) }
                activityOwner.set(null)
                store.getAndSet(null)?.clear()
                input.get()?.close()
                output.get()?.shutdown()
            })
            Handler(Looper.getMainLooper()).post(cleanup)
            val cleanedOnMain = runCatching { cleanup.get(5, TimeUnit.SECONDS); true }.getOrDefault(false)
            if (cleanedOnMain) scope.getAndSet(null)?.let { app ->
                runCatching { runBlocking { withTimeout(5_000) { LocalGameClient(app).deleteAll() } }; app.clean() }
            }
            runCatching { scenario?.close() }
        }
    }

    private class PcmEngine(context: Context, private val pcm: ByteArray, private val transportMode: String,
        private val trace: Trace) : PleosSttEngine {
        private val sdk = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
        private val handler = Handler(Looper.getMainLooper())
        private val paced = transportMode == "paced-100ms"
        val plannedChunks = if (paced) (pcm.size + 3_199) / 3_200 else 1
        private var requested = false
        private var closed = false
        private var generation = 0
        private var pendingTask: Runnable? = null
        private var lastScheduledTask: Runnable? = null
        private val pendingTasks = AtomicInteger()
        private val cancelledTasks = AtomicInteger()
        private val blockedTasks = AtomicInteger()
        private val submittedBytes = AtomicInteger()
        private val submittedHash = AtomicReference<String?>()
        private val submittedDigest = MessageDigest.getInstance("SHA-256")
        private val chunks = mutableListOf<JSONObject>()
        @Volatile private var stoppedAtNs: Long? = null
        @Volatile private var completeAtNs: Long? = null
        @Volatile private var stoppedProbeVerified = false
        private var productionListener: ResultListener? = null
        private var bridge: ResultListener? = null
        private val actualFinal = AtomicReference<String?>()
        val sendCount = AtomicInteger()
        val completeSendCount = AtomicInteger()
        val realFinalCount = AtomicInteger()
        val stopCount = AtomicInteger()
        val releaseCount = AtomicInteger()
        val fixtureMatched = AtomicReference<Boolean?>()
        private val recognizedSha = AtomicReference<String?>()
        private val partials = AtomicInteger()

        override fun initialize(listener: OnInitializeListener) = call("stt_initialize") {
            sdk.initialize(object : OnInitializeListener {
                override fun onSuccess() { trace.mark("stt_initialize_success"); listener.onSuccess() }
                override fun onFailure(errorType: ErrorType) { trace.mark("stt_initialize_failure", errorType.name); listener.onFailure(errorType) }
            })
        }
        override fun setLocale(locale: Locale) = call("stt_set_locale") {
            check(locale == Locale.KOREA)
            sdk.setLocale(locale)
        }
        override fun addListener(listener: ResultListener) = call("stt_add_listener") {
            check(productionListener == null)
            productionListener = listener
            val observed = object : ResultListener {
                override fun onUpdated(stt: String, completed: Boolean) {
                    if (completed) {
                        realFinalCount.incrementAndGet()
                        // In memory only. Never put this string or any partial into evidence/logs.
                        actualFinal.compareAndSet(null, stt)
                        recognizedSha.compareAndSet(null, sha(stt.toByteArray(Charsets.UTF_8)))
                        fixtureMatched.compareAndSet(null, normalized(stt) == normalized(FIXED_PHRASE))
                        trace.mark("stt_real_final_receipt")
                    } else partials.incrementAndGet()
                    listener.onUpdated(stt, completed)
                }
                override fun onStartedRecognition() { trace.mark("stt_started_recognition"); listener.onStartedRecognition() }
                override fun onEndedRecognition() { trace.mark("stt_ended_recognition"); listener.onEndedRecognition() }
                override fun onReady() { trace.mark("stt_ready"); listener.onReady() }
                override fun onError(errorType: ErrorType) { trace.mark("stt_error", errorType.name); listener.onError(errorType) }
                override fun onUpdatedEpdData(on: Long, off: Long) = listener.onUpdatedEpdData(on, off)
                override fun onUpdatedRms(rms: Double) = listener.onUpdatedRms(rms)
            }
            bridge = observed
            sdk.addListener(observed)
        }
        override fun request() {
            check(Looper.myLooper() == Looper.getMainLooper() && !requested && !closed)
            requested = true
            val token = ++generation
            // The default preserves the original whole-buffer transport. Pacing is only
            // an opt-in timing comparison, not a diagnosis of an SDK ordering race.
            // Deliberately do not call sdk.request(), AudioRecord or any microphone API.
            if (paced) sendNext(token, 0) else {
                sendChunk(0, pcm)
                if (active(token)) completeSend()
            }
        }
        private fun active(token: Int): Boolean = !closed && token == generation
        private fun sendChunk(offset: Int, bytes: ByteArray) {
            check(!closed)
            val index = sendCount.incrementAndGet()
            check(index <= plannedChunks)
            val entry = JSONObject().put("index", index).put("offset", offset)
                .put("bytes", bytes.size).put("sha256", sha(bytes))
                .put("elapsed_enter_ns", SystemClock.elapsedRealtimeNanos())
            trace.mark("pcm_chunk_submit", index.toString())
            var returned = false
            try {
                call("stt_send_audio") { sdk.sendAudio(bytes) }
                returned = true
                submittedDigest.update(bytes)
                if (submittedBytes.addAndGet(bytes.size) == pcm.size) {
                    submittedHash.set(hex(submittedDigest.digest()))
                }
            } finally {
                entry.put("returned", returned).put("elapsed_return_ns", SystemClock.elapsedRealtimeNanos())
                synchronized(chunks) { chunks += entry }
            }
        }
        private fun completeSend() {
            check(!closed && submittedBytes.get() == pcm.size)
            completeAtNs = SystemClock.elapsedRealtimeNanos()
            call("stt_complete_audio_send") {
                check(completeSendCount.incrementAndGet() == 1)
                sdk.completeAudioSend()
            }
        }
        private fun sendNext(token: Int, offset: Int) {
            if (!active(token)) return
            val end = minOf(offset + 3_200, pcm.size)
            sendChunk(offset, pcm.copyOfRange(offset, end))
            if (active(token)) schedule(token) {
                if (end == pcm.size) completeSend() else sendNext(token, end)
            }
        }
        private fun schedule(token: Int, action: () -> Unit) {
            check(pendingTask == null)
            val notBeforeNs = SystemClock.elapsedRealtimeNanos() + 100_000_000L
            val task = object : Runnable {
                override fun run() {
                    if (!active(token)) {
                        blockedTasks.incrementAndGet()
                        trace.mark("pcm_stale_scheduled_task_blocked")
                        return
                    }
                    // Handler uses millisecond uptime. Recheck the monotonic deadline so
                    // its rounding cannot make a nominal 100ms interval shorter.
                    val waitNs = notBeforeNs - SystemClock.elapsedRealtimeNanos()
                    if (waitNs > 0) {
                        if (!handler.postDelayed(this, (waitNs + 999_999L) / 1_000_000L)) {
                            trace.failed("pcm_schedule", IllegalStateException("PCM scheduling failed."))
                            cancelScheduled()
                        }
                        return
                    }
                    pendingTask = null
                    pendingTasks.set(0)
                    try { action() }
                    catch (error: Throwable) {
                        // Preserve the real failure; do not manufacture a recognition event
                        // or change the production input timeout to conceal an async failure.
                        trace.failed("pcm_scheduled_dispatch", error)
                        cancelScheduled()
                    }
                }
            }
            pendingTask = task
            lastScheduledTask = task
            pendingTasks.set(1)
            check(handler.postDelayed(task, 100L)) { "PCM scheduling failed." }
        }
        private fun cancelScheduled() {
            closed = true
            generation++
            pendingTask?.let { handler.removeCallbacks(it); cancelledTasks.incrementAndGet() }
            pendingTask = null
            pendingTasks.set(0)
        }
        override fun stop() = call("stt_stop") {
            if (stoppedAtNs == null) stoppedAtNs = SystemClock.elapsedRealtimeNanos()
            cancelScheduled()
            stopCount.incrementAndGet()
            sdk.stop()
        }
        override fun removeListener(listener: ResultListener) = call("stt_remove_listener") {
            cancelScheduled()
            check(listener === productionListener)
            sdk.removeListener(checkNotNull(bridge))
        }
        override fun release() = call("stt_release") { cancelScheduled(); releaseCount.incrementAndGet(); sdk.release() }
        fun verifyStoppedTransport() {
            check(closed && stoppedAtNs != null && pendingTask == null && pendingTasks.get() == 0)
            val sends = sendCount.get()
            val completes = completeSendCount.get()
            if (paced) {
                // Controlled replay of an already scheduled task after stop: no new SDK
                // request, no fabricated result, and not a second audio transmission.
                val before = blockedTasks.get()
                checkNotNull(lastScheduledTask).run()
                check(blockedTasks.get() == before + 1)
            }
            check(sendCount.get() == sends && completeSendCount.get() == completes)
            stoppedProbeVerified = true
        }
        fun assertTransportCompleteAndStopped() {
            check(stoppedProbeVerified && closed && pendingTask == null && pendingTasks.get() == 0)
            check(sendCount.get() == plannedChunks && submittedBytes.get() == pcm.size && submittedHash.get() == sha(pcm))
            check(completeSendCount.get() == 1 && blockedTasks.get() == (if (paced) 1 else 0))
            val sent = synchronized(chunks) { chunks.toList() }
            check(sent.size == plannedChunks && sent.all { it.getBoolean("returned") })
            check(sent.last().getLong("elapsed_return_ns") <= checkNotNull(completeAtNs))
            check(checkNotNull(completeAtNs) <= checkNotNull(stoppedAtNs))
            if (paced) {
                sent.zipWithNext().forEach { (before, after) ->
                    check(after.getLong("elapsed_enter_ns") - before.getLong("elapsed_return_ns") >= 100_000_000L)
                }
                check(checkNotNull(completeAtNs) - sent.last().getLong("elapsed_return_ns") >= 100_000_000L)
            }
        }
        fun replayReceivedFinalAfterStop() {
            check(stopCount.get() == 1 && releaseCount.get() == 1 && realFinalCount.get() == 1)
            trace.mark("controlled_late_final_replay")
            checkNotNull(productionListener).onUpdated(checkNotNull(actualFinal.get()), true)
        }
        fun summary(): JSONObject = JSONObject().put("send_audio_calls", sendCount.get())
            .put("mode", transportMode).put("planned_chunks", plannedChunks)
            .put("minimum_schedule_interval_ms", if (paced) 100 else 0)
            .put("schedule_scope", "minimum scheduling interval; actual monotonic times are observed, not an exact 100ms performance claim")
            .put("submitted_bytes", submittedBytes.get()).put("submitted_sha256", submittedHash.get() ?: JSONObject.NULL)
            .put("chunks", synchronized(chunks) { JSONArray(chunks.toList()) })
            .put("complete_elapsed_ns", completeAtNs ?: JSONObject.NULL).put("stop_elapsed_ns", stoppedAtNs ?: JSONObject.NULL)
            .put("scheduled_pending", pendingTasks.get()).put("scheduled_cancelled", cancelledTasks.get())
            .put("stale_scheduled_tasks_blocked", blockedTasks.get()).put("stopped_transport_probe_verified", stoppedProbeVerified)
            .put("stop_probe_scope", if (paced) "controlled replay of the last scheduled task after stop; verifies generation rejection, not an observed pending-task race"
                else "no pending scheduled task and no extra SDK calls during the post-stop observation")
            .put("complete_audio_send_calls", completeSendCount.get()).put("sdk_request_calls", 0)
            .put("real_final_callbacks", realFinalCount.get()).put("partial_callbacks", partials.get())
            .put("known_fixture_match", fixtureMatched.get() ?: JSONObject.NULL)
            .put("recognized_sha256", recognizedSha.get() ?: JSONObject.NULL)
            .put("match_normalization", "remove whitespace and terminal dot/question/exclamation punctuation; original recognition delivered unchanged")
            .put("stop_calls", stopCount.get()).put("release_calls", releaseCount.get())
        private fun call(name: String, body: () -> Unit) {
            trace.mark("${name}_enter")
            try { body(); trace.mark("${name}_return") }
            catch (error: Throwable) {
                trace.failed(name, error)
                // Even LinkageError must reach the adapter's Exception failure route. The
                // original class/frames are recorded safely; raw provider messages are not.
                throw IllegalStateException("Fixed PCM transport call failed.")
            }
        }
    }

    private class ObservedInput(private val actual: PleosSpeechInput, private val trace: Trace) : SpeechCaptureInput by actual {
        val opens = AtomicInteger()
        val finals = AtomicInteger()
        val failures = AtomicInteger()
        override fun open(ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
            onFinal: (CaptureTicket, String) -> Unit, onFailure: (CaptureTicket, SpeechInputFailure) -> Unit) {
            check(opens.incrementAndGet() == 1) { "The experiment permits one capture only." }
            trace.mark("production_input_open")
            actual.open(ticket, { received, stage -> trace.mark("production_input_stage", stage.name); onStage(received, stage) },
                { received, text -> finals.incrementAndGet(); trace.mark("production_input_final"); onFinal(received, text) },
                { received, failure -> failures.incrementAndGet(); trace.mark("production_input_failure", failure.cause?.name); onFailure(received, failure) })
        }
        fun summary(): JSONObject = JSONObject().put("adapter", actual.javaClass.name)
            .put("opens", opens.get()).put("finals", finals.get()).put("failures", failures.get())
    }

    private class ObservedOutput(private val actual: SpeechOutput, private val trace: Trace,
        private val beforeFeedbackCompletion: () -> Unit) : SpeechOutput by actual {
        val requests = AtomicInteger()
        val completions = AtomicInteger()
        val failures = AtomicInteger()
        private val starts = AtomicInteger()
        val feedbackPlanMatched = AtomicReference<Boolean?>()
        private val feedbackPlanEvidence = AtomicReference<JSONObject?>()
        override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
            failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) {
            val request = requests.incrementAndGet()
            check(request in 1..2) { "Only question and feedback output are permitted." }
            if (request == 2) {
                val texts = plan.segments.map { it.text }
                val required = plan.segments.all { it.required }
                val matched = texts == EXPECTED_RIVER_FEEDBACK && required
                feedbackPlanMatched.set(matched)
                feedbackPlanEvidence.set(JSONObject().put("matched", matched)
                    .put("segment_count", texts.size).put("required_all_true", required)
                    .put("plan_text_sha256", sha(JSONArray(texts).toString().toByteArray(Charsets.UTF_8)))
                    .put("expected_plan_text_sha256", sha(JSONArray(EXPECTED_RIVER_FEEDBACK).toString().toByteArray(Charsets.UTF_8)))
                    .put("segment_sha256", JSONArray(texts.map { sha(it.toByteArray(Charsets.UTF_8)) }))
                    .put("hash_scope", "ordered segment text JSON array, UTF-8; request IDs excluded"))
            }
            trace.mark("output_request_$request")
            // Observe only: the production plan and callback semantics are not replaced, even
            // if the authored-text assertion will fail when this experiment checks its result.
            actual.speak(plan, generation, {
                completions.incrementAndGet()
                trace.mark("output_completed_$request")
                if (request == 2) beforeFeedbackCompletion()
                complete()
            }, {
                failures.incrementAndGet()
                trace.mark("output_failed_$request", it.cause.name)
                failure(it)
            }, {
                starts.incrementAndGet()
                trace.mark("output_started_$request")
                started()
            })
        }
        fun summary(): JSONObject = JSONObject().put("adapter", actual.javaClass.name)
            .put("requests", requests.get()).put("completions", completions.get())
            .put("starts", starts.get()).put("failures", failures.get())
            .put("feedback_plan", feedbackPlanEvidence.get() ?: JSONObject.NULL)
    }

    private class Trace {
        private val events = mutableListOf<JSONObject>()
        private val failures = mutableListOf<JSONObject>()
        @Volatile var dropped = 0
            private set
        @Synchronized fun mark(name: String, code: String? = null) {
            if (events.size >= 200) { dropped++; return }
            events += JSONObject().put("name", name).put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
                .put("main_thread", Looper.myLooper() == Looper.getMainLooper()).put("code", code ?: JSONObject.NULL)
        }
        @Synchronized fun snapshot(): JSONArray = JSONArray(events.toList())
        @Synchronized fun failureSnapshot(): JSONArray = JSONArray(failures.toList())
        @Synchronized fun failed(boundary: String, error: Throwable) {
            mark("${boundary}_failed", error.javaClass.name)
            if (failures.size < 8) failures += JSONObject().put("boundary", boundary)
                .put("diagnostic", safeThrowable(error))
        }
        @Synchronized fun count(name: String): Int = events.count { it.getString("name") == name }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "pleos_pcm_" + UUID.randomUUID()
        private val parent = context.noBackupFilesDir.canonicalFile
        private val directory = File(parent, prefix).also { check(it.mkdirs()) }
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
            check(directory.canonicalFile.parentFile == parent && directory.name == prefix)
            synchronized(names) { names.forEach { check(super.deleteSharedPreferences(it)) } }
            val alias = "onu.records.v1." + sha(directory.canonicalPath.toByteArray(Charsets.UTF_8)).take(32)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(directory.deleteRecursively())
        }
    }

    private fun assertRiver(snapshot: Snapshot) {
        val game = snapshot.game
        assertEquals(DEFAULT_ADVENTURE_ID, game.getString("pack_id"))
        assertEquals("0.11.0", game.getString("pack_version"))
        assertEquals("river_evidence", game.getString("scene_id"))
        assertEquals("RIVER", game.getJSONObject("flags").getString("route"))
        assertTrue((0 until game.getJSONArray("clues").length()).any { game.getJSONArray("clues").getString(it) == "RIVER_ROUTE" })
        assertEquals(1, game.getJSONArray("history").length())
        assertEquals("RIVER", game.getJSONArray("history").getJSONObject(0).getString("action_id"))
    }
    private fun remaining(maximum: Long): Long = (deadlineMs - SystemClock.elapsedRealtime()).let {
        check(it > 0) { "Bounded PCM experiment deadline reached." }; minOf(it, maximum)
    }
    private fun await(milliseconds: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + remaining(milliseconds)
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < end) { "Bounded PCM stage did not complete." }
            SystemClock.sleep(25)
        }
    }
    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable { action() })
        check(Handler(Looper.getMainLooper()).post(task))
        try { return task.get(remaining(35_000), TimeUnit.MILLISECONDS) }
        catch (error: Throwable) { task.cancel(false); throw error }
    }
    private fun fileIdentity(file: File): JSONObject {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return JSONObject().put("sha256", hex(digest.digest())).put("bytes", file.length())
    }
    private fun save(evidence: JSONObject, runId: String) {
        val bytes = evidence.toString(2).toByteArray(Charsets.UTF_8)
        check(bytes.size <= 64 * 1024) { "Bounded PCM evidence exceeded its limit." }
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("pleos-pcm-adventure"))
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "$runId.json")
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
        check(file.readBytes().contentEquals(bytes))
        instrumentation.sendStatus(2, Bundle().apply {
            putString("pleos_pcm_run_id", runId)
            putString("pleos_pcm_status", evidence.getString("status"))
            putInt("pleos_pcm_revision", evidence.getInt("revision"))
            putInt("pleos_pcm_json_bytes", bytes.size)
            putString("pleos_pcm_json_sha256", sha(bytes))
            putString("pleos_pcm_json_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            putString("pleos_pcm_evidence_path", file.absolutePath)
        })
    }
    private companion object {
        const val FIXTURE = "pleos-pcm/river-choice.pcm"
        const val FIXED_PHRASE = "물가로 갈래"
        const val MAX_PCM_BYTES = 320_000
        // Independent authored expectations from dinosaur 0.11.0: RIVER.feedback then
        // river_evidence.prompt with the fixed solo nickname. Never read from the actual plan.
        val EXPECTED_RIVER_FEEDBACK = listOf(
            "물가에 바퀴 자국이 나 있어. 오누도 옆에서 흔적을 찾아볼게.",
            "별, 물가에 바퀴 자국과 운반 상자가 있네. 자국을 따라갈까, 상자 표지를 볼까?",
        )
        fun normalized(text: String): String = text.trim().trimEnd('.', '?', '!', '。', '？', '！').filterNot(Char::isWhitespace)
        fun checkedSha(value: String?): String {
            check(value != null && value.matches(Regex("[0-9a-f]{64}"))) { "An independently reviewed SHA-256 argument is required." }
            return value
        }
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                check(output.size() + count <= limit) { "PCM fixture exceeds its permitted size." }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
        /** No exception message, toString(), file paths, recognition text or suppressed payloads. */
        fun safeThrowable(error: Throwable): JSONObject {
            val causes = JSONArray()
            val seen = IdentityHashMap<Throwable, Boolean>()
            var current: Throwable? = error
            while (current != null && causes.length() < 6 && seen.put(current, true) == null) {
                causes.put(JSONObject().put("class", current.javaClass.name)
                    .put("frames", JSONArray(current.stackTrace.take(8).map { frame -> JSONObject()
                        .put("class", frame.className).put("method", frame.methodName).put("line", frame.lineNumber) })))
                current = current.cause
            }
            return JSONObject().put("causes", causes)
        }
    }
}
