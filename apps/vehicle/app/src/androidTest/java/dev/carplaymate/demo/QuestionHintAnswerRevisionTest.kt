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

/** Independently question-authored inputs. No microphone, TTS or family observation. */
@RunWith(AndroidJUnit4::class)
class QuestionHintAnswerRevisionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val library by lazy { LocalAdventureLibrary(context) }
    private val fixture by lazy {
        JSONObject(instrumentation.context.assets.open("fixtures/question-hint-answers.json")
            .bufferedReader().use { it.readText() })
    }
    private val cases get() = fixture.getJSONArray("cases").objects()
    private val editions get() = fixture.getJSONArray("editions").objects()
    private fun edition(case: JSONObject) = editions.single { it.getString("pack_id") == case.getString("pack_id") }
    private fun rules(case: JSONObject, previous: Boolean = false) = library.rules(case.getString("pack_id"),
        edition(case).getString(if (previous) "prior_version" else "current_version"))

    private fun publishedContent(packId: String, version: String): JSONObject {
        val catalog = JSONObject(context.assets.open("content/catalog.json")
            .bufferedReader().use { it.readText() })
        val entry = (catalog.getJSONArray("packs").objects() + catalog.getJSONArray("legacy_packs").objects())
            .single { it.getString("pack_id") == packId && it.getString("version") == version }
        return JSONObject(context.assets.open(entry.getString("content_asset"))
            .bufferedReader().use { it.readText() })
    }

    @Test fun allSixOriginalBytesAndEveryNonAliasStoryFieldArePreserved() {
        assertTrue(fixture.getBoolean("synthetic_inputs_only"))
        assertEquals(42, cases.size)
        assertEquals(6, editions.size)
        assertEquals("0.8.0", library.card(DEFAULT_ADVENTURE_ID, "0.8.0").version)
        for (edition in editions) {
            val id = edition.getString("pack_id")
            assertEquals(edition.getString("current_version"), library.card(id, edition.getString("current_version")).version)
            val path = edition.getString("archive_asset")
            val bytes = context.assets.open(path).use { it.readBytes() }
            assertEquals(edition.getInt("published_source_bytes"), bytes.size)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(edition.getString("published_source_sha256"), digest)
            val previous = JSONObject(bytes.toString(Charsets.UTF_8))
            // Compare the published 0.27 revision, even after it moves to the archive.
            val current = publishedContent(id, edition.getString("current_version"))
            val scenes = current.getJSONObject("scenes")
            for (sceneId in scenes.keyList()) {
                val actions = scenes.getJSONObject(sceneId).optJSONArray("actions") ?: continue
                val oldActions = previous.getJSONObject("scenes").getJSONObject(sceneId).getJSONArray("actions").objects()
                for (action in actions.objects()) {
                    val original = oldActions.single { it.getString("id") == action.getString("id") }
                    val additions = cases.filter { it.getString("pack_id") == id && it.getString("scene") == sceneId &&
                        it.getString("action") == action.getString("id") }.map { it.getString("text") }
                    assertEquals(original.getJSONArray("aliases").strings() + additions, action.getJSONArray("aliases").strings())
                    action.put("aliases", original.getJSONArray("aliases"))
                }
            }
            current.put("version", edition.getString("prior_version"))
            assertEquals("Only declared aliases and version may change", canonicalJson(previous), canonicalJson(current))
        }
    }

    @Test fun directAnswersCommitOnlyInNewEditionWithOneOrTwoChildrenAndBothAddressPositions() {
        for (second in listOf(null, "서윤")) for (case in cases) {
            val current = rules(case)
            val state = walk(current, case, second)
            val before = canonicalJson(state)
            assertTrue(current.present(state).getString(case.getString("evidence_field")).contains(case.getString("evidence")))
            val text = case.getString("text")
            val forms = mutableListOf(named(state, text), named(state, "오누야 $text"), "오누야 ${named(state, text)}")
            if (second == null) forms.addAll(listOf(text, "오누야 $text"))
            for (form in forms) {
                accepted(current, state, form, case.getString("action"))
                assertEquals(case.getJSONArray("path").length() + 1, current.applyAction(state,
                    state.getString("expected_player_id"), case.getString("action")).getJSONArray("history").length())
            }
            val previous = rules(case, true)
            val oldState = walk(previous, case, second)
            val refused = previous.interpret(named(oldState, text), oldState)
            assertNull(refused.actionId)
            assertTrue(refused.needsClarification)
            assertEquals(case.getString("prior_reason"), refused.reason)
            assertEquals(before, canonicalJson(state))
        }
    }

    @Test fun negationBothOptionsQuotesAndIncompleteCorrectionsCannotCommit() {
        for (second in listOf(null, "서윤")) for (case in cases) {
            val current = rules(case)
            val state = walk(current, case, second)
            val before = canonicalJson(state)
            val text = case.getString("text")
            val other = case.getString("other")
            for (body in listOf("$text 하지 마", "$text 그리고 $other", "$text 또는 $other", "$text $text",
                "어제 ${text}라고 했어", "$text 말고", "$text 그만", "오누야 \"$text\"", "오누야 오누야 $text",
                "오누야 어제 ${text}라고 했어 말고 $other", "응", "아니", "둘 다", "아무거나")) {
                val candidate = current.interpret(named(state, body), state)
                assertNull(body, candidate.actionId)
                assertTrue(body, candidate.needsClarification)
            }
            for (body in listOf("$text 말고 $other", "오누야 $text 말고 $other")) {
                accepted(current, state, named(state, body), case.getString("other_action"))
            }
            for ((command, intent) in listOf("오누야 쉬자" to "PAUSE", "다시" to "REPEAT", "기다려 줘" to "WAIT", "몰라" to "HINT")) {
                val candidate = current.interpret(command, state)
                assertEquals(intent, candidate.intent)
                assertNull(candidate.actionId)
            }
            assertEquals(before, canonicalJson(state))
        }
    }

    @Test fun twoChildrenNeedOneCorrectDeclaredOwner() {
        for (case in cases) {
            val current = rules(case)
            val state = walk(current, case, "서윤")
            val text = case.getString("text")
            val other = case.getString("other")
            val otherName = state.getJSONArray("players").objects().first {
                it.getString("player_id") != state.getString("expected_player_id") }.getString("nickname")
            for (body in listOf(text, "오누야 $text")) assertEquals("missing_player", current.interpret(body, state).reason)
            for (body in listOf("$otherName, $text", "오누야 $otherName, $text")) {
                assertEquals("wrong_player", current.interpret(body, state).reason)
            }
            for (body in listOf("$otherName, $text", "$text 말고 $otherName, $other")) {
                assertEquals("multiple_players", current.interpret(named(state, body), state).reason)
            }
        }
    }

    @Test fun registeredNamesKeepPriorityAndTwelveCharacterLimit() {
        for (case in cases) {
            val current = rules(case)
            val text = case.getString("text")
            val even = case.getJSONArray("path").length() % 2 == 0
            if (text.length <= 12) {
                val state = walk(current, case, if (even) "서윤" else text, if (even) text else "민준")
                for (body in listOf(text, "오누야 $text")) {
                    assertNull(current.interpret(body, state).actionId)
                    assertTrue(current.interpret(body, state).needsClarification)
                }
                accepted(current, state, named(state, text), case.getString("action"))
                assertEquals("address_only", current.interpret("오누야 ${named(state, text)}", state).reason)
            } else {
                try {
                    walk(current, case, if (even) "서윤" else text, if (even) text else "민준")
                    fail("An answer longer than 12 characters cannot become a registered nickname")
                } catch (error: LocalGameError) { assertEquals("invalid_players", error.code) }
            }
            for (nickname in listOf("오누", "오누야", "오 누야")) {
                val state = walk(current, case, if (even) nickname else "서윤", if (even) "민준" else nickname)
                accepted(current, state, named(state, text), case.getString("action"))
                val rejected = current.interpret(named(state, "오누야 $text"), state)
                assertNull(rejected.actionId)
                assertTrue(rejected.needsClarification)
            }
        }
    }

    @Test fun syntheticSessionsKeepNameConfirmationOriginalReceiptAndQuietBudget() = runBlocking {
        for (edition in editions) {
            val case = cases.first { it.getString("pack_id") == edition.getString("pack_id") }
            val client = LocalGameClient(context, "question_answers_${UUID.randomUUID().toString().replace("-", "")}")
            try {
                var snapshot = client.createAdventure(playersBody("민준", "서윤"), edition.getString("pack_id"))
                assertEquals(ContentReleaseExpectations.currentVersions.getValue(edition.getString("pack_id")),
                    snapshot.game.getString("pack_version"))
                for (action in case.getJSONArray("path").strings()) {
                    snapshot = listen(client, snapshot)
                    snapshot = submit(client, snapshot, named(snapshot.game, action))
                }
                snapshot = listen(client, snapshot)
                val before = canonicalJson(snapshot.game)
                snapshot = submit(client, snapshot, case.getString("text"))
                assertEquals("player_name", snapshot.presentation.getString("input_kind"))
                assertEquals(before, canonicalJson(snapshot.game))
                snapshot = listen(client, snapshot)
                val name = named(snapshot.game, "").trimEnd(',', ' ')
                val request = input(snapshot, name)
                val response = client.command(snapshot.sessionId, request)
                snapshot = Snapshot.from(response)
                assertEquals(case.getJSONArray("path").length() + 1, snapshot.game.getJSONArray("history").length())
                assertEquals(case.getString("action"), snapshot.game.getJSONArray("history")
                    .getJSONObject(case.getJSONArray("path").length()).getString("action_id"))
                assertEquals(canonicalJson(response), canonicalJson(client.command(snapshot.sessionId, request)))
                snapshot = listen(client, snapshot)
                val after = canonicalJson(snapshot.game)
                for (invalid in listOf("응", "둘 다")) {
                    snapshot = submit(client, snapshot, named(snapshot.game, invalid))
                    if (snapshot.status == "ACTIVE") snapshot = listen(client, snapshot)
                }
                assertEquals("PAUSED", snapshot.status)
                assertNull(snapshot.speechPlan)
                assertNull(snapshot.listenWindowId)
                assertEquals(after, canonicalJson(snapshot.game))
            } finally { client.deleteAll() }
        }
    }

    private fun walk(rules: LocalGameEngine, case: JSONObject, second: String?, first: String = "민준"): JSONObject {
        var state = rules.newGame(playersBody(first, second).getJSONArray("players"))
        for (action in case.getJSONArray("path").strings()) state = rules.applyAction(state, state.getString("expected_player_id"), action)
        assertEquals(case.getString("scene"), state.getString("scene_id"))
        return state
    }
    private fun named(state: JSONObject, text: String): String {
        val owner = state.getJSONArray("players").objects().first { it.getString("player_id") == state.getString("expected_player_id") }
        return "${owner.getString("nickname")}, $text"
    }
    private fun accepted(rules: LocalGameEngine, state: JSONObject, text: String, action: String) {
        assertEquals(text, LocalCandidate("ACTION", state.getString("expected_player_id"), action, false), rules.interpret(text, state))
    }
    private fun input(snapshot: Snapshot, text: String) = commandEnvelope(snapshot, UUID.randomUUID().toString(),
        "action.submit", JSONObject().put("text", text).put("is_final", true)
            .put("capture_id", UUID.randomUUID().toString()).put("listen_window_id", snapshot.listenWindowId))
    private suspend fun submit(client: LocalGameClient, snapshot: Snapshot, text: String) =
        Snapshot.from(client.command(snapshot.sessionId, input(snapshot, text)))
    private suspend fun listen(client: LocalGameClient, snapshot: Snapshot) = Snapshot.from(client.command(snapshot.sessionId,
        commandEnvelope(snapshot, UUID.randomUUID().toString(), "playback.completed", JSONObject()
            .put("playback_id", requireNotNull(snapshot.speechPlan).playbackId))))
}
