package dev.carplaymate.demo

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Format-1 file bytes only; never substitute a format-2 logical export for this evidence. */
internal fun readGameStorePlaintext(context: Context, file: File): ByteArray {
    val bytes = file.readBytes()
    return if (PrivateRecordCipher.hasEnvelope(bytes)) {
        PrivateRecordCipher(context, "local-game:${file.nameWithoutExtension}").decrypt(bytes)
    } else bytes
}

internal fun readGameStoreText(context: Context, file: File): String =
    readGameStorePlaintext(context, file).toString(Charsets.UTF_8)

/** Authenticated logical export for assertions; it is not the original format-1 byte stream. */
internal suspend fun readRecordStoreDocument(client: LocalGameClient): JSONObject =
    withContext(Dispatchers.IO) {
        client.recordStore.open().use { store ->
            val document = JSONObject(store.root.toString())
            val sessions = JSONObject()
            for (sessionId in store.sessionIds()) {
                val session = JSONObject(requireNotNull(store.readSession(sessionId)).toString())
                val receipts = JSONObject()
                for (commandId in store.receiptIds(sessionId)) {
                    receipts.put(commandId, requireNotNull(store.readReceipt(sessionId, commandId)))
                }
                session.put("receipts", receipts)
                sessions.put(sessionId, session)
            }
            document.put("sessions", sessions)
        }
    }

/**
 * Test-fixture upsert through the real encrypted backend. Omitted existing rows are NOT deleted.
 * The input may deliberately contain invalid model fields to exercise later projection failures.
 */
internal suspend fun writeRecordStoreDocument(client: LocalGameClient, document: JSONObject) =
    withContext(Dispatchers.IO) {
        client.recordStore.open().use { store ->
            val root = JSONObject(document.toString()).apply { remove("sessions") }
            store.putRoot(root)
            val sessions = document.getJSONObject("sessions")
            for (sessionId in sessions.keys().asSequence().toList()) {
                val session = JSONObject(sessions.getJSONObject(sessionId).toString())
                val receipts = session.getJSONObject("receipts")
                session.put("receipts", JSONObject())
                store.putSession(sessionId, session)
                for (commandId in receipts.keys().asSequence().toList()) {
                    store.putReceipt(sessionId, commandId, receipts.getJSONObject(commandId))
                }
            }
            store.commit(recovery = true)
        }
    }

/**
 * Actual encrypted row bytes, keyed by a JSON identity tuple. Excludes SQLite headers/journals:
 * a read-only reopen may change those without changing any private record. Does not authenticate
 * payloads, so damaged bytes can also be compared without invoking a destructive recovery path.
 */
internal suspend fun recordStoreCiphertextSnapshot(client: LocalGameClient): Map<String, String> =
    withContext(Dispatchers.IO) {
        val file = client.recordStore.databaseFile
        if (!file.exists()) return@withContext emptyMap()
        SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
                DatabaseErrorHandler { /* Test inspection must never delete corrupt evidence. */ },
            )
            .use { database ->
                val rows = sortedMapOf<String, String>()
                database.rawQuery(
                        "SELECT kind, session_id, record_id, length(payload) FROM records " +
                            "ORDER BY kind, session_id, record_id",
                        null,
                    )
                    .use { cursor ->
                        while (cursor.moveToNext()) {
                            val parts = listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                            val identity = JSONArray(parts).toString()
                            val length = cursor.getInt(3)
                            require(length in 0..LocalGameClient.MAX_STORE_BYTES + PrivateRecordCipher.ENVELOPE_OVERHEAD_BYTES)
                            val hex = CharArray(length * 2)
                            val digits = "0123456789abcdef"
                            var offset = 0
                            // Inspect a valid large legacy row without depending on CursorWindow's
                            // device-specific capacity or selecting the whole BLOB into one row.
                            while (offset < length) {
                                val count = minOf(64 * 1024, length - offset)
                                database.rawQuery(
                                        "SELECT substr(payload, ?, ?) FROM records " +
                                            "WHERE kind = ? AND session_id = ? AND record_id = ?",
                                        arrayOf((offset + 1).toString(), count.toString(), *parts.toTypedArray()),
                                    )
                                    .use { chunk ->
                                        check(chunk.moveToFirst())
                                        val bytes = chunk.getBlob(0)
                                        check(bytes.size == count)
                                        bytes.forEachIndexed { index, byte ->
                                            val value = byte.toInt() and 0xff
                                            hex[(offset + index) * 2] = digits[value ushr 4]
                                            hex[(offset + index) * 2 + 1] = digits[value and 15]
                                        }
                                    }
                                offset += count
                            }
                            rows[identity] = String(hex)
                        }
                    }
                rows
            }
    }
