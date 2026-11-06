package dev.carplaymate.demo

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated-emulator fixture only: STARTED pauses the activity without calling onStop. */
@RunWith(AndroidJUnit4::class)
class ActivityPauseTest {
    @Test
    fun pausingWithoutStoppingSilencesTheSessionAndResumeDoesNotAutoplay(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("familyUiTests") == "true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: DeveloperViewModel
            scenario.onActivity { model = ViewModelProvider(it)[DeveloperViewModel::class.java] }
            withTimeout(12_000) { model.ui.first { !it.busy } }
            scenario.onActivity { model.forgetFamily() }
            withTimeout(12_000) {
                model.ui.first { !it.busy && !it.familyDeletionPending && !it.onboarded }
            }
            scenario.onActivity {
                assertTrue(model.saveFamily("시험별", null))
                model.useSimulation(true)
                model.startFamilyAdventure()
            }
            withTimeout(12_000) { model.ui.first { !it.busy && !it.quiet && it.snapshot != null } }
            scenario.moveToState(Lifecycle.State.STARTED)
            val paused =
                withTimeout(12_000) {
                    model.ui.first { !it.busy && it.quiet && it.snapshot?.status == "PAUSED" }
                }
            assertFalse(paused.microphoneListening)
            val version = paused.snapshot!!.version
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertTrue(model.ui.value.quiet)
                assertFalse(model.ui.value.microphoneListening)
                assertEquals(version, model.ui.value.snapshot?.version)
                model.forgetFamily()
            }
            withTimeout(12_000) {
                model.ui.first { !it.busy && !it.familyDeletionPending && !it.onboarded }
            }
        }
    }
}
