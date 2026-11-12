package dev.carplaymate.demo

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AdventureLibraryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun allBundledAdventuresKeepActualChoicesDatesAndDuplicateSafeJournals() = runBlocking {
        withStore { client, file, clock ->
            val cards = client.adventures()
            assertTrue(ContentReleaseExpectations.hasCurrentCards(cards))
            assertEquals(InterestOptions.ids, cards.flatMap { it.interestTags }.toSet())
            for ((index, card) in cards.withIndex()) {
                clock.value += 1_000
                val createdAt = clock.value
                val started = client.createAdventure(playersBody("별", "달"), card.packId)
                assertEquals(card.title, started.presentation.getString("pack_title"))
                assertEquals(card.totalChoices, started.presentation.getInt("total_choices"))
                val manifest = JSONObject(context.assets.open("content/catalog.json").bufferedReader().use { it.readText() })
                val sourcePath = manifest.getJSONArray("packs").objects().single {
                    it.getString("pack_id") == card.packId
                }.getString("content_asset")
                val source = JSONObject(context.assets.open(sourcePath)
                    .bufferedReader(Charsets.UTF_8).use { it.readText() })
                assertEquals(source.getInt("hint_score_penalty"), card.hintScorePenalty)
                assertEquals(card.hintScorePenalty, started.presentation.getInt("hint_score_penalty"))
                val finished = finishChoices(client, started, last = index % 2 == 1)
                assertTrue(finished.game.getBoolean("finished"))
                assertEquals("ACTIVE", finished.status)
                assertEquals(index, client.journal().size) // An unheard ending is not yet completed.
                clock.value += 2_000
                val completedAt = clock.value
                val finalAck = playback(finished)
                val result = client.command(finished.sessionId, finalAck)
                clock.value += 5_000
                assertEquals(canonicalJson(result), canonicalJson(client.command(finished.sessionId, finalAck.copyJson())))
                val entry = client.journal().first { it.sessionId == finished.sessionId }
                assertEquals(createdAt, entry.createdAtEpochMillis)
                assertEquals(completedAt, entry.completedAtEpochMillis)
                assertEquals(card.title, entry.title)
                assertEquals(listOf("별", "달"), entry.participants)
                assertEquals(finished.game.getJSONArray("history").length(), entry.choicesCount)
                assertEquals(entry.choicesCount, entry.choices.size)
                assertEquals(finished.game.getString("ending"), entry.endingId)
                assertTrue(entry.endingTitle.isNotBlank())
                assertTrue(entry.summary.isNotBlank())
                val history = finished.game.getJSONArray("history").objects()
                entry.choices.forEachIndexed { step, choice ->
                    assertEquals(history[step].getString("action_id"), choice.actionId)
                    assertTrue(choice.actionLabel.isNotBlank())
                    assertEquals(if (step % 2 == 0) "별" else "달", choice.participant)
                }
            }
            assertEquals(cards.size, LocalGameClient(context, file.nameWithoutExtension).journal().size)
            assertEquals(cards.size, client.completedAdventures())
            assertFalse(readRecordStoreDocument(client).toString().contains("별, 물가"))
            assertTrue(client.savedAdventures().isEmpty())
        }
    }

    @Test fun switchingPacksRestoresEachOwnProgressAndDeletingRemovesEveryRecord() = runBlocking {
        withStore { client, _, clock ->
            val first = advanceOne(client, client.createAdventure(playersBody("별", null), DEFAULT_ADVENTURE_ID))
            client.restore(first.sessionId)
            clock.value += 1_000
            val second = advanceOne(client, client.createAdventure(playersBody("달", null), "starlight-post-office"))
            val pausedSecond = requireNotNull(client.restore(second.sessionId))
            val saved = client.savedAdventures()
            assertEquals(setOf(first.sessionId, second.sessionId), saved.map { it.sessionId }.toSet())
            val restored = requireNotNull(client.restore(first.sessionId))
            assertEquals(DEFAULT_ADVENTURE_ID, restored.game.getString("pack_id"))
            assertEquals("PAUSED", restored.status)
            assertNull(restored.speechPlan)
            assertNull(restored.listenWindowId)
            assertEquals(canonicalJson(first.game), canonicalJson(restored.game))
            assertEquals(canonicalJson(pausedSecond.game), canonicalJson(client.snapshot(second.sessionId).game))
            assertEquals(first.sessionId, requireNotNull(client.restore()).sessionId)
            client.deleteAll()
            assertNull(client.restore())
            assertTrue(client.journal().isEmpty())
            assertTrue(client.savedAdventures().isEmpty())
            assertTrue(ContentReleaseExpectations.hasCurrentCards(client.adventures()))
        }
    }

    @Test fun v02RecordsWithoutMetadataKeepTheirReceiptsAndNeverInventHistoricalDates() = runBlocking {
        withStore { client, file, clock ->
            val ending = finishChoices(client, client.create(playersBody("이전별", null)))
            val ack = playback(ending)
            val receipt = client.command(ending.sessionId, ack)
            // This is exactly the 0.2 format: version 1, state+receipts, no metadata/date fields.
            // State and receipts already pass the untouched Python/0.2 service fixtures separately.
            val oldStore = readRecordStoreDocument(client)
            assertEquals(1, oldStore.getInt("format_version"))
            oldStore.getJSONObject("sessions").getJSONObject(ending.sessionId).remove("metadata")
            val legacyFile = writeFreshLegacyStore(file, oldStore)
            val originalBytes = legacyFile.readBytes()
            clock.value += 100_000
            val reopened = LocalGameClient(context, legacyFile.nameWithoutExtension) { clock.value }
            val oldEntry = reopened.journal().single()
            assertNull(oldEntry.createdAtEpochMillis)
            assertNull(oldEntry.completedAtEpochMillis)
            assertEquals("공룡 연구소의 사라진 알", oldEntry.title)
            assertEquals(listOf("이전별"), oldEntry.participants)
            assertEquals(canonicalJson(receipt), canonicalJson(reopened.command(ending.sessionId, ack)))
            assertEquals("ENDED", requireNotNull(reopened.restore()).status)
            assertTrue("legacy inspection activates an encrypted fence", PrivateRecordCipher.hasEnvelope(legacyFile.readBytes()))
            assertArrayEquals("the preserved legacy archive keeps the original bytes", originalBytes, readPreservedLegacyPlaintext(legacyFile))
            assertEquals("read-only inspection must not fabricate metadata", canonicalJson(oldStore), canonicalJson(readRecordStoreDocument(reopened)))
            val fresh = reopened.createAdventure(playersBody("새별", null), "undersea-lighthouse")
            assertEquals("undersea-lighthouse", fresh.game.getString("pack_id"))
            assertNull(reopened.journal().single().completedAtEpochMillis)
            assertEquals(canonicalJson(receipt), canonicalJson(reopened.receipt(ending.sessionId, ack.getString("command_id"))))
        }
    }

    @Test fun v02ActiveProgressRestoresQuietlyAndKeepsItsUnheardOutputAndReceipt() = runBlocking {
        withStore { client, file, clock ->
            val initial = client.create(playersBody("이전별", null))
            val ack = playback(initial)
            val receipt = client.command(initial.sessionId, ack)
            val ready = Snapshot.from(receipt)
            val selected = ready.presentation.getJSONArray("actions").getJSONObject(0).getString("label")
            val payload = JSONObject().put("text", "이전별, $selected").put("is_final", true)
                .put("capture_id", uid()).put("listen_window_id", ready.listenWindowId)
                .put("locale", "ko-KR").put("input_source", "touch")
            val chosen = Snapshot.from(client.command(initial.sessionId,
                commandEnvelope(ready, uid(), "action.submit", payload)))
            val unheard = requireNotNull(chosen.speechPlan).segments.map { it.text }
            val oldStore = readRecordStoreDocument(client)
            oldStore.getJSONObject("sessions").getJSONObject(initial.sessionId).remove("metadata")
            val legacyFile = writeFreshLegacyStore(file, oldStore)
            clock.value += 100_000
            val reopened = LocalGameClient(context, legacyFile.nameWithoutExtension) { clock.value }
            val paused = requireNotNull(reopened.restore())
            assertEquals("PAUSED", paused.status)
            assertNull(paused.speechPlan)
            assertNull(paused.listenWindowId)
            assertEquals(canonicalJson(chosen.game), canonicalJson(paused.game))
            assertTrue(paused.turnEpoch > chosen.turnEpoch)
            assertEquals(canonicalJson(receipt), canonicalJson(reopened.command(initial.sessionId, ack)))
            val saved = reopened.savedAdventures().single()
            assertEquals(1, saved.choicesCount)
            assertNull(saved.updatedAtEpochMillis)
            val resumed = Snapshot.from(reopened.command(initial.sessionId,
                commandEnvelope(paused, uid(), "session.resume", JSONObject())))
            assertEquals(unheard, requireNotNull(resumed.speechPlan).segments.map { it.text })
            assertEquals(canonicalJson(chosen.game), canonicalJson(resumed.game))
            assertTrue(reopened.journal().isEmpty())
        }
    }

    @Test fun aLegacyCompletedSelectionDoesNotResurrectAnOlderUnfinishedAttempt() = runBlocking {
        withStore { client, file, clock ->
            val older = client.create(playersBody("이전별", null))
            val oldAck = playback(older)
            val oldReceipt = client.command(older.sessionId, oldAck)
            val newest = finishChoices(client, client.create(playersBody("최근별", null)))
            client.command(newest.sessionId, playback(newest))
            val legacy = readRecordStoreDocument(client)
            val sessions = legacy.getJSONObject("sessions")
            sessions.keyList().forEach { sessions.getJSONObject(it).remove("metadata") }
            val legacyFile = writeFreshLegacyStore(file, legacy)
            val before = legacyFile.readBytes()
            clock.value += 100_000
            val reopened = LocalGameClient(context, legacyFile.nameWithoutExtension) { clock.value }
            assertTrue(reopened.savedAdventures().isEmpty())
            assertEquals(listOf(newest.sessionId), reopened.journal().map { it.sessionId })
            assertEquals(canonicalJson(oldReceipt), canonicalJson(reopened.receipt(older.sessionId, oldAck.getString("command_id"))))
            assertTrue("legacy inspection activates an encrypted fence", PrivateRecordCipher.hasEnvelope(legacyFile.readBytes()))
            assertArrayEquals("catalog/journal reads must preserve the legacy archive", before, readPreservedLegacyPlaintext(legacyFile))
            assertEquals("catalog/journal reads must not rewrite legacy records", canonicalJson(legacy), canonicalJson(readRecordStoreDocument(reopened)))

            val other = reopened.createAdventure(playersBody("새별", null), "starlight-post-office")
            assertEquals(listOf(other.sessionId), reopened.savedAdventures().map { it.sessionId })
            val migrated = readRecordStoreDocument(reopened).getJSONObject("sessions")
            assertEquals(newest.sessionId, migrated.getJSONObject(older.sessionId)
                .getJSONObject("metadata").getString("superseded_by"))
            for (id in listOf(older.sessionId, newest.sessionId)) {
                assertEquals(canonicalJson(sessions.getJSONObject(id).getJSONObject("state")),
                    canonicalJson(migrated.getJSONObject(id).getJSONObject("state")))
                assertEquals(canonicalJson(sessions.getJSONObject(id).getJSONObject("receipts")),
                    canonicalJson(migrated.getJSONObject(id).getJSONObject("receipts")))
            }
            assertNull(reopened.journal().single().createdAtEpochMillis)
            assertNull(reopened.journal().single().completedAtEpochMillis)
            reject("SESSION_SUPERSEDED") { reopened.restore(older.sessionId) }
            assertEquals(canonicalJson(oldReceipt), canonicalJson(reopened.command(older.sessionId, oldAck)))
        }
    }

    @Test fun aFreshStartSupersedesOnlyItsPackAndOldProgressCannotReappearAfterCompletion() = runBlocking {
        withStore { client, _, clock ->
            val old = client.create(playersBody("별", null))
            val oldAck = playback(old)
            val oldReceipt = client.command(old.sessionId, oldAck)
            val other = client.createAdventure(playersBody("달", null), "starlight-post-office")
            clock.value += 1_000
            val fresh = client.create(playersBody("새별", null))
            assertEquals(setOf(fresh.sessionId, other.sessionId), client.savedAdventures().map { it.sessionId }.toSet())
            assertEquals(canonicalJson(oldReceipt), canonicalJson(client.command(old.sessionId, oldAck)))
            reject("SESSION_SUPERSEDED") { client.restore(old.sessionId) }
            val oldCurrent = client.snapshot(old.sessionId)
            reject("SESSION_SUPERSEDED") {
                client.command(old.sessionId, commandEnvelope(oldCurrent, uid(), "session.resume", JSONObject()))
            }
            val ending = finishChoices(client, fresh)
            client.command(ending.sessionId, playback(ending))
            assertEquals(listOf(other.sessionId), client.savedAdventures().map { it.sessionId })
            assertEquals(listOf(fresh.sessionId), client.journal().map { it.sessionId })
            assertEquals(canonicalJson(oldReceipt), canonicalJson(client.receipt(old.sessionId, oldAck.getString("command_id"))))
            assertEquals("starlight-post-office", requireNotNull(client.restore(other.sessionId)).game.getString("pack_id"))
        }
    }

    @Test fun currentShelfDoesNotDuplicateArchivedEditionsAndUnknownVersionsFailClosed() {
        val library = LocalAdventureLibrary(context)
        assertTrue(ContentReleaseExpectations.hasCurrentCards(library.cards()))
        val manifest = JSONObject(context.assets.open("content/catalog.json").bufferedReader().use { it.readText() })
        val legacy = manifest.getJSONArray("legacy_packs").objects().map {
            it.getString("pack_id") to it.getString("version")
        }
        assertEquals(ContentReleaseExpectations.legacyIdentities.size, legacy.size)
        assertEquals(ContentReleaseExpectations.legacyIdentities.toSet(), legacy.toSet())
        // Independent SHA-256 pins read from the released 0.10/0.11 APKs, including their CRLF
        // line endings. Check shipped bytes, not hashes re-derived from current assets.
        for ((identity, expectedHash) in ContentReleaseExpectations.newlyArchivedHashes) {
            val entry = manifest.getJSONArray("legacy_packs").objects().single {
                it.getString("pack_id") == identity.first && it.getString("version") == identity.second
            }
            val bytes = context.assets.open(entry.getString("content_asset")).use { it.readBytes() }
            val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals("$identity published source must remain byte-for-byte intact", expectedHash, actualHash)
        }
        for (card in library.cards()) {
            assertEquals(ContentReleaseExpectations.currentVersions.getValue(card.packId), card.version)
            assertEquals(0, card.hintScorePenalty)
            for ((packId, version) in ContentReleaseExpectations.legacyIdentities.filter { it.first == card.packId }) {
                assertEquals(version, library.card(packId, version).version)
            }
            try {
                library.card(card.packId, "99.0.0")
                fail("Unknown content cannot silently use current rules")
            } catch (error: ApiException) {
                assertEquals("CONTENT_VERSION_UNAVAILABLE", error.code)
            }
        }
        val picnicId = ContentReleaseExpectations.newPackId
        assertEquals(ContentReleaseExpectations.currentVersions.getValue(picnicId), library.card(picnicId).version)
        assertEquals("0.1.0", library.card(picnicId, "0.1.0").version)
        for (version in listOf("0.1.0", "0.2.0", "0.3.0", "0.4.0", "0.5.0", "0.6.0")) {
            val rules = library.rules(picnicId, version)
            val game = rules.newGame(playersBody("별", null).getJSONArray("players"))
            assertEquals(version, game.getString("pack_version"))
            assertEquals(4, library.card(picnicId, version).totalChoices)
        }
        for (version in listOf("0.0.0", "0.7.0", "0.8.0")) {
            try {
                library.rules(picnicId, version)
                fail("Picnic editions must not fall back to another content version")
            } catch (error: ApiException) {
                assertEquals("CONTENT_VERSION_UNAVAILABLE", error.code)
            }
        }
        assertEquals(1, library.card(DEFAULT_ADVENTURE_ID, "0.1.0").hintScorePenalty)
    }

    @Test fun archivedProgressResumesQuietlyWithOriginalRulesAndNewStartsUseCurrentEdition() = runBlocking {
        val library = LocalAdventureLibrary(context)
        for ((packId, version) in ContentReleaseExpectations.legacyIdentities) withStore { client, file, _ ->
            val card = library.card(packId)
            val initial = client.createAdventure(playersBody("별", "달"), card.packId)
            val oldRules = library.rules(card.packId, version)
            val oldStart = oldRules.newGame(playersBody("별", "달").getJSONArray("players"))
            val helped = oldRules.applyHint(oldStart)
            val firstAction = oldRules.present(helped).getJSONArray("actions").getJSONObject(0).getString("id")
            val oldGame = oldRules.applyAction(helped, helped.getString("expected_player_id"), firstAction)
            val legacyFile = seedArchivedState(client, file, initial.sessionId, oldGame, finished = false)
            val reopened = LocalGameClient(context, legacyFile.nameWithoutExtension)
            val restored = requireNotNull(reopened.restore())
            assertEquals("PAUSED", restored.status)
            assertNull(restored.speechPlan)
            assertNull(restored.listenWindowId)
            assertEquals(canonicalJson(oldGame), canonicalJson(restored.game))
            assertEquals(oldRules.present(oldGame).getString("prompt"), restored.presentation.getString("prompt"))
            val resumed = Snapshot.from(reopened.command(restored.sessionId,
                commandEnvelope(restored, uid(), "session.resume", JSONObject())))
            assertEquals(canonicalJson(oldGame), canonicalJson(resumed.game))
            assertTrue(requireNotNull(resumed.speechPlan).segments.any {
                it.text == oldRules.present(oldGame).getString("prompt")
            })
            val next = advanceOne(reopened, resumed)
            assertEquals(version, next.game.getString("pack_version"))
            assertEquals(2, next.game.getJSONArray("history").length())
            val fresh = reopened.createAdventure(playersBody("새별", null), card.packId)
            assertEquals(ContentReleaseExpectations.currentVersions.getValue(card.packId), fresh.game.getString("pack_version"))
            assertEquals(0, fresh.presentation.getInt("hint_score_penalty"))
        }
    }

    @Test fun archivedCompletedChoicesAndEndingAreReadWithTheirOriginalEdition() = runBlocking {
        val library = LocalAdventureLibrary(context)
        for ((packId, version) in ContentReleaseExpectations.legacyIdentities) withStore { client, file, _ ->
            val card = library.card(packId)
            val initial = client.createAdventure(playersBody("별", null), card.packId)
            val rules = library.rules(card.packId, version)
            var game = rules.newGame(playersBody("별", null).getJSONArray("players"))
            while (!game.getBoolean("finished")) {
                val action = rules.present(game).getJSONArray("actions").getJSONObject(0).getString("id")
                game = rules.applyAction(game, game.getString("expected_player_id"), action)
            }
            val legacyFile = seedArchivedState(client, file, initial.sessionId, game, finished = true)
            val reopened = LocalGameClient(context, legacyFile.nameWithoutExtension)
            val entry = reopened.journal().single()
            assertEquals(version, entry.packVersion)
            assertEquals(if (packId == ContentReleaseExpectations.newPackId) 4 else 6, entry.choices.size)
            assertEquals(library.details(game).getString("ending_summary"), entry.summary)
            assertEquals(canonicalJson(game), canonicalJson(requireNotNull(reopened.restore()).game))
            assertTrue(reopened.savedAdventures().isEmpty())
        }
    }

    @Test fun currentPicnicEditionResumesQuietlyAndKeepsItsFourActualChoices() = runBlocking {
        withStore { client, file, _ ->
            val initial = client.createAdventure(playersBody("별", "달"), ContentReleaseExpectations.newPackId)
            assertEquals(ContentReleaseExpectations.currentVersions.getValue(ContentReleaseExpectations.newPackId),
                initial.game.getString("pack_version"))
            assertEquals(4, initial.presentation.getInt("total_choices"))
            val first = advanceOne(client, initial)
            val paused = requireNotNull(client.restore(first.sessionId))
            val reopened = LocalGameClient(context, file.nameWithoutExtension)
            val restored = requireNotNull(reopened.restore(first.sessionId))
            assertEquals("PAUSED", restored.status)
            assertNull(restored.speechPlan)
            assertNull(restored.listenWindowId)
            assertEquals(canonicalJson(paused.game), canonicalJson(restored.game))
            assertEquals(ContentReleaseExpectations.newPackId, restored.game.getString("pack_id"))
            val resumed = Snapshot.from(reopened.command(restored.sessionId,
                commandEnvelope(restored, uid(), "session.resume", JSONObject())))
            assertEquals(canonicalJson(first.game), canonicalJson(resumed.game))
            val ending = finishChoices(reopened, resumed)
            val finalAck = playback(ending)
            val receipt = reopened.command(ending.sessionId, finalAck)
            assertEquals(canonicalJson(receipt), canonicalJson(reopened.command(ending.sessionId, finalAck.copyJson())))
            val entry = reopened.journal().single()
            assertEquals(ContentReleaseExpectations.newPackId, entry.packId)
            assertEquals(ContentReleaseExpectations.currentVersions.getValue(ContentReleaseExpectations.newPackId), entry.packVersion)
            assertEquals(4, entry.choices.size)
            assertEquals(ending.game.getJSONArray("history").objects().map { it.getString("action_id") },
                entry.choices.map { it.actionId })
            assertEquals(listOf("별", "달", "별", "달"), entry.choices.map { it.participant })
            assertTrue(reopened.savedAdventures().isEmpty())
        }
    }

    private suspend fun seedArchivedState(client: LocalGameClient, file: File, sessionId: String, game: JSONObject, finished: Boolean): File {
        // Synthetic old record, generated by the preserved original content; no invented receipts.
        val store = readRecordStoreDocument(client)
        val record = store.getJSONObject("sessions").getJSONObject(sessionId)
        record.getJSONObject("state").put("game", game)
            .put("status", if (finished) "ENDED" else "PAUSED")
            .put("phase", if (finished) "FEEDBACK" else "PROMPTING")
            .put("speech_plan", JSONObject.NULL).put("listen_window_id", JSONObject.NULL)
            .put("pause_reason", "USER").put("requires_explicit_resume", true)
            .remove("_unheard_texts")
        if (finished) record.getJSONObject("metadata").put("completed_at_ms", 1_800_000_005_000L)
        return writeFreshLegacyStore(file, store)
    }

    private class Clock(var value: Long = 1_800_000_000_000L)
    private suspend fun withStore(block: suspend (LocalGameClient, File, Clock) -> Unit) {
        val name = "library_${uid()}"
        val clock = Clock()
        val client = LocalGameClient(context, name) { clock.value }
        val file = File(context.noBackupFilesDir, "$name.json")
        try { block(client, file, clock) } finally {
            try { LocalGameClient(context, "${name}_legacy").deleteAll() }
            finally { client.deleteAll() }
        }
    }

    private suspend fun finishChoices(client: LocalGameClient, initial: Snapshot, last: Boolean = false): Snapshot {
        var current = initial
        var steps = 0
        while (!current.game.getBoolean("finished")) {
            current = advanceOne(client, current, last)
            assertTrue("content must terminate", ++steps <= 64)
        }
        return current
    }

    private suspend fun advanceOne(client: LocalGameClient, initial: Snapshot, last: Boolean = false): Snapshot {
        val ready = Snapshot.from(client.command(initial.sessionId, playback(initial)))
        val actions = ready.presentation.getJSONArray("actions").objects()
        val action = if (last) actions.last() else actions.first()
        val actor = ready.game.getJSONArray("players").objects().first {
            it.getString("player_id") == ready.game.getString("expected_player_id")
        }
        val payload = JSONObject().put("text", "${actor.getString("nickname")}, ${action.getString("label")}")
            .put("is_final", true).put("capture_id", uid()).put("listen_window_id", ready.listenWindowId)
            .put("locale", "ko-KR").put("input_source", "touch")
        return Snapshot.from(client.command(ready.sessionId, commandEnvelope(ready, uid(), "action.submit", payload)))
    }

    private fun playback(snapshot: Snapshot) = commandEnvelope(snapshot, uid(), "playback.completed",
        JSONObject().put("playback_id", requireNotNull(snapshot.speechPlan).playbackId))
    private fun writeFreshLegacyStore(sourceFile: File, value: JSONObject): File {
        val name = "${sourceFile.nameWithoutExtension}_legacy"
        val file = File(sourceFile.parentFile, "$name.json")
        check(sourceFile.parentFile!!.listFiles().orEmpty().none { it.name.startsWith("$name.") }) {
            "A migration fixture requires an unused namespace, never an activated v2 fence"
        }
        assertEquals(1, value.getInt("format_version"))
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(value.toString().toByteArray()); output.fd.sync(); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
        return file
    }
    private fun readPreservedLegacyPlaintext(file: File): ByteArray =
        PrivateRecordCipher(context, "local-game:${file.nameWithoutExtension}").decrypt(
            File(file.parentFile, "${file.nameWithoutExtension}.legacy-v1.enc").readBytes(),
        )
    private suspend fun reject(code: String, action: suspend () -> Any?) {
        try { action(); fail("Expected $code") } catch (error: ApiException) { assertEquals(code, error.code) }
    }
    private fun uid() = UUID.randomUUID().toString()
}

