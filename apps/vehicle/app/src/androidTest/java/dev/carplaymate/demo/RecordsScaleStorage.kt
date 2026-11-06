package dev.carplaymate.demo

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

/**
 * Loaded by class name only in records mode. Keep every v2-only reference here so the same test
 * APK can generate and measure the retained fixture on an unmodified 0.12 target application.
 */
internal class RecordsScaleStorage : ScaleStorage {
    private val totals = linkedMapOf<RecordStoreWork, LongArray>()

    override suspend fun reset(context: Context, name: String, client: LocalGameClient) {
        client.deleteAll()
    }

    override fun startWork(client: LocalGameClient) {
        check(client.recordStore.observeWork == null)
        synchronized(totals) {
            totals.clear()
            RecordStoreWork.entries.forEach { totals[it] = longArrayOf(0, 0) }
        }
        client.recordStore.observeWork = { stage, nanos -> synchronized(totals) {
            val total = totals.getValue(stage)
            total[0] += 1
            total[1] += nanos
        } }
    }

    override fun stopWork(client: LocalGameClient): JSONObject {
        client.recordStore.observeWork = null
        return synchronized(totals) {
            JSONObject().also { result -> totals.forEach { (stage, total) ->
                result.put(stage.name, JSONObject().put("calls", total[0]).put("elapsed_ms", total[1] / 1_000_000.0))
            } }
        }
    }

    override suspend fun migrate(client: LocalGameClient): Unit = withContext(Dispatchers.IO) {
        client.recordStore.open().close()
    }

    // Used only for the tiny, authentic seed in the optional unpaired test. Scale verification
    // below never builds a whole archive or a whole ciphertext/hex map in memory.
    override suspend fun document(context: Context, name: String, client: LocalGameClient): JSONObject =
        readRecordStoreDocument(client)

    override suspend fun verifyCompleted(context: Context, name: String, client: LocalGameClient,
        hashes: Map<String, String>, unfinishedId: String): Unit = withContext(Dispatchers.IO) {
        client.recordStore.open().use { tx ->
            assertEquals(hashes.keys + unfinishedId, tx.sessionIds().toSet())
        }
        for ((id, expected) in hashes) {
            // A transaction caches its reads. Close after each completed session so validation
            // itself does not retain every receipt from the large fixture at once.
            client.recordStore.open().use { tx ->
                val record = requireNotNull(tx.readSession(id)).copyJson()
                val receipts = JSONObject()
                for (command in tx.receiptIds(id)) receipts.put(command, requireNotNull(tx.readReceipt(id, command)))
                record.put("receipts", receipts)
                assertEquals("Completed state and every receipt must match the source: $id", expected,
                    scaleDigest(canonicalJson(record).toByteArray(Charsets.UTF_8)))
            }
        }
    }

    /**
     * SHA-256 of unambiguous ordered row identities, lengths and raw encrypted payloads. Read
     * payloads in 64 KiB pieces, excluding SQLite page/journal headers from the no-op assertion.
     * This hashes the live database rows, never a preserved source, fence or export of plaintext.
     */
    override suspend fun ciphertextHash(context: Context, name: String, client: LocalGameClient): String =
        withContext(Dispatchers.IO) {
            val database = client.recordStore.databaseFile
            check(database.isFile)
            val digest = MessageDigest.getInstance("SHA-256")
            val flags = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            SQLiteDatabase.openDatabase(database.absolutePath, null, flags,
                DatabaseErrorHandler { throw IllegalStateException("Do not delete a damaged scale fixture") }).use { db ->
                db.rawQuery("SELECT kind,session_id,record_id,length(payload) FROM records ORDER BY kind,session_id,record_id", null).use { rows ->
                    var count = 0L
                    while (rows.moveToNext()) {
                        val identity = Array(3) { rows.getString(it) }
                        val key = JSONArray(identity.toList()).toString().toByteArray(Charsets.UTF_8)
                        val size = rows.getLong(3)
                        check(size > 0)
                        digest.update(ByteBuffer.allocate(4).putInt(key.size).array())
                        digest.update(key)
                        digest.update(ByteBuffer.allocate(8).putLong(size).array())
                        var offset = 0L
                        while (offset < size) {
                            val length = minOf(64L * 1024L, size - offset).toInt()
                            val args = arrayOf((offset + 1).toString(), length.toString(), identity[0], identity[1], identity[2])
                            db.rawQuery("SELECT substr(payload,?,?) FROM records WHERE kind=? AND session_id=? AND record_id=?", args).use { part ->
                                check(part.moveToFirst())
                                val bytes = part.getBlob(0)
                                check(bytes.size == length)
                                digest.update(bytes)
                            }
                            offset += length
                        }
                        count++
                    }
                    check(count >= 2) // Actual root and control, in addition to fixture records.
                    digest.update(ByteBuffer.allocate(8).putLong(count).array())
                }
            }
            scaleHex(digest.digest())
        }

    override suspend fun storedBytes(context: Context, name: String, client: LocalGameClient): Long =
        withContext(Dispatchers.IO) {
            val database = client.recordStore.databaseFile
            (listOf(database) + listOf("-journal", "-wal", "-shm").map { File(database.path + it) })
                .sumOf { if (it.isFile) it.length() else 0L }
        }
}
