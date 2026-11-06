package dev.carplaymate.demo

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

/** Actual ViewModel/coroutines/storage. Latches control request order; no network or timing sleeps. */
@RunWith(AndroidJUnit4::class)
class ViewModelRecoveryTest {
    @Test fun upgradingAnExistingControlLikeNicknameCanEditItsSiblingAndStartANewAdventure() = runBlocking {
        withHarness(prepare = { application ->
            check(application.getSharedPreferences("family_v1", Context.MODE_PRIVATE).edit()
                .putString("first", "몰라").putString("second", "달").commit())
        }) { game ->
            game.awaitUi { !it.busy && it.onboarded }
            game.onModel { assertTrue(saveFamily("몰라", "새달")) }
            game.onModel { useSimulation(true); startFamilyAdventure() }
            val playing = game.awaitUi { !it.busy && it.snapshot?.speechPlan != null }
            assertEquals(ContentReleaseExpectations.currentVersions.getValue(DEFAULT_ADVENTURE_ID), playing.snapshot!!.game.getString("pack_version"))
            assertEquals(listOf("몰라", "새달"), playing.snapshot.game.getJSONArray("players").objects()
                .map { it.getString("nickname") })
            assertFalse(playing.failure)
        }
    }

    @Test fun explicitPauseFencesAResumeThatHasNotCommittedYet() = runBlocking {
        lateinit var delayed: DelayedResumeClient
        withHarness({ application ->
            DelayedResumeClient(LocalGameClient(application)).also { delayed = it }
        }) { game ->
            game.startAndPause()
            val before = requireNotNull(game.model.ui.value.snapshot)
            game.onModel { resume() }
            val held = withTimeout(12_000) { delayed.resumeArrived.await() }
            val baseline = held.snapshot
            // Resume first commits its own pause fence. Bind the held request to that actual
            // durable state, rather than assuming the pre-resume UI snapshot is still current.
            assertEquals(before.sessionId, baseline.sessionId)
            assertEquals("PAUSED", baseline.status)
            assertTrue("resume preparation must fence older work", baseline.version > before.version)
            assertTrue("resume preparation must advance the epoch", baseline.turnEpoch > before.turnEpoch)
            assertEquals(baseline.version, held.body.getLong("expected_version"))
            assertEquals(baseline.turnId, held.body.getString("turn_id"))
            assertEquals(baseline.turnEpoch, held.body.getLong("turn_epoch"))

            // Stop completes while the original resume is still held before its durable commit.
            game.onModel { pause("QUIET_REQUEST") }
            val paused = requireNotNull(game.awaitUi { !it.busy && it.quiet && it.snapshot?.status == "PAUSED" }.snapshot)
            assertTrue("the new stop must invalidate the held request", paused.version > baseline.version)
            assertTrue("the new stop must fence the prepared resume epoch", paused.turnEpoch > baseline.turnEpoch)
            delayed.releaseResume.complete(Unit)
            assertEquals("STALE_VERSION", withTimeout(12_000) { delayed.resumeOutcome.await() })
            game.onModel { /* Drain the main continuation after the rejected late response. */ }

            val persisted = game.backend.snapshot(before.sessionId)
            assertEquals("PAUSED", persisted.status)
            assertTrue("an explicit stop fences even an already-paused snapshot", persisted.turnEpoch > baseline.turnEpoch)
            assertEquals(paused.turnEpoch, persisted.turnEpoch)
            assertEquals(paused.version, persisted.version)
            assertEquals(0, persisted.game.getJSONArray("history").length())
            val ui = game.model.ui.value
            assertTrue(ui.quiet)
            assertFalse(ui.busy)
            assertFalse(ui.microphoneListening)
            assertNull(ui.snapshot?.speechPlan)
            assertEquals(persisted.version, ui.snapshot?.version)
        }
    }

