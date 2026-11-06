package dev.carplaymate.demo

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

/** Real Android ViewModel and HTTP API; input and playback completion are explicitly simulated. */
@RunWith(AndroidJUnit4::class)
class VehicleFlowTest {
    @Test
    fun onePlayerFindsEggAndFinishesAfterSimulatedPlayback() = withHarness { game ->
        game.start(twoPlayers = false)
        game.walk(listOf("물가", "바퀴 자국", "나뭇잎", "돋보기", "트리케라톱스", "공작실"))
        game.finish("FOUND_TOGETHER")
    }

    @Test
    fun twoPlayersReachTeamAssistanceEnding() = withHarness { game ->
        game.start(twoPlayers = true)
        game.walk(listOf("물가", "바퀴 자국", "나뭇잎", "돋보기", "트리케라톱스", "온실"))
        val history = game.snapshot().game.getJSONArray("history")
        assertEquals(listOf("p1", "p2", "p1", "p2", "p1", "p2"),
            List(history.length()) { history.getJSONObject(it).getString("player_id") })
        game.finish("TEAM_ASSIST")
    }

    @Test
    fun clearedViewModelRestoresQuietlyAndResumesWithoutRepeatingAction() = withHarness { game ->
        game.start(twoPlayers = false)
        game.walk(listOf("물가"))
        val committed = game.snapshot()
        val previousPlayback = requireNotNull(committed.speechPlan)
        val scores = game.scores(committed)
        val history = game.history(committed)

        game.onModel { pause() }
        val paused = game.awaitState("server-confirmed pause") {
            !it.busy && it.quiet && it.snapshot?.status == "PAUSED"
        }
        assertEquals(committed.turnId, paused.snapshot?.turnId)
        assertEquals(scores, game.scores(requireNotNull(paused.snapshot)))

        game.recreate()
        assertTrue(game.state().quiet)
        assertFalse(game.state().voiceInput)
        assertFalse(game.state().microphoneListening)
        assertEquals(null, game.state().snapshot)
        game.onModel { restore() }
        val restored = requireNotNull(game.awaitState("quiet checkpoint restoration") {
            val snapshot = it.snapshot
            !it.busy && it.quiet && snapshot?.sessionId == committed.sessionId &&
                snapshot.status == "PAUSED"
        }.snapshot)
        assertEquals(committed.turnId, restored.turnId)
        assertEquals(committed.game.getInt("turn_index"), restored.game.getInt("turn_index"))
        assertEquals(scores, game.scores(restored))
        assertEquals(history, game.history(restored))
        assertEquals(null, restored.speechPlan)
        assertEquals(null, restored.listenWindowId)

        // A stale development completion must not open input while the restored app is quiet.
        game.onModel { simulatePlaybackCompletion() }
        assertTrue(game.state().quiet)
        assertEquals(restored.version, game.snapshot().version)
        assertEquals(null, game.snapshot().listenWindowId)

        game.onModel { resume() }
        val resumed = requireNotNull(game.awaitState("explicit resume with a new playback plan") {
            val snapshot = it.snapshot
            !it.busy && !it.quiet && snapshot?.status == "ACTIVE" && snapshot.speechPlan != null
        }.snapshot)
        assertNotEquals(committed.turnId, resumed.turnId)
        assertTrue(resumed.turnEpoch > committed.turnEpoch)
        assertNotEquals(previousPlayback.playbackId, resumed.speechPlan?.playbackId)
        assertEquals(previousPlayback.segments.map { it.text }, resumed.speechPlan?.segments?.map { it.text })
        assertEquals(scores, game.scores(resumed))
        assertEquals(history, game.history(resumed))

        game.completePrompt()
        assertEquals(scores, game.scores(game.snapshot()))
        assertEquals(history, game.history(game.snapshot()))
        game.submitChoice("바퀴 자국")
        val continued = game.snapshot()
        assertEquals(2, continued.game.getInt("turn_index"))
        assertEquals(2, continued.game.getJSONArray("history").length())
        assertEquals(scores.getValue("p1") + 1, game.scores(continued).getValue("p1"))
    }

