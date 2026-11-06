package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real encrypted SQLite I/O in isolated scopes. Fault hooks simulate exceptions, not OS death. */
@RunWith(AndroidJUnit4::class)
class RecordStoreMigrationTest {
    @Test fun opaqueScalarAndNullReceiptsCannotBeReplacedByAnIdenticalCommandId() = runBlocking {
        for (opaque in listOf<Any>("opaque older receipt", JSONObject.NULL)) scoped { app ->
            val seed = seed(app)
            val document = seed.document.copyJson()
            document.getJSONObject("sessions").getJSONObject(seed.id).getJSONObject("receipts")
                .put(seed.command.getString("command_id"), opaque)
            val legacy = writeLegacy(app, document, encrypted = true)
            val client = LocalGameClient(app, legacy.name)
            // Import preserves opaque values; applying a command cannot reinterpret one as absent.
            withContext(Dispatchers.IO) { client.recordStore.open().close() }
            val before = recordStoreCiphertextSnapshot(client)
            repeat(2) {
                reject("LOCAL_STORAGE_UNAVAILABLE", 500) { client.command(seed.id, seed.command.copyJson()) }
                reject("LOCAL_STORAGE_UNAVAILABLE", 500) { client.receipt(seed.id, seed.command.getString("command_id")) }
                assertEquals(before, recordStoreCiphertextSnapshot(client))
                assertEquals(seed.snapshot.version, client.snapshot(seed.id).version)
                assertPreserved(app, legacy)
            }
        }
    }

    @Test fun plaintextAndEncryptedImportsPreserveEveryExtensionAndOriginalReceipt() = runBlocking {
        for (encrypted in listOf(false, true)) scoped { app ->
            val seed = seed(app)
            val document = seed.document.copyJson()
            val record = document.getJSONObject("sessions").getJSONObject(seed.id)
            val receipt = record.getJSONObject("receipts").getJSONObject(seed.command.getString("command_id"))
            val extension = JSONObject().put("nested", JSONArray().put("kept").put(7).put(JSONObject.NULL))
                .put("false_value", false).put("empty", JSONObject())
            for (entry in listOf(document, record, record.getJSONObject("state"),
                record.getJSONObject("state").getJSONObject("game"), record.getJSONObject("metadata"),
                receipt, receipt.getJSONObject("result"))) {
                entry.put("opaque_extension", extension.copyJson())
            }
            val legacy = writeLegacy(app, document, encrypted)
            val expectedReceipt = receipt.getJSONObject("result").copyJson()
            val client = LocalGameClient(app, legacy.name)
            assertEquals(canonicalJson(expectedReceipt), canonicalJson(client.receipt(seed.id, seed.command.getString("command_id"))))
            assertEquals(canonicalJson(document), canonicalJson(readRecordStoreDocument(client)))
            assertPreserved(app, legacy)
            assertEquals(2, readFence(app, legacy.name).getInt("format_version"))
            val committed = recordStoreCiphertextSnapshot(client)
            val retry = client.command(seed.id, seed.command.copyJson())
            assertEquals(canonicalJson(expectedReceipt), canonicalJson(retry))
            assertEquals(committed, recordStoreCiphertextSnapshot(client))
            assertEquals(seed.snapshot.version, client.restore()!!.version)
            assertEquals(canonicalJson(document), canonicalJson(readRecordStoreDocument(client)))
        }
    }

