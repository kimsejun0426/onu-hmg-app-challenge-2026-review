package dev.carplaymate.demo

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Real Compose screens, local persistence, and engine. Each test explicitly selects "읽으며 체험":
 * playback completion is manual, and these tests do not claim microphone or TTS verification.
 * Destructive fixture setup is allowed only with familyUiTests=true on a dedicated emulator.
 */
@RunWith(AndroidJUnit4::class)
class FamilyUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val testName = TestName()

    private lateinit var model: DeveloperViewModel
    private var fixturesAuthorised = false
    private val runningImeAnimations = mutableSetOf<WindowInsetsAnimationCompat>()

    @Before
    fun prepareDedicatedEmulator() {
        assumeTrue(
            "FamilyUiTest clears app data: explicitly enable familyUiTests=true on a dedicated emulator.",
            InstrumentationRegistry.getArguments()
                .getString("familyUiTests")
                .equals("true", ignoreCase = true),
        )
        fixturesAuthorised = true
        bindActivity()
        awaitState("initial local load") { !it.busy }
        compose.runOnIdle { model.forgetFamily() }
        awaitState("clean family fixture") { !it.busy && !it.onboarded && it.snapshot == null }
        compose.onNodeWithTag("family.onboarding").assertIsDisplayed()
        capture("welcome")
    }

    private fun bindActivity() {
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[DeveloperViewModel::class.java]
            runningImeAnimations.clear()
            ViewCompat.setWindowInsetsAnimationCallback(
                compose.activity.window.decorView,
                object :
                    WindowInsetsAnimationCompat.Callback(
                        WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
                    ) {
                    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                        if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                            runningImeAnimations.add(animation)
                        }
                    }

                    override fun onProgress(
                        insets: WindowInsetsCompat,
                        runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                    ): WindowInsetsCompat = insets

                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        runningImeAnimations.remove(animation)
                    }
                },
            )
        }
    }

    @After
    fun removeOnlyExplicitlyAuthorisedFixtures() {
        if (!fixturesAuthorised || !::model.isInitialized) return
        compose.runOnIdle { model.pause() }
        awaitState("fixture pause") { !it.busy && it.quiet }
        compose.runOnIdle { model.forgetFamily() }
        awaitState("fixture removal") { !it.busy && !it.onboarded && it.snapshot == null }
        compose.runOnIdle {
            ViewCompat.setWindowInsetsAnimationCallback(compose.activity.window.decorView, null)
            runningImeAnimations.clear()
        }
    }

    @Test
    fun firstReadingChoiceIsQuietAndNotAskedAgainAfterActivityRecreation() {
        onboard(first = "별", second = null, firstMode = "읽으며 체험")
        assertTrue(state().interactionConfigured && state().simulation && state().quiet)
        assertFalse(state().microphoneListening)
        assertEquals(null, state().snapshot)
        compose.activityRule.scenario.recreate()
        bindActivity()
        awaitState("selected way restored without starting a story") {
            !it.busy && it.onboarded && it.interactionConfigured && it.simulation && it.quiet
        }
        compose.onNodeWithTag("family.home").assertIsDisplayed()
        compose.onNodeWithTag("family.interactionSetup").assertDoesNotExist()
        assertFalse(state().microphoneListening)
        assertEquals(null, state().snapshot)
    }

    @Test
    fun interestEditingBackAndHeaderReturnToSettingsWithoutSavingDrafts() {
        onboard(first = "하늘", second = null, firstInterests = setOf("space"), firstMode = "읽으며 체험")
        val savedChildren = state().children
        val savedProfile = requireNotNull(FamilyPreferences(compose.activity).profile)
        val savedInteraction = InteractionPreferences(compose.activity).read()
        compose.onNodeWithTag("family.settingsNav").performClick()

        listOf(false, true).forEach { systemBack ->
            compose.onNodeWithTag("family.settings").assertIsDisplayed()
            clickReachable(compose.onNodeWithTag("settings.interests"))
            compose.onNodeWithTag("family.interests").assertIsDisplayed()
            compose.onNodeWithTag("setup.step.2").assertDoesNotExist()
            compose.onNodeWithTag("interest.space").assertIsOn()
            compose.onNodeWithTag("interest.animals").assertIsOff()
            clickReachable(compose.onNodeWithTag("interest.animals"))
            compose.onNodeWithTag("interest.animals").assertIsOn()
            if (systemBack) compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            else compose.onNodeWithTag("family.homeNav").assertTextEquals("설정").performClick()

            compose.onNodeWithTag("family.settings").assertIsDisplayed()
            compose.onNodeWithTag("family.home").assertDoesNotExist()
            assertEquals(savedChildren, state().children)
            assertEquals(savedProfile, FamilyPreferences(compose.activity).profile)
            assertEquals(savedInteraction, InteractionPreferences(compose.activity).read())
            assertTrue(state().quiet)
            assertFalse(state().microphoneListening)
        }
        // Re-entering after the system Back also discards the uncommitted draft.
        clickReachable(compose.onNodeWithTag("settings.interests"))
        compose.onNodeWithTag("interest.space").assertIsOn()
        compose.onNodeWithTag("interest.animals").assertIsOff()
    }

    @Test
    fun unfinishedFirstInterestsBackHidesAndResumesWithoutSavingOrSkipping() {
        compose.onNodeWithTag("welcome.first").performScrollTo().performTextInput("별")
        awaitKeyboardReadyForScrolling()
        clickReachable(compose.onNodeWithTag("welcome.save"))
        awaitState("first interests remain unconfigured") {
            !it.busy && it.onboarded && it.interestSetupNeeded && !it.interactionConfigured
        }
        assertKeyboardDismissed()
        compose.onNodeWithTag("family.interests").assertIsDisplayed()
        compose.onNodeWithTag("setup.step.2").assertExists()
        compose.onNodeWithTag("family.homeNav").assertDoesNotExist()
        val savedProfile = requireNotNull(FamilyPreferences(compose.activity).profile)
        val savedChildren = state().children
        val savedInteraction = InteractionPreferences(compose.activity).read()
        clickReachable(compose.onNodeWithTag("interest.space"))
        compose.onNodeWithTag("interest.space").assertIsOn()

        val activity = compose.activity
        val stopped = CountDownLatch(1)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stopped.countDown()
        }
        try {
            compose.runOnIdle {
                activity.lifecycle.addObserver(observer)
                activity.onBackPressedDispatcher.onBackPressed()
            }
            assertTrue("System Back should hide the task, not complete interests", stopped.await(10, TimeUnit.SECONDS))
            assertTrue(state().interestSetupNeeded)
            assertFalse(state().interactionConfigured)
            assertEquals(savedChildren, state().children)
            assertEquals(savedProfile, FamilyPreferences(activity).profile)
            assertEquals(savedInteraction, InteractionPreferences(activity).read())
            assertTrue(state().quiet)
            assertFalse(state().microphoneListening)
        } finally {
            // Re-enter through the launcher: moving lifecycle state alone cannot foreground a task.
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val resumed = CountDownLatch(1)
            val resumeObserver = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumed.countDown()
            }
            instrumentation.runOnMainSync { activity.lifecycle.addObserver(resumeObserver) }
            try {
                val component = activity.componentName.flattenToString()
                check(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+").matches(component))
                val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                val command = "am start -a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_LAUNCHER} -n $component -f $flags"
                val output = ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)
                ).bufferedReader().use { it.readText() }
                assertTrue("Launcher re-entry must resume the original Activity: $output", resumed.await(10, TimeUnit.SECONDS))
                bindActivity()
                assertTrue("Launcher re-entry must retain the same Activity", compose.activity === activity)
            } finally {
                // Avoid Compose synchronization until the stopped task has actually resumed.
                instrumentation.runOnMainSync {
                    activity.lifecycle.removeObserver(resumeObserver)
                    activity.lifecycle.removeObserver(observer)
                }
            }
        }
        compose.onNodeWithTag("family.interests").assertIsDisplayed()
        compose.onNodeWithTag("family.interactionSetup").assertDoesNotExist()
        compose.onNodeWithTag("family.homeNav").assertDoesNotExist()
        compose.onNodeWithTag("setup.step.2").assertExists()
        compose.onNodeWithTag("interest.space").assertIsOn()
        assertTrue(state().interestSetupNeeded && state().quiet)
        assertFalse(state().interactionConfigured || state().microphoneListening)
        assertEquals(savedProfile, FamilyPreferences(compose.activity).profile)
        assertEquals(savedInteraction, InteractionPreferences(compose.activity).read())
        assertEquals(null, state().snapshot)
    }

    @Test
    fun oneChildReadsChoosesSixTimesAndSeesTheirActualEnding() {
        onboard(first = "반짝반짝우리집탐험대장님", second = null)
        selectReadingMode()
        startAdventure()
        compose.onNodeWithTag("family.turnTitle")
            .assertTextEquals("반짝반짝우리집탐험대장님의 차례예요")
        assertTextFits("family.turnTitle", minimumSp = 32f, maximumLines = 2)

        listOf("물가", "바퀴 자국", "나뭇잎", "돋보기", "트리케라톱스", "공작실").forEachIndexed { index, label ->
            completeReading()
            choose(label, expectedTurn = index + 1)
        }
        val beforeFinalReading = state().snapshot!!
        assertEquals("ACTIVE", beforeFinalReading.status)
        assertEquals("FOUND_TOGETHER", beforeFinalReading.game.getString("ending"))
        assertEquals(6, beforeFinalReading.game.getJSONArray("history").length())
        compose.onNodeWithTag("family.ending").assertDoesNotExist()

        clickScrollable("다 읽었어요 · 다음으로")
        awaitState("completed story after explicit final reading") {
            !it.busy && it.quiet && it.snapshot?.status == "ENDED" && it.completedAdventures == 1
        }
        compose.onNodeWithTag("family.ending").assertExists()
        compose
            .onNodeWithText(state().snapshot!!.presentation.getString("ending_title"))
            .assertExists()
        compose.onNodeWithText("함께 보탠 선택").assertDoesNotExist()
        compose.onNodeWithText("${state().children.single().nickname} · 6번").assertDoesNotExist()
        compose.onNodeWithTag("family.pause").assertDoesNotExist()
        compose.onNodeWithContentDescription("함께한 이야기를 기뻐하는 오누").assertIsDisplayed()
        compose.onNodeWithContentDescription("쉬고 있는 오누").assertDoesNotExist()
        assertTrue(state().quiet)
        assertFalse(state().microphoneListening)
        assertFalse(state().narrationActive)
        assertFalse(state().hasSavedSession)
        capture("ending")

        val childId = state().children.single().id
        val completedSession = state().snapshot!!.sessionId
        assertTrue(state().adventureFeedback.isEmpty())
        compose.onNodeWithTag("feedback.$childId.liked").assertDoesNotExist()
        clickReachable(compose.onNodeWithTag("family.ending.feedbackToggle"))
        assertTrue(state().adventureFeedback.isEmpty()) // Opening optional preferences is not a response.
        clickReachable(compose.onNodeWithTag("feedback.$childId.liked"))
        awaitState("explicit liked response saved for the actual participant") {
            !it.feedbackSaving &&
                it.adventureFeedback.singleOrNull()?.let { feedback ->
                    feedback.sessionId == completedSession &&
                        feedback.childId == childId &&
                        feedback.feedback == AdventureFeedback.LIKED
                } == true
        }
        capture("ending-feedback")
        val savedFeedback = state().adventureFeedback
        clickReachable(compose.onNodeWithTag("family.ending.feedbackToggle"))
        compose.onNodeWithTag("feedback.$childId.liked").assertDoesNotExist()
        assertEquals(savedFeedback, state().adventureFeedback)
        assertFalse(state().microphoneListening)
        clickReachable(compose.onNodeWithTag("family.ending.feedbackToggle"))

        // A second deliberate tap clears the response; completion itself never invents one.
        clickReachable(compose.onNodeWithTag("feedback.$childId.liked"))
        awaitState("explicit response cleared") {
            !it.feedbackSaving && it.adventureFeedback.isEmpty()
        }

        clickReachable(compose.onNodeWithTag("family.ending.home"))
        compose.onNodeWithTag("family.home").assertIsDisplayed()
        compose.onNodeWithText("이어서 함께하기").assertDoesNotExist()
        assertEquals(1, state().completedAdventures)
    }

    @Test
    fun helpAndRepeatAreDirectChoicesAndNeverTakeTheChildsTurn() {
        onboard(first = "별", second = "달")
        selectReadingMode()
        startAdventure()
        completeReading()
        val before = state().snapshot!!
        compose.onNodeWithTag("family.help").assertIsDisplayed()
            .assertHeightIsAtLeast(64.dp).performClick()
        awaitState("a helpful explanation without committing an answer") {
            !it.busy && it.snapshot?.speechPlan != null
        }
        assertEquals(0, state().snapshot!!.game.getJSONArray("history").length())
        assertEquals("p1", state().snapshot!!.game.getString("expected_player_id"))
        assertEquals(scores(before), scores(state().snapshot!!))
        capture("help")
        val storyScroll = compose.onNodeWithTag("family.storyScroll").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        if (storyScroll != null && storyScroll.maxValue() > storyScroll.value()) {
            val gameBeforeScroll = canonicalJson(state().snapshot!!.game)
            compose.onNodeWithTag("family.readMore").assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onNodeWithTag("family.storyScroll").fetchSemanticsNode().config
                    .getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()?.let { it > 0f } == true
            }
            assertEquals(gameBeforeScroll, canonicalJson(state().snapshot!!.game))
            capture("help-more")
        }
        completeReading()
        val afterHelp = canonicalJson(state().snapshot!!.game)
        compose.onNodeWithTag("family.repeat").assertIsDisplayed()
            .assertHeightIsAtLeast(64.dp).performClick()
        awaitState("repeat without changing progress") { !it.busy && it.snapshot?.speechPlan != null }
        assertEquals(afterHelp, canonicalJson(state().snapshot!!.game))
        completeReading()
        choose("물가", expectedTurn = 1)
        assertEquals("p2", state().snapshot!!.game.getString("expected_player_id"))
    }

    @Test
    fun twoChildrenPauseAndResumeWithoutRepeatingTheFirstChoice() {
        onboard(first = "별", second = "달")
        selectReadingMode()
        startAdventure()
        completeReading()
        choose("물가", expectedTurn = 1)
        val committed = state().snapshot!!
        val recordedScores = scores(committed)

        // The fixed footer must stay directly reachable without scrolling the story.
        compose.onNodeWithTag("family.pause").assertIsDisplayed().performClick()
        awaitState("quiet screen with saved first choice") {
            !it.busy && it.quiet && it.snapshot?.status == "PAUSED"
        }
        compose.onNodeWithTag("family.quiet").assertExists()
        compose.onNodeWithContentDescription("쉬고 있는 오누").assertIsDisplayed()
        compose.onNodeWithContentDescription("함께한 이야기를 기뻐하는 오누").assertDoesNotExist()
        assertTrue(state().quiet)
        assertFalse(state().microphoneListening)
        assertFalse(state().narrationActive)
        assertEquals(1, state().snapshot!!.game.getInt("turn_index"))
        compose.onNodeWithTag("family.pause").assertDoesNotExist()
        val quietSnapshot = state().snapshot!!
        val quietGame = canonicalJson(quietSnapshot.game)
        compose.onNodeWithTag("family.pauseStatus").assertDoesNotExist()
        // Rest has one explicit resume action. Tapping its heading must not resume.
        compose.onNodeWithTag("family.quiet.title").performScrollTo()
            .assertIsDisplayed().assertHasNoClickAction()
            .performTouchInput { click(); click() }
        compose.runOnIdle {
            assertTrue(state().quiet)
            assertFalse(state().busy)
            assertFalse(state().microphoneListening)
            assertEquals(quietSnapshot.turnId, state().snapshot!!.turnId)
            assertEquals(quietSnapshot.turnEpoch, state().snapshot!!.turnEpoch)
            assertEquals(quietGame, canonicalJson(state().snapshot!!.game))
        }
        capture("quiet")

        clickScrollable("준비됐어요 · 이어하기")
        awaitState("explicitly resumed prompt") {
            !it.busy && !it.quiet && it.snapshot?.speechPlan != null
        }
        val resumed = state().snapshot!!
        assertNotEquals(committed.turnId, resumed.turnId)
        assertTrue(resumed.turnEpoch > committed.turnEpoch)
        assertEquals(recordedScores, scores(resumed))
        assertEquals(1, resumed.game.getJSONArray("history").length())
        assertEquals("p2", resumed.game.getString("expected_player_id"))
        compose.onNodeWithText("달의 차례예요").assertExists()

        completeReading()
        choose("바퀴 자국", expectedTurn = 2)
        val history = state().snapshot!!.game.getJSONArray("history")
        assertEquals(2, history.length())
        assertEquals("p1", history.getJSONObject(0).getString("player_id"))
        assertEquals("p2", history.getJSONObject(1).getString("player_id"))
        assertEquals(0, state().completedAdventures)
    }

    @Test
    fun guardianChangesNamesAndMustConfirmDeletingTheFamily() {
        onboard(first = "별", second = null, firstInterests = setOf("dinosaurs"))
        selectReadingMode()
        startAdventure()
        completeReading()
        choose("물가", expectedTurn = 1)

        compose.onNodeWithTag("family.settingsNav").performClick()
        awaitState("settings pause") { !it.busy && it.quiet }
        compose.onNodeWithTag("family.settings").assertIsDisplayed()
        val storyBeforeInvalidName = canonicalJson(state().snapshot!!.game)
        val interactionBeforeProfileEdit = InteractionPreferences(compose.activity).read()
        listOf(
            "" to "부르기 쉬운 별명을 1~12글자로 적어 주세요.",
            "다시" to "놀이 중 사용하는 말과 겹치지 않는 별명으로 정해 주세요.",
        ).forEach { (invalidName, problem) ->
            compose.onNodeWithTag("settings.first").performScrollTo().performTextReplacement(invalidName)
            awaitKeyboardReadyForScrolling()
            clickScrollable("가족 별명 저장")
            awaitState("one-child nickname validation keeps the saved family") {
                it.failure && it.message == problem && it.firstNickname == "별" && !it.twoPlayers
            }
            compose.onNodeWithTag("settings.profileError").performScrollTo()
                .assertIsDisplayed().assertTextEquals(problem)
            compose.onAllNodesWithText(problem).assertCountEquals(1)
            assertEquals(LiveRegionMode.Polite, compose.onNodeWithTag("settings.profileError")
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
            compose.onNodeWithText("서로 구별되는 별명을 적어 주세요.").assertDoesNotExist()
            assertEquals(storyBeforeInvalidName, canonicalJson(state().snapshot!!.game))
            assertTrue(state().quiet)
            assertFalse(state().microphoneListening)
        }
        compose.onNodeWithTag("settings.first").performScrollTo().performTextReplacement("하늘")
        awaitKeyboardReadyForScrolling()
        clickScrollable("두 명")
        compose.onAllNodesWithText("두 명").assertCountEquals(1)
        compose.onNodeWithText("두 명").assertIsSelected()
        assertEquals(Role.RadioButton, compose.onNodeWithText("두 명")
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        compose.onNodeWithTag("settings.first").performScrollTo().performClick()
        awaitKeyboardReadyForScrolling()
        compose.onNodeWithTag("settings.first").performImeAction()
        compose.onNodeWithTag("settings.second").assertIsFocused().performTextInput("바다")
        awaitKeyboardReadyForScrolling()
        compose.onNodeWithTag("settings.second").performImeAction()
        compose.onNodeWithTag("settings.second").assertIsNotFocused()
        assertKeyboardDismissed()
        clickScrollable("가족 별명 저장")
        awaitState("updated family profile") {
            it.firstNickname == "하늘" && it.secondNickname == "바다" && it.twoPlayers
        }
        // Only the newly added child needs optional interests; the saved interaction mode remains.
        compose.onNodeWithTag("family.interests").assertIsDisplayed()
        assertKeyboardDismissed()
        compose.onNodeWithTag("interests.childTitle").assertContentDescriptionEquals("바다의 관심사")
        compose.onNodeWithTag("setup.step.2").assertDoesNotExist()
        compose.onNodeWithTag("family.homeNav").assertTextEquals("설정")
        assertEquals(interactionBeforeProfileEdit, InteractionPreferences(compose.activity).read())
        assertTrue(state().interactionConfigured && state().simulation && state().quiet)
        assertFalse(state().voiceInput || state().microphoneListening)
        val pendingChildProfile = requireNotNull(FamilyPreferences(compose.activity).profile)
        clickReachable(compose.onNodeWithTag("interest.ocean"))
        compose.onNodeWithTag("interest.ocean").assertIsOn()
        compose.onNodeWithTag("family.homeNav").performClick()
        compose.onNodeWithTag("family.settings").assertIsDisplayed()
        assertTrue(state().interestSetupNeeded)
        assertFalse(state().children[1].interestsConfigured)
        assertEquals(pendingChildProfile, FamilyPreferences(compose.activity).profile)
        assertEquals(interactionBeforeProfileEdit, InteractionPreferences(compose.activity).read())
        clickReachable(compose.onNodeWithTag("settings.interests"))
        // Navigation first persists a pause. Only tap once the form is actually enabled.
        awaitState("pending interests form ready") { !it.busy && it.quiet && it.interestSetupNeeded }
        compose.onNodeWithTag("interests.childTitle").assertContentDescriptionEquals("바다의 관심사")
        compose.onNodeWithTag("interest.ocean").assertIsOff()
        compose.onNodeWithTag("interests.skip").assertIsDisplayed().assertIsEnabled()
            .assertTextEquals("나중에 고르기").assertHeightIsAtLeast(64.dp).performClick()
        awaitState("new child interests explicitly skipped") { !it.interestSetupNeeded }
        compose.onNodeWithTag("family.settings").assertIsDisplayed()
        assertEquals(interactionBeforeProfileEdit, InteractionPreferences(compose.activity).read())
        assertEquals(setOf("dinosaurs"), state().children[0].interests)
        assertTrue(state().children.all { it.interestsConfigured })
        assertTrue(state().children[1].interests.isEmpty())
        capture("settings-updated")
        // Editing the next adventure's family must not rewrite the people in the saved story.
        assertEquals(
            "별",
            state().snapshot!!.game.getJSONArray("players").getJSONObject(0).getString("nickname"),
        )
        assertEquals(1, state().snapshot!!.game.getJSONArray("history").length())

        repeat(2) {
            clickScrollable("이 기기의 가족과 모험 기록 지우기")
            compose.onNodeWithTag("settings.deleteDialog").assertIsDisplayed()
            compose.onNodeWithText("이 기기의 기록을 지울까요?").assertIsDisplayed()
            compose.onNodeWithText("그대로 두기").performClick()
            compose.onNodeWithTag("settings.deleteDialog").assertDoesNotExist()
            assertTrue(state().onboarded)
            assertTrue(state().hasSavedSession)
        }
        capture("settings-after-cancel")

        clickScrollable("이 기기의 가족과 모험 기록 지우기")
        compose.onNodeWithTag("settings.deleteDialog").assertIsDisplayed()
        compose.onNodeWithText("기록 지우기").assertIsDisplayed().performClick()
        awaitState("confirmed deletion") { !it.busy && !it.onboarded && it.snapshot == null }
        compose.onNodeWithTag("family.onboarding").assertIsDisplayed()
        assertEquals("", state().firstNickname)
        assertEquals("", state().secondNickname)
        assertEquals(0, state().completedAdventures)
        assertFalse(state().hasSavedSession)
        assertFalse(state().voiceInput)
        assertFalse(state().microphoneListening)
        assertTrue(state().children.isEmpty())
        assertTrue(state().adventureFeedback.isEmpty())
        assertTrue(state().recommendations.all { it.matchedChildIds.isEmpty() })
    }

    @Test
    fun catalogAndJournalPreserveTheChosenWorldAndActualMemories() {
        onboard(first = "별", second = "달")
        selectReadingMode()
        openMemoriesFromHome()
        compose.onNodeWithTag("family.journal").assertIsDisplayed()
        assertTrue(state().journal.isEmpty())
        compose.onNodeWithText("아직 펼치지 않은\n우리의 이야기").assertExists()

        returnHomeFromMemories()
        compose.onNodeWithTag("family.libraryLink").assertIsDisplayed().performClick()
        compose.onNodeWithTag("family.library").assertIsDisplayed()
        assertEquals(7, state().adventures.size)
        assertEquals(LocalAdventureLibrary(compose.activity).cards().map { it.packId }.toSet(),
            state().adventures.map { it.packId }.toSet())
        compose.onNodeWithTag("library.filter.all").assertDoesNotExist()
        state().adventures.forEach {
            compose.onNodeWithTag("adventure.${it.packId}").performScrollTo().assertIsDisplayed()
            capture("library-${it.packId}")
        }
        compose.onNodeWithTag("adventure.${state().recommendations.first().packId}").performScrollTo()
        capture("library")
        clickReachable(compose.onNodeWithTag("adventure.starlight-post-office"))
        compose.onNodeWithTag("family.detail").assertIsDisplayed()
        assertEquals("starlight-post-office", state().selectedPackId)
        capture("starlight-detail")
        compose.onNodeWithTag("adventure.start").assertIsDisplayed().performClick()
        awaitState("starlight opening") { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
        assertEquals("starlight-post-office", state().snapshot!!.game.getString("pack_id"))
        captureRenderedStory("starlight-play")
        val choices =
            state().adventures.single { it.packId == "starlight-post-office" }.totalChoices
        repeat(choices) { index ->
            completeReading()
            val label =
                state()
                    .snapshot!!
                    .presentation
                    .getJSONArray("actions")
                    .getJSONObject(0)
                    .getString("label")
            choose(label, expectedTurn = index + 1)
        }
        assertTrue(state().snapshot!!.game.getBoolean("finished"))
        assertTrue(state().journal.isEmpty()) // An unheard final scene has not earned a memory.
        clickScrollable("다 읽었어요 · 다음으로")
        awaitState("completed starlight memory") {
            !it.busy && it.quiet && it.snapshot?.status == "ENDED" && it.journal.size == 1
        }
        val completed = state().snapshot!!
        val memory = state().journal.single()
        assertEquals(completed.sessionId, memory.sessionId)
        assertEquals("starlight-post-office", memory.packId)
        assertEquals(choices, memory.choicesCount)
        assertEquals(choices, memory.choices.size)
        assertEquals(listOf("별", "달"), memory.participants)
        assertEquals(completed.game.getString("ending"), memory.endingId)
        assertTrue(
            memory.choices.all {
                it.actionLabel.isNotBlank() && it.participant in memory.participants
            }
        )
        compose.onNodeWithTag("family.ending").assertExists()
        capture("starlight-ending")

        clickReachable(compose.onNodeWithTag("family.ending.home"))
        openMemoriesFromHome()
        compose.onNodeWithTag("family.journal").assertIsDisplayed()
        capture("journal")
        clickReachable(compose.onNodeWithTag("journal.${memory.sessionId}"))
        compose.onNodeWithTag("family.memoryDetail").assertIsDisplayed()
        compose.onNodeWithText(memory.summary).assertExists()
        memory.choices.forEach {
            compose.onNodeWithText("${it.participant} · ${it.actionLabel}").assertExists()
        }
        capture("memory-detail")
        val lastChoice = memory.choices.last()
        compose.onNodeWithText("${lastChoice.participant} · ${lastChoice.actionLabel}")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("기억 덮어두기").assertIsDisplayed()
        capture("memory-detail-end")
        compose.onNodeWithText("기억 덮어두기").performClick()
        returnHomeFromMemories()
        compose.onNodeWithTag("family.libraryLink").assertIsDisplayed().performClick()
        clickReachable(compose.onNodeWithTag("adventure.undersea-lighthouse"))
        assertEquals("undersea-lighthouse", state().selectedPackId)
        capture("undersea-detail")
        compose.onNodeWithTag("adventure.start").assertIsDisplayed().performClick()
        awaitState("undersea opening") { !it.busy && !it.quiet && it.snapshot?.speechPlan != null }
        assertEquals("undersea-lighthouse", state().snapshot!!.game.getString("pack_id"))
        assertEquals(0, state().snapshot!!.game.getJSONArray("history").length())
        assertEquals(memory, state().journal.single())
        compose.onNodeWithTag("family.pause").assertIsDisplayed().assertHeightIsAtLeast(80.dp)
        captureRenderedStory("undersea-play")
    }

    @Test
    fun picnicChoicesBecomeOneMemoryAndFeedbackStaysOptional() {
        onboard(first = "별", second = null)
        selectReadingMode()
        compose.onNodeWithTag("family.libraryLink").assertIsDisplayed().performClick()
        clickReachable(compose.onNodeWithTag("adventure.onu-little-picnic"))
        compose.onNodeWithTag("family.detail").assertIsDisplayed()
        compose.onNodeWithTag("adventure.start").assertIsDisplayed().performClick()
        awaitState("picnic opening") {
            !it.busy && !it.quiet && it.snapshot?.speechPlan != null
        }
        assertEquals("onu-little-picnic", state().snapshot!!.game.getString("pack_id"))
        assertEquals(4, state().adventures.single { it.packId == "onu-little-picnic" }.totalChoices)
        captureRenderedStory("picnic-opening")

        val chosen = listOf("잔잔한 연못", "종이배", "작은 원", "풍경 간직하기")
        chosen.forEachIndexed { index, label ->
            completeReading()
            choose(label, expectedTurn = index + 1)
        }
        assertEquals("QUIET_SCENE", state().snapshot!!.game.getString("ending"))
        assertEquals(4, state().snapshot!!.game.getJSONArray("history").length())
        assertTrue(state().journal.isEmpty())
        assertTrue(state().adventureFeedback.isEmpty())
        compose.onNodeWithTag("family.ending").assertDoesNotExist()
        clickScrollable("다 읽었어요 · 다음으로")
        awaitState("four-choice picnic completed after its closing scene") {
            !it.busy && it.quiet && it.snapshot?.status == "ENDED" && it.journal.size == 1
        }
        val memory = state().journal.single()
        val childId = state().children.single().id
        assertEquals("onu-little-picnic", memory.packId)
        assertEquals(chosen, memory.choices.map { it.actionLabel })
        assertEquals(4, memory.choicesCount)
        assertTrue(memory.summary.contains("잔잔한 연못"))
        assertTrue(memory.summary.contains("종이배"))
        assertFalse(state().microphoneListening)
        compose.onNodeWithTag("family.ending").assertExists()
        compose.onNodeWithTag("feedback.$childId.liked").assertDoesNotExist()
        capture("picnic-ending")

        clickReachable(compose.onNodeWithTag("family.ending.feedbackToggle"))
        assertTrue(state().adventureFeedback.isEmpty())
        clickReachable(compose.onNodeWithTag("feedback.$childId.liked"))
        awaitState("picnic response saved only after an explicit choice") {
            !it.feedbackSaving && it.adventureFeedback.singleOrNull()?.let { feedback ->
                feedback.sessionId == memory.sessionId && feedback.childId == childId &&
                    feedback.feedback == AdventureFeedback.LIKED
            } == true
        }
        clickReachable(compose.onNodeWithTag("family.ending.feedbackToggle"))
        compose.onNodeWithTag("feedback.$childId.liked").assertDoesNotExist()
        clickReachable(compose.onNodeWithTag("family.ending.home"))
        openMemoriesFromHome()
        clickReachable(compose.onNodeWithTag("journal.${memory.sessionId}"))
        compose.onNodeWithTag("family.memoryDetail").assertIsDisplayed()
        compose.onNodeWithText(memory.summary).assertExists()
        memory.choices.forEach {
            compose.onNodeWithText("${it.participant} · ${it.actionLabel}").assertExists()
        }
        assertEquals(memory, state().journal.single())
        assertEquals(1, state().adventureFeedback.size)
        assertTrue(state().quiet)
        assertFalse(state().microphoneListening)
        capture("picnic-memory")
    }

    @Test
    fun editedInterestsChangeTheRecommendationAndSurviveActivityRecreation() {
        onboard(first = "하늘", second = null, firstInterests = setOf("dinosaurs"))
        val childId = state().children.single().id
        val dinosaurs = state().adventures.single { "dinosaurs" in it.interestTags }.packId
        assertRecommendation(dinosaurs, childId, "dinosaurs")
        capture("home-matched-dinosaurs")

        compose.onNodeWithTag("family.settingsNav").performClick()
        clickReachable(compose.onNodeWithTag("settings.interests"))
        compose.onNodeWithTag("family.interests").assertIsDisplayed()
        compose.onNodeWithTag("interest.dinosaurs").assertIsOn()
        compose.onNodeWithTag("interest.ocean").assertIsOff()
        clickReachable(compose.onNodeWithTag("interest.dinosaurs"))
        clickReachable(compose.onNodeWithTag("interest.ocean"))
        capture("interests-edited")
        compose.onNodeWithTag("interests.save").assertIsDisplayed().performClick()
        awaitState("explicit ocean interest saved") {
            !it.busy && it.children.single().interests == setOf("ocean")
        }
        assertEquals(childId, state().children.single().id)
        compose.onNodeWithTag("family.settings").assertIsDisplayed()
        compose.onNodeWithTag("family.homeNav").performClick()
        assertRecommendation("undersea-lighthouse", childId, "ocean")
        capture("home-matched-ocean")

        // Inspect the saved preference record as well as the Activity's retained UI state.
        // Real OS process termination is exercised separately by ProcessRestoreTest.
        val stored = requireNotNull(FamilyPreferences(compose.activity).profile)
        assertEquals(childId, stored.firstId)
        assertEquals(setOf("ocean"), stored.firstInterests)
        assertTrue(stored.firstInterestsConfigured)
        compose.activityRule.scenario.recreate()
        bindActivity()
        awaitState("recreated activity with the same explicit interests") {
            !it.busy &&
                it.onboarded &&
                !it.interestSetupNeeded &&
                it.children.single().id == childId &&
                it.children.single().interests == setOf("ocean")
        }
        compose.onNodeWithTag("family.home").assertIsDisplayed()
        assertRecommendation("undersea-lighthouse", childId, "ocean")
        assertTrue(state().quiet)
        assertFalse(state().voiceInput)
        assertFalse(state().microphoneListening)
        assertFalse(state().simulation)
        capture("home-restored-interests")
        compose.onNodeWithTag("family.settingsNav").performClick()
        clickReachable(compose.onNodeWithTag("settings.interests"))
        compose.onNodeWithTag("interest.ocean").assertIsOn()
        compose.onNodeWithTag("interest.dinosaurs").assertIsOff()
    }

    @Test
    fun twoChildrenKeepIndependentInterestsAndCancelOrDeleteExplicitly() {
        onboard(
            first = "별",
            second = "달",
            firstInterests = setOf("space"),
            secondInterests = setOf("ocean"),
        )
        val savedChildren = state().children
        assertEquals(setOf("space"), savedChildren[0].interests)
        assertEquals(setOf("ocean"), savedChildren[1].interests)
        assertNotEquals(savedChildren[0].id, savedChildren[1].id)
        assertTrue(state().recommendations.any { savedChildren[0].id in it.matchedChildIds })
        assertTrue(state().recommendations.any { savedChildren[1].id in it.matchedChildIds })

        compose.onNodeWithTag("family.settingsNav").performClick()
        clickReachable(compose.onNodeWithTag("settings.interests"))
        compose.onNodeWithTag("interest.space").assertIsOn()
        compose.onNodeWithTag("interest.ocean").assertIsOff()
        clickReachable(compose.onNodeWithTag("interest.animals"))
        clickReachable(compose.onNodeWithTag("interests.child.1"))
        compose.onNodeWithTag("interest.ocean").assertIsOn()
        compose.onNodeWithTag("interest.space").assertIsOff()
        compose.onNodeWithTag("interest.animals").assertIsOff()
        capture("interests-second-child")
        compose.onNodeWithTag("interests.skip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("family.settings").assertIsDisplayed()
        assertEquals(savedChildren, state().children)
        assertEquals(
            savedChildren,
            requireNotNull(FamilyPreferences(compose.activity).profile).children,
        )

        // An explicit empty selection is allowed for one child without changing the other.
        clickReachable(compose.onNodeWithTag("settings.interests"))
        compose.onNodeWithTag("interest.animals").assertIsOff()
        clickReachable(compose.onNodeWithTag("interests.child.1"))
        clickReachable(compose.onNodeWithTag("interest.ocean"))
        compose.onNodeWithTag("interests.save").assertIsDisplayed().performClick()
        awaitState("second child explicitly saves no interests") {
            it.children.size == 2 &&
                it.children[1].interests.isEmpty() &&
                it.children.all { child -> child.interestsConfigured }
        }
        assertEquals(savedChildren[0], state().children[0])
        assertEquals(savedChildren[1].id, state().children[1].id)
        assertTrue(state().recommendations.none { savedChildren[1].id in it.matchedChildIds })
        capture("settings-independent-interests")

        clickScrollable("이 기기의 가족과 모험 기록 지우기")
        compose.onNodeWithTag("settings.deleteDialog").assertIsDisplayed()
        compose.onNodeWithText("기록 지우기").assertIsDisplayed().performClick()
        awaitState("confirmed deletion includes child identities and interests") {
            !it.busy && !it.onboarded && it.children.isEmpty()
        }
        compose.onNodeWithTag("family.onboarding").assertIsDisplayed()
        assertEquals(null, FamilyPreferences(compose.activity).profile)
        assertTrue(state().adventureFeedback.isEmpty())
        assertTrue(state().recommendations.all { it.matchedChildIds.isEmpty() })
        assertFalse(state().interestSetupNeeded)
        assertFalse(state().microphoneListening)
        capture("interests-deleted")
    }

    private fun openMemoriesFromHome() {
        compose.onNodeWithTag("family.home").assertIsDisplayed()
        compose.onNodeWithTag("family.settingsNav").assertIsDisplayed().performClick()
        compose.onNodeWithTag("family.settings").assertIsDisplayed()
        clickReachable(compose.onNodeWithTag("settings.memory"))
        compose.onNodeWithTag("family.journal").assertIsDisplayed()
        assertTrue(state().quiet)
        assertFalse(state().microphoneListening)
    }

    private fun returnHomeFromMemories() {
        compose.onNodeWithTag("family.homeNav").assertIsDisplayed().performClick()
        compose.onNodeWithTag("family.settings").assertIsDisplayed()
        compose.onNodeWithTag("family.homeNav").assertIsDisplayed().performClick()
        compose.onNodeWithTag("family.home").assertIsDisplayed()
        // Navigation pauses/persists asynchronously. The library intentionally stays disabled
        // until that operation finishes; do not send a no-op click while it is still saving.
        awaitState("home navigation committed quiet progress") { !it.busy && it.quiet }
        compose.onNodeWithTag("family.libraryLink").assertIsEnabled()
        assertTrue(state().quiet)
        assertFalse(state().microphoneListening)
    }

    private fun assertRecommendation(packId: String, childId: String, interestId: String) {
        val recommendation = state().recommendations.first()
        assertEquals(packId, recommendation.packId)
        assertTrue(childId in recommendation.matchedChildIds)
        assertTrue(interestId in recommendation.matchedInterestIds)
        if (interestId == "ocean" && state().savedAdventures.isEmpty()) {
            compose.onNodeWithTag("recommendation.title").assertTextEquals("첫 점프를 들려줘!")
            compose.onNodeWithTag("family.firstJump").assertIsDisplayed()
            return
        }
        val card = state().adventures.single { it.packId == packId }
        compose.onNodeWithTag("recommendation.title").assertTextEquals(card.title)
        compose
            .onNodeWithTag("recommendation.reason")
            .assertTextEquals(recommendation.reasons.first())
    }

    private fun onboard(
        first: String,
        second: String?,
        firstInterests: Set<String>? = null,
        secondInterests: Set<String> = emptySet(),
        firstMode: String = "듣고 선택하기",
    ) {
        compose.onNodeWithTag("welcome.first").performScrollTo().performTextInput(first)
        awaitKeyboardReadyForScrolling()
        if (second != null) {
            clickScrollable("두 명")
            compose.onNodeWithTag("welcome.second").performScrollTo().performTextInput(second)
            awaitKeyboardReadyForScrolling()
        }
        clickReachable(compose.onNodeWithTag("welcome.save"))
        awaitState("saved family profile") { !it.busy && it.onboarded }
        compose.onNodeWithTag("family.interests").assertIsDisplayed()
        assertKeyboardDismissed()
        assertTrue(state().interestSetupNeeded)
        assertTrue(state().children.all { it.interests.isEmpty() })
        capture("interests")
        if (firstInterests == null) {
            compose.onNodeWithTag("interests.skip").assertIsDisplayed().performClick()
        } else {
            firstInterests.forEach { clickReachable(compose.onNodeWithTag("interest.$it")) }
            if (second != null) {
                clickReachable(compose.onNodeWithTag("interests.child.1"))
                secondInterests.forEach { clickReachable(compose.onNodeWithTag("interest.$it")) }
            }
            compose
                .onNodeWithTag("interests.save")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(80.dp)
                .performClick()
        }
        awaitState("explicit interest setup saved or skipped") {
            !it.busy && !it.interestSetupNeeded
        }
        assertTrue(state().children.all { it.interestsConfigured })
        assertEquals(firstInterests.orEmpty(), state().children[0].interests)
        if (second != null) assertEquals(secondInterests, state().children[1].interests)
        compose.onNodeWithTag("family.interactionSetup").assertIsDisplayed()
        val voiceDescription = if (second == null) "이야기를 듣고, 선택을 말해요."
            else "이야기를 듣고, 이름과 선택을 말해요."
        // Check the untouched first viewport before any choice or scroll can conceal clipping.
        listOf("음성으로 함께", "듣고 선택하기", "읽으며 체험").forEach { mode ->
            assertFullyVisible(compose.onNodeWithText(mode), "Initial interaction choice: $mode")
        }
        val compactChoices = compose.onAllNodesWithTag("interaction.compactChoices")
            .fetchSemanticsNodes().isNotEmpty()
        if (compactChoices) {
            listOf("voice", "listen", "read").forEach { id ->
                compose.onNodeWithTag("interaction.compact.$id")
                    .assertIsNotSelected().assertHeightIsAtLeast(88.dp)
                assertTextFits("interaction.compact.title.$id", minimumSp = 24f,
                    maximumLines = 2, useUnmergedTree = true)
            }
            compose.onNodeWithTag("interaction.compact.detail")
                .assertTextEquals("원하는 방식을 골라 주세요.")
        } else {
            compose.onNodeWithText(voiceDescription).assertExists()
            compose.onNodeWithText(if (second == null) "이야기를 듣고, 이름과 선택을 말해요."
                else "이야기를 듣고, 선택을 말해요.").assertDoesNotExist()
        }
        assertFalse(state().interactionConfigured)
        assertTrue(state().quiet)
        assertFalse(state().microphoneListening)
        compose.onNodeWithTag("interaction.done").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("family.home").assertDoesNotExist()
        compose.onNodeWithTag("family.settingsNav").assertDoesNotExist()
        if (compactChoices && firstMode != "읽으며 체험") {
            clickScrollable("읽으며 체험")
            awaitState("compact reading choice is immediately usable and quiet") {
                canFinishInteractionSetup(it) && it.simulation && !it.voiceInput && it.quiet
            }
            assertCompactInteractionChoice("읽으며 체험", voiceDescription)
            compose.onNodeWithTag("interaction.done").assertIsEnabled()
            assertFalse(state().microphoneListening)
            assertFalse(state().narrationActive)
            assertEquals(null, state().snapshot)
        }
        clickScrollable(firstMode)
        awaitState("explicit first interaction choice saved quietly") {
            canFinishInteractionSetup(it) && it.quiet &&
                it.simulation == (firstMode == "읽으며 체험") &&
                it.voiceInput == (firstMode == "음성으로 함께")
        }
        if (compactChoices) assertCompactInteractionChoice(firstMode, voiceDescription)
        assertFalse(state().microphoneListening)
        assertFalse(state().narrationActive)
        assertEquals(null, state().snapshot)
        if (firstMode == "읽으며 체험") {
            compose.onNodeWithTag("interaction.inputProblem").assertDoesNotExist()
            compose.onNodeWithTag("interaction.outputProblem").assertDoesNotExist()
            compose.onNodeWithTag("interaction.retry").assertDoesNotExist()
            compose.onNodeWithTag("interaction.appPermissions").assertDoesNotExist()
        }
        capture("interaction-choice")
        compose.onNodeWithTag("interaction.done").assertIsDisplayed().assertHeightIsAtLeast(80.dp).performClick()
        compose.onNodeWithTag("family.home").assertIsDisplayed()
        listOf("home", "library", "journal", "settings").forEach {
            compose.onNodeWithTag("family.nav.$it").assertDoesNotExist()
        }
        compose.onNodeWithTag("family.editInterests").assertDoesNotExist()
        compose.onNodeWithTag("family.libraryLink").assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        compose.onNodeWithTag("family.home.mode").assertTextEquals("선택한 방식 · $firstMode")
        assertFullyVisible(compose.onNodeWithTag("family.home.mode"), "Initial home mode")
        assertTextFits("family.home.mode", minimumSp = 20f)
        val letterHome = shouldFeatureFirstJump(state().children, state().savedAdventures.isNotEmpty())
        assertFullyVisible(compose.onNodeWithTag(if (letterHome) "recommendation.title" else "recommendation.reason"), "Initial home invitation")
        // No scroll here: the primary action must be fully visible on the initial home viewport.
        val startButton =
            compose
                .onNodeWithTag(if (letterHome) "family.firstJump" else "family.start")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(80.dp)
                .fetchSemanticsNode()
        assertEquals(
            "The initial home action is vertically clipped.",
            startButton.size.height.toFloat(),
            startButton.boundsInRoot.height,
            0.5f,
        )
        assertEquals(
            "The initial home action is horizontally clipped.",
            startButton.size.width.toFloat(),
            startButton.boundsInRoot.width,
            0.5f,
        )
        assertEquals(firstMode == "읽으며 체험", state().simulation)
        assertFalse(state().voiceInput)
        capture("home")
    }

    private fun selectReadingMode() {
        compose.onNodeWithTag("family.settingsNav").performClick()
        awaitState("settings are quiet") { !it.busy && it.quiet }
        clickScrollable("읽으며 체험")
        awaitState("explicit reading mode") {
            !it.busy && it.simulation && !it.voiceInput && it.quiet
        }
        assertFalse(state().microphoneListening)
        capture("settings")
        compose.onNodeWithTag("family.homeNav").performClick()
        awaitState("home is quiet") { !it.busy && it.quiet }
        compose.onNodeWithTag("family.home.mode").assertTextEquals("선택한 방식 · 읽으며 체험")
        assertFullyVisible(compose.onNodeWithTag("family.home.mode"), "Updated home mode")
        assertTextFits("family.home.mode", minimumSp = 20f)
        val primary = if (shouldFeatureFirstJump(state().children, state().savedAdventures.isNotEmpty()))
            "family.firstJump" else "family.start"
        assertFullyVisible(compose.onNodeWithTag(primary), "Home action after changing mode")
    }

    private fun assertCompactInteractionChoice(mode: String, voiceDescription: String) {
        val ids = mapOf("음성으로 함께" to "voice", "듣고 선택하기" to "listen", "읽으며 체험" to "read")
        ids.forEach { (label, id) ->
            val option = compose.onNodeWithTag("interaction.compact.$id")
            if (label == mode) option.assertIsSelected() else option.assertIsNotSelected()
        }
        val detail = when (mode) {
            "음성으로 함께" -> voiceDescription
            "듣고 선택하기" -> "이야기를 듣고 화면에서 선택해요."
            "읽으며 체험" -> "소리 없이, 보호자와 읽으며 선택해요."
            else -> error("Unexpected interaction mode: $mode")
        }
        compose.onNodeWithTag("interaction.compact.detail").assertTextEquals(detail).assertIsDisplayed()
        compose.onNodeWithTag("interaction.selectedMode").assertTextEquals("선택한 방식 · $mode")
    }

    private fun assertFullyVisible(node: SemanticsNodeInteraction, description: String) {
        val semantics = node.assertIsDisplayed().fetchSemanticsNode()
        assertEquals("$description is horizontally clipped.",
            semantics.size.width.toFloat(), semantics.boundsInRoot.width, 0.5f)
        assertEquals("$description is vertically clipped.",
            semantics.size.height.toFloat(), semantics.boundsInRoot.height, 0.5f)
    }

    private fun startAdventure() {
        clickScrollable("함께 모험 시작하기")
        awaitState("first story passage") {
            !it.busy && !it.quiet && it.snapshot?.speechPlan != null
        }
        assertTrue(state().simulation)
        assertTrue(state().useLocal)
        assertFalse(state().voiceInput)
        assertFalse(state().microphoneListening)
        compose.onNodeWithText("함께 읽는 이야기").assertExists()
        assertTextFits("family.turnTitle", minimumSp = 32f)
        assertTextFits("family.storyText", minimumSp = 26f)
        compose.onNodeWithTag("family.pause").assertIsDisplayed().assertHeightIsAtLeast(80.dp)
        capture("play-reading")
    }

    private fun completeReading() {
        clickScrollable("다 읽었어요 · 다음으로")
        awaitState("choice after explicit reading completion") {
            !it.busy && !it.quiet && it.snapshot?.phase == "LISTENING"
        }
        assertTextFits("family.turnTitle", minimumSp = 32f)
        assertTextFits("family.storyText", minimumSp = 26f)
        compose.onNodeWithTag("family.choice.0").performScrollTo().assertHeightIsAtLeast(80.dp)
        compose.onNodeWithTag("family.pause").assertIsDisplayed().assertHeightIsAtLeast(80.dp)
        if (state().snapshot?.game?.optInt("turn_index") == 0) capture("listening")
    }

    private fun choose(label: String, expectedTurn: Int) {
        clickScrollable(label)
        awaitState("choice $expectedTurn committed") {
            !it.busy && !it.quiet && it.snapshot?.game?.optInt("turn_index") == expectedTurn
        }
    }

    private fun clickScrollable(label: String) {
        clickReachable(compose.onNodeWithText(label))
    }

    private fun clickReachable(node: SemanticsNodeInteraction) {
        val before = node.fetchSemanticsNode()
        val ancestors = generateSequence(before.parent) { it.parent }.toList()
        val scrollAncestors = ancestors.filter { it.config.contains(SemanticsActions.ScrollBy) }
        try {
            // Clipped bounds alone cannot distinguish fully off-screen content from a fixed
            // control. Use the actual scroll semantics, matching a user's scroll-then-tap.
            if (scrollAncestors.isNotEmpty()) node.performScrollTo()
            node.assertIsDisplayed().performClick()
        } catch (failure: AssertionError) {
            val after = node.fetchSemanticsNode()
            val diagnostic =
                "Unreachable control: before=${before.boundsInRoot}, " +
                    "beforeSize=${before.size}, beforeWindow=${before.boundsInWindow}, " +
                    "after=${after.boundsInRoot}, afterSize=${after.size}, afterWindow=${after.boundsInWindow}, " +
                    "scrollAncestors=${scrollAncestors.size}, ancestors=" +
                    ancestors.map {
                        "${it.config.getOrNull(SemanticsProperties.TestTag)}:${it.boundsInRoot}"
                    }
            val captureFailure = runCatching { capture("unreachable-control") }.exceptionOrNull()
            throw AssertionError("$diagnostic; captureFailure=${captureFailure?.message}", failure)
        }
    }

    private fun state(): DeveloperUiState = model.ui.value

    private fun assertTextFits(
        tag: String,
        minimumSp: Float,
        maximumLines: Int = Int.MAX_VALUE,
        useUnmergedTree: Boolean = false,
    ) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = useUnmergedTree)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            action ->
            assertTrue("$tag must expose its rendered text layout.", action(layouts))
        }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            val diagnostic =
                "$tag: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                    "lines=${layout.lineCount}, overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}, " +
                    "constraints=${layout.layoutInput.constraints}, font=${layout.layoutInput.style.fontSize}, " +
                    "fontScale=${layout.layoutInput.density.fontScale}, maxLines=${layout.layoutInput.maxLines}, " +
                    "lineWidths=${(0 until layout.lineCount).map { layout.getLineRight(it) - layout.getLineLeft(it) }}"
            // Plain String semantics rebuild MultiParagraph at the parent's maxWidth but retain
            // the actual, narrower layoutSize. hasVisualOverflow then reports empty horizontal
            // space as clipping. Check every rendered line and the complete text instead.
            val complete =
                layout.lineCount > 0 &&
                    !layout.multiParagraph.didExceedMaxLines &&
                    layout.getLineEnd(layout.lineCount - 1, visibleEnd = false) ==
                        layout.layoutInput.text.length
            val linesFit =
                (0 until layout.lineCount).all { line ->
                    layout.getLineLeft(line) >= 0f &&
                        layout.getLineRight(line) <= layout.size.width.toFloat() &&
                        layout.getLineTop(line) >= 0f &&
                        layout.getLineBottom(line) <= layout.size.height.toFloat() &&
                        !layout.isLineEllipsized(line)
                }
            val fits =
                complete && linesFit && layout.multiParagraph.height <= layout.size.height.toFloat()
            if (!fits) capture("layout-failure")
            assertTrue("$diagnostic clips, ellipsizes, or omits rendered text.", fits)
            assertTrue("$diagnostic exceeds $maximumLines lines.", layout.lineCount <= maximumLines)
            assertTrue(
                "$tag must retain the readable base font size.",
                layout.layoutInput.style.fontSize.value >= minimumSp,
            )
        }
    }

    private fun awaitKeyboardReadyForScrolling() {
        // Text input requests the platform IME asynchronously. Compose idleness alone does not
        // wait for the window resize or the focused field's resulting bring-into-view request.
        // Wait for the real keyboard animation to finish before scrolling to another control.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle {
                val decor = compose.activity.window.decorView
                val insets = ViewCompat.getRootWindowInsets(decor)
                insets != null &&
                    insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0 &&
                    runningImeAnimations.isEmpty() &&
                    !decor.isLayoutRequested
            }
        }
        compose.waitForIdle()
    }

    private fun assertKeyboardDismissed() {
        // The product clears focus on successful save; synchronize with the OS inset change,
        // rather than hiding the keyboard from the test or sleeping past a missed tap.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle {
                val insets =
                    requireNotNull(
                        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    )
                !insets.isVisible(WindowInsetsCompat.Type.ime())
            }
        }
    }

    private fun captureRenderedStory(label: String) {
        val snapshot = requireNotNull(state().snapshot)
        val expected =
            snapshot.speechPlan?.segments?.joinToString("\n\n") { it.text }
                ?: snapshot.presentation.optString("prompt")
        assertTrue("A story screenshot requires actual passage text.", expected.isNotBlank())
        // A committed model snapshot can precede Compose's next composition. Wait for this
        // specific passage in the rendered screen, not merely for a non-null model snapshot.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("family.storyText").fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                    it.text == expected
                }
            }
        }
        compose.onNodeWithTag("family.storyText").assertIsDisplayed()
        compose.onNodeWithTag("family.pause").assertIsDisplayed().assertHeightIsAtLeast(80.dp)
        assertTextFits("family.turnTitle", minimumSp = 32f)
        assertTextFits("family.storyText", minimumSp = 26f)
        capture(label)
    }

    private fun capture(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Wait for the window's accessibility events to settle, then capture the composed display.
        // Capturing only the Compose root can omit unchanged render layers or system insets.
        awaitCommittedUiFrame(compose.activity)
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        // Screenshot evidence only: platform dialog fades and ripples are not covered by
        // Compose/accessibility idleness. Functional assertions have already run above.
        SystemClock.sleep(300)
        instrumentation.waitForIdleSync()
        val directory = requireNotNull(compose.activity.getExternalFilesDir("test-screenshots"))
        check(directory.isDirectory || directory.mkdirs())
        val image =
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
                "Could not capture the actual Android display."
            }
        try {
            File(directory, "${testName.methodName}-$label.png").outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    }

    private fun scores(snapshot: Snapshot): Map<String, Int> {
        val scores = snapshot.game.getJSONObject("score_by_player")
        return scores.keys().asSequence().associateWith { scores.getInt(it) }
    }

    private fun awaitState(description: String, predicate: (DeveloperUiState) -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 15_000) { predicate(state()) }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            val current = state()
            val captureFailure = runCatching { capture("state-timeout") }.exceptionOrNull()
            val skipNodes = compose.onAllNodesWithTag("interests.skip").fetchSemanticsNodes()
            throw AssertionError(
                "Timed out at $description: busy=${current.busy}, quiet=${current.quiet}, " +
                    "interestSetupNeeded=${current.interestSetupNeeded}, " +
                    "configured=${current.children.map { it.interestsConfigured }}, " +
                    "failure=${current.failure}, message=${current.message}, " +
                    "fullUiAllowed=${current.fullUiAllowed}, storageProblem=${current.localStorageProblem}, " +
                    "skip=${skipNodes.map { it.config.toString() + it.boundsInRoot.toString() }}, " +
                    "captureFailure=${captureFailure?.message}",
                error,
            )
        } catch (error: AssertionError) {
            val current = state()
            throw AssertionError(
                "Timed out at $description: busy=${current.busy}, quiet=${current.quiet}, " +
                    "status=${current.snapshot?.status}, phase=${current.snapshot?.phase}",
                error,
            )
        }
        compose.waitForIdle()
    }
}

/** Wait for submitted rendering before taking a display capture; semantics alone can precede it. */
internal fun awaitCommittedUiFrame(activity: android.app.Activity) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val committed = java.util.concurrent.CountDownLatch(1)
    instrumentation.runOnMainSync {
        val view = activity.window.decorView
        if (android.os.Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.postInvalidateOnAnimation()
        } else {
            // API 28/software rendering has no frame-commit callback. Retain display settling below.
            view.postOnAnimation { view.postOnAnimation { committed.countDown() } }
            view.postInvalidateOnAnimation()
        }
    }
    check(committed.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
        "The current UI frame was not submitted before screenshot capture."
    }
}
