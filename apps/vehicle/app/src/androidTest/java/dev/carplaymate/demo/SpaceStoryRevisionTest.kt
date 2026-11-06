package dev.carplaymate.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Authored text and synthetic final results, not microphone or child-listening evidence. */
@RunWith(AndroidJUnit4::class)
class SpaceStoryRevisionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library by lazy { LocalAdventureLibrary(context) }
    private val packId = "space-return-signal"

    @Test fun bothPlayerCountsRecallAllSixteenObservationRecordAndSharingCombinations() {
        val rules = library.rules(packId, "0.8.0")
        val oldRules = library.rules(packId, "0.7.0")
        for (second in listOf(null, "서윤")) for (mission in listOf("MOON", "MARS")) {
            for (tool in listOf("PANORAMA", "CLOSE_CAMERA")) for (record in listOf("PHOTO", "NOTE")) {
                for (sharing in listOf("MAKE_GALLERY", "TELL_STORY")) {
                    val path = listOf(mission, tool, record, "READ_CHECKLIST", "CLOSE_DOOR", sharing)
                    val state = walk(rules, path, second)
                    val before = canonicalJson(state)
                    val text = rules.present(state).getString("prompt")
                    val subject = if (mission == "MOON") "달의 충돌구를" else "화성의 붉은 먼지를"
                    val view = if (tool == "PANORAMA") "한눈에 본" else "가까이 본"
                    val memory = if (record == "PHOTO") "사진에는 $subject $view 모습이 담겼어."
                        else "노트에는 $subject $view 이야기가 적혔어."
                    assertTrue(text.contains(memory))
                    assertFalse(text.contains(if (mission == "MOON") "화성" else "달"))
                    assertFalse(text.contains(if (tool == "PANORAMA") "가까이 본" else "한눈에 본"))
                    assertFalse(text.contains(if (record == "PHOTO") "노트" else "사진"))
                    assertEquals(3, text.count { it == '.' })
                    assertFalse(text.contains('{') || text.contains('}'))
                    if (sharing == "MAKE_GALLERY") {
                        assertEquals("MISSION_GALLERY", state.getString("ending"))
                        assertTrue(text.contains("전시를 열었어"))
                        assertFalse(text.contains("들려줬어"))
                    } else {
                        assertEquals("MISSION_STORY", state.getString("ending"))
                        assertTrue(text.contains("들려줬어"))
                        assertFalse(text.contains("전시"))
                    }
                    assertEquals(6, state.getInt("turn_index"))
                    val otherTool = if (tool == "PANORAMA") "CLOSE_CAMERA" else "PANORAMA"
                    val contrast = listOf(mission, otherTool, record, "READ_CHECKLIST", "CLOSE_DOOR", sharing)
                    assertFalse(text == rules.present(walk(rules, contrast, second)).getString("prompt"))
                    // The old edition must keep its original generic ending, including this limitation.
                    assertEquals(oldRules.present(walk(oldRules, path, second)).getString("prompt"),
                        oldRules.present(walk(oldRules, contrast, second)).getString("prompt"))
                    assertEquals(before, canonicalJson(state))
                }
            }
        }
    }

    @Test fun sixQuestionsKeepNamesNeutralRolesRecordParticlesAndBoardingBeforeDeparture() {
        val rules = library.rules(packId, "0.8.0")
        val paths = listOf(
            listOf("MOON", "PANORAMA", "PHOTO", "READ_CHECKLIST", "CLOSE_DOOR", "TELL_STORY"),
            listOf("MARS", "CLOSE_CAMERA", "NOTE", "COUNT_STEPS", "SEND_SIGNAL", "TELL_STORY"),
        )
        for (second in listOf(null, "서윤")) for (path in paths) {
            var state = rules.newGame(playersBody("민준", second).getJSONArray("players"))
            for ((index, action) in path.withIndex()) {
                val playerId = if (second == null || index % 2 == 0) "p1" else "p2"
                val name = if (playerId == "p1") "민준" else "서윤"
                assertEquals(playerId, state.getString("expected_player_id"))
                val shown = rules.present(state)
                val text = shown.getString("prompt")
                val prefix = if (second == null) "$name, " else "$name 탐사 친구 차례야. "
                assertTrue(text.startsWith(prefix))
                assertEquals(1, text.count { it == '?' })
                assertFalse(text.contains("관측 대장") || text.contains("귀환 대장"))
                if (second != null) {
                    assertEquals("탐사 친구", shown.getJSONObject("roles").getString("p1"))
                    assertEquals("탐사 친구", shown.getJSONObject("roles").getString("p2"))
                }
                if (state.getString("scene_id") == "return_order") {
                    val feedback = state.getString("feedback")
                    assertTrue(feedback.startsWith("돌아갈 우주선에 토리와 오누가 함께 탔어."))
                    assertTrue(feedback.indexOf("함께 탔어") < feedback.indexOf("문 닫기"))
                    assertTrue(feedback.indexOf("문 닫기") < feedback.indexOf("출발 신호"))
                    assertTrue(text.contains("우주선의 문이 아직 열려"))
                    assertFalse(text.contains("토리의 문"))
                    assertTrue(shown.getString("hint").contains("함께 탔으니"))
                }
                if (state.getString("scene_id") == "share_record") {
                    assertTrue(text.contains(if (path[2] == "PHOTO") "풍경 사진을 토리가" else "관찰 노트를 토리가"))
                    assertFalse(text.contains("노트을"))
                    val feedback = state.getString("feedback")
                    assertFalse(feedback.contains("탔어"))
                    assertTrue(feedback.contains(if (path[4] == "CLOSE_DOOR") "문을 닫고 출발 신호" else "문부터 닫고 신호"))
                }
                state = rules.applyAction(state, playerId, action)
            }
            val summary = rules.renderMetadata(rules.sceneMetadata(state).getString("summary"), state)
            assertTrue(summary.contains(if (path[2] == "PHOTO") "풍경 사진을 보며" else "관찰 노트를 보며"))
            assertEquals(if (second == null) List(6) { "p1" } else listOf("p1", "p2", "p1", "p2", "p1", "p2"),
                state.getJSONArray("history").objects().map { it.getString("player_id") })
        }
    }

    @Test fun exactQuestionAnswersBelongOnlyToTheNewEditionAndKeepVoiceBoundaries() {
        val rules = library.rules(packId, "0.8.0")
        val oldRules = library.rules(packId, "0.7.0")
        val path = listOf("MOON", "PANORAMA", "PHOTO", "READ_CHECKLIST")
        for (second in listOf(null, "서윤")) {
            val state = walk(rules, path, second)
            val previous = walk(oldRules, path, second)
            val before = canonicalJson(state)
            for ((text, actionId, other) in listOf(
                Triple("문 닫기부터", "CLOSE_DOOR", "출발 신호부터"),
                Triple("출발 신호부터", "SEND_SIGNAL", "문 닫기부터"))) {
                val utterances = mutableListOf("민준, $text", "오누야 민준, $text", "민준, 오누야 $text")
                if (second == null) utterances.addAll(listOf(text, "오누야 $text"))
                for (utterance in utterances) {
                    val candidate = rules.interpret(utterance, state)
                    assertEquals("ACTION", candidate.intent)
                    assertEquals("p1", candidate.declaredPlayerId)
                    assertEquals(actionId, candidate.actionId)
                    assertFalse(candidate.needsClarification)
                    val rejected = oldRules.interpret(utterance, previous)
                    assertTrue(rejected.needsClarification)
                    assertNull(rejected.actionId)
                }
                for (body in listOf("$text 그리고 $other", "$text 하지 마", "$text 말고", "$text $text",
                    "어제 ${text}라고 했어", "$text 다시")) {
                    val candidate = rules.interpret("민준, $body", state)
                    assertTrue(body, candidate.needsClarification)
                    assertNull(body, candidate.actionId)
                }
                val quoted = rules.interpret("오누야 민준, \"$text\"", state)
                assertEquals("unconfirmed_expansion", quoted.reason)
                assertNull(quoted.actionId)
                if (second != null) {
                    assertEquals("missing_player", rules.interpret(text, state).reason)
                    assertEquals("wrong_player", rules.interpret("서윤, $text", state).reason)
                    assertEquals("multiple_players", rules.interpret("민준, 서윤, $text", state).reason)
                }
            }
            for ((text, intent) in listOf("그만" to "PAUSE", "다시" to "REPEAT", "기다려 줘" to "WAIT",
                "도와줘" to "HINT", "계속" to "RESUME", "끝내기" to "END")) {
                val candidate = rules.interpret(text, state)
                assertEquals(intent, candidate.intent)
                assertNull(candidate.actionId)
            }
            assertEquals(before, canonicalJson(state))
        }
    }

    @Test fun quietRestoreResumeAndReceiptRetryKeepTheObservationUntilEndingPlaybackCompletes() = runBlocking {
        for (second in listOf(null, "서윤")) {
            val storageName = "space_revision_${uid().replace("-", "")}"
            val initialClient = LocalGameClient(context, storageName)
            try {
                val path = if (second == null) listOf("MOON", "PANORAMA", "PHOTO", "READ_CHECKLIST")
                    else listOf("MARS", "CLOSE_CAMERA", "NOTE", "COUNT_STEPS")
                val spoken = mutableListOf<String>()
                var state = initialClient.createAdventure(playersBody("민준", second), packId)
                assertEquals(ContentReleaseExpectations.currentVersions.getValue(packId), state.game.getString("pack_version"))
                for (actionId in path) {
                    state = listen(initialClient, state)
                    val text = addressed(state, actionId, second)
                    spoken.add(text)
                    state = Snapshot.from(initialClient.command(state.sessionId, action(state, text)))
                }
                assertEquals(4, state.game.getJSONArray("history").length())
                // Acknowledge boarding and the question, then explicitly pause the answer window.
                state = listen(initialClient, state)
                val rememberedGame = canonicalJson(state.game)
                val prompt = state.presentation.getString("prompt")
                val paused = Snapshot.from(initialClient.command(state.sessionId, commandEnvelope(state, uid(),
                    "session.pause", JSONObject().put("reason", "QUIET_REQUEST"))))
                assertQuiet(paused)

                val client = LocalGameClient(context, storageName)
                val restored = requireNotNull(client.restore(paused.sessionId))
                assertQuiet(restored)
                assertEquals(rememberedGame, canonicalJson(restored.game))
                assertEquals(4, restored.game.getJSONArray("history").length())
                state = Snapshot.from(client.command(restored.sessionId,
                    commandEnvelope(restored, uid(), "session.resume", JSONObject())))
                assertEquals("ACTIVE", state.status)
                assertNull(state.listenWindowId)
                assertEquals(listOf(prompt), requireNotNull(state.speechPlan).segments.map { it.text })
                state = listen(client, state)

                val orderText = if (second == null) "문 닫기부터" else "출발 신호부터"
                val text = addressed(state, orderText, second)
                spoken.add(text)
                val request = action(state, text)
                val response = client.command(state.sessionId, request)
                state = Snapshot.from(response)
                assertEquals(5, state.game.getJSONArray("history").length())
                assertEquals(if (second == null) "CLOSE_DOOR" else "SEND_SIGNAL",
                    state.game.getJSONArray("history").getJSONObject(4).getString("action_id"))
                assertEquals(canonicalJson(response), canonicalJson(client.command(state.sessionId, request)))
                assertEquals(canonicalJson(response), canonicalJson(client.receipt(state.sessionId, request.getString("command_id"))))
                assertEquals(5, client.snapshot(state.sessionId).game.getJSONArray("history").length())

                state = listen(client, state)
                val sharing = if (second == null) "MAKE_GALLERY" else "TELL_STORY"
                val lastText = addressed(state, sharing, second)
                spoken.add(lastText)
                state = Snapshot.from(client.command(state.sessionId, action(state, lastText)))
                assertTrue(state.game.getBoolean("finished"))
                assertEquals("ACTIVE", state.status)
                assertEquals("FEEDBACK", state.phase)
                assertNull(state.listenWindowId)
                val memory = if (second == null) "사진에는 달의 충돌구를 한눈에 본 모습이 담겼어."
                    else "노트에는 화성의 붉은 먼지를 가까이 본 이야기가 적혔어."
                val ending = state.presentation.getString("prompt")
                assertTrue(ending.contains(memory))
                assertEquals(ending, requireNotNull(state.speechPlan).segments.last().text)
                assertEquals(path + listOf(if (second == null) "CLOSE_DOOR" else "SEND_SIGNAL", sharing),
                    state.game.getJSONArray("history").objects().map { it.getString("action_id") })

                val ended = listen(client, state)
                assertEquals("ENDED", ended.status)
                assertNull(ended.speechPlan)
                assertNull(ended.listenWindowId)
                assertEquals(canonicalJson(state.game), canonicalJson(ended.game))
                assertTrue(ended.presentation.getString("prompt").contains(memory))
                val saved = readRecordStoreDocument(client).toString()
                spoken.forEach { assertFalse("Synthetic utterance text must not be retained", saved.contains(it)) }
            } finally {
                initialClient.deleteAll()
            }
        }
    }

    private fun walk(rules: LocalGameEngine, path: List<String>, second: String?): JSONObject {
        var state = rules.newGame(playersBody("민준", second).getJSONArray("players"))
        for (actionId in path) state = rules.applyAction(state, state.getString("expected_player_id"), actionId)
        return state
    }

    private fun addressed(state: Snapshot, body: String, second: String?): String {
        if (second == null) return "오누야 $body"
        val name = if (state.game.getString("expected_player_id") == "p1") "민준" else "서윤"
        return "오누야 $name, $body"
    }

    private fun assertQuiet(state: Snapshot) {
        assertEquals("PAUSED", state.status)
        assertNull(state.speechPlan)
        assertNull(state.listenWindowId)
    }

    private suspend fun listen(client: LocalGameClient, state: Snapshot) = Snapshot.from(client.command(state.sessionId,
        commandEnvelope(state, uid(), "playback.completed", JSONObject().put("playback_id", requireNotNull(state.speechPlan).playbackId))))

    private fun action(state: Snapshot, text: String) = commandEnvelope(state, uid(), "action.submit", JSONObject()
        .put("text", text).put("is_final", true).put("capture_id", uid()).put("listen_window_id", state.listenWindowId))

    private fun uid() = UUID.randomUUID().toString()
}
