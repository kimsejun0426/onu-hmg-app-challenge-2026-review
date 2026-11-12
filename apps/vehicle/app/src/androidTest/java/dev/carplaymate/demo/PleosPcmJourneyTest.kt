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
 * Six pinned authored PCM inputs through real ON_DEVICE ASR, production input/ViewModel,
 * local game/storage and composed output. SDK microphone request transport alone is replaced.
 * No manufactured transcript, action or playback completion; no physical microphone/sound
 * measurement. The visible Activity stays quiet while an isolated production VM runs.
 */
@RunWith(AndroidJUnit4::class)
class PleosPcmJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var deadlineMs = Long.MAX_VALUE

    @Suppress("DEPRECATION")
    @Test(timeout = 400_000L)
    fun sixFixedPcmChoicesReachTheDurableEndingThroughRealRecognition() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Enable pleosPcmJourneyTests=true explicitly on the prepared Pleos runtime.",
            args.getString("pleosPcmJourneyTests") == "true")
        deadlineMs = SystemClock.elapsedRealtime() + 360_000L
        val target = instrumentation.targetContext
        val runId = UUID.randomUUID().toString()
        val trace = Trace()
        val engines = java.util.concurrent.CopyOnWriteArrayList<PcmEngine>()
        val scope = AtomicReference<ScopedApplication?>()
        val store = AtomicReference<ViewModelStore?>()
        val model = AtomicReference<DeveloperViewModel?>()
        val input = AtomicReference<ObservedInput?>()
        val output = AtomicReference<ObservedOutput?>()
        val activityOwner = AtomicReference<MainActivity?>()
        val lifecycleObserver = AtomicReference<DefaultLifecycleObserver?>()
        var scenario: ActivityScenario<MainActivity>? = null
        var cleaned = false
        var stage = "preflight"
        var revision = 0
        val evidence = JSONObject().put("schema_version", 1).put("run_id", runId)
            .put("started_at", Instant.now().toString()).put("android_api", Build.VERSION.SDK_INT)
            .put("scope", "six_fixed_synthetic_pcm_into_real_pleos_stt_game_and_composed_output")
            .put("sdk_mode", "ON_DEVICE").put("request_language", "ko-KR")
            .put("fixture_set_id", "dinosaur_river_journey_v1").put("fixture_manifest_asset", MANIFEST)
            .put("synthetic", true).put("fixed_authored_nickname", "별").put("pcm_transport_mode", "paced-100ms")
            .put("pcm_format", JSONObject().put("sample_rate_hz", 16_000).put("bits_per_sample", 16)
                .put("channels", 1).put("encoding", "signed_pcm_little_endian"))
            .put("sdk_microphone_request_called", false).put("physical_microphone_measured", false)
            .put("physical_audio_measured", false).put("raw_recognition_text_recorded", false)
            .put("visible_ui_journey", false).put("recognition_result_replay", false)
            .put("transport_substitution", "six pinned authored PCM fixtures replace SDK microphone request transport only")
            .put("output_scope", "real production ComposedSpeechOutput: bundled narration/cues and Pleos TTS, not seven pure SDK TTS requests")
            .put("completion_boundary", "six real finals and seven production output completions, then durable ENDED; no pause before final completion")
            .put("experiment_deadline_ms", 360_000).put("post_terminal_observation_ms", 1_000)
        fun persist(status: String) {
            evidence.put("stage", stage).put("status", status).put("revision", ++revision)
                .put("events", trace.snapshot()).put("dropped_events", trace.dropped)
                .put("transport_call_failures", trace.failureSnapshot())
                .put("pcm_transports", JSONArray(engines.map { it.summary() }))
                .put("input", input.get()?.summary() ?: JSONObject.NULL)
                .put("output", output.get()?.summary() ?: JSONObject.NULL)
            save(evidence, runId)
        }
        try {
            val expectedApp = checkedSha(args.getString("pleosPcmJourneyExpectedAppSha256"))
            val expectedManifest = checkedSha(args.getString("pleosPcmJourneyExpectedManifestSha256"))
            assertEquals("Host microphone input must be disabled.", "true", args.getString("pleosPcmJourneyHostMicDisabled"))
            assertEquals("Host audio output must be disabled.", "true", args.getString("pleosPcmJourneyHostOutputDisabled"))
            evidence.put("host_microphone_disabled_attestation", true).put("host_audio_output_disabled_attestation", true)
                .put("host_attestation_scope", "runner-supplied preconditions, not independent physical measurements")
                .put("expected_app_sha256", expectedApp).put("expected_manifest_sha256", expectedManifest)
                .put("app_apk", fileIdentity(File(target.applicationInfo.sourceDir)))
                .put("test_apk", fileIdentity(File(instrumentation.context.applicationInfo.sourceDir)))
            assertEquals(expectedApp, evidence.getJSONObject("app_apk").getString("sha256"))
            val manifestBytes = instrumentation.context.assets.open(MANIFEST).use { it.readBytesBounded(32 * 1024) }
            assertEquals("Fixture manifest pin differs.", expectedManifest, sha(manifestBytes))
            evidence.put("fixture_manifest_sha256", sha(manifestBytes))
            val fixtures = loadFixtures(JSONObject(String(manifestBytes, Charsets.UTF_8)))
            evidence.put("fixtures", JSONArray(fixtures.map { fixture -> JSONObject()
                .put("id", fixture.step.id).put("asset", fixture.step.asset).put("sha256", sha(fixture.pcm))
                .put("bytes", fixture.pcm.size).put("authored_text_sha256", sha(fixture.step.text.toByteArray(Charsets.UTF_8))) }))
            assertTrue(BuildConfig.PLEOS_ENABLED)
            val services = target.packageManager.queryIntentServices(
                Intent(PleosSpeechRuntime.ACTION).setPackage(PleosSpeechRuntime.PACKAGE), 0)
                .mapNotNull { it.serviceInfo }.filter { it.enabled && it.exported && it.applicationInfo.enabled }
            assertTrue("A real enabled exported Pleos speech service must resolve.", services.isNotEmpty())
            val installed = target.packageManager.getPackageInfo(PleosSpeechRuntime.PACKAGE, 0)
            for (permission in listOf(PleosSpeechRuntime.STT_PERMISSION, PleosSpeechRuntime.TTS_PERMISSION)) {
                assertEquals(PackageManager.PERMISSION_GRANTED, target.checkSelfPermission(permission))
                assertNull(PleosSpeechRuntime.unavailableFailure(target, permission))
            }
            assertTrue("No Android provider fallback.", SpeechProviders(target).usesPleos)
            evidence.put("service_package", installed.packageName).put("service_version_name", installed.versionName ?: JSONObject.NULL)
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
            main { assertTrue(visibleModel.get().ui.value.quiet); assertFalse(visibleModel.get().ui.value.microphoneListening) }
            val app = ScopedApplication(target).also { scope.set(it) }
            assertTrue(InteractionPreferences(app).save(InteractionMode.VOICE))
            main {
                val realInput = PleosSpeechInput(
                    engineFactory = {
                        try {
                            val index = engines.size
                            check(index in STEPS.indices) { "Only six real PCM transports are permitted." }
                            check(input.get()!!.opens.get() == index + 1)
                            if (index > 0) check(engines[index - 1].stopCount.get() == 1 && engines[index - 1].releaseCount.get() == 1)
                            PcmEngine(target, fixtures[index].pcm, "paced-100ms", trace, fixtures[index]).also { engines.add(it) }
                        } catch (error: Throwable) {
                            trace.failed("stt_constructor", error)
                            throw IllegalStateException("Fixed PCM journey transport construction failed.")
                        }
                    },
                    capability = { check -> PleosSpeechRuntime.unavailableReason(target, PleosSpeechRuntime.STT_PERMISSION, check) },
                    typedCapability = { check -> PleosSpeechRuntime.unavailableFailure(target, PleosSpeechRuntime.STT_PERMISSION, check)
                        ?.let { SpeechInputFailure(it.message,
                            if (it.cause == RecoveryCause.INPUT_PERMISSION) "MIC_PERMISSION" else "USER", it.cause) } },
                )
                val observed = ObservedInput(realInput, trace) { index ->
                    val snapshot = checkNotNull(checkNotNull(model.get()).ui.value.snapshot)
                    assertEquals("LISTENING", snapshot.phase); assertEquals("ACTIVE", snapshot.status)
                    assertProgress(snapshot, index)
                    assertEquals(index + 1, output.get()!!.requests.get())
                    assertEquals("Input must follow real output completion.", index + 1, output.get()!!.completions.get())
                    assertEquals(index, input.get()!!.finals.get())
                }.also { input.set(it) }
                val viewModels = ViewModelStore().also { store.set(it) }
                val vm = ViewModelProvider(viewModels, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = DeveloperViewModel(
                        app, null, inputFactory = { observed },
                        outputFactory = { interrupted ->
                            val actual = SpeechProviders(target).output { trace.mark("output_focus_interrupted"); interrupted() }
                            check(actual is ComposedSpeechOutput) { "Current real production composed output is required." }
                            ObservedOutput(actual, trace) { completedActions ->
                                assertProgress(checkNotNull(model.get()!!.ui.value.snapshot), completedActions)
                                assertEquals(completedActions, observed.finals.get()); assertEquals(completedActions, observed.opens.get())
                            }.also { output.set(it) }
                        },
                    ) as T
                })[DeveloperViewModel::class.java]
                model.set(vm)
            }
            val vm = checkNotNull(model.get())
            activity.onActivity { owner ->
                val observer = object : DefaultLifecycleObserver {
                    override fun onResume(owner: LifecycleOwner) = vm.foreground(owner as MainActivity)
                    override fun onPause(owner: LifecycleOwner) = vm.background()
                }
                activityOwner.set(owner); lifecycleObserver.set(observer); owner.lifecycle.addObserver(observer)
            }
            await(25_000) { main { val state = vm.ui.value
                !state.busy && state.speechReady && state.adventures.isNotEmpty() && state.vehicleAccess == VehicleAccessState.UNRESTRICTED
            } }
            main { val state = vm.ui.value
                assertTrue(state.quiet && state.voiceInput && !state.simulation)
                assertFalse(state.failure || state.localStorageProblem || state.microphoneListening)
                assertEquals(VehicleAccessState.UNRESTRICTED, state.vehicleAccess)
                assertEquals(0, input.get()!!.opens.get()); assertEquals(0, output.get()!!.requests.get())
                evidence.put("vehicle_access_at_start", state.vehicleAccess.name)
            }
            persist("running")

            stage = "six-real-choices-and-output-completions"
            trace.mark("explicit_adventure_start")
            main { vm.start("별", null) }
            await(300_000) { main { val state = vm.ui.value
                state.failure || state.snapshot?.status == "PAUSED" || (state.snapshot?.status == "ENDED" && !state.busy)
            } }
            val observedInput = checkNotNull(input.get())
            val observedOutput = checkNotNull(output.get())
            assertEquals(6, observedInput.opens.get()); assertEquals(6, observedInput.finals.get())
            assertEquals(0, observedInput.failures.get())
            assertEquals(7, observedOutput.requests.get()); assertEquals(7, observedOutput.completions.get())
            assertEquals(0, observedOutput.failures.get()); assertEquals(6, engines.size)
            main { engines.forEach { engine ->
                // A real exact final may close the transport before all scheduled PCM or
                // completeAudioSend. The branch-specific proof below still checks every byte.
                assertEquals(1, engine.realFinalCount.get())
                assertEquals(true, engine.fixtureMatched.get())
                assertEquals(1, engine.stopCount.get()); assertEquals(1, engine.releaseCount.get())
                engine.verifyStoppedTransport(); engine.assertTransportCompleteAndStopped()
            } }
            val ended = main { vm.ui.value }.also { assertFalse(it.failure || it.busy || it.microphoneListening) }
            val snapshot = checkNotNull(ended.snapshot)
            assertEquals("ENDED", snapshot.status); assertProgress(snapshot, 6)
            assertNull(snapshot.speechPlan); assertNull(snapshot.listenWindowId)
            val durable = runBlocking { withTimeout(10_000) { LocalGameClient(app).snapshot(snapshot.sessionId) } }
            assertEquals("ENDED", durable.status); assertProgress(durable, 6)
            assertEquals(snapshot.version, durable.version); assertEquals(snapshot.game.toString(), durable.game.toString())
            assertNull(durable.speechPlan); assertNull(durable.listenWindowId)
            evidence.put("game", JSONObject().put("pack_id", DEFAULT_ADVENTURE_ID).put("pack_version", "0.12.0")
                .put("scene_id", "ending_found").put("ending", "FOUND_TOGETHER").put("status", snapshot.status)
                .put("finished", true).put("history_count", 6).put("action_ids", JSONArray(STEPS.map { it.action }))
                .put("score_total", 8).put("version", snapshot.version).put("durable_snapshot_verified", true))
            persist("running")
            stage = "post-terminal-observation"
            val observationEnd = SystemClock.elapsedRealtime() + remaining(1_000)
            while (SystemClock.elapsedRealtime() < observationEnd) SystemClock.sleep(25)
            main { val state = vm.ui.value
                assertFalse(state.failure || state.busy || state.microphoneListening); assertNull(state.captureStage)
                assertEquals(snapshot.version, state.snapshot!!.version)
                assertEquals(snapshot.game.toString(), state.snapshot!!.game.toString())
                assertEquals(6, observedInput.opens.get()); assertEquals(6, observedInput.finals.get())
                assertEquals(7, observedOutput.requests.get()); assertEquals(7, observedOutput.completions.get())
                assertEquals(0, observedInput.failures.get()); assertEquals(0, observedOutput.failures.get())
                engines.forEach { it.assertTransportCompleteAndStopped(); assertEquals(1, it.realFinalCount.get()) }
                assertTrue(visibleModel.get().ui.value.quiet); assertFalse(visibleModel.get().ui.value.microphoneListening)
            }
            assertEquals(Lifecycle.State.RESUMED, activity.state)
            evidence.put("post_terminal_no_new_input_or_output", true)
            stage = "cleanup"
            main {
                lifecycleObserver.getAndSet(null)?.let { activityOwner.get()?.lifecycle?.removeObserver(it) }
                activityOwner.set(null); store.getAndSet(null)?.clear(); observedInput.close(); observedOutput.shutdown()
            }
            runBlocking { withTimeout(10_000) { LocalGameClient(app).deleteAll() } }
            app.clean(); scope.set(null); activity.close(); scenario = null
            assertEquals(0, trace.count("output_focus_interrupted")); assertEquals(0, trace.dropped)
            assertEquals("No swallowed transport, invariant or cleanup failure may pass.", 0, trace.failureSnapshot().length())
            cleaned = true
            evidence.put("completed_at", Instant.now().toString()).put("isolated_storage_removed", true)
            stage = "complete"; persist("passed")
        } catch (error: Throwable) {
            evidence.put("failure_diagnostic", safeThrowable(error))
            model.get()?.let { vm -> runCatching { main { val state = vm.ui.value
                evidence.put("failure_ui", JSONObject().put("quiet", state.quiet).put("busy", state.busy)
                    .put("snapshot_status", state.snapshot?.status ?: JSONObject.NULL)
                    .put("snapshot_version", state.snapshot?.version ?: JSONObject.NULL)
                    .put("recovery_cause", state.recoveryCause?.name ?: JSONObject.NULL)
                    .put("vehicle_access", state.vehicleAccess.name))
            } } }
            runCatching { persist("failed") }
            throw AssertionError("Pleos PCM journey failed at $stage; inspect safe typed evidence.")
        } finally {
            if (!cleaned) {
                val cleanup = FutureTask(Callable {
                    lifecycleObserver.getAndSet(null)?.let { activityOwner.get()?.lifecycle?.removeObserver(it) }
                    activityOwner.set(null); store.getAndSet(null)?.clear(); input.get()?.close(); output.get()?.shutdown()
                })
                Handler(Looper.getMainLooper()).post(cleanup)
                val mainCleaned = runCatching { cleanup.get(5, TimeUnit.SECONDS); true }.getOrDefault(false)
                if (mainCleaned) scope.getAndSet(null)?.let { app ->
                    runCatching { runBlocking { withTimeout(5_000) { LocalGameClient(app).deleteAll() } }; app.clean() }
                }
                runCatching { scenario?.close() }
            }
        }
    }

    private data class Step(val id: String, val asset: String, val text: String, val action: String)
    private data class Fixture(val step: Step, val pcm: ByteArray)
    private fun loadFixtures(manifest: JSONObject): List<Fixture> {
        assertEquals(1, manifest.getInt("schema_version")); assertEquals("dinosaur_river_journey_v1", manifest.getString("fixture_set_id"))
        assertEquals(DEFAULT_ADVENTURE_ID, manifest.getString("pack_id")); assertEquals("0.9.0", manifest.getString("pack_version"))
        assertTrue(manifest.getBoolean("synthetic"))
        val format = manifest.getJSONObject("format")
        assertEquals(16_000, format.getInt("sample_rate_hz")); assertEquals(16, format.getInt("bits_per_sample"))
        assertEquals(1, format.getInt("channels")); assertEquals("signed_pcm_little_endian", format.getString("encoding"))
        // Authored 0.9.0 PCM is reused unchanged; the current 0.12.0 game is independently checked.
        val entries = manifest.getJSONArray("fixtures"); assertEquals(6, entries.length())
        return STEPS.mapIndexed { index, step ->
            val entry = entries.getJSONObject(index)
            assertEquals(step.id, entry.getString("id")); assertEquals(step.asset, entry.getString("asset"))
            assertEquals(step.text, entry.getString("authored_text"))
            val expected = checkedSha(entry.getString("sha256"))
            val pcm = instrumentation.context.assets.open(step.asset).use { it.readBytesBounded(MAX_PCM_BYTES) }
            assertTrue("Aligned raw PCM, at most ten seconds.", pcm.size in 3_200..MAX_PCM_BYTES && pcm.size % 2 == 0)
            assertFalse("No WAV container may be sent as raw PCM.", pcm.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()))
            assertTrue("The PCM must not be all zero samples.", pcm.any { it != 0.toByte() })
            assertEquals(entry.getInt("bytes"), pcm.size); assertEquals(expected, sha(pcm)); Fixture(step, pcm)
        }
    }

    private class PcmEngine(context: Context, private val pcm: ByteArray, private val transportMode: String,
        private val trace: Trace, private val fixture: Fixture) : PleosSttEngine {
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
        // Only bytes from successful SDK sendAudio returns enter this observation buffer.
        // Never fill submitted_sha256 from the expected fixture prefix.
        private val submittedAudio = java.io.ByteArrayOutputStream()
        private val chunks = mutableListOf<JSONObject>()
        @Volatile private var stoppedAtNs: Long? = null
        @Volatile private var completeAtNs: Long? = null
        private val realFinalAtNs = AtomicReference<Long?>()
        @Volatile private var releasedAtNs: Long? = null
        @Volatile private var pendingAtStop: Int? = null
        @Volatile private var sendCallsAtStop: Int? = null
        @Volatile private var completeCallsAtStop: Int? = null
        @Volatile private var submittedBytesAtStop: Int? = null
        @Volatile private var submittedShaAtStop: String? = null
        @Volatile private var stoppedProbeVerified = false
        private var productionListener: ResultListener? = null
        private var bridge: ResultListener? = null
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
                override fun onSuccess() { trace.markFixture(fixture.step.id, "stt_initialize_success"); listener.onSuccess() }
                override fun onFailure(errorType: ErrorType) { trace.markFixture(fixture.step.id, "stt_initialize_failure", errorType.name); listener.onFailure(errorType) }
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
                        realFinalAtNs.compareAndSet(null, SystemClock.elapsedRealtimeNanos())
                        realFinalCount.incrementAndGet()
                        // In memory only. Never put this string or any partial into evidence/logs.
                        recognizedSha.compareAndSet(null, sha(stt.toByteArray(Charsets.UTF_8)))
                        fixtureMatched.compareAndSet(null, normalized(stt) == normalized(fixture.step.text))
                        trace.markFixture(fixture.step.id, "stt_real_final_receipt")
                    } else partials.incrementAndGet()
                    listener.onUpdated(stt, completed)
                }
                override fun onStartedRecognition() { trace.markFixture(fixture.step.id, "stt_started_recognition"); listener.onStartedRecognition() }
                override fun onEndedRecognition() { trace.markFixture(fixture.step.id, "stt_ended_recognition"); listener.onEndedRecognition() }
                override fun onReady() { trace.markFixture(fixture.step.id, "stt_ready"); listener.onReady() }
                override fun onError(errorType: ErrorType) { trace.markFixture(fixture.step.id, "stt_error", errorType.name); listener.onError(errorType) }
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
            trace.markFixture(fixture.step.id, "pcm_chunk_submit", index.toString())
            var returned = false
            try {
                call("stt_send_audio") { sdk.sendAudio(bytes) }
                returned = true
                val submitted = synchronized(submittedAudio) {
                    submittedAudio.write(bytes)
                    submittedAudio.toByteArray()
                }
                check(submittedBytes.addAndGet(bytes.size) == submitted.size)
                // Snapshot the actual successful submissions even when the SDK finishes early.
                submittedHash.set(sha(submitted))
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
                        trace.markFixture(fixture.step.id, "pcm_stale_scheduled_task_blocked")
                        return
                    }
                    // Handler uses millisecond uptime. Recheck the monotonic deadline so
                    // its rounding cannot make a nominal 100ms interval shorter.
                    val waitNs = notBeforeNs - SystemClock.elapsedRealtimeNanos()
                    if (waitNs > 0) {
                        if (!handler.postDelayed(this, (waitNs + 999_999L) / 1_000_000L)) {
                            trace.failedFixture(fixture.step.id, "pcm_schedule", IllegalStateException("PCM scheduling failed."))
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
                        trace.failedFixture(fixture.step.id, "pcm_scheduled_dispatch", error)
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
            if (stoppedAtNs == null) {
                stoppedAtNs = SystemClock.elapsedRealtimeNanos()
                pendingAtStop = pendingTasks.get()
                sendCallsAtStop = sendCount.get()
                completeCallsAtStop = completeSendCount.get()
                submittedBytesAtStop = submittedBytes.get()
                submittedShaAtStop = submittedHash.get()
            }
            cancelScheduled()
            stopCount.incrementAndGet()
            sdk.stop()
        }
        override fun removeListener(listener: ResultListener) = call("stt_remove_listener") {
            cancelScheduled()
            check(listener === productionListener)
            sdk.removeListener(checkNotNull(bridge))
        }
        override fun release() {
            call("stt_release") { cancelScheduled(); releaseCount.incrementAndGet(); sdk.release() }
            // Timestamp follows the real SDK return and the stt_release_return trace event.
            releasedAtNs = SystemClock.elapsedRealtimeNanos()
        }
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
        private fun completionKind(): String = when {
            realFinalCount.get() == 1 && completeSendCount.get() == 1 -> "explicit_complete_audio_send"
            realFinalCount.get() == 1 && completeSendCount.get() == 0 -> "early_real_final"
            else -> "incomplete"
        }
        fun assertTransportCompleteAndStopped() {
            check(stoppedProbeVerified && requested && closed && pendingTask == null && pendingTasks.get() == 0)
            check(realFinalCount.get() == 1 && fixtureMatched.get() == true && recognizedSha.get() != null)
            check(stopCount.get() == 1 && releaseCount.get() == 1)
            check(blockedTasks.get() == (if (paced) 1 else 0))
            val finalAt = checkNotNull(realFinalAtNs.get())
            val stoppedAt = checkNotNull(stoppedAtNs)
            val releasedAt = checkNotNull(releasedAtNs)
            check(finalAt <= stoppedAt && stoppedAt <= releasedAt)
            // Compare with the observations captured when production first stopped this input.
            check(sendCount.get() == sendCallsAtStop && completeSendCount.get() == completeCallsAtStop)
            check(submittedBytes.get() == submittedBytesAtStop && submittedHash.get() == submittedShaAtStop)
            val submitted = synchronized(submittedAudio) { submittedAudio.toByteArray() }
            check(submitted.size == submittedBytes.get() && submitted.size in 1..pcm.size)
            check(submittedHash.get() == sha(submitted))
            // This comparison validates observed successful bytes; it does not manufacture their hash.
            check(submitted.contentEquals(pcm.copyOfRange(0, submitted.size)))
            val sent = synchronized(chunks) { chunks.toList() }
            check(sent.size == sendCount.get() && sent.size in 1..plannedChunks)
            var offset = 0
            sent.forEachIndexed { index, chunk ->
                val expectedSize = if (paced) minOf(3_200, pcm.size - offset) else pcm.size
                check(chunk.getInt("index") == index + 1 && chunk.getInt("offset") == offset)
                check(chunk.getInt("bytes") == expectedSize && chunk.getBoolean("returned"))
                check(offset + expectedSize <= submitted.size)
                check(chunk.getString("sha256") == sha(submitted.copyOfRange(offset, offset + expectedSize)))
                val entered = chunk.getLong("elapsed_enter_ns")
                val returned = chunk.getLong("elapsed_return_ns")
                check(entered <= returned && returned <= stoppedAt)
                offset += expectedSize
            }
            check(offset == submitted.size)
            if (paced) sent.zipWithNext().forEach { (before, after) ->
                check(after.getLong("elapsed_enter_ns") - before.getLong("elapsed_return_ns") >= 100_000_000L)
            }
            when (completionKind()) {
                "explicit_complete_audio_send" -> {
                    check(sent.size == plannedChunks && submitted.size == pcm.size)
                    check(pendingAtStop == 0 && cancelledTasks.get() == 0)
                    val completedAt = checkNotNull(completeAtNs)
                    check(sent.last().getLong("elapsed_return_ns") <= completedAt && completedAt <= finalAt)
                    if (paced) check(completedAt - sent.last().getLong("elapsed_return_ns") >= 100_000_000L)
                }
                "early_real_final" -> {
                    check(paced && completeAtNs == null && pendingAtStop == 1 && cancelledTasks.get() == 1)
                    // An asynchronous final can arrive during sendAudio; its return may follow
                    // the final, but no chunk may start after that final or finish after stop.
                    check(sent.last().getLong("elapsed_enter_ns") <= finalAt)
                    // All PCM may have been submitted while its final completeAudioSend task
                    // was still pending. This is still an early real final, not explicit EOS.
                }
                else -> error("No supported successful PCM completion boundary was observed.")
            }
        }
        fun summary(): JSONObject = JSONObject().put("fixture_id", fixture.step.id)
            .put("fixture_asset", fixture.step.asset).put("fixture_sha256", sha(pcm))
            .put("send_audio_calls", sendCount.get())
            .put("mode", transportMode).put("planned_chunks", plannedChunks)
            .put("minimum_schedule_interval_ms", if (paced) 100 else 0)
            .put("schedule_scope", "minimum scheduling interval; actual monotonic times are observed, not an exact 100ms performance claim")
            .put("submitted_bytes", submittedBytes.get()).put("submitted_sha256", submittedHash.get() ?: JSONObject.NULL)
            .put("chunks", synchronized(chunks) { JSONArray(chunks.toList()) })
            .put("completion_kind", completionKind())
            .put("real_final_elapsed_ns", realFinalAtNs.get() ?: JSONObject.NULL)
            .put("release_elapsed_ns", releasedAtNs ?: JSONObject.NULL)
            .put("release_timestamp_scope", "after successful SDK release return and its stt_release_return trace event")
            .put("submitted_hash_scope", "snapshot of bytes accumulated only after successful SDK sendAudio returns")
            .put("complete_elapsed_ns", completeAtNs ?: JSONObject.NULL).put("stop_elapsed_ns", stoppedAtNs ?: JSONObject.NULL)
            .put("scheduled_pending_at_stop", pendingAtStop ?: JSONObject.NULL)
            .put("send_audio_calls_at_stop", sendCallsAtStop ?: JSONObject.NULL)
            .put("complete_audio_send_calls_at_stop", completeCallsAtStop ?: JSONObject.NULL)
            .put("submitted_bytes_at_stop", submittedBytesAtStop ?: JSONObject.NULL)
            .put("submitted_sha256_at_stop", submittedShaAtStop ?: JSONObject.NULL)
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
            if (name != "stt_send_audio") trace.markFixture(fixture.step.id, "${name}_enter")
            try { body(); if (name != "stt_send_audio") trace.markFixture(fixture.step.id, "${name}_return") }
            catch (error: Throwable) {
                trace.failedFixture(fixture.step.id, name, error)
                // Even LinkageError must reach the adapter's Exception failure route. The
                // original class/frames are recorded safely; raw provider messages are not.
                throw IllegalStateException("Fixed PCM transport call failed.")
            }
        }
    }

    private class ObservedInput(private val actual: PleosSpeechInput, private val trace: Trace,
        private val beforeOpen: (Int) -> Unit) : SpeechCaptureInput by actual {
        val opens = AtomicInteger()
        val finals = AtomicInteger()
        val failures = AtomicInteger()
        private val tickets = mutableSetOf<CaptureTicket>()
        override fun open(ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
            onFinal: (CaptureTicket, String) -> Unit, onFailure: (CaptureTicket, SpeechInputFailure) -> Unit) {
            val index = opens.getAndIncrement()
            try {
                check(index in STEPS.indices && tickets.add(ticket)) { "Only six distinct ordered captures are permitted." }
                beforeOpen(index)
            } catch (error: Throwable) {
                trace.failed("input_invariant", error); failures.incrementAndGet()
                onFailure(ticket, SpeechInputFailure("Fixed PCM journey capture invariant failed.")); return
            }
            trace.markFixture(STEPS[index].id, "production_input_open")
            actual.open(ticket,
                { received, stage -> trace.markFixture(STEPS[index].id, "production_input_stage", stage.name); onStage(received, stage) },
                { received, text -> finals.incrementAndGet(); trace.markFixture(STEPS[index].id, "production_input_final"); onFinal(received, text) },
                { received, failure -> failures.incrementAndGet(); trace.markFixture(STEPS[index].id, "production_input_failure", failure.cause?.name); onFailure(received, failure) })
        }
        fun summary(): JSONObject = JSONObject().put("adapter", actual.javaClass.name)
            .put("opens", opens.get()).put("finals", finals.get()).put("failures", failures.get())
    }

    private class ObservedOutput(private val actual: SpeechOutput, private val trace: Trace,
        private val beforeSpeak: (Int) -> Unit) : SpeechOutput by actual {
        val requests = AtomicInteger()
        val completions = AtomicInteger()
        val failures = AtomicInteger()
        private val starts = AtomicInteger()
        private val plans = mutableListOf<JSONObject>()
        override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
            failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) {
            val request = requests.incrementAndGet()
            try {
                check(request in 1..7 && completions.get() == request - 1) { "Seven sequential real output plans are required." }
                val texts = plan.segments.map { it.text }
                val required = plan.segments.all { it.required }
                val matched = texts == EXPECTED_PLANS[request - 1] && required
                synchronized(plans) { plans += JSONObject().put("request", request).put("matched", matched)
                    .put("segment_count", texts.size).put("required_all_true", required)
                    .put("plan_text_sha256", sha(JSONArray(texts).toString().toByteArray(Charsets.UTF_8)))
                    .put("expected_plan_text_sha256", sha(JSONArray(EXPECTED_PLANS[request - 1]).toString().toByteArray(Charsets.UTF_8))) }
                check(matched) { "Authored plan differs from the independent journey expectation." }
                beforeSpeak(request - 1)
            } catch (error: Throwable) {
                trace.failed("output_invariant", error); failures.incrementAndGet()
                failure(SpeechOutputFailure("Fixed PCM journey output invariant failed.")); return
            }
            trace.mark("output_request", request.toString(), turn = request - 1)
            val terminal = java.util.concurrent.atomic.AtomicBoolean()
            actual.speak(plan, generation, {
                if (terminal.compareAndSet(false, true)) {
                    completions.incrementAndGet(); trace.mark("output_completed", request.toString(), turn = request - 1); complete()
                } else trace.failed("duplicate_output_terminal", IllegalStateException("Duplicate real output terminal callback."))
            }, {
                if (terminal.compareAndSet(false, true)) {
                    failures.incrementAndGet(); trace.mark("output_failed", it.cause.name, turn = request - 1); failure(it)
                } else trace.failed("duplicate_output_terminal", IllegalStateException("Duplicate real output terminal callback."))
            }, {
                starts.incrementAndGet(); trace.mark("output_started", request.toString(), turn = request - 1); started()
            })
        }
        fun summary(): JSONObject = JSONObject().put("adapter", actual.javaClass.name)
            .put("requests", requests.get()).put("completions", completions.get()).put("starts", starts.get()).put("failures", failures.get())
            .put("plans", synchronized(plans) { JSONArray(plans.toList()) })
    }

    private class Trace {
        private val events = mutableListOf<JSONObject>()
        private val failures = mutableListOf<JSONObject>()
        @Volatile var dropped = 0
            private set
        @Synchronized fun mark(name: String, code: String? = null, fixtureId: String? = null, turn: Int? = null) {
            if (events.size >= 1_024) { dropped++; return }
            events += JSONObject().put("name", name).put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
                .put("main_thread", Looper.myLooper() == Looper.getMainLooper()).put("code", code ?: JSONObject.NULL)
                .put("fixture_id", fixtureId ?: JSONObject.NULL).put("turn", turn ?: JSONObject.NULL)
        }
        @Synchronized fun snapshot(): JSONArray = JSONArray(events.toList())
        @Synchronized fun failureSnapshot(): JSONArray = JSONArray(failures.toList())
        @Synchronized fun failed(boundary: String, error: Throwable, fixtureId: String? = null) {
            mark("${boundary}_failed", error.javaClass.name, fixtureId)
            if (failures.size < 8) failures += JSONObject().put("boundary", boundary)
                .put("fixture_id", fixtureId ?: JSONObject.NULL).put("diagnostic", safeThrowable(error))
        }
        fun markFixture(id: String, name: String, code: String? = null) = mark(name, code, id)
        fun failedFixture(id: String, boundary: String, error: Throwable) = failed(boundary, error, id)
        @Synchronized fun count(name: String): Int = events.count { it.getString("name") == name }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "pleos_journey_" + UUID.randomUUID()
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

    private fun assertProgress(snapshot: Snapshot, count: Int) {
        check(count in 0..6)
        val game = snapshot.game
        assertEquals(DEFAULT_ADVENTURE_ID, game.getString("pack_id")); assertEquals("0.12.0", game.getString("pack_version"))
        assertEquals(SCENES[count], game.getString("scene_id")); assertEquals(count, game.getInt("turn_index"))
        assertEquals(count == 6, game.getBoolean("finished"))
        val history = game.getJSONArray("history"); assertEquals(count, history.length())
        for (index in 0 until count) {
            assertEquals(SCENES[index], history.getJSONObject(index).getString("scene_id"))
            assertEquals(STEPS[index].action, history.getJSONObject(index).getString("action_id"))
            assertEquals(SCORES[index], history.getJSONObject(index).getInt("score_delta"))
        }
        val scores = game.getJSONObject("score_by_player")
        assertEquals(1, scores.length()); assertEquals(SCORES.take(count).sum(), scores.getInt(scores.keys().next()))
        if (count >= 1) {
            assertEquals("RIVER", game.getJSONObject("flags").getString("route"))
            assertEquals("WORKSHOP", game.getJSONObject("flags").getString("egg_location"))
        }
        if (count >= 2) assertEquals("FOLLOW_TRACKS", game.getJSONObject("flags").getString("evidence_method"))
        if (count >= 4) {
            assertEquals("MAGNIFIER", game.getJSONObject("flags").getString("tool_used"))
            assertEquals(0, game.getJSONObject("resources").getInt("tool_tokens"))
        }
        val clues = game.getJSONArray("clues")
        assertEquals(CLUES.take(minOf(count, 5)).toSet(), (0 until clues.length()).map { clues.getString(it) }.toSet())
        if (count == 6) assertEquals("FOUND_TOGETHER", game.getString("ending")) else assertTrue(game.isNull("ending"))
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
        val bytes = evidence.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 256 * 1024) { "Bounded PCM evidence exceeded its limit." }
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("pleos-pcm-journey"))
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "$runId.json")
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
        check(file.readBytes().contentEquals(bytes))
        instrumentation.sendStatus(2, Bundle().apply {
            putString("pleos_journey_run_id", runId)
            putString("pleos_journey_status", evidence.getString("status"))
            putInt("pleos_journey_revision", evidence.getInt("revision"))
            putInt("pleos_journey_json_bytes", bytes.size)
            putString("pleos_journey_json_sha256", sha(bytes))
            putString("pleos_journey_json_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            putString("pleos_journey_evidence_path", file.absolutePath)
        })
    }
    private companion object {
        const val MANIFEST = "pleos-pcm/journey-v1/manifest.json"
        const val MAX_PCM_BYTES = 320_000
        val STEPS = listOf(
            Step("river", "pleos-pcm/journey-v1/01-river.pcm", "물가로 갈래", "RIVER"),
            Step("tracks", "pleos-pcm/journey-v1/02-tracks.pcm", "바퀴 자국을 따라갈래", "FOLLOW_TRACKS"),
            Step("leaves", "pleos-pcm/journey-v1/03-leaves.pcm", "나뭇잎이야", "LEAVES"),
            Step("magnifier", "pleos-pcm/journey-v1/04-magnifier.pcm", "돋보기로 볼래", "MAGNIFIER"),
            Step("triceratops", "pleos-pcm/journey-v1/05-triceratops.pcm", "트리케라톱스야", "TRICERATOPS"),
            Step("workshop", "pleos-pcm/journey-v1/06-workshop.pcm", "공작실로 갈래", "WORKSHOP"),
        )
        val SCENES = listOf("choose_path", "river_evidence", "diet", "tool", "identity", "deduction", "ending_found")
        val SCORES = listOf(0, 1, 2, 1, 2, 2)
        val CLUES = listOf("RIVER_ROUTE", "BLUE_TRIANGLE", "TRICERATOPS_PLANT_EATER", "THREE_HORNS_PICTURE", "MATCHED_ROBOT")
        // Independent authored 0.12.0 expectations; never taken from the output plan under test.
        val EXPECTED_PLANS = listOf(
            listOf("오누랑 상상 속 공룡 연구소에 가자. 공룡 알 모형이 없어졌어. 물가에서 찾을까, 숲에서 찾을까?"),
            listOf("물가에 바퀴 자국이 나 있어. 오누도 옆에서 흔적을 찾아볼게.",
                "물가에 바퀴 자국과 운반 상자가 있네. 자국을 따라갈까, 상자 표지를 볼까?"),
            listOf("자국 끝에 파란 세모가 있어. 공작실 표시야. 옆에 로봇의 노트도 떨어져 있네.",
                "노트에는 트리케라톱스가 적혀 있어. 먹이 표시를 보고 로봇을 찾자. 나뭇잎일까, 생선일까?"),
            listOf("나뭇잎 표시를 찾았어. 트리케라톱스는 식물을 먹었지. 이 표시의 로봇을 살펴보자.",
                "나뭇잎 표시의 로봇이 두 대네. 공룡 그림은 서로 달라. 돋보기로 볼까, 듣기 장치로 설명을 들을까?"),
            listOf("오누가 돋보기를 잡았어.",
                "돋보기로 로봇의 공룡 그림을 봤어. 얼굴에 뿔이 세 개야. 이 그림은 트리케라톱스일까, 스테고사우루스일까?"),
            listOf("트리케라톱스 그림의 로봇이 반짝였어. 알 모형을 옮긴 로봇을 찾았네.",
                "바퀴 자국에서 찾은 건 공작실 표시였어. 로봇과 온실로 갈까, 공작실로 갈까?"),
            listOf("공작실 문이 열렸어. 로봇 옆 상자에 알 모형이 있네.",
                "처음엔 물가에서 바퀴 자국을 봤어. 돋보기로 공룡 그림을 크게 봤지. 로봇이 알 모형을 전시대에 돌려놨어. 우리 함께 찾았네."),
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
