package dev.carplaymate.demo

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

/**
 * A two-process protocol, run as two separate instrumentation invocations on a dedicated emulator.
 * The controller must force-stop the target package AFTER process-ready.txt appears.
 * seedActiveAdventure deliberately cannot pass: a surviving seed fails after 45 seconds.
 * Both phases use explicit reading mode; they do not verify real speech playback or recognition.
 */
@RunWith(AndroidJUnit4::class)
class ProcessRestoreTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun seedActiveAdventure() {
        requireExplicitFixturePermission()
        val expectedFile = expectedFile(context)
        val marker = readyFile(context)
        check(!marker.exists() || marker.delete()) { "Could not remove an old process marker." }
        check(!expectedFile.exists() || expectedFile.delete()) { "Could not remove an old fixture expectation." }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: DeveloperViewModel
        scenario.onActivity { model = ViewModelProvider(it)[DeveloperViewModel::class.java] }
        try {
            awaitState(model, "initial local load") { !it.busy }
            onMain { model.forgetFamily() }
            awaitState(model, "explicitly authorised clean fixture") { !it.busy && !it.onboarded && it.snapshot == null }
            onMain {
                assertTrue(model.saveFamily("별", "달"))
                assertTrue(model.saveInterests(setOf("dinosaurs", "space"), setOf("ocean")))
                model.useSimulation(true)
            }
            awaitState(model, "explicit reading mode") { !it.busy && it.simulation && it.quiet }
            onMain { model.startFamilyAdventure() }
            val initial = awaitState(model, "first active prompt") { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            val initialPlan = requireNotNull(initial.snapshot?.speechPlan)
            onMain { model.simulatePlaybackCompletion() }
            awaitState(model, "first input window") { !it.busy && !it.quiet && it.snapshot?.phase == "LISTENING" }
            onMain { model.chooseAction("물가") }
            // A nonfinal action publishes its feedback AND the next prompt as PROMPTING.
            // FEEDBACK is reserved for the finished game's final narration in both services.
            val state = awaitState(model, "committed action with unheard feedback and next prompt") {
                val snapshot = it.snapshot
                !it.busy && !it.quiet && snapshot?.status == "ACTIVE" && snapshot.phase == "PROMPTING" &&
                    !snapshot.game.optBoolean("finished") && snapshot.game.optInt("turn_index") == 1 &&
                    snapshot.game.getJSONArray("history").length() == 1 && snapshot.speechPlan != null
            }
            val snapshot = requireNotNull(state.snapshot)
            assertTrue(state.useLocal)
            assertTrue(state.simulation)
            assertFalse(state.voiceInput)
            assertFalse(state.microphoneListening)
            assertEquals(1, snapshot.game.getJSONArray("history").length())
            val firstAction = snapshot.game.getJSONArray("history").getJSONObject(0)
            assertEquals("p1", firstAction.getString("player_id"))
            assertEquals("RIVER", firstAction.getString("action_id"))
            assertEquals("p2", snapshot.game.getString("expected_player_id"))
            assertNull(snapshot.listenWindowId)
            val pendingPlan = requireNotNull(snapshot.speechPlan)
            assertNotEquals(initialPlan.playbackId, pendingPlan.playbackId)
            assertEquals(snapshot.turnId, pendingPlan.turnId)
            assertEquals(snapshot.turnEpoch, pendingPlan.turnEpoch)
            assertTrue(pendingPlan.segments.all { it.required && it.text.isNotBlank() })
            assertEquals(listOf(snapshot.game.getString("feedback"), snapshot.presentation.getString("prompt")),
                pendingPlan.segments.map { it.text })
            val runId = UUID.randomUUID().toString()
            val expected = JSONObject()
                .put("schema", 1)
                .put("run_id", runId)
                .put("seed_pid", Process.myPid())
                .put("seed_process_started_elapsed", Process.getStartElapsedRealtime())
                .put("session_id", snapshot.sessionId)
                .put("version", snapshot.version)
                .put("turn_id", snapshot.turnId)
                .put("turn_epoch", snapshot.turnEpoch)
                .put("turn_index", snapshot.game.getInt("turn_index"))
                .put("history", JSONArray(snapshot.game.getJSONArray("history").toString()))
                .put("scores", JSONObject(snapshot.game.getJSONObject("score_by_player").toString()))
                .put("playback_id", pendingPlan.playbackId)
                .put("unheard_texts", JSONArray(pendingPlan.segments.map { it.text }))
                .put("children", childrenJson(state.children))

            // Persist expectations before publishing readiness. The marker never precedes the game commit.
            writeDurably(expectedFile, expected.toString())
            writeDurably(marker, runId + "\n")
            val deadline = SystemClock.elapsedRealtime() + 45_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val current = model.ui.value
                assertTrue("The seed stopped normally before the controller killed the process.",
                    !current.quiet && current.snapshot?.status == "ACTIVE")
                val unchanged = requireNotNull(current.snapshot)
                assertEquals("Pending narration was acknowledged before process death.", pendingPlan, unchanged.speechPlan)
                assertEquals(snapshot.version, unchanged.version)
                assertEquals("PROMPTING", unchanged.phase)
                assertNull(unchanged.listenWindowId)
                assertPreserved(expected, unchanged)
                SystemClock.sleep(100)
            }
            throw AssertionError("No external process death occurred within 45 seconds. This seed must be force-stopped, not reported as passed.")
        } finally {
            // A real force-stop cannot execute this cleanup. A normal exit invalidates the marker.
            marker.delete()
            scenario.close()
        }
    }

    @Test
    fun verifyRestoredAdventure() {
        requireExplicitFixturePermission()
        val expectedFile = expectedFile(context)
        val marker = readyFile(context)
        assertTrue("Run seedActiveAdventure and force-stop after its marker first.", expectedFile.isFile && marker.isFile)
        val expected = JSONObject(expectedFile.readText(Charsets.UTF_8))
        assertEquals(1, expected.getInt("schema"))
        assertEquals(expected.getString("run_id"), marker.readText(Charsets.UTF_8).trim())
        assertNotEquals("An Activity recreation is not a process-death test.", expected.getInt("seed_pid"), Process.myPid())
        assertTrue("Verification must run in a newer OS process.",
            Process.getStartElapsedRealtime() > expected.getLong("seed_process_started_elapsed"))

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: DeveloperViewModel
        scenario.onActivity { model = ViewModelProvider(it)[DeveloperViewModel::class.java] }
        try {
            val state = awaitState(model, "automatic quiet recovery in the new process") {
                !it.busy && it.snapshot != null
            }
            val restored = requireNotNull(state.snapshot)
            assertTrue(state.useLocal)
            assertTrue(state.onboarded)
            assertTrue(state.hasSavedSession)
            assertTrue(state.quiet)
            assertFalse(state.failure)
            assertFalse(state.voiceInput)
            assertFalse(state.microphoneListening)
            assertTrue("The explicitly selected reading preference survives process restart.", state.simulation)
            assertNull(state.captureStage)
            assertEquals("PAUSED", restored.status)
            assertEquals(expected.getString("session_id"), restored.sessionId)
            assertEquals(expected.getString("turn_id"), restored.turnId)
            assertTrue(restored.turnEpoch > expected.getLong("turn_epoch"))
            assertNull(restored.speechPlan)
            assertNull(restored.listenWindowId)
            assertPreserved(expected, restored)
            assertEquals(0, state.completedAdventures)
            assertFalse(state.interestSetupNeeded)
            assertEquals(structuralJson(expected.getJSONArray("children")), structuralJson(childrenJson(state.children)))
            assertEquals(setOf("dinosaurs", "space"), state.children[0].interests)
            assertEquals(setOf("ocean"), state.children[1].interests)

            // Old playback completions remain powerless while the new process is quiet.
            onMain { model.simulatePlaybackCompletion() }
            assertTrue(model.ui.value.quiet)
            assertEquals(restored.version, model.ui.value.snapshot?.version)
            assertNull(model.ui.value.snapshot?.listenWindowId)

            // Re-selecting the saved preference still does not resume. Only resume grants playback.
            onMain { model.useSimulation(true) }
            awaitState(model, "explicit reading mode remains quiet") { !it.busy && it.simulation && it.quiet }
            assertNull(model.ui.value.snapshot?.speechPlan)
            onMain { model.resume() }
            val resumedState = awaitState(model, "explicit resume creates a new feedback plan") {
                !it.busy && !it.quiet && it.snapshot?.status == "ACTIVE" && it.snapshot.speechPlan != null
            }
            val resumed = requireNotNull(resumedState.snapshot)
            assertNotEquals(expected.getString("turn_id"), resumed.turnId)
            assertTrue(resumed.turnEpoch > restored.turnEpoch)
            assertNotNull(resumed.speechPlan)
            val resumedPlan = requireNotNull(resumed.speechPlan)
            assertNotEquals(expected.getString("playback_id"), resumedPlan.playbackId)
            assertEquals(structuralJson(expected.getJSONArray("unheard_texts")),
                resumedPlan.segments.map { it.text })
            assertPreserved(expected, resumed)
            assertFalse(resumedState.microphoneListening)

            onMain { model.simulatePlaybackCompletion() }
            val listening = awaitState(model, "new input after restored feedback was read") {
                !it.busy && !it.quiet && it.snapshot?.phase == "LISTENING" && it.snapshot.listenWindowId != null
            }
            val listeningSnapshot = requireNotNull(listening.snapshot)
            assertPreserved(expected, listeningSnapshot)
            assertEquals("p2", listeningSnapshot.game.getString("expected_player_id"))

            // Cleanup is allowed only after the complete proof succeeded.
            onMain { model.pause() }
            awaitState(model, "verified fixture is paused") { !it.busy && it.quiet }
            onMain { model.forgetFamily() }
            awaitState(model, "verified fixture removed") { !it.busy && !it.onboarded && it.snapshot == null }
            check(marker.delete())
            check(expectedFile.delete())
        } finally {
            scenario.close()
        }
    }

    private fun requireExplicitFixturePermission() {
        assumeTrue("ProcessRestoreTest resets the app: enable processDeathTests=true only on a dedicated emulator.",
            InstrumentationRegistry.getArguments().getString("processDeathTests").equals("true", ignoreCase = true))
    }

    private fun assertPreserved(expected: JSONObject, snapshot: Snapshot) {
        assertEquals(expected.getString("session_id"), snapshot.sessionId)
        assertEquals(expected.getInt("turn_index"), snapshot.game.getInt("turn_index"))
        assertEquals(structuralJson(expected.getJSONArray("history")), structuralJson(snapshot.game.getJSONArray("history")))
        assertEquals(structuralJson(expected.getJSONObject("scores")), structuralJson(snapshot.game.getJSONObject("score_by_player")))
        assertFalse(snapshot.game.getBoolean("finished"))
    }

    private fun childrenJson(children: List<ChildProfile>): JSONArray = JSONArray(children.map { child ->
        JSONObject().put("id", child.id).put("nickname", child.nickname)
            .put("interests", JSONArray(child.interests.sorted())).put("configured", child.interestsConfigured)
    })

    private fun structuralJson(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { structuralJson(value.get(it)) }
        is JSONArray -> List(value.length()) { structuralJson(value.get(it)) }
        else -> value
    }

    private fun expectedFile(context: Context) = File(context.noBackupFilesDir, "process-restore-expected.json")
    private fun readyFile(context: Context) = File(requireNotNull(context.getExternalFilesDir(null)), "process-ready.txt")

    private fun writeDurably(file: File, text: String) {
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        FileOutputStream(file).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    private fun awaitState(model: DeveloperViewModel, description: String,
        predicate: (DeveloperUiState) -> Boolean): DeveloperUiState {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = model.ui.value
            if (predicate(state)) return state
            SystemClock.sleep(25)
        }
        val state = model.ui.value
        throw AssertionError("Timed out at $description: busy=${state.busy}, quiet=${state.quiet}, " +
            "status=${state.snapshot?.status}, phase=${state.snapshot?.phase}")
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(Callable { action() })
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