    @Test fun interruptedImportAtEachDurableBoundaryResumesWithoutLosingTheOriginal() = runBlocking {
        val stages = listOf(RecordStoreStage.LEGACY_PRESERVED, RecordStoreStage.IMPORT_ROWS_WRITTEN,
            RecordStoreStage.IMPORT_COMMITTED, RecordStoreStage.FENCE_WRITTEN, RecordStoreStage.STORE_ACTIVATED)
        for (stage in stages) scoped { app ->
            val seed = seed(app)
            val legacy = writeLegacy(app, seed.document, encrypted = true)
            val client = LocalGameClient(app, legacy.name)
            var interruptions = 0
            client.recordStore.checkpoint = {
                if (it == stage) { interruptions += 1; throw IOException("Synthetic interruption at $stage") }
            }
            reject("LOCAL_STORAGE_UNAVAILABLE") { client.receipt(seed.id, seed.command.getString("command_id")) }
            assertEquals("The intended stage must really be reached: $stage", 1, interruptions)
            if (stage in listOf(RecordStoreStage.LEGACY_PRESERVED, RecordStoreStage.IMPORT_ROWS_WRITTEN, RecordStoreStage.IMPORT_COMMITTED)) {
                assertArrayEquals("Before the fence, the authoritative v1 bytes stay unchanged", legacy.stored, legacy.file.readBytes())
            } else {
                assertEquals(2, readFence(app, legacy.name).getInt("format_version"))
            }
            assertPreserved(app, legacy)
            client.recordStore.checkpoint = null
            val reopened = LocalGameClient(app, legacy.name)
            assertEquals(canonicalJson(seed.receipt), canonicalJson(reopened.receipt(seed.id, seed.command.getString("command_id"))))
            assertEquals(canonicalJson(seed.document), canonicalJson(readRecordStoreDocument(reopened)))
            assertEquals(seed.snapshot.version, reopened.restore()!!.version)
            assertPreserved(app, legacy)
        }
    }

