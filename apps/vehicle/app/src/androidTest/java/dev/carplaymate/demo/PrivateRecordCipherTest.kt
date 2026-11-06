package dev.carplaymate.demo

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyInfo
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real AndroidKeyStore operations in unique directories; never delete the installed family's key.
 */
@RunWith(AndroidJUnit4::class)
class PrivateRecordCipherTest {
    @Test
    fun authenticatedRecordsUseFreshIvsAndANonExportableAes256Key() = withScope { app ->
        val cipher = PrivateRecordCipher(app, "test-domain")
        val plaintext = "가짜별명, 관심사 dinosaurs".toByteArray(Charsets.UTF_8)
        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)
        assertTrue(PrivateRecordCipher.hasEnvelope(first))
        assertEquals(plaintext.size + 32, first.size)
        assertFalse(first.copyOfRange(4, 16).contentEquals(second.copyOfRange(4, 16)))
        assertArrayEquals(plaintext, cipher.decrypt(first))
        assertArrayEquals(plaintext, PrivateRecordCipher(app, "test-domain").decrypt(second))
        val key = app.keyStore().getKey(app.alias, null)
        assertNull("Keystore material must not be exportable", key.encoded)
        val info =
            SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key as javax.crypto.SecretKey, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertTrue(KeyProperties.BLOCK_MODE_GCM in info.blockModes)
    }

    @Test
    fun DomainIvCiphertextTagAndHeaderChangesCannotAuthenticate() = withScope { app ->
        val cipher = PrivateRecordCipher(app, "local-game:sample")
        val encrypted = cipher.encrypt("private family record".toByteArray())
        rejected { PrivateRecordCipher(app, "family_profile_v3").decrypt(encrypted) }
        for (index in listOf(0, 4, 16, encrypted.lastIndex)) {
            val changed = encrypted.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            rejected { cipher.decrypt(changed) }
        }
        rejected { cipher.decrypt(encrypted.copyOf(31)) }
        rejected { cipher.decrypt(encrypted.copyOf(encrypted.size - 1)) }
        assertEquals("private family record", cipher.decrypt(encrypted).toString(Charsets.UTF_8))
    }

    @Test
    fun MissingKeysAreNotRegeneratedByExistingRecordReadsOrWrites() = withScope { app ->
        val cipher = PrivateRecordCipher(app, "key-loss")
        val encrypted = cipher.encrypt("preserve this ciphertext".toByteArray())
        assertEquals("preserve this ciphertext", cipher.decrypt(encrypted).toString(Charsets.UTF_8))
        // Model key loss after a valid read, before an established store commits its next row.
        app.keyStore().deleteEntry(app.alias)
        rejected { PrivateRecordCipher(app, "key-loss").decrypt(encrypted) }
        rejected { cipher.encryptExisting("updated record".toByteArray()) }
        assertFalse(
            "An established record read/write must not silently replace the key",
            app.keyStore().containsAlias(app.alias),
        )
        // Only a deliberately fresh-store bootstrap may create a key; old data stays unreadable.
        val fresh = cipher.encrypt("fresh record".toByteArray())
        assertTrue(app.keyStore().containsAlias(app.alias))
        assertEquals("fresh record", cipher.decrypt(fresh).toString(Charsets.UTF_8))
        rejected { cipher.decrypt(encrypted) }
    }

    @Test
    fun gameRowsContainNoPlaintextAndDuplicateCommandsKeepTheCiphertextCommit() =
        withScope { app ->
            runBlocking {
                val client = LocalGameClient(app)
                val created = client.create(playersBody("암호화가족", null))
                val ack =
                    commandEnvelope(
                        created,
                        uid(),
                        "playback.completed",
                        JSONObject().put("playback_id", requireNotNull(created.speechPlan).playbackId),
                    )
                val result = client.command(created.sessionId, ack)
                val committed = recordStoreCiphertextSnapshot(client)
                assertTrue(committed.isNotEmpty())
                committed.values.forEach { assertTrue(PrivateRecordCipher.hasEnvelope(unhex(it))) }
                assertTrue(readRecordStoreDocument(client).toString().contains("암호화가족"))
                assertNoPrivatePlaintext(app, "암호화가족", "body_hash")
                assertEquals(
                    canonicalJson(result),
                    canonicalJson(LocalGameClient(app).command(created.sessionId, ack)),
                )
                assertEquals(
                    "An identical retry performs no fresh row encryption/write",
                    committed,
                    recordStoreCiphertextSnapshot(client),
                )
                val restored = requireNotNull(LocalGameClient(app).restore())
                assertEquals("PAUSED", restored.status)
                assertNull(restored.speechPlan)
                assertEquals(canonicalJson(Snapshot.from(result).game), canonicalJson(restored.game))
                assertEquals(
                    canonicalJson(result),
                    canonicalJson(client.receipt(created.sessionId, ack.getString("command_id"))),
                )
                assertNoPrivatePlaintext(app, "암호화가족", "body_hash")
            }
        }

    @Test
    fun encryptedLegacyImportPreservesTheOriginalArchiveAndReceiptAfterInterruptedWrite() =
        withScope { app ->
            runBlocking {
                val seed = LocalGameClient(app, "fixture_seed")
                val created = seed.create(playersBody("이전가족", null))
                val pause = commandEnvelope(created, uid(), "session.pause", JSONObject())
                val receipt = seed.command(created.sessionId, pause)
                val document = readRecordStoreDocument(seed).put("format_version", 1)
                val legacy = ("  " + document.toString(2) + "\n").toByteArray(Charsets.UTF_8)
                val name = "legacy_cipher"
                val file = app.gameFile(name)
                val original = PrivateRecordCipher(app, "local-game:$name").encryptExisting(legacy)
                file.writeBytes(original)
                assertArrayEquals(legacy, readGameStorePlaintext(app, file))
                val interrupted = AtomicFile(file).startWrite()
                interrupted.write(
                    PrivateRecordCipher(app, "local-game:$name").encryptExisting("incomplete".toByteArray())
                )
                interrupted.fd.sync()
                interrupted.close()
                val reopened = LocalGameClient(app, name)
                assertEquals(
                    canonicalJson(receipt),
                    canonicalJson(reopened.receipt(created.sessionId, pause.getString("command_id"))),
                )
                assertEquals(canonicalJson(document), canonicalJson(readRecordStoreDocument(reopened)))
                val archive = File(app.noBackupFilesDir, "$name.legacy-v1.enc")
                assertArrayEquals("The original encrypted v1 archive is immutable", original, archive.readBytes())
                val committed = recordStoreCiphertextSnapshot(reopened)
                assertEquals("PAUSED", requireNotNull(reopened.restore()).status)
                assertEquals(committed, recordStoreCiphertextSnapshot(reopened))
                assertArrayEquals(original, archive.readBytes())
                assertFalse(File(file.path + ".new").exists())
                assertFalse(File(file.path + ".bak").exists())
            }
        }

    @Test
    fun alteredSessionAndReceiptRowsFailAuthenticationWithoutRepairOrDeletion() =
        withScope { app ->
            runBlocking {
                val client = LocalGameClient(app)
                val created = client.create(playersBody("보존가족", null))
                val pause = commandEnvelope(created, uid(), "session.pause", JSONObject())
                client.command(created.sessionId, pause)
                val original = recordStoreCiphertextSnapshot(client)
                val sessionRow = rowIdentity(original, "session", created.sessionId)
                val receiptRow = rowIdentity(original, "receipt", created.sessionId, pause.getString("command_id"))
                for (identity in listOf(sessionRow, receiptRow)) {
                    val good = unhex(original.getValue(identity))
                    for (index in listOf(0, 4, 16, good.lastIndex)) {
                        val damaged = good.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
                        replaceRow(client, identity, damaged)
                        val beforeFailure = recordStoreCiphertextSnapshot(client)
                        apiRejected("LOCAL_STORAGE_UNAVAILABLE") {
                            if (identity == sessionRow) client.snapshot(created.sessionId)
                            else client.receipt(created.sessionId, pause.getString("command_id"))
                        }
                        assertEquals(beforeFailure, recordStoreCiphertextSnapshot(client))
                        replaceRow(client, identity, good)
                    }
                }
                assertEquals(original, recordStoreCiphertextSnapshot(client))
                assertEquals("PAUSED", client.snapshot(created.sessionId).status)
            }
        }

    @Test
    fun rowKindSessionAndCommandIdentityCannotBeSubstituted() = withScope { app ->
        runBlocking {
            val client = LocalGameClient(app)
            val first = client.create(playersBody("첫가족", null))
            val pause = commandEnvelope(first, uid(), "session.pause", JSONObject())
            val paused = Snapshot.from(client.command(first.sessionId, pause))
            val secondPause = commandEnvelope(paused, uid(), "session.pause", JSONObject())
            client.command(first.sessionId, secondPause)
            val second = client.create(playersBody("둘째가족", null))
            val original = recordStoreCiphertextSnapshot(client)
            val sessionA = rowIdentity(original, "session", first.sessionId)
            val sessionB = rowIdentity(original, "session", second.sessionId)
            val receiptA = rowIdentity(original, "receipt", first.sessionId, pause.getString("command_id"))
            val receiptB = rowIdentity(original, "receipt", first.sessionId, secondPause.getString("command_id"))
            val substitutions = listOf(sessionA to sessionB, receiptA to receiptB, sessionA to receiptA)
            for ((source, target) in substitutions) {
                replaceRow(client, target, unhex(original.getValue(source)))
                val damaged = recordStoreCiphertextSnapshot(client)
                val tuple = JSONArray(target)
                // The backend must reject before any game-model identity checks can hide missing AAD.
                apiRejected("LOCAL_STORAGE_UNAVAILABLE") {
                    client.recordStore.open().use { store ->
                        if (tuple.getString(0) == "session") store.readSession(tuple.getString(1))
                        else store.readReceipt(tuple.getString(1), tuple.getString(2))
                    }
                }
                assertEquals(damaged, recordStoreCiphertextSnapshot(client))
                replaceRow(client, target, unhex(original.getValue(target)))
            }
            assertEquals(original, recordStoreCiphertextSnapshot(client))
        }
    }

    @Test
    fun rowsFromAnotherStoreNamespaceOrDeletedGenerationCannotAuthenticate() = withScope { app ->
        runBlocking {
            val client = LocalGameClient(app)
            val created = client.create(playersBody("세대가족", null))
            client.command(created.sessionId, commandEnvelope(created, uid(), "session.pause", JSONObject()))
            val document = readRecordStoreDocument(client)
            val oldRows = recordStoreCiphertextSnapshot(client)
            val oldIdentity = rowIdentity(oldRows, "session", created.sessionId)
            val oldPayload = unhex(oldRows.getValue(oldIdentity))
            val other = LocalGameClient(app, "other_namespace")
            writeRecordStoreDocument(other, document)
            val otherRows = recordStoreCiphertextSnapshot(other)
            val otherIdentity = rowIdentity(otherRows, "session", created.sessionId)
            replaceRow(other, otherIdentity, oldPayload)
            apiRejected("LOCAL_STORAGE_UNAVAILABLE") {
                other.recordStore.open().use { it.readSession(created.sessionId) }
            }
            assertEquals(oldRows, recordStoreCiphertextSnapshot(client))

            client.deleteAll()
            writeRecordStoreDocument(client, document)
            val newRows = recordStoreCiphertextSnapshot(client)
            val newIdentity = rowIdentity(newRows, "session", created.sessionId)
            replaceRow(client, newIdentity, oldPayload)
            val damaged = recordStoreCiphertextSnapshot(client)
            apiRejected("LOCAL_STORAGE_UNAVAILABLE") {
                client.recordStore.open().use { it.readSession(created.sessionId) }
            }
            assertEquals(damaged, recordStoreCiphertextSnapshot(client))
            replaceRow(client, newIdentity, unhex(newRows.getValue(newIdentity)))
            assertEquals("PAUSED", client.snapshot(created.sessionId).status)
        }
    }

    @Test
    fun keyLossAfterAnAuthenticatedOpenCannotCreateAReplacementKeyOrCommitRows() = withScope { app ->
        runBlocking {
            val client = LocalGameClient(app)
            val created = client.create(playersBody("키보존가족", null))
            val original = recordStoreCiphertextSnapshot(client)
            client.recordStore.open().use { store ->
                val record = requireNotNull(store.readSession(created.sessionId))
                record.put("test_uncommitted_extension", "must not persist")
                store.putSession(created.sessionId, record)
                app.keyStore().deleteEntry(app.alias)
                apiRejected("LOCAL_STORAGE_UNAVAILABLE") { store.commit(recovery = false) }
            }
            assertFalse(app.keyStore().containsAlias(app.alias))
            assertEquals(original, recordStoreCiphertextSnapshot(client))
            apiRejected("LOCAL_STORAGE_UNAVAILABLE") { LocalGameClient(app).snapshot(created.sessionId) }
            apiRejected("LOCAL_STORAGE_UNAVAILABLE") { LocalGameClient(app).create(playersBody("새가족", null)) }
            assertFalse(app.keyStore().containsAlias(app.alias))
            assertEquals(original, recordStoreCiphertextSnapshot(client))
            client.deleteAll()
            assertNull(client.restore())
            assertFalse(client.recordStore.databaseFile.exists())
            assertFalse("Explicit deletion must not manufacture a key", app.keyStore().containsAlias(app.alias))
            assertFalse(app.noBackupFilesDir.listFiles().orEmpty().any { it.name.startsWith("local_game_v1.") })
            val fresh = client.create(playersBody("새가족", null))
            assertEquals("새가족", fresh.game.getJSONArray("players").getJSONObject(0).getString("nickname"))
        }
    }

    @Test
    fun keyLossAfterOpenRejectsAnUnreadRowWithoutChangingStoredBytes() = withScope { app ->
        runBlocking {
            val client = LocalGameClient(app)
            val created = client.create(playersBody("읽기보존가족", null))
            val original = recordStoreCiphertextSnapshot(client)
            client.recordStore.open().use { store ->
                // open has already authenticated control using the transaction's key scope.
                // This session has not yet been loaded into the transaction object cache.
                app.keyStore().deleteEntry(app.alias)
                apiRejected("LOCAL_STORAGE_UNAVAILABLE") { store.readSession(created.sessionId) }
            }
            assertFalse(app.keyStore().containsAlias(app.alias))
            assertEquals(original, recordStoreCiphertextSnapshot(client))
        }
    }

    @Test
    fun replacingTheAliasCannotMakeAnOpenTransactionCommitWithAnotherKey() = withScope { app ->
        runBlocking {
            val client = LocalGameClient(app)
            val created = client.create(playersBody("교체보존가족", null))
            val original = recordStoreCiphertextSnapshot(client)
            client.recordStore.open().use { store ->
                val record = requireNotNull(store.readSession(created.sessionId))
                store.putSession(created.sessionId, record.put("uncommitted_extension", true))
                app.keyStore().deleteEntry(app.alias)
                // Explicitly replace this disposable test key; the product never does this.
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(app.alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build())
                }.generateKey()
                val replacement = PrivateRecordCipher(app, "replacement-proof")
                val probe = replacement.encrypt("synthetic replacement".toByteArray())
                assertEquals("synthetic replacement", replacement.decrypt(probe).toString(Charsets.UTF_8))
                apiRejected("LOCAL_STORAGE_UNAVAILABLE") { store.commit(recovery = false) }
            }
            assertEquals(original, recordStoreCiphertextSnapshot(client))
            apiRejected("LOCAL_STORAGE_UNAVAILABLE") { LocalGameClient(app).snapshot(created.sessionId) }
            assertEquals(original, recordStoreCiphertextSnapshot(client))
        }
    }

    @Test
    fun encryptedFamilyPreferencesWithoutAGameDatabaseCannotAuthorizeAReplacementKey() = withScope { app ->
        runBlocking {
            assertTrue(FamilyPreferences(app).save(FamilyProfile(first = "가족보존")))
            val preferences = app.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
            val sealed = requireNotNull(preferences.getString("profile_v3", null))
            app.keyStore().deleteEntry(app.alias)
            val client = LocalGameClient(app)
            assertFalse(client.recordStore.databaseFile.exists())
            apiRejected("LOCAL_STORAGE_UNAVAILABLE") { client.create(playersBody("새가족", null)) }
            assertEquals(sealed, preferences.getString("profile_v3", null))
            assertFalse(app.keyStore().containsAlias(app.alias))
            assertFalse(client.recordStore.databaseFile.exists())
            assertFalse(app.gameFile().exists())
        }
    }

    @Test
    fun damagedUnvisitedLegacyRecordAndFutureSchemaCannotBeMigratedOrOverwritten() =
        withScope { app ->
            runBlocking {
                val seed = LocalGameClient(app, "validation_seed")
                val created = seed.create(playersBody("검증가족", null))
                val pause = commandEnvelope(created, uid(), "session.pause", JSONObject())
                seed.command(created.sessionId, pause)
                val good = readRecordStoreDocument(seed).put("format_version", 1)
                val damaged = JSONObject(good.toString())
                damaged.getJSONObject("sessions").put(
                    "unvisited",
                    JSONObject().put("state", "broken").put("receipts", JSONObject()),
                )
                val brokenClient = LocalGameClient(app, "broken_legacy")
                val brokenFile = app.gameFile("broken_legacy")
                brokenFile.writeText(damaged.toString())
                val original = brokenFile.readBytes()
                apiRejected("LOCAL_STORAGE_UNAVAILABLE") {
                    brokenClient.receipt(created.sessionId, pause.getString("command_id"))
                }
                assertArrayEquals(original, brokenFile.readBytes())
                assertFalse(brokenClient.recordStore.databaseFile.exists())
                for ((index, version) in listOf<Any>(99, "1").withIndex()) {
                    val name = "future_legacy_$index"
                    val futureClient = LocalGameClient(app, name)
                    val file = app.gameFile(name)
                    file.writeText(JSONObject(good.toString()).put("format_version", version).toString())
                    val future = file.readBytes()
                    apiRejected("LOCAL_STORAGE_VERSION_UNAVAILABLE") { futureClient.restore() }
                    assertArrayEquals(future, file.readBytes())
                    assertFalse(futureClient.recordStore.databaseFile.exists())
                }
            }
        }

    private fun rowIdentity(
        rows: Map<String, String>,
        kind: String,
        sessionId: String,
        recordId: String? = null,
    ): String = rows.keys.single {
        val tuple = JSONArray(it)
        tuple.getString(0) == kind && tuple.getString(1) == sessionId &&
            (recordId == null || tuple.getString(2) == recordId)
    }

    private fun replaceRow(client: LocalGameClient, identity: String, payload: ByteArray) {
        val tuple = JSONArray(identity)
        SQLiteDatabase.openDatabase(
                client.recordStore.databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                DatabaseErrorHandler { /* Never delete evidence during test inspection. */ },
            )
            .use { database ->
                val values = ContentValues().apply { put("payload", payload) }
                assertEquals(
                    1,
                    database.update(
                        "records",
                        values,
                        "kind = ? AND session_id = ? AND record_id = ?",
                        Array(3) { tuple.getString(it) },
                    ),
                )
            }
    }

    private fun unhex(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun assertNoPrivatePlaintext(app: ScopedApplication, vararg markers: String) {
        app.noBackupFilesDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val text = file.readBytes().toString(Charsets.UTF_8)
            markers.forEach { marker -> assertFalse("Private text leaked to ${file.name}", text.contains(marker)) }
        }
    }

    private fun rejected(action: () -> Unit) {
        try {
            action()
            fail("Authentication/key failure expected")
        } catch (_: java.security.GeneralSecurityException) {}
    }

    private suspend fun apiRejected(code: String, action: suspend () -> Any?) {
        try {
            action()
            fail("Expected $code")
        } catch (error: ApiException) {
            assertEquals(code, error.code)
        }
    }

    private fun withScope(block: (ScopedApplication) -> Unit) {
        val app = ScopedApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            block(app)
        } finally {
            app.clean()
        }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val scope = "cipher_${UUID.randomUUID()}"
        private val directory =
            File(context.noBackupFilesDir, scope).also {
                check(it.mkdirs())
            }
        val alias =
            "onu.records.v1." +
                MessageDigest.getInstance("SHA-256")
                    .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    .take(32)

        init {
            attachBaseContext(context)
        }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = directory

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("${scope}_$name", mode)

        fun gameFile(name: String = "local_game_v1") = File(directory, "$name.json")

        fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun clean() {
            check(getSharedPreferences("family_v1", Context.MODE_PRIVATE).edit().clear().commit())
            keyStore().deleteEntry(alias)
            check(directory.deleteRecursively())
        }
    }

    private fun uid() = UUID.randomUUID().toString()
}
