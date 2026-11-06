package dev.carplaymate.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Delivery checkpoints, not microphone/TTS integration, determine what a resume repeats. */
@RunWith(AndroidJUnit4::class)
class LocalResumeNarrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun onlyNewTwoPlayerSessionsIntroduceNameFirstAndCompletedIntroDoesNotRepeat() = runBlocking {
        val intro = "둘이 함께할 때는 내 이름을 먼저 말하고, 고른 말을 이어서 말해 줘."
        for (second in listOf(null, "서윤")) withClient { client ->
            val initial = client.create(playersBody("민준", second))
            val prompt = initial.presentation.getString("prompt")
            val initialTexts = (if (second == null) emptyList() else listOf(intro)) + prompt
            assertFalse(prompt.contains(intro))
            assertEquals(initialTexts, texts(initial))
            val restored = resume(client, pause(client, initial))
            assertEquals(initialTexts, texts(restored))
            val ready = listen(client, restored)
            val resumed = resume(client, pause(client, ready))
            assertEquals(listOf(prompt), texts(resumed))
            val repeated = submit(client, listen(client, resumed), "다시")
            assertEquals(listOf(prompt), texts(repeated))
            val chosen = submit(client, listen(client, repeated), "민준, 물가")
            assertFalse(texts(chosen).any { intro in it })
            assertEquals(1, chosen.game.getJSONArray("history").length())
        }
    }

    @Test fun completedFeedbackDoesNotReplayAndResumeReceiptIsIdempotent() = runBlocking {
        withClient { client ->
            val delivered = walk(client, listOf("RIVER"), second = null)
            val oldPlayback = requireNotNull(delivered.speechPlan).playbackId
            assertEquals(2, texts(delivered).size)
            val ready = listen(client, delivered)
            val paused = pause(client, ready)
            val request = commandEnvelope(paused, uid(), "session.resume", JSONObject())
            val response = client.command(paused.sessionId, request)
            val resumed = Snapshot.from(response)
            assertEquals(listOf(ready.presentation.getString("prompt")), texts(resumed))
            same(ready.game, resumed.game)
            assertNotEquals(oldPlayback, requireNotNull(resumed.speechPlan).playbackId)
            same(response, client.command(paused.sessionId, request))
            reject("STALE_PLAYBACK") {
                client.command(resumed.sessionId, commandEnvelope(resumed, uid(), "playback.completed",
                    JSONObject().put("playback_id", oldPlayback)))
            }
            same(resumed.game, client.snapshot(resumed.sessionId).game)
            assertEquals(resumed.version, client.snapshot(resumed.sessionId).version)
            assertEquals("LISTENING", listen(client, resumed).phase)
        }
    }

    @Test fun undeliveredFeedbackAndEndingKeepTheirTextsWithFreshPlaybackIds() = runBlocking {
        val paths = listOf(listOf("RIVER"),
            listOf("RIVER", "FOLLOW_TRACKS", "LEAVES", "MAGNIFIER", "TRICERATOPS", "WORKSHOP"))
        for (path in paths) withClient { client ->
            val pending = walk(client, path)
            val resumed = resume(client, pause(client, pending))
            assertEquals(texts(pending), texts(resumed))
            assertNotEquals(requireNotNull(pending.speechPlan).playbackId,
                requireNotNull(resumed.speechPlan).playbackId)
            same(pending.game, resumed.game)
            assertEquals("ACTIVE", resumed.status)
            val completed = listen(client, resumed)
            assertEquals(if (path.size == 6) "ENDED" else "ACTIVE", completed.status)
            assertEquals(path.size, completed.game.getJSONArray("history").length())
        }
    }

    @Test fun pausedNameConfirmationReturnsToFullQuestionWithoutRevivingTheChoice() = runBlocking {
        for (deliveredNamePrompt in listOf(false, true)) withClient { client ->
            val ready = listen(client, walk(client, listOf("RIVER")))
            var pending = submit(client, ready, "바퀴 자국")
            assertEquals("player_name", pending.presentation.getString("input_kind"))
            if (deliveredNamePrompt) pending = listen(client, pending)
            val resumed = resume(client, pause(client, pending))
            assertEquals("choice", resumed.presentation.getString("input_kind"))
            assertEquals(listOf(ready.presentation.getString("prompt")), texts(resumed))
            same(ready.game, resumed.game)
            val nameOnly = submit(client, listen(client, resumed), "서윤")
            same(ready.game, nameOnly.game)
            val chosen = submit(client, listen(client, nameOnly), "서윤, 바퀴 자국")
            assertEquals(listOf("RIVER", "FOLLOW_TRACKS"),
                chosen.game.getJSONArray("history").objects().map { it.getString("action_id") })
        }
    }

    @Test fun noResponseResumeReadsCurrentQuestionWithoutPreviousResult() = runBlocking {
        withClient { client ->
            val ready = listen(client, walk(client, listOf("RIVER"), second = null))
            val first = submit(client, ready, "딸기 케이크")
            val paused = submit(client, listen(client, first), "딸기 케이크")
            assertEquals("PAUSED", paused.status)
            assertEquals("NO_RESPONSE", client.snapshotDocument(paused.sessionId).getString("pause_reason"))
            val resumed = resume(client, paused)
            assertEquals(listOf(ready.presentation.getString("prompt")), texts(resumed))
            same(ready.game, resumed.game)
            val chosen = submit(client, listen(client, resumed), "바퀴 자국")
            assertEquals(listOf("RIVER", "FOLLOW_TRACKS"),
                chosen.game.getJSONArray("history").objects().map { it.getString("action_id") })
        }
    }

    @Test fun interruptedWaitResumeRestoresQuestionOnceWithoutApplyingAction() = runBlocking {
        for (second in listOf(null, "서윤")) withClient { client ->
            val path = if (second == null) emptyList() else listOf("RIVER")
            val answer = if (second == null) "물가" else "서윤, 바퀴 자국"
            val ready = listen(client, walk(client, path, second))
            var waiting = submit(client, ready, "기다려")
            val waitText = "천천히 생각해도 괜찮아."
            val prompt = ready.presentation.getString("prompt")
            assertEquals(listOf(waitText), texts(waiting))
            same(ready.game, waiting.game)
            assertNull(waiting.listenWindowId)
            // Interrupt the WAIT and then its recovery before either plan completes.
            // The current question belongs at the end exactly once, without any choice.
            repeat(2) {
                val oldPlayback = requireNotNull(waiting.speechPlan).playbackId
                val paused = pause(client, waiting)
                assertNull(paused.speechPlan)
                assertNull(paused.listenWindowId)
                waiting = resume(client, paused)
                assertEquals(listOf(waitText, prompt), texts(waiting))
                assertNotEquals(oldPlayback, requireNotNull(waiting.speechPlan).playbackId)
                same(ready.game, waiting.game)
                assertNull(waiting.listenWindowId)
            }
            val listening = listen(client, waiting)
            assertEquals("LISTENING", listening.phase)
            assertNotEquals(ready.listenWindowId, listening.listenWindowId)
            val chosen = submit(client, listening, answer)
            assertEquals(path + if (path.isEmpty()) listOf("RIVER") else listOf("FOLLOW_TRACKS"),
                chosen.game.getJSONArray("history").objects().map { it.getString("action_id") })
        }
    }

    @Test fun deliveredWaitStaysShortAndDoesNotReplayOnResume() = runBlocking {
        withClient { client ->
            val ready = listen(client, client.create(playersBody("민준", null)))
            val waiting = submit(client, ready, "기다려")
            assertEquals(listOf("천천히 생각해도 괜찮아."), texts(waiting))
            val listening = listen(client, waiting)
            assertEquals("LISTENING", listening.phase)
            same(ready.game, listening.game)
            val resumed = resume(client, pause(client, listening))
            assertEquals(listOf(ready.presentation.getString("prompt")), texts(resumed))
        }
    }

    @Test fun persistedWaitResumeUsesSavedEditionWithoutNewStateFields() = runBlocking {
        val storageName = "resume_saved_wait_${uid().replace("-", "")}"
        val client = LocalGameClient(context, storageName)
        try {
            val ready = listen(client, client.create(playersBody("민준", null)))
            val paused = pause(client, ready)
            val legacy = LocalAdventureLibrary(context).rules(paused.game.getString("pack_id"), "0.1.0")
            val savedGame = legacy.newGame(playersBody("민준", null).getJSONArray("players"))
            val savedPrompt = legacy.present(savedGame).getString("prompt")
            assertNotEquals(ready.presentation.getString("prompt"), savedPrompt)
            // Seed the existing paused WAIT shape independently of today's WAIT handler.
            // Its only pending narration is the old reassurance; no new flag is present.
            val document = readRecordStoreDocument(client)
            val state = document.getJSONObject("sessions").getJSONObject(paused.sessionId).getJSONObject("state")
            state.put("game", savedGame).put("_unheard_texts", JSONArray().put("천천히 생각해도 괜찮아."))
                .put("_recovery_requests", 1)
            assertFalse(state.has("_pending_action"))
            writeRecordStoreDocument(client, document)
            val reopened = LocalGameClient(context, storageName)
            val restored = requireNotNull(reopened.restore(paused.sessionId))
            assertEquals("PAUSED", restored.status)
            assertEquals(paused.version, restored.version)
            assertNull(restored.speechPlan)
            assertNull(restored.listenWindowId)
            val resumed = resume(reopened, restored)
            assertEquals(listOf("천천히 생각해도 괜찮아.", savedPrompt), texts(resumed))
            same(savedGame, resumed.game)
            assertEquals("LISTENING", listen(reopened, resumed).phase)
        } finally {
            client.deleteAll()
        }
    }

    private suspend fun walk(client: LocalGameClient, path: List<String>, second: String? = "서윤"): Snapshot {
        var snapshot = client.create(playersBody("민준", second))
        for (actionId in path) {
            snapshot = listen(client, snapshot)
            val expected = snapshot.game.getString("expected_player_id")
            val nickname = snapshot.game.getJSONArray("players").objects()
                .single { it.getString("player_id") == expected }.getString("nickname")
            snapshot = submit(client, snapshot, "${nickname}, ${actionId}")
        }
        return snapshot
    }

    private suspend fun withClient(block: suspend (LocalGameClient) -> Unit) {
        val client = LocalGameClient(context, "resume_narration_${uid().replace("-", "")}")
        try { block(client) } finally { client.deleteAll() }
    }

    private suspend fun listen(client: LocalGameClient, snapshot: Snapshot): Snapshot =
        Snapshot.from(client.command(snapshot.sessionId, commandEnvelope(snapshot, uid(), "playback.completed",
            JSONObject().put("playback_id", requireNotNull(snapshot.speechPlan).playbackId))))

    private suspend fun pause(client: LocalGameClient, snapshot: Snapshot): Snapshot =
        Snapshot.from(client.command(snapshot.sessionId, commandEnvelope(snapshot, uid(), "session.pause",
            JSONObject().put("reason", "QUIET_REQUEST"))))

    private suspend fun resume(client: LocalGameClient, snapshot: Snapshot): Snapshot =
        Snapshot.from(client.command(snapshot.sessionId, commandEnvelope(snapshot, uid(), "session.resume", JSONObject())))

    private suspend fun submit(client: LocalGameClient, snapshot: Snapshot, text: String): Snapshot =
        Snapshot.from(client.command(snapshot.sessionId, commandEnvelope(snapshot, uid(), "action.submit",
            JSONObject().put("text", text).put("is_final", true).put("capture_id", uid())
                .put("listen_window_id", snapshot.listenWindowId))))

    private fun texts(snapshot: Snapshot) = requireNotNull(snapshot.speechPlan).segments.map { it.text }
    private fun same(expected: JSONObject, actual: JSONObject) =
        assertEquals(canonicalJson(expected), canonicalJson(actual))
    private suspend fun reject(code: String, block: suspend () -> Any?) {
        try { block(); fail("Expected $code") } catch (error: ApiException) { assertEquals(code, error.code) }
    }
    private fun uid() = UUID.randomUUID().toString()
}
