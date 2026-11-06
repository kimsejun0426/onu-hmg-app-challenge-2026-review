package dev.carplaymate.demo

import android.Manifest
import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated emulator only. Actual FGS promotion, but no speech/capture action is started. */
@RunWith(AndroidJUnit4::class)
class VoiceForegroundServiceTest {
    @Test fun privateForegroundPromotionDispatchesOnceAndReleasesAnUnusedSession(): Unit = runBlocking {
        fixture { scenario, model, host ->
            val promoted = CompletableDeferred<DeveloperUiState>()
            var calls = 0
            scenario.onActivity { activity ->
                val service = activity.packageManager.getServiceInfo(
                    ComponentName(activity, VoiceSessionService::class.java), 0)
                assertFalse(service.exported)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.foregroundServiceType)
                }
                model.useVoiceInput(true) // Select only. No start/resume, TTS or recognition.
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.voiceSessionAllowed)
                host.runExplicitVoiceAction {
                    calls++
                    promoted.complete(model.ui.value)
                }
                assertEquals(0, calls) // The service must promote before dispatching.
            }
            val during = withTimeout(8_000) { promoted.await() }
            assertTrue(during.voiceSessionAllowed)
            assertTrue(during.quiet)
            assertFalse(during.microphoneListening)
            assertNull(during.outputStage)
            assertNull(during.snapshot)
            withTimeout(8_000) { model.ui.first { !it.voiceSessionAllowed && !it.busy } }
            scenario.onActivity {
                assertEquals(1, calls)
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.microphoneListening)
            }
        }
    }

    @Test fun hiddenActivityCannotDispatchOrRestoreMicrophoneOwnership(): Unit = runBlocking {
        fixture { scenario, model, host ->
            scenario.onActivity { model.useVoiceInput(true) }
            scenario.moveToState(Lifecycle.State.STARTED)
            var calls = 0
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                host.runExplicitVoiceAction { calls++ }
                assertEquals(0, calls)
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.voiceSessionAllowed)
                assertFalse(model.ui.value.microphoneListening)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertEquals(0, calls)
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.voiceSessionAllowed)
            }
        }
    }

    @Test fun duplicateStartsBeforePromotionDispatchOnlyTheFirstAction(): Unit = runBlocking {
        fixture { scenario, model, host ->
            val promoted = CompletableDeferred<DeveloperUiState>()
            var firstCalls = 0
            var duplicateCalls = 0
            scenario.onActivity {
                model.useVoiceInput(true)
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.voiceSessionAllowed)
                assertFalse(model.ui.value.failure)
                // Both requests arrive before the main thread can receive Service.onStartCommand.
                host.runExplicitVoiceAction {
                    firstCalls++
                    promoted.complete(model.ui.value)
                }
                host.runExplicitVoiceAction { duplicateCalls++ }
                assertEquals(0, firstCalls)
                assertEquals(0, duplicateCalls)
            }
            val during = withTimeout(8_000) { promoted.await() }
            assertTrue(during.voiceSessionAllowed)
            assertTrue(during.quiet)
            assertFalse(during.failure)
            assertFalse(during.microphoneListening)
            assertNull(during.outputStage)
            assertNull(during.snapshot)
            // The callback does not start/resume an adventure: no capture or utterance is requested.
            withTimeout(8_000) { model.ui.first { !it.voiceSessionAllowed && !it.busy } }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals(1, firstCalls)
                assertEquals(0, duplicateCalls)
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.failure)
                assertNull(model.ui.value.recoveryCause)
                assertFalse(model.ui.value.microphoneListening)
                assertNull(model.ui.value.outputStage)
                assertNull(model.ui.value.snapshot)
            }
        }
    }

    @Test fun cancelledStartCannotStopANewerAuthorizedPromotion(): Unit = runBlocking {
        fixture { scenario, model, host ->
            val promoted = CompletableDeferred<DeveloperUiState>()
            var cancelledCalls = 0
            var latestCalls = 0
            scenario.onActivity {
                model.useVoiceInput(true)
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.failure)
                // Queue A, revoke it, then queue B before either Service START can be delivered.
                host.runExplicitVoiceAction { cancelledCalls++ }
                host.pause("USER")
                assertFalse(model.ui.value.voiceSessionAllowed)
                host.runExplicitVoiceAction {
                    latestCalls++
                    promoted.complete(model.ui.value)
                }
                assertEquals(0, cancelledCalls)
                assertEquals(0, latestCalls)
            }
            val during = withTimeout(8_000) { promoted.await() }
            assertTrue(during.voiceSessionAllowed)
            assertTrue(during.quiet)
            assertFalse(during.failure)
            assertFalse(during.microphoneListening)
            assertNull(during.outputStage)
            assertNull(during.snapshot)
            withTimeout(8_000) { model.ui.first { !it.voiceSessionAllowed && !it.busy } }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals(0, cancelledCalls)
                assertEquals(1, latestCalls)
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.failure)
                assertNull(model.ui.value.recoveryCause)
                assertFalse(model.ui.value.microphoneListening)
                assertNull(model.ui.value.outputStage)
                assertNull(model.ui.value.snapshot)
            }
        }
    }

    @Test fun alreadyPromotedServiceCanPauseAndDispatchOneFreshExplicitStart(): Unit = runBlocking {
        fixture { scenario, model, host ->
            val promotedAgain = CompletableDeferred<DeveloperUiState>()
            var originalCalls = 0
            var freshCalls = 0
            var duplicateCalls = 0
            scenario.onActivity {
                model.useVoiceInput(true)
                host.runExplicitVoiceAction {
                    originalCalls++
                    assertTrue(model.ui.value.voiceSessionAllowed)
                    // This runs inside the first real Service promotion, before onDestroy can run.
                    host.pause("USER")
                    assertFalse(model.ui.value.voiceSessionAllowed)
                    host.runExplicitVoiceAction {
                        freshCalls++
                        promotedAgain.complete(model.ui.value)
                    }
                    host.runExplicitVoiceAction { duplicateCalls++ }
                    assertEquals(0, freshCalls)
                    assertEquals(0, duplicateCalls)
                }
            }
            val during = withTimeout(8_000) { promotedAgain.await() }
            assertTrue(during.voiceSessionAllowed)
            assertTrue(during.quiet)
            assertFalse(during.failure)
            assertNull(during.recoveryCause)
            assertFalse(during.microphoneListening)
            assertNull(during.outputStage)
            assertNull(during.snapshot)
            withTimeout(8_000) { model.ui.first { !it.voiceSessionAllowed && !it.busy } }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals(1, originalCalls)
                assertEquals(1, freshCalls)
                assertEquals(0, duplicateCalls)
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.failure)
                assertNull(model.ui.value.recoveryCause)
                assertFalse(model.ui.value.microphoneListening)
                assertNull(model.ui.value.outputStage)
                assertNull(model.ui.value.snapshot)
            }
        }
    }

    @Test fun cancellationWhileAnOwnedServiceStopsCannotReviveTheOlderRequest(): Unit = runBlocking {
        fixture { scenario, model, host ->
            val latestPromoted = CompletableDeferred<DeveloperUiState>()
            var originalCalls = 0
            var cancelledCalls = 0
            var latestCalls = 0
            scenario.onActivity {
                model.useVoiceInput(true)
                host.runExplicitVoiceAction {
                    originalCalls++
                    assertTrue(model.ui.value.voiceSessionAllowed)
                    host.pause("USER")
                    host.runExplicitVoiceAction { cancelledCalls++ }
                    host.pause("USER")
                    host.runExplicitVoiceAction {
                        latestCalls++
                        latestPromoted.complete(model.ui.value)
                    }
                    assertEquals(0, cancelledCalls)
                    assertEquals(0, latestCalls)
                }
            }
            val during = withTimeout(8_000) { latestPromoted.await() }
            assertTrue(during.voiceSessionAllowed)
            assertTrue(during.quiet)
            assertFalse(during.failure)
            assertNull(during.recoveryCause)
            assertFalse(during.microphoneListening)
            assertNull(during.outputStage)
            assertNull(during.snapshot)
            withTimeout(8_000) { model.ui.first { !it.voiceSessionAllowed && !it.busy } }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals(1, originalCalls)
                assertEquals(0, cancelledCalls)
                assertEquals(1, latestCalls)
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.failure)
                assertNull(model.ui.value.recoveryCause)
                assertFalse(model.ui.value.microphoneListening)
                assertNull(model.ui.value.outputStage)
                assertNull(model.ui.value.snapshot)
            }
        }
    }

    @Test fun returningToTheActivityCannotReviveAStartWaitingForServiceDestruction(): Unit = runBlocking {
        fixture { scenario, model, host ->
            val visibilityRoundTrip = CompletableDeferred<Unit>()
            val freshPromotion = CompletableDeferred<DeveloperUiState>()
            var originalCalls = 0
            var obsoleteCalls = 0
            var freshCalls = 0
            // Observe the real teardown barrier only; never inject an owner or invoke its callbacks.
            // App members are preserved by the existing demo instrumentation keep rules.
            val stoppingOwner = VoiceSessionHost::class.java.getDeclaredField("stoppingService").apply {
                isAccessible = true
            }
            scenario.onActivity { activity ->
                model.useVoiceInput(true)
                host.runExplicitVoiceAction {
                    originalCalls++
                    assertTrue(model.ui.value.voiceSessionAllowed)
                    host.pause("USER")
                    assertNotNull(stoppingOwner.get(host))
                    host.runExplicitVoiceAction { obsoleteCalls++ }
                    // The same lifecycle entry points used by MainActivity, before old onDestroy.
                    host.activityPaused(activity)
                    model.background()
                    host.activityResumed(activity)
                    model.foreground(activity)
                    assertEquals(0, obsoleteCalls)
                    assertTrue(model.ui.value.quiet)
                    assertFalse(model.ui.value.voiceSessionAllowed)
                    visibilityRoundTrip.complete(Unit)
                }
            }
            withTimeout(8_000) { visibilityRoundTrip.await() }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            withTimeout(8_000) {
                var destroyed = false
                while (!destroyed) {
                    instrumentation.runOnMainSync { destroyed = stoppingOwner.get(host) == null }
                    if (!destroyed) delay(25)
                }
            }
            instrumentation.waitForIdleSync() // Drain the posted dispatch after the actual teardown.
            scenario.onActivity {
                assertEquals(1, originalCalls)
                assertEquals(0, obsoleteCalls)
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.voiceSessionAllowed)
                assertFalse(model.ui.value.failure)
                assertNull(model.ui.value.recoveryCause)
                assertFalse(model.ui.value.microphoneListening)
                assertNull(model.ui.value.outputStage)
                assertNull(model.ui.value.snapshot)
                host.runExplicitVoiceAction {
                    freshCalls++
                    freshPromotion.complete(model.ui.value)
                }
                assertEquals(0, freshCalls)
            }
            val during = withTimeout(8_000) { freshPromotion.await() }
            assertTrue(during.voiceSessionAllowed)
            assertTrue(during.quiet)
            assertFalse(during.failure)
            assertNull(during.recoveryCause)
            assertFalse(during.microphoneListening)
            assertNull(during.outputStage)
            assertNull(during.snapshot)
            withTimeout(8_000) { model.ui.first { !it.voiceSessionAllowed && !it.busy } }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(1, originalCalls)
                assertEquals(0, obsoleteCalls)
                assertEquals(1, freshCalls)
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.failure)
                assertNull(model.ui.value.recoveryCause)
                assertFalse(model.ui.value.microphoneListening)
                assertNull(model.ui.value.outputStage)
                assertNull(model.ui.value.snapshot)
            }
        }
    }

    private suspend fun fixture(
        test: suspend (ActivityScenario<MainActivity>, DeveloperViewModel, VoiceSessionHost) -> Unit,
    ) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("familyUiTests") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: DeveloperViewModel
            lateinit var host: VoiceSessionHost
            scenario.onActivity {
                model = ViewModelProvider(it)[DeveloperViewModel::class.java]
                host = (it.application as OnuApplication).voiceSessionHost
            }
            withTimeout(12_000) { model.ui.first { !it.busy } }
            scenario.onActivity { model.forgetFamily() }
            withTimeout(12_000) { model.ui.first { !it.busy && !it.familyDeletionPending && !it.onboarded } }
            try { test(scenario, model, host) }
            finally {
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { model.pause(); model.forgetFamily() }
                withTimeout(12_000) { model.ui.first { !it.busy && !it.familyDeletionPending && !it.onboarded } }
            }
        }
    }
}
