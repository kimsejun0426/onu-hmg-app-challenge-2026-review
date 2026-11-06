package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite page allocation and damaged files; neither checkpoint exceptions nor OS kills. */
@RunWith(AndroidJUnit4::class)
class RecordStoreIntegrityTest {
    @Test fun nativeSqliteFullRollsBackPendingStateAndReceiptAndReportsCapacity() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val before = recordStoreCiphertextSnapshot(seed.client)
            val beforeDocument = readRecordStoreDocument(seed.client)
            val newCommand = UUID.randomUUID().toString()
            val wrapper = withContext(Dispatchers.IO) {
                seed.client.recordStore.open().use { tx ->
                    tx.readReceipt(seed.id, seed.command.getString("command_id"))!!.copyJson()
                        .put("large_test_extension", "x".repeat(512 * 1024))
                        .also { it.getJSONObject("result").put("command_id", newCommand) }
                }
            }
            var beforeCommitSignals = 0
            seed.client.recordStore.checkpoint = {
                if (it == RecordStoreStage.BEFORE_COMMIT) beforeCommitSignals += 1
            }
            withContext(Dispatchers.IO) {
                seed.client.recordStore.open().use { tx ->
                    // Use the connection already pinned to this thread's transaction. Opening a
                    // second DB would not reliably constrain the connection used by commit().
                    val database = transactionDatabase(tx)
                    val oldLimit = pragma(database, "max_page_count")
                    val pages = pragma(database, "page_count")
                    val pageSize = pragma(database, "page_size")
                    val freeBytes = pragma(database, "freelist_count") * pageSize
                    assertTrue("Even the whole seed DB is smaller than the new receipt", pages * pageSize < 256 * 1024)
                    assertTrue("The new encrypted receipt must need more pages", freeBytes < 256 * 1024)
                    try {
                        assertEquals(pages, pragma(database, "max_page_count = $pages"))
                        assertEquals(pages, pragma(database, "max_page_count"))
                        val session = tx.readSession(seed.id)!!
                        val state = session.getJSONObject("state")
                        state.put("version", state.getLong("version") + 1)
                        tx.putSession(seed.id, session)
                        tx.putReceipt(seed.id, newCommand, wrapper.copyJson())
                        try {
                            tx.commit(recovery = false)
                            fail("A native SQLite page-allocation failure was required")
                        } catch (error: ApiException) {
                            // A cleanup error must not hide the original SQLITE_FULL as HTTP 500.
                            assertEquals(507, error.status)
                            assertEquals("LOCAL_STORAGE_FULL", error.code)
                        }
                    } finally {
                        // A failed transaction now closes its key scope and connection. Keep
                        // the real SQLITE_FULL/507 assertion above; release the test cap on
                        // the live connection or a newly opened one, then prove the same large
                        // receipt can persist below.
                        if (database.isOpen) {
                            assertEquals(oldLimit, pragma(database, "max_page_count = $oldLimit"))
                        } else {
                            rawDatabase(seed.client.recordStore.databaseFile).use { reopened ->
                                assertEquals(oldLimit, pragma(reopened, "max_page_count = $oldLimit"))
                            }
                        }
                    }
                }
            }
            assertEquals("Failure happened in SQL writes, before the test checkpoint", 0, beforeCommitSignals)
            seed.client.recordStore.checkpoint = null
            assertEquals(before, recordStoreCiphertextSnapshot(seed.client))
            assertEquals(canonicalJson(beforeDocument), canonicalJson(readRecordStoreDocument(seed.client)))
            reject("COMMAND_NOT_FOUND", 404) { seed.client.receipt(seed.id, newCommand) }
            assertEquals(canonicalJson(seed.receipt), canonicalJson(seed.client.command(seed.id, seed.command)))
            // Releasing the page cap really permits the same large logical receipt to persist.
            withContext(Dispatchers.IO) {
                seed.client.recordStore.open().use { tx ->
                    tx.putReceipt(seed.id, newCommand, wrapper.copyJson())
                    tx.commit(recovery = false)
                }
                seed.client.recordStore.open().use { tx ->
                    assertEquals(canonicalJson(wrapper), canonicalJson(tx.readReceipt(seed.id, newCommand)))
                }
            }
            assertEquals(seed.snapshot.version, seed.client.snapshot(seed.id).version)
            assertEquals(canonicalJson(seed.receipt), canonicalJson(seed.client.command(seed.id, seed.command)))
        }
    }

    @Test fun damagedDatabaseHeaderIsPreservedAndNeverRecreatedByReads() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            val file = seed.client.recordStore.databaseFile
            assertTrue(file.length() > 100)
            RandomAccessFile(file, "rw").use {
                it.seek(0)
                it.write("BROKEN_STORE_V2!!".toByteArray(Charsets.US_ASCII))
                it.fd.sync()
            }
            val damaged = file.readBytes()
            val otherFiles = namespaceBytes(app).filterKeys { it != file.name }
            repeat(2) {
                reject("LOCAL_STORAGE_UNAVAILABLE") { LocalGameClient(app).restore() }
                assertTrue(file.isFile)
                assertArrayEquals("Opening corruption must not delete or replace it", damaged, file.readBytes())
                assertFileBytes(otherFiles, namespaceBytes(app).filterKeys { it != file.name })
            }
            seed.client.deleteAll()
            assertTrue("Deletion must preserve the shared family key", app.hasKey())
            assertTrue(namespaceBytes(app).isEmpty())
            assertNull(seed.client.restore())
            assertEquals("ACTIVE", seed.client.create(playersBody("새합성가족", null)).status)
        }
    }

    @Test fun deletedControlRowBlocksReadsAndMutationsUntilExplicitDeletion() = runBlocking {
        scoped { app ->
            val seed = seed(app)
            withContext(Dispatchers.IO) {
                rawDatabase(seed.client.recordStore.databaseFile).use {
                    assertEquals(1, it.delete("records", "kind=? AND session_id=? AND record_id=?", arrayOf("control", "", "")))
                }
            }
            val damaged = recordStoreCiphertextSnapshot(seed.client)
            assertFalse(damaged.keys.any { JSONArray(it).getString(0) == "control" })
            reject("LOCAL_STORAGE_UNAVAILABLE") { seed.client.restore() }
            reject("LOCAL_STORAGE_UNAVAILABLE") { seed.client.receipt(seed.id, seed.command.getString("command_id")) }
            reject("LOCAL_STORAGE_UNAVAILABLE") { seed.client.create(playersBody("금지된새시작", null)) }
            assertEquals(damaged, recordStoreCiphertextSnapshot(seed.client))
            seed.client.deleteAll()
            assertTrue(app.hasKey())
            assertTrue(namespaceBytes(app).isEmpty())
            assertNull(seed.client.restore())
        }
    }

    @Test fun authenticatedCountsDetectDeletedSessionOrReceiptWithoutDiscardingOtherRows() = runBlocking {
        for (kind in listOf("session", "receipt")) scoped { app ->
            val seed = seed(app)
            val original = recordStoreCiphertextSnapshot(seed.client)
            val controlKey = original.keys.single { JSONArray(it).getString(0) == "control" }
            val command = if (kind == "receipt") seed.command.getString("command_id") else ""
            withContext(Dispatchers.IO) {
                rawDatabase(seed.client.recordStore.databaseFile).use {
                    assertEquals(1, it.delete("records", "kind=? AND session_id=? AND record_id=?", arrayOf(kind, seed.id, command)))
                }
            }
            val damaged = recordStoreCiphertextSnapshot(seed.client)
            assertEquals(original.size - 1, damaged.size)
            assertEquals("The authenticated count itself was not modified", original[controlKey], damaged[controlKey])
            reject("LOCAL_STORAGE_UNAVAILABLE") {
                withContext(Dispatchers.IO) { seed.client.recordStore.open().use { } }
            }
            reject("LOCAL_STORAGE_UNAVAILABLE") { seed.client.snapshot(seed.id) }
            assertEquals(damaged, recordStoreCiphertextSnapshot(seed.client))
            seed.client.deleteAll()
            assertTrue(namespaceBytes(app).isEmpty())
            assertNull(seed.client.restore())
        }
    }

    private data class Seed(val client: LocalGameClient, val id: String, val snapshot: Snapshot,
        val command: JSONObject, val receipt: JSONObject)

    private suspend fun seed(app: Context): Seed {
        val client = LocalGameClient(app)
        val created = client.create(playersBody("합성가족", null))
        val command = commandEnvelope(created, UUID.randomUUID().toString(), "session.pause", JSONObject())
        val receipt = client.command(created.sessionId, command)
        return Seed(client, created.sessionId, Snapshot.from(receipt), command, receipt)
    }

    private fun transactionDatabase(transaction: RecordTransaction): SQLiteDatabase =
        RecordTransaction::class.java.getDeclaredField("database").apply { isAccessible = true }
            .get(transaction) as SQLiteDatabase

    private fun pragma(database: SQLiteDatabase, expression: String): Long =
        database.rawQuery("PRAGMA main.$expression", null).use {
            check(it.moveToFirst())
            it.getLong(0)
        }

    private fun rawDatabase(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(file,
        SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            .setJournalMode("DELETE").setSynchronousMode("EXTRA")
            .setErrorHandler(DatabaseErrorHandler { /* Never destroy evidence during test inspection. */ })
            .build())

    private fun namespaceBytes(app: ScopedApplication): Map<String, ByteArray> =
        app.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("local_game_v1.") }
            .associate { it.name to it.readBytes() }

    private fun assertFileBytes(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, actual.getValue(name)) }
    }

    private suspend fun reject(code: String, status: Int = 500, block: suspend () -> Any?) {
        try { block(); fail("Expected $code") } catch (error: ApiException) {
            assertEquals(status, error.status)
            assertEquals(code, error.code)
        }
    }

    private suspend fun scoped(block: suspend (ScopedApplication) -> Unit) {
        val app = ScopedApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        try { block(app) } finally { withContext(Dispatchers.IO) { app.clean() } }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val scope = "record_integrity_${UUID.randomUUID()}"
        private val directory = File(context.noBackupFilesDir, scope).also { check(it.mkdirs()) }
        private val preferenceNames = mutableSetOf<String>()
        private val alias = "onu.records.v1." + MessageDigest.getInstance("SHA-256")
            .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scopedName = "$scope.$name"
            preferenceNames.add(scopedName)
            return super.getSharedPreferences(scopedName, mode)
        }
        fun hasKey() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)
        fun clean() {
            preferenceNames.forEach { check(super.getSharedPreferences(it, MODE_PRIVATE).edit().clear().commit()) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(directory.deleteRecursively())
        }
    }
}
