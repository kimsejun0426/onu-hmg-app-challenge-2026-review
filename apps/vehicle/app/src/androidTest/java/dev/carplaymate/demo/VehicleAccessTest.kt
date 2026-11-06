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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fake UXR events and silent output around the real VM/local store; not an AAOS service test. */
@RunWith(AndroidJUnit4::class)
class VehicleAccessTest {
    @Test
    fun unknownAndRestrictedBlockStartingInputAndPermissionCallbacks() = runBlocking {
        withHarness { h ->
            h.ready()
            h.onModel {
                foreground(h.application)
                assertFalse(saveFamily("별", null))
                start("별", null)
                useVoiceInput(true)
                microphonePermissionResult(true)
                useSimulation(true)
                submit("별, 물가")
                simulatePlaybackCompletion()
            }
            assertEquals(VehicleAccessState.UNKNOWN, h.model.ui.value.vehicleAccess)
            assertNull(h.model.ui.value.snapshot)
            assertNull(h.backend.restore())
            assertFalse(h.model.ui.value.voiceInput || h.model.ui.value.simulation)
            assertEquals(0, h.output.refreshes)
            h.emit(VehicleAccessState.RESTRICTED)
            h.onModel {
                start("별", null)
                resume()
            }
            assertNull(h.backend.restore())
            h.emit(VehicleAccessState.UNRESTRICTED)
            assertTrue(h.model.ui.value.quiet)
            assertEquals(0, h.output.playbacks.size)
            h.onModel { start("별", null) }
            h.await { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            assertEquals(1, h.output.playbacks.size)
        }
    }

    @Test
    fun briefRestrictionStopsOutputAndLateAudioCannotResumeOrAcknowledge() = runBlocking {
        withHarness { h ->
            h.start()
            val playback = h.output.playbacks.single()
            val initial = checkNotNull(h.model.ui.value.snapshot)
            val stops = h.output.stops
            h.onModel {
                h.provider.emit(VehicleAccessState.RESTRICTED)
                assertTrue(ui.value.quiet)
                assertTrue(h.output.stops > stops)
                h.provider.emit(VehicleAccessState.UNRESTRICTED)
                playback.complete()
                playback.failure(SpeechOutputFailure("Late synthetic engine failure"))
            }
            val paused = h.await { !it.busy && it.quiet && it.snapshot?.status == "PAUSED" }
            assertEquals(VehicleAccessState.UNRESTRICTED, paused.vehicleAccess)
            assertFalse(paused.microphoneListening)
            assertEquals(1, h.output.playbacks.size)
            assertEquals(
                0,
                h.backend.snapshot(initial.sessionId).game.getJSONArray("history").length(),
            )
            assertNull(paused.snapshot?.speechPlan)
            h.onModel { resume() }
            h.await { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            assertEquals(2, h.output.playbacks.size)
        }
    }

    @Test
    fun restrictionClosesTouchWindowAndRequiresANewExplicitResume() = runBlocking {
        withHarness { h ->
            h.ready()
            h.onModel { foreground(h.application) }
            h.emit(VehicleAccessState.UNRESTRICTED)
            h.onModel {
                useSimulation(true)
                start("별", null)
            }
            h.await { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            h.onModel { simulatePlaybackCompletion() }
            val listening =
                checkNotNull(h.await { !it.busy && it.snapshot?.phase == "LISTENING" }.snapshot)
            val choice =
                listening.presentation.getJSONArray("actions").getJSONObject(0).getString("label")
            h.emit(VehicleAccessState.UNKNOWN)
            h.onModel {
                chooseAction(choice)
                submit("별, $choice")
                resume()
                resumeAdventure(listening.sessionId)
            }
            h.await { !it.busy && it.quiet && it.snapshot?.status == "PAUSED" }
            assertEquals(
                0,
                h.backend.snapshot(listening.sessionId).game.getJSONArray("history").length(),
            )
            h.emit(VehicleAccessState.UNRESTRICTED)
            h.onModel {
                chooseAction(choice)
                simulatePlaybackCompletion()
            }
            assertTrue(h.model.ui.value.quiet)
            assertEquals(
                0,
                h.backend.snapshot(listening.sessionId).game.getJSONArray("history").length(),
            )
            assertTrue(h.output.playbacks.isEmpty())
        }
    }

    @Test
    fun lifecycleAndDeletionFenceOldVehicleCallbacksWithoutChangingTheGate() = runBlocking {
        withHarness { h ->
            h.start()
            val oldCallback = h.provider.callbacks.single()
            val stops = h.output.stops
            h.provider.beforeClose = {
                assertTrue(
                    "Local silence must precede a potentially blocking SDK disconnect.",
                    h.model.ui.value.quiet,
                )
                assertTrue(h.output.stops > stops)
            }
            h.onModel { background() }
            h.await { !it.busy && it.quiet && it.snapshot?.status == "PAUSED" }
            h.onModel { oldCallback(VehicleAccessState.UNRESTRICTED) }
            assertEquals(VehicleAccessState.UNKNOWN, h.model.ui.value.vehicleAccess)
            h.onModel { forgetFamily() }
            assertNotNull(
                "Unknown vehicle state must block a new deletion.",
                h.model.ui.value.snapshot,
            )
            assertFalse(h.model.ui.value.familyDeletionPending)
            assertEquals(VehicleAccessState.UNKNOWN, h.model.ui.value.vehicleAccess)
            h.onModel {
                foreground(h.application)
                oldCallback(VehicleAccessState.UNRESTRICTED)
            }
            assertEquals(VehicleAccessState.UNKNOWN, h.model.ui.value.vehicleAccess)
            h.emit(VehicleAccessState.UNRESTRICTED)
            h.onModel { oldCallback(VehicleAccessState.RESTRICTED) }
            assertEquals(VehicleAccessState.UNRESTRICTED, h.model.ui.value.vehicleAccess)
            h.onModel { forgetFamily() }
            h.await { !it.busy && !it.familyDeletionPending && it.snapshot == null }
            assertEquals(VehicleAccessState.UNRESTRICTED, h.model.ui.value.vehicleAccess)
            assertTrue(h.model.ui.value.quiet)
            assertEquals(1, h.output.playbacks.size)
        }
    }

    @Test
    fun aCreationCommittedBeforeRestrictionCannotPlayWhenItsResponseArrivesLate() = runBlocking {
        lateinit var delayed: DelayedCreation
        withHarness({ backend -> DelayedCreation(backend).also { delayed = it } }) { h ->
            h.ready()
            h.onModel { foreground(h.application) }
            h.emit(VehicleAccessState.UNRESTRICTED)
            h.onModel { start("별", null) }
            val created = withTimeout(12_000) { delayed.arrived.await() }
            h.emit(VehicleAccessState.RESTRICTED)
            h.emit(VehicleAccessState.UNRESTRICTED)
            delayed.release.complete(Unit)
            withTimeout(12_000) { delayed.paused.await() }
            h.onModel { /* drain the completion after the committed pause */ }
            assertEquals("PAUSED", h.backend.snapshot(created.sessionId).status)
            assertTrue(h.model.ui.value.quiet)
            assertNull(h.model.ui.value.snapshot)
            assertTrue(h.output.playbacks.isEmpty())
        }
    }

    private suspend fun withHarness(
        client: ((LocalGameClient) -> GameClient)? = null,
        block: suspend (Harness) -> Unit,
    ) {
        val h = Harness(client)
        try {
            block(h)
        } finally {
            h.close()
        }
    }

    private class DelayedCreation(private val delegate: LocalGameClient) : GameClient by delegate {
        val arrived = CompletableDeferred<Snapshot>()
        val release = CompletableDeferred<Unit>()
        val paused = CompletableDeferred<Unit>()

        override suspend fun createAdventure(players: JSONObject, packId: String): Snapshot {
            val committed = delegate.createAdventure(players, packId)
            arrived.complete(committed)
            release.await()
            return committed
        }

        override suspend fun command(id: String, body: JSONObject): JSONObject {
            val result = delegate.command(id, body)
            if (body.getString("type") == "session.pause") paused.complete(Unit)
            return result
        }
    }

    private class FakeVehicle : VehicleAccessProvider {
        override val initialState = VehicleAccessState.UNKNOWN
        val callbacks = mutableListOf<(VehicleAccessState) -> Unit>()
        var beforeClose: (() -> Unit)? = null

        override fun start(context: Context, changed: (VehicleAccessState) -> Unit) {
            callbacks += changed
        }

        override fun close() {
            beforeClose?.invoke()
        } // Keep queued callbacks so the VM must fence them.

        fun emit(state: VehicleAccessState) {
            callbacks.last()(state)
        }
    }

    private class SilentOutput : SpeechOutput {
        data class Playback(val complete: () -> Unit, val failure: (SpeechOutputFailure) -> Unit)

        override val status = MutableStateFlow("가짜 음성 출력")
        override val readyState = MutableStateFlow(true)
        val playbacks = mutableListOf<Playback>()
        var stops = 0
        var refreshes = 0

        override fun speak(
            plan: SpeechPlan,
            generation: Long,
            complete: () -> Unit,
            failure: (SpeechOutputFailure) -> Unit,
            started: () -> Unit,
        ) {
            playbacks += Playback(complete, failure)
        }

        override fun stop() {
            stops++
        }

        override fun shutdown() = stop()

        override fun refresh() {
            refreshes++
        }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "vehicle_access_${UUID.randomUUID()}"
        private val directory = File(context.noBackupFilesDir, prefix).also { check(it.mkdirs()) }
        private val preferences = mutableSetOf<String>()

        init {
            attachBaseContext(context)
        }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = directory

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val name = "${prefix}_$name"
            synchronized(preferences) { preferences += name }
            return super.getSharedPreferences(name, mode)
        }

        fun clean() {
            synchronized(preferences) {
                preferences.forEach { check(super.deleteSharedPreferences(it)) }
            }
            check(directory.deleteRecursively())
        }
    }

    private class Harness(client: ((LocalGameClient) -> GameClient)?) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ScopedApplication(instrumentation.targetContext)
        val backend = LocalGameClient(application)
        private val injected = client?.invoke(backend)
        val provider = FakeVehicle()
        val output = SilentOutput()
        private val store = ViewModelStore()
        val model = main {
            ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        DeveloperViewModel(
                            application,
                            injected,
                            outputFactory = { output },
                            vehicleAccessProvider = provider,
                        )
                            as T
                },
            )[DeveloperViewModel::class.java]
        }

        fun onModel(action: DeveloperViewModel.() -> Unit) = main { model.action() }

        fun emit(state: VehicleAccessState) = main { provider.emit(state) }

        suspend fun await(predicate: (DeveloperUiState) -> Boolean): DeveloperUiState {
            val state = withTimeout(12_000) { model.ui.first(predicate) }
            main { /* Complete the publishing main-thread operation, including output callbacks. */
            }
            return state
        }

        suspend fun ready() {
            await { !it.busy }
        }

        suspend fun start() {
            ready()
            onModel { foreground(application) }
            emit(VehicleAccessState.UNRESTRICTED)
            onModel { start("별", null) }
            await { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
        }

        suspend fun close() {
            (injected as? DelayedCreation)?.release?.complete(Unit)
            main { store.clear() }
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
