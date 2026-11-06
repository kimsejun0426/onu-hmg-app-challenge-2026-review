package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Interrupted first AtomicFile writes, not an OS process-kill or power-loss experiment. */
@RunWith(AndroidJUnit4::class)
class RecordStoreInterruptedFileTest {
    @Test fun interruptedFirstPreservedCopyCanRetryWithoutLosingAnyLegacyReceipt() = runBlocking {
        for (encrypted in listOf(false, true)) scoped { app ->
            val fixture = legacyFixture(app, encrypted)
            val preserved = File(app.noBackupFilesDir, "${fixture.name}.legacy-v1.enc")
            interruptedFirstWrite(preserved, fixture.stored.take(13).toByteArray())
            assertFalse(File(app.noBackupFilesDir, "${fixture.name}.migration").exists())
            assertFalse(LocalGameClient(app, fixture.name).recordStore.databaseFile.exists())
            assertArrayEquals(fixture.stored, fixture.primary.readBytes())

            assertRecoveredExactly(app, fixture)
        }
    }

    @Test fun interruptedFirstManifestCanRetryFromTheAuthenticatedLegacySource() = runBlocking {
        for (encrypted in listOf(false, true)) scoped { app ->
            val fixture = legacyFixture(app, encrypted)
            val preserved = File(app.noBackupFilesDir, "${fixture.name}.legacy-v1.enc")
            preserved.writeBytes(if (encrypted) fixture.stored else
                PrivateRecordCipher(app, "local-game:${fixture.name}").encryptExisting(fixture.plaintext))
            val preservedBytes = preserved.readBytes()
            val manifest = File(app.noBackupFilesDir, "${fixture.name}.migration")
            interruptedFirstWrite(manifest, byteArrayOf(0x4f, 0x4e, 0x55, 0x31, 0x01))
            assertFalse(LocalGameClient(app, fixture.name).recordStore.databaseFile.exists())
            assertArrayEquals(fixture.stored, fixture.primary.readBytes())

            assertRecoveredExactly(app, fixture)
            assertArrayEquals("A complete preservation copy must never be replaced", preservedBytes, preserved.readBytes())
        }
    }

    @Test fun activeStoreCannotTreatAnIncompleteOrDamagedManifestAsPreImportDebris() = runBlocking {
        for (newOnly in listOf(true, false)) scoped { app ->
            val fixture = legacyFixture(app, encrypted = true)
            val client = LocalGameClient(app, fixture.name)
            assertRecoveredExactly(app, fixture)
            val manifest = File(app.noBackupFilesDir, "${fixture.name}.migration")
            if (newOnly) {
                check(manifest.delete())
                interruptedFirstWrite(manifest, byteArrayOf(0x4f, 0x4e, 0x55, 0x31, 0x01))
            } else {
                val bytes = manifest.readBytes()
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                manifest.writeBytes(bytes)
            }
            val expectedFiles = namespaceBytes(app, fixture.name)
            val expectedRows = recordStoreCiphertextSnapshot(client)
            repeat(2) {
                rejectStorage { LocalGameClient(app, fixture.name).restore() }
                assertFileBytes(expectedFiles, namespaceBytes(app, fixture.name))
                assertEquals(expectedRows, recordStoreCiphertextSnapshot(client))
            }
        }
    }

    @Test fun activeStoreWithMissingFenceCannotRestartFromItsPreservedOriginal() = runBlocking {
        scoped { app ->
            val fixture = legacyFixture(app, encrypted = true)
            val client = LocalGameClient(app, fixture.name)
            assertRecoveredExactly(app, fixture)
            check(fixture.primary.delete())
            val expectedFiles = namespaceBytes(app, fixture.name)
            val expectedRows = recordStoreCiphertextSnapshot(client)
            repeat(2) {
                rejectStorage { LocalGameClient(app, fixture.name).restore() }
                assertFalse("No replacement primary may be manufactured", fixture.primary.exists())
                assertFileBytes(expectedFiles, namespaceBytes(app, fixture.name))
                assertEquals(expectedRows, recordStoreCiphertextSnapshot(client))
            }
        }
    }

    private data class Request(val session: String, val command: JSONObject)
    private data class LegacyFixture(val name: String, val primary: File, val plaintext: ByteArray,
        val stored: ByteArray, val document: JSONObject, val requests: List<Request>, val encrypted: Boolean)

