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

/** Authored narration and synthetic commands; no claims about microphone or child listening. */
@RunWith(AndroidJUnit4::class)
class ContentNarrationRevisionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library by lazy { LocalAdventureLibrary(context) }

    @Test fun storyObservationAndGlobalWaitingRemainDistinctAcrossCurrentAndHistoricalEditionsAndPlayerCounts() {
        val versions = listOf("0.6.0", "0.7.0", "0.8.0", "0.9.0",
            ContentReleaseExpectations.currentVersions.getValue("animal-footprint-garden")).distinct()
        for (second in listOf(null, "서윤")) for (version in versions) {
            val rules = library.rules("animal-footprint-garden", version)
            for ((route, feature) in listOf("POND" to "WEBBED_FEET", "FLOWER_GARDEN" to "SIX_LEGS")) {
                val state = walk(rules, listOf(route, "MOVEMENT", "PHOTO_ZOOM", "SAY_ONE", feature), second)
                val before = canonicalJson(state)
                fun text(body: String) = if (second == null) body else "서윤, $body"
                for (body in listOf("더 지켜보기", "지켜보기", "더 지켜볼래", "지켜봐 줘", "지켜봐줘",
                    "더 지켜봐 줘", "더 지켜봐줘")) {
                    val candidate = rules.interpret(text(body), state)
                    if (version != "0.6.0") {
                        assertEquals("WAIT_A_LITTLE", candidate.actionId)
                        assertFalse(candidate.needsClarification)
                    } else {
                        assertNull(candidate.actionId)
                        assertTrue(candidate.needsClarification)
                    }
                }
                for (body in listOf("잠깐 기다리기", "기다리기", "조금 더", "기다릴래")) {
                    val candidate = rules.interpret(text(body), state)
                    if (version == "0.6.0") assertEquals("WAIT_A_LITTLE", candidate.actionId)
                    else assertNull(candidate.actionId)
                }
                for ((body, intent) in listOf("기다려 줘" to "WAIT", "하기 싫어" to "PAUSE",
                    "다시" to "REPEAT", "몰라" to "HINT")) {
                    val candidate = rules.interpret(text(body), state)
                    assertEquals(intent, candidate.intent)
                    assertNull(candidate.actionId)
                }
                if (version != "0.6.0") {
                    for (body in listOf("더", "", "더 지켜봐 줘 하지 마", "더 지켜보기 말고",
                        "더 지켜봐 줘 그리고 기록 나누기", "더 지켜봐 줘 다시", "어제 더 지켜봐 줘라고 했어")) {
                        val candidate = rules.interpret(text(body), state)
                        assertTrue(candidate.needsClarification)
                        assertNull(candidate.actionId)
                    }
                    val corrected = rules.interpret(text("더 지켜보기 말고 기록 나누기"), state)
                    assertEquals("SHARE_NOTE", corrected.actionId)
                    assertFalse(corrected.needsClarification)
                    if (second != null) {
                        assertEquals("missing_player", rules.interpret("더 지켜봐 줘", state).reason)
                        assertEquals("wrong_player", rules.interpret("민준, 더 지켜봐 줘", state).reason)
                        assertEquals("multiple_players", rules.interpret("서윤, 민준, 더 지켜봐 줘", state).reason)
                    }
                }
                assertEquals(before, canonicalJson(state))
            }
        }
    }

    @Test fun dinosaurAndOceanCluesOccurOnceAndRemainInEachStandaloneQuestion() {
        for (tool in listOf("MAGNIFIER", "LISTENING_DEVICE")) {
            val rules = library.rules(DEFAULT_ADVENTURE_ID)
            val state = walk(rules, listOf("RIVER", "FOLLOW_TRACKS", "LEAVES", tool))
            val prompt = rules.present(state).getString("prompt")
            assertEquals(1, occurrences(state.getString("feedback") + prompt, "뿔이 세 개"))
            assertTrue(prompt.contains("뿔이 세 개"))
            assertTrue(prompt.contains("트리케라톱스") && prompt.contains("스테고사우루스"))
        }
        val rules = library.rules("undersea-lighthouse")
        for ((route, pattern, beacon) in listOf(
            Triple("CORAL_ARCH", "동그라미, 세모, 동그라미", "동쪽 등대"),
            Triple("SHELL_TUNNEL", "세모, 동그라미, 세모", "서쪽 등대"))) {
            for (observation in listOf("READ_SIGN", "HEAR_BUOY")) {
                val observed = walk(rules, listOf(route, observation))
                val prompt = rules.present(observed).getString("prompt")
                assertTrue(observed.getString("feedback").contains(beacon))
                assertEquals(1, occurrences(observed.getString("feedback") + prompt, pattern))
                assertTrue(prompt.contains(pattern))
                for (tool in listOf("LIGHT_PRISM", "ECHO_SHELL")) {
                    val state = walk(rules, listOf(route, observation, "KEEP_MAP", tool))
                    val question = rules.present(state).getString("prompt")
                    assertEquals(1, occurrences(state.getString("feedback") + question, pattern))
                    assertTrue(question.contains(pattern))
                    assertTrue(question.contains("동그라미를 넣을까, 세모를 넣을까?"))
                    val shape = if (route == "CORAL_ARCH") "CIRCLE" else "TRIANGLE"
                    val ready = rules.applyAction(state, state.getString("expected_player_id"), shape)
                    val destinationQuestion = rules.present(ready).getString("prompt")
                    assertTrue(destinationQuestion.contains("누리가 돌아갈 곳은 $beacon"))
                    assertFalse(destinationQuestion.contains("누리가 기다리는 곳"))
                }
            }
        }
    }

    @Test fun lastChoicesPrepareOrDeliverOneEndingWithoutLosingChosenDetails() {
        val animal = library.rules("animal-footprint-garden")
        val watched = walk(animal, listOf("POND", "MOVEMENT", "PHOTO_ZOOM", "SAY_ONE", "WEBBED_FEET", "WAIT_A_LITTLE"))
        assertEquals("", watched.getString("feedback"))
        val observationEnding = animal.present(watched).getString("prompt")
        assertTrue(observationEnding.contains("새 움직임이 없어"))
        assertTrue(observationEnding.contains("오리 뒤에 남은 잔물결"))
        assertTrue(observationEnding.contains("옆에 헤엄을 돕는 물갈퀴 설명도 붙였어."))
        assertFalse(observationEnding.contains("만 수첩에 남겼어"))
        assertTrue(observationEnding.contains("모르는 건 빈칸"))

        val letter = library.rules("starlight-post-office")
        for (greeting in listOf("HELLO", "TAKE_YOUR_TIME")) {
            val finished = walk(letter, listOf("MOON_BRIDGE", "LOOK_STAMP", "WALK_TOGETHER",
                "STAR_LANTERN", greeting, "READ_TOGETHER"))
            assertEquals("모모가 친구 곁에서 별편지를 펼쳤어.", finished.getString("feedback"))
            val ending = letter.present(finished).getString("prompt")
            assertEquals(1, occurrences(ending, "편지를 읽어 줬어"))
            assertTrue(ending.contains(if (greeting == "HELLO") "만나서 반가워" else "천천히 열어도 괜찮아"))
        }
        val space = library.rules("space-return-signal")
        for (sharing in listOf("MAKE_GALLERY", "TELL_STORY")) {
            val finished = walk(space, listOf("MARS", "PANORAMA", "NOTE", "COUNT_STEPS", "CLOSE_DOOR", sharing))
            assertFalse(finished.getString("feedback").contains("전시를 열었어"))
            assertFalse(finished.getString("feedback").contains("들려줬어"))
            val ending = space.present(finished).getString("prompt")
            assertTrue(ending.contains("화성") && ending.contains("노트에는"))
            assertTrue(ending.contains(if (sharing == "MAKE_GALLERY") "전시를 열었어" else "들려줬어"))
        }
    }

    @Test fun repeatedAndResumedQuestionsKeepTheFullClueWithoutOldFeedback() = runBlocking {
        val cases = listOf(
            Triple(DEFAULT_ADVENTURE_ID, listOf("RIVER", "FOLLOW_TRACKS", "LEAVES", "MAGNIFIER"), "뿔이 세 개"),
            Triple(DEFAULT_ADVENTURE_ID, listOf("RIVER", "FOLLOW_TRACKS", "LEAVES", "LISTENING_DEVICE"), "뿔이 세 개"),
            Triple("undersea-lighthouse", listOf("CORAL_ARCH", "READ_SIGN", "KEEP_MAP", "LIGHT_PRISM"), "동그라미, 세모, 동그라미"),
            Triple("undersea-lighthouse", listOf("SHELL_TUNNEL", "HEAR_BUOY", "KEEP_MAP", "ECHO_SHELL"), "세모, 동그라미, 세모"),
        )
        for ((packId, path, clue) in cases) withClient { client ->
            val chosen = walkCommands(client, packId, path)
            val prompt = chosen.presentation.getString("prompt")
            assertTrue(prompt.contains(clue))
            val repeated = submit(client, listen(client, chosen), "다시")
            assertEquals(listOf(prompt), requireNotNull(repeated.speechPlan).segments.map { it.text })
            val ready = listen(client, repeated)
            val paused = Snapshot.from(client.command(ready.sessionId, commandEnvelope(ready, uid(), "session.pause",
                JSONObject().put("reason", "QUIET_REQUEST"))))
            val resumed = Snapshot.from(client.command(paused.sessionId, commandEnvelope(paused, uid(), "session.resume", JSONObject())))
            assertEquals(listOf(prompt), requireNotNull(resumed.speechPlan).segments.map { it.text })
            assertEquals(canonicalJson(chosen.game), canonicalJson(resumed.game))
        }
    }

    @Test fun askingForTimeDoesNotFinishTheAnimalStoryButExplicitObservationDoes() = runBlocking {
        withClient { client ->
            val chosen = walkCommands(client, "animal-footprint-garden",
                listOf("POND", "MOVEMENT", "PHOTO_ZOOM", "SAY_ONE", "WEBBED_FEET"))
            val waiting = submit(client, listen(client, chosen), "기다려 줘")
            assertEquals(canonicalJson(chosen.game), canonicalJson(waiting.game))
            assertEquals("ACTIVE", waiting.status)
            val finished = submit(client, listen(client, waiting), "더 지켜봐 줘")
            assertEquals("PATIENT_OBSERVATION", finished.game.getString("ending"))
            assertEquals(6, finished.game.getJSONArray("history").length())
            assertEquals("", finished.game.getString("feedback"))
            assertEquals(listOf(finished.presentation.getString("prompt")),
                requireNotNull(finished.speechPlan).segments.map { it.text })
            assertEquals("ENDED", listen(client, finished).status)
        }
    }

    private fun walk(rules: LocalGameEngine, path: List<String>, second: String? = null): JSONObject {
        var state = rules.newGame(playersBody("민준", second).getJSONArray("players"))
        for (action in path) state = rules.applyAction(state, state.getString("expected_player_id"), action)
        return state
    }

    private suspend fun walkCommands(client: LocalGameClient, packId: String, path: List<String>): Snapshot {
        var state = client.createAdventure(playersBody("민준", null), packId)
        for (action in path) state = submit(client, listen(client, state), action)
        return state
    }

    private suspend fun withClient(block: suspend (LocalGameClient) -> Unit) {
        val client = LocalGameClient(context, "content_narration_${uid().replace("-", "")}")
        try { block(client) } finally { client.deleteAll() }
    }

    private suspend fun listen(client: LocalGameClient, state: Snapshot) = Snapshot.from(client.command(state.sessionId,
        commandEnvelope(state, uid(), "playback.completed",
            JSONObject().put("playback_id", requireNotNull(state.speechPlan).playbackId))))

    private suspend fun submit(client: LocalGameClient, state: Snapshot, text: String) = Snapshot.from(client.command(state.sessionId,
        commandEnvelope(state, uid(), "action.submit", JSONObject().put("text", text).put("is_final", true)
            .put("capture_id", uid()).put("listen_window_id", state.listenWindowId))))

    private fun occurrences(text: String, term: String) = text.windowed(term.length).count { it == term }
    private fun uid() = UUID.randomUUID().toString()
}
