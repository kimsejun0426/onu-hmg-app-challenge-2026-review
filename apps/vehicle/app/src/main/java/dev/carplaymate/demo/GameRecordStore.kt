package dev.carplaymate.demo

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Encrypted records in an app-private SQLite transaction. Never records input audio or text. */
internal class GameRecordStore(
    private val context: Context,
    private val storageName: String,
    private val validatePlaintext: (JSONObject) -> Unit,
) {
    internal val databaseFile = File(context.noBackupFilesDir, "$storageName.records.sqlite")
    private val primary = File(context.noBackupFilesDir, "$storageName.json")
    private val preserved = File(context.noBackupFilesDir, "$storageName.legacy-v1.enc")
    private val manifest = File(context.noBackupFilesDir, "$storageName.migration")
    private val deleting = File(context.noBackupFilesDir, "$storageName.deleting")
    private fun legacyCipher(scope: PrivateRecordCipher.KeyScope) = PrivateRecordCipher(context, "local-game:$storageName", scope)
    private fun manifestCipher(scope: PrivateRecordCipher.KeyScope) = PrivateRecordCipher(context, "local-game:$storageName:migration", scope)
    internal var checkpoint: ((RecordStoreStage) -> Unit)? = null
    internal var observeWork: ((RecordStoreWork, Long) -> Unit)? = null

    internal fun <T> work(kind: RecordStoreWork, block: () -> T): T {
        val observer = observeWork ?: return block()
        val start = android.os.SystemClock.elapsedRealtimeNanos()
        try { return block() } finally { observer(kind, android.os.SystemClock.elapsedRealtimeNanos() - start) }
    }

    init { require(Regex("[A-Za-z0-9_-]+").matches(storageName)) }

    internal fun keyScope() = PrivateRecordCipher.KeyScope(context)

    private inline fun opening(block: (PrivateRecordCipher.KeyScope) -> RecordTransaction): RecordTransaction {
        val scope = keyScope()
        try { return block(scope) }
        catch (error: Throwable) { scope.close(); throw error }
    }

    fun open(): RecordTransaction = opening { scope -> storageBoundary {
        if (atomicExists(deleting)) unavailable()
        if (!databaseFile.exists() && !atomicExists(primary) && !atomicExists(manifest) &&
            !atomicExists(preserved)) {
            if (databaseSidecars().any { it.exists() }) unavailable()
            return@storageBoundary RecordTransaction(this, null, null, scope)
        }
        recoverUncommittedPreparation(scope)
        val info = if (atomicExists(manifest)) readManifest(scope) else null
        val sourceBytes = if (atomicExists(primary)) atomicRead(primary, MAX_ENCRYPTED) else null
        val source = sourceBytes?.let { parsePrimary(it, scope) }
        if (databaseFile.exists()) {
            val identity = when {
                source?.optInt("format_version") == 2 -> fenceIdentity(source)
                info != null -> info
                else -> unavailable()
            }
            if (info != null) requireIdentity(identity, info)
            val database = openDatabase(create = false)
            try {
                if (!hasRecords(database)) {
                    // A verified PREPARING manifest is the only authority for an empty DB
                    // left by interruption before the import transaction committed.
                    if (info == null || source?.optInt("format_version") == 2 ||
                        database.version != 0 || hasUserTables(database)) unavailable()
                    val document = verifySource(info, sourceBytes, source, scope)
                    import(database, info, document, scope)
                }
                val control = readControl(database, identity, scope)
                when (control.getString("phase")) {
                    "PREPARED" -> {
                        if (info == null) unavailable()
                        verifySource(info, sourceBytes, source, scope)
                        activate(database, info, scope)
                    }
                    "ACTIVE" -> {
                        if (source == null || source.optInt("format_version") != 2) unavailable()
                        requireIdentity(identity, fenceIdentity(source))
                    }
                    else -> unavailable()
                }
                return@storageBoundary RecordTransaction(this, database, identity, scope)
            } catch (error: Throwable) { database.close(); throw error }
        }
        // A format-2 fence without its DB must never become a new empty archive.
        if (source?.optInt("format_version") == 2 || databaseSidecars().any { it.exists() }) unavailable()
        val identity = info ?: prepare(sourceBytes, source, scope)
        val document = verifySource(identity, sourceBytes, source, scope)
        val database = openDatabase(create = true)
        try {
            import(database, identity, document, scope)
            activate(database, identity, scope)
            RecordTransaction(this, database, identity, scope)
        } catch (error: Throwable) { database.close(); throw error }
    } }

    /** Explicit deletion works even when the key, schema or payload cannot be read. */
    fun deleteAll() = storageBoundary {
        atomicWrite(deleting, byteArrayOf(1))
        signal(RecordStoreStage.BEFORE_DELETE)
        deleteFiles(listOf(databaseFile) + databaseSidecars())
        signal(RecordStoreStage.DATABASE_DELETED)
        deleteFiles(listOf(primary, preserved, manifest).flatMap(::atomicPaths))
        signal(RecordStoreStage.ALL_FILES_DELETED)
        deleteFiles(atomicPaths(deleting))
    }

    internal fun initialize(): RecordTransaction = opening(::initialize)

    internal fun initialize(scope: PrivateRecordCipher.KeyScope): RecordTransaction = storageBoundary {
        // First mutation only; read-only access to an unused namespace leaves no files.
        if (databaseFile.exists() || atomicExists(primary) || atomicExists(manifest) ||
            atomicExists(preserved) || atomicExists(deleting)) unavailable()
        val identity = prepare(null, null, scope)
        val database = openDatabase(create = true)
        try {
            import(database, identity, emptyDocument(), scope)
            activate(database, identity, scope)
            RecordTransaction(this, database, identity, scope)
        } catch (error: Throwable) { database.close(); throw error }
    }

    /** Only rebuild an unfinished first preparation from an authoritative, verified v1 source. */
    private fun recoverUncommittedPreparation(scope: PrivateRecordCipher.KeyScope) {
        fun committed(file: File) = file.exists() || File(file.path + ".bak").exists()
        fun unfinished(file: File) = !committed(file) && File(file.path + ".new").isFile
        if (!unfinished(preserved) && !unfinished(manifest)) return
        if (databaseFile.exists() || databaseSidecars().any { it.exists() } || atomicExists(deleting) ||
            committed(manifest) || !committed(primary)) return
        val bytes = atomicRead(primary, MAX_ENCRYPTED)
        // parsePrimary authenticates encrypted v1 and applies the existing strict plaintext
        // validator. A format-2 fence, even without a DB, is never reconstruction authority.
        val document = parsePrimary(bytes, scope)
        if (document.opt("format_version") != 1) return
        if (unfinished(preserved)) {
            val encrypted = if (PrivateRecordCipher.hasEnvelope(bytes)) bytes
                else bootstrapEncrypt(legacyCipher(scope), bytes)
            atomicWrite(preserved, encrypted)
        }
        // prepare verifies a committed archive against the source before replacing only the
        // incomplete manifest. It preserves the encrypted v1 source bytes without re-encoding.
        prepare(bytes, document, scope)
    }

    private fun prepare(bytes: ByteArray?, document: JSONObject?, scope: PrivateRecordCipher.KeyScope): JSONObject {
        val kind = when { bytes == null -> "empty"; PrivateRecordCipher.hasEnvelope(bytes) -> "encrypted"; else -> "plain" }
        if (bytes != null && document == null) unavailable()
        if (kind == "plain") validatePlaintext(document!!)
        val archive = if (atomicExists(preserved)) {
            val existing = atomicRead(preserved, MAX_ENCRYPTED)
            if (kind == "empty" || (kind == "encrypted" && !existing.contentEquals(bytes)) ||
                (kind == "plain" && !legacyCipher(scope).decrypt(existing).contentEquals(bytes))) unavailable()
            existing
        } else if (bytes != null) {
            val encrypted = if (kind == "encrypted") bytes else bootstrapEncrypt(legacyCipher(scope), bytes)
            atomicWrite(preserved, encrypted)
            encrypted
        } else null
        val identity = JSONObject().put("generation", UUID.randomUUID().toString())
            .put("source_kind", kind).put("source_hash", hash(bytes ?: byteArrayOf()))
            .put("preserved_hash", archive?.let(::hash) ?: JSONObject.NULL)
        val preparing = JSONObject(identity.toString()).put("phase", "PREPARING")
        val encrypted = if (archive != null) manifestCipher(scope).encryptExisting(encode(preparing))
            else bootstrapEncrypt(manifestCipher(scope), encode(preparing))
        atomicWrite(manifest, encrypted)
        signal(RecordStoreStage.LEGACY_PRESERVED)
        return identity
    }

    private fun bootstrapEncrypt(cipher: PrivateRecordCipher, bytes: ByteArray): ByteArray {
        // The cipher centrally prevents every writer from replacing a missing shared key
        // while encrypted family preferences or game artifacts remain.
        return cipher.encrypt(bytes)
    }

    private fun verifySource(identity: JSONObject, bytes: ByteArray?, source: JSONObject?, scope: PrivateRecordCipher.KeyScope): JSONObject {
        val kind = identity.getString("source_kind")
        if (kind == "empty") {
            if (atomicExists(preserved) || identity.getString("source_hash") != hash(byteArrayOf()) ||
                (bytes != null && source?.optInt("format_version") != 2)) unavailable()
            return emptyDocument()
        }
        val archive = atomicRead(preserved, MAX_ENCRYPTED)
        if (hash(archive) != identity.getString("preserved_hash")) unavailable()
        val document = if (source?.optInt("format_version") == 1) {
            if (bytes == null || hash(bytes) != identity.getString("source_hash") ||
                (kind == "encrypted" && !archive.contentEquals(bytes)) ||
                (kind == "plain" && !legacyCipher(scope).decrypt(archive).contentEquals(bytes))) unavailable()
            source
        } else parseDocument(legacyCipher(scope).decrypt(archive))
        requireLegacyShape(document)
        if (kind == "plain") validatePlaintext(document)
        else if (kind != "encrypted") unavailable()
        if (source?.optInt("format_version") == 2) requireIdentity(identity, fenceIdentity(source))
        else if (source?.optInt("format_version") != 1) unavailable()
        return document
    }

    private fun import(database: SQLiteDatabase, identity: JSONObject, document: JSONObject, scope: PrivateRecordCipher.KeyScope) {
        database.beginTransaction()
        var failure: Throwable? = null
        try {
            database.execSQL("CREATE TABLE records (kind TEXT NOT NULL, session_id TEXT NOT NULL, record_id TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(kind, session_id, record_id))")
            database.version = 2
            val root = JSONObject()
            for (key in document.keys().asSequence()) if (key != "sessions") root.put(key, document.get(key))
            writeValue(database, identity, "root", "", "", root, scope)
            val sessions = document.getJSONObject("sessions")
            for (id in sessions.keys().asSequence()) {
                val value = sessions.get(id)
                if (value is JSONObject) {
                    val receipts = value.optJSONObject("receipts")
                    if (receipts != null) {
                        for (command in receipts.keys().asSequence())
                            writeValue(database, identity, "receipt", id, command, receipts.get(command), scope)
                        value.put("receipts", JSONObject())
                    }
                }
                writeValue(database, identity, "session", id, "", value, scope)
            }
            writeControl(database, identity, "PREPARED", scope)
            // Authenticate every inserted value before publishing the new format fence.
            for (key in keys(database)) readValue(database, identity, key[0], key[1], key[2], scope)
            signal(RecordStoreStage.IMPORT_ROWS_WRITTEN)
            database.setTransactionSuccessful()
        } catch (error: Throwable) { failure = error; throw error }
        finally { finishTransaction(database, failure) }
        signal(RecordStoreStage.IMPORT_COMMITTED)
    }

    private fun activate(database: SQLiteDatabase, identity: JSONObject, scope: PrivateRecordCipher.KeyScope) {
        val fence = JSONObject(identity.toString()).put("format_version", 2)
        val bytes = legacyCipher(scope).encryptExisting(encode(fence))
        atomicWrite(primary, bytes)
        requireIdentity(identity, fenceIdentity(parsePrimary(atomicRead(primary, MAX_ENCRYPTED), scope)))
        signal(RecordStoreStage.FENCE_WRITTEN)
        database.beginTransaction()
        var failure: Throwable? = null
        try {
            writeControl(database, identity, "ACTIVE", scope)
            database.setTransactionSuccessful()
        } catch (error: Throwable) { failure = error; throw error }
        finally { finishTransaction(database, failure) }
        signal(RecordStoreStage.STORE_ACTIVATED)
    }

    private fun readManifest(scope: PrivateRecordCipher.KeyScope): JSONObject {
        val doc = parseDocument(manifestCipher(scope).decrypt(atomicRead(manifest, MAX_ENCRYPTED)))
        if (doc.optString("phase") != "PREPARING") unavailable()
        return identity(doc)
    }

    private fun parsePrimary(bytes: ByteArray, scope: PrivateRecordCipher.KeyScope): JSONObject {
        val encrypted = PrivateRecordCipher.hasEnvelope(bytes)
        val doc = parseDocument(if (encrypted) legacyCipher(scope).decrypt(bytes) else bytes)
        when (doc.opt("format_version")) {
            1 -> { requireLegacyShape(doc); if (!encrypted) validatePlaintext(doc) }
            2 -> if (!encrypted) unavailable()
            else -> throw ApiException(409, "LOCAL_STORAGE_VERSION_UNAVAILABLE")
        }
        return doc
    }

    private fun fenceIdentity(fence: JSONObject): JSONObject {
        if (fence.optInt("format_version") != 2) unavailable()
        return identity(fence)
    }

    private fun identity(value: JSONObject): JSONObject {
        val generation = value.getString("generation")
        if (!Regex("[a-f0-9-]{36}").matches(generation) ||
            value.getString("source_kind") !in setOf("empty", "plain", "encrypted") ||
            !Regex("[a-f0-9]{64}").matches(value.getString("source_hash"))) unavailable()
        if (value.getString("source_kind") != "empty" &&
            !Regex("[a-f0-9]{64}").matches(value.getString("preserved_hash"))) unavailable()
        return JSONObject().put("generation", generation).put("source_kind", value.get("source_kind"))
            .put("source_hash", value.get("source_hash")).put("preserved_hash", value.get("preserved_hash"))
    }

    private fun requireIdentity(left: JSONObject, right: JSONObject) {
        if (canonicalJson(identity(left)) != canonicalJson(identity(right))) unavailable()
    }

    private fun openDatabase(create: Boolean): SQLiteDatabase {
        if (!create && !databaseFile.isFile) unavailable()
        val params = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
                (if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0))
            .setJournalMode("DELETE").setSynchronousMode("EXTRA")
            .setErrorHandler(DatabaseErrorHandler { /* Preserve corrupt evidence for explicit deletion. */ })
            .build()
        val database = SQLiteDatabase.openDatabase(databaseFile, params)
        try {
            database.rawQuery("PRAGMA journal_mode", null).use { if (!it.moveToFirst() || it.getString(0).lowercase() != "delete") unavailable() }
            database.rawQuery("PRAGMA synchronous", null).use { if (!it.moveToFirst() || it.getInt(0) != 3) unavailable() }
            return database
        } catch (error: Throwable) { database.close(); throw error }
    }

    private fun hasRecords(database: SQLiteDatabase): Boolean = database.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='records'", null
    ).use { it.moveToFirst() }

    private fun hasUserTables(database: SQLiteDatabase): Boolean = database.rawQuery(
        "SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'", null
    ).use { it.moveToFirst() }

    internal fun readControl(database: SQLiteDatabase, identity: JSONObject): JSONObject {
        return readControl(database, identity, null)
    }

    internal fun readControl(database: SQLiteDatabase, identity: JSONObject, scope: PrivateRecordCipher.KeyScope?): JSONObject {
        if (database.version != 2) throw ApiException(409, "LOCAL_STORAGE_VERSION_UNAVAILABLE")
        val control = readValue(database, identity, "control", "", "", scope) as? JSONObject ?: unavailable()
        requireIdentity(identity, control)
        val counts = counts(database)
        if (control.getLong("session_count") != counts.first || control.getLong("receipt_count") != counts.second ||
            count(database, "root") != 1L || count(database, "control") != 1L ||
            count(database, null) != counts.first + counts.second + 2L) unavailable()
        return control
    }

    internal fun writeControl(database: SQLiteDatabase, identity: JSONObject, phase: String) {
        writeControl(database, identity, phase, null)
    }

    internal fun writeControl(database: SQLiteDatabase, identity: JSONObject, phase: String, scope: PrivateRecordCipher.KeyScope?) {
        val counts = counts(database)
        writeValue(database, identity, "control", "", "", JSONObject(identity.toString())
            .put("phase", phase).put("session_count", counts.first).put("receipt_count", counts.second), scope)
    }

    private fun counts(database: SQLiteDatabase) = count(database, "session") to count(database, "receipt")
    private fun count(database: SQLiteDatabase, kind: String?): Long = database.rawQuery(
        "SELECT count(*) FROM records" + if (kind == null) "" else " WHERE kind = ?",
        kind?.let { arrayOf(it) },
    ).use { if (!it.moveToFirst()) unavailable(); it.getLong(0) }

    private fun keys(database: SQLiteDatabase): List<List<String>> = database.rawQuery(
        "SELECT kind, session_id, record_id FROM records ORDER BY rowid", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2))) } }

    internal fun ids(database: SQLiteDatabase, kind: String, sessionId: String = ""): List<String> = database.rawQuery(
        if (kind == "session") "SELECT session_id FROM records WHERE kind = ? ORDER BY rowid"
        else "SELECT record_id FROM records WHERE kind = ? AND session_id = ? ORDER BY rowid",
        if (kind == "session") arrayOf(kind) else arrayOf(kind, sessionId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun cipher(identity: JSONObject, kind: String, sessionId: String, recordId: String, scope: PrivateRecordCipher.KeyScope?): PrivateRecordCipher {
        val domain = JSONArray(listOf("game-record-v2", storageName, identity.getString("generation"), kind, sessionId, recordId)).toString()
        return if (scope == null) PrivateRecordCipher(context, domain) else PrivateRecordCipher(context, domain, scope)
    }

    internal fun readValue(database: SQLiteDatabase, identity: JSONObject, kind: String, sessionId: String, recordId: String): Any? {
        return readValue(database, identity, kind, sessionId, recordId, null)
    }

    internal fun readValue(database: SQLiteDatabase, identity: JSONObject, kind: String, sessionId: String, recordId: String, scope: PrivateRecordCipher.KeyScope?): Any? {
        val bytes = work(RecordStoreWork.ROW_QUERY) { readPayload(database, kind, sessionId, recordId) } ?: return null
        val plain = work(RecordStoreWork.ROW_DECRYPT) { cipher(identity, kind, sessionId, recordId, scope).decrypt(bytes) }
        return work(RecordStoreWork.ROW_PARSE) { parseDocument(plain).get("value") }
    }

    private fun readPayload(database: SQLiteDatabase, kind: String, sessionId: String, recordId: String): ByteArray? {
        val args = arrayOf(kind, sessionId, recordId)
        val length = database.rawQuery("SELECT length(payload) FROM records WHERE kind=? AND session_id=? AND record_id=?", args)
            .use { if (!it.moveToFirst()) return null else it.getLong(0) }
        if (length !in PrivateRecordCipher.ENVELOPE_OVERHEAD_BYTES.toLong()..MAX_ENCRYPTED.toLong()) unavailable()
        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val size = minOf(128 * 1024, bytes.size - offset)
            database.rawQuery("SELECT substr(payload, ?, ?) FROM records WHERE kind=? AND session_id=? AND record_id=?",
                arrayOf((offset + 1).toString(), size.toString(), *args)).use {
                if (!it.moveToFirst()) unavailable()
                val part = it.getBlob(0)
                if (part.size != size) unavailable()
                part.copyInto(bytes, offset)
            }
            offset += size
        }
        return bytes
    }

    internal fun writeValue(database: SQLiteDatabase, identity: JSONObject, kind: String, sessionId: String, recordId: String, value: Any) {
        writeValue(database, identity, kind, sessionId, recordId, value, null)
    }

    internal fun writeValue(database: SQLiteDatabase, identity: JSONObject, kind: String, sessionId: String, recordId: String, value: Any, scope: PrivateRecordCipher.KeyScope?) {
        val plain = work(RecordStoreWork.ROW_SERIALIZE) { encode(JSONObject().put("value", value)) }
        val encrypted = work(RecordStoreWork.ROW_ENCRYPT) { cipher(identity, kind, sessionId, recordId, scope).encryptExisting(plain) }
        work(RecordStoreWork.ROW_WRITE) {
        val fields = ContentValues().apply { put("payload", encrypted) }
        if (database.update("records", fields, "kind=? AND session_id=? AND record_id=?", arrayOf(kind, sessionId, recordId)) == 0) {
            fields.put("kind", kind); fields.put("session_id", sessionId); fields.put("record_id", recordId)
            database.insertOrThrow("records", null, fields)
        }
        }
    }

    internal fun signal(stage: RecordStoreStage) { checkpoint?.invoke(stage) }
    private fun databaseSidecars() = listOf("-journal", "-wal", "-shm").map { File(databaseFile.path + it) }
    private fun deleteFiles(files: List<File>) {
        for (file in files) if (file.exists() && !file.delete()) throw IOException("Private record removal failed")
        if (files.any { it.exists() }) throw IOException("Private records remain")
        syncDirectory()
    }
    private fun syncDirectory() {
        val descriptor = Os.open(context.noBackupFilesDir.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream); syncDirectory() }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
        if (!atomicRead(file, maxOf(bytes.size, 1)).contentEquals(bytes)) unavailable()
    }
    private fun atomicRead(file: File, maximum: Int): ByteArray = AtomicFile(file).openRead().use { input ->
        if (input.channel.size() > maximum) throw ApiException(507, "LOCAL_STORAGE_FULL")
        val bytes = input.readBytes()
        if (bytes.size > maximum) throw ApiException(507, "LOCAL_STORAGE_FULL")
        bytes
    }

    companion object {
        internal const val MAX_RECORD_BYTES = 16 * 1024 * 1024
        private const val MAX_ENCRYPTED = MAX_RECORD_BYTES + PrivateRecordCipher.ENVELOPE_OVERHEAD_BYTES
        private fun atomicPaths(file: File) = listOf(file, File(file.path + ".bak"), File(file.path + ".new"))
        private fun atomicExists(file: File) = atomicPaths(file).any { it.exists() }
        private fun emptyDocument() = JSONObject().put("format_version", 1).put("sessions", JSONObject()).put("last_session_id", JSONObject.NULL)
        private fun requireLegacyShape(document: JSONObject) {
            if (document.opt("format_version") != 1 || document.optJSONObject("sessions") == null)
                throw ApiException(409, "LOCAL_STORAGE_VERSION_UNAVAILABLE")
        }
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun encode(value: JSONObject): ByteArray = value.toString().toByteArray(Charsets.UTF_8).also {
            if (it.size > MAX_RECORD_BYTES) throw ApiException(507, "LOCAL_STORAGE_FULL")
        }
        private fun parseDocument(bytes: ByteArray): JSONObject {
            if (bytes.size > MAX_RECORD_BYTES) throw ApiException(507, "LOCAL_STORAGE_FULL")
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val tokener = JSONTokener(text)
            val value = tokener.nextValue() as? JSONObject ?: unavailable()
            if (tokener.nextClean() != '\u0000') unavailable()
            return value
        }
    }
}

