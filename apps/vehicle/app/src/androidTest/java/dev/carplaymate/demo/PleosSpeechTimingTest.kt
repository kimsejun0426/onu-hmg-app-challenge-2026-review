package dev.carplaymate.demo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.time.Instant
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in timing of the production adapter, real ON_DEVICE SDK and real production audio focus.
 * The injected engine only observes/delegates. This does not measure physical sound, microphone
 * activity, cold process/model startup, or the ViewModel's complete adventure path.
 */
@RunWith(AndroidJUnit4::class)
class PleosSpeechTimingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var deadlineMs = Long.MAX_VALUE

    @Suppress("DEPRECATION")
    @Test fun threeFixedGreetingsMeasureObservableProductionBoundaries() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Enable pleosSpeechTimingTests=true explicitly on the prepared Pleos runtime.",
            arguments.getString("pleosSpeechTimingTests") == "true")
        deadlineMs = SystemClock.elapsedRealtime() + 170_000L
        val context = instrumentation.targetContext
        val runId = UUID.randomUUID().toString()
        val traces = mutableListOf(Trace(0, "silent_preparation"))
        val currentTrace = AtomicReference(traces.single())
        val completions = AtomicInteger()
        val interruptions = AtomicInteger()
        val failure = AtomicReference<RecoveryCause?>()
        val terminal = AtomicReference<CountDownLatch?>()
        val ownedOutput = AtomicReference<PleosSpeechOutput?>()
        val evidence = JSONObject()
            .put("schema_version", 2).put("run_id", runId)
            .put("started_at", Instant.now().toString())
            .put("app_package", context.packageName).put("android_api", Build.VERSION.SDK_INT)
            .put("clock", "SystemClock.elapsedRealtimeNanos")
            .put("scope", "production_adapter_observing_retained_production_engine_and_focus")
            .put("engine_implementation", "dev.carplaymate.demo.OnDevicePleosTts")
            .put("sdk_call_boundary_scope", "retained production PleosTtsEngine entry/return surrounding the real SDK; no direct SDK method calls from test")
            .put("sdk_constructor_scope", "production engine constructor including SDK via reflection; constructor lookup excluded")
            .put("phase_timing_coverage_policy", "sdk_and_production_started_required")
            .put("missing_start_interpretation", "missing SDK or production started callback fails this revision; no physical first-sound measurement")
            .put("physical_audio_measured", false).put("physical_microphone_measured", false)
            .put("stt_requested_by_test", false).put("raw_text_recorded", false)
            .put("cold_service_start_measured", false)
            .put("actual_pleos_service", false).put("sdk_mode", "ON_DEVICE")
            .put("phrase_id", "greeting_v1").put("request_language", "ko-KR")
            .put("requested_greeting_count", 3).put("post_completion_observation_ms", 500)
            .put("experiment_deadline_ms", 170_000)
            .put("main_processing_boundaries", JSONObject()
                .put("initialization", "first setLocale entry after initialization callback; includes dispatch and adapter checks")
                .put("completion", "first removeListener entry after onDone; includes dispatch, checks and timeout removal"))
            .put("on_start_scope", "SDK playback-start callback receipt; not measured first audible sound")
        var stage = "preflight"
        var scenario: ActivityScenario<MainActivity>? = null
        var output: PleosSpeechOutput? = null
        var revision = 0

        fun persist(status: String) {
            val requested = traces.drop(1)
            val allStarts = requested.size == 3 && requested.all { it.count("on_start_receipt") == 1 && it.count("app_started") == 1 }
            evidence.put("stage", stage).put("status", status).put("revision", ++revision)
                .put("app_completion_count", completions.get())
                .put("focus_interruption_count", interruptions.get())
                .put("all_start_boundaries_observed", allStarts)
                .put("phase_timing_coverage", when {
                    requested.isEmpty() -> "pending"
                    allStarts -> "all_requested_start_boundaries_observed"
                    else -> "incomplete_required_start_boundary"
                })
                .put("traces", JSONArray(traces.map { it.snapshot() }))
            save(evidence, runId)
        }

        try {
            val expectedAppSha = arguments.getString("pleosTimingExpectedAppSha256")
            assertTrue("Pass the independently pinned target APK SHA-256 as pleosTimingExpectedAppSha256.",
                expectedAppSha != null && expectedAppSha.matches(Regex("[0-9a-f]{64}")))
            val appIdentity = apkIdentity(File(context.applicationInfo.sourceDir))
            evidence.put("expected_app_sha256", expectedAppSha)
                .put("app_apk", appIdentity)
                .put("test_apk", apkIdentity(File(instrumentation.context.applicationInfo.sourceDir)))
            assertEquals("The installed target APK differs from the pinned measurement target.",
                expectedAppSha, appIdentity.getString("sha256"))
            assertTrue("The target must enable actual Pleos integration.", BuildConfig.PLEOS_ENABLED)
            val pm = context.packageManager
            val installed = pm.getPackageInfo(PleosSpeechRuntime.PACKAGE, 0)
            val services = pm.queryIntentServices(Intent(PleosSpeechRuntime.ACTION)
                .setPackage(PleosSpeechRuntime.PACKAGE), 0).mapNotNull { it.serviceInfo }
                .filter { it.enabled && it.exported && it.applicationInfo.enabled }
            assertTrue("An enabled exported real Pleos speech service must resolve.", services.isNotEmpty())
            assertEquals("The actual target app must have the Pleos output permission.",
                PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(PleosSpeechRuntime.TTS_PERMISSION))
            assertNull("The real production output capability must pass.",
                PleosSpeechRuntime.unavailableFailure(context, PleosSpeechRuntime.TTS_PERMISSION))
            assertTrue("The actual provider must select Pleos without Android TTS fallback.", SpeechProviders(context).usesPleos)
            evidence.put("service_package", installed.packageName)
                .put("service_version_name", installed.versionName ?: JSONObject.NULL)
                .put("service_version_code", installed.longVersionCode)
                .put("resolved_services", JSONArray(services.map { "${it.packageName}/${it.name}" }.sorted()))
                .put("tts_permission_granted", true).put("capability_passed", true)

            stage = "foreground"
            val activity = ActivityScenario.launch(MainActivity::class.java)
            scenario = activity
            val model = AtomicReference<DeveloperViewModel>()
            activity.onActivity { model.set(ViewModelProvider(it)[DeveloperViewModel::class.java]) }
            awaitCondition(20_000) { main { !model.get().ui.value.busy && model.get().ui.value.speechReady } }
            assertEquals("A real resumed Activity is required for audio focus.", Lifecycle.State.RESUMED, activity.state)
            val initialUi = main { model.get().ui.value }
            evidence.put("vehicle_access", initialUi.vehicleAccess.name)
                .put("production_ui_speech_ready_at_probe", initialUi.speechReady)
                .put("service_preparation_scope", "foreground app prepared before the separately observed adapter")
            assertTrue("App preparation did not finish successfully.", !initialUi.busy && initialUi.speechReady)
            assertTrue("Opening the app must remain quiet.", initialUi.quiet)
            assertFalse("The output-only experiment must not activate input.", initialUi.microphoneListening)

            stage = "silent-preparation"
            output = main {
                // This is our own retained class, not a hidden Android/SDK API. No fake fallback.
                val focusClass = Class.forName("dev.carplaymate.demo.AndroidPleosPlaybackFocus")
                val constructor = focusClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
                val realFocus = constructor.newInstance(context.applicationContext) as PleosPlaybackFocus
                evidence.put("focus_implementation", realFocus.javaClass.name)
                currentTrace.get().mark("adapter_construct_enter")
                PleosSpeechOutput(
                    engineFactory = { ObservedProductionEngine(context.applicationContext, currentTrace.get()) },
                    capability = { PleosSpeechRuntime.unavailableReason(context, PleosSpeechRuntime.TTS_PERMISSION) },
                    focus = ObservedFocus(realFocus, currentTrace),
                    onInterrupted = {
                        currentTrace.get().mark("app_focus_interrupted")
                        interruptions.incrementAndGet()
                        failure.compareAndSet(null, RecoveryCause.AUDIO_FOCUS)
                        terminal.get()?.countDown()
                    },
                    typedCapability = {
                        PleosSpeechRuntime.unavailableFailure(context, PleosSpeechRuntime.TTS_PERMISSION)
                            ?.let { SpeechOutputFailure(it.message, it.cause) }
                    },
                    // Deliberately use the unchanged production MainThreadCaptureTimer.
                ).also {
                    ownedOutput.set(it)
                    currentTrace.get().mark("adapter_construct_return")
                }
            }
            val actual = checkNotNull(output)
            evidence.put("output_adapter", actual.javaClass.name)
            awaitCondition(15_000) { actual.readyState.value }
            main { /* Barrier: readyState becomes true before silent SDK disposal completes. */ }
            assertTrue("Observed production initialization did not become ready.", actual.readyState.value)
            assertNull(actual.availabilityFailure)
            traces.single().assertSilentPreparation()
            persist("running")

            for (request in 1..3) {
                stage = "greeting-$request"
                val trace = Trace(request, "greeting_v1")
                traces.add(trace)
                val done = CountDownLatch(1)
                terminal.set(done)
                currentTrace.set(trace)
                // Same fixed authored text on all three requests; never persisted in evidence.
                val plan = SpeechPlan(UUID.randomUUID().toString(), "pleos-timing-turn", request.toLong(),
                    listOf(SpeechSegment("greeting", "안녕. 나는 오누야. 함께 모험을 떠나 볼까?", true)))
                trace.mark("request_enqueued")
                main {
                    trace.mark("app_speak_enter")
                    actual.speak(plan, request.toLong(), {
                        trace.mark("app_complete")
                        completions.incrementAndGet()
                        done.countDown()
                    }, {
                        trace.mark("app_failure", it.cause.name)
                        failure.compareAndSet(null, it.cause)
                        done.countDown()
                    }, started = { trace.mark("app_started") })
                    trace.mark("app_speak_return")
                }
                assertTrue("The actual request produced no terminal callback inside the bounded wait.",
                    done.await(remaining(40_000), TimeUnit.MILLISECONDS))
                assertNull("Actual Pleos request failed; see typed cause in evidence.", failure.get())
                observeCompletion(500, request, completions, interruptions, failure)
                trace.assertCompleted()
                assertEquals("Exactly one SDK instance is required for each production request.", request + 1,
                    traces.sumOf { it.count("sdk_construct_enter") })
                assertEquals("The actual Activity must stay resumed.", Lifecycle.State.RESUMED, activity.state)
                val ui = main { model.get().ui.value }
                assertTrue("The separate output probe must not start an adventure.", ui.quiet)
                assertFalse("No input is authorized by this experiment.", ui.microphoneListening)
                persist("running")
            }
            terminal.set(null)
            stage = "cleanup"
            main { actual.shutdown() }
            ownedOutput.set(null)
            output = null
            activity.close()
            scenario = null
            assertEquals(3, completions.get())
            assertEquals(0, interruptions.get())
            // Recheck the recorded requests after cleanup: a late raw SDK callback from
            // an earlier request may have arrived while a later one was being measured.
            traces.drop(1).forEach { it.assertCompleted() }
            assertNull(failure.get())
            evidence.put("actual_pleos_service", true).put("completed_at", Instant.now().toString())
            stage = "complete"
            persist("passed")
        } catch (error: Throwable) {
            evidence.put("failure_class", error.javaClass.name)
                .put("failure_diagnostic", safeThrowable(error))
                .put("output_failure_cause", failure.get()?.name ?: JSONObject.NULL)
                .put("current_availability_cause", output?.availabilityFailure?.cause?.name ?: JSONObject.NULL)
            runCatching { persist("failed") }
            // Even a provider exception must not expose its raw message through JUnit/log output.
            throw AssertionError("Pleos timing experiment failed at $stage; inspect the safe typed evidence.")
        } finally {
            // Read ownership on main, after a possibly slow constructor, rather than capturing null
            // before it returns. A timed-out pending request is cancelled by main() below.
            val cleanup = FutureTask(Callable { ownedOutput.getAndSet(null)?.shutdown() })
            Handler(Looper.getMainLooper()).post(cleanup)
            runCatching { cleanup.get(5, TimeUnit.SECONDS) }
            runCatching { scenario?.close() }
        }
    }

    private class Trace(private val index: Int, private val role: String) {
        private data class Event(val name: String, val time: Long, val main: Boolean, val code: String?)
        private val events = mutableListOf<Event>()
        private val failures = mutableListOf<JSONObject>()
        private var dropped = 0

        @Synchronized fun mark(name: String, code: String? = null) {
            if (events.size >= 128) { dropped++; return }
            events.add(Event(name, SystemClock.elapsedRealtimeNanos(), Looper.myLooper() == Looper.getMainLooper(), code))
        }
        @Synchronized fun count(name: String): Int = events.count { it.name == name }
        @Synchronized fun failedCall(boundary: String, error: Throwable) {
            if (failures.size < 4) failures.add(JSONObject().put("boundary", boundary)
                .put("elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos())
                .put("diagnostic", safeThrowable(error)))
        }
        @Synchronized private fun first(name: String): Long? = events.firstOrNull { it.name == name }?.time
        private fun duration(start: String, end: String): Any {
            val a = first(start) ?: return JSONObject.NULL
            val b = first(end) ?: return JSONObject.NULL
            return if (b >= a) (b - a) / 1_000_000.0 else JSONObject.NULL
        }
        @Synchronized fun snapshot(): JSONObject = JSONObject().put("index", index).put("role", role)
            .put("dropped_event_count", dropped)
            .put("start_observed", count("on_start_receipt") > 0)
            .put("ready_callback_observed", count("on_ready_receipt") > 0)
            .put("call_failures", JSONArray(failures))
            .put("events", JSONArray(events.map { e -> JSONObject().put("event", e.name)
                .put("elapsed_realtime_ns", e.time).put("main_thread", e.main)
                .apply { if (e.code != null) put("code", e.code) } }))
            .put("durations_ms", JSONObject()
                .put("test_enqueue_to_adapter_entry", duration("request_enqueued", "app_speak_enter"))
                .put("sdk_constructor", duration("sdk_construct_enter", "sdk_construct_return"))
                .put("initialize_call", duration("initialize_enter", "initialize_return"))
                .put("initialize_to_success_receipt", duration("initialize_enter", "initialize_success_receipt"))
                .put("init_receipt_to_main_set_locale_entry", duration("initialize_success_receipt", "set_locale_enter"))
                .put("focus_acquire", duration("focus_acquire_enter", "focus_acquire_return"))
                .put("sdk_speak_call", duration("sdk_speak_enter", "sdk_speak_return"))
                .put("sdk_speak_to_ready_receipt", duration("sdk_speak_enter", "on_ready_receipt"))
                .put("sdk_speak_to_start_receipt", duration("sdk_speak_enter", "on_start_receipt"))
                .put("start_receipt_to_app_started", duration("on_start_receipt", "app_started"))
                .put("start_to_done_receipt", duration("on_start_receipt", "on_done_receipt"))
                .put("done_receipt_to_main_remove_listener_entry", duration("on_done_receipt", "remove_listener_enter"))
                .put("sdk_release_call", duration("sdk_release_enter", "sdk_release_return"))
                .put("focus_release_call", duration("focus_release_enter", "focus_release_return"))
                .put("done_receipt_to_app_complete", duration("on_done_receipt", "app_complete"))
                .put("app_request_to_complete", duration("app_speak_enter", "app_complete")))

        fun assertSilentPreparation() {
            assertEquals(1, count("initialize_success_receipt"))
            assertEquals(1, count("sdk_release_return"))
            assertEquals(0, count("sdk_speak_enter"))
            assertEquals(0, count("focus_acquire_enter"))
        }
        @Synchronized fun assertCompleted() {
            assertEquals("Timing events must not overflow.", 0, dropped)
            assertTrue("Production engine calls must not throw.", failures.isEmpty())
            for (name in listOf("sdk_construct_enter", "initialize_success_receipt", "sdk_speak_enter",
                    "on_start_receipt", "app_started", "on_done_receipt", "sdk_release_return", "app_complete")) {
                assertEquals("Expected one $name event for request $index.", 1, count(name))
            }
            for (name in listOf("app_failure", "initialize_failure_receipt", "on_error_receipt", "on_stop_receipt")) {
                assertEquals("Unexpected $name event for request $index.", 0, count(name))
            }
            val requiredOrder = listOf("app_speak_enter", "sdk_construct_enter", "sdk_construct_return",
                "initialize_enter", "initialize_success_receipt", "set_locale_enter", "focus_acquire_enter",
                "focus_acquire_return", "sdk_speak_enter", "on_start_receipt", "on_done_receipt",
                "remove_listener_enter", "sdk_release_enter", "sdk_release_return", "focus_release_enter",
                "focus_release_return", "app_complete")
            val times = requiredOrder.map { checkNotNull(first(it)) }
            assertTrue("Required callback/adapter boundaries must be ordered.", times.zipWithNext().all { (a, b) -> a <= b })
            assertTrue("The production handling boundaries must run on main.",
                events.filter { it.name in listOf("set_locale_enter", "app_started", "remove_listener_enter", "app_complete") }.all { it.main })
            // 0.18 consumes playback start in production. Its optimized APK must preserve
            // both the SDK receipt and the guarded adapter callback; physical sound is separate.
            // Both SDK events may arrive before main drains either callback. Do not impose
            // app_started < raw onDone across those threads; only the dispatch causal edges.
            assertTrue("Production start must follow its SDK receipt and precede completion handling.",
                checkNotNull(first("app_started")) >= checkNotNull(first("on_start_receipt")) &&
                    checkNotNull(first("app_started")) <= checkNotNull(first("remove_listener_enter")))
            // onReady is also recorded if delivered; absence remains null in the relevant duration.
        }
    }

    /** Retained production engine preserves optimized SDK calls inside the pinned target APK. */
    private class ObservedProductionEngine(context: Context, private val trace: Trace) : PleosTtsEngine {
        private val engine: PleosTtsEngine
        private val listeners = IdentityHashMap<EventListener, EventListener>()
        init {
            val constructor = call("production_engine_lookup") {
                Class.forName("dev.carplaymate.demo.OnDevicePleosTts")
                    .getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            }
            trace.mark("sdk_construct_enter")
            engine = call("production_engine_constructor") {
                try { constructor.newInstance(context) as PleosTtsEngine }
                catch (error: InvocationTargetException) { throw error.targetException }
            }
            trace.mark("sdk_construct_return")
        }
        override fun initialize(listener: OnInitializeListener) {
            trace.mark("initialize_enter")
            call("initialize") { engine.initialize(object : OnInitializeListener {
                override fun onSuccess() { trace.mark("initialize_success_receipt"); listener.onSuccess() }
                override fun onFailure(errorType: ErrorType) {
                    trace.mark("initialize_failure_receipt", errorType.name); listener.onFailure(errorType)
                }
            }) }
            trace.mark("initialize_return")
        }
        override fun addListener(listener: EventListener) {
            trace.mark("add_listener_enter")
            val observed = object : EventListener {
                override fun onReady() { trace.mark("on_ready_receipt"); listener.onReady() }
                override fun onStart() { trace.mark("on_start_receipt"); listener.onStart() }
                override fun onDone() { trace.mark("on_done_receipt"); listener.onDone() }
                override fun onStop() { trace.mark("on_stop_receipt"); listener.onStop() }
                override fun onError(errorType: ErrorType) {
                    trace.mark("on_error_receipt", errorType.name); listener.onError(errorType)
                }
                override fun onUpdatedRms(rms: Double) = listener.onUpdatedRms(rms) // Never record samples/RMS.
            }
            listeners[listener] = observed
            call("add_listener") { engine.addListener(observed) }
            trace.mark("add_listener_return")
        }
        override fun removeListener(listener: EventListener) {
            trace.mark("remove_listener_enter")
            call("remove_listener") { engine.removeListener(listeners.remove(listener) ?: listener) }
            trace.mark("remove_listener_return")
        }
        override fun setLocale(locale: Locale) {
            trace.mark("set_locale_enter")
            call("set_locale") { engine.setLocale(locale) }
            trace.mark("set_locale_return")
        }
        override fun setAudioAttributes(attributes: AudioAttributes) {
            trace.mark("set_audio_attributes_enter")
            call("set_audio_attributes") { engine.setAudioAttributes(attributes) }
            trace.mark("set_audio_attributes_return")
        }
        override fun speak(text: String) {
            trace.mark("sdk_speak_enter")
            call("speak") { engine.speak(text) }
            trace.mark("sdk_speak_return")
        }
        override fun stop() { trace.mark("sdk_stop_enter"); call("stop") { engine.stop() }; trace.mark("sdk_stop_return") }
        override fun release() { trace.mark("sdk_release_enter"); call("release") { engine.release() }; trace.mark("sdk_release_return") }
        private inline fun <T> call(boundary: String, action: () -> T): T = try { action() }
        catch (error: Throwable) { trace.failedCall(boundary, error); throw error }
    }

    private class ObservedFocus(private val delegate: PleosPlaybackFocus, private val trace: AtomicReference<Trace>) : PleosPlaybackFocus {
        private var owner: Trace? = null
        override fun acquire(onLost: () -> Unit): Boolean {
            val current = trace.get()
            owner = current
            current.mark("focus_acquire_enter")
            val granted = delegate.acquire { current.mark("focus_loss_callback"); onLost() }
            current.mark("focus_acquire_return", if (granted) "GRANTED" else "DENIED")
            return granted
        }
        override fun release() {
            val previous = owner
            owner = null
            previous?.mark("focus_release_enter")
            delegate.release()
            previous?.mark("focus_release_return")
        }
    }

    private fun remaining(maximum: Long): Long {
        val left = deadlineMs - SystemClock.elapsedRealtime()
        check(left > 0) { "Bounded timing experiment deadline reached." }
        return minOf(left, maximum)
    }
    private fun awaitCondition(milliseconds: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + remaining(milliseconds)
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
    }
    private fun observeCompletion(milliseconds: Long, expected: Int, completed: AtomicInteger,
        interrupted: AtomicInteger, failure: AtomicReference<RecoveryCause?>) {
        val end = SystemClock.elapsedRealtime() + remaining(milliseconds)
        do {
            assertEquals(expected, completed.get())
            assertEquals(0, interrupted.get())
            assertNull(failure.get())
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < end)
        main { /* Drain queued production callbacks before checking the finite observation. */ }
        assertEquals(expected, completed.get())
        assertEquals(0, interrupted.get())
        assertNull(failure.get())
    }
    private fun apkIdentity(file: File): JSONObject {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return JSONObject().put("sha256", hex(digest.digest())).put("bytes", file.length())
    }
    private fun save(evidence: JSONObject, runId: String) {
        val bytes = evidence.toString(2).toByteArray(Charsets.UTF_8)
        check(bytes.size <= 128 * 1024) { "Timing evidence exceeds its bounded result size." }
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("pleos-speech-timing"))
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "$runId.json")
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
        check(file.readBytes().contentEquals(bytes)) { "Timing evidence read-back differs." }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("pleos_timing_run_id", runId)
            putString("pleos_timing_status", evidence.getString("status"))
            putInt("pleos_timing_revision", evidence.getInt("revision"))
            putInt("pleos_timing_json_bytes", bytes.size)
            putString("pleos_timing_json_sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            putString("pleos_timing_json_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            putString("pleos_timing_evidence_path", file.absolutePath)
        })
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        /** Classes and structured frames only: no message, toString(), paths or suppressed payloads. */
        fun safeThrowable(error: Throwable): JSONObject {
            val causes = JSONArray()
            val seen = IdentityHashMap<Throwable, Boolean>()
            var current: Throwable? = error
            while (current != null && causes.length() < 6 && seen.put(current, true) == null) {
                val frames = current.stackTrace
                causes.put(JSONObject().put("class", current.javaClass.name)
                    .put("frames", JSONArray(frames.take(8).map { frame -> JSONObject()
                        .put("class", frame.className).put("method", frame.methodName)
                        .put("line", frame.lineNumber).put("native", frame.isNativeMethod) }))
                    .put("omitted_frame_count", (frames.size - 8).coerceAtLeast(0)))
                current = current.cause
            }
            return JSONObject().put("causes", causes).put("cause_chain_truncated_or_cyclic", current != null)
        }
    }

    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable { action() })
        check(Handler(Looper.getMainLooper()).post(task))
        try { return task.get(remaining(45_000), TimeUnit.MILLISECONDS) }
        catch (error: Throwable) {
            // Do not allow a queued speak to start after its test has already timed out. An SDK call
            // already running on main is not interrupted; queued cleanup runs when it returns.
            task.cancel(false)
            throw error
        }
    }
}
