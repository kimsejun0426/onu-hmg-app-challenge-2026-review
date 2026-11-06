package dev.carplaymate.demo

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalGameClientTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun eighteenServiceScenariosMatchPythonIncludingEverySnapshotAndReceipt() = runBlocking {
        val fixture = JSONObject(instrumentation.context.assets.open("fixtures/service-parity.json")
            .bufferedReader().use { it.readText() })
        val scenarios = fixture.getJSONArray("scenarios").objects()
        val expectedScenarios = setOf(
            "ending-1", "ending-2", "name-repair", "quiet-resume-duplicate", "controls-and-objection",
            "unfinished-correction-clears-pending", "help-clears-pending", "wait-preserves-pending",
            "pause-clears-pending", "recovery-budget", "recovery-helpers-budget", "reason-choice",
            "completed-feedback-resume", "ending-resume",
            "address-pending-guard", "address-quiet-resume", "address-recovery-budget", "address-only-budget",
        )
        assertEquals(18, scenarios.size)
        assertEquals(expectedScenarios, scenarios.map { it.getString("id") }.toSet())
        for (scenario in scenarios) withClient { client, _ ->
            val identities = Identities(fixture.getJSONObject("id_normalization").getJSONObject("fields"))
            val initial = client.create(JSONObject().put("players", scenario.getJSONArray("players")))
            same(scenario.getString("id"), scenario.getJSONObject("initial_snapshot"), identities.normalize(client.snapshotDocument(initial.sessionId)))
            for ((index, step) in scenario.getJSONArray("steps").objects().withIndex()) {
                val label = "${scenario.getString("id")} step $index"
                val request = identities.expand(step.getJSONObject("request")) as JSONObject
                // Mirror the fixture traversal order: request, response/error, current snapshot.
                same("$label request", step.getJSONObject("request"), identities.normalize(request))
                try {
                    val result = client.command(initial.sessionId, request)
                    if (step.has("expected_error")) fail("$label expected rejection")
                    same("$label receipt", step.getJSONObject("expected_response"), identities.normalize(result))
                } catch (error: ApiException) {
                    val expected = step.getJSONObject("expected_error")
                    assertEquals(label, expected.getInt("status"), error.status)
                    assertEquals(label, expected.getString("code"), error.code)
                }
                same("$label current", step.getJSONObject("expected_current_snapshot"), identities.normalize(client.snapshotDocument(initial.sessionId)))
            }
        }
    }

    @Test fun reopeningPausesDurablyAndPreservesUnheardFeedbackAndReceipt() = runBlocking {
        withClient { client, name ->
            var snapshot = listen(client, client.create(playersBody("민준", null)))
            val submitted = action(snapshot, "민준, 물가")
            val response = client.command(snapshot.sessionId, submitted)
            snapshot = Snapshot.from(response)
            val unheard = snapshot.speechPlan!!.segments.map { it.text }
            val oldPlayback = commandEnvelope(snapshot, uid(), "playback.completed", JSONObject().put("playback_id", snapshot.speechPlan!!.playbackId))

            val recreated = LocalGameClient(context, name)
            val restored = requireNotNull(recreated.restore())
            assertEquals("PAUSED", restored.status)
            assertNull(restored.speechPlan)
            assertNull(restored.listenWindowId)
            assertEquals(snapshot.turnEpoch + 1, restored.turnEpoch)
            same("game retained", snapshot.game, restored.game)
            same("receipt survives recreation", response, recreated.receipt(snapshot.sessionId, submitted.getString("command_id")))
            reject("STALE_VERSION") { recreated.command(snapshot.sessionId, oldPlayback) }
            assertEquals(restored.version, requireNotNull(LocalGameClient(context, name).restore()).version)

            val resumed = Snapshot.from(recreated.command(restored.sessionId, commandEnvelope(restored, uid(), "session.resume", JSONObject())))
            assertNotEquals(snapshot.turnId, resumed.turnId)
            assertNotEquals(snapshot.speechPlan!!.playbackId, resumed.speechPlan!!.playbackId)
            assertEquals(unheard, resumed.speechPlan!!.segments.map { it.text })
            same("resume cannot apply action twice", snapshot.game, resumed.game)
        }
    }

    @Test fun concurrentInstancesApplyAnIdenticalCommandOnceAndRejectChangedBody() = runBlocking {
        withClient { client, name ->
            val snapshot = listen(client, client.create(playersBody("민준", null)))
            val body = action(snapshot, "물가")
            val other = LocalGameClient(context, name)
            val responses = coroutineScope { List(12) { index -> async {
                (if (index % 2 == 0) client else other).command(snapshot.sessionId, body.copyJson())
            } }.awaitAll() }
            responses.forEach { same("identical retry", responses.first(), it) }
            val current = other.snapshot(snapshot.sessionId)
            assertEquals(snapshot.version + 1, current.version)
            assertEquals(1, current.game.getJSONArray("history").length())
            val changed = body.copyJson()
            changed.getJSONObject("payload").put("text", "숲")
            reject("COMMAND_ID_REUSED") { client.command(snapshot.sessionId, changed) }
            same("rejected body cannot mutate", current.game, client.snapshot(snapshot.sessionId).game)
        }
    }

    @Test fun storageNeverContainsUtterancesAndExplicitDeleteWorksAfterCorruption() = runBlocking {
        withClient { client, name ->
            val snapshot = listen(client, client.create(playersBody("민준", null)))
            val privateUtterance = "이 원문은 저장되면 안 돼 ${uid()}"
            val response = client.command(snapshot.sessionId, action(snapshot, privateUtterance))
            val file = File(context.noBackupFilesDir, "$name.json")
            val persisted = readRecordStoreDocument(client).toString()
            assertFalse(persisted.contains(privateUtterance))
            assertFalse(persisted.contains("input_source"))
            val records = JSONObject(persisted).getJSONObject("sessions").getJSONObject(snapshot.sessionId).getJSONObject("receipts")
            val hashes = records.keyList().map { records.getJSONObject(it).getString("body_hash") }
            assertTrue(hashes.all { Regex("[a-f0-9]{64}").matches(it) })
            assertEquals(0, Snapshot.from(response).game.getJSONArray("history").length())
            file.writeText("broken stored json")
            reject("LOCAL_STORAGE_UNAVAILABLE") { client.restore() }
            client.deleteAll()
            assertNull(LocalGameClient(context, name).restore())
            reject("SESSION_NOT_FOUND") { client.snapshot(snapshot.sessionId) }
            assertFalse(readRecordStoreDocument(client).toString().contains("민준"))
        }
    }

    @Test fun unfinishedFenceWriteKeepsTheLastCommittedState() = runBlocking {
        withClient { client, name ->
            val initial = client.create(playersBody("민준", null))
            val file = File(context.noBackupFilesDir, "$name.json")
            val atomic = AtomicFile(file)
            val interrupted = atomic.startWrite()
            interrupted.write("incomplete new state".toByteArray())
            interrupted.fd.sync()
            interrupted.close() // Simulate death before finishWrite; openRead must recover the commit.
            val recovered = LocalGameClient(context, name).snapshot(initial.sessionId)
            same("previous complete state", initial.game, recovered.game)
            assertEquals(initial.version, recovered.version)
        }
    }

    @Test fun validationDefaultsTouchAndQuietReasonMatchTheCommandContract() = runBlocking {
        withClient { client, _ ->
            val created = client.create(playersBody("민준", null))
            val invalid = commandEnvelope(created, uid(), "session.pause", JSONObject().put("reason", "made_up"))
            reject("INVALID_REQUEST") { client.command(created.sessionId, invalid) }
            assertEquals(created.version, client.snapshot(created.sessionId).version)
            val pause = commandEnvelope(created, uid(), "session.pause", JSONObject())
            val first = client.command(created.sessionId, pause)
            val equivalent = pause.copyJson().put("payload", JSONObject().put("reason", "QUIET_REQUEST"))
            same("validated default participates in hash", first, client.command(created.sessionId, equivalent))
            var snapshot = Snapshot.from(first)
            snapshot = Snapshot.from(client.command(snapshot.sessionId, commandEnvelope(snapshot, uid(), "session.pause", JSONObject().put("reason", "NETWORK"))))
            assertEquals("QUIET_REQUEST", client.snapshotDocument(snapshot.sessionId).getString("pause_reason"))
            snapshot = Snapshot.from(client.command(snapshot.sessionId, commandEnvelope(snapshot, uid(), "session.resume", JSONObject())))
            snapshot = listen(client, snapshot)
            val touch = action(snapshot, "민준, 물가")
            touch.getJSONObject("payload").put("input_source", "touch")
            assertEquals(1, Snapshot.from(client.command(snapshot.sessionId, touch)).game.getJSONArray("history").length())
        }
    }

    @Test fun latestEndedSessionRemainsEndedAndDeletionRemovesEverySession() = runBlocking {
        withClient { client, name ->
            val older = client.create(playersBody("민준", null))
            val latest = client.create(playersBody("민준", "서윤"))
            val end = commandEnvelope(latest, uid(), "session.end", JSONObject())
            val ended = Snapshot.from(client.command(latest.sessionId, end))
            val restored = requireNotNull(LocalGameClient(context, name).restore())
            assertEquals(latest.sessionId, restored.sessionId)
            assertEquals("ENDED", restored.status)
            assertEquals(ended.version, restored.version)
            assertEquals(ended.turnEpoch, restored.turnEpoch)
            assertNull(restored.speechPlan)
            client.deleteAll()
            assertNull(LocalGameClient(context, name).restore())
            reject("SESSION_NOT_FOUND") { client.snapshot(older.sessionId) }
            reject("SESSION_NOT_FOUND") { client.snapshot(latest.sessionId) }
            reject("SESSION_NOT_FOUND") { client.receipt(latest.sessionId, end.getString("command_id")) }
        }
    }

    @Test fun oversizedLogicalRecordIsRejectedButPreservesReceiptAndQuietRecovery() = runBlocking {
        withClient { client, name ->
            val created = client.create(playersBody("민준", null))
            val playback = commandEnvelope(created, uid(), "playback.completed", JSONObject().put("playback_id", created.speechPlan!!.playbackId))
            val receipt = client.command(created.sessionId, playback)
            val listening = Snapshot.from(receipt)
            val before = recordStoreCiphertextSnapshot(client)
            // A per-record limit replaces the old whole-archive quota. Reject an actual oversized
            // root record without evicting any acknowledged command or preventing quiet recovery.
            val stored = readRecordStoreDocument(client)
                .put("_capacity_fixture", "x".repeat(16 * 1024 * 1024))
            reject("LOCAL_STORAGE_FULL") { writeRecordStoreDocument(client, stored) }
            assertEquals("failed write preserves every ciphertext row", before, recordStoreCiphertextSnapshot(client))
            same("failed record write cannot advance play", listening.game, client.snapshot(listening.sessionId).game)
            same("prior receipt survives", receipt, client.receipt(created.sessionId, playback.getString("command_id")))
            same("retry remains idempotent at limit", receipt, client.command(created.sessionId, playback))
            val quiet = requireNotNull(LocalGameClient(context, name).restore())
            assertEquals("PAUSED", quiet.status)
            assertEquals(0, quiet.game.getJSONArray("history").length())
            client.deleteAll()
            assertNull(client.restore())
            assertEquals(0, readRecordStoreDocument(client).getJSONObject("sessions").length())
        }
    }

    @Test fun completionCountComesFromAllDurableSessionsAndSurvivesRecreation() = runBlocking {
        withClient { client, name ->
            assertEquals(0, client.completedAdventures())
            val first = reachEnding(client)
            assertEquals("ACTIVE", first.status) // The ending narration has not been acknowledged.
            assertTrue(first.game.getBoolean("finished"))
            assertEquals(0, client.completedAdventures())
            listen(client, first)
            assertEquals(1, LocalGameClient(context, name).completedAdventures())

            val abandoned = client.create(playersBody("민준", null))
            client.command(abandoned.sessionId, commandEnvelope(abandoned, uid(), "session.end", JSONObject()))
            assertEquals("ending a partial game is not a completed adventure", 1, client.completedAdventures())

            val second = reachEnding(client)
            val completion = commandEnvelope(second, uid(), "playback.completed", JSONObject().put("playback_id", second.speechPlan!!.playbackId))
            client.command(second.sessionId, completion)
            val recreated = LocalGameClient(context, name)
            assertEquals(2, recreated.completedAdventures())
            val before = recreated.snapshotDocument(second.sessionId)
            recreated.restore()
            recreated.command(second.sessionId, completion)
            repeat(3) { assertEquals(2, recreated.completedAdventures()) }
            same("count/restore/retry must not change the committed ending", before, recreated.snapshotDocument(second.sessionId))
            recreated.deleteAll()
            assertEquals(0, client.completedAdventures())
        }
    }

    private suspend fun withClient(block: suspend (LocalGameClient, String) -> Unit) {
        val name = "local_test_${uid().replace("-", "")}" // Never touch the user's real local store.
        val client = LocalGameClient(context, name)
        try { block(client, name) } finally { client.deleteAll() }
    }

    private suspend fun listen(client: LocalGameClient, snapshot: Snapshot): Snapshot = Snapshot.from(client.command(snapshot.sessionId,
        commandEnvelope(snapshot, uid(), "playback.completed", JSONObject().put("playback_id", requireNotNull(snapshot.speechPlan).playbackId))))

    private suspend fun reachEnding(client: LocalGameClient): Snapshot {
        var snapshot = client.create(playersBody("민준", null))
        for (choice in listOf("물가", "바퀴 자국", "나뭇잎", "돋보기", "트리케라톱스", "공작실")) {
            snapshot = listen(client, snapshot)
            snapshot = Snapshot.from(client.command(snapshot.sessionId, action(snapshot, choice)))
        }
        return snapshot
    }

    private fun action(snapshot: Snapshot, text: String) = commandEnvelope(snapshot, uid(), "action.submit", JSONObject()
        .put("text", text).put("is_final", true).put("capture_id", uid()).put("listen_window_id", snapshot.listenWindowId))

    private suspend fun reject(code: String, block: suspend () -> Any?) {
        try { block(); fail("Expected $code") } catch (error: ApiException) { assertEquals(code, error.code) }
    }

    private fun same(label: String, expected: JSONObject, actual: JSONObject) = assertEquals(label, canonicalJson(expected), canonicalJson(actual))
    private fun uid() = UUID.randomUUID().toString()

    /** Renames only protocol IDs, preserving every equality and all other values. */
    private class Identities(fields: JSONObject) {
        private val fields = fields.keyList().associateWith { fields.getString(it) }
        private val names = mutableMapOf<String, MutableMap<String, String>>()

        fun normalize(value: Any?, field: String? = null): Any = when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> JSONObject().also { result -> value.keyList().sorted().forEach { result.put(it, normalize(value.get(it), it)) } }
            is JSONArray -> JSONArray().also { result -> (0 until value.length()).forEach { result.put(normalize(value.get(it), field)) } }
            is String -> fields[field]?.let { namespace ->
                val map = names.getOrPut(namespace) { linkedMapOf() }
                map.getOrPut(value) { "${namespace}_${map.size + 1}" }
            } ?: value
            else -> value
        }

        fun expand(value: Any?, field: String? = null): Any = when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> JSONObject().also { result -> value.keyList().sorted().forEach { result.put(it, expand(value.get(it), it)) } }
            is JSONArray -> JSONArray().also { result -> (0 until value.length()).forEach { result.put(expand(value.get(it), field)) } }
            is String -> fields[field]?.let { namespace ->
                val map = names.getOrPut(namespace) { linkedMapOf() }
                map.entries.firstOrNull { it.value == value }?.key ?: "fixture_$value".also { map[it] = value }
            } ?: value
            else -> value
        }

        fun normalize(value: JSONObject): JSONObject = normalize(value as Any) as JSONObject
    }
}
