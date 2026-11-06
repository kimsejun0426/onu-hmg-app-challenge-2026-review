package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

/** Authored synthetic inputs only. No SpeechProviders, microphone, output or Activity. */
@RunWith(AndroidJUnit4::class)
class CompanionGreetingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun exactGreetingsAndNicknameActionBoundariesMatchIndependentOracles() {
        val fixture = JSONObject(instrumentation.context.assets.open("fixtures/companion-greetings.json")
            .bufferedReader().use { it.readText() })
        assertEquals(1, fixture.getInt("schema_version"))
        val cases = fixture.getJSONArray("cases").objects()
        assertEquals(34, cases.size)
        val source = instrumentation.targetContext.assets.open("content/ko-KR/dinosaurs/episode.json")
            .bufferedReader().use { it.readText() }
        for (case in cases) {
            val pack = JSONObject(source)
            if (case.has("action_alias")) {
                val river = pack.getJSONObject("scenes").getJSONObject(pack.getString("start_scene"))
                    .getJSONArray("actions").objects().single { it.getString("id") == "RIVER" }
                river.getJSONArray("aliases").put(case.getString("action_alias"))
            }
            val rules = LocalGameEngine(pack)
            val state = rules.newGame(case.getJSONArray("players"))
            val before = canonicalJson(state)
            assertEquals(case.getString("id"), canonicalJson(case.getJSONObject("expected_candidate")),
                canonicalJson(rules.interpret(case.getString("text"), state).toJson()))
            assertEquals("Interpretation must not mutate game", before, canonicalJson(state))
        }
    }

    @Test fun greetingAnswersOnceThenPausesWithoutAListeningLoopOrReplayedReceipt() = runBlocking {
        for (second in listOf<String?>(null, "서윤")) withClient { client ->
            var state = listen(client, client.create(playersBody("민준", second)))
            val before = canonicalJson(state.game)
            val turn = state.turnId
            val request = action(state, "오누야 안녕")
            val response = client.command(state.sessionId, request)
            state = Snapshot.from(response)
            assertEquals("ACTIVE", state.status)
            assertEquals(listOf("응, 여기 있어.", state.presentation.getString("prompt")),
                requireNotNull(state.speechPlan).segments.map { it.text })
            assertEquals(before, canonicalJson(state.game))
            assertEquals(turn, state.turnId)
            assertNull(state.listenWindowId)
            assertEquals(canonicalJson(response), canonicalJson(client.command(state.sessionId, request)))
            val changed = request.copyJson().also { it.getJSONObject("payload").put("text", "오누야 뭐해") }
            reject("COMMAND_ID_REUSED") { client.command(state.sessionId, changed) }
            state = listen(client, state)
            state = Snapshot.from(client.command(state.sessionId, action(state, "오누야 같이 놀자")))
            quiet(client, state)
            assertEquals(before, canonicalJson(state.game))
            val paused = canonicalJson(client.snapshotDocument(state.sessionId))
            reject("SESSION_PAUSED") { client.command(state.sessionId, action(state, "오누야 뭐해")) }
            assertEquals(paused, canonicalJson(client.snapshotDocument(state.sessionId)))
            state = Snapshot.from(client.command(state.sessionId, commandEnvelope(state, uid(), "session.resume", JSONObject())))
            state = listen(client, state)
            state = Snapshot.from(client.command(state.sessionId, action(state, "민준 물가")))
            assertEquals("RIVER", state.game.getJSONArray("history").getJSONObject(0).getString("action_id"))
            val saved = readRecordStoreDocument(client).toString()
            for (text in listOf("오누야 안녕", "오누야 같이 놀자", "오누야 뭐해")) {
                assertFalse("Raw greeting must not be stored", saved.contains(text))
            }
        }
    }

    @Test fun greetingCannotConfirmPendingChoiceOrBypassWrongPlayer() = runBlocking {
        for (first in listOf("물가", "서윤 오누야 안녕")) withClient { client ->
            var state = listen(client, client.create(playersBody("민준", "서윤")))
            state = Snapshot.from(client.command(state.sessionId, action(state, first)))
            if (first == "물가") assertEquals("player_name", state.presentation.getString("input_kind"))
            else assertEquals("지금은 민준 차례야. 같은 기회로 다시 해 보자.", requireNotNull(state.speechPlan).segments.first().text)
            state = listen(client, state)
            state = Snapshot.from(client.command(state.sessionId, action(state, "오누야 민준 안녕")))
            quiet(client, state)
            assertEquals(0, state.game.getJSONArray("history").length())
            state = Snapshot.from(client.command(state.sessionId, commandEnvelope(state, uid(), "session.resume", JSONObject())))
            state = listen(client, state)
            state = Snapshot.from(client.command(state.sessionId, action(state, "민준")))
            assertEquals(0, state.game.getJSONArray("history").length())
        }
    }

    @Test fun greetingDoesNotResetHelpWaitRepeatBudget() = runBlocking {
        withClient { client ->
            var state = client.create(playersBody("민준", null))
            for (text in listOf("도와줘", "오누야 뭐해", "기다려")) {
                state = listen(client, state)
                state = Snapshot.from(client.command(state.sessionId, action(state, text)))
                assertEquals("ACTIVE", state.status)
            }
            state = listen(client, state)
            state = Snapshot.from(client.command(state.sessionId, action(state, "다시 말해줘")))
            quiet(client, state)
            assertEquals(0, state.game.getJSONArray("history").length())
        }
    }

    @Test fun registeredFullNicknameStillConfirmsItsOwnPendingChoice() = runBlocking {
        for (nickname in listOf("안녕", "오누야 안녕")) withClient { client ->
            var state = listen(client, client.create(playersBody(nickname, "서윤")))
            state = Snapshot.from(client.command(state.sessionId, action(state, "물가")))
            assertEquals("player_name", state.presentation.getString("input_kind"))
            state = listen(client, state)
            state = Snapshot.from(client.command(state.sessionId, action(state, nickname)))
            assertEquals(1, state.game.getJSONArray("history").length())
            assertEquals("p1", state.game.getJSONArray("history").getJSONObject(0).getString("player_id"))
        }
    }

    @Test fun greetingBeforePlaybackCompletionCannotOpenAnAnswerWindow() = runBlocking {
        withClient { client ->
            val state = client.create(playersBody("민준", null))
            val before = canonicalJson(client.snapshotDocument(state.sessionId))
            reject("INPUT_NOT_READY") { client.command(state.sessionId, action(state, "오누야 안녕")) }
            assertEquals(before, canonicalJson(client.snapshotDocument(state.sessionId)))
        }
    }

    private suspend fun quiet(client: LocalGameClient, state: Snapshot) {
        assertEquals("PAUSED", state.status)
        val document = client.snapshotDocument(state.sessionId)
        assertEquals("NO_RESPONSE", document.getString("pause_reason"))
        assertNull(state.speechPlan)
        assertNull(state.listenWindowId)
        assertTrue(document.getBoolean("requires_explicit_resume"))
    }

    private suspend fun listen(client: LocalGameClient, state: Snapshot) = Snapshot.from(client.command(
        state.sessionId, commandEnvelope(state, uid(), "playback.completed",
            JSONObject().put("playback_id", requireNotNull(state.speechPlan).playbackId))))

    private fun action(state: Snapshot, text: String) = commandEnvelope(state, uid(), "action.submit", JSONObject()
        .put("text", text).put("is_final", true).put("capture_id", uid())
        .put("listen_window_id", state.listenWindowId ?: "closed-window"))

    private suspend fun reject(code: String, block: suspend () -> Any?) {
        try { block(); fail("Expected $code") } catch (error: ApiException) { assertEquals(code, error.code) }
    }

    private suspend fun withClient(block: suspend (LocalGameClient) -> Unit) {
        val app = ScopedApplication(instrumentation.targetContext)
        val client = LocalGameClient(app, "greeting")
        try { block(client) } finally {
            try { client.deleteAll() } finally { app.clean() }
        }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val scope = "greeting_${UUID.randomUUID()}"
        private val directory = File(context.noBackupFilesDir, scope).also { check(it.mkdirs()) }
        private val alias = "onu.records.v1." + MessageDigest.getInstance("SHA-256")
            .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("${scope}_$name", mode)
        fun clean() {
            check(getSharedPreferences("family_v1", Context.MODE_PRIVATE).edit().clear().commit())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(directory.deleteRecursively())
        }
    }

    private fun uid() = UUID.randomUUID().toString()
}
