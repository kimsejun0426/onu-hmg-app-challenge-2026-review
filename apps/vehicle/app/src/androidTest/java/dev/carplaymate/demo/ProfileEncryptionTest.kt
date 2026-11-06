package dev.carplaymate.demo

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileEncryptionTest {
    @Test
    fun newlyReservedWordsDoNotDestroyAnExistingFamilyDuringUpgrade() = scoped { context ->
        for (name in listOf("몰라", "잘몰라", "하기싫어", "안할래", "안하고싶어", "그만하고싶어", "오누야그만", "오누야 그만")) {
            assertTrue(context.preferences().edit().clear().putString("first", name).commit())
            val family = FamilyPreferences(context)
            assertNotNull("Existing names remain readable: $name", family.profile)
            assertTrue("Migration preserves the old name: $name", family.migrate())
            assertEquals(name, FamilyPreferences(context).profile!!.first)
            assertTrue(family.saveInterests(setOf("space"), emptySet()))
            assertEquals(setOf("space"), FamilyPreferences(context).profile!!.firstInterests)
            assertNotNull("New profiles cannot introduce ambiguous command names", FamilyProfile.problem(name, null))
        }
    }

    @Test
    fun addressedNicknameV2RecordsRemainReadableAndKeepIdentityThroughEncryptionAndSameNameSave() = scoped { context ->
        for ((first, second) in listOf("오누야 쉬자" to "오누야 멈춰", "오누야 하기 싫어" to null)) {
            val firstId = UUID.randomUUID().toString()
            val secondId = if (second == null) null else UUID.randomUUID().toString()
            val expected = FamilyProfile(first, second, firstId, secondId,
                setOf("space"), if (second == null) emptySet() else setOf("ocean"),
                firstInterestsConfigured = true, secondInterestsConfigured = second != null)
            val saved = JSONObject()
                .put("schema_version", 2)
                .put("first_id", firstId)
                .put("second_id", secondId ?: "")
                .put("first_interests", JSONArray().put("space"))
                .put("second_interests", JSONArray().also { if (second != null) it.put("ocean") })
                .put("first_configured", true)
                .put("second_configured", second != null)
            assertTrue(context.preferences().edit().clear()
                .putString("first", first).putString("second", second)
                .putString("profile_v2", saved.toString()).commit())
            val family = FamilyPreferences(context)
            assertNull(first, family.readIssue)
            assertEquals(expected, family.profile)
            assertTrue(first, family.migrate())
            assertEquals(setOf("profile_v3"), context.preferences().all.keys)
            val encrypted = context.preferences().all
            val reopened = FamilyPreferences(context)
            assertNull(first, reopened.readIssue)
            assertEquals(expected, reopened.profile)
            assertTrue(reopened.migrate())
            assertEquals(encrypted, context.preferences().all)
            assertTrue(first, reopened.save(FamilyProfile(first, second)))
            assertEquals(expected, FamilyPreferences(context).profile)
            assertNull(FamilyPreferences(context).readIssue)
        }
    }

    @Test
    fun savedNicknamesInterestsAndIdentityAreEncryptedAndSurviveRecreation() = scoped { context ->
        val family = FamilyPreferences(context)
        assertTrue(family.save(FamilyProfile("암호시험별", "암호시험달")))
        assertTrue(family.saveInterests(setOf("space"), setOf("ocean")))
        val expected = family.profile
        val values = context.preferences().all
        assertEquals(setOf("profile_v3"), values.keys)
        val envelope = Base64.decode(values["profile_v3"] as String, Base64.NO_WRAP)
        assertTrue(PrivateRecordCipher.hasEnvelope(envelope))
        val disk = context.preferenceFile().readText()
        assertFalse(
            disk.contains("암호시험별") || disk.contains("암호시험달") || disk.contains(expected!!.firstId!!)
        )
        assertEquals(expected, FamilyPreferences(context).profile)
        assertTrue(FamilyPreferences(context).migrate())
        assertEquals(values, context.preferences().all) // Already encrypted migration is a no-op.
    }

    @Test
    fun legacyV2MigrationPreservesBothIdentitiesAndRemovesPlaintextInTheSameCommit() =
        scoped { context ->
            val firstId = UUID.randomUUID().toString()
            val secondId = UUID.randomUUID().toString()
            val profile =
                JSONObject()
                    .put("schema_version", 2)
                    .put("first_id", firstId)
                    .put("second_id", secondId)
                    .put("first_interests", JSONArray().put("space"))
                    .put("second_interests", JSONArray().put("ocean"))
                    .put("first_configured", true)
                    .put("second_configured", true)
            assertTrue(
                context
                    .preferences()
                    .edit()
                    .putString("first", "예전별")
                    .putString("second", "예전달")
                    .putString("profile_v2", profile.toString())
                    .commit()
            )
            assertTrue(context.preferenceFile().readText().contains("예전별"))
            val family = FamilyPreferences(context)
            val before = family.profile
            assertTrue(family.migrate())
            assertEquals(before, FamilyPreferences(context).profile)
            assertEquals(listOf(firstId, secondId), family.profile!!.children.map { it.id })
            assertEquals(setOf("profile_v3"), context.preferences().all.keys)
            for (file in
                listOf(context.preferenceFile(), File(context.preferenceFile().path + ".bak"))) {
                if (file.exists()) assertFalse(file.readText().contains("예전별"))
            }
        }

    @Test
    fun modifiedCiphertextAndWrongDomainCannotResetOrOverwriteTheFamily() = scoped { context ->
        val family = FamilyPreferences(context)
        assertTrue(family.save(FamilyProfile("암호시험별", null)))
        val prefs = context.preferences()
        val original = prefs.getString("profile_v3", null)!!
        val changed = Base64.decode(original, Base64.NO_WRAP)
        changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
        val variants =
            listOf(
                Base64.encodeToString(changed, Base64.NO_WRAP),
                Base64.encodeToString(
                    PrivateRecordCipher(context, "wrong-domain").encrypt("{}".toByteArray()),
                    Base64.NO_WRAP,
                ),
            )
        for (variant in variants) {
            assertTrue(prefs.edit().putString("profile_v3", variant).commit())
            assertNull(family.profile)
            assertNotNull(family.readIssue)
            assertFalse(family.migrate())
            assertFalse(family.save(FamilyProfile("새별", null)))
            assertEquals(variant, prefs.getString("profile_v3", null))
        }
        assertTrue(prefs.edit().putString("profile_v3", original).commit())
        assertEquals("암호시험별", family.profile!!.first)
    }

    @Test
    fun missingKeystoreKeyPreservesCiphertextAndNeedsExplicitDeletionToStartAgain() =
        scoped { context ->
            val family = FamilyPreferences(context)
            assertTrue(family.save(FamilyProfile("암호시험별", null)))
            val original = context.preferences().getString("profile_v3", null)
            val keys = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            assertTrue(keys.containsAlias(context.keyAlias()))
            keys.deleteEntry(context.keyAlias())
            assertNull(family.profile)
            assertFalse(family.migrate())
            assertFalse(family.save(FamilyProfile("다른별", null)))
            assertEquals(original, context.preferences().getString("profile_v3", null))
            assertFalse(keys.containsAlias(context.keyAlias()))
            assertTrue(family.clear())
            assertTrue(family.save(FamilyProfile("새별", null)))
            assertEquals("새별", family.profile!!.first)
        }

    private inline fun scoped(block: (ScopedContext) -> Unit) {
        val context = ScopedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            block(context)
        } finally {
            context.clean()
        }
    }

    private class ScopedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "profile_crypto_" + UUID.randomUUID()
        private val directory = File(base.noBackupFilesDir, prefix).also { check(it.mkdirs()) }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = directory

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + "_" + name, mode)

        fun preferences(): SharedPreferences =
            getSharedPreferences("family_v1", Context.MODE_PRIVATE)

        fun preferenceFile(): File =
            File(baseContext.dataDir, "shared_prefs/${prefix}_family_v1.xml")

        fun keyAlias(): String =
            "onu.records.v1." +
                MessageDigest.getInstance("SHA-256")
                    .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                    .take(32)

        fun clean() {
            check(super.deleteSharedPreferences(prefix + "_family_v1"))
            KeyStore.getInstance("AndroidKeyStore").also {
                it.load(null)
                it.deleteEntry(keyAlias())
            }
            check(directory.deleteRecursively())
        }
    }
}