    private fun withHarness(block: (Harness) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("apiToken").orEmpty()
        assumeTrue("VehicleFlowTest requires an explicit apiToken instrumentation argument.", token.isNotBlank())
        val apiUrl = args.getString("apiUrl") ?: "http://10.0.2.2:8000"
        val game = Harness(InstrumentationRegistry.getInstrumentation(), apiUrl, token)
        var failure: Throwable? = null
        try {
            game.recreate()
            block(game)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                game.close()
            } catch (cleanupError: Throwable) {
                if (failure == null) throw cleanupError
                failure.addSuppressed(cleanupError)
            }
        }
    }

    /** All preferences and the Keystore-derived private directory share one isolated test scope. */
    private class ScopedApplication(context: Context, private val checkpointName: String) : Application() {
        private val base = context.noBackupFilesDir.canonicalFile
        private val directory = File(base, checkpointName).also { check(it.mkdirs()) }
        private val preferences = mutableSetOf<String>()
        init { attachBaseContext(context) }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "${checkpointName}_$name"
            synchronized(preferences) { preferences += scoped }
            return super.getSharedPreferences(scoped, mode)
        }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory

        fun clean() {
            check(directory.canonicalFile.parentFile == base && directory.name == checkpointName)
            synchronized(preferences) { preferences.forEach { check(super.deleteSharedPreferences(it)) } }
            val alias = "onu.records.v1." + java.security.MessageDigest.getInstance("SHA-256")
                .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
            check(directory.deleteRecursively())
        }
    }

    private class Harness(
        private val instrumentation: Instrumentation,
        private val apiUrl: String,
        private val apiToken: String,
    ) {
        private val context = instrumentation.targetContext
        private val checkpointName = "vehicle_flow_${UUID.randomUUID()}"
        private val application = onMain { ScopedApplication(context, checkpointName) }
        private var store: ViewModelStore? = null
        private var model: DeveloperViewModel? = null

        fun recreate() = onMain {
            model?.background()
            store?.clear()
            val nextStore = ViewModelStore()
            store = nextStore
            model = ViewModelProvider(nextStore,
                ViewModelProvider.AndroidViewModelFactory(application))[DeveloperViewModel::class.java]
            requireNotNull(model).useSimulation(true)
            requireNotNull(model).configure(apiUrl, apiToken)
        }

        fun onModel(action: DeveloperViewModel.() -> Unit) = onMain { requireNotNull(model).action() }

        fun state(): DeveloperUiState = onMain { requireNotNull(model).ui.value }

        fun snapshot(): Snapshot = requireNotNull(state().snapshot)

        fun start(twoPlayers: Boolean) {
            assertTrue(state().connected)
            assertTrue(state().simulation)
            assertFalse(state().voiceInput)
            onModel { start("별", if (twoPlayers) "달" else null) }
            awaitState("new adventure") { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            assertEquals(if (twoPlayers) 2 else 1, snapshot().game.getJSONArray("players").length())
        }

        fun walk(choices: List<String>) {
            choices.forEach { choice ->
                completePrompt()
                submitChoice(choice)
            }
        }

        fun completePrompt() {
            val before = snapshot()
            assertNotNull(before.speechPlan)
            onModel { simulatePlaybackCompletion() }
            awaitState("input after simulated playback") {
                val snapshot = it.snapshot
                !it.busy && !it.quiet && snapshot?.sessionId == before.sessionId &&
                    snapshot.phase == "LISTENING" && snapshot.listenWindowId != null
            }
        }

        fun submitChoice(choice: String) {
            val before = snapshot()
            val expectedPlayer = before.game.getString("expected_player_id")
            val players = before.game.getJSONArray("players")
            val name = (0 until players.length()).map { players.getJSONObject(it) }
                .single { it.getString("player_id") == expectedPlayer }.getString("nickname")
            val expectedTurn = before.game.getInt("turn_index") + 1
            onModel { submit("$name, $choice") }
            val after = requireNotNull(awaitState("submitted choice") {
                val snapshot = it.snapshot
                !it.busy && !it.quiet && snapshot?.sessionId == before.sessionId &&
                    snapshot.game.getInt("turn_index") == expectedTurn
            }.snapshot)
            assertNotEquals(before.turnId, after.turnId)
            assertEquals(expectedTurn, after.game.getJSONArray("history").length())
            assertEquals(expectedPlayer, after.game.getJSONArray("history")
                .getJSONObject(expectedTurn - 1).getString("player_id"))
        }

        fun finish(expectedEnding: String) {
            val before = snapshot()
            assertEquals(expectedEnding, before.game.getString("ending"))
            assertEquals("ACTIVE", before.status)
            assertEquals("FEEDBACK", before.phase)
            assertEquals(6, before.game.getJSONArray("history").length())
            val originalScores = scores(before)
            onModel { simulatePlaybackCompletion() }
            val ended = awaitState("final simulated playback") {
                !it.busy && it.quiet && it.snapshot?.status == "ENDED"
            }
            val finalSnapshot = requireNotNull(ended.snapshot)
            assertEquals(expectedEnding, finalSnapshot.game.getString("ending"))
            assertEquals(originalScores, scores(finalSnapshot))
            assertEquals(6, finalSnapshot.game.getJSONArray("history").length())
            assertEquals(null, finalSnapshot.listenWindowId)
            assertFalse(ended.microphoneListening)
        }

        fun scores(snapshot: Snapshot): Map<String, Int> {
            val scores = snapshot.game.getJSONObject("score_by_player")
            return scores.keys().asSequence().associateWith { scores.getInt(it) }
        }

        fun history(snapshot: Snapshot): List<Any?> {
            val history = snapshot.game.getJSONArray("history")
            return List(history.length()) { structuralJson(history.get(it)) }
        }

        // Preserve every field and array position; JSON object key order is not game state.
        private fun structuralJson(value: Any?): Any? = when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.keys().asSequence().associateWith { structuralJson(value.get(it)) }
            is JSONArray -> List(value.length()) { structuralJson(value.get(it)) }
            else -> value
        }

        fun awaitState(description: String, predicate: (DeveloperUiState) -> Boolean): DeveloperUiState {
            val deadline = SystemClock.elapsedRealtime() + 12_000
            var last = state()
            while (SystemClock.elapsedRealtime() < deadline) {
                last = state()
                if (predicate(last)) return last
                SystemClock.sleep(25)
            }
            throw AssertionError("Timed out waiting for $description: busy=${last.busy}, " +
                "quiet=${last.quiet}, status=${last.snapshot?.status}, phase=${last.snapshot?.phase}, " +
                "message=${last.message}")
        }

        fun close() {
            try {
                if (model != null) {
                    onModel { background() }
                    awaitState("cleanup pause") {
                        val snapshot = it.snapshot
                        !it.busy && it.quiet && (snapshot == null || snapshot.status != "ACTIVE")
                    }
                }
            } finally {
                onMain {
                    try {
                        store?.clear()
                    } finally {
                        store = null
                        model = null
                        // Never read, rewrite or delete the target app's unscoped family records.
                        application.clean()
                    }
                }
            }
        }

        private fun <T> onMain(action: () -> T): T {
            val task = FutureTask(Callable { action() })
            instrumentation.runOnMainSync(task)
            return task.get()
        }
    }
}
