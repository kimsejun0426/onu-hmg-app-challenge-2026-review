package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StabilityRecoveryTest {
    @Test
    fun deletionSurvivesBackgroundAndRejectsNewFamilyWrites() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withHarness { h ->
            h.create { step ->
                if (step == FamilyDeletionStep.BEFORE_GAME) {
                    arrived.complete(Unit)
                    release.await()
                }
            }
            h.seed()
            try {
                h.onModel { forgetFamily() }
                withTimeout(12_000) { arrived.await() }
                h.onModel {
                    background()
                    assertFalse(saveFamily("새별", null))
                    assertFalse(saveInterests(setOf("space")))
                    assertFalse(selectAdventure(DEFAULT_ADVENTURE_ID))
                    startFamilyAdventure()
                    start("새별", null)
                    restore()
                    resume()
                    useLocalBackend()
                    configure("http://127.0.0.1:8000", "test-only")
                    useSimulation(true)
                    microphonePermissionResult(true)
                    forgetFamily()
                }
                val held = h.model.ui.value
                assertTrue(held.busy && held.quiet && held.familyDeletionPending)
                assertTrue(held.children.isEmpty() && held.journal.isEmpty())
                assertFalse(held.onboarded || held.microphoneListening || held.voiceInput)
                assertNull(held.snapshot)
                assertEquals("별", FamilyPreferences(h.application).profile?.first)
                release.complete(Unit)
                h.await { !it.busy && !it.familyDeletionPending }
                h.assertErased()
                h.onModel { background() }
                h.assertErased()
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun failedDeletionAtEveryStoreBoundaryRemainsHiddenAndCanRetry() = runBlocking {
        for (failureAt in FamilyDeletionStep.entries) withHarness { h ->
            var inject = true
            h.create { step ->
                if (step == failureAt && inject) {
                    inject = false
                    error("Injected storage boundary failure")
                }
            }
            h.seed()
            h.onModel { forgetFamily() }
            val failed = h.await { !it.busy && it.familyDeletionPending && it.failure }
            assertFalse(failed.onboarded)
            assertTrue(failed.children.isEmpty() && failed.journal.isEmpty())
            assertTrue(FamilyDeletionJournal(h.application).pending)
            h.onModel {
                background()
                assertFalse(saveFamily("옛가족", null))
                start("옛가족", null)
                forgetFamily()
            }
            h.await { !it.busy && !it.familyDeletionPending }
            h.assertErased()
            h.onModel {
                // Deletion may finish while hidden; editing a family requires returning to UI.
                assertFalse(saveFamily("새가족", null))
                foreground(h.application)
                assertTrue(saveFamily("새가족", null))
            }
            assertEquals("새가족", FamilyPreferences(h.application).profile?.first)
        }
    }

    @Test
    fun interruptedDeletionResumesBeforeExposingAProfileOnRecreation() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        withHarness { h ->
            h.create { step ->
                if (step == FamilyDeletionStep.AFTER_GAME) {
                    arrived.complete(Unit)
                    neverComplete.await()
                }
            }
            h.seed()
            h.onModel { forgetFamily() }
            withTimeout(12_000) { arrived.await() }
            assertNotNull(FamilyPreferences(h.application).profile)
            h
                .clearModel() // Cancels the deletion after the game file but before
                              // profile/checkpoint erasure.
            assertTrue(FamilyDeletionJournal(h.application).pending)
            val resumed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                h.create { step ->
                    if (step == FamilyDeletionStep.BEFORE_GAME) {
                        resumed.complete(Unit)
                        release.await()
                    }
                }
                withTimeout(12_000) { resumed.await() }
                val initial = h.model.ui.value
                assertTrue(initial.familyDeletionPending && initial.busy && initial.quiet)
                assertFalse(initial.onboarded)
                assertTrue(initial.firstNickname.isEmpty() && initial.children.isEmpty())
                assertTrue(initial.journal.isEmpty() && initial.adventureFeedback.isEmpty())
                release.complete(Unit)
                h.await { !it.busy && !it.familyDeletionPending && ContentReleaseExpectations.hasCurrentCards(it.adventures) }
                h.assertErased()
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun permissionGrantInitializesAfterStoppingAndNeverStartsPlayback() = runBlocking {
        withHarness { h ->
            h.create()
            h.await { !it.busy }
            h.onModel { microphonePermissionResult(true) }
            assertEquals(listOf("stop", "refresh"), h.output.events)
            h.onModel { h.output.finishInitialization() }
            val ready = h.await { it.speechReady }
            assertTrue(ready.quiet)
            assertFalse(ready.microphoneListening)
            assertNull(ready.snapshot)
            assertEquals(0, h.output.speaks)
        }
    }

    @Test
    fun deniedPermissionStillLeavesReadingAvailableAndOutputCanInitialize() = runBlocking {
        withHarness { h ->
            h.create()
            h.await { !it.busy }
            h.onModel {
                microphonePermissionResult(false)
                h.output.finishInitialization()
            }
            val ready = h.await { it.speechReady }
            assertFalse(ready.voiceInput || ready.microphoneListening)
            assertTrue(ready.quiet)
            h.onModel { useSimulation(true) }
            assertTrue(h.model.ui.value.simulation)
            assertEquals(0, h.output.speaks)
        }
    }

    private suspend fun withHarness(block: suspend (Harness) -> Unit) {
        val h = Harness()
        try {
            block(h)
        } finally {
            h.close()
        }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "stability_" + UUID.randomUUID()
        private val directory = File(context.noBackupFilesDir, prefix).also { check(it.mkdirs()) }
        private val preferences = mutableSetOf<String>()

        init {
            attachBaseContext(context)
        }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = directory

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = prefix + "_" + name
            synchronized(preferences) { preferences.add(scoped) }
            return super.getSharedPreferences(scoped, mode)
        }

        fun clean() {
            synchronized(preferences) {
                preferences.forEach { check(super.deleteSharedPreferences(it)) }
            }
            check(directory.deleteRecursively())
        }
    }

    /** Models Pleos initialization ownership: stop invalidates an in-flight initialization. */
    private class PendingOutput : SpeechOutput {
        override val status = MutableStateFlow("시험 음성")
        override val readyState = MutableStateFlow(false)
        val events = mutableListOf<String>()
        var speaks = 0
        private var initializing = false

        override fun refresh() {
            events += "refresh"
            readyState.value = false
            initializing = true
        }

        fun finishInitialization() {
            if (initializing) {
                initializing = false
                readyState.value = true
            }
        }

        override fun stop() {
            events += "stop"
            initializing = false
        }

        override fun shutdown() {
            stop()
        }

        override fun speak(
            plan: SpeechPlan,
            generation: Long,
            complete: () -> Unit,
            failure: (SpeechOutputFailure) -> Unit,
            started: () -> Unit,
        ) {
            speaks += 1
            error("Unexpected playback")
        }
    }

    private class Harness {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ScopedApplication(instrumentation.targetContext)
        val backend = LocalGameClient(application)
        val output = PendingOutput()
        private var store = ViewModelStore()
        lateinit var model: DeveloperViewModel

        fun create(checkpoint: suspend (FamilyDeletionStep) -> Unit = {}) {
            store = ViewModelStore()
            model = main {
                ViewModelProvider(
                    store,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            DeveloperViewModel(
                                application,
                                null,
                                deletionCheckpoint = checkpoint,
                                outputFactory = { output },
                            )
                                as T
                    },
                )[DeveloperViewModel::class.java]
            }
        }

        fun onModel(action: DeveloperViewModel.() -> Unit) = main { model.action() }

        fun clearModel() = main { store.clear() }

        suspend fun await(predicate: (DeveloperUiState) -> Boolean) =
            withTimeout(12_000) { model.ui.first(predicate) }

        suspend fun seed() {
            await { !it.busy && ContentReleaseExpectations.hasCurrentCards(it.adventures) }
            onModel {
                check(saveFamily("별", "달"))
                check(saveInterests(setOf("dinosaurs"), setOf("space")))
                useSimulation(true)
                startFamilyAdventure()
            }
            await { !it.busy && !it.quiet && it.snapshot != null }
            onModel { pause() }
            await { !it.busy && it.quiet }
            check(LocalCheckpoint(application).session("remote-test-session"))
        }

        suspend fun assertErased() {
            assertEquals(0, output.speaks)
            val ui = model.ui.value
            assertFalse(ui.onboarded || ui.hasSavedSession || ui.familyDeletionPending)
            assertTrue(
                ui.children.isEmpty() && ui.journal.isEmpty() && ui.adventureFeedback.isEmpty()
            )
            assertTrue(ui.quiet)
            assertNull(ui.snapshot)
            assertNull(FamilyPreferences(application).profile)
            assertNull(backend.restore())
            assertTrue(backend.journal().isEmpty() && backend.feedback().isEmpty())
            assertNull(LocalCheckpoint(application).sessionId)
            assertNull(LocalCheckpoint(application, "recovery_local_v1").sessionId)
            assertFalse(FamilyDeletionJournal(application).pending)
        }

        suspend fun close() {
            clearModel()
            backend.deleteAll()
            application.clean()
        }

        private fun <T> main(action: () -> T): T {
            val task = FutureTask(Callable { action() })
            instrumentation.runOnMainSync(task)
            return task.get()
        }
    }
}
