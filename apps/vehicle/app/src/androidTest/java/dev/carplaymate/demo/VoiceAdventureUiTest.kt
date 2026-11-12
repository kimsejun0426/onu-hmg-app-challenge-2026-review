package dev.carplaymate.demo

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Render-only voice state fixtures. These tests never open a microphone or verify audio quality. */
@RunWith(AndroidJUnit4::class)
class VoiceAdventureUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun listening(secondNickname: String? = null, firstNickname: String = "별"): DeveloperUiState {
        val library = LocalAdventureLibrary(context)
        val rules = library.rules(DEFAULT_ADVENTURE_ID)
        val game = rules.newGame(playersBody(firstNickname, secondNickname).getJSONArray("players"))
        val snapshot = Snapshot("voice-ui", 1, "turn-1", 1, "ACTIVE", "LISTENING", "window-1",
            null, game, rules.present(game))
        return DeveloperUiState(quiet = false, voiceInput = true, microphoneListening = true,
            captureStage = CaptureStage.LISTENING,
            inputStatus = if (secondNickname == null) "듣는 중 · 선택을 말씀해 주세요."
                else "듣는 중 · 이름과 선택을 말씀해 주세요.",
            snapshot = library.decorate(snapshot), adventures = library.cards(), onboarded = true)
    }

    @Test fun sharedRecommendationNamesBothLongProfilesWithoutTruncation() {
        val first = "반짝이는별빛탐험대장님들"
        val second = "푸른바다속친구탐험대장님"
        assertEquals(12, first.length)
        assertEquals(12, second.length)
        val children = listOf(
            ChildProfile("current-first", first, setOf("animals"), true),
            ChildProfile("current-second", second, setOf("stories"), true),
        )
        // A synthetic multi-topic card exercises different interests, not a catalog change.
        val card = LocalAdventureLibrary(context).cards().first { it.packId == "animal-footprint-garden" }
            .copy(interestTags = setOf("animals", "stories"))
        val recommendations = InterestMatching.recommend(listOf(card), children)
        assertTrue(recommendations.single().shared)
        assertEquals(listOf("current-first", "current-second"), recommendations.single().matchedChildIds)
        val ui = DeveloperUiState(quiet = true, simulation = true, interactionConfigured = true,
            onboarded = true, firstNickname = first, secondNickname = second, twoPlayers = true,
            children = children, adventures = listOf(card), recommendations = recommendations)
        val starts = mutableListOf<String>()
        var resumes = 0
        var libraries = 0
        var settings = 0
        compose.activity.setContent {
            PlaymateTheme { Surface(Modifier.fillMaxSize(), color = PlaymateColors.Ivory) {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    FamilyLanding(ui,
                        maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp,
                        { starts += it }, { resumes++ }, { libraries++ }, { settings++ })
                }
            } }
        }
        compose.onNodeWithTag("recommendation.reason")
            .assertTextEquals("$first·${second}의 관심사를 함께 반영했어요.")
            .performScrollTo().assertIsDisplayed()
        // Long names may wrap and scroll; the authored explanation must not be ellipsized.
        assertTextLayoutComplete("recommendation.reason", 20f)
        compose.onNodeWithTag("family.start").assertIsEnabled()
        assertControlFullyVisible("family.start", 80.dp)
        assertControlFullyVisible("family.libraryLink", 64.dp)
        capture("shared-recommendation")
        // Render fixture callbacks only: neither action starts an adventure or requests audio.
        compose.onNodeWithTag("family.start").performClick()
        compose.onNodeWithTag("family.libraryLink").performClick()
        compose.runOnIdle {
            assertEquals(listOf(card.packId), starts)
            assertEquals(1, libraries)
            assertEquals(0, resumes)
            assertEquals(0, settings)
            assertEquals(false, ui.microphoneListening)
            assertEquals(null, ui.outputStage)
        }
    }

    @Test fun aNewStoryShowsPreparationAndRecoveryInsteadOfThePreviousEnding() {
        val initial = listening()
        val old = requireNotNull(initial.snapshot).let { snapshot ->
            snapshot.copy(status = "ENDED", phase = "ENDED", listenWindowId = null, speechPlan = null,
                game = snapshot.game.copyJson().put("finished", true),
                presentation = snapshot.presentation.copyJson().put("ending_title", "지난 모험의 결말"))
        }
        val state = mutableStateOf(initial.copy(snapshot = old, quiet = true, busy = true,
            microphoneListening = false, captureStage = null,
            newAdventureStart = NewAdventureStart.PREPARING))
        var resumes = 0
        var homes = 0
        compose.activity.setContent {
            PlaymateTheme { Surface(Modifier.fillMaxSize(), color = PlaymateColors.Ivory) {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    ImmersiveAdventure(state.value,
                        maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp,
                        { state.value = state.value.copy(busy = false, newAdventureStart = NewAdventureStart.STOPPED) },
                        { resumes++ }, {}, {}, { homes++ }, {}, { _, _, _ -> })
                }
            } }
        }
        compose.onNodeWithTag("family.newAdventure.preparing").assertIsDisplayed()
        compose.onNodeWithTag("family.ending").assertDoesNotExist()
        compose.onNodeWithText("지난 모험의 결말").assertDoesNotExist()
        assertControlFullyVisible("family.pause", 80.dp)
        capture("new-adventure-preparing")
        compose.onNodeWithTag("family.pause").performClick()
        compose.onNodeWithText("새 모험은 아직 시작 전이에요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("우리 가족 홈으로").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("family.ending").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(newAdventureStart = NewAdventureStart.FAILED,
            failure = true, recoveryCause = RecoveryCause.CONNECTION) }
        compose.onNodeWithText("연결을 다시 확인해 주세요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("준비됐어요 · 이어하기").assertDoesNotExist()
        compose.onNodeWithText("우리 가족 홈으로").performScrollTo().assertIsEnabled()
        assertControlFullyVisible("family.quiet.resume", 72.dp)
        compose.onNodeWithTag("family.quiet.reason").assertTextEquals(
            "새 이야기는 아직 시작 전이에요. 홈에서 이어갈 이야기나 새 모험을 골라 주세요.")
        capture("new-adventure-failed")
        compose.onNodeWithText("우리 가족 홈으로").performClick()
        compose.runOnIdle {
            assertEquals(1, homes)
            assertEquals(0, resumes)
            assertEquals(false, state.value.microphoneListening)
            state.value = initial.copy(microphoneListening = false, newAdventureStart = null)
        }
        compose.onNodeWithTag("family.voice.panel").assertIsDisplayed()
        compose.onNodeWithTag("family.newAdventure.preparing").assertDoesNotExist()
        // An ordinary completed story still has its ending once no new attempt is pending.
        compose.runOnIdle { state.value = state.value.copy(snapshot = old, quiet = true) }
        compose.onNodeWithTag("family.ending").assertIsDisplayed()
        compose.onNodeWithText("지난 모험의 결말").performScrollTo().assertIsDisplayed()
    }

    @Test fun savedParticipantsRemainReadableBeforeResumingWithADifferentCurrentFamily() {
        val card = LocalAdventureLibrary(context).cards().first { it.packId == "animal-footprint-garden" }
        val oldNames = listOf("반짝이는별빛탐험대장님들", "푸른바다속친구탐험대장님")
        val saved = SavedAdventure("saved-two-children", card.packId, card.title, card.artKey,
            null, 1, card.totalChoices, oldNames)
        val current = listOf(ChildProfile("new-family-child", "새친구", setOf("animals"), true))
        val ui = DeveloperUiState(quiet = true, simulation = true, onboarded = true,
            interactionConfigured = true, firstNickname = "새친구", children = current,
            adventures = listOf(card), selectedPackId = card.packId, savedAdventures = listOf(saved))
        val detail = mutableStateOf(false)
        val resumes = mutableListOf<String>()
        val restarts = mutableListOf<String>()
        var starts = 0
        var settings = 0
        compose.activity.setContent {
            PlaymateTheme { Surface(Modifier.fillMaxSize(), color = PlaymateColors.Ivory) {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    val wide = maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp
                    // Include the same fixed notice/header as FamilyScreen, not extra body height.
                    Column(Modifier.fillMaxSize()) {
                        FamilyDemoNotice()
                        FamilyHeader(if (detail.value) "detail" else "home", wide,
                            onHome = { detail.value = false }, onSettings = { settings++ })
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (detail.value) AdventureDetail(ui, wide, { starts++ }, { resumes += it },
                                { restarts += it }, { settings++ })
                            else FamilyLanding(ui, wide, { starts++ }, { resumes += it },
                                { detail.value = true }, { settings++ })
                        }
                    }
                }
            } }
        }
        val names = "함께하던 친구 · ${oldNames.joinToString(" · ")}"
        compose.onNodeWithText("일반 기기 체험 · 차량 상태 미연결").assertIsDisplayed()
        compose.onNodeWithTag("family.settingsNav").assertIsDisplayed()
        compose.onNodeWithTag("recommendation.participants").assertTextEquals(names)
        assertTextFullyVisible("recommendation.participants", 20f)
        assertControlFullyVisible("family.start", 80.dp)
        assertControlFullyVisible("family.libraryLink", 64.dp)
        // These captures and checks precede any scrolling: names must be visible before resuming.
        capture("saved-participants-home")
        compose.onNodeWithTag("recommendation.participants.changedFamily").performScrollTo().assertIsDisplayed()
        assertTextLayoutComplete("recommendation.participants.changedFamily", 20f)
        assertTextFullyVisible("recommendation.participants", 20f)
        compose.onNodeWithTag("family.start").performClick()
        compose.onNodeWithTag("family.libraryLink").performClick()
        compose.onNodeWithTag("family.homeNav").assertIsDisplayed()
        compose.onNodeWithTag("adventure.participants").assertTextEquals(names)
        assertTextFullyVisible("adventure.participants", 20f)
        assertControlFullyVisible("adventure.start", 80.dp)
        assertControlFullyVisible("adventure.restart", 64.dp)
        capture("saved-participants-detail")
        compose.onNodeWithTag("adventure.participants.changedFamily").performScrollTo().assertIsDisplayed()
        assertTextLayoutComplete("adventure.participants.changedFamily", 20f)
        assertTextFullyVisible("adventure.participants", 20f)
        compose.onNodeWithTag("adventure.start").performClick()
        compose.onNodeWithTag("adventure.restart").performClick()
        compose.runOnIdle {
            assertEquals(listOf(saved.sessionId, saved.sessionId), resumes)
            assertEquals(listOf(card.packId), restarts)
            assertEquals(0, starts)
            assertEquals(0, settings)
            assertEquals(oldNames, saved.participants)
            assertEquals(current, ui.children)
            assertEquals(false, ui.microphoneListening)
        }
    }

    @Test fun companionRemainsWhilePreparingListeningAndProcessing() {
        val initial = listening()
        var resumes = 0
        val state = mutableStateOf(initial.copy(microphoneListening = false, captureStage = null,
            inputStatus = "마이크 준비 중 · ‘듣는 중’ 표시 뒤 말씀해 주세요."))
        compose.activity.setContent {
            PlaymateTheme { Surface(Modifier.fillMaxSize(), color = PlaymateColors.Ivory) {
                // Active voice play omits the navigation header, as in FamilyScreen.
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    ImmersiveAdventure(state.value,
                        maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp,
                        {}, { resumes++ }, {}, {}, {}, {}, { _, _, _ -> })
                }
            } }
        }
        compose.onNodeWithTag("family.voice.onu").assertIsDisplayed()
        compose.onNodeWithContentDescription(OnuMood.LISTENING.description).assertDoesNotExist()
        capture("input-preparing")
        compose.runOnIdle { state.value = initial }
        compose.onNodeWithContentDescription(OnuMood.LISTENING.description).assertIsDisplayed()
        compose.onNodeWithTag("family.pause").assertIsDisplayed().assertHeightIsAtLeast(80.dp)
        compose.onNodeWithTag("family.voice.onu").assertHeightIsAtLeast(100.dp)
        compose.onNodeWithTag("family.voice.examples")
            .assertTextEquals("“물가” 또는 “숲”").assertHasNoClickAction()
        assertControlFullyVisible("family.voice.transcript")
        assertControlFullyVisible("family.voice.manual")
        compose.onNodeWithTag("family.voice.status").assertTextEquals(initial.inputStatus)
        // A glance should connect the question with its spoken answers, without a blank panel between.
        val question = compose.onNodeWithTag("family.voice.question").fetchSemanticsNode().boundsInRoot
        val examples = compose.onNodeWithTag("family.voice.examples").fetchSemanticsNode().boundsInRoot
        val maximumGap = with(compose.density) { 32.dp.toPx() }
        assertTrue("Spoken answer examples are too far from the question.",
            examples.top >= question.bottom && examples.top - question.bottom <= maximumGap)
        val questionLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("family.voice.question").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            action -> assertTrue(action(questionLayouts))
        }
        assertTrue(questionLayouts.isNotEmpty())
        for (layout in questionLayouts) for (word in listOf("물가에서", "숲에서")) {
            val start = layout.layoutInput.text.text.indexOf(word)
            assertTrue("Expected a complete authored place name.", start >= 0)
            assertEquals("The Korean place name is broken in the middle: $word",
                layout.getLineForOffset(start), layout.getLineForOffset(start + word.length - 1))
        }
        capture("listening")
        compose.runOnIdle { state.value = initial.copy(microphoneListening = false,
            captureStage = CaptureStage.PROCESSING, inputStatus = "대답을 확인하고 있어요") }
        compose.onNodeWithContentDescription(OnuMood.THINKING.description).assertIsDisplayed()
        compose.onNodeWithContentDescription(OnuMood.LISTENING.description).assertDoesNotExist()
        capture("processing")
        compose.runOnIdle { state.value = initial.copy(quiet = true, microphoneListening = false,
            captureStage = null, speechReady = true) }
        compose.onNodeWithTag("family.pauseStatus").assertDoesNotExist()
        compose.onNodeWithTag("family.pause").assertDoesNotExist()
        compose.onNodeWithTag("family.quiet.resume").performScrollTo().assertIsEnabled()
        assertControlFullyVisible("family.quiet.resume", 80.dp)
        compose.runOnIdle { assertEquals(0, resumes) }
        capture("ordinary-rest")
        compose.onNodeWithTag("family.quiet.resume").performClick()
        compose.runOnIdle { assertEquals(1, resumes) }
    }

    @Test fun touchAssistanceIsExplicitAndSubmitsOnlyTheChosenWord() {
        val answers = mutableListOf<String>()
        val initial = listening("달")
        val longDialogue = "별, 물가에서 반짝이는 작은 흔적을 살펴볼까, 나무가 모여 있는 숲에서 사라진 알 모형의 흔적을 찾아볼까?"
        val state = mutableStateOf(initial.copy(authoredDialogue = longDialogue))
        compose.activity.setContent {
            PlaymateTheme { Surface(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    ImmersiveAdventure(
                        ui = state.value,
                        wide = maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp,
                        onPause = {},
                        onResume = {},
                        onChoose = { answers += it },
                        onReadDone = {},
                        onHome = {},
                        onSettings = {},
                        onFeedback = { _, _, _ -> },
                    )
                }
            } }
        }
        compose.onNodeWithTag("family.voice.status").assertTextEquals(initial.inputStatus)
        compose.onNodeWithTag("family.voice.question").assertTextEquals(longDialogue)
        compose.onNodeWithTag("family.voice.examples")
            .assertTextEquals("“별, 물가” 또는 “별, 숲”")
        compose.onNodeWithTag("family.voice.assist.0").assertDoesNotExist()
        // No scroll first: the answer examples, question, status, help and pause all fit
        // with two children and a long question in the three supported layout fixtures.
        assertTextFullyVisible("family.voice.examples", 24f)
        assertTextFullyVisible("family.voice.question", 30f)
        assertTextFullyVisible("family.voice.status", 20f)
        assertControlFullyVisible("family.voice.transcript")
        assertControlFullyVisible("family.voice.manual")
        assertControlFullyVisible("family.pause", 80.dp)
        capture("two-child-examples")
        compose.onNodeWithTag("family.voice.transcript").performClick()
        compose.onNodeWithTag("family.voice.transcriptText").assertTextEquals(longDialogue)
            .performScrollTo().assertIsDisplayed()
        // Reading the complete dialogue must not move the speech examples off screen.
        assertTextFullyVisible("family.voice.examples", 24f)
        assertControlFullyVisible("family.voice.transcript")
        compose.onNodeWithTag("family.voice.transcript").performClick()
        compose.onNodeWithTag("family.voice.manual").performClick()
        compose.onNodeWithTag("family.voice.assist.1").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("숲"), answers) }
        compose.onNodeWithTag("family.voice.assist.1").assertDoesNotExist()
        assertControlFullyVisible("family.voice.manual")
        assertControlFullyVisible("family.pause", 80.dp)

        // The supported 12-character nickname must wrap, never silently disappear. The
        // complete question may scroll in this denser case, but both examples stay pinned.
        val longName = "반짝이는초록숲속작은다람쥐".take(12)
        val longNamedDialogue = longDialogue.replace("별,", "$longName,")
        compose.runOnIdle {
            state.value = listening("달", firstNickname = longName).copy(
                authoredDialogue = longNamedDialogue,
            )
        }
        compose.onNodeWithTag("family.voice.examples")
            .assertTextEquals("“$longName, 물가” 또는 “$longName, 숲”")
            .assertHasNoClickAction()
        compose.onNodeWithTag("family.turnTitle").assertTextEquals("${longName}의 차례예요")
        assertTextFullyVisible("family.turnTitle", 26f)
        assertControlFullyVisible("family.repeat", 64.dp)
        assertControlFullyVisible("family.help", 64.dp)
        assertTextFullyVisible("family.voice.examples", 24f)
        assertTextFullyVisible("family.voice.status", 20f)
        assertTextLayoutComplete("family.voice.question", 30f)
        // Even a long name leaves at least two large lines available before scrolling.
        compose.onNodeWithTag("family.voice.question").assertHeightIsAtLeast(96.dp)
        capture("long-name-question")
        assertControlFullyVisible("family.voice.manual")
        assertControlFullyVisible("family.voice.transcript")
        assertControlFullyVisible("family.pause", 80.dp)
        compose.onNodeWithTag("family.voice.transcript").performClick()
        compose.onNodeWithTag("family.voice.transcriptText").assertTextEquals(longNamedDialogue)
            .performScrollTo().assertIsDisplayed()
        assertTextFullyVisible("family.voice.examples", 24f)
    }

    @Test fun completeAuthoredDialogueRemainsAvailableBeyondTheShortCaption() {
        val state = listening()
        val complete = state.snapshot!!.presentation.getString("prompt")
        compose.activity.setContent {
            PlaymateTheme { Surface(Modifier.fillMaxSize()) {
                VoiceAdventurePanel(state, "dinosaurs", {})
            } }
        }
        compose.onNodeWithTag("family.voice.transcript").assertIsDisplayed().performClick()
        compose.onNodeWithTag("family.voice.transcriptText").assertTextEquals(complete)
        compose.onNodeWithTag("family.voice.transcript").assertIsDisplayed().performClick()
        compose.onNodeWithTag("family.voice.onu").assertIsDisplayed()
    }

    @Test fun voiceExamplesFollowTheSavedSessionPlayerAcrossTurns() {
        // The saved two-child session, not the edited one-child setup, owns these turns.
        val initial = listening("달").copy(twoPlayers = false, firstNickname = "새친구")
        val state = mutableStateOf(initial)
        compose.activity.setContent {
            PlaymateTheme { Surface(Modifier.fillMaxSize()) {
                VoiceAdventurePanel(state.value, "dinosaurs", {})
            } }
        }
        compose.onNodeWithTag("family.voice.examples")
            .assertTextEquals("“별, 물가” 또는 “별, 숲”")
        val before = requireNotNull(initial.snapshot)
        val rules = LocalAdventureLibrary(context).rulesFor(before.game)
        val next = rules.applyAction(before.game, before.game.getString("expected_player_id"), "RIVER")
        val presentation = rules.present(next)
        compose.runOnIdle {
            state.value = initial.copy(snapshot = before.copy(
                turnId = "turn-2", game = next, presentation = presentation,
            ))
        }
        compose.onNodeWithTag("family.voice.examples")
            .assertTextEquals("“달, 바퀴 자국” 또는 “달, 상자 표지”")
        // These are accepted speech examples, not only visually plausible strings.
        for (label in presentation.getJSONArray("actions").objects().map { it.getString("label") }) {
            val parsed = rules.interpret("달, $label", next)
            assertEquals("ACTION", parsed.intent)
            assertEquals(next.getString("expected_player_id"), parsed.declaredPlayerId)
            assertEquals(false, parsed.needsClarification)
        }
    }

    @Test fun nameConfirmationShowsTheAuthoredRequestWithoutChoiceCards() {
        val initial = listening()
        val presentation = initial.snapshot!!.presentation.copyJson().put("input_kind", "player_name")
        val state = initial.copy(authoredDialogue = "누구의 선택인지 이름만 말해 줄래?",
            snapshot = initial.snapshot.copy(presentation = presentation))
        compose.activity.setContent {
            PlaymateTheme { Surface(Modifier.fillMaxSize()) {
                VoiceAdventurePanel(state, "dinosaurs", {})
            } }
        }
        compose.onNodeWithTag("family.voice.question").assertTextEquals("누구의 선택인지 이름만 말해 줄래?")
        compose.onNodeWithTag("family.voice.examples").assertDoesNotExist()
        compose.onNodeWithTag("family.voice.manual").assertDoesNotExist()
    }

    @Test fun interruptedVoiceExplainsTheCauseAndKeepsRecoveryActionsReachable() {
        val initial = listening().copy(quiet = true, microphoneListening = false,
            captureStage = null, speechReady = true, failure = true,
            recoveryCause = RecoveryCause.INPUT_LANGUAGE, inputStatus = "PRIVATE_PROVIDER_SENTINEL")
        val state = mutableStateOf(initial)
        var resumes = 0
        var settings = 0
        var homes = 0
        compose.activity.setContent {
            PlaymateTheme { Surface(Modifier.fillMaxSize(), color = PlaymateColors.Ivory) {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    ImmersiveAdventure(state.value,
                        maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp,
                        {}, { resumes++ }, {}, {}, { homes++ }, { settings++ }, { _, _, _ -> })
                }
            } }
        }
        compose.onNodeWithText("한국어 음성 입력이 필요해요").assertIsDisplayed()
        assertTextFullyVisible("family.quiet.title", 36f)
        assertTitleHasNoOrphanedLastCharacter()
        compose.onNodeWithText("PRIVATE_PROVIDER_SENTINEL").assertDoesNotExist()
        capture("recovery")
        compose.onNodeWithTag("family.quiet.settings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, settings); assertEquals(0, resumes) }
        for ((cause, title) in listOf(
            RecoveryCause.AUDIO_FOCUS to "소리 재생을 잠시 쉬어요",
            RecoveryCause.OUTPUT_FAILED to "이야기 소리가 멈췄어요",
            RecoveryCause.CONNECTION to "연결을 다시 확인해 주세요",
        )) {
            compose.runOnIdle { state.value = initial.copy(recoveryCause = cause) }
            compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("family.quiet.resume").performScrollTo()
            assertControlFullyVisible("family.quiet.resume", 72.dp)
            compose.onNodeWithTag("family.quiet.resume").assertIsEnabled()
        }
        compose.onNodeWithTag("family.quiet.settings").assertDoesNotExist()
        compose.onNodeWithTag("family.quiet.resume").performClick()
        compose.runOnIdle { assertEquals(1, resumes) }
        // A newer output problem must still offer a way out of a connection interruption.
        compose.runOnIdle { state.value = initial.copy(recoveryCause = RecoveryCause.CONNECTION,
            speechReady = false, speechProblem = RecoveryCause.OUTPUT_LANGUAGE) }
        compose.onNodeWithTag("family.quiet.resume").assertDoesNotExist()
        compose.onNodeWithTag("family.quiet.outputProblem").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("family.quiet.settings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, settings); assertEquals(1, resumes) }
        // Replaced sessions cannot be repaired by repeatedly resuming the same session.
        compose.runOnIdle { state.value = state.value.copy(recoveryCause = RecoveryCause.SESSION_REPLACED) }
        compose.onNodeWithText("최근 이야기를 이어가 주세요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("family.quiet.resume").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, homes); assertEquals(1, resumes) }
        // Failed first creation has no checkpoint to resume. Home remains reachable even
        // without TTS; an existing checkpoint must keep the ordinary explicit resume route.
        for (cause in listOf(RecoveryCause.CONNECTION, RecoveryCause.PROGRESS)) {
            compose.runOnIdle { state.value = initial.copy(snapshot = null, hasSavedSession = false,
                speechReady = false, recoveryCause = cause) }
            compose.onNodeWithTag("family.quiet.reason").performScrollTo().assertTextEquals(
                "아직 이어갈 이야기가 없어요. 홈에서 연결과 이용 방식을 확인한 뒤 다시 시작해 주세요.")
            compose.onNodeWithTag("family.quiet.resume").performScrollTo().assertIsEnabled().performClick()
        }
        compose.runOnIdle { assertEquals(3, homes); assertEquals(1, resumes) }
        compose.runOnIdle { state.value = initial.copy(snapshot = null, hasSavedSession = true,
            recoveryCause = RecoveryCause.CONNECTION) }
        compose.onNodeWithTag("family.quiet.reason").performScrollTo().assertTextEquals(
            RecoveryCause.CONNECTION.notice().detail)
        compose.onNodeWithTag("family.quiet.resume").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(3, homes); assertEquals(2, resumes) }
    }

    @Test fun unavailableInputOffersSettingsUntilTheSelectedModeCanResume() {
        val initial = listening().copy(quiet = true, microphoneListening = false, captureStage = null,
            speechReady = true, failure = true, recoveryCause = RecoveryCause.OUTPUT_FAILED,
            voiceInputProblem = "PRIVATE_CAPABILITY_SENTINEL")
        val state = mutableStateOf(initial)
        var resumes = 0
        var settings = 0
        var homes = 0
        compose.activity.setContent {
            PlaymateTheme { Surface(Modifier.fillMaxSize(), color = PlaymateColors.Ivory) {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    ImmersiveAdventure(state.value,
                        maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp,
                        {}, { resumes++ }, {}, {}, { homes++ }, { settings++ }, { _, _, _ -> })
                }
            } }
        }
        // Last interruption was output, but current input capability controls the action.
        compose.onNodeWithTag("family.quiet.resume").assertDoesNotExist()
        compose.onNodeWithText("PRIVATE_CAPABILITY_SENTINEL").assertDoesNotExist()
        // The current obstacle and its action must be fully visible without first scrolling.
        compose.onNodeWithTag("family.quiet.settings").assertTextEquals("음성 입력 설정 확인하기")
            .assertIsEnabled()
        assertTextFullyVisible("family.quiet.title", 36f)
        assertTitleHasNoOrphanedLastCharacter()
        compose.onNodeWithTag("family.quiet.inputProblem").assertTextEquals("지금은 음성으로 대답을 받을 수 없어요.")
        assertTextFullyVisible("family.quiet.inputProblem", 26f)
        assertControlFullyVisible("family.quiet.settings", 80.dp)
        capture("recovery-input-unavailable")
        compose.onNodeWithTag("family.quiet.settings").performClick()
        compose.runOnIdle { assertEquals(1, settings); assertEquals(0, resumes) }
        compose.onNodeWithTag("family.quiet.inputProblem").performScrollTo().assertIsDisplayed()

        // Repair only restores the explicit resume action; it never starts the session.
        compose.runOnIdle { state.value = initial.copy(voiceInputProblem = null) }
        compose.onNodeWithText("이야기 소리가 멈췄어요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("family.quiet.inputProblem").assertDoesNotExist()
        compose.onNodeWithTag("family.quiet.resume").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, resumes) }
        capture("recovery-input-ready")
        compose.onNodeWithTag("family.quiet.resume").performClick()
        compose.runOnIdle { assertEquals(1, resumes) }

        compose.runOnIdle { state.value = initial.copy(speechReady = false) }
        compose.onNodeWithTag("family.quiet.settings").assertTextEquals("이용 방식 확인하기")
            .performScrollTo().performClick()
        compose.onNodeWithTag("family.quiet.resume").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, settings); assertEquals(1, resumes) }

        // A deliberately selected reading/listen-and-select mode ignores stale input problems.
        compose.runOnIdle { state.value = initial.copy(simulation = true, voiceInput = false, speechReady = false) }
        compose.onNodeWithTag("family.quiet.inputProblem").assertDoesNotExist()
        compose.onNodeWithTag("family.quiet.outputProblem").assertDoesNotExist()
        compose.onNodeWithTag("family.quiet.resume").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { state.value = initial.copy(voiceInput = false) }
        compose.onNodeWithTag("family.quiet.resume").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(3, resumes) }

        // Missing checkpoints still lead home, even if both audio capabilities are unavailable.
        compose.runOnIdle { state.value = initial.copy(snapshot = null, hasSavedSession = false, speechReady = false) }
        compose.onNodeWithTag("family.quiet.resume").assertTextEquals("우리 가족 홈으로")
            .performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, homes); assertEquals(3, resumes) }
    }

    @Test fun recoveryHistoryDoesNotPreventExplicitPreparationOfReadingMode() {
        val state = mutableStateOf(listening().copy(quiet = true, microphoneListening = false,
            captureStage = null, speechReady = false, failure = true,
            interactionConfigured = true, recoveryCause = RecoveryCause.OUTPUT_LANGUAGE))
        var done = 0
        compose.activity.setContent {
            PlaymateTheme { Surface(Modifier.fillMaxSize(), color = PlaymateColors.Ivory) {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    InteractionSetupScreen(state.value,
                        maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp,
                        null, {}, {}, {
                            state.value = state.value.copy(simulation = true, voiceInput = false, failure = false)
                        }, {}, {}, { done++ })
                }
            } }
        }
        compose.onNodeWithText("한국어 안내 음성이 필요해요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("읽으며 체험", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("interaction.lastRecovery").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("마지막으로 멈춘 이유").assertIsDisplayed()
        capture("recovery-settings")
        compose.onNodeWithTag("interaction.done").assertIsDisplayed().assertIsEnabled()
        assertControlFullyVisible("interaction.done", 80.dp)
        compose.runOnIdle { assertEquals(0, done) }
        compose.onNodeWithTag("interaction.done").performClick()
        compose.runOnIdle { assertEquals(1, done) }
    }

    @Test fun outputPreparationAndStartedPlaybackHaveDistinctPresence() {
        val initial = listening()
        val spoken = "별, 물가에서 흔적을 살펴볼까, 숲으로 가 볼까?"
        val plan = SpeechPlan("presence", "turn-1", 1,
            listOf(SpeechSegment("question", spoken, true)))
        val state = mutableStateOf(initial.copy(microphoneListening = false, captureStage = null,
            outputStage = OutputStage.PREPARING, authoredDialogue = spoken,
            snapshot = initial.snapshot!!.copy(phase = "PROMPTING", listenWindowId = null, speechPlan = plan)))
        compose.activity.setContent {
            PlaymateTheme { Surface(Modifier.fillMaxSize(), color = PlaymateColors.Ivory) {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    ImmersiveAdventure(state.value,
                        maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp,
                        {}, {}, {}, {}, {}, {}, { _, _, _ -> })
                }
            } }
        }
        compose.onNodeWithTag("family.voice.status").assertTextEquals("오누가 목소리를 준비하고 있어요")
        compose.onNodeWithContentDescription(OnuMood.THINKING.description).assertIsDisplayed()
        compose.onNodeWithContentDescription(OnuMood.STORYTELLING.description).assertDoesNotExist()
        compose.onNodeWithContentDescription(OnuMood.LISTENING.description).assertDoesNotExist()
        assertControlFullyVisible("family.pause", 80.dp)
        capture("output-preparing")
        compose.runOnIdle { state.value = state.value.copy(outputStage = OutputStage.STARTED) }
        compose.onNodeWithTag("family.voice.status").assertTextEquals("오누가 이야기를 들려줘요")
        compose.onNodeWithContentDescription(OnuMood.STORYTELLING.description).assertIsDisplayed()
        compose.onNodeWithContentDescription(OnuMood.LISTENING.description).assertDoesNotExist()
        assertControlFullyVisible("family.pause", 80.dp)
        capture("output-started")
    }

    private fun assertTitleHasNoOrphanedLastCharacter() {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("family.quiet.title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            action -> assertTrue(action(layouts))
        }
        assertTrue(layouts.isNotEmpty())
        for (layout in layouts) if (layout.lineCount > 1) {
            val last = layout.lineCount - 1
            val text = layout.layoutInput.text.text.substring(
                layout.getLineStart(last), layout.getLineEnd(last, visibleEnd = true)).trim()
            assertTrue("The recovery title leaves its final Korean character on a line alone.", text.length > 1)
        }
    }

    private fun assertTextFullyVisible(tag: String, minimumSp: Float) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        assertEquals("$tag is vertically clipped.", node.size.height.toFloat(),
            node.boundsInRoot.height, 0.5f)
        assertEquals("$tag is horizontally clipped.", node.size.width.toFloat(),
            node.boundsInRoot.width, 0.5f)
        assertTextLayoutComplete(tag, minimumSp)
    }

    private fun assertTextLayoutComplete(tag: String, minimumSp: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            action -> assertTrue("$tag must expose its rendered text layout.", action(layouts))
        }
        assertTrue(layouts.isNotEmpty())
        for (layout in layouts) {
            // As in FamilyUiTest, inspect the actual glyph lines: String semantics may
            // rebuild the paragraph at a wider width than the measured Text node.
            val complete = layout.lineCount > 0 && !layout.multiParagraph.didExceedMaxLines &&
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = false) == layout.layoutInput.text.length
            val linesFit = (0 until layout.lineCount).all { line ->
                layout.getLineLeft(line) >= 0f &&
                    layout.getLineRight(line) <= layout.size.width.toFloat() &&
                    layout.getLineTop(line) >= 0f &&
                    layout.getLineBottom(line) <= layout.size.height.toFloat() &&
                    !layout.isLineEllipsized(line)
            }
            assertTrue("$tag clips, ellipsizes, or omits text.",
                complete && linesFit && layout.multiParagraph.height <= layout.size.height.toFloat())
            assertTrue("$tag must retain the readable base font size.",
                layout.layoutInput.style.fontSize.value >= minimumSp)
        }
    }

    private fun assertControlFullyVisible(tag: String, minimumHeight: Dp = 56.dp) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed()
            .assertHeightIsAtLeast(minimumHeight).fetchSemanticsNode()
        assertEquals("$tag is vertically clipped.", node.size.height.toFloat(),
            node.boundsInRoot.height, 0.5f)
        assertEquals("$tag is horizontally clipped.", node.size.width.toFloat(),
            node.boundsInRoot.width, 0.5f)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        awaitCommittedUiFrame(compose.activity)
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        // Wait for the display frame after semantic assertions, as in FamilyUiTest.
        SystemClock.sleep(300)
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val dir = File(context.getExternalFilesDir(null), "voice-ui-screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