/** One caller holds LocalGameClient's namespace mutex across this transaction and projection. */
internal class RecordTransaction internal constructor(
    private val owner: GameRecordStore,
    private var database: SQLiteDatabase?,
    private var identity: JSONObject?,
    private val keyScope: PrivateRecordCipher.KeyScope,
) : Closeable {
    // Keep the original JVM constructor for the retained paired-measurement test APK.
    internal constructor(owner: GameRecordStore, database: SQLiteDatabase?, identity: JSONObject?) :
        this(owner, database, identity, owner.keyScope())
    private var rootValue: JSONObject? = null
    private var rootDirty = false
    private val sessions = linkedMapOf<String, Any?>()
    private val receipts = linkedMapOf<Pair<String, String>, Any?>()
    private val dirtySessions = linkedSetOf<String>()
    private val dirtyReceipts = linkedSetOf<Pair<String, String>>()
    private var closed = false
    private var inTransaction = false

    init { boundary { begin() } }

    private inline fun <T> boundary(block: () -> T): T = try { storageBoundary(block) }
    catch (error: Throwable) {
        try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
        throw error
    }

    private fun begin() {
        val db = database ?: return
        db.beginTransaction(); inTransaction = true
        try {
            val control = owner.readControl(db, identity!!, keyScope)
            if (control.getString("phase") != "ACTIVE") unavailable()
        } catch (error: Throwable) {
            try { finishTransaction(db, error) } finally { inTransaction = false }
            throw error
        }
    }

    val root: JSONObject get() = boundary {
        checkOpen()
        rootValue ?: (database?.let { owner.readValue(it, identity!!, "root", "", "", keyScope) as? JSONObject ?: unavailable() }
            ?: JSONObject().put("format_version", 1).put("last_session_id", JSONObject.NULL)).also { rootValue = it }
    }
    fun putRoot(root: JSONObject) = boundary { checkOpen(); rootValue = root; rootDirty = true }
    fun sessionIds(): List<String> = boundary {
        checkOpen(); ((database?.let { owner.ids(it, "session") } ?: emptyList()) + dirtySessions).distinct()
    }
    fun readSession(id: String): JSONObject? = boundary {
        checkOpen()
        if (!sessions.containsKey(id)) sessions[id] = database?.let { owner.readValue(it, identity!!, "session", id, "", keyScope) }
        val value = sessions[id]
        if (value == null) null else value as? JSONObject ?: unavailable()
    }
    fun putSession(id: String, record: JSONObject) = boundary {
        checkOpen()
        val embedded = record.optJSONObject("receipts")
        if (embedded != null && embedded.length() != 0) {
            for (command in embedded.keys().asSequence()) {
                val key = id to command; receipts[key] = embedded.get(command); dirtyReceipts.add(key)
            }
            record.put("receipts", JSONObject())
        }
        sessions[id] = record; dirtySessions.add(id)
    }
    fun receiptIds(session: String): List<String> = boundary {
        checkOpen(); readSession(session)?.getJSONObject("receipts") ?: return@boundary emptyList()
        ((database?.let { owner.ids(it, "receipt", session) } ?: emptyList()) + dirtyReceipts.filter { it.first == session }.map { it.second }).distinct()
    }
    fun readReceipt(session: String, command: String): JSONObject? = boundary {
        checkOpen(); readSession(session)?.getJSONObject("receipts") ?: return@boundary null
        val key = session to command
        if (!receipts.containsKey(key)) receipts[key] = database?.let { owner.readValue(it, identity!!, "receipt", session, command, keyScope) }
        val value = receipts[key]
        if (value == null) null else value as? JSONObject ?: unavailable()
    }
    fun putReceipt(session: String, command: String, wrapper: JSONObject) = boundary {
        checkOpen(); (readSession(session) ?: unavailable()).getJSONObject("receipts")
        val key = session to command; receipts[key] = wrapper; dirtyReceipts.add(key)
    }

    @Suppress("UNUSED_PARAMETER")
    fun commit(recovery: Boolean) = boundary {
        checkOpen()
        if (!rootDirty && dirtySessions.isEmpty() && dirtyReceipts.isEmpty()) return@boundary
        if (database == null) {
            val initialized = owner.initialize(keyScope)
            database = initialized.database; identity = initialized.identity
            inTransaction = initialized.inTransaction
            initialized.database = null; initialized.inTransaction = false; initialized.closed = true
        }
        val db = database!!
        var writeFailure: Throwable? = null
        try {
            if (rootDirty) owner.writeValue(db, identity!!, "root", "", "", root, keyScope)
            for (id in dirtySessions) owner.writeValue(db, identity!!, "session", id, "", sessions.getValue(id) ?: JSONObject.NULL, keyScope)
            for (key in dirtyReceipts) owner.writeValue(db, identity!!, "receipt", key.first, key.second, receipts.getValue(key) ?: JSONObject.NULL, keyScope)
            owner.writeControl(db, identity!!, "ACTIVE", keyScope)
            owner.signal(RecordStoreStage.BEFORE_COMMIT)
            db.setTransactionSuccessful()
        } catch (error: Throwable) {
            writeFailure = error
            throw error
        } finally {
            // endTransaction is the durable boundary, including disk-full/sync failures.
            try { owner.work(RecordStoreWork.COMMIT) { finishTransaction(db, writeFailure) } }
            finally { inTransaction = false }
        }
        rootDirty = false; dirtySessions.clear(); dirtyReceipts.clear()
        owner.signal(RecordStoreStage.AFTER_COMMIT)
        begin()
    }

    private fun checkOpen() { check(!closed) }
    override fun close() {
        if (closed) return
        closed = true
        storageBoundary {
            try { if (inTransaction) database?.endTransaction() }
            finally {
                inTransaction = false
                try { database?.close() }
                finally {
                    database = null; identity = null
                    keyScope.close()
                    rootValue = null; sessions.clear(); receipts.clear()
                    rootDirty = false; dirtySessions.clear(); dirtyReceipts.clear()
                }
            }
        }
    }
}

private fun unavailable(): Nothing = throw ApiException(500, "LOCAL_STORAGE_UNAVAILABLE")
private fun finishTransaction(database: SQLiteDatabase, originalFailure: Throwable?) {
    try { database.endTransaction() }
    catch (cleanup: Throwable) {
        // SQLITE_FULL can already roll back the native transaction. Preserve that original
        // failure if Android's subsequent ROLLBACK also fails; otherwise propagate commit failure.
        if (originalFailure != null) originalFailure.addSuppressed(cleanup) else throw cleanup
    }
}
private inline fun <T> storageBoundary(block: () -> T): T = try { block() }
catch (error: ApiException) { throw error }
catch (_: SQLiteFullException) { throw ApiException(507, "LOCAL_STORAGE_FULL") }
catch (_: SQLiteException) { unavailable() }
catch (_: IOException) { unavailable() }
catch (_: GeneralSecurityException) { unavailable() }
catch (_: ProviderException) { unavailable() }
catch (_: JSONException) { unavailable() }
catch (_: android.system.ErrnoException) { unavailable() }
