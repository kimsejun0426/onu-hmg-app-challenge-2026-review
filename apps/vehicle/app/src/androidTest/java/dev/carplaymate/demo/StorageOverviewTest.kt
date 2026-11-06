package dev.carplaymate.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageOverviewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun emptyOverviewDoesNotInventAStoreAndCommittedOverviewMatchesSeparateQueries() = runBlocking {
        scoped { client, name ->
            val work = observe(client)
            assertNull(client.restoreAndOverview().snapshot)
            assertFalse(File(context.noBackupFilesDir, "$name.json").exists())
            assertFalse(File(context.noBackupFilesDir, "$name.records.sqlite").exists())
            assertNoCommit(work)
            val completed = complete(client)
            client.setFeedback(completed.sessionId, CHILD, AdventureFeedback.LIKED)
            client.createAdventure(playersBody("시험", null), "undersea-lighthouse")
            val active = client.createAdventure(playersBody("시험", null), "starlight-post-office")
            work.clear()
            val overview = client.restoreAndOverview()
            assertCommit(work)
            assertEquals(active.version + 1, overview.snapshot!!.version)
            assertEquals("PAUSED", overview.snapshot.status)
            assertNull(overview.snapshot.speechPlan)
            assertNull(overview.snapshot.listenWindowId)
            assertEquals(client.journal(), overview.journal)
            assertEquals(client.savedAdventures(), overview.savedAdventures)
            assertEquals(client.feedback(), overview.feedback)
            assertEquals(listOf(completed.sessionId), overview.journal.map { it.sessionId })
            val committed = recordStoreCiphertextSnapshot(client)
            work.clear()
            val again = client.restoreAndOverview()
            assertEquals(overview.snapshot.version, again.snapshot!!.version)
            assertNoCommit(work)
            assertEquals(committed, recordStoreCiphertextSnapshot(client))
        }
    }

    @Test fun damagedOverviewMetadataFailsAfterDurablePauseAndPreservesEveryOtherRecord() = runBlocking {
        scoped { client, _ ->
            val completed = complete(client)
            val active = client.createAdventure(playersBody("시험", null), "starlight-post-office")
            val store = readRecordStoreDocument(client)
            val broken = store.getJSONObject("sessions").getJSONObject(completed.sessionId)
            broken.getJSONObject("metadata").put("participant_profile_ids", "damaged")
            val preserved = canonicalJson(broken)
            writeRecordStoreDocument(client, store)
            val work = observe(client)
            reject("LOCAL_STORAGE_UNAVAILABLE") { client.restoreAndOverview() }
            assertCommit(work)
            val saved = client.snapshot(active.sessionId)
            assertEquals("PAUSED", saved.status)
            assertEquals(active.version + 1, saved.version)
            assertEquals(active.turnEpoch + 1, saved.turnEpoch)
            assertNull(saved.speechPlan)
            assertNull(saved.listenWindowId)
            assertEquals(preserved, canonicalJson(readRecordStoreDocument(client).getJSONObject("sessions").getJSONObject(completed.sessionId)))
            val committed = recordStoreCiphertextSnapshot(client)
            work.clear()
            reject("LOCAL_STORAGE_UNAVAILABLE") { client.restoreAndOverview() }
            assertNoCommit(work)
            assertEquals(committed, recordStoreCiphertextSnapshot(client))
        }
    }

    @Test fun failedPauseCommitReturnsNoOverviewAndKeepsTheActiveCommit() = runBlocking {
        scoped { client, _ ->
            val active = client.create(playersBody("시험", null))
            val committed = recordStoreCiphertextSnapshot(client)
            var failures = 0
            client.recordStore.checkpoint = { stage ->
                if (stage == RecordStoreStage.BEFORE_COMMIT) {
                    failures += 1
                    throw IOException("Synthetic failure before SQLite commit")
                }
            }
            try {
                reject("LOCAL_STORAGE_UNAVAILABLE") { client.restoreAndOverview() }
                assertEquals(1, failures)
                assertEquals(committed, recordStoreCiphertextSnapshot(client))
                assertEquals("ACTIVE", client.snapshot(active.sessionId).status)
            } finally {
                client.recordStore.checkpoint = null
            }
            assertEquals("PAUSED", client.restoreAndOverview().snapshot!!.status)
        }
    }

    @Test fun oldReceiptIsReturnedBeforeSupersessionChecksWithoutChangingCiphertextRows() = runBlocking {
        scoped { client, _ ->
            val active = client.create(playersBody("시험", null))
            val request = commandEnvelope(active, uid(), "session.pause", JSONObject())
            val original = client.command(active.sessionId, request)
            client.create(playersBody("시험", null)) // The old session is superseded.
            val committed = recordStoreCiphertextSnapshot(client)
            val work = observe(client)
            val retry = client.command(active.sessionId, request.copyJson())
            assertEquals(canonicalJson(original), canonicalJson(retry))
            assertNoCommit(work)
            assertEquals(committed, recordStoreCiphertextSnapshot(client))
            retry.put("command_status", "caller changed its copy")
            assertEquals(canonicalJson(original), canonicalJson(client.receipt(active.sessionId, request.getString("command_id"))))
            val different = request.copyJson().put("payload", JSONObject().put("reason", "NETWORK"))
            reject("COMMAND_ID_REUSED") { client.command(active.sessionId, different) }
            assertEquals(committed, recordStoreCiphertextSnapshot(client))
        }
    }

    @Test fun selectingAnotherQuietSessionIsAMutationButRepeatedLegacyReplacementIsNot() = runBlocking {
        scoped { client, _ ->
            val first = client.create(playersBody("시험", null))
            client.restore()
            client.createAdventure(playersBody("시험", null), "starlight-post-office")
            client.restore()
            val work = observe(client)
            val selected = client.restoreAndOverview(first.sessionId)
            assertEquals(first.sessionId, selected.snapshot!!.sessionId)
            assertCommit(work)
            val original = readRecordStoreDocument(client)
            val sessions = original.getJSONObject("sessions")
            val old = sessions.getJSONObject(first.sessionId).copyJson()
            old.getJSONObject("state").put("session_id", "legacy-older")
            old.remove("metadata")
            sessions.put("legacy-older", old)
            sessions.getJSONObject(first.sessionId).remove("metadata")
            writeRecordStoreDocument(client, original)
            work.clear()
            client.restoreAndOverview()
            assertCommit(work)
            assertEquals(first.sessionId, readRecordStoreDocument(client).getJSONObject("sessions").getJSONObject("legacy-older")
                .getJSONObject("metadata").getString("superseded_by"))
            val committed = recordStoreCiphertextSnapshot(client)
            work.clear()
            client.restoreAndOverview()
            assertNoCommit(work)
            assertEquals(committed, recordStoreCiphertextSnapshot(client))
        }
    }

    @Test fun feedbackNoOpsRetainTimestampsAndStillValidateExistingEntries() = runBlocking {
        scoped { client, _ ->
            val completed = complete(client)
            val work = observe(client)
            client.setFeedback(completed.sessionId, CHILD, null)
            assertCommit(work)
            assertEquals(0, readRecordStoreDocument(client).getJSONObject("sessions").getJSONObject(completed.sessionId)
                .getJSONObject("metadata").getJSONObject("feedback").length())
            work.clear()
            client.setFeedback(completed.sessionId, CHILD, null)
            assertNoCommit(work)
            client.setFeedback(completed.sessionId, CHILD, AdventureFeedback.LIKED)
            val entries = client.feedback()
            val committed = recordStoreCiphertextSnapshot(client)
            work.clear()
            client.setFeedback(completed.sessionId, CHILD, AdventureFeedback.LIKED)
            assertNoCommit(work)
            assertEquals(committed, recordStoreCiphertextSnapshot(client))
            assertEquals(entries, client.feedback())
            val damaged = readRecordStoreDocument(client)
            damaged.getJSONObject("sessions").getJSONObject(completed.sessionId).getJSONObject("metadata")
                .getJSONObject("feedback").put("unvisited", JSONObject().put("choice", "future").put("recorded_at_ms", 1))
            writeRecordStoreDocument(client, damaged)
            val before = recordStoreCiphertextSnapshot(client)
            reject("LOCAL_STORAGE_VERSION_UNAVAILABLE") { client.setFeedback(completed.sessionId, CHILD, AdventureFeedback.LIKED) }
            assertEquals(before, recordStoreCiphertextSnapshot(client))
        }
    }

    @Test fun unchangedLegacyRestorePreservesExactPayloadAndReceiptAcrossMigration() = runBlocking {
        scoped { seed, _ ->
            val initial = seed.create(playersBody("시험", null))
            val command = commandEnvelope(initial, uid(), "session.pause", JSONObject())
            val receipt = seed.command(initial.sessionId, command)
            val document = readRecordStoreDocument(seed).put("opaque_extension", JSONObject().put("retained", true))
            val legacy = (document.toString(2) + "\n").toByteArray()
            val name = "overview_legacy_${uid().replace("-", "")}";
            File(context.noBackupFilesDir, "$name.json").writeBytes(legacy)
            val client = LocalGameClient(context, name)
            try {
                val overview = client.restoreAndOverview()
                assertEquals("PAUSED", overview.snapshot!!.status)
                assertEquals(canonicalJson(document), canonicalJson(readRecordStoreDocument(client)))
                val preserved = File(context.noBackupFilesDir, "$name.legacy-v1.enc")
                assertArrayEquals(legacy, PrivateRecordCipher(context, "local-game:$name").decrypt(preserved.readBytes()))
                assertEquals(canonicalJson(receipt), canonicalJson(client.receipt(initial.sessionId, command.getString("command_id"))))
                val committed = recordStoreCiphertextSnapshot(client)
                val work = observe(client)
                client.restoreAndOverview()
                assertNoCommit(work)
                assertEquals(committed, recordStoreCiphertextSnapshot(client))
            } finally {
                client.recordStore.checkpoint = null
                client.deleteAll()
            }
        }
    }

    @Test fun explicitPauseStillFencesAlreadyQuietStateAndFailedCommandDoesNotCommitLegacyMetadata() = runBlocking {
        scoped { client, _ ->
            val first = client.create(playersBody("시험", null))
            val quiet = client.restore()!!
            val work = observe(client)
            val explicit = Snapshot.from(client.command(quiet.sessionId, commandEnvelope(quiet, uid(), "session.pause", JSONObject())))
            assertEquals(quiet.turnEpoch + 1, explicit.turnEpoch)
            assertEquals(quiet.version + 1, explicit.version)
            assertCommit(work)
            val store = readRecordStoreDocument(client)
            val sessions = store.getJSONObject("sessions")
            val old = sessions.getJSONObject(first.sessionId).copyJson()
            old.getJSONObject("state").put("session_id", "legacy-older")
            old.remove("metadata")
            sessions.put("legacy-older", old)
            sessions.getJSONObject(first.sessionId).remove("metadata")
            writeRecordStoreDocument(client, store)
            val committed = recordStoreCiphertextSnapshot(client)
            reject("STALE_VERSION") { client.command(first.sessionId, commandEnvelope(first, uid(), "session.pause", JSONObject())) }
            assertEquals(committed, recordStoreCiphertextSnapshot(client))
        }
    }

    @Test fun encryptedSelectionTypeNormalizationIsARealMutation() = runBlocking {
        scoped { client, _ ->
            val initial = client.create(playersBody("시험", null))
            client.restore()
            val store = readRecordStoreDocument(client)
            val record = store.getJSONObject("sessions").getJSONObject(initial.sessionId)
            record.getJSONObject("state").put("session_id", "123")
            store.put("sessions", JSONObject().put("123", record)).put("last_session_id", 123)
            writeRecordStoreDocument(client, store)
            val work = observe(client)
            assertEquals("123", client.restoreAndOverview().snapshot!!.sessionId)
            assertCommit(work)
            assertEquals("123", readRecordStoreDocument(client).get("last_session_id"))
            val committed = recordStoreCiphertextSnapshot(client)
            work.clear()
            client.restoreAndOverview()
            assertNoCommit(work)
            assertEquals(committed, recordStoreCiphertextSnapshot(client))
        }
    }

    private fun observe(client: LocalGameClient) = mutableListOf<RecordStoreStage>().also { work ->
        client.recordStore.checkpoint = { work.add(it) }
    }
    private fun assertCommit(work: List<RecordStoreStage>) {
        assertEquals(1, work.count { it == RecordStoreStage.BEFORE_COMMIT })
        assertEquals(1, work.count { it == RecordStoreStage.AFTER_COMMIT })
    }
    private fun assertNoCommit(work: List<RecordStoreStage>) {
        assertEquals(0, work.count { it == RecordStoreStage.BEFORE_COMMIT || it == RecordStoreStage.AFTER_COMMIT })
    }
    private suspend fun scoped(block: suspend (LocalGameClient, String) -> Unit) {
        val name = "overview_${uid().replace("-", "")}";
        val client = LocalGameClient(context, name)
        try { block(client, name) } finally { client.recordStore.checkpoint = null; client.deleteAll() }
    }
    private suspend fun complete(client: LocalGameClient): Snapshot {
        var current = client.createFamilyAdventure(playersBody("시험", null), DEFAULT_ADVENTURE_ID, mapOf("p1" to CHILD))
        var choices = 0
        while (!current.game.getBoolean("finished")) {
            current = Snapshot.from(client.command(current.sessionId, commandEnvelope(current, uid(), "playback.completed",
                JSONObject().put("playback_id", current.speechPlan!!.playbackId))))
            current = Snapshot.from(client.command(current.sessionId, commandEnvelope(current, uid(), "action.submit", JSONObject()
                .put("text", current.presentation.getJSONArray("actions").getJSONObject(0).getString("label"))
                .put("is_final", true).put("capture_id", uid()).put("listen_window_id", current.listenWindowId))))
            assertTrue(++choices <= 12)
        }
        return Snapshot.from(client.command(current.sessionId, commandEnvelope(current, uid(), "playback.completed",
            JSONObject().put("playback_id", current.speechPlan!!.playbackId))))
    }
    private suspend fun reject(code: String, block: suspend () -> Any?) {
        try { block(); fail("Expected $code") } catch (error: ApiException) { assertEquals(code, error.code) }
    }
    private fun uid() = UUID.randomUUID().toString()
    companion object { private const val CHILD = "synthetic-child" }
}
