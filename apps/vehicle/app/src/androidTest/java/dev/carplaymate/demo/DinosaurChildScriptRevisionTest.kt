package dev.carplaymate.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic text requests only; this does not measure child comprehension or microphone quality. */
@RunWith(AndroidJUnit4::class)
class DinosaurChildScriptRevisionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val library by lazy { LocalAdventureLibrary(context) }
    private val fixture by lazy {
        JSONObject(instrumentation.context.assets.open("fixtures/dinosaur-child-script-revision.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
    private val cases get() = fixture.getJSONArray("cases").objects()
    private val current get() = library.rules(DEFAULT_ADVENTURE_ID, "0.10.0")
    private val previous get() = library.rules(DEFAULT_ADVENTURE_ID, "0.9.0")

    @Test fun currentSourceContainsOnlyApprovedProseAliasesAndTemplateChanges() {
        val oldBytes = context.assets.open(fixture.getString("archive_asset")).use { it.readBytes() }
        // This regression remains pinned to the approved 0.10 edition after newer starts ship.
        val newBytes = context.assets.open("content/legacy/0.10.0/dinosaurs/episode.json").use { it.readBytes() }
        assertEquals(fixture.getInt("published_source_bytes"), oldBytes.size)
        assertEquals(fixture.getString("published_source_sha256"), sha256(oldBytes))
        assertEquals(fixture.getString("approved_source_sha256"), sha256(newBytes))
        val old = JSONObject(oldBytes.toString(Charsets.UTF_8))
        val restored = JSONObject(newBytes.toString(Charsets.UTF_8))
        assertEquals("0.10.0", restored.getString("version"))
        val fields = fixture.getJSONArray("changed_fields").objects()
        assertEquals(11, fields.size)
        assertEquals(7, cases.size)
        for (field in fields) restoreField(restored, field)
        val template = fixture.getJSONObject("added_template")
        assertEquals(canonicalJson(template.getJSONObject("value")), canonicalJson(
            restored.getJSONObject("template_labels").remove(template.getString("name")) as JSONObject))
        val action = restored.getJSONObject("scenes").getJSONObject("tool").getJSONArray("actions").objects()
            .single { it.getString("id") == "LISTENING_DEVICE" }
        val oldAction = old.getJSONObject("scenes").getJSONObject("tool").getJSONArray("actions").objects()
            .single { it.getString("id") == "LISTENING_DEVICE" }
        assertEquals(oldAction.getJSONArray("aliases").strings() + cases.map { it.getString("text") },
            action.getJSONArray("aliases").strings())
        action.put("aliases", oldAction.getJSONArray("aliases"))
        restored.put("version", "0.9.0")
        assertEquals(canonicalJson(old), canonicalJson(restored))
    }

    @Test fun sevenAnswersWorkInNewEditionAndRemainUnknownInArchivedEdition() {
        for (second in listOf(null, "서윤")) for (case in cases) {
            val state = walk(current, case, second)
            val before = canonicalJson(state)
            assertEquals(case.getString("scene"), state.getString("scene_id"))
            assertTrue(current.present(state).getString("prompt").contains(case.getString("question")))
            val text = case.getString("text")
            val forms = mutableListOf(named(state, text), named(state, "오누야 $text"), "오누야 ${named(state, text)}")
            if (second == null) forms.addAll(listOf(text, "오누야 $text"))
            forms.forEach { accepted(state, it) }
            val oldState = walk(previous, case, second)
            val rejected = previous.interpret(named(oldState, text), oldState)
            assertTrue(rejected.needsClarification)
            assertNull(rejected.actionId)
            assertEquals(before, canonicalJson(state))
        }
    }

    @Test fun refusalsCorrectionsAndTwoChildOwnershipStayBounded() {
        for (second in listOf(null, "서윤")) for (case in cases) {
            val state = walk(current, case, second)
            val before = canonicalJson(state)
            val text = case.getString("text")
            val other = case.getString("other")
            for (body in listOf(case.getString("negative"), "$text 하지 마", "$text 그리고 $other",
                    "$text 또는 $other", "$text $text", "어제 ${text}라고 했어", "$text 말고", "$text 그만",
                    "오누야 \"$text\"", "오누야 오누야 $text", "오누야 어제 ${text}라고 했어 말고 $other", "응", "아니", "둘 다")) {
                val candidate = current.interpret(named(state, body), state)
                assertTrue(body, candidate.needsClarification)
                assertNull(body, candidate.actionId)
            }
            for (body in listOf("$text 말고 $other", "오누야 $text 말고 $other"))
                accepted(state, named(state, body), case.getString("other_action"))
            for (body in listOf("$other 말고 $text", "오누야 $other 말고 $text")) accepted(state, named(state, body))
            for ((command, intent) in listOf("그만" to "PAUSE", "오누야 쉬자" to "PAUSE", "다시" to "REPEAT",
                    "기다려 줘" to "WAIT", "몰라" to "HINT")) {
                val candidate = current.interpret(command, state)
                assertEquals(intent, candidate.intent)
                assertNull(candidate.actionId)
            }
            if (second != null) {
                val wrong = state.getJSONArray("players").objects().first {
                    it.getString("player_id") != state.getString("expected_player_id") }.getString("nickname")
                assertEquals("missing_player", current.interpret(text, state).reason)
                assertEquals("wrong_player", current.interpret("$wrong, $text", state).reason)
                assertEquals("multiple_players", current.interpret(named(state, "$wrong, $text"), state).reason)
                assertEquals("multiple_players", current.interpret(named(state, "$text 말고 $wrong, $other"), state).reason)
            }
            assertEquals(before, canonicalJson(state))
        }
    }

    @Test fun registeredNamesKeepPriorityOverExplanationAliases() {
        for (case in cases) {
            val text = case.getString("text")
            if (text.length > 12) {
                try {
                    walk(current, case, text)
                    fail("Overlong registered names must remain invalid")
                } catch (error: LocalGameError) { assertEquals("invalid_players", error.code) }
            } else {
                val state = walk(current, case, text)
                for (body in listOf(text, "오누야 $text")) {
                    val candidate = current.interpret(body, state)
                    assertTrue(candidate.needsClarification)
                    assertNull(candidate.actionId)
                }
                accepted(state, named(state, text))
                assertEquals("address_only", current.interpret("오누야 ${named(state, text)}", state).reason)
            }
            for (name in listOf("오누", "오누야", "오 누야")) {
                val state = walk(current, case, "서윤", name)
                accepted(state, named(state, text))
                val rejected = current.interpret(named(state, "오누야 $text"), state)
                assertTrue(rejected.needsClarification)
                assertNull(rejected.actionId)
            }
        }
    }

    @Test fun allRoutesPreserveScoresResourcesAndDistinctEndingAgency() {
        for (second in listOf(null, "서윤")) {
            val players = playersBody("민준", second).getJSONArray("players")
            val pending = mutableListOf(previous.newGame(players) to current.newGame(players))
            val endings = mutableMapOf("FOUND_TOGETHER" to 0, "TEAM_ASSIST" to 0)
            while (pending.isNotEmpty()) {
                val (old, state) = pending.removeAt(pending.lastIndex)
                fun stable(value: JSONObject) = value.copyJson().apply { remove("pack_version"); remove("feedback") }
                assertEquals(canonicalJson(stable(old)), canonicalJson(stable(state)))
                val view = current.present(state)
                val prompt = view.getString("prompt")
                if (state.getBoolean("finished")) {
                    val ending = state.getString("ending")
                    endings[ending] = endings.getValue(ending) + 1
                    assertEquals(6, state.getInt("turn_index"))
                    if (ending == "TEAM_ASSIST") {
                        assertTrue(prompt.contains("연구소 팀이") && prompt.contains("알 모형을 찾았대."))
                        assertTrue(prompt.contains("우리가 찾은 로봇이"))
                        assertFalse(prompt.contains("우리 함께 찾았네."))
                    } else {
                        assertTrue(prompt.contains("처음엔 ") && prompt.contains("에서 바퀴 자국을 봤어."))
                        assertFalse(prompt.contains("에서 찾은 로봇"))
                        assertTrue(prompt.endsWith("우리 함께 찾았네."))
                    }
                    val memory = if (state.getJSONObject("flags").getString("tool_used") == "LISTENING_DEVICE")
                        "듣기 장치로 공룡 그림 설명을 들었지." else "돋보기로 공룡 그림을 크게 봤지."
                    assertTrue(prompt.contains(memory))
                    continue
                }
                assertEquals(2, view.getJSONArray("actions").length())
                assertEquals(1, prompt.count { it == '?' })
                assertTrue(prompt.length <= 160)
                for (action in previous.present(old).getJSONArray("actions").objects()) {
                    for (text in listOf(action.getString("label")) + action.getJSONArray("aliases").strings()) {
                        assertEquals(previous.interpret(named(old, text), old), current.interpret(named(state, text), state))
                    }
                    pending.add(previous.applyAction(old, old.getString("expected_player_id"), action.getString("id")) to
                        current.applyAction(state, state.getString("expected_player_id"), action.getString("id")))
                }
            }
            assertEquals(mapOf("FOUND_TOGETHER" to 32, "TEAM_ASSIST" to 32), endings)
        }
    }

    @Test fun syntheticRequestsKeepOriginalReceiptsAndOneChoiceEffect() = runBlocking {
        for (second in listOf(null, "서윤")) for (case in cases) {
            val client = LocalGameClient(context, "child_script_${UUID.randomUUID().toString().replace("-", "")}")
            try {
                var snapshot = client.create(playersBody("민준", second))
                for (action in case.getJSONArray("path").strings()) {
                    snapshot = listen(client, snapshot)
                    snapshot = Snapshot.from(client.command(snapshot.sessionId, request(snapshot, named(snapshot.game, action))))
                }
                snapshot = listen(client, snapshot)
                val input = named(snapshot.game, case.getString("text"))
                val command = request(snapshot, input)
                val response = client.command(snapshot.sessionId, command)
                snapshot = Snapshot.from(response)
                assertEquals("0.10.0", snapshot.game.getString("pack_version"))
                assertEquals(4, snapshot.game.getJSONArray("history").length())
                assertEquals("LISTENING_DEVICE", snapshot.game.getJSONArray("history").getJSONObject(3).getString("action_id"))
                assertEquals(canonicalJson(response), canonicalJson(client.command(snapshot.sessionId, command)))
                assertEquals(snapshot.version, client.snapshot(snapshot.sessionId).version)
                assertFalse(readRecordStoreDocument(client).toString().contains(input))
            } finally { client.deleteAll() }
        }
    }

    private fun walk(rules: LocalGameEngine, case: JSONObject, second: String?, first: String = "민준"): JSONObject {
        var state = rules.newGame(playersBody(first, second).getJSONArray("players"))
        for (action in case.getJSONArray("path").strings())
            state = rules.applyAction(state, state.getString("expected_player_id"), action)
        return state
    }

    private fun named(state: JSONObject, text: String): String {
        val owner = state.getJSONArray("players").objects().first { it.getString("player_id") == state.getString("expected_player_id") }
        return "${owner.getString("nickname")}, $text"
    }

    private fun accepted(state: JSONObject, text: String, action: String = "LISTENING_DEVICE") {
        assertEquals(text, LocalCandidate("ACTION", state.getString("expected_player_id"), action, false), current.interpret(text, state))
    }

    private fun restoreField(root: JSONObject, field: JSONObject) {
        val parts = field.getString("path").trim('/').split('/')
        var parent: Any = root
        for (part in parts.dropLast(1)) parent = when (val value = parent) {
            is JSONObject -> value.get(part)
            is JSONArray -> value.get(part.toInt())
            else -> error("Invalid approved field path")
        }
        val key = parts.last()
        val before = field.getString("before")
        when (val value = parent) {
            is JSONObject -> { assertEquals(field.getString("after"), value.getString(key)); value.put(key, before) }
            is JSONArray -> { assertEquals(field.getString("after"), value.getString(key.toInt())); value.put(key.toInt(), before) }
            else -> error("Invalid approved field parent")
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun uid() = UUID.randomUUID().toString()
    private fun request(snapshot: Snapshot, text: String) = commandEnvelope(snapshot, uid(), "action.submit", JSONObject()
        .put("text", text).put("is_final", true).put("capture_id", uid()).put("listen_window_id", snapshot.listenWindowId))
    private suspend fun listen(client: LocalGameClient, snapshot: Snapshot) = Snapshot.from(client.command(snapshot.sessionId,
        commandEnvelope(snapshot, uid(), "playback.completed", JSONObject().put("playback_id", requireNotNull(snapshot.speechPlan).playbackId))))
}