    private suspend fun legacyFixture(app: Context, encrypted: Boolean): LegacyFixture {
        val seed = LocalGameClient(app, "seed")
        val requests = mutableListOf<Request>()
        repeat(2) {
            var state = seed.create(playersBody("합성별명", null))
            for (type in listOf("session.pause", "session.resume", "session.pause")) {
                val command = commandEnvelope(state, UUID.randomUUID().toString(), type, JSONObject())
                requests += Request(state.sessionId, command.copyJson())
                state = Snapshot.from(seed.command(state.sessionId, command))
            }
        }
        val document = readRecordStoreDocument(seed)
            .put("unknown_root_extension", JSONObject().put("preserve", true))
        val name = "interrupted_${UUID.randomUUID().toString().replace("-", "")}"
        val primary = File(app.noBackupFilesDir, "$name.json")
        val plaintext = ("  " + document.toString(2) + "\n").toByteArray(Charsets.UTF_8)
        val stored = if (encrypted) PrivateRecordCipher(app, "local-game:$name").encryptExisting(plaintext) else plaintext
        primary.writeBytes(stored)
        return LegacyFixture(name, primary, plaintext, stored, document, requests, encrypted)
    }

    private suspend fun assertRecoveredExactly(app: Context, fixture: LegacyFixture) {
        val client = LocalGameClient(app, fixture.name)
        assertEquals(canonicalJson(fixture.document), canonicalJson(readRecordStoreDocument(client)))
        val preserved = File(app.noBackupFilesDir, "${fixture.name}.legacy-v1.enc").readBytes()
        if (fixture.encrypted) assertArrayEquals("Exact encrypted v1 original", fixture.stored, preserved)
        assertArrayEquals("Exact original UTF-8 bytes including whitespace", fixture.plaintext,
            PrivateRecordCipher(app, "local-game:${fixture.name}").decrypt(preserved))
        assertFalse(File(app.noBackupFilesDir, "${fixture.name}.legacy-v1.enc.new").exists())
        assertFalse(File(app.noBackupFilesDir, "${fixture.name}.migration.new").exists())
        val fence = JSONObject(String(PrivateRecordCipher(app, "local-game:${fixture.name}")
            .decrypt(fixture.primary.readBytes()), Charsets.UTF_8))
        assertEquals(2, fence.getInt("format_version"))

        val committed = recordStoreCiphertextSnapshot(client)
        var receipts = 0
        val sessions = fixture.document.getJSONObject("sessions")
        for (session in sessions.keys().asSequence()) {
            val originals = sessions.getJSONObject(session).getJSONObject("receipts")
            for (command in originals.keys().asSequence()) {
                val expected = originals.getJSONObject(command).getJSONObject("result")
                assertEquals(canonicalJson(expected), canonicalJson(client.receipt(session, command)))
                receipts++
            }
        }
        assertEquals("Every generated command must have its original durable receipt", 6, receipts)
        for (request in fixture.requests) {
            val expected = sessions.getJSONObject(request.session).getJSONObject("receipts")
                .getJSONObject(request.command.getString("command_id")).getJSONObject("result")
            assertEquals(canonicalJson(expected), canonicalJson(client.command(request.session, request.command.copyJson())))
        }
        assertEquals(committed, recordStoreCiphertextSnapshot(client))
        assertEquals(canonicalJson(fixture.document), canonicalJson(readRecordStoreDocument(LocalGameClient(app, fixture.name))))
        assertEquals(committed, recordStoreCiphertextSnapshot(client))
    }

    private fun interruptedFirstWrite(base: File, fragment: ByteArray) {
        check(!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists())
        val stream = AtomicFile(base).startWrite()
        stream.write(fragment)
        stream.fd.sync()
        // Deliberately bypass finishWrite/failWrite to leave an unfinished first-write artifact.
        stream.close()
        assertFalse(base.exists())
        assertTrue(File(base.path + ".new").isFile)
    }

    private fun namespaceBytes(app: Context, name: String): Map<String, ByteArray> =
        app.noBackupFilesDir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("$name.") }
            .associate { it.name to it.readBytes() }

    private fun assertFileBytes(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, actual.getValue(name)) }
    }

    private suspend fun rejectStorage(action: suspend () -> Any?) {
        try { action(); fail("Expected LOCAL_STORAGE_UNAVAILABLE") }
        catch (error: ApiException) {
            assertEquals(500, error.status)
            assertEquals("LOCAL_STORAGE_UNAVAILABLE", error.code)
        }
    }

    private suspend fun scoped(block: suspend (ScopedApplication) -> Unit) {
        val app = ScopedApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        try { block(app) } finally { withContext(Dispatchers.IO) { app.clean() } }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val scope = "record_interrupted_${UUID.randomUUID()}"
        private val directory = File(context.noBackupFilesDir, scope).also { check(it.mkdirs()) }
        private val preferences = mutableSetOf<String>()
        private val alias = "onu.records.v1." + MessageDigest.getInstance("SHA-256")
            .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scopedName = "$scope.$name"
            preferences.add(scopedName)
            return super.getSharedPreferences(scopedName, mode)
        }
        fun clean() {
            preferences.forEach { check(super.getSharedPreferences(it, MODE_PRIVATE).edit().clear().commit()) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(directory.deleteRecursively())
        }
    }
}