    @Test fun changedLegacySourceAfterImportCommitCannotActivateAStaleImport() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val legacy = writeLegacy(app, seed.document, encrypted = true)
            val client = LocalGameClient(app, legacy.name)
            client.recordStore.checkpoint = { if (it == RecordStoreStage.IMPORT_COMMITTED) throw IOException("before fence") }
            reject("LOCAL_STORAGE_UNAVAILABLE") { client.receipt(seed.id, seed.command.getString("command_id")) }
            val changed = seed.document.copyJson().put("newer_external_extension", "preserve newer source")
            val changedBytes = PrivateRecordCipher(app, "local-game:${legacy.name}").encrypt(changed.toString().toByteArray())
            legacy.file.writeBytes(changedBytes)
            client.recordStore.checkpoint = null
            reject("LOCAL_STORAGE_UNAVAILABLE") { LocalGameClient(app, legacy.name).restore() }
            assertArrayEquals(changedBytes, legacy.file.readBytes())
            assertPreserved(app, legacy)
        }
    }

    @Test fun activeFenceNeverFallsBackWhenTheDatabaseDisappears() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val legacy = writeLegacy(app, seed.document, encrypted = true)
            val client = LocalGameClient(app, legacy.name)
            client.receipt(seed.id, seed.command.getString("command_id"))
            val state = client.snapshot(seed.id)
            val changed = Snapshot.from(client.command(seed.id, commandEnvelope(state, uid(), "session.pause", JSONObject())))
            assertEquals(state.version + 1, changed.version)
            val fence = legacy.file.readBytes()
            val database = client.recordStore.databaseFile
            assertTrue(database.delete()) // Every backend operation has already closed its connection.
            reject("LOCAL_STORAGE_UNAVAILABLE") { LocalGameClient(app, legacy.name).restore() }
            assertFalse("Read failure must not create a replacement database", database.exists())
            assertArrayEquals(fence, legacy.file.readBytes())
            assertPreserved(app, legacy)
        }
    }

    @Test fun restoringOldPrimaryBytesCannotRollBackAnAlreadyActiveDatabase() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val legacy = writeLegacy(app, seed.document, encrypted = true)
            val client = LocalGameClient(app, legacy.name)
            client.receipt(seed.id, seed.command.getString("command_id"))
            val state = client.snapshot(seed.id)
            client.command(seed.id, commandEnvelope(state, uid(), "session.pause", JSONObject()))
            val committed = recordStoreCiphertextSnapshot(client)
            legacy.file.writeBytes(legacy.stored)
            reject("LOCAL_STORAGE_UNAVAILABLE") { LocalGameClient(app, legacy.name).restore() }
            assertEquals(committed, recordStoreCiphertextSnapshot(client))
            assertArrayEquals(legacy.stored, legacy.file.readBytes())
        }
    }

    @Test fun commandFailureBeforeCommitRollsBackStateAndReceiptTogether() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val client = seed.client
            val request = commandEnvelope(seed.snapshot, uid(), "session.pause", JSONObject())
            val before = recordStoreCiphertextSnapshot(client)
            var failures = 0
            client.recordStore.checkpoint = { if (it == RecordStoreStage.BEFORE_COMMIT) {
                failures += 1; throw IOException("synthetic commit failure")
            } }
            reject("LOCAL_STORAGE_UNAVAILABLE") { client.command(seed.id, request) }
            assertEquals(1, failures)
            assertEquals(before, recordStoreCiphertextSnapshot(client))
            assertEquals(seed.snapshot.version, client.snapshot(seed.id).version)
            reject("COMMAND_NOT_FOUND") { client.receipt(seed.id, request.getString("command_id")) }
            client.recordStore.checkpoint = null
            val committed = Snapshot.from(client.command(seed.id, request))
            assertEquals(seed.snapshot.version + 1, committed.version)
            assertEquals(committed.version, Snapshot.from(client.command(seed.id, request)).version)
        }
    }

    @Test fun lostResponseAfterCommitReturnsTheOriginalReceiptWithoutASecondEffect() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val request = commandEnvelope(seed.snapshot, uid(), "session.pause", JSONObject())
            var interruptions = 0
            seed.client.recordStore.checkpoint = { if (it == RecordStoreStage.AFTER_COMMIT) {
                interruptions += 1; throw IOException("synthetic lost acknowledgement")
            } }
            reject("LOCAL_STORAGE_UNAVAILABLE") { seed.client.command(seed.id, request) }
            assertEquals(1, interruptions)
            seed.client.recordStore.checkpoint = null
            val reopened = LocalGameClient(app, "seed")
            val receipt = reopened.receipt(seed.id, request.getString("command_id"))
            assertEquals(seed.snapshot.version + 1, Snapshot.from(receipt).version)
            assertEquals(seed.snapshot.turnEpoch + 1, Snapshot.from(receipt).turnEpoch)
            val committed = recordStoreCiphertextSnapshot(reopened)
            assertEquals(canonicalJson(receipt), canonicalJson(reopened.command(seed.id, request)))
            assertEquals(committed, recordStoreCiphertextSnapshot(reopened))
            val changedBody = request.copyJson().put("payload", JSONObject().put("reason", "NETWORK"))
            reject("COMMAND_ID_REUSED") { reopened.command(seed.id, changedBody) }
            assertEquals(committed, recordStoreCiphertextSnapshot(reopened))
        }
    }

    @Test fun sqliteFullAtCommitKeepsPriorReceiptAndAllowsRecoveryAfterTheFailureClears() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val request = commandEnvelope(seed.snapshot, uid(), "session.resume", JSONObject())
            val before = recordStoreCiphertextSnapshot(seed.client)
            var failures = 0
            seed.client.recordStore.checkpoint = { if (it == RecordStoreStage.BEFORE_COMMIT) {
                failures += 1; throw SQLiteFullException("Injected SQLite full result, not a filled device")
            } }
            reject("LOCAL_STORAGE_FULL", 507) { seed.client.command(seed.id, request) }
            assertEquals(1, failures)
            assertEquals(before, recordStoreCiphertextSnapshot(seed.client))
            reject("COMMAND_NOT_FOUND") { seed.client.receipt(seed.id, request.getString("command_id")) }
            assertEquals(canonicalJson(seed.receipt), canonicalJson(seed.client.command(seed.id, seed.command)))
            assertEquals("Receipt retry must not attempt a commit", 1, failures)
            seed.client.recordStore.checkpoint = null
            assertEquals("PAUSED", seed.client.restore()!!.status)
            assertEquals(seed.snapshot.version, seed.client.snapshot(seed.id).version)
            val resumed = Snapshot.from(seed.client.command(seed.id, request))
            assertEquals(seed.snapshot.version + 1, resumed.version)
        }
    }

    @Test fun sqliteFullDuringImportOrFencePublicationKeepsTheSourceAndCanResume() = runBlocking {
        for (stage in listOf(RecordStoreStage.IMPORT_ROWS_WRITTEN, RecordStoreStage.FENCE_WRITTEN)) scoped { app ->
            val seed = seed(app)
            val legacy = writeLegacy(app, seed.document, encrypted = true)
            val client = LocalGameClient(app, legacy.name)
            var failures = 0
            client.recordStore.checkpoint = { if (it == stage) {
                failures += 1; throw SQLiteFullException("Injected full result at $stage")
            } }
            reject("LOCAL_STORAGE_FULL", 507) { client.receipt(seed.id, seed.command.getString("command_id")) }
            assertEquals(1, failures)
            assertPreserved(app, legacy)
            if (stage == RecordStoreStage.IMPORT_ROWS_WRITTEN) assertArrayEquals(legacy.stored, legacy.file.readBytes())
            else assertEquals(2, readFence(app, legacy.name).getInt("format_version"))
            client.recordStore.checkpoint = null
            val reopened = LocalGameClient(app, legacy.name)
            assertEquals(canonicalJson(seed.receipt), canonicalJson(reopened.receipt(seed.id, seed.command.getString("command_id"))))
            assertEquals(canonicalJson(seed.document), canonicalJson(readRecordStoreDocument(reopened)))
        }
    }

    @Test fun supersessionAndNewSelectionAreRolledBackTogetherOnCreateFailure() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val before = readRecordStoreDocument(seed.client)
            seed.client.recordStore.checkpoint = { if (it == RecordStoreStage.BEFORE_COMMIT) throw IOException("create failure") }
            reject("LOCAL_STORAGE_UNAVAILABLE") { seed.client.create(playersBody("새시작", null)) }
            seed.client.recordStore.checkpoint = null
            assertEquals(canonicalJson(before), canonicalJson(readRecordStoreDocument(seed.client)))
            assertEquals(seed.id, seed.client.restore()!!.sessionId)
            val replacement = seed.client.create(playersBody("새시작", null))
            val saved = readRecordStoreDocument(seed.client)
            assertEquals(replacement.sessionId, saved.getString("last_session_id"))
            assertEquals(replacement.sessionId, saved.getJSONObject("sessions").getJSONObject(seed.id)
                .getJSONObject("metadata").getString("superseded_by"))
            assertEquals(canonicalJson(seed.receipt), canonicalJson(seed.client.command(seed.id, seed.command)))
            reject("SESSION_SUPERSEDED") { seed.client.restore(seed.id) }
        }
    }

    @Test fun concurrentInstancesShareOneImportAndReturnTheSameOriginalReceipt() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val legacy = writeLegacy(app, seed.document, encrypted = true)
            val clients = listOf(LocalGameClient(app, legacy.name), LocalGameClient(app, legacy.name))
            val activations = AtomicInteger()
            clients.forEach { client -> client.recordStore.checkpoint = {
                if (it == RecordStoreStage.STORE_ACTIVATED) activations.incrementAndGet()
            } }
            val results = coroutineScope { List(12) { index -> async {
                clients[index % 2].command(seed.id, seed.command.copyJson())
            } }.awaitAll() }
            assertEquals(1, activations.get())
            results.forEach { assertEquals(canonicalJson(seed.receipt), canonicalJson(it)) }
            assertEquals(canonicalJson(seed.document), canonicalJson(readRecordStoreDocument(clients.first())))
            assertPreserved(app, legacy)
        }
    }

    @Test fun competingDifferentCommandsFromOneVersionApplyOnlyOneEffect() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val clients = listOf(seed.client, LocalGameClient(app, "seed"))
            val requests = List(2) { commandEnvelope(seed.snapshot, uid(), "session.pause", JSONObject()) }
            val results = coroutineScope { requests.mapIndexed { index, request -> async {
                runCatching { clients[index].command(seed.id, request) }
            } }.awaitAll() }
            assertEquals(1, results.count { it.isSuccess })
            val failure = results.single { it.isFailure }.exceptionOrNull() as ApiException
            assertEquals("STALE_VERSION", failure.code)
            assertEquals(seed.snapshot.version + 1, clients.first().snapshot(seed.id).version)
            for ((index, result) in results.withIndex()) {
                if (result.isSuccess) assertEquals(canonicalJson(result.getOrThrow()),
                    canonicalJson(clients.first().receipt(seed.id, requests[index].getString("command_id"))))
                else reject("COMMAND_NOT_FOUND") { clients.first().receipt(seed.id, requests[index].getString("command_id")) }
            }
        }
    }

    @Test fun deletionCanRetryEveryBackendBoundaryAndRemovesTheLegacyRecoveryCopy() = runBlocking {
        for (stage in listOf(RecordStoreStage.BEFORE_DELETE, RecordStoreStage.DATABASE_DELETED, RecordStoreStage.ALL_FILES_DELETED)) scoped { app ->
            val seed = seed(app)
            val legacy = writeLegacy(app, seed.document, encrypted = true)
            val client = LocalGameClient(app, legacy.name)
            client.receipt(seed.id, seed.command.getString("command_id"))
            var interruptions = 0
            client.recordStore.checkpoint = { if (it == stage) {
                interruptions += 1; throw IOException("delete boundary $stage")
            } }
            reject("LOCAL_STORAGE_UNAVAILABLE") { client.deleteAll() }
            assertEquals(1, interruptions)
            client.recordStore.checkpoint = null
            LocalGameClient(app, legacy.name).deleteAll()
            val reopened = LocalGameClient(app, legacy.name)
            assertNull(reopened.restore())
            reject("SESSION_NOT_FOUND") { reopened.receipt(seed.id, seed.command.getString("command_id")) }
            assertFalse(reopened.recordStore.databaseFile.exists())
            assertFalse(File(app.noBackupFilesDir, "${legacy.name}.legacy-v1.enc").exists())
            val leftovers = app.noBackupFilesDir.listFiles().orEmpty()
                .filter { it.name.startsWith(legacy.name) }.map { it.name }
            assertTrue("No database, sidecar or imported source remains: $leftovers", leftovers.isEmpty())
        }
    }

    @Test fun legacyMultiMegabyteRootSessionAndReceiptRowsRoundTripWithoutCursorWindowLoss() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val large = "bounded-extension-" + "x".repeat(3 * 1024 * 1024)
            val document = seed.document.copyJson().put("large_root", large)
            val session = document.getJSONObject("sessions").getJSONObject(seed.id).put("large_session", large)
            val receipt = session.getJSONObject("receipts").getJSONObject(seed.command.getString("command_id"))
                .put("large_receipt", large)
            val legacy = writeLegacy(app, document, encrypted = true)
            assertTrue("This was a valid whole-archive v1 input", legacy.plaintext.size < 16 * 1024 * 1024)
            val client = LocalGameClient(app, legacy.name)
            assertEquals(canonicalJson(receipt.getJSONObject("result")), canonicalJson(client.receipt(seed.id, seed.command.getString("command_id"))))
            withContext(Dispatchers.IO) {
                client.recordStore.open().use { tx ->
                    assertEquals(large, tx.root.getString("large_root"))
                    assertEquals(large, tx.readSession(seed.id)!!.getString("large_session"))
                    assertEquals(large, tx.readReceipt(seed.id, seed.command.getString("command_id"))!!.getString("large_receipt"))
                }
            }
            val paused = Snapshot.from(client.command(seed.id, commandEnvelope(seed.snapshot, uid(), "session.pause", JSONObject())))
            assertEquals(seed.snapshot.version + 1, paused.version)
            withContext(Dispatchers.IO) {
                client.recordStore.open().use { tx ->
                    assertEquals(large, tx.root.getString("large_root"))
                    assertEquals(large, tx.readSession(seed.id)!!.getString("large_session"))
                    assertEquals(large, tx.readReceipt(seed.id, seed.command.getString("command_id"))!!.getString("large_receipt"))
                }
            }
            assertPreserved(app, legacy)
        }
    }

    @Test fun formerlyValidFifteenMiBRootExtensionDoesNotBlockReceiptOrQuietRecovery() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val large = "x".repeat(15 * 1024 * 1024 - 64 * 1024)
            val document = seed.document.copyJson().put("large_extension", large)
            val legacy = writeLegacy(app, document, encrypted = true)
            assertTrue(legacy.plaintext.size < 15 * 1024 * 1024)
            val client = LocalGameClient(app, legacy.name)
            assertEquals(canonicalJson(seed.receipt), canonicalJson(client.command(seed.id, seed.command)))
            assertEquals(seed.snapshot.version, client.restore()!!.version)
            withContext(Dispatchers.IO) {
                client.recordStore.open().use { assertEquals(large, it.root.getString("large_extension")) }
            }
            val next = Snapshot.from(client.command(seed.id, commandEnvelope(seed.snapshot, uid(), "session.pause", JSONObject())))
            assertEquals(seed.snapshot.version + 1, next.version)
            assertEquals(canonicalJson(seed.receipt), canonicalJson(client.receipt(seed.id, seed.command.getString("command_id"))))
        }
    }

    @Test fun totalArchiveCanExceedSixteenMiBWhileEachLogicalRecordRemainsWithinItsLimit() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val large = "x".repeat(9 * 1024 * 1024)
            withContext(Dispatchers.IO) {
                seed.client.recordStore.open().use { tx ->
                    tx.putRoot(tx.root.put("large_root", large))
                    tx.putSession(seed.id, tx.readSession(seed.id)!!.put("large_session", large))
                    tx.commit(recovery = false)
                }
            }
            assertTrue(seed.client.recordStore.databaseFile.length() > 16L * 1024 * 1024)
            val reopened = LocalGameClient(app, "seed")
            assertEquals(canonicalJson(seed.receipt), canonicalJson(reopened.command(seed.id, seed.command)))
            val paused = Snapshot.from(reopened.command(seed.id, commandEnvelope(seed.snapshot, uid(), "session.pause", JSONObject())))
            assertEquals(seed.snapshot.version + 1, paused.version)
            withContext(Dispatchers.IO) {
                reopened.recordStore.open().use { tx ->
                    assertEquals(large, tx.root.getString("large_root"))
                    assertEquals(large, tx.readSession(seed.id)!!.getString("large_session"))
                }
            }
            reopened.deleteAll()
            assertNull(reopened.restore())
        }
    }

    private data class Seed(val client: LocalGameClient, val id: String, val snapshot: Snapshot,
        val command: JSONObject, val receipt: JSONObject, val document: JSONObject)
    private data class Legacy(val name: String, val file: File, val plaintext: ByteArray, val stored: ByteArray, val encrypted: Boolean)

    private suspend fun seed(app: Context): Seed {
        val client = LocalGameClient(app, "seed")
        val initial = client.createFamilyAdventure(playersBody("합성별명", null), DEFAULT_ADVENTURE_ID, mapOf("p1" to "synthetic-child"))
        val command = commandEnvelope(initial, uid(), "session.pause", JSONObject())
        val receipt = client.command(initial.sessionId, command)
        return Seed(client, initial.sessionId, Snapshot.from(receipt), command, receipt, readRecordStoreDocument(client))
    }

    private fun writeLegacy(app: Context, document: JSONObject, encrypted: Boolean): Legacy {
        val name = "migration_${uid().replace("-", "")}";
        val file = File(app.noBackupFilesDir, "$name.json")
        val plaintext = (document.toString(2) + "\n").toByteArray(Charsets.UTF_8)
        val stored = if (encrypted) PrivateRecordCipher(app, "local-game:$name").encrypt(plaintext) else plaintext
        file.writeBytes(stored)
        return Legacy(name, file, plaintext, stored, encrypted)
    }

    private fun assertPreserved(app: Context, legacy: Legacy) {
        val preserved = File(app.noBackupFilesDir, "${legacy.name}.legacy-v1.enc").readBytes()
        assertTrue(PrivateRecordCipher.hasEnvelope(preserved))
        if (legacy.encrypted) assertArrayEquals(legacy.stored, preserved)
        assertArrayEquals(legacy.plaintext, PrivateRecordCipher(app, "local-game:${legacy.name}").decrypt(preserved))
    }

    private fun readFence(app: Context, name: String): JSONObject = JSONObject(
        PrivateRecordCipher(app, "local-game:$name").decrypt(File(app.noBackupFilesDir, "$name.json").readBytes()).toString(Charsets.UTF_8))

    private suspend fun reject(code: String, status: Int? = null, block: suspend () -> Any?) {
        try { block(); fail("Expected $code") } catch (error: ApiException) {
            assertEquals(code, error.code)
            if (status != null) assertEquals(status, error.status)
        }
    }

    private suspend fun scoped(block: suspend (ScopedApplication) -> Unit) {
        val app = ScopedApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        try { block(app) } finally { withContext(Dispatchers.IO) { app.clean() } }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val scope = "record_migration_${UUID.randomUUID()}"
        private val directory = File(context.noBackupFilesDir, scope).also { check(it.mkdirs()) }
        private val alias = "onu.records.v1." + MessageDigest.getInstance("SHA-256")
            .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)
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