/** Independent release contract: current editions and every preserved published identity. */
internal object ContentReleaseExpectations {
    const val newPackId = "onu-little-picnic"
    private val publishedIds = listOf(DEFAULT_ADVENTURE_ID, "starlight-post-office", "undersea-lighthouse",
        "space-return-signal", "animal-footprint-garden", "little-car-workshop")
    val editedIds = publishedIds.filter { it != "little-car-workshop" }
    val currentVersions = mapOf(DEFAULT_ADVENTURE_ID to "0.12.0", "starlight-post-office" to "0.9.0",
        "undersea-lighthouse" to "0.9.0", "space-return-signal" to "0.11.0",
        "animal-footprint-garden" to "0.12.0", "little-car-workshop" to "0.11.0", newPackId to "0.6.0")
    val legacyIdentities = publishedIds.flatMap { id ->
        listOf("0.1.0", "0.2.0", "0.3.0", "0.4.0", "0.5.0").map { id to it }
    } + publishedIds.map { it to "0.6.0" } + (newPackId to "0.1.0") +
        (newPackId to "0.2.0") + ("space-return-signal" to "0.7.0") +
        ("animal-footprint-garden" to "0.7.0") + ("little-car-workshop" to "0.7.0") +
        ("animal-footprint-garden" to "0.8.0") + ("little-car-workshop" to "0.8.0") +
        ("space-return-signal" to "0.8.0") + ("animal-footprint-garden" to "0.9.0") +
        (newPackId to "0.3.0") + ("undersea-lighthouse" to "0.7.0") +
        (DEFAULT_ADVENTURE_ID to "0.7.0") +
        ("starlight-post-office" to "0.7.0") +
        ("undersea-lighthouse" to "0.8.0") +
        ("space-return-signal" to "0.9.0") +
        ("animal-footprint-garden" to "0.10.0") +
        ("little-car-workshop" to "0.9.0") +
        ("onu-little-picnic" to "0.4.0") +
        (DEFAULT_ADVENTURE_ID to "0.8.0") + ("space-return-signal" to "0.10.0") +
        ("animal-footprint-garden" to "0.11.0") + ("little-car-workshop" to "0.10.0") + (DEFAULT_ADVENTURE_ID to "0.9.0") +
        ("starlight-post-office" to "0.8.0") + (newPackId to "0.5.0") + (DEFAULT_ADVENTURE_ID to "0.10.0") + (DEFAULT_ADVENTURE_ID to "0.11.0")
    val newlyArchivedHashes = mapOf(
        (DEFAULT_ADVENTURE_ID to "0.11.0") to "3a450fa04e93afa05ce4f6762c37eba7691ab14c8e9e4dc4155d6b3df1fdb11b",
        (DEFAULT_ADVENTURE_ID to "0.10.0") to "c00aa0e09caa0dcea9b3945ee4fffaeee2a8a791daaf7c801c75d34d8ff6c0e0",
        ("starlight-post-office" to "0.8.0") to "570ea85cc89c52c9b552e3d633c16c3fee532945a41e9b314882bc64829cd13a",
        ("onu-little-picnic" to "0.5.0") to "2d8ff28298be3b433589e96a8ecd1e93b8e5598d79639e2eccf3ed10d13c34cc",
        (DEFAULT_ADVENTURE_ID to "0.9.0") to "fee86690b26f46da2d3619c5c43314d6dad369299b47be0020c03002a2c7ca97",
        (DEFAULT_ADVENTURE_ID to "0.8.0") to "b72c84b453a71733251233a879a453b2cae8ba9a20e5f06c0e3fe320b598699d",
        ("space-return-signal" to "0.10.0") to "d6cd68056aa1ee661c0681bc8d67e5d7c5d735dd40a768fe7db05352694e7e09",
        ("animal-footprint-garden" to "0.11.0") to "5e3a173325c79539c1241fd0e3beefee1f03ce77c11636dd52aa85282d33ddb5",
        ("little-car-workshop" to "0.10.0") to "ac9d86ebe0dc6774629926e7c7c1db956b742400ef8d53de74bb662fc97c9eef",
        ("starlight-post-office" to "0.7.0") to "25ce6be112f7722daabf510faa3d1e327574e7a2840a8925d0ee4f2e413a5885",
        ("undersea-lighthouse" to "0.8.0") to "9c796a798f466c89da6469345d9a7337c76c729389d77f9f7e8b9638f7a566e5",
        ("space-return-signal" to "0.9.0") to "280ae677236a659493250ec3558603ba099f00d1675039d2322078a7d924be1f",
        ("animal-footprint-garden" to "0.10.0") to "902b4b937f3df023f0be9d5f53bcac622219cbc49bac6be2276a32b037b05146",
        ("little-car-workshop" to "0.9.0") to "41e9d500c84f71317140109eda5a6e4ef38b920071021e8992b6cfdd2c8f4dae",
        ("onu-little-picnic" to "0.4.0") to "479eb4ce3aa109fc6d66209226151cd1d3f6fe03ea6e802fb34513cbdd63c116",
        (DEFAULT_ADVENTURE_ID to "0.7.0") to "2b482c1854918fe2d11f934ae106356ccd0e6a6baef78400a5b4b6285f41cc0c",
        ("animal-footprint-garden" to "0.9.0") to "f0df4e5675c00d6fc16e3e7862c4fe405e5089a0c73b1f72d9b1048db564a032",
        (newPackId to "0.3.0") to "ac19c817166e409aea23a89f40cec6cd34e6bb1577492c673037534785433fe3",
        ("undersea-lighthouse" to "0.7.0") to "e2bb8a5e545290c61ddbc90a34220a5e4e67b8f1e9c9507533427c09e0ed4ac2",
        ("animal-footprint-garden" to "0.8.0") to "c1c09e0ed505ee3135e7555271548c3b7ac4235bec7b01cfefc5b5605e890705",
        ("little-car-workshop" to "0.8.0") to "f14aa5ba5910a4e943d7e58526a0a4ce8407bde4f10c2aa0b12e0dc7eb5d2f2b",
        ("space-return-signal" to "0.8.0") to "a463aa211b5a7225788de6ce9a164608a057be01a674b8a64de73ffc556ad1fc",
        ("animal-footprint-garden" to "0.7.0") to "aa2c481fab05ef14b8eea79da071b036a83a508c341213689ece33c6ed9827fa",
        ("little-car-workshop" to "0.7.0") to "a4338e44267118017a7ac0154c3b42ed9a9dd1ded1b0b40fc1d3f9f77332da94",
        ("little-car-workshop" to "0.6.0") to "1f8edee60150e7df59cff77cd2af29e5c9cb5539122f30b48f562d337e1e2f4e",
        (newPackId to "0.2.0") to "e2e27ec4787a7cf4beafca9bc2a1d2ef54317534a822fc83b207c9a205372a18",
        ("space-return-signal" to "0.7.0") to "3b9aed0562e7b2660cef06cc224c9007cdeabe61dc7ff786ffd43b1e95cc7e50",
        (DEFAULT_ADVENTURE_ID to "0.6.0") to "849b2c5ae52c5ad11d6687362512646981769f582e07db441685361d9c87ee52",
        ("starlight-post-office" to "0.6.0") to "4106191e1284224e0d699c13fa9278fcbb4ccbbb6e0991e0c100b3ba8ac47c28",
        ("undersea-lighthouse" to "0.6.0") to "5422ec0dd01c5d550fa727e5143b6a6683fa40746af2a2c65cff921afd35bdd6",
        ("space-return-signal" to "0.6.0") to "59135de21436614c9d512f5cee406cf6c49e9910b030d9753a3fe0194bbbcaf1",
        ("animal-footprint-garden" to "0.6.0") to "332694325b271cc13bdceaad207c6f734fcd93ed568f912a4f43d4c7fb93d463",
        (DEFAULT_ADVENTURE_ID to "0.5.0") to "ecb10c249516a170be805b79e9ca1e42d8be7f515775ae1d3d3d8a9579c048e0",
        ("starlight-post-office" to "0.5.0") to "4aac63b206ccd59ba0420b9b9244a359fd1dad5671b24e1b1ca2713d8df87155",
        ("undersea-lighthouse" to "0.5.0") to "f19ff3a15aae230908da3a5eac954608bdd0d95b46be7dfc5dbbeae8adf0816a",
        ("space-return-signal" to "0.5.0") to "1c83b29d07e5381b9e12abc74e34044274613f48f2b0e3cce57c5d9303b0c6fb",
        ("animal-footprint-garden" to "0.5.0") to "2acc7ce878c6e134c88595d1bc34434c8cd307ee4d772090788107602a696935",
        ("little-car-workshop" to "0.5.0") to "6816ad401be1be45f57dc7ea263983503ac2b4c3dc07b02e0e509840f400f755",
        (newPackId to "0.1.0") to "9d8a73898d165696f9e474119da35a59d306f2cad1f9e2cfe694a5b8e92ccd23",
    )
    fun hasCurrentCards(cards: List<AdventureCard>) = cards.size == currentVersions.size &&
        cards.associate { it.packId to it.version } == currentVersions
    fun hasAllRecommendations(items: List<AdventureRecommendation>) = items.size == currentVersions.size &&
        items.map { it.packId }.toSet() == currentVersions.keys
}