    @Test fun invalidRemoteConfigurationKeepsTheWorkingLocalGameAndCheckpoint() = runBlocking {
        withHarness { game ->
            game.startAndPause()
            val before = requireNotNull(game.model.ui.value.snapshot)
            game.onModel { configure("http://example.invalid", "") }
            val failedConfiguration = game.model.ui.value
            assertTrue(failedConfiguration.failure)
            assertTrue(failedConfiguration.connected)
            assertTrue(failedConfiguration.useLocal)
            assertEquals(before.sessionId, failedConfiguration.snapshot?.sessionId)
            assertEquals(before.sessionId, LocalCheckpoint(game.application, "recovery_local_v1").sessionId)
            assertNull(LocalCheckpoint(game.application, "recovery_v1").sessionId)

            // Preservation means more than retaining a label: the same local game must resume.
            game.onModel { resume() }
            game.awaitUi { !it.busy && !it.quiet && it.snapshot?.status == "ACTIVE" && it.snapshot.speechPlan != null }
            game.onModel { simulatePlaybackCompletion() }
            game.awaitUi { !it.busy && !it.quiet && it.snapshot?.phase == "LISTENING" }
            game.onModel { chooseAction("물가") }
            val continued = requireNotNull(game.awaitUi {
                !it.busy && !it.quiet && it.snapshot?.game?.optInt("turn_index") == 1
            }.snapshot)
            assertEquals(before.sessionId, continued.sessionId)
            assertTrue(game.model.ui.value.useLocal)
            assertEquals(1, game.backend.snapshot(before.sessionId).game.getJSONArray("history").length())
            assertNull(LocalCheckpoint(game.application, "recovery_v1").sessionId)
        }
    }

