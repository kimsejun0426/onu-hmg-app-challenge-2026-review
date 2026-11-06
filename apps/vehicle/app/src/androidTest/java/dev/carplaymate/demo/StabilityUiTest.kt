package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real family UI and ViewModel, with unique game/checkpoint/profile stores and silent output.
 * MainActivity is only the existing Compose host: its original ViewModel performs normal startup
 * loading/quiet checkpoint work, but receives no family edits or deletion calls from this test. The
 * dedicated-emulator opt-in remains required. Only synthetic names appear in saved screenshots.
 */
@RunWith(AndroidJUnit4::class)
class StabilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun interruptedDeletionHidesTheFamilyAndUiRetryFinishesErasure() = runBlocking {
        assumeTrue(
            "Run StabilityUiTest only with familyUiTests=true on a dedicated emulator.",
            InstrumentationRegistry.getArguments()
                .getString("familyUiTests")
                .equals("true", ignoreCase = true),
        )
        val application = ScopedApplication(instrumentation.targetContext)
        val backend = LocalGameClient(application)
        val store = ViewModelStore()
        val output = SilentOutput()
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var failOnce = true
        try {
            val entry = seedCompletedMemory(application, backend)
            val model = main {
                ViewModelProvider(
                    store,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            DeveloperViewModel(
                                application,
                                null,
                                deletionCheckpoint = { step ->
                                    if (step == FamilyDeletionStep.AFTER_GAME && failOnce) {
                                        failOnce = false
                                        arrived.complete(Unit)
                                        release.await()
                                        error("Injected synthetic storage failure")
                                    }
                                },
                                outputFactory = { output },
                            )
                                as T
                    },
                )[DeveloperViewModel::class.java]
            }
            await(model) { !it.busy && it.onboarded && it.journal.size == 1 }
            compose.runOnUiThread {
                compose.activity.setContent { PlaymateTheme { FamilyScreen(model, onHide = {}) } }
            }
            compose.onNodeWithTag("family.home").assertIsDisplayed()
            compose.onNodeWithTag("family.settingsNav").performClick()
            compose.onNodeWithTag("settings.memory").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("family.journal").assertIsDisplayed()
            compose
                .onNodeWithTag("journal.${entry.sessionId}")
                .performScrollTo()
                .assertIsDisplayed()
            assertTrue(
                compose
                    .onAllNodesWithText(FIRST, substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            )
            compose.onNodeWithTag("family.homeNav").performClick()
            await(model) { !it.busy }
            compose.onNodeWithTag("family.settings").assertIsDisplayed()
            compose
                .onNodeWithText("이 기기의 가족과 모험 기록 지우기")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.onNodeWithTag("settings.deleteDialog").assertIsDisplayed()
            compose.onNodeWithText("기록 지우기").assertIsDisplayed().performClick()

            withTimeout(12_000) { arrived.await() }
            await(model) { it.busy && it.familyDeletionPending }
            assertFamilyHidden()
            compose.onNodeWithText("가족 기록을 지우고 있어요").assertIsDisplayed()
            compose.onNodeWithTag("retry-family-deletion").assertDoesNotExist()
            assertTextFits("가족 기록을 지우고 있어요", minimumSp = 32f)
            assertTextFits("삭제가 끝날 때까지 가족 정보와 모험을 열지 않아요.", minimumSp = 24f)
            // The profile still exists on disk: the UI must mask it before all stores finish.
            assertEquals(FIRST, FamilyPreferences(application).profile?.first)
            assertTrue(FamilyDeletionJournal(application).pending)
            capture("deletion-busy")

            release.complete(Unit)
            await(model) { !it.busy && it.familyDeletionPending && it.failure }
            assertFamilyHidden()
            compose.onNodeWithText("삭제를 마저 진행해 주세요").assertIsDisplayed()
            assertTextFits("삭제를 마저 진행해 주세요", minimumSp = 32f)
            assertTextFits("삭제를 마치지 못했어요. 다시 삭제하면 남은 기록까지 지워요.", minimumSp = 26f)
            assertNotNull(FamilyPreferences(application).profile)
            assertNotNull(LocalCheckpoint(application).sessionId)
            val retry =
                compose
                    .onNodeWithTag("retry-family-deletion")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertIsEnabled()
                    .assertHeightIsAtLeast(80.dp)
            val retryNode = retry.fetchSemanticsNode()
            assertTrue(
                "The retry button must fit fully after scrolling: ${retryNode.boundsInRoot}, ${retryNode.size}",
                retryNode.boundsInRoot.height >= retryNode.size.height - 1f,
            )
            assertTextFits("다시 삭제하기", minimumSp = 24f)
            capture("deletion-failed")

            retry.performClick()
            await(model) { !it.busy && !it.familyDeletionPending && !it.onboarded }
            compose.onNodeWithTag("family-deletion").assertDoesNotExist()
            compose.onNodeWithTag("family.onboarding").assertIsDisplayed()
            compose.onNodeWithTag("welcome.first").performScrollTo().assertIsDisplayed()
            assertEquals(
                "",
                compose
                    .onNodeWithTag("welcome.first")
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.EditableText)
                    ?.text,
            )
            assertNoFamilyTextOrTabs()
            assertNull(FamilyPreferences(application).profile)
            assertNull(backend.restore())
            assertTrue(backend.journal().isEmpty())
            assertTrue(backend.feedback().isEmpty())
            assertNull(LocalCheckpoint(application).sessionId)
            assertNull(LocalCheckpoint(application, "recovery_local_v1").sessionId)
            assertFalse(FamilyDeletionJournal(application).pending)
            assertTrue(model.ui.value.quiet)
            assertFalse(model.ui.value.voiceInput || model.ui.value.microphoneListening)
            assertEquals(0, output.speaks)
            capture("deletion-recovered-welcome")
        } finally {
            release.complete(Unit)
            compose.runOnUiThread { compose.activity.setContent {} }
            main { store.clear() }
            backend.deleteAll()
            application.clean()
        }
    }

    private fun assertFamilyHidden() {
        compose.onNodeWithTag("family-deletion").assertIsDisplayed()
        listOf(
                "family.home",
                "family.journal",
                "family.settings",
                "family.onboarding",
                "family.memoryDetail",
            )
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        assertNoFamilyTextOrTabs()
    }

    private fun assertNoFamilyTextOrTabs() {
        listOf(FIRST, SECOND).forEach {
            compose.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }
        listOf("family.libraryLink", "family.settingsNav", "settings.memory", "settings.interests").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
    }

    private fun assertTextFits(text: String, minimumSp: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult
        ) {
            assertTrue(it(layouts))
        }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            // Compose String semantics can rebuild a wider paragraph than the measured Text.
            // Check rendered lines and final character, rather than that empty-width overflow flag.
            assertTrue(layout.lineCount > 0)
            assertFalse(layout.multiParagraph.didExceedMaxLines)
            assertEquals(
                layout.layoutInput.text.length,
                layout.getLineEnd(layout.lineCount - 1, false),
            )
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue(layout.getLineLeft(line) >= 0f)
                assertTrue(layout.getLineRight(line) <= layout.size.width.toFloat())
                assertTrue(layout.getLineTop(line) >= 0f)
                assertTrue(layout.getLineBottom(line) <= layout.size.height.toFloat())
            }
            assertTrue(layout.multiParagraph.height <= layout.size.height.toFloat())
            assertTrue(layout.layoutInput.style.fontSize.value >= minimumSp)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.filesDir, "stability-screenshots")
        check(directory.isDirectory || directory.mkdirs())
        try {
            File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun await(model: DeveloperViewModel, predicate: (DeveloperUiState) -> Boolean) =
        withTimeout(12_000) { model.ui.first(predicate) }

    /** Complete a synthetic reading/touch fixture through the real durable game commands. */
    private suspend fun seedCompletedMemory(
        application: Application,
        backend: LocalGameClient,
    ): FamilyJournalEntry {
        val preferences = FamilyPreferences(application)
        check(preferences.save(FamilyProfile(FIRST, SECOND)))
        check(preferences.saveInterests(setOf("dinosaurs"), setOf("space")))
        // Returning family with an explicit valid mode, not the separate first-choice flow.
        check(InteractionPreferences(application).save(InteractionMode.LISTEN_AND_SELECT))
        val profile = checkNotNull(preferences.profile)
        var snapshot =
            backend.createFamilyAdventure(
                playersBody(FIRST, SECOND),
                DEFAULT_ADVENTURE_ID,
                mapOf("p1" to checkNotNull(profile.firstId), "p2" to checkNotNull(profile.secondId)),
            )
        var choices = 0
        while (!snapshot.game.getBoolean("finished")) {
            snapshot = acknowledge(backend, snapshot)
            val players = snapshot.game.getJSONArray("players")
            val actor =
                (0 until players.length())
                    .map { players.getJSONObject(it) }
                    .first {
                        it.getString("player_id") == snapshot.game.getString("expected_player_id")
                    }
            val label =
                snapshot.presentation.getJSONArray("actions").getJSONObject(0).getString("label")
            val payload =
                JSONObject()
                    .put("text", "${actor.getString("nickname")}, $label")
                    .put("is_final", true)
                    .put("capture_id", UUID.randomUUID().toString())
                    .put("listen_window_id", snapshot.listenWindowId)
                    .put("locale", "ko-KR")
                    .put("input_source", "touch")
            snapshot =
                Snapshot.from(
                    backend.command(
                        snapshot.sessionId,
                        commandEnvelope(
                            snapshot,
                            UUID.randomUUID().toString(),
                            "action.submit",
                            payload,
                        ),
                    )
                )
            check(++choices <= 12)
        }
        snapshot = acknowledge(backend, snapshot)
        check(snapshot.status == "ENDED")
        val entry = backend.journal().single()
        backend.setFeedback(entry.sessionId, checkNotNull(profile.firstId), AdventureFeedback.LIKED)
        check(LocalCheckpoint(application).session("scoped-remote-checkpoint"))
        return entry
    }

    private suspend fun acknowledge(backend: LocalGameClient, snapshot: Snapshot) =
        Snapshot.from(
            backend.command(
                snapshot.sessionId,
                commandEnvelope(
                    snapshot,
                    UUID.randomUUID().toString(),
                    "playback.completed",
                    JSONObject().put("playback_id", checkNotNull(snapshot.speechPlan).playbackId),
                ),
            )
        )

    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable { action() })
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "stability_ui_${UUID.randomUUID()}"
        private val directory = File(context.noBackupFilesDir, prefix).also { check(it.mkdirs()) }
        private val preferences = mutableSetOf<String>()

        init {
            attachBaseContext(context)
        }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = directory

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "${prefix}_$name"
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

    private class SilentOutput : SpeechOutput {
        override val status = MutableStateFlow("시험용 무음 출력")
        override val readyState = MutableStateFlow(false)
        var speaks = 0

        override fun refresh() = Unit

        override fun stop() = Unit

        override fun shutdown() = Unit

        override fun speak(
            plan: SpeechPlan,
            generation: Long,
            complete: () -> Unit,
            failure: (SpeechOutputFailure) -> Unit,
            started: () -> Unit,
        ) {
            speaks++
            error("Deletion UI must never start playback")
        }
    }

    private companion object {
        const val FIRST = "시험별"
        const val SECOND = "시험달"
    }
}
