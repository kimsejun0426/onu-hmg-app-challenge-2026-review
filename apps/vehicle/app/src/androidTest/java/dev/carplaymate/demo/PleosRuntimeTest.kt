package dev.carplaymate.demo

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit, real Pleos output integration. No fake engine, input request, microphone, or PCM input.
 * Completion is an SDK/app callback observation, not a measurement of audible speech or stop latency.
 */
@RunWith(AndroidJUnit4::class)
class PleosRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Suppress("DEPRECATION")
    @Test fun actualPleosProviderCompletesKoreanOnceAndStopAndRefreshDoNotCompleteAnotherPlan() {
        assumeTrue(
            "Enable pleosRuntimeTests=true explicitly on the prepared Pleos emulator/device.",
            InstrumentationRegistry.getArguments().getString("pleosRuntimeTests") == "true",
        )
        val context = instrumentation.targetContext
        val runId = UUID.randomUUID().toString()
        val evidence = JSONObject()
            .put("schema_version", 1)
            .put("run_id", runId)
            .put("started_at", Instant.now().toString())
            .put("app_package", context.packageName)
            .put("android_api", Build.VERSION.SDK_INT)
            .put("actual_pleos_service", false)
            .put("physical_audio_measured", false)
            .put("physical_microphone_measured", false)
            .put("stt_requested_by_test", false)
            .put("scope", "production_output_provider_probe_not_vehicle_gate_validation")
            .put("observations", JSONArray())
        var stage = "preflight"
        var activity: ActivityScenario<MainActivity>? = null
        var output: SpeechOutput? = null
        val failureCause = AtomicReference<RecoveryCause?>()
        val completions = AtomicInteger()
        val interruptions = AtomicInteger()
        val completed = CountDownLatch(1)
        try {
            evidence.put("app_apk", apkIdentity(File(context.applicationInfo.sourceDir)))
                .put("test_apk", apkIdentity(File(instrumentation.context.applicationInfo.sourceDir)))
            assertTrue("Pleos runtime integration requires the enabled demo/release target.", BuildConfig.PLEOS_ENABLED)
            val pm = context.packageManager
            val installed = try {
                pm.getPackageInfo(PleosSpeechRuntime.PACKAGE, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                throw AssertionError("Required Pleos CAAS package is not installed; no Android TTS fallback is allowed.")
            }
            val appInfo = pm.getApplicationInfo(PleosSpeechRuntime.PACKAGE, 0)
            assertTrue("Pleos CAAS application must be enabled.", appInfo.enabled)
            val services = pm.queryIntentServices(Intent(PleosSpeechRuntime.ACTION)
                .setPackage(PleosSpeechRuntime.PACKAGE), 0)
                .mapNotNull { it.serviceInfo }
                .filter { it.enabled && it.exported && it.applicationInfo.enabled }
            assertTrue("No enabled, exported Pleos speech service resolves the production capability action.", services.isNotEmpty())
            assertEquals("Grant the target app's actual Pleos TTS permission before this opt-in test.",
                PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(PleosSpeechRuntime.TTS_PERMISSION))
            val unavailable = PleosSpeechRuntime.unavailableFailure(context, PleosSpeechRuntime.TTS_PERMISSION)
            assertNull("Production Pleos output capability failed: ${unavailable?.cause}", unavailable)
            val providers = SpeechProviders(context)
            assertTrue("The production provider must select Pleos, not Android TTS.", providers.usesPleos)
            evidence.put("service_package", installed.packageName)
                .put("service_version_name", installed.versionName ?: JSONObject.NULL)
                .put("service_version_code", installed.longVersionCode)
                .put("resolved_services", JSONArray(services.map { "${it.packageName}/${it.name}" }.sorted()))
                .put("tts_permission", PleosSpeechRuntime.TTS_PERMISSION)
                .put("tts_permission_granted", true)
                .put("capability_passed", true)
            observation(evidence, stage, completions, interruptions)
            save(evidence, stage, "running", runId)

            stage = "foreground"
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            activity = scenario
            val model = AtomicReference<DeveloperViewModel>()
            scenario.onActivity { model.set(ViewModelProvider(it)[DeveloperViewModel::class.java]) }
            awaitCondition(20_000) { main { !model.get().ui.value.busy } }
            assertEquals("Audio focus requires the actual foreground Activity.", Lifecycle.State.RESUMED, scenario.state)
            val foreground = main { model.get().ui.value }
            // Record real UXR without treating a direct SDK probe as validation of the app's vehicle gate.
            evidence.put("vehicle_access", foreground.vehicleAccess.name)
                .put("foreground_state", scenario.state.name)
                .put("production_ui_speech_ready_at_probe", foreground.speechReady)
                .put("production_ui_speech_problem_at_probe", foreground.speechProblem?.name ?: JSONObject.NULL)
            assertFalse("The foreground app did not finish loading its local state.", foreground.busy)
            assertTrue("Opening the app must remain quiet before an explicit adventure.", foreground.quiet)
            assertFalse("This output-only test must not activate input.", foreground.microphoneListening)
            output = main {
                providers.output {
                    interruptions.incrementAndGet()
                    failureCause.compareAndSet(null, RecoveryCause.AUDIO_FOCUS)
                    completed.countDown()
                }
            }
            assertTrue("Production provider did not return PleosSpeechOutput.", output is PleosSpeechOutput)
            val actual = checkNotNull(output)
            evidence.put("output_provider", actual.javaClass.name)

            stage = "initial-ready"
            requireReady(actual)
            // Reproduce the actual settings/pause boundary while a new, silent SDK init is pending.
            stage = "refresh-stop-ready"
            main { actual.refresh(); actual.stop() }
            requireReady(actual)
            observeNoCallbacks(800, completions, interruptions, failureCause, expectedCompletions = 0)
            observation(evidence, stage, completions, interruptions).put("observation_ms", 800)
            save(evidence, stage, "running", runId)

            stage = "korean-completion"
            val greeting = SpeechPlan(UUID.randomUUID().toString(), "pleos-runtime-turn", 1,
                listOf(SpeechSegment("greeting", "안녕. 나는 오누야. 함께 모험을 떠나 볼까?", true)))
            val started = SystemClock.elapsedRealtime()
            val callbackAt = AtomicLong()
            main {
                actual.speak(greeting, 1, {
                    callbackAt.set(SystemClock.elapsedRealtime())
                    completions.incrementAndGet()
                    completed.countDown()
                }, {
                    failureCause.compareAndSet(null, it.cause)
                    completed.countDown()
                })
            }
            assertTrue("Actual Pleos output produced no terminal callback within 45 seconds.",
                completed.await(45, TimeUnit.SECONDS))
            assertNull("Actual Pleos output failed: ${failureCause.get()}", failureCause.get())
            assertEquals("The actual Korean request must complete exactly once.", 1, completions.get())
            observeNoCallbacks(800, completions, interruptions, failureCause, expectedCompletions = 1)
            evidence.put("actual_pleos_service", true)
            observation(evidence, stage, completions, interruptions)
                .put("callback_elapsed_ms", callbackAt.get() - started)
                .put("request_language", "ko-KR")
            save(evidence, stage, "running", runId)

            stage = "request-stop"
            val next = greeting.copy(playbackId = UUID.randomUUID().toString(), segments = listOf(
                SpeechSegment("cancel", "잠깐 쉬고 싶을 때는 함께 조용히 기다릴게. ".repeat(8), true)))
            main {
                actual.speak(next, 2, { completions.incrementAndGet() }, { failureCause.compareAndSet(null, it.cause) })
                actual.stop()
            }
            observeNoCallbacks(1_500, completions, interruptions, failureCause, expectedCompletions = 1)
            observation(evidence, stage, completions, interruptions)
                .put("observation_ms", 1_500)
                .put("stop_boundary", "immediately_after_request_no_playback_start_observer")
            save(evidence, stage, "running", runId)

            stage = "refresh-without-plan"
            main { actual.refresh() }
            requireReady(actual)
            observeNoCallbacks(1_500, completions, interruptions, failureCause, expectedCompletions = 1)
            observation(evidence, stage, completions, interruptions)
                .put("observation_ms", 1_500)
                .put("evidence_scope", "prepared_without_plan_no_extra_app_terminal_callback")
            val finalUi = main { model.get().ui.value }
            evidence.put("production_ui_speech_ready_after_probe", finalUi.speechReady)
                .put("production_ui_speech_problem_after_probe", finalUi.speechProblem?.name ?: JSONObject.NULL)
                .put("vehicle_access_after_probe", finalUi.vehicleAccess.name)
            assertTrue("The foreground app must remain quiet during this separate output probe.", finalUi.quiet)
            assertFalse("No microphone input is authorized by this output test.", finalUi.microphoneListening)
            assertTrue("The actual app's output preparation also must settle successfully; cause=${finalUi.speechProblem}.",
                finalUi.speechReady)
            assertNull(failureCause.get())
            stage = "cleanup"
            main { actual.shutdown() }
            output = null
            scenario.close()
            activity = null
            assertEquals(1, completions.get())
            assertEquals(0, interruptions.get())
            assertNull(failureCause.get())
            stage = "complete"
            evidence.put("completed_at", Instant.now().toString())
            save(evidence, stage, "passed", runId)
        } catch (error: Throwable) {
            evidence.put("failure_class", error.javaClass.name)
                .put("output_failure_cause", failureCause.get()?.name ?: JSONObject.NULL)
                .put("current_availability_cause", output?.availabilityFailure?.cause?.name ?: JSONObject.NULL)
            // No provider exception message, spoken text, token, or microphone data is persisted.
            runCatching { save(evidence, stage, "failed", runId) }
            throw error
        } finally {
            try { main { output?.shutdown() } } finally { activity?.close() }
        }
    }

    private fun requireReady(output: SpeechOutput) {
        awaitCondition(15_000) { output.readyState.value }
        assertTrue("Pleos initialization did not become ready; cause=${output.availabilityFailure?.cause}.", output.readyState.value)
        assertNull(output.availabilityFailure)
    }

    private fun awaitCondition(milliseconds: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + milliseconds
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
    }

    private fun observeNoCallbacks(milliseconds: Long, completions: AtomicInteger, interruptions: AtomicInteger,
        failure: AtomicReference<RecoveryCause?>, expectedCompletions: Int) {
        val end = SystemClock.elapsedRealtime() + milliseconds
        do {
            assertEquals("Unexpected extra/missing app completion callback.", expectedCompletions, completions.get())
            assertEquals("Unexpected actual audio focus interruption.", 0, interruptions.get())
            assertNull("Actual Pleos output failed: ${failure.get()}", failure.get())
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < end)
        main { /* Finish any queued adapter callback before checking the bounded observation. */ }
        assertEquals(expectedCompletions, completions.get())
        assertEquals(0, interruptions.get())
        assertNull(failure.get())
    }

    private fun observation(evidence: JSONObject, stage: String, completions: AtomicInteger,
        interruptions: AtomicInteger): JSONObject = JSONObject()
        .put("stage", stage).put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
        .put("app_completion_count", completions.get()).put("focus_interruption_count", interruptions.get())
        .also { evidence.getJSONArray("observations").put(it) }

    private fun apkIdentity(file: File): JSONObject {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return JSONObject().put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
            .put("bytes", file.length())
    }

    private fun save(evidence: JSONObject, stage: String, status: String, runId: String) {
        evidence.put("stage", stage).put("status", status)
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("pleos-runtime"))
        check(directory.isDirectory || directory.mkdirs())
        for (file in listOf(File(directory, "$runId.json"), File(directory, "latest.json"))) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(evidence.toString(2).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("pleos_runtime_run_id", runId)
            putString("pleos_runtime_stage", stage)
            putString("pleos_runtime_status", status)
            putString("pleos_runtime_evidence_path", File(directory, "$runId.json").absolutePath)
        })
    }

    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable { action() })
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