    @Test fun choosingAnotherAdventureAndReturningResumesItsOriginalPackAndChoices() = runBlocking {
        withHarness { game ->
            game.awaitUi { !it.busy && ContentReleaseExpectations.hasCurrentCards(it.adventures) }
            game.onModel { saveFamily("별", "달"); useSimulation(true); startFamilyAdventure("starlight-post-office") }
            game.awaitUi { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            game.onModel { simulatePlaybackCompletion() }
            val ready = requireNotNull(game.awaitUi { !it.busy && it.snapshot?.phase == "LISTENING" }.snapshot)
            val firstLabel = ready.presentation.getJSONArray("actions").getJSONObject(0).getString("label")
            game.onModel { chooseAction(firstLabel) }
            val first = requireNotNull(game.awaitUi { !it.busy && it.snapshot?.game?.optInt("turn_index") == 1 }.snapshot)
            game.onModel { pause() }
            game.awaitUi { !it.busy && it.quiet && it.savedAdventures.any { item -> item.sessionId == first.sessionId } }

            game.onModel { startFamilyAdventure("undersea-lighthouse") }
            val second = requireNotNull(game.awaitUi {
                !it.busy && !it.quiet && it.snapshot?.game?.optString("pack_id") == "undersea-lighthouse"
            }.snapshot)
            game.onModel { pause() }
            game.awaitUi { !it.busy && it.quiet && it.savedAdventures.size == 2 }
            game.onModel { selectAdventure(DEFAULT_ADVENTURE_ID); resumeAdventure(first.sessionId) }
            val resumed = requireNotNull(game.awaitUi {
                !it.busy && !it.quiet && it.snapshot?.sessionId == first.sessionId && it.snapshot.status == "ACTIVE"
            }.snapshot)
            assertEquals("starlight-post-office", resumed.game.getString("pack_id"))
            assertEquals("starlight-post-office", game.model.ui.value.selectedPackId)
            assertEquals("별빛 우체국", resumed.presentation.getString("pack_title"))
            assertEquals(canonicalJson(first.game), canonicalJson(resumed.game))
            assertEquals(first.sessionId, LocalCheckpoint(game.application, "recovery_local_v1").sessionId)
            assertEquals("PAUSED", game.backend.snapshot(second.sessionId).status)
            assertFalse(game.model.ui.value.microphoneListening)
            assertNull(LocalCheckpoint(game.application, "recovery_v1").sessionId)
        }
    }

    @Test fun editingNamesAndInterestsDuringAdventureKeepsItsOriginalParticipants() = runBlocking {
        withHarness { game ->
            game.awaitUi { !it.busy && ContentReleaseExpectations.hasCurrentCards(it.adventures) }
            game.onModel {
                assertTrue(saveFamily("별", "달"))
                assertTrue(saveInterests(setOf("dinosaurs"), setOf("ocean")))
                useSimulation(true)
                startFamilyAdventure(DEFAULT_ADVENTURE_ID)
            }
            val original = game.awaitUi { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            val identities = original.children.map { it.id }
            game.onModel {
                assertTrue(saveFamily("새별", "새달"))
                assertTrue(saveInterests(setOf("space"), setOf("vehicles")))
            }
            assertEquals(identities, game.model.ui.value.children.map { it.id })
            assertEquals(original.snapshot, game.model.ui.value.snapshot)
            assertEquals(setOf("space"), game.model.ui.value.children[0].interests)
            var current = requireNotNull(game.model.ui.value.snapshot)
            repeat(64) {
                if (current.status != "ENDED") {
                    game.onModel { simulatePlaybackCompletion() }
                    current = requireNotNull(game.awaitUi { !it.busy &&
                        (it.snapshot?.status == "ENDED" || it.snapshot?.phase == "LISTENING") }.snapshot)
                    if (current.status != "ENDED") {
                        val label = current.presentation.getJSONArray("actions").getJSONObject(0).getString("label")
                        val before = current.version
                        game.onModel { chooseAction(label) }
                        current = requireNotNull(game.awaitUi { !it.busy && (it.snapshot?.version ?: 0) > before }.snapshot)
                    }
                }
            }
            assertEquals("ENDED", current.status)
            val journal = game.awaitUi { it.journal.any { entry -> entry.sessionId == current.sessionId } }
                .journal.single { it.sessionId == current.sessionId }
            assertEquals(listOf("별", "달"), journal.participants)
            assertEquals(identities, journal.participantProfiles.map { it.profileId })
            game.onModel { startFamilyAdventure("space-return-signal") }
            val next = requireNotNull(game.awaitUi { !it.busy && !it.quiet && it.snapshot?.sessionId != current.sessionId }.snapshot)
            assertEquals(listOf("새별", "새달"), next.game.getJSONArray("players").objects().map { it.getString("nickname") })
            game.onModel { pause() }
            game.awaitUi { !it.busy && it.quiet }
            game.onModel { forgetFamily() }
            val cleared = game.awaitUi { !it.busy && !it.onboarded }
            assertTrue(cleared.children.isEmpty())
            assertTrue(cleared.adventureFeedback.isEmpty())
            assertTrue(game.backend.feedback().isEmpty())
            assertNull(FamilyPreferences(game.application).profile)
        }
    }

    @Test fun addingAChildAndSkippingSetupKeepsTheExistingChildsExplicitInterests() = runBlocking {
        withHarness { game ->
            game.awaitUi { !it.busy }
            game.onModel {
                assertTrue(saveFamily("별", null))
                assertTrue(saveInterests(setOf("space", "dinosaurs")))
            }
            val existing = game.model.ui.value.children.single()
            game.onModel { assertTrue(saveFamily("별", "달")) }
            assertTrue(game.model.ui.value.interestSetupNeeded)
            game.onModel { assertTrue(skipInterests()) }
            val children = game.model.ui.value.children
            assertEquals(existing, children[0])
            assertTrue(children[1].interestsConfigured)
            assertTrue(children[1].interests.isEmpty())
            assertFalse(game.model.ui.value.interestSetupNeeded)
            assertEquals(children, requireNotNull(FamilyPreferences(game.application).profile).children)
        }
    }

    @Test fun skippingLegacyInterestSetupAndPausingCannotDiscardTheInitialCatalog() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withHarness(prepare = { application ->
            // Exactly the old profile format: no profile IDs, interest settings or session.
            check(application.getSharedPreferences("family_v1", Context.MODE_PRIVATE).edit()
                .putString("first", "별").putString("second", "달").commit())
        }, beforeLocalLoad = {
            arrived.complete(Unit)
            release.await()
        }) { game ->
            try {
                withTimeout(12_000) { arrived.await() }
                assertTrue(game.model.ui.value.interestSetupNeeded)
                assertTrue(game.model.ui.value.adventures.isEmpty())
                game.onModel {
                    assertTrue(skipInterests())
                    pause()
                    background()
                    startFamilyAdventure(DEFAULT_ADVENTURE_ID)
                    restore()
                }
                val selected = game.model.ui.value.children
                assertEquals(2, selected.size)
                assertTrue(selected.all { it.interestsConfigured && it.interests.isEmpty() })
                assertTrue(game.model.ui.value.busy)
                assertTrue(game.model.ui.value.quiet)
                assertFalse(game.model.ui.value.microphoneListening)
                assertNull(game.model.ui.value.snapshot)

                release.complete(Unit)
                val ready = game.awaitUi { !it.busy && ContentReleaseExpectations.hasCurrentCards(it.adventures) && ContentReleaseExpectations.hasAllRecommendations(it.recommendations) }
                assertEquals(selected, ready.children)
                assertFalse(ready.interestSetupNeeded)
                assertEquals(ready.adventures.map { it.packId }, ready.recommendations.map { it.packId })
                assertTrue(ready.quiet)
                assertFalse(ready.microphoneListening)
                assertNull(ready.snapshot)
                assertNull(game.backend.restore())
                assertNull(LocalCheckpoint(game.application, "recovery_local_v1").sessionId)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun changingBackendCancelsAHeldLocalLoadAndReturningStartsAFreshQuietLoad() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        withHarness(beforeLocalLoad = {
            arrived.complete(Unit)
            try { release.await() } finally { finished.complete(Unit) }
        }) { game ->
            try {
                withTimeout(12_000) { arrived.await() }
                game.onModel { configure("http://127.0.0.1:8000", "test-token") }
                withTimeout(12_000) { finished.await() }
                release.complete(Unit)
                game.onModel { /* Drain the cancelled load; no remote request is needed. */ }
                val remote = game.model.ui.value
                assertFalse(remote.useLocal)
                assertTrue(remote.connected)
                assertFalse(remote.busy)
                assertTrue(remote.quiet)
                assertTrue(remote.adventures.isEmpty())
                assertTrue(remote.recommendations.isEmpty())
                assertNull(remote.snapshot)
                assertNull(LocalCheckpoint(game.application, "recovery_v1").sessionId)

                game.onModel { useLocalBackend() }
                val local = game.awaitUi { it.useLocal && !it.busy && ContentReleaseExpectations.hasCurrentCards(it.adventures) && ContentReleaseExpectations.hasAllRecommendations(it.recommendations) }
                assertTrue(local.quiet)
                assertFalse(local.microphoneListening)
                assertNull(local.snapshot)
                assertNull(game.backend.restore())
                assertNull(LocalCheckpoint(game.application, "recovery_local_v1").sessionId)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun failedOverviewKeepsDurablePauseAndRetryCannotOpenVoiceOrHideTheCatalog() = runBlocking {
        var activeId = ""
        var activeVersion = 0L
        var speaks = 0
        var captures = 0
        val output = object : SpeechOutput {
            override val readyState = MutableStateFlow(true)
            override val status = MutableStateFlow("시험 출력")
            override fun refresh() = Unit
            override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit, failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) { speaks++ }
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val input = object : SpeechCaptureInput {
            override val available = true
            override val source = "android_on_device"
            override fun unavailableReason(checkPermission: Boolean): String? = null
            override fun open(ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
                onFinal: (CaptureTicket, String) -> Unit, onFailure: (CaptureTicket, SpeechInputFailure) -> Unit) { captures++ }
            override fun close() = Unit
        }
        withHarness(output = output, input = input, prepare = { app ->
            runBlocking {
                assertTrue(InteractionPreferences(app).save(InteractionMode.VOICE))
                val client = LocalGameClient(app)
                var completed = client.create(playersBody("시험", null))
                var choices = 0
                while (!completed.game.getBoolean("finished")) {
                    completed = Snapshot.from(client.command(completed.sessionId, commandEnvelope(completed, UUID.randomUUID().toString(),
                        "playback.completed", JSONObject().put("playback_id", completed.speechPlan!!.playbackId))))
                    completed = Snapshot.from(client.command(completed.sessionId, commandEnvelope(completed, UUID.randomUUID().toString(),
                        "action.submit", JSONObject().put("text", completed.presentation.getJSONArray("actions").getJSONObject(0).getString("label"))
                            .put("is_final", true).put("capture_id", UUID.randomUUID().toString()).put("listen_window_id", completed.listenWindowId))))
                    assertTrue(++choices <= 12)
                }
                completed = Snapshot.from(client.command(completed.sessionId, commandEnvelope(completed, UUID.randomUUID().toString(),
                    "playback.completed", JSONObject().put("playback_id", completed.speechPlan!!.playbackId))))
                val active = client.createAdventure(playersBody("시험", null), "starlight-post-office")
                activeId = active.sessionId
                activeVersion = active.version
                val damaged = readRecordStoreDocument(client)
                damaged.getJSONObject("sessions").getJSONObject(completed.sessionId).getJSONObject("metadata")
                    .put("participant_profile_ids", "damaged")
                writeRecordStoreDocument(client, damaged)
            }
        }) { game ->
            game.awaitUi { !it.busy && it.localStorageProblem }
            game.onModel { /* Main-thread barrier before checking output/input side effects. */ }
            assertEquals("PAUSED", game.backend.snapshot(activeId).status)
            assertEquals(activeVersion + 1, game.backend.snapshot(activeId).version)
            assertTrue(ContentReleaseExpectations.hasCurrentCards(game.model.ui.value.adventures))
            assertTrue(game.model.ui.value.quiet)
            assertFalse(game.model.ui.value.microphoneListening)
            assertEquals(0, speaks)
            assertEquals(0, captures)
            game.onModel { retryLocalStorage() }
            game.awaitUi { !it.busy && it.localStorageProblem }
            game.onModel { }
            assertEquals(activeVersion + 1, game.backend.snapshot(activeId).version)
            assertTrue(game.model.ui.value.quiet)
            assertEquals(0, speaks)
            assertEquals(0, captures)
        }
    }

    @Test fun startingAfterAnEndingKeepsTheOldCheckpointUntilSuccessAndExposesFailure() = runBlocking {
        for (fails in listOf(false, true)) {
            lateinit var delayed: DelayedCreateClient
            withHarness({ app -> DelayedCreateClient(LocalGameClient(app)).also { delayed = it } }) { game ->
                val completed = seedFinishedAdventure(game)
                val original = canonicalJson(completed.game)
                delayed.holdNext = true
                delayed.failure = if (fails) ApiException(503, "TEST_CONNECTION_UNAVAILABLE") else null
                try {
                    game.onModel { start("별", null) }
                    withTimeout(12_000) { delayed.createArrived.await() }
                    val waiting = game.model.ui.value
                    assertEquals(NewAdventureStart.PREPARING, waiting.newAdventureStart)
                    assertTrue(waiting.busy)
                    assertTrue(waiting.quiet)
                    assertEquals(completed.sessionId, waiting.snapshot?.sessionId)
                    assertEquals(completed.sessionId, LocalCheckpoint(game.application, "recovery_v1").sessionId)
                    delayed.releaseCreate.complete(Unit)
                    if (fails) {
                        val failed = game.awaitUi { !it.busy && it.newAdventureStart == NewAdventureStart.FAILED }
                        assertTrue(failed.failure)
                        assertTrue(failed.quiet)
                        assertEquals(RecoveryCause.CONNECTION, failed.recoveryCause)
                        assertEquals(completed.sessionId, failed.snapshot?.sessionId)
                        assertEquals(completed.sessionId, LocalCheckpoint(game.application, "recovery_v1").sessionId)
                        // Leaving the failed attempt drops its UI notice, not the old record.
                        game.onModel { leaveAdventureScreen() }
                        game.awaitUi { !it.busy && it.newAdventureStart == null }
                        delayed.failure = null
                        game.onModel { start("별", null) }
                    }
                    val ready = game.awaitUi { !it.busy && !it.quiet && it.snapshot?.sessionId != completed.sessionId }
                    assertNull(ready.newAdventureStart)
                    assertFalse(ready.failure)
                    assertEquals("ACTIVE", ready.snapshot?.status)
                    assertEquals(ready.snapshot?.sessionId, LocalCheckpoint(game.application, "recovery_v1").sessionId)
                    assertEquals(original, canonicalJson(game.backend.snapshot(completed.sessionId).game))
                } finally { delayed.releaseCreate.complete(Unit) }
            }
        }
    }

    @Test fun cancellingANewStoryCannotRestoreItsNoticeOrReplaceTheCompletedCheckpoint() = runBlocking {
        lateinit var delayed: DelayedCreateClient
        withHarness({ app -> DelayedCreateClient(LocalGameClient(app)).also { delayed = it } }) { game ->
            val completed = seedFinishedAdventure(game)
            delayed.holdNext = true
            try {
                game.onModel { start("별", null) }
                withTimeout(12_000) { delayed.createArrived.await() }
                game.onModel { pause() }
                val stopped = game.awaitUi { !it.busy && it.newAdventureStart == NewAdventureStart.STOPPED }
                assertTrue(stopped.quiet)
                assertFalse(stopped.microphoneListening)
                assertEquals(completed.sessionId, stopped.snapshot?.sessionId)
                game.onModel { leaveAdventureScreen() }
                game.awaitUi { !it.busy && it.newAdventureStart == null }
                delayed.releaseCreate.complete(Unit)
                val detached = withTimeout(12_000) { delayed.detachedPaused.await() }
                game.onModel { /* Main-thread barrier after the late response was fenced. */ }
                assertEquals("PAUSED", detached.status)
                assertNull(detached.speechPlan)
                assertNull(detached.listenWindowId)
                assertTrue(game.model.ui.value.quiet)
                assertFalse(game.model.ui.value.microphoneListening)
                assertNull(game.model.ui.value.newAdventureStart)
                assertEquals(completed.sessionId, game.model.ui.value.snapshot?.sessionId)
                assertEquals(completed.sessionId, LocalCheckpoint(game.application, "recovery_v1").sessionId)
                assertEquals("ENDED", game.backend.snapshot(completed.sessionId).status)
                // A later direct start is a new action, not revival of the cancelled one.
                game.onModel { start("별", null) }
                val active = game.awaitUi { !it.busy && !it.quiet && it.snapshot?.status == "ACTIVE" }
                assertNull(active.newAdventureStart)
                assertTrue(active.snapshot!!.sessionId != detached.sessionId)
            } finally { delayed.releaseCreate.complete(Unit) }
        }
    }

    /** Real controller/local protocol; authored output callbacks and touch input are controlled. */
    @Test fun completedEntranceIsNotReplayedThroughControllerRepeat() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val requests = mutableListOf<Triple<BundledAudio?, BundledAudio, () -> Unit>>()
        var activeOutput = false
        val scenes = object : StoryScenePlayer {
            override fun play(cue: BundledAudio, narration: BundledAudio, complete: () -> Unit,
                              failure: () -> Unit, speechState: (Boolean) -> Unit) = record(cue, narration, complete, speechState)
            override fun playNarration(narration: BundledAudio, complete: () -> Unit,
                                       failure: () -> Unit, speechState: (Boolean) -> Unit) = record(null, narration, complete, speechState)
            private fun record(cue: BundledAudio?, narration: BundledAudio, complete: () -> Unit,
                               speechState: (Boolean) -> Unit) {
                assertFalse("The previous output must close before the next begins", activeOutput)
                activeOutput = true
                speechState(true)
                requests += Triple(cue, narration) {
                    activeOutput = false; speechState(false); complete()
                }
            }
            override fun stop() { activeOutput = false }
            override fun shutdown() = stop()
        }
        val focus = object : PlaybackAudioFocus {
            override fun acquire(onLost: () -> Unit) = true
            override fun release() = Unit
        }
        val clips = object : StoryClipPlayer {
            override fun play(audio: BundledAudio, complete: () -> Unit, failure: () -> Unit, started: () -> Unit) =
                fail("All authored narration must use the scene player")
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        lateinit var output: ComposedSpeechOutput
        instrumentation.runOnMainSync {
            output = ComposedSpeechOutput(loadNarrationAssets(instrumentation.targetContext), clips, focus,
                { fail("Unexpected controlled output interruption") }, { _, _ ->
                    object : SpeechOutput {
                        override val status = MutableStateFlow("controlled-dynamic-text")
                        override val readyState = MutableStateFlow(true)
                        override fun refresh() = Unit
                        override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
                                           failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) {
                            started(); complete()
                        }
                        override fun stop() = Unit
                        override fun shutdown() = Unit
                    }
                }, scenes = scenes)
        }
        val input = object : SpeechCaptureInput {
            override val available = true
            override val source = "controlled-unused-input"
            override fun unavailableReason(checkPermission: Boolean): String? = null
            override fun open(ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
                              onFinal: (CaptureTicket, String) -> Unit,
                              onFailure: (CaptureTicket, SpeechInputFailure) -> Unit) = fail("No microphone input in this test")
            override fun close() = Unit
        }
        withHarness(output = output, input = input) { game ->
            game.awaitUi { !it.busy && it.speechReady }
            game.onModel { useSimulation(false); start("별", null) }
            val first = requireNotNull(game.awaitUi { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }.snapshot)
            game.onModel {
                assertEquals(1, requests.size)
                assertEquals("cue_dino_arrival", requests.single().first?.id)
                requests.single().third()
            }
            val ready = requireNotNull(game.awaitUi { !it.busy && it.snapshot?.phase == "LISTENING" }.snapshot)
            game.onModel { chooseAction("다시") }
            val repeated = requireNotNull(game.awaitUi { !it.busy && it.snapshot?.speechPlan != null }.snapshot)
            assertEquals(first.turnId, repeated.turnId)
            assertEquals(first.turnEpoch, repeated.turnEpoch)
            assertTrue(first.speechPlan!!.playbackId != repeated.speechPlan!!.playbackId)
            game.onModel {
                assertEquals(2, requests.size)
                assertNull("Real controller repeat must retain completed cue delivery", requests.last().first)
                assertEquals(requests.first().second, requests.last().second)
                assertFalse(ui.value.microphoneListening)
                requests.last().third()
            }
            game.awaitUi { !it.busy && it.snapshot?.phase == "LISTENING" }
            game.onModel { chooseAction("물가") }
            val advanced = requireNotNull(game.awaitUi {
                !it.busy && it.snapshot?.game?.getJSONArray("history")?.length() == 1
            }.snapshot)
            assertTrue(ready.turnId != advanced.turnId)
            game.onModel {
                var completedFeedback = 0
                while (requests.last().first?.id != "cue_stream") {
                    assertTrue("Only bounded feedback may precede the next environment", ++completedFeedback <= 4)
                    val before = requests.size
                    requests.last().third()
                    assertEquals("Finishing feedback must advance exactly one authored part", before + 1, requests.size)
                }
                assertEquals("A new scene gets its own environment once", "cue_stream", requests.last().first?.id)
                assertFalse(ui.value.microphoneListening)
                pause("QUIET_REQUEST")
                assertFalse(activeOutput)
            }
            game.awaitUi { !it.busy && it.quiet && it.snapshot?.status == "PAUSED" }
        }
    }

    private suspend fun seedFinishedAdventure(game: Harness): Snapshot {
        game.awaitUi { !it.busy }
        game.onModel { useSimulation(true) }
        var state = game.backend.create(playersBody("별", null))
        var moves = 0
        while (!state.game.getBoolean("finished")) {
            state = Snapshot.from(game.backend.command(state.sessionId, commandEnvelope(state, UUID.randomUUID().toString(),
                "playback.completed", JSONObject().put("playback_id", requireNotNull(state.speechPlan).playbackId))))
            val label = state.presentation.getJSONArray("actions").getJSONObject(0).getString("label")
            state = Snapshot.from(game.backend.command(state.sessionId, commandEnvelope(state, UUID.randomUUID().toString(),
                "action.submit", JSONObject().put("text", label).put("is_final", true)
                    .put("capture_id", UUID.randomUUID().toString()).put("listen_window_id", state.listenWindowId))))
            assertTrue(++moves <= 12)
        }
        state = Snapshot.from(game.backend.command(state.sessionId, commandEnvelope(state, UUID.randomUUID().toString(),
            "playback.completed", JSONObject().put("playback_id", requireNotNull(state.speechPlan).playbackId))))
        assertEquals("ENDED", state.status)
        assertTrue(LocalCheckpoint(game.application, "recovery_v1").session(state.sessionId))
        game.onModel { restore() }
        game.awaitUi { !it.busy && it.snapshot?.sessionId == state.sessionId && it.snapshot.status == "ENDED" }
        return state
    }

    private class DelayedCreateClient(private val delegate: GameClient) : GameClient by delegate {
        var holdNext = false
        var failure: ApiException? = null
        val createArrived = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        val detachedPaused = CompletableDeferred<Snapshot>()
        private var heldSession: String? = null
        override suspend fun createAdventure(players: JSONObject, packId: String): Snapshot {
            val held = holdNext
            if (held) {
                holdNext = false
                createArrived.complete(Unit)
                releaseCreate.await()
            }
            failure?.let { throw it }
            return delegate.createAdventure(players, packId).also { if (held) heldSession = it.sessionId }
        }
        override suspend fun command(id: String, body: JSONObject): JSONObject =
            delegate.command(id, body).also { response ->
                if (id == heldSession && body.getString("type") == "session.pause")
                    detachedPaused.complete(Snapshot.from(response))
            }
    }

    private suspend fun withHarness(
        initialClient: (Application) -> GameClient? = { null },
        prepare: (Application) -> Unit = {},
        beforeLocalLoad: suspend () -> Unit = {},
        output: SpeechOutput? = null,
        input: SpeechCaptureInput? = null,
        block: suspend (Harness) -> Unit,
    ) {
        val game = Harness(InstrumentationRegistry.getInstrumentation(), initialClient, prepare, beforeLocalLoad, output, input)
        var primary: Throwable? = null
        try { block(game) } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try { game.close() } catch (failure: Throwable) {
                if (primary == null) throw failure
                primary.addSuppressed(failure)
            }
        }
    }

    private data class HeldResume(val body: JSONObject, val snapshot: Snapshot)

    private class DelayedResumeClient(private val delegate: GameClient) : GameClient by delegate {
        val resumeArrived = CompletableDeferred<HeldResume>()
        val releaseResume = CompletableDeferred<Unit>()
        val resumeOutcome = CompletableDeferred<String>()

        override suspend fun command(id: String, body: JSONObject): JSONObject {
            if (body.getString("type") != "session.resume") return delegate.command(id, body)
            val held = HeldResume(body.copyJson(), delegate.snapshot(id))
            resumeArrived.complete(held)
            releaseResume.await()
            try {
                return delegate.command(id, held.body).also { resumeOutcome.complete(it.getString("command_status")) }
            } catch (error: ApiException) {
                resumeOutcome.complete(error.code)
                throw error
            }
        }
    }

    /** Names, both checkpoint namespaces and local game files are isolated from real app data. */
    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "vm_recovery_${UUID.randomUUID()}"
        private val directory = File(context.noBackupFilesDir, prefix).also { check(it.mkdirs()) }
        private val preferences = ConcurrentHashMap.newKeySet<String>()
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "${prefix}_$name"
            preferences.add(scoped)
            return super.getSharedPreferences(scoped, mode)
        }
        fun clearPreferences() { preferences.forEach { check(super.deleteSharedPreferences(it)) } }
    }

    private class Harness(
        private val instrumentation: Instrumentation,
        initialClient: (Application) -> GameClient?,
        prepare: (Application) -> Unit,
        beforeLocalLoad: suspend () -> Unit,
        output: SpeechOutput?,
        input: SpeechCaptureInput?,
    ) {
        val application = ScopedApplication(instrumentation.targetContext).also(prepare)
        val backend = LocalGameClient(application)
        private val injected = initialClient(application)
        private val store = ViewModelStore()
        val model = onMain {
            ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DeveloperViewModel(application, injected, beforeLocalLoad,
                        outputFactory = output?.let { value -> { _ -> value } },
                        inputFactory = input?.let { value -> { value } }) as T
            })[DeveloperViewModel::class.java]
        }

        fun onModel(action: DeveloperViewModel.() -> Unit) = onMain { model.action() }

        suspend fun awaitUi(predicate: (DeveloperUiState) -> Boolean): DeveloperUiState =
            withTimeout(12_000) { model.ui.first(predicate) }

        suspend fun startAndPause() {
            awaitUi { !it.busy && it.quiet }
            onModel { useSimulation(true); start("별", null) }
            awaitUi { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
            onModel { pause("QUIET_REQUEST") }
            awaitUi { !it.busy && it.quiet && it.snapshot?.status == "PAUSED" }
        }

        suspend fun close() {
            try {
                (injected as? DelayedResumeClient)?.let { delayed ->
                    delayed.releaseResume.complete(Unit)
                    if (delayed.resumeArrived.isCompleted) withTimeout(12_000) { delayed.resumeOutcome.await() }
                }
                onModel { background() }
                awaitUi { !it.busy && it.quiet }
            } finally {
                onMain { store.clear() }
                backend.deleteAll()
                application.clearPreferences()
            }
        }

        private fun <T> onMain(action: () -> T): T {
            val task = FutureTask(Callable { action() })
            instrumentation.runOnMainSync(task)
            return task.get()
        }
    }
}
