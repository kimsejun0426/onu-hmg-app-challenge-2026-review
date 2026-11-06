package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit, observational scale run; intentionally absent from android_smoke's class list. Enable
 * with -e class dev.carplaymate.demo.StorageScaleTest -e storageScaleTests true. Timings are
 * measurements, never device-dependent pass/fail thresholds.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class StorageScaleTest {
    @Test
    fun completedArchivesAtOneFiveAndFourteenMiBPreserveRecordsAndIdempotency(): Unit =
        runBlocking {
            assumeTrue(
                "Run this large archive measurement explicitly.",
                InstrumentationRegistry.getArguments().getString("storageScaleTests") == "true",
            )
            assertNotSame(
                "The instrumentation workload must not run on the UI thread.",
                Looper.getMainLooper(),
                Looper.myLooper(),
            )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val app = ScopedApplication(context)
            val storage = recordsStorage()
            val rows = JSONArray()
            val report =
                JSONObject()
                    .put("schema_version", 2)
                    .put("measurements", rows)
                    .put("completed", false)
            try {
                writeReport(context, report)
                val seed = makeSeed(app, storage)
                for (targetMiB in listOf(1, 5, 14)) {
                    val row =
                        JSONObject()
                            .put("target_mib", targetMiB)
                            .put("elapsed_ms", JSONObject())
                            .put("checks_passed", false)
                    rows.put(row)
                    val name = "scale_$targetMiB"
                    val fixture =
                        measured(row, "fixture_build") { writeArchive(app, name, seed, targetMiB) }
                    row.put("plaintext_bytes", fixture.plaintextBytes)
                        .put("stored_bytes_before", fixture.file.length())
                        .put("completed_sessions", fixture.completedHashes.size)
                        .put("unfinished_sessions", 1)
                        .put("receipt_count", fixture.completedHashes.size * seed.requests.size)
                    writeReport(context, report)

                    // A fresh client reads the actual on-disk archive, without a shared in-memory
                    // store.
                    val client = LocalGameClient(app, name)
                    measured(row, "migration") { storage.migrate(client) }
                    val restored =
                        measured(row, "restore_and_pause") { requireNotNull(client.restore()) }
                    assertEquals("PAUSED", restored.status)
                    assertEquals(seed.unfinishedId, restored.sessionId)
                    assertNull(restored.speechPlan)
                    assertNull(restored.listenWindowId)
                    assertEquals(seed.unfinishedVersion + 1, restored.version)
                    assertEquals(seed.unfinishedEpoch + 1, restored.turnEpoch)
                    assertEquals(seed.unfinishedGame, canonicalJson(restored.game))

                    measured(row, "verify_completed_record_preservation") {
                        storage.verifyCompleted(app, name, client, fixture.completedHashes, seed.unfinishedId)
                    }
                    val afterRestore = storage.ciphertextHash(app, name, client)
                    row.put("stored_bytes_after_restore", storage.storedBytes(app, name, client))

                    val journal = measured(row, "journal") { client.journal() }
                    assertEquals(fixture.completedHashes.size, journal.size)
                    assertTrue(
                        "Every completed session remains in the journal.",
                        journal.map { it.sessionId }.toSet() == fixture.completedHashes.keys,
                    )
                    assertTrue(journal.all { it.choicesCount == seed.choicesCount })

                    val saved = measured(row, "saved_adventures") { client.savedAdventures() }
                    assertEquals(1, saved.size)
                    assertEquals(seed.unfinishedId, saved.single().sessionId)

                    val feedback = measured(row, "feedback") { client.feedback() }
                    assertEquals(fixture.completedHashes.size, feedback.size)
                    assertTrue(
                        feedback.all {
                            it.feedback == AdventureFeedback.LIKED && it.childId == CHILD_ID
                        }
                    )
                    assertTrue(
                        "Each synthetic completed session keeps its feedback.",
                        feedback.map { it.sessionId }.toSet() == fixture.completedHashes.keys,
                    )

                    val storedReceipt =
                        measured(row, "receipt_lookup") {
                            client.receipt(
                                seed.completedId,
                                seed.finalRequest.getString("command_id"),
                            )
                        }
                    assertEquals(seed.finalResult, canonicalJson(storedReceipt))
                    repeat(3) { index ->
                        val retried =
                            measured(row, "same_command_retry_$index") {
                                client.command(seed.completedId, seed.finalRequest.copyJson())
                            }
                        assertEquals(
                            "An acknowledged command cannot be applied twice.",
                            seed.finalResult,
                            canonicalJson(retried),
                        )
                    }
                    val clonedRetry =
                        measured(row, "cloned_command_retry") {
                            client.command(
                                fixture.replay.sessionId,
                                fixture.replay.request.copyJson(),
                            )
                        }
                    assertEquals(
                        "Renamed receipt hashes must remain valid command acknowledgements.",
                        fixture.replay.result,
                        canonicalJson(clonedRetry),
                    )
                    val reopened =
                        measured(row, "reopen_quiet_archive") {
                            requireNotNull(LocalGameClient(app, name).restore())
                        }
                    assertEquals("PAUSED", reopened.status)
                    assertEquals(restored.version, reopened.version)
                    assertEquals(restored.turnEpoch, reopened.turnEpoch)
                    assertEquals(seed.unfinishedGame, canonicalJson(reopened.game))
                    assertEquals("Queries and retries keep every encrypted record unchanged.",
                        afterRestore, storage.ciphertextHash(app, name, client))
                    storage.verifyCompleted(app, name, client, fixture.completedHashes, seed.unfinishedId)
                    row.put("idempotent_retries", 4).put("checks_passed", true)
                    writeReport(context, report)
                    client.deleteAll()
                }
                report.put("completed", true)
            } finally {
                try {
                    writeReport(context, report)
                } finally {
                    withContext(Dispatchers.IO) { app.clean() }
                }
            }
        }

    /** Paired opt-in measurement. Retains ONLY encrypted synthetic fixtures in a fixed test scope. */
    @Test
    fun identicalRetainedArchivesCompareStartupAndNoChangeOperations(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("storageComparisonMode")
        assumeTrue("Explicit paired measurement only", mode in setOf("baseline", "records"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = ScopedApplication(context, "storage_comparison_v2")
        val controlFile = File(app.noBackupFilesDir, "fixture-control.enc")
        val controlCipher = PrivateRecordCipher(app, "storage-scale:fixture-control")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        assertEquals("Each mode is tied to its actual APK", if (mode == "baseline") "0.12.0-demo" else "0.13.0-demo", version)
        // This interface and the baseline adapter reference only APIs present in 0.12. The
        // SQLite adapter is verified/loaded only after choosing the 0.13 records mode.
        val storage: ScaleStorage = if (mode == "baseline") BaselineScaleStorage() else recordsStorage()
        val existing = withContext(Dispatchers.IO) {
            if (controlFile.isFile) JSONObject(controlCipher.decrypt(controlFile.readBytes()).toString(Charsets.UTF_8)) else null
        }
        if (existing == null) {
            assertEquals("Only the preserved baseline may generate the comparison fixture", "baseline", mode)
        }
        val seed = if (existing == null) makeSeed(app, storage) else seedFrom(existing.getJSONObject("seed"))
        val control = existing ?: JSONObject().put("schema_version", 2).put("fixture_origin", "0.12.0-demo")
            .put("seed", seedDocument(seed)).put("fixtures", JSONObject())
        assertEquals(2, control.getInt("schema_version"))
        assertEquals("0.12.0-demo", control.getString("fixture_origin"))
        val repeats = (args.getString("storageComparisonRepeats") ?: "3").toInt().also { require(it in 1..10) }
        val rows = JSONArray()
        val report = JSONObject().put("schema_version", 2).put("mode", mode).put("app_version", version)
            .put("fixture_origin", "0.12.0-demo").put("fixture_scope", "storage_comparison_v2")
            .put("record_work_scope", if (mode == "records")
                "Candidate-only partial payload work; excludes fence/manifest, SQL metadata/count, domain logic and migration commits. Candidate elapsed includes observer overhead; baseline has no equivalent stage instrumentation."
                else JSONObject.NULL)
            .put("ciphertext_scope", if (mode == "records") "ordered live database records" else "live encrypted v1 archive")
            .put("stored_bytes_scope", if (mode == "records") "database and SQLite sidecars" else "live encrypted v1 archive")
            .put("repeats", repeats).put("measurements", rows).put("completed", false)
        val reportName = "storage-comparison-$mode.json"
        writeReport(context, report, reportName)
        try { for (targetMiB in listOf(1, 5, 14)) {
            val name = "scale_$targetMiB"
            val original = File(app.noBackupFilesDir, "$name.original")
            if (!original.exists()) {
                assertEquals("Only baseline may create missing fixture bytes", "baseline", mode)
                val generated = writeArchive(app, name, seed, targetMiB)
                withContext(Dispatchers.IO) { generated.file.copyTo(original, overwrite = false) }
                control.getJSONObject("fixtures").put(name, digest(original.readBytes()))
                withContext(Dispatchers.IO) {
                    val atomic = AtomicFile(controlFile)
                    val stream = atomic.startWrite()
                    try {
                        stream.write(controlCipher.encrypt(control.toString().toByteArray(Charsets.UTF_8)))
                        stream.fd.sync()
                        atomic.finishWrite(stream)
                    } catch (error: Throwable) { atomic.failWrite(stream); throw error }
                }
            }
            val expectedCipherHash = control.getJSONObject("fixtures").getString(name)
            val controlHash = withContext(Dispatchers.IO) { digest(controlFile.readBytes()) }
            val fixture = archiveFromOriginal(app, name, original, seed, expectedCipherHash)
            val plaintextHash = withContext(Dispatchers.IO) { digest(fixture.cipher.decrypt(original.readBytes())) }
            repeat(repeats) { iteration ->
                // Delete through the installed backend so fence, DB, journal and migration
                // artifacts cannot leak from an earlier repeat. Original/control/key stay put.
                storage.reset(app, name, LocalGameClient(app, name))
                withContext(Dispatchers.IO) {
                    assertEquals(expectedCipherHash, digest(original.readBytes()))
                    assertEquals("Namespace deletion keeps encrypted control", controlHash, digest(controlFile.readBytes()))
                    val leftovers = app.noBackupFilesDir.listFiles().orEmpty()
                        .filter { it.name.startsWith("$name.") && it != original }
                    assertTrue("Namespace cleanup must be complete: ${leftovers.map { it.name }}", leftovers.isEmpty())
                    original.copyTo(fixture.file, overwrite = false)
                    assertEquals(expectedCipherHash, digest(fixture.file.readBytes()))
                }
                val row = JSONObject().put("target_mib", targetMiB).put("iteration", iteration)
                    .put("fixture_sha256", expectedCipherHash).put("plaintext_sha256", plaintextHash)
                    .put("plaintext_bytes", fixture.plaintextBytes).put("completed_sessions", fixture.completedHashes.size)
                    .put("receipt_count", fixture.completedHashes.size * seed.requests.size)
                    .put("elapsed_ms", JSONObject()).put("heap_observations", JSONObject()).put("checks_passed", false)
                    .put("record_work", JSONObject())
                    .put("migration_applicable", mode == "records")
                rows.put(row)
                val client = LocalGameClient(app, name)
                if (mode == "records") compared(row, "migration", storage, client) { storage.migrate(client) }
                else row.getJSONObject("elapsed_ms").put("migration", JSONObject.NULL)
                val overview = compared(row, "startup", storage, client) { loadOverview(client) }
                assertEquals("PAUSED", overview.snapshot!!.status)
                assertNull(overview.snapshot.speechPlan)
                assertNull(overview.snapshot.listenWindowId)
                assertEquals(seed.unfinishedVersion + 1, overview.snapshot.version)
                assertEquals(seed.unfinishedEpoch + 1, overview.snapshot.turnEpoch)
                assertEquals(seed.unfinishedGame, canonicalJson(overview.snapshot.game))
                assertEquals(fixture.completedHashes.keys, overview.journal.map { it.sessionId }.toSet())
                assertTrue(overview.journal.all { it.choicesCount == seed.choicesCount })
                assertEquals(listOf(seed.unfinishedId), overview.saved.map { it.sessionId })
                assertEquals(fixture.completedHashes.keys, overview.feedback.map { it.sessionId }.toSet())
                assertTrue(overview.feedback.all { it.feedback == AdventureFeedback.LIKED && it.childId == CHILD_ID })
                storage.verifyCompleted(app, name, client, fixture.completedHashes, seed.unfinishedId)
                val quietBytes = storage.ciphertextHash(app, name, client)
                row.put("stored_bytes_after_restore", storage.storedBytes(app, name, client))
                    .put("quiet_ciphertext_sha256", quietBytes)
                val quietClient = LocalGameClient(app, name)
                val quiet = compared(row, "quiet_startup", storage, quietClient) { loadOverview(quietClient) }
                assertEquals(overview.snapshot.version, quiet.snapshot!!.version)
                assertEquals(overview.snapshot.turnEpoch, quiet.snapshot.turnEpoch)
                assertEquals("PAUSED", quiet.snapshot.status)
                assertNull(quiet.snapshot.speechPlan)
                assertNull(quiet.snapshot.listenWindowId)
                assertEquals(seed.unfinishedGame, canonicalJson(quiet.snapshot.game))
                val receipt = compared(row, "receipt_retry", storage, client) { client.command(seed.completedId, seed.finalRequest.copyJson()) }
                assertEquals(seed.finalResult, canonicalJson(receipt))
                val clone = compared(row, "cloned_receipt_retry", storage, client) { client.command(fixture.replay.sessionId, fixture.replay.request.copyJson()) }
                assertEquals(fixture.replay.result, canonicalJson(clone))
                compared(row, "same_feedback", storage, client) { client.setFeedback(seed.completedId, CHILD_ID, AdventureFeedback.LIKED) }
                assertEquals("Every no-change operation keeps actual ciphertext unchanged", quietBytes,
                    storage.ciphertextHash(app, name, client))
                withContext(Dispatchers.IO) {
                    assertEquals("The source fixture is immutable", expectedCipherHash, digest(original.readBytes()))
                }
                val pauseId = UUID.nameUUIDFromBytes("scale-pause-$targetMiB-$iteration".toByteArray()).toString()
                val pauseRequest = commandEnvelope(quiet.snapshot, pauseId, "session.pause", JSONObject().put("reason", "QUIET_REQUEST"))
                val paused = Snapshot.from(compared(row, "mutating_pause", storage, client) { client.command(seed.unfinishedId, pauseRequest) })
                assertEquals(quiet.snapshot.version + 1, paused.version)
                assertEquals(quiet.snapshot.turnEpoch + 1, paused.turnEpoch)
                assertNull(paused.speechPlan)
                assertNull(paused.listenWindowId)
                assertEquals(seed.unfinishedGame, canonicalJson(paused.game))
                storage.verifyCompleted(app, name, client, fixture.completedHashes, seed.unfinishedId)
                assertEquals(expectedCipherHash, withContext(Dispatchers.IO) { digest(original.readBytes()) })
                assertEquals(controlHash, withContext(Dispatchers.IO) { digest(controlFile.readBytes()) })
                row.put("completed_records_and_receipts_verified", fixture.completedHashes.size)
                    .put("checks_passed", true)
                writeReport(context, report, reportName)
            }
        }
        report.put("completed", true)
        } finally { writeReport(context, report, reportName) }
        // Deliberately retained for the paired APK. These synthetic encrypted fixtures are
        // isolated from the family's local_game_v1 and are removed explicitly after comparison.
    }

    private suspend fun <T> compared(row: JSONObject, name: String, storage: ScaleStorage,
        client: LocalGameClient, block: suspend () -> T): T {
        val runtime = Runtime.getRuntime()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val gcBefore = android.os.Debug.getRuntimeStat("art.gc.gc-count")
        storage.startWork(client)
        try { return measured(row, name, block) } finally {
            storage.stopWork(client)?.let { row.getJSONObject("record_work").put(name, it) }
            row.getJSONObject("heap_observations").put(name, JSONObject()
                .put("used_before_bytes", before).put("used_after_bytes", runtime.totalMemory() - runtime.freeMemory())
                .put("gc_count_before", gcBefore).put("gc_count_after", android.os.Debug.getRuntimeStat("art.gc.gc-count")))
        }
    }

    private suspend fun archiveFromOriginal(app: ScopedApplication, name: String, original: File, seed: Seed, hash: String): Archive =
        withContext(Dispatchers.IO) {
            assertEquals(hash, digest(original.readBytes()))
            val cipher = PrivateRecordCipher(app, "local-game:$name")
            val plaintext = cipher.decrypt(original.readBytes())
            val sessions = JSONObject(plaintext.toString(Charsets.UTF_8)).getJSONObject("sessions")
            val hashes = linkedMapOf<String, String>()
            for (id in sessions.keyList()) if (id != seed.unfinishedId)
                hashes[id] = digest(canonicalJson(sessions.getJSONObject(id)).toByteArray(Charsets.UTF_8))
            // A cloned receipt request can be reconstructed from the recorded result's IDs and
            // its stored original request mapping, retained with the encrypted control document.
            val replayId = hashes.keys.last { it != seed.completedId }
            val record = sessions.getJSONObject(replayId)
            val lastReceipt = record.getJSONObject("receipts").keyList().map { id -> id to record.getJSONObject("receipts").getJSONObject(id) }
                .maxBy { (_, entry) -> Snapshot.from(entry.getJSONObject("result")).version }
            val request = seed.finalRequest.copyJson().put("command_id", lastReceipt.first)
            val result = lastReceipt.second.getJSONObject("result")
            val beforeEnding = record.getJSONObject("receipts").keyList().map { record.getJSONObject("receipts").getJSONObject(it).getJSONObject("result") }
                .map { Snapshot.from(it) }.single { it.version == request.getLong("expected_version") }
            request.put("turn_id", beforeEnding.turnId).put("turn_epoch", beforeEnding.turnEpoch)
            request.getJSONObject("payload").put("playback_id", beforeEnding.speechPlan!!.playbackId)
            assertEquals(lastReceipt.second.getString("body_hash"), digest(canonicalJson(request).toByteArray(Charsets.UTF_8)))
            Archive(File(app.noBackupFilesDir, "$name.json"), hashes, Replay(replayId, request, canonicalJson(result)), cipher, plaintext.size)
        }

    private fun seedDocument(seed: Seed) = JSONObject()
        .put("completed_id", seed.completedId).put("completed_record", seed.completedRecord).put("requests", JSONObject(seed.requests))
        .put("final_request", seed.finalRequest).put("final_result", seed.finalResult).put("choices", seed.choicesCount)
        .put("unfinished_id", seed.unfinishedId).put("unfinished_record", seed.unfinishedRecord)
        .put("unfinished_version", seed.unfinishedVersion).put("unfinished_epoch", seed.unfinishedEpoch).put("unfinished_game", seed.unfinishedGame)

    private fun seedFrom(data: JSONObject) = Seed(data.getString("completed_id"), data.getJSONObject("completed_record"),
        data.getJSONObject("requests").let { obj -> obj.keyList().associateWith { obj.getJSONObject(it) } },
        data.getJSONObject("final_request"), data.getString("final_result"), data.getInt("choices"), data.getString("unfinished_id"),
        data.getJSONObject("unfinished_record"), data.getLong("unfinished_version"), data.getLong("unfinished_epoch"), data.getString("unfinished_game"))

    /** Generate authentic snapshots and receipts by completing the bundled authored adventure. */
    private suspend fun makeSeed(app: ScopedApplication, storage: ScaleStorage): Seed {
        var clock = 1_800_000_000_000L
        val client = LocalGameClient(app, "seed") { clock.also { clock += 1_000 } }
        val requests = linkedMapOf<String, JSONObject>()
        suspend fun send(snapshot: Snapshot, type: String, payload: JSONObject): JSONObject {
            val request = commandEnvelope(snapshot, uid(), type, payload)
            val result = client.command(snapshot.sessionId, request)
            requests[request.getString("command_id")] = request.copyJson()
            return result
        }
        var current =
            client.createFamilyAdventure(
                playersBody("시험아이", null),
                DEFAULT_ADVENTURE_ID,
                mapOf("p1" to CHILD_ID),
            )
        var choices = 0
        while (!current.game.getBoolean("finished")) {
            current =
                Snapshot.from(
                    send(
                        current,
                        "playback.completed",
                        JSONObject()
                            .put("playback_id", requireNotNull(current.speechPlan).playbackId),
                    )
                )
            val label =
                current.presentation.getJSONArray("actions").getJSONObject(0).getString("label")
            current =
                Snapshot.from(
                    send(
                        current,
                        "action.submit",
                        JSONObject()
                            .put("text", label)
                            .put("is_final", true)
                            .put("capture_id", uid())
                            .put("listen_window_id", current.listenWindowId)
                            .put("locale", "ko-KR")
                            .put("input_source", "touch"),
                    )
                )
            assertTrue("The authored seed must finish.", ++choices <= 64)
        }
        val finalResult =
            send(
                current,
                "playback.completed",
                JSONObject().put("playback_id", requireNotNull(current.speechPlan).playbackId),
            )
        val completed = Snapshot.from(finalResult)
        assertEquals("ENDED", completed.status)
        client.setFeedback(completed.sessionId, CHILD_ID, AdventureFeedback.LIKED)
        val unfinished =
            client.createFamilyAdventure(
                playersBody("시험아이", null),
                "starlight-post-office",
                mapOf("p1" to CHILD_ID),
            )
        val records = storage.document(app, "seed", client).getJSONObject("sessions")
        val record = records.getJSONObject(completed.sessionId)
        assertEquals(requests.size, record.getJSONObject("receipts").length())
        return Seed(
            completed.sessionId,
            record,
            requests,
            requests.values.last().copyJson(),
            canonicalJson(finalResult),
            choices,
            unfinished.sessionId,
            records.getJSONObject(unfinished.sessionId),
            unfinished.version,
            unfinished.turnEpoch,
            canonicalJson(unfinished.game),
        )
    }

    /**
     * Write whole realistic session/receipt records until the requested size is reached. Size may
     * exceed the target by at most one record; no padding or invented huge strings.
     */
    private suspend fun writeArchive(
        app: ScopedApplication,
        name: String,
        seed: Seed,
        targetMiB: Int,
    ): Archive =
        withContext(Dispatchers.IO) {
            check(Looper.myLooper() != Looper.getMainLooper())
            val file = File(app.noBackupFilesDir, "$name.json")
            val hashes = linkedMapOf<String, String>()
            var bytes = 0L
            var lastCopy =
                SessionCopy(seed.completedRecord.copyJson(), seed.finalRequest.copyJson())
            file.outputStream().buffered().use { output ->
                fun append(text: String) {
                    val encoded = text.toByteArray(Charsets.UTF_8)
                    output.write(encoded)
                    bytes += encoded.size
                }
                append("{\"format_version\":1,\"sessions\":{")
                append(JSONObject.quote(seed.unfinishedId) + ":" + seed.unfinishedRecord.toString())
                var index = 0
                while (bytes < targetMiB.toLong() * MIB || index == 0) {
                    if (index > 0) lastCopy = cloneCompleted(seed, index)
                    val record = lastCopy.record
                    val id = record.getJSONObject("state").getString("session_id")
                    hashes[id] = digest(canonicalJson(record).toByteArray(Charsets.UTF_8))
                    append("," + JSONObject.quote(id) + ":" + record.toString())
                    index++
                }
                append("},\"last_session_id\":" + JSONObject.quote(seed.unfinishedId) + "}")
            }
            assertTrue(file.length() >= targetMiB.toLong() * MIB)
            assertTrue(
                "Whole-record overshoot must leave room for quiet recovery.",
                file.length() < 15L * MIB,
            )
            val plaintext = file.readBytes()
            val cipher = PrivateRecordCipher(app, "local-game:$name")
            file.writeBytes(cipher.encrypt(plaintext))
            assertTrue(PrivateRecordCipher.hasEnvelope(file.readBytes()))
            assertEquals(
                plaintext.size.toLong() + PrivateRecordCipher.ENVELOPE_OVERHEAD_BYTES,
                file.length(),
            )
            val replayId = lastCopy.record.getJSONObject("state").getString("session_id")
            val replayResult =
                lastCopy.record
                    .getJSONObject("receipts")
                    .getJSONObject(lastCopy.finalRequest.getString("command_id"))
                    .getJSONObject("result")
            Archive(
                file,
                hashes,
                Replay(replayId, lastCopy.finalRequest, canonicalJson(replayResult)),
                cipher,
                plaintext.size,
            )
        }

    /** Renaming every protocol UUID preserves references; request hashes are recalculated. */
    private fun cloneCompleted(seed: Seed, index: Int): SessionCopy {
        val ids = mutableMapOf<String, String>()
        fun renamed(value: String): String =
            if (UUID_TEXT.matches(value)) ids.getOrPut(value) { uid() } else value
        fun copy(value: Any?): Any =
            when (value) {
                null,
                JSONObject.NULL -> JSONObject.NULL
                is JSONObject ->
                    JSONObject().also { result ->
                        value.keyList().forEach { key ->
                            result.put(renamed(key), copy(value.get(key)))
                        }
                    }
                is JSONArray ->
                    JSONArray().also { result ->
                        (0 until value.length()).forEach { result.put(copy(value.get(it))) }
                    }
                is String -> renamed(value)
                else -> value
            }
        val cloned = copy(seed.completedRecord) as JSONObject
        val receipts = cloned.getJSONObject("receipts")
        for ((originalId, request) in seed.requests) {
            val rewritten = copy(request) as JSONObject
            receipts
                .getJSONObject(renamed(originalId))
                .put("body_hash", digest(canonicalJson(rewritten).toByteArray(Charsets.UTF_8)))
        }
        val metadata = cloned.getJSONObject("metadata")
        for (key in listOf("created_at_ms", "updated_at_ms", "completed_at_ms")) {
            metadata.put(key, metadata.getLong(key) + index * 1_000L)
        }
        val feedback = metadata.getJSONObject("feedback").getJSONObject(CHILD_ID)
        feedback.put("recorded_at_ms", feedback.getLong("recorded_at_ms") + index * 1_000L)
        return SessionCopy(cloned, copy(seed.finalRequest) as JSONObject)
    }

    private fun recordsStorage(): ScaleStorage =
        Class.forName("dev.carplaymate.demo.RecordsScaleStorage")
            .getDeclaredConstructor().newInstance() as ScaleStorage

    private suspend fun loadOverview(client: LocalGameClient): ScaleOverview = client.restoreAndOverview().let {
        ScaleOverview(it.snapshot, it.journal, it.savedAdventures, it.feedback)
    }

    private suspend fun <T> measured(row: JSONObject, name: String, block: suspend () -> T): T {
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            row.getJSONObject("elapsed_ms")
                .put(name, (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
        }
    }

    /** Only aggregate counts, sizes, booleans and elapsed timings leave the isolated fixture. */
    private suspend fun writeReport(context: Context, report: JSONObject, name: String = "storage-scale.json") =
        withContext(Dispatchers.IO) {
            val atomic = AtomicFile(File(context.filesDir, name))
            val stream = atomic.startWrite()
            try {
                stream.write((report.toString(2) + "\n").toByteArray(Charsets.UTF_8))
                stream.fd.sync()
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }

    private class ScopedApplication(context: Context, retainedName: String? = null) : Application() {
        private val base = context.noBackupFilesDir.canonicalFile
        private val scope = retainedName ?: ("storage_scale_" + UUID.randomUUID())
        private val directory =
            File(base, scope).also { check(it.isDirectory || it.mkdir()) }
        private val files = File(directory, "files").also { check(it.isDirectory || it.mkdir()) }
        private val alias = "onu.records.v1." + MessageDigest.getInstance("SHA-256")
            .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)

        init {
            attachBaseContext(context)
        }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = directory

        override fun getFilesDir(): File = files

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("${scope}_$name", mode)

        fun clean() {
            check(directory.canonicalFile.parentFile == base)
            check(directory.name.startsWith("storage_scale_"))
            check(getSharedPreferences("family_v1", Context.MODE_PRIVATE).edit().clear().commit())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(directory.deleteRecursively())
        }
    }

    private data class Seed(
        val completedId: String,
        val completedRecord: JSONObject,
        val requests: Map<String, JSONObject>,
        val finalRequest: JSONObject,
        val finalResult: String,
        val choicesCount: Int,
        val unfinishedId: String,
        val unfinishedRecord: JSONObject,
        val unfinishedVersion: Long,
        val unfinishedEpoch: Long,
        val unfinishedGame: String,
    )

    private data class SessionCopy(val record: JSONObject, val finalRequest: JSONObject)

    private data class Replay(val sessionId: String, val request: JSONObject, val result: String)

    private data class Archive(
        val file: File,
        val completedHashes: Map<String, String>,
        val replay: Replay,
        val cipher: PrivateRecordCipher,
        val plaintextBytes: Int,
    )

    private fun uid() = UUID.randomUUID().toString()

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

    companion object {
        private const val MIB = 1024L * 1024L
        private const val CHILD_ID = "synthetic-scale-child"
        private val UUID_TEXT =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}


internal data class ScaleOverview(val snapshot: Snapshot?, val journal: List<FamilyJournalEntry>,
    val saved: List<SavedAdventure>, val feedback: List<AdventureFeedbackEntry>)

/** Baseline-safe boundary. No GameRecordStore/RecordTransaction types may appear here. */
internal interface ScaleStorage {
    suspend fun reset(context: Context, name: String, client: LocalGameClient)
    fun startWork(client: LocalGameClient)
    fun stopWork(client: LocalGameClient): JSONObject?
    suspend fun migrate(client: LocalGameClient)
    suspend fun document(context: Context, name: String, client: LocalGameClient): JSONObject
    suspend fun verifyCompleted(context: Context, name: String, client: LocalGameClient,
        hashes: Map<String, String>, unfinishedId: String)
    suspend fun ciphertextHash(context: Context, name: String, client: LocalGameClient): String
    suspend fun storedBytes(context: Context, name: String, client: LocalGameClient): Long
}

/** Reads the actual 0.12 single-file store, only in baseline mode. */
internal class BaselineScaleStorage : ScaleStorage {
    override suspend fun reset(context: Context, name: String, client: LocalGameClient) {
        client.deleteAll()
        // 0.12 deliberately commits an empty encrypted archive; it does not remove its file.
        // Verify that public deletion succeeded, then remove only this synthetic v1 namespace
        // to permit the next exact original-byte copy. Retained fixture/control/key survive.
        val empty = document(context, name, client)
        assertEquals(1, empty.getInt("format_version"))
        assertEquals(0, empty.getJSONObject("sessions").length())
        assertTrue(empty.has("last_session_id") && empty.isNull("last_session_id"))
        withContext(Dispatchers.IO) {
            require(Regex("scale_[0-9]+").matches(name))
            val directory = context.noBackupFilesDir.canonicalFile
            for (suffix in listOf(".json", ".json.bak", ".json.new")) {
                val file = File(directory, name + suffix).canonicalFile
                check(file.parentFile == directory)
                if (file.exists()) check(file.isFile && file.delete())
            }
        }
    }
    override fun startWork(client: LocalGameClient) = Unit
    override fun stopWork(client: LocalGameClient): JSONObject? = null
    override suspend fun migrate(client: LocalGameClient) = Unit
    override suspend fun document(context: Context, name: String, client: LocalGameClient): JSONObject =
        withContext(Dispatchers.IO) {
            JSONObject(PrivateRecordCipher(context, "local-game:$name")
                .decrypt(File(context.noBackupFilesDir, "$name.json").readBytes()).toString(Charsets.UTF_8))
        }
    override suspend fun verifyCompleted(context: Context, name: String, client: LocalGameClient,
        hashes: Map<String, String>, unfinishedId: String) {
        val sessions = document(context, name, client).getJSONObject("sessions")
        assertEquals(hashes.keys + unfinishedId, sessions.keyList().toSet())
        for ((id, hash) in hashes) assertEquals("Completed state and all its receipts: $id", hash,
            scaleDigest(canonicalJson(sessions.getJSONObject(id)).toByteArray(Charsets.UTF_8)))
    }
    override suspend fun ciphertextHash(context: Context, name: String, client: LocalGameClient): String =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            File(context.noBackupFilesDir, "$name.json").inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            scaleHex(digest.digest())
        }
    override suspend fun storedBytes(context: Context, name: String, client: LocalGameClient): Long =
        withContext(Dispatchers.IO) { File(context.noBackupFilesDir, "$name.json").length() }
}

internal fun scaleDigest(bytes: ByteArray): String = scaleHex(MessageDigest.getInstance("SHA-256").digest(bytes))
internal fun scaleHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
