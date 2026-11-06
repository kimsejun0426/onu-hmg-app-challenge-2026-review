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

/** Real local command storage, with unique test stores and no microphone or TTS. */
@RunWith(AndroidJUnit4::class)
class LocalSpeechRepairTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun unfinishedAndMixedOwnerCorrectionsCannotConfirmThePendingChoice() = runBlocking {
        for (text in listOf("민준, 물가 말고", "민준, 아니", "민준, 물가 말고 민준",
            "민준, 물가 서윤 말고 숲", "민준, 서윤 물가 말고 숲")) {
            withClient { client ->
                val initial = listen(client, client.create(playersBody("민준", "서윤")))
                val pending = submit(client, initial, "물가")
                assertTrue(pending.speechPlan!!.segments.single().text.contains("이름만"))
                assertEquals("player_name", pending.presentation.getString("input_kind"))
                val ready = listen(client, pending)
                val request = action(ready, text)
                val response = client.command(ready.sessionId, request)
                val repaired = Snapshot.from(response)
                same("repair cannot alter game", initial.game, repaired.game)
                assertEquals(initial.turnId, repaired.turnId)
                assertEquals(initial.turnEpoch + 1, repaired.turnEpoch)
                assertEquals("PAUSED", repaired.status)
                assertEquals("NO_RESPONSE", client.snapshotDocument(repaired.sessionId).getString("pause_reason"))
                assertNull(repaired.speechPlan)
                assertNull(repaired.listenWindowId)
                assertEquals("choice", repaired.presentation.getString("input_kind"))
                same("retry cannot add an effect", response, client.command(ready.sessionId, request))
                reject("STALE_VERSION") { client.command(ready.sessionId, action(ready, "민준, 물가")) }
                val resumed = Snapshot.from(client.command(repaired.sessionId,
                    commandEnvelope(repaired, uid(), "session.resume", JSONObject())))
                val nameOnly = submit(client, listen(client, resumed), "민준")
                same("discarded choice cannot return through a later name", initial.game, nameOnly.game)
                val chosen = submit(client, listen(client, nameOnly), "민준, 물가 말고 숲")
                assertEquals(listOf("FOREST"), chosen.game.getJSONArray("history").objects().map { it.getString("action_id") })
                assertEquals("p2", chosen.game.getString("expected_player_id"))
            }
        }
    }

    @Test fun refusalPausesImmediatelyDiscardsPendingAndRejectsLateInputAndPlayback() = runBlocking {
        withClient { client ->
            val initial = listen(client, client.create(playersBody("민준", "서윤")))
            val pending = submit(client, initial, "물가")
            val ready = listen(client, pending)
            val request = action(ready, "서윤, 하기 싫어")
            val response = client.command(ready.sessionId, request)
            val paused = Snapshot.from(response)
            assertEquals("PAUSED", paused.status)
            assertEquals("QUIET_REQUEST", client.snapshotDocument(paused.sessionId).getString("pause_reason"))
            same("refusal retains scores, resources and turn", initial.game, paused.game)
            assertEquals(initial.turnId, paused.turnId)
            assertEquals(ready.turnEpoch + 1, paused.turnEpoch)
            assertNull(paused.speechPlan)
            assertNull(paused.listenWindowId)
            assertTrue(client.snapshotDocument(paused.sessionId).getBoolean("requires_explicit_resume"))
            same("duplicate stop applies once", response, client.command(ready.sessionId, request))
            reject("STALE_VERSION") { client.command(ready.sessionId, action(ready, "민준")) }
            val late = action(ready, "민준").put("expected_version", paused.version).put("turn_epoch", paused.turnEpoch)
            reject("SESSION_PAUSED") { client.command(paused.sessionId, late) }
            assertEquals("PAUSED", client.snapshot(paused.sessionId).status)
            val resumed = Snapshot.from(client.command(paused.sessionId, commandEnvelope(paused, uid(), "session.resume", JSONObject())))
            reject("STALE_PLAYBACK") {
                client.command(resumed.sessionId, commandEnvelope(resumed, uid(), "playback.completed",
                    JSONObject().put("playback_id", pending.speechPlan!!.playbackId)))
            }
            val nameOnly = submit(client, listen(client, resumed), "민준")
            same("explicit resume cannot restore discarded choice", initial.game, nameOnly.game)
        }
        withClient { client ->
            val initial = listen(client, client.create(playersBody("민준", "서윤")))
            val pending = submit(client, initial, "물가")
            val paused = Snapshot.from(client.command(pending.sessionId, commandEnvelope(pending, uid(), "session.pause",
                JSONObject().put("reason", "QUIET_REQUEST"))))
            val resumed = Snapshot.from(client.command(paused.sessionId, commandEnvelope(paused, uid(), "session.resume", JSONObject())))
            assertEquals(listOf(initial.presentation.getString("prompt")), resumed.speechPlan!!.segments.map { it.text })
            val nameOnly = submit(client, listen(client, resumed), "민준")
            same("unheard name prompt becomes a complete choice prompt", initial.game, nameOnly.game)
        }
    }

    @Test fun waitPreservesNameConfirmationWithinTheSharedBudget() = runBlocking {
        for (waits in listOf(2, 3)) withClient { client ->
            val initial = listen(client, client.create(playersBody("민준", "서윤")))
            var pending = submit(client, initial, "물가")
            repeat(waits) { index ->
                val ready = listen(client, pending)
                val request = action(ready, "기다려")
                val response = client.command(ready.sessionId, request)
                pending = Snapshot.from(response)
                same("duplicate wait is free", response, client.command(ready.sessionId, request))
                same("wait does not advance", initial.game, pending.game)
                if (index < 2) {
                    assertEquals("player_name", pending.presentation.getString("input_kind"))
                    assertEquals(listOf("천천히 생각해도 괜찮아. 누구의 선택인지 이름만 말해 줄래?"),
                        pending.speechPlan!!.segments.map { it.text })
                }
            }
            if (waits == 2) {
                val ready = listen(client, pending)
                assertFalse(initial.listenWindowId == ready.listenWindowId)
                val chosen = submit(client, ready, "민준")
                assertEquals(listOf("RIVER"), chosen.game.getJSONArray("history").objects().map { it.getString("action_id") })
                assertEquals("p2", chosen.game.getString("expected_player_id"))
            } else {
                assertEquals("PAUSED", pending.status)
                assertEquals("choice", pending.presentation.getString("input_kind"))
                assertNull(pending.speechPlan)
                assertNull(pending.listenWindowId)
                val resumed = Snapshot.from(client.command(pending.sessionId,
                    commandEnvelope(pending, uid(), "session.resume", JSONObject())))
                val nameOnly = submit(client, listen(client, resumed), "민준")
                same("budget pause discards tentative choice", initial.game, nameOnly.game)
                assertEquals("choice", nameOnly.presentation.getString("input_kind"))
            }
        }
    }

    @Test fun repeatDuringNameConfirmationKeepsQuestionChoiceAndOriginalReceipt() = runBlocking {
        for (afterFirstChoice in listOf(false, true)) withClient { client ->
            var initial = client.create(playersBody("민준", "서윤"))
            if (afterFirstChoice) initial = submit(client, listen(client, initial), "민준 물가")
            initial = listen(client, initial)
            val choice = if (afterFirstChoice) "바퀴 자국" else "물가"
            val name = if (afterFirstChoice) "서윤" else "민준"
            val expectedActions = if (afterFirstChoice) listOf("RIVER", "FOLLOW_TRACKS") else listOf("RIVER")
            val pending = submit(client, initial, choice)
            val question = pending.speechPlan!!.segments.map { it.text }
            val ready = listen(client, pending)
            val request = action(ready, "다시 말해줘")
            val response = client.command(ready.sessionId, request)
            val repeated = Snapshot.from(response)
            assertEquals("player_name", repeated.presentation.getString("input_kind"))
            assertEquals(question, repeated.speechPlan!!.segments.map { it.text })
            same("repeat preserves the unconfirmed choice and game", initial.game, repeated.game)
            assertEquals(initial.turnId, repeated.turnId)
            assertEquals(initial.turnEpoch, repeated.turnEpoch)
            assertNull(repeated.listenWindowId)
            assertFalse(pending.speechPlan!!.playbackId == repeated.speechPlan!!.playbackId)
            same("duplicate repeat does not spend another request", response, client.command(ready.sessionId, request))
            val chosen = submit(client, listen(client, repeated), name)
            assertEquals(expectedActions, chosen.game.getJSONArray("history").objects().map { it.getString("action_id") })
            same("old repeat receipt remains unchanged after the choice", response, client.command(ready.sessionId, request))
            same("old receipt cannot roll back progress", chosen.game, client.snapshot(chosen.sessionId).game)
        }
    }

    @Test fun nameRepeatAndWaitShareBudgetWithoutGuessingTheOwner() = runBlocking {
        for (controls in listOf(listOf("다시 말해줘", "기다려"), listOf("기다려", "다시 말해줘"),
            listOf("기다려", "다시 말해줘", "다시 말해줘"))) withClient { client ->
            val initial = listen(client, client.create(playersBody("민준", "서윤")))
            var pending = submit(client, initial, "물가")
            for ((index, control) in controls.withIndex()) {
                pending = submit(client, listen(client, pending), control)
                same("control cannot confirm an owner or advance", initial.game, pending.game)
                if (index < 2) {
                    assertEquals("player_name", pending.presentation.getString("input_kind"))
                    assertTrue(pending.speechPlan!!.segments.single().text.contains("이름만"))
                }
            }
            if (controls.size == 2) {
                val chosen = submit(client, listen(client, pending), "민준")
                assertEquals(listOf("RIVER"), chosen.game.getJSONArray("history").objects().map { it.getString("action_id") })
            } else {
                assertEquals("PAUSED", pending.status)
                assertEquals("NO_RESPONSE", client.snapshotDocument(pending.sessionId).getString("pause_reason"))
                assertEquals("choice", pending.presentation.getString("input_kind"))
                assertNull(pending.speechPlan)
                assertNull(pending.listenWindowId)
                val resumed = Snapshot.from(client.command(pending.sessionId,
                    commandEnvelope(pending, uid(), "session.resume", JSONObject())))
                assertEquals(listOf(initial.presentation.getString("prompt")), resumed.speechPlan!!.segments.map { it.text })
                val nameOnly = submit(client, listen(client, resumed), "민준")
                same("budget pause still discards the tentative choice", initial.game, nameOnly.game)
            }
        }
    }

    @Test fun hintOrStopAfterNameRepeatStillDiscardsThePendingChoice() = runBlocking {
        for (control in listOf("모르겠어", "쉬자")) withClient { client ->
            val initial = listen(client, client.create(playersBody("민준", "서윤")))
            val pending = submit(client, initial, "물가")
            val repeated = submit(client, listen(client, pending), "다시 말해줘")
            assertEquals("player_name", repeated.presentation.getString("input_kind"))
            var changed = submit(client, listen(client, repeated), control)
            assertEquals("choice", changed.presentation.getString("input_kind"))
            if (control == "쉬자") {
                assertEquals("PAUSED", changed.status)
                assertEquals("QUIET_REQUEST", client.snapshotDocument(changed.sessionId).getString("pause_reason"))
                changed = Snapshot.from(client.command(changed.sessionId,
                    commandEnvelope(changed, uid(), "session.resume", JSONObject())))
            }
            assertEquals(initial.presentation.getString("prompt"), changed.speechPlan!!.segments.last().text)
            val nameOnly = submit(client, listen(client, changed), "민준")
            assertEquals(0, nameOnly.game.getJSONArray("history").length())
            assertEquals("p1", nameOnly.game.getString("expected_player_id"))
        }
    }

    @Test fun notKnowingUsesOneHintWithoutChangingTurnOrConfirmingAPendingChoice() = runBlocking {
        withClient { client ->
            val initial = listen(client, client.create(playersBody("민준", "서윤")))
            val pending = submit(client, initial, "물가")
            val hinted = submit(client, listen(client, pending), "몰라")
            assertEquals("choice", hinted.presentation.getString("input_kind"))
            for (key in listOf("history", "score_by_player", "turn_index", "expected_player_id", "scene_id")) {
                assertEquals(key, canonicalJson(initial.game.get(key)), canonicalJson(hinted.game.get(key)))
            }
            assertEquals(initial.turnId, hinted.turnId)
            assertEquals(initial.turnEpoch, hinted.turnEpoch)
            assertEquals(initial.game.getJSONObject("resources").getInt("hint_tokens") - 1,
                hinted.game.getJSONObject("resources").getInt("hint_tokens"))
            val repeated = submit(client, listen(client, hinted), "잘 몰라")
            same("repeat hint is free", hinted.game, repeated.game)
            val nameOnly = submit(client, listen(client, repeated), "민준")
            same("hint cancels pending choice", repeated.game, nameOnly.game)
            assertEquals("PAUSED", nameOnly.status)
            assertNull(nameOnly.speechPlan)
            val resumed = Snapshot.from(client.command(nameOnly.sessionId,
                commandEnvelope(nameOnly, uid(), "session.resume", JSONObject())))
            val negative = submit(client, listen(client, resumed), "안 할래라고 말하면 돼")
            assertEquals("ACTIVE", negative.status)
            same("control substrings are not commands", nameOnly.game, negative.game)
        }
    }

    @Test fun repeatAfterChoiceKeepsTheCompleteQuestionWithoutPreviousFeedback() = runBlocking {
        withClient { client ->
            val initial = listen(client, client.create(playersBody("민준", "서윤")))
            val chosen = submit(client, initial, "민준 물가")
            val feedback = chosen.game.getString("feedback")
            val prompt = chosen.presentation.getString("prompt")
            assertTrue(feedback.isNotEmpty())
            assertEquals(listOf(feedback, prompt), chosen.speechPlan!!.segments.map { it.text })
            val ready = listen(client, chosen)
            val request = action(ready, "다시")
            val response = client.command(ready.sessionId, request)
            val repeated = Snapshot.from(response)
            assertEquals(listOf(prompt), repeated.speechPlan!!.segments.map { it.text })
            same("repeat cannot advance or change resources", ready.game, repeated.game)
            assertEquals(ready.turnId, repeated.turnId)
            assertEquals(ready.turnEpoch, repeated.turnEpoch)
            same("duplicate repeat cannot add an effect", response, client.command(ready.sessionId, request))
        }
    }

    @Test fun unsupportedReasonGetsHonestRepairWithoutApplyingOrEchoingIt() = runBlocking {
        val cases = listOf(
            Triple("민준 숲으로 가자 나 지우개가 빨간색이라서",
                "그 말은 내가 잘 이해하지 못했어. 다시 물어볼게.", "지우개"),
            Triple("민준 우주선을 꺼내",
                "어느 쪽인지 내가 잘 모르겠어. 다시 물어볼게.", "우주선"),
        )
        for ((text, prefix, marker) in cases) withClient { client ->
            val initial = listen(client, client.create(playersBody("민준", "서윤")))
            val repaired = submit(client, initial, text)
            same("uninterpreted reason cannot advance or change resources", initial.game, repaired.game)
            assertEquals(initial.turnId, repaired.turnId)
            assertEquals(initial.turnEpoch, repaired.turnEpoch)
            assertEquals("choice", repaired.presentation.getString("input_kind"))
            assertEquals(listOf(prefix, initial.presentation.getString("prompt")),
                repaired.speechPlan!!.segments.map { it.text })
            assertFalse(client.snapshotDocument(repaired.sessionId).toString().contains(marker))
            val chosen = submit(client, listen(client, repaired), "민준 숲")
            assertEquals(listOf("FOREST"), chosen.game.getJSONArray("history").objects().map { it.getString("action_id") })
        }
    }

    @Test fun exactChoiceNicknameCollisionAndHistoricalNicknamesRemainUsable() {
        val library = LocalAdventureLibrary(context)
        for (version in listOf("0.1.0", "0.2.0", "0.3.0", "0.4.0", "0.5.0", "0.6.0")) {
            val starRules = library.rules("starlight-post-office", version)
            var star = starRules.newGame(playersBody("달", "별").getJSONArray("players"))
            for (choice in listOf("MOON_BRIDGE", "LOOK_STAMP", "WALK_TOGETHER")) {
                star = starRules.applyAction(star, star.getString("expected_player_id"), choice)
            }
            for (text in listOf("별, 별 등", "별, 별등", "별, 바람종 말고 별 등")) {
                val candidate = starRules.interpret(text, star)
                assertFalse(text, candidate.needsClarification)
                assertEquals("p2", candidate.declaredPlayerId)
                assertEquals("STAR_LANTERN", candidate.actionId)
            }
            val mixed = starRules.interpret("별, 바람종 말고 달, 별 등", star)
            assertTrue(mixed.needsClarification)
            assertEquals("multiple_players", mixed.reason)
            val unnamed = starRules.interpret("바람종 말고 별 등", star)
            assertTrue(unnamed.needsClarification)
            assertEquals("missing_player", unnamed.reason)
            assertNull(unnamed.declaredPlayerId)
            assertEquals("STAR_LANTERN", unnamed.actionId)

            val rules = library.rules("dinosaur-lab-missing-egg", version)
            val oldName = rules.newGame(playersBody("몰라", null).getJSONArray("players"))
            val candidate = rules.interpret("몰라, 물가", oldName)
            assertFalse(candidate.needsClarification)
            assertEquals("RIVER", candidate.actionId)
            assertEquals("p1", candidate.declaredPlayerId)
            val prompt = rules.present(oldName).getString("prompt")
            assertTrue(prompt.startsWith(if (version == "0.1.0") "몰라 탐험가와 연구원 차례야. " else "몰라, "))
        }
    }

    @Test fun explicitRequestsBelongToCurrentEditionsAndDoNotRewriteArchivedInterpretation() {
        data class RequestCase(
            val packId: String,
            val path: List<String>,
            val text: String,
            val actionId: String,
            val previousReason: String,
            val previousVersion: String? = null,
        )
        val cases = listOf(
            RequestCase(DEFAULT_ADVENTURE_ID, emptyList(),
                "물가에서 찾아 줘", "RIVER", "unknown_action"),
            RequestCase("starlight-post-office", emptyList(),
                "달다리로 가 줘", "MOON_BRIDGE", "unconfirmed_expansion"),
            RequestCase("undersea-lighthouse", listOf("CORAL_ARCH", "READ_SIGN", "KEEP_MAP"),
                "빛거울로 비춰 줘", "LIGHT_PRISM", "unknown_action"),
            RequestCase("space-return-signal", listOf("MOON", "PANORAMA", "PHOTO"),
                "확인표 읽어 줘", "READ_CHECKLIST", "unconfirmed_expansion"),
            RequestCase("animal-footprint-garden", listOf("POND"),
                "움직임 적어 줘", "MOVEMENT", "unconfirmed_expansion"),
            RequestCase("little-car-workshop", emptyList(),
                "화분을 챙기자", "PLANTS", "unknown_action"),
            RequestCase(ContentReleaseExpectations.newPackId, listOf("HILL", "KITE"),
                "높이 올려 줘", "HIGH", "unconfirmed_expansion"),
            RequestCase("animal-footprint-garden", listOf("POND", "MOVEMENT", "PHOTO_ZOOM"),
                "이어서 그려 줘", "DRAW_TOGETHER", "unknown_action", "0.9.0"),
            RequestCase(ContentReleaseExpectations.newPackId, listOf("HILL", "KITE", "HIGH"),
                "풍경을 조용히 간직해 줘", "KEEP_SCENE", "unknown_action", "0.3.0"),
        )
        assertEquals(ContentReleaseExpectations.currentVersions.keys, cases.map { it.packId }.toSet())
        val library = LocalAdventureLibrary(context)
        for (case in cases) for (second in listOf(null, "서윤")) {
            val previousVersion = case.previousVersion ?: if (case.packId == ContentReleaseExpectations.newPackId) "0.1.0" else "0.5.0"
            val currentVersion = ContentReleaseExpectations.currentVersions.getValue(case.packId)
            for (version in listOf(previousVersion, currentVersion)) {
                val rules = library.rules(case.packId, version)
                var game = rules.newGame(playersBody("민준", second).getJSONArray("players"))
                assertEquals(version, game.getString("pack_version"))
                for (actionId in case.path) {
                    game = rules.applyAction(game, game.getString("expected_player_id"), actionId)
                }
                val actorId = game.getString("expected_player_id")
                val actor = game.getJSONArray("players").objects().single {
                    it.getString("player_id") == actorId
                }
                val text = if (second == null) case.text else "${actor.getString("nickname")}, ${case.text}"
                val before = canonicalJson(game)
                val candidate = rules.interpret(text, game)
                val label = "${case.packId}@$version: $text"
                assertEquals("$label interpretation cannot mutate progress", before, canonicalJson(game))
                if (version == currentVersion) {
                    assertEquals(label, "ACTION", candidate.intent)
                    assertFalse(label, candidate.needsClarification)
                    assertEquals(label, case.actionId, candidate.actionId)
                    assertEquals(label, actorId, candidate.declaredPlayerId)
                } else {
                    assertEquals(label, "UNKNOWN", candidate.intent)
                    assertTrue(label, candidate.needsClarification)
                    assertNull(label, candidate.actionId)
                    assertEquals(label, case.previousReason, candidate.reason)
                }
            }
        }
    }

    private suspend fun withClient(block: suspend (LocalGameClient) -> Unit) {
        val client = LocalGameClient(context, "speech_repair_${uid().replace("-", "")}")
        try { block(client) } finally { client.deleteAll() }
    }

    private suspend fun listen(client: LocalGameClient, snapshot: Snapshot): Snapshot = Snapshot.from(client.command(snapshot.sessionId,
        commandEnvelope(snapshot, uid(), "playback.completed", JSONObject().put("playback_id", requireNotNull(snapshot.speechPlan).playbackId))))

    private suspend fun submit(client: LocalGameClient, snapshot: Snapshot, text: String): Snapshot =
        Snapshot.from(client.command(snapshot.sessionId, action(snapshot, text)))

    private fun action(snapshot: Snapshot, text: String): JSONObject = commandEnvelope(snapshot, uid(), "action.submit", JSONObject()
        .put("text", text).put("is_final", true).put("capture_id", uid()).put("listen_window_id", snapshot.listenWindowId))

    private suspend fun reject(code: String, block: suspend () -> Any?) {
        try { block(); fail("Expected $code") } catch (error: ApiException) { assertEquals(code, error.code) }
    }

    private fun same(label: String, expected: JSONObject, actual: JSONObject) =
        assertEquals(label, canonicalJson(expected), canonicalJson(actual))

    private fun uid() = UUID.randomUUID().toString()
}
