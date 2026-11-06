package dev.carplaymate.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Question-authored synthetic answers; no microphone, TTS or child-playtest claim. */
@RunWith(AndroidJUnit4::class)
class DinosaurAnswerRevisionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val library by lazy { LocalAdventureLibrary(context) }
    private val fixture by lazy {
        JSONObject(instrumentation.context.assets.open("fixtures/dinosaur-question-answers.json")
            .bufferedReader().use { it.readText() })
    }
    private val cases get() = fixture.getJSONArray("cases").objects()
    private val current get() = library.rules(DEFAULT_ADVENTURE_ID, "0.8.0")
    private val previous get() = library.rules(DEFAULT_ADVENTURE_ID, "0.7.0")

    @Test fun independentQuestionsAndExactPreviousEditionAreBundled() {
        assertEquals("0.8.0", library.card(DEFAULT_ADVENTURE_ID, "0.8.0").version)
        assertTrue(fixture.getBoolean("synthetic_inputs_only"))
        assertEquals(8, cases.size)
        assertEquals(6, cases.map { it.getString("text") }.toSet().size)
        val bytes = context.assets.open("content/legacy/0.7.0/dinosaurs/episode.json").use { it.readBytes() }
        assertEquals(fixture.getInt("published_source_bytes"), bytes.size)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(fixture.getString("published_source_sha256"), digest)
        assertEquals("0.7.0", JSONObject(bytes.toString(Charsets.UTF_8)).getString("version"))
    }

    @Test fun answersToTheQuestionWorkWithOneOrTwoChildrenAndBothAddressPositions() {
        for (second in listOf(null, "서윤")) for (case in cases) {
            val state = walk(current, case, second)
            val before = canonicalJson(state)
            assertEquals(case.getString("scene"), state.getString("scene_id"))
            assertTrue(current.present(state).getString("prompt").contains(case.getString("question")))
            val text = case.getString("text")
            val forms = mutableListOf(named(state, text), named(state, "오누야 $text"), "오누야 ${named(state, text)}")
            if (second == null) forms.addAll(listOf(text, "오누야 $text"))
            for (form in forms) {
                val candidate = accepted(current, state, form, case.getString("action"))
                val changed = current.applyAction(state, requireNotNull(candidate.declaredPlayerId), requireNotNull(candidate.actionId))
                assertEquals(case.getJSONArray("path").length() + 1, changed.getJSONArray("history").length())
            }
            val oldState = walk(previous, case, second)
            val rejected = previous.interpret(named(oldState, text), oldState)
            assertTrue(rejected.needsClarification)
            assertNull(rejected.actionId)
            assertEquals(before, canonicalJson(state))
        }
    }

    @Test fun negativeBothQuotedAndRepeatedAnswersStayUncommittedWhileCorrectionsRemainExplicit() {
        for (second in listOf(null, "서윤")) for (case in cases) {
            val state = walk(current, case, second)
            val before = canonicalJson(state)
            val text = case.getString("text")
            val other = case.getString("other")
            val rejected = listOf(case.getString("negative"), case.getString("both"), "둘 다", "아무거나", "응", "첫 번째",
                "$text 하지 마", "$text 그리고 $other", "$text $text", "어제 ${text}라고 했어", "$text 말고",
                "$text 그만", "오누야 \"$text\"", "오누야 오누야 $text", "오누야 어제 ${text}라고 했어 말고 $other")
            for (body in rejected) {
                val candidate = current.interpret(named(state, body), state)
                assertTrue(body, candidate.needsClarification)
                assertNull(body, candidate.actionId)
            }
            for (body in listOf("$text 말고 $other", "오누야 $text 말고 $other")) {
                accepted(current, state, named(state, body), case.getString("other_action"))
            }
            accepted(current, state, named(state, "\"$text\""), case.getString("action"))
            for ((command, intent) in listOf("그만" to "PAUSE", "오누야 쉬자" to "PAUSE", "다시" to "REPEAT",
                    "기다려 줘" to "WAIT", "몰라" to "HINT")) {
                val candidate = current.interpret(command, state)
                assertEquals(intent, candidate.intent)
                assertNull(candidate.actionId)
            }
            assertEquals(before, canonicalJson(state))
        }
    }

    @Test fun ownerAndFullNicknameBoundariesRemainIndependentOfTheNewAliases() {
        for (case in cases) {
            val state = walk(current, case, "서윤")
            val text = case.getString("text")
            val otherName = state.getJSONArray("players").objects()
                .first { it.getString("player_id") != state.getString("expected_player_id") }.getString("nickname")
            for (form in listOf(text, "오누야 $text")) {
                assertEquals("missing_player", current.interpret(form, state).reason)
            }
            for (form in listOf("$otherName, $text", "오누야 $otherName, $text")) {
                assertEquals("wrong_player", current.interpret(form, state).reason)
            }
            assertEquals("multiple_players", current.interpret(named(state, "$otherName, $text"), state).reason)
            assertEquals("multiple_players", current.interpret(named(state,
                "$text 말고 $otherName, ${case.getString("other")}"), state).reason)
            val even = case.getJSONArray("path").length() % 2 == 0
            val fullNameState = walk(current, case, if (even) "서윤" else text, if (even) text else "민준")
            for (form in listOf(text, "오누야 $text")) {
                val candidate = current.interpret(form, fullNameState)
                assertTrue(candidate.needsClarification)
                assertNull(candidate.actionId)
            }
            accepted(current, fullNameState, named(fullNameState, text), case.getString("action"))
            assertEquals("address_only", current.interpret("오누야 ${named(fullNameState, text)}", fullNameState).reason)
        }
        for (name in listOf("오누", "오누야", "오 누야")) {
            val state = current.newGame(playersBody("민준", name).getJSONArray("players"))
            accepted(current, state, "민준, 물가에서 찾을래", "RIVER")
            val rejected = current.interpret("민준, 오누야 물가에서 찾을래", state)
            assertTrue(rejected.needsClarification)
            assertNull(rejected.actionId)
        }
        val boxCase = cases.first { it.getString("id") == "river-label" }
        val boxState = walk(current, boxCase, "서윤", "상자")
        accepted(current, boxState, "서윤, 상자 표지를 볼래", "READ_LABEL")
        for (text in listOf("상자 표지를 볼래", "서윤, 표지를 볼래")) {
            val rejected = current.interpret(text, boxState)
            assertTrue(rejected.needsClarification)
            assertNull(rejected.actionId)
        }
    }

    @Test fun syntheticFamilyJourneysUseTheAnswersWithoutExtraClarificationOrDuplicateEffects() = runBlocking {
        for (second in listOf(null, "서윤")) for (forest in listOf(false, true)) {
            val client = LocalGameClient(context, "dinosaur_answers_${UUID.randomUUID().toString().replace("-", "")}")
            try {
                val spoken = listOf(if (forest) "숲에서 찾을래" else "물가에서 찾을래",
                    if (forest) "자국을 따라갈래" else "상자 표지를 볼래", "생선 같아",
                    "듣기 장치로 알아볼래", "트리케라톱스", if (forest) "온실" else "공작실")
                val actions = listOf(if (forest) "FOREST" else "RIVER", if (forest) "FOLLOW_TRACKS" else "READ_LABEL",
                    "FISH", "LISTENING_DEVICE", "TRICERATOPS", if (forest) "GREENHOUSE" else "WORKSHOP")
                val submitted = mutableListOf<String>()
                var snapshot = client.create(playersBody("민준", second))
                for ((index, text) in spoken.withIndex()) {
                    snapshot = listen(client, snapshot)
                    val input = "오누야 ${named(snapshot.game, text)}"
                    submitted += input
                    val request = commandEnvelope(snapshot, uid(), "action.submit", JSONObject()
                        .put("text", input).put("is_final", true).put("capture_id", uid())
                        .put("listen_window_id", snapshot.listenWindowId))
                    val response = client.command(snapshot.sessionId, request)
                    snapshot = Snapshot.from(response)
                    assertEquals(index + 1, snapshot.game.getJSONArray("history").length())
                    assertEquals(actions[index], snapshot.game.getJSONArray("history").getJSONObject(index).getString("action_id"))
                    assertEquals(canonicalJson(response), canonicalJson(client.command(snapshot.sessionId, request)))
                    assertEquals(snapshot.version, client.snapshot(snapshot.sessionId).version)
                }
                assertEquals(ContentReleaseExpectations.currentVersions.getValue(DEFAULT_ADVENTURE_ID),
                    snapshot.game.getString("pack_version"))
                assertEquals("FOUND_TOGETHER", snapshot.game.getString("ending"))
                val scores = snapshot.game.getJSONObject("score_by_player")
                assertEquals(6, scores.keyList().sumOf { scores.getInt(it) })
                assertEquals(0, snapshot.game.getJSONObject("resources").getInt("tool_tokens"))
                assertEquals("ENDED", listen(client, snapshot).status)
                val stored = readRecordStoreDocument(client).toString()
                submitted.forEach { assertFalse("Raw named answers must not be retained", stored.contains(it)) }
            } finally { client.deleteAll() }
        }
    }

    private fun walk(rules: LocalGameEngine, case: JSONObject, second: String?, first: String = "민준"): JSONObject {
        var state = rules.newGame(playersBody(first, second).getJSONArray("players"))
        for (action in case.getJSONArray("path").strings()) state = rules.applyAction(state, state.getString("expected_player_id"), action)
        return state
    }

    private fun named(state: JSONObject, text: String): String {
        val owner = state.getJSONArray("players").objects().first { it.getString("player_id") == state.getString("expected_player_id") }
        return "${owner.getString("nickname")}, $text"
    }

    private fun accepted(rules: LocalGameEngine, state: JSONObject, text: String, action: String): LocalCandidate {
        val candidate = rules.interpret(text, state)
        assertEquals(text, LocalCandidate("ACTION", state.getString("expected_player_id"), action, false), candidate)
        return candidate
    }

    private suspend fun listen(client: LocalGameClient, snapshot: Snapshot) = Snapshot.from(client.command(snapshot.sessionId,
        commandEnvelope(snapshot, uid(), "playback.completed", JSONObject()
            .put("playback_id", requireNotNull(snapshot.speechPlan).playbackId))))

    private fun uid() = UUID.randomUUID().toString()
}
