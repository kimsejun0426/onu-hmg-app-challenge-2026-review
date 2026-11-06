package dev.carplaymate.demo

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FamilyInterestsTest {
    @Test fun migratingV03NamesCreatesOnlyCurrentIdentitiesAndNeverRewritesPastParticipants() = runBlocking {
        scoped { context, client, _ ->
            val past = finish(client, client.create(playersBody("별", "달")))
            val original = recordStoreCiphertextSnapshot(client)
            check(context.getSharedPreferences("family_v1", Context.MODE_PRIVATE).edit()
                .putString("first", "별").putString("second", "달").commit())
            val family = FamilyPreferences(context)
            assertTrue(requireNotNull(family.profile).children.isEmpty())
            assertTrue(family.migrate())
            val migrated = requireNotNull(family.profile)
            assertEquals(2, migrated.children.size)
            assertEquals(2, migrated.children.map { it.id }.toSet().size)
            assertTrue(migrated.children.all { it.interests.isEmpty() && !it.interestsConfigured })
            assertTrue(family.migrate())
            assertEquals(migrated, FamilyPreferences(context).profile)
            assertEquals(original, recordStoreCiphertextSnapshot(client))
            val journal = client.journal().single()
            assertEquals(listOf("별", "달"), journal.participants)
            assertTrue(journal.participantProfiles.all { it.profileId == null })
            rejected("PARTICIPANT_UNKNOWN") { client.setFeedback(past.sessionId, migrated.children[0].id, AdventureFeedback.LIKED) }
            assertEquals(original, recordStoreCiphertextSnapshot(client))
        }
    }

    @Test fun explicitInterestEditsKeepIdentityAcrossRenamesAndDoNotTransferToANewSecondChild() = runBlocking {
        scoped { context, _, _ ->
            val family = FamilyPreferences(context)
            assertTrue(family.save(FamilyProfile("별", "달")))
            val ids = requireNotNull(family.profile).children.map { it.id }
            assertTrue(family.saveInterests(setOf("space", "animals"), setOf("vehicles")))
            assertTrue(family.save(FamilyProfile("새별", "새달")))
            val renamed = requireNotNull(FamilyPreferences(context).profile)
            assertEquals(ids, renamed.children.map { it.id })
            assertEquals(setOf("space", "animals"), renamed.children[0].interests)
            assertEquals(setOf("vehicles"), renamed.children[1].interests)
            assertTrue(renamed.children.all { it.interestsConfigured })
            assertFalse(family.saveInterests(setOf("invented"), emptySet()))
            assertEquals(renamed, family.profile)
            assertTrue(family.save(FamilyProfile("새별", null)))
            assertTrue(family.save(FamilyProfile("새별", "다른아이")))
            val changed = requireNotNull(family.profile)
            assertEquals(ids[0], changed.children[0].id)
            assertNotEquals(ids[1], changed.children[1].id)
            assertTrue(changed.children[1].interests.isEmpty())
            assertFalse(changed.children[1].interestsConfigured)
            assertTrue(family.saveInterests(emptySet(), emptySet()))
            assertTrue(requireNotNull(family.profile).children.all { it.interestsConfigured && it.interests.isEmpty() })
            assertTrue(family.clear())
            assertNull(FamilyPreferences(context).profile)
        }
    }

    @Test fun completedParticipantFeedbackIsDurableDuplicateSafeEditableAndRemovable() = runBlocking {
        scoped { context, client, clock ->
            val family = FamilyPreferences(context)
            assertTrue(family.save(FamilyProfile("별", "달")))
            val children = requireNotNull(family.profile).children
            val identities = children.mapIndexed { index, child -> "p${index + 1}" to child.id }.toMap()
            val created = client.createFamilyAdventure(playersBody("별", "달"), "starlight-post-office", identities)
            rejected("FEEDBACK_NOT_READY") { client.setFeedback(created.sessionId, children[0].id, AdventureFeedback.LIKED) }
            assertTrue(family.save(FamilyProfile("새별", "새달")))
            val finished = finish(client, created)
            val journal = client.journal().single()
            assertEquals(listOf("별", "달"), journal.participants)
            assertEquals(children.map { it.id }, journal.participantProfiles.map { it.profileId })
            client.setFeedback(finished.sessionId, children[0].id, AdventureFeedback.LIKED)
            val first = client.feedback().single()
            clock.value += 500
            client.setFeedback(finished.sessionId, children[0].id, AdventureFeedback.LIKED)
            assertEquals(first, client.feedback().single())
            client.setFeedback(finished.sessionId, children[1].id, AdventureFeedback.TRY_ANOTHER_TOPIC)
            assertEquals(2, LocalGameClient(context).feedback().size)
            clock.value += 500
            client.setFeedback(finished.sessionId, children[0].id, AdventureFeedback.TRY_ANOTHER_TOPIC)
            val edited = client.feedback().first { it.childId == children[0].id }
            assertEquals(AdventureFeedback.TRY_ANOTHER_TOPIC, edited.feedback)
            assertTrue(edited.recordedAtEpochMillis > first.recordedAtEpochMillis)
            client.setFeedback(finished.sessionId, children[0].id, null)
            assertEquals(listOf(children[1].id), client.feedback().map { it.childId })
            val originalGame = canonicalJson(finished.game)
            assertEquals(originalGame, canonicalJson(client.snapshot(finished.sessionId).game))
            client.deleteAll()
            assertTrue(client.feedback().isEmpty())
            assertTrue(client.journal().isEmpty())
            assertNull(client.restore())
        }
    }

    @Test fun feedbackCannotInventAChildIdentityAndInvalidWritesLeaveTheRecordUntouched() = runBlocking {
        scoped { context, client, _ ->
            val id = UUID.randomUUID().toString()
            val finished = finish(client, client.createFamilyAdventure(playersBody("별", null), DEFAULT_ADVENTURE_ID, mapOf("p1" to id)))
            val original = recordStoreCiphertextSnapshot(client)
            rejected("PARTICIPANT_UNKNOWN") { client.setFeedback(finished.sessionId, UUID.randomUUID().toString(), AdventureFeedback.LIKED) }
            rejected("INVALID_REQUEST") { client.createFamilyAdventure(playersBody("별", "달"), DEFAULT_ADVENTURE_ID, mapOf("p1" to id)) }
            assertEquals(original, recordStoreCiphertextSnapshot(client))
            assertTrue(client.feedback().isEmpty())
        }
    }

    private class Clock(var value: Long = 1_800_000_000_000L)
    private suspend fun scoped(block: suspend (ScopedContext, LocalGameClient, Clock) -> Unit) {
        val context = ScopedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val clock = Clock()
        val client = LocalGameClient(context) { clock.value }
        try { block(context, client, clock) }
        finally { client.deleteAll(); assertTrue(FamilyPreferences(context).clear()) }
    }
    private class ScopedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "family_interests_${UUID.randomUUID()}"
        private val directory = File(base.noBackupFilesDir, prefix).also { check(it.mkdirs()) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("${prefix}_$name", mode)
    }
    private suspend fun finish(client: LocalGameClient, started: Snapshot): Snapshot {
        var current = started
        repeat(64) {
            if (current.status == "ENDED") return current
            current = Snapshot.from(client.command(current.sessionId, commandEnvelope(current, UUID.randomUUID().toString(),
                "playback.completed", JSONObject().put("playback_id", requireNotNull(current.speechPlan).playbackId))))
            if (current.status == "ENDED") return current
            val actor = current.game.getJSONArray("players").objects().single { it.getString("player_id") == current.game.getString("expected_player_id") }
            val choice = current.presentation.getJSONArray("actions").getJSONObject(0).getString("label")
            val payload = JSONObject().put("text", "${actor.getString("nickname")}, $choice")
                .put("is_final", true).put("capture_id", UUID.randomUUID().toString()).put("listen_window_id", current.listenWindowId)
                .put("locale", "ko-KR").put("input_source", "touch")
            current = Snapshot.from(client.command(current.sessionId, commandEnvelope(current, UUID.randomUUID().toString(), "action.submit", payload)))
        }
        error("Content did not end")
    }
    private suspend fun rejected(code: String, action: suspend () -> Any?) {
        try { action(); fail("Expected $code") } catch (error: ApiException) { assertEquals(code, error.code) }
    }
}
