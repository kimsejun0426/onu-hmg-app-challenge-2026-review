package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** All private-record writers share one key; absence of their own record is not a fresh family. */
@RunWith(AndroidJUnit4::class)
class SharedKeyBootstrapTest {
    @Test fun firstInteractionPreferenceCannotReplaceTheLostKeyOfAnExistingProfile() = runBlocking {
        scoped { app ->
            val family = FamilyPreferences(app)
            assertTrue(family.save(FamilyProfile("합성별명", null)))
            val interaction = InteractionPreferences(app)
            assertFalse(interaction.read().configured)
            assertFalse(interaction.read().unavailable)
            val before = app.familyPreferences().all.toMap()
            assertTrue(before.containsKey("profile_v3"))
            assertFalse(before.containsKey(InteractionPreferences.KEY))
            app.removeKey()

            assertFalse(interaction.save(InteractionMode.VOICE))
            assertFalse("A first mode choice cannot create a replacement shared key", app.hasKey())
            assertEquals(before, app.familyPreferences().all)
            assertFalse(app.familyPreferences().contains(InteractionPreferences.KEY))
            assertNotNull("The original profile remains blocked, never treated as a new family", family.readIssue)
        }
    }

    @Test fun firstProfileCannotReplaceTheLostKeyOfAnExistingInteractionPreference() = runBlocking {
        scoped { app ->
            val interaction = InteractionPreferences(app)
            assertTrue(interaction.save(InteractionMode.READING))
            val family = FamilyPreferences(app)
            assertNull(family.profile)
            assertNull(family.readIssue)
            val before = app.familyPreferences().all.toMap()
            assertTrue(before.containsKey(InteractionPreferences.KEY))
            assertFalse(before.containsKey("profile_v3"))
            app.removeKey()

            assertFalse(family.save(FamilyProfile("합성별명", null)))
            assertFalse("A first profile cannot create a replacement shared key", app.hasKey())
            assertEquals(before, app.familyPreferences().all)
            assertFalse(app.familyPreferences().contains("profile_v3"))
            assertTrue(interaction.read().unavailable)
        }
    }

    @Test fun gameRecordsAlonePreventBothFirstPreferenceWritersFromReplacingTheLostKey() = runBlocking {
        for (modeFirst in listOf(true, false)) scoped { app ->
            val client = LocalGameClient(app)
            client.create(playersBody("합성별명", null))
            val beforeRows = recordStoreCiphertextSnapshot(client)
            val beforeFiles = app.recordFiles()
            assertTrue(beforeRows.isNotEmpty())
            assertTrue(beforeFiles.containsKey("local_game_v1.records.sqlite"))
            assertTrue(app.familyPreferences().all.isEmpty())
            app.removeKey()
            val family = FamilyPreferences(app)
            val interaction = InteractionPreferences(app)
            val attempts: List<() -> Boolean> = listOf(
                { interaction.save(InteractionMode.LISTEN_AND_SELECT) },
                { family.save(FamilyProfile("합성별명", null)) },
            ).let { if (modeFirst) it else it.reversed() }

            for (attempt in attempts) {
                assertFalse(attempt())
                assertFalse("Neither preference writer may bootstrap over encrypted game records", app.hasKey())
                assertTrue(app.familyPreferences().all.isEmpty())
                assertEquals(beforeRows, recordStoreCiphertextSnapshot(client))
                val afterFiles = app.recordFiles()
                assertEquals(beforeFiles.keys, afterFiles.keys)
                beforeFiles.forEach { (name, bytes) -> assertArrayEquals(name, bytes, afterFiles.getValue(name)) }
            }
        }
    }

    private suspend fun scoped(block: suspend (ScopedApplication) -> Unit) {
        val app = ScopedApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        try { block(app) } finally { withContext(Dispatchers.IO) { app.clean() } }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val scope = "shared_key_bootstrap_${UUID.randomUUID()}"
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
        fun familyPreferences(): SharedPreferences = getSharedPreferences("family_v1", MODE_PRIVATE)
        fun hasKey(): Boolean = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)
        fun removeKey() {
            assertTrue(hasKey())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            assertFalse(hasKey())
        }
        fun recordFiles(): Map<String, ByteArray> = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("local_game_v1.") }
            .associate { it.name to it.readBytes() }
        fun clean() {
            preferenceNames.forEach { check(super.getSharedPreferences(it, MODE_PRIVATE).edit().clear().commit()) }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(directory.deleteRecursively())
        }
    }
}
