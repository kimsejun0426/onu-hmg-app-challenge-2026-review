package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageResilienceTest {
    @Test
    fun damagedProfileCannotBecomeALegacyMigrationOrOverwriteAnIdentity() {
        scoped { app ->
            val prefs = app.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
            val family = FamilyPreferences(app)
            assertTrue(family.save(FamilyProfile("별", "달")))
            assertTrue(family.saveInterests(setOf("space"), setOf("animals")))
            val original = makeLegacyProfile(app, prefs)
            assertTrue(prefs.edit().putString("profile_v2", original.dropLast(3)).commit())
            val damaged = prefs.all.toMap()
            assertNull(family.profile)
            assertEquals(FamilyReadIssue.DAMAGED, family.readIssue)
            assertFalse(family.migrate())
            assertFalse(family.save(FamilyProfile("새별", null)))
            assertFalse(family.saveInterests(emptySet(), emptySet()))
            assertEquals(damaged, prefs.all)
            // Restoring the intact bytes recovers the same identity, never a regenerated one.
            assertTrue(prefs.edit().putString("profile_v2", original).commit())
            assertNull(family.readIssue)
            assertEquals(JSONObject(original).getString("first_id"), family.profile!!.firstId)
        }
    }

    @Test
    fun unknownSchemaAndInvalidTypesArePreservedUntilExplicitDeletion() {
        scoped { app ->
            val prefs = app.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
            val family = FamilyPreferences(app)
            assertTrue(family.save(FamilyProfile("별", "달")))
            val good = makeLegacyProfile(app, prefs)
            val variants =
                listOf(
                    JSONObject(good).put("schema_version", 3),
                    JSONObject(good).put("first_configured", "true"),
                    JSONObject(good).put("second_id", JSONObject(good).getString("first_id")),
                    JSONObject(good).put("first_interests", org.json.JSONArray().put(17)),
                    JSONObject(good).put("first_id", ""),
                    JSONObject(good).put("future_field", "preserve"),
                )
            for (variant in variants) {
                assertTrue(prefs.edit().putString("profile_v2", variant.toString()).commit())
                val before = prefs.all.toMap()
                assertNull(family.profile)
                assertNotNull(family.readIssue)
                assertFalse(family.migrate())
                assertFalse(family.save(FamilyProfile("새가족", null)))
                assertEquals(before, prefs.all)
            }
            assertTrue(prefs.edit().putInt("first", 7).commit())
            assertNull(family.profile)
            assertEquals(FamilyReadIssue.DAMAGED, family.readIssue)
            assertTrue(family.clear())
            assertNull(family.readIssue)
            assertTrue(family.save(FamilyProfile("새별", null)))
            assertNotEquals(JSONObject(good).getString("first_id"), family.profile!!.firstId)
        }
    }

    @Test
    fun invalidCheckpointReadsFailClosedAndPendingWritesPreserveOriginal() {
        scoped { app ->
            val prefs = app.getSharedPreferences("recovery_v1", Context.MODE_PRIVATE)
            val checkpoint = LocalCheckpoint(app)
            assertTrue(
                prefs
                    .edit()
                    .putInt("session_id", 42)
                    .putString("pending_command_ids", "wrong type")
                    .commit()
            )
            rejected("LOCAL_CHECKPOINT_UNAVAILABLE") { checkpoint.sessionId }
            rejected("LOCAL_CHECKPOINT_UNAVAILABLE") { checkpoint.pendingCommandIds }
            assertFalse(checkpoint.addPending("new-command"))
            assertEquals("wrong type", prefs.getString("pending_command_ids", null))
            assertTrue(checkpoint.quiet())
            assertTrue(checkpoint.clear())
            assertNull(checkpoint.sessionId)
            assertTrue(checkpoint.pendingCommandIds.isEmpty())
        }
    }

    @Test
    fun structurallyDamagedGameAndFutureFormatCannotBeReplacedByANewAdventure() = runBlocking {
        val app = ScopedApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val seed = LocalGameClient(app, "resilience_seed")
            val created = seed.create(playersBody("별", null))
            val good = readRecordStoreDocument(seed).toString()
            val name = "resilience_legacy"
            val client = LocalGameClient(app, name)
            // A never-opened v1 namespace: overwriting an active v2 fence would not test migration.
            val file = File(app.noBackupFilesDir, "$name.json")
            val damaged = JSONObject(good)
            damaged
                .getJSONObject("sessions")
                .getJSONObject(created.sessionId)
                .put("state", "broken")
            file.writeText(damaged.toString())
            val bytes = file.readBytes()
            suspendRejected("LOCAL_STORAGE_UNAVAILABLE") { client.restore() }
            suspendRejected("LOCAL_STORAGE_UNAVAILABLE") { client.create(playersBody("새별", null)) }
            assertArrayEquals(bytes, file.readBytes())
            file.writeText(JSONObject(good).put("format_version", 99).toString())
            val future = file.readBytes()
            suspendRejected("LOCAL_STORAGE_VERSION_UNAVAILABLE") { client.restore() }
            suspendRejected("LOCAL_STORAGE_VERSION_UNAVAILABLE") {
                client.create(playersBody("새별", null))
            }
            assertArrayEquals(future, file.readBytes())
            client.deleteAll()
            assertNull(client.restore())
        } finally {
            app.clean()
        }
    }

    @Test
    fun damagedProfileStartupIsQuietAndRetryRestoresTheOriginalFamily() = runBlocking {
        val app = ScopedApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val store = ViewModelStore()
        val output = SilentOutput()
        try {
            val family = FamilyPreferences(app)
            assertTrue(family.save(FamilyProfile("별", null)))
            assertTrue(family.saveInterests(setOf("space"), emptySet()))
            val prefs = app.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
            val original = makeLegacyProfile(app, prefs)
            assertTrue(prefs.edit().putString("profile_v2", "{broken").commit())
            val model = main {
                ViewModelProvider(
                    store,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            DeveloperViewModel(app, null, outputFactory = { output }) as T
                    },
                )[DeveloperViewModel::class.java]
            }
            withTimeout(15_000) { model.ui.first { !it.busy && it.localStorageProblem } }
            assertTrue(model.ui.value.quiet)
            assertFalse(model.ui.value.onboarded)
            main {
                assertFalse(model.saveFamily("덮어쓰기", null))
                model.useSimulation(true)
                model.startFamilyAdventure()
                model.resume()
            }
            assertNull(model.ui.value.snapshot)
            assertEquals("{broken", prefs.getString("profile_v2", null))
            assertEquals(0, output.speaks)
            assertTrue(prefs.edit().putString("profile_v2", original).commit())
            main { model.retryLocalStorage() }
            withTimeout(15_000) {
                model.ui.first { !it.busy && !it.localStorageProblem && it.onboarded }
            }
            assertEquals(
                JSONObject(original).getString("first_id"),
                model.ui.value.children.single().id,
            )
            assertEquals(setOf("space"), model.ui.value.children.single().interests)
            assertTrue(model.ui.value.quiet)
            assertEquals(0, output.speaks)
        } finally {
            main { store.clear() }
            app.clean()
        }
    }

    @Test
    fun checkpointCorruptionAfterStartupCannotCrashBackgroundOrResume() = runBlocking {
        val app = ScopedApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val store = ViewModelStore()
        val output = SilentOutput()
        try {
            assertTrue(FamilyPreferences(app).save(FamilyProfile("별", null)))
            val model = main {
                ViewModelProvider(
                    store,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            DeveloperViewModel(app, null, outputFactory = { output }) as T
                    },
                )[DeveloperViewModel::class.java]
            }
            withTimeout(15_000) { model.ui.first { !it.busy && it.onboarded } }
            val checkpoint = app.getSharedPreferences("recovery_local_v1", Context.MODE_PRIVATE)
            assertTrue(checkpoint.edit().putInt("session_id", 42).commit())
            main {
                model.background()
                model.foreground(app)
                model.resume()
            }
            assertTrue(model.ui.value.localStorageProblem)
            assertTrue(model.ui.value.quiet)
            assertEquals(42, checkpoint.getInt("session_id", 0))
            assertEquals(0, output.speaks)
        } finally {
            main { store.clear() }
            app.clean()
        }
    }

    @Test
    fun invalidOptionalMetadataCannotDisappearDuringStorageMigration() = runBlocking {
        val app = ScopedApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val seed = LocalGameClient(app, "metadata_seed")
            val created = seed.create(playersBody("별", null))
            val good = readRecordStoreDocument(seed).toString()
            val name = "metadata_legacy"
            val client = LocalGameClient(app, name)
            val file = File(app.noBackupFilesDir, "$name.json")
            for (key in listOf("feedback", "participant_profile_ids")) {
                val invalid = JSONObject(good)
                invalid
                    .getJSONObject("sessions")
                    .getJSONObject(created.sessionId)
                    .getJSONObject("metadata")
                    .put(key, "wrong type")
                file.writeText(invalid.toString())
                val before = file.readBytes()
                suspendRejected("LOCAL_STORAGE_UNAVAILABLE") { client.restore() }
                suspendRejected("LOCAL_STORAGE_UNAVAILABLE") {
                    client.create(playersBody("새별", null))
                }
                assertArrayEquals(before, file.readBytes())
            }
        } finally {
            app.clean()
        }
    }

    private fun makeLegacyProfile(app: Context, prefs: SharedPreferences): String {
        val document =
            JSONObject(
                String(
                    PrivateRecordCipher(app, "family_profile_v3")
                        .decrypt(
                            android.util.Base64.decode(
                                prefs.getString("profile_v3", null)!!,
                                android.util.Base64.NO_WRAP,
                            )
                        ),
                    Charsets.UTF_8,
                )
            )
        val profile = document.getJSONObject("profile").toString()
        assertTrue(
            prefs
                .edit()
                .remove("profile_v3")
                .putString("first", document.getString("first"))
                .putString(
                    "second",
                    if (document.isNull("second")) null else document.getString("second"),
                )
                .putString("profile_v2", profile)
                .commit()
        )
        return profile
    }

    private class SilentOutput : SpeechOutput {
        override val status = MutableStateFlow("시험 음성")
        override val readyState = MutableStateFlow(false)
        var speaks = 0

        override fun refresh() {}

        override fun stop() {}

        override fun shutdown() {}

        override fun speak(
            plan: SpeechPlan,
            generation: Long,
            complete: () -> Unit,
            failure: (SpeechOutputFailure) -> Unit,
            started: () -> Unit,
        ) {
            speaks += 1
            error("Unexpected playback")
        }
    }

    private inline fun scoped(block: (ScopedApplication) -> Unit) {
        val app = ScopedApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            block(app)
        } finally {
            app.clean()
        }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "storage_" + UUID.randomUUID()
        private val directory = File(context.noBackupFilesDir, prefix).also { check(it.mkdirs()) }
        private val preferences = mutableSetOf<String>()

        init {
            attachBaseContext(context)
        }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = directory

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = prefix + "_" + name
            synchronized(preferences) { preferences.add(scoped) }
            return super.getSharedPreferences(scoped, mode)
        }

        fun clean() {
            synchronized(preferences) {
                preferences.forEach { check(super.deleteSharedPreferences(it)) }
            }
            check(directory.deleteRecursively())
        }
    }

    private fun rejected(code: String, block: () -> Any?) {
        try {
            block()
            fail("Expected $code")
        } catch (error: ApiException) {
            assertEquals(code, error.code)
        }
    }

    private suspend fun suspendRejected(code: String, block: suspend () -> Any?) {
        try {
            block()
            fail("Expected $code")
        } catch (error: ApiException) {
            assertEquals(code, error.code)
        }
    }

    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable { action() })
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        return task.get()
    }
}
