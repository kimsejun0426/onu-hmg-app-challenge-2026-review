package dev.carplaymate.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic final results inside real service answer windows; no microphone claim. */
@RunWith(AndroidJUnit4::class)
class AddressedVoiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun quotedChoicesAndQuotedCorrectionsCannotCommit() = runBlocking {
        for (text in listOf("오누야 \"숲\"", "오누야 \"물가 말고 숲\"",
                "민준 오누야 『물가 말고 숲』", "오누야 민준 (숲)")) {
            withClient { client ->
                val before = listen(client, client.create(playersBody("민준", null)))
                val after = Snapshot.from(client.command(before.sessionId, action(before, text)))
                assertEquals(text, canonicalJson(before.game), canonicalJson(after.game))
                assertEquals("ACTIVE", after.status)
                assertEquals(0, after.game.getJSONArray("history").length())
                assertNull(after.listenWindowId)
                assertEquals(listOf("그 말은 내가 잘 이해하지 못했어. 다시 물어볼게.",
                    before.presentation.getString("prompt")), requireNotNull(after.speechPlan).segments.map { it.text })
            }
        }
    }

    @Test fun aBareAddressGetsOneShortReplyWithoutCreatingAnAlwaysListeningLoop() = runBlocking {
        withClient { client ->
            var state = listen(client, client.create(playersBody("민준", null)))
            state = Snapshot.from(client.command(state.sessionId, action(state, "오누야")))
            assertEquals("응, 여기 있어.", requireNotNull(state.speechPlan).segments.first().text)
            assertEquals(0, state.game.getJSONArray("history").length())
            assertNull(state.listenWindowId)
            state = listen(client, state)
            state = Snapshot.from(client.command(state.sessionId, action(state, "오누야")))
            assertEquals("PAUSED", state.status)
            assertNull(state.speechPlan)
            assertNull(state.listenWindowId)
            assertEquals(0, state.game.getJSONArray("history").length())
        }
    }

    @Test fun twoChildrenFinishThePicnicUsingBothAddressPositions() = runBlocking {
        withClient { client ->
            var state = client.createAdventure(playersBody("민준", "서윤"), "onu-little-picnic")
            val spoken = listOf("오누야 민준 바람 언덕", "서윤, 오누야 종이 연",
                "오누야 민준 높이", "서윤, 오누야 풍경 간직하기")
            for ((index, text) in spoken.withIndex()) {
                state = listen(client, state)
                state = Snapshot.from(client.command(state.sessionId, action(state, text)))
                assertEquals(index + 1, state.game.getJSONArray("history").length())
            }
            assertTrue(state.game.getBoolean("finished"))
            assertEquals(listOf("HILL", "KITE", "HIGH", "KEEP_SCENE"),
                state.game.getJSONArray("history").objects().map { it.getString("action_id") })
            assertEquals(listOf("p1", "p2", "p1", "p2"),
                state.game.getJSONArray("history").objects().map { it.getString("player_id") })
            state = listen(client, state)
            assertEquals("ENDED", state.status)
            assertNull(state.speechPlan)
            assertNull(state.listenWindowId)
            val saved = readRecordStoreDocument(client).toString()
            spoken.forEach { assertFalse("Raw result must not be retained", saved.contains(it)) }
        }
    }

    @Test fun addressesCannotConfirmPendingChoicesButTheOriginalNameStillCan() = runBlocking {
        for (text in listOf("오누야", "민준 오누야", "오누야 민준", "민준 오누야 민준", "민준")) {
            withClient { client ->
                var state = listen(client, client.create(playersBody("민준", "서윤")))
                state = Snapshot.from(client.command(state.sessionId, action(state, "물가")))
                assertEquals("player_name", state.presentation.getString("input_kind"))
                state = listen(client, state)
                state = Snapshot.from(client.command(state.sessionId, action(state, text)))
                if (text == "민준") {
                    assertEquals(1, state.game.getJSONArray("history").length())
                    assertEquals("RIVER", state.game.getJSONArray("history").getJSONObject(0).getString("action_id"))
                } else {
                    assertEquals(text, 0, state.game.getJSONArray("history").length())
                    assertEquals(text, "PAUSED", state.status)
                    assertNull(state.speechPlan)
                    assertNull(state.listenWindowId)
                }
            }
        }
    }

    @Test fun addressedHelpersShareTheExistingBudgetAndQuietStateRejectsInput() = runBlocking {
        withClient { client ->
            var state = client.create(playersBody("민준", null))
            for (text in listOf("오누야 도와줘", "오누야 다시", "오누야 기다려")) {
                state = listen(client, state)
                state = Snapshot.from(client.command(state.sessionId, action(state, text)))
                assertEquals(0, state.game.getJSONArray("history").length())
            }
            assertEquals("PAUSED", state.status)
            assertNull(state.speechPlan)
            assertNull(state.listenWindowId)
            val before = client.snapshotDocument(state.sessionId)
            val lateInput = action(state, "오누야 계속").also {
                it.getJSONObject("payload").put("listen_window_id", uid())
            }
            reject("SESSION_PAUSED") { client.command(state.sessionId, lateInput) }
            assertEquals(canonicalJson(before), canonicalJson(client.snapshotDocument(state.sessionId)))
        }
    }

    @Test fun addressDoesNotNormalizeTheReceiptBodyOrReplayAnEffect() = runBlocking {
        withClient { client ->
            val state = listen(client, client.create(playersBody("민준", null)))
            val request = action(state, "물가")
            val original = client.command(state.sessionId, request)
            val changed = request.copyJson().also { it.getJSONObject("payload").put("text", "오누야 물가") }
            reject("COMMAND_ID_REUSED") { client.command(state.sessionId, changed) }
            assertEquals(canonicalJson(original), canonicalJson(client.command(state.sessionId, request)))
            assertEquals(canonicalJson(original), canonicalJson(client.receipt(state.sessionId, request.getString("command_id"))))
            assertEquals(1, client.snapshot(state.sessionId).game.getJSONArray("history").length())
            val quiet = requireNotNull(client.restore())
            assertEquals("PAUSED", quiet.status)
            assertEquals(canonicalJson(original), canonicalJson(client.command(state.sessionId, request)))
            assertEquals(1, client.snapshot(state.sessionId).game.getJSONArray("history").length())
        }
    }

    private suspend fun withClient(block: suspend (LocalGameClient) -> Unit) {
        val client = LocalGameClient(context, "address_test_${uid().replace("-", "")}")
        try { block(client) } finally { client.deleteAll() }
    }

    private suspend fun listen(client: LocalGameClient, state: Snapshot) = Snapshot.from(client.command(
        state.sessionId, commandEnvelope(state, uid(), "playback.completed",
            JSONObject().put("playback_id", requireNotNull(state.speechPlan).playbackId))))

    private fun action(state: Snapshot, text: String) = commandEnvelope(state, uid(), "action.submit", JSONObject()
        .put("text", text).put("is_final", true).put("capture_id", uid()).put("listen_window_id", state.listenWindowId))

    private suspend fun reject(code: String, action: suspend () -> Any?) {
        try { action(); fail("Expected $code") } catch (error: ApiException) { assertEquals(code, error.code) }
    }

    private fun uid() = UUID.randomUUID().toString()
}
