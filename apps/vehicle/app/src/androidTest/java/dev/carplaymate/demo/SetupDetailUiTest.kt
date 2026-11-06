package dev.carplaymate.demo

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Render-only callback fixtures; these do not verify physical storage, speech or a microphone. */
@RunWith(AndroidJUnit4::class)
class SetupDetailUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun failedInterestSaveKeepsBothDraftsAndShowsVisibleRetry() {
        val ui = DeveloperUiState(
            onboarded = true, quiet = true, simulation = true, interactionConfigured = true,
            firstNickname = "별", secondNickname = "달", twoPlayers = true,
            children = listOf(
                ChildProfile("setup-first", "별", setOf("dinosaurs"), true),
                ChildProfile("setup-second", "달", setOf("space"), true),
            ),
        )
        val attempts = mutableListOf<Pair<Set<String>, Set<String>>>()
        var acceptSave = false
        var cancelCalls = 0
        compose.activity.setContent {
            PlaymateTheme {
                Surface(Modifier.fillMaxSize(), color = PlaymateColors.Ivory) {
                    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                        val wide = maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp
                        Column(Modifier.fillMaxSize()) {
                            FamilyDemoNotice()
                            FamilyHeader("interests", wide, {}, {})
                            InterestSetupScreen(
                                ui = ui,
                                wide = wide,
                                modifier = Modifier.weight(1f),
                                editing = true,
                                onSave = { first, second ->
                                    // A false UI callback is intentional, not a physical disk-failure fixture.
                                    attempts.add(first.toSet() to second.toSet())
                                    acceptSave
                                },
                                onSkip = { cancelCalls++ },
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithTag("interests.child.0").assertIsSelected()
        compose.onNodeWithTag("interests.child.1").assertIsNotSelected()
        compose.onNodeWithTag("interest.dinosaurs").performScrollTo().assertIsOn()
        compose.onNodeWithTag("interest.animals").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("interest.animals").assertIsOn()

        compose.onNodeWithTag("interests.child.1").performScrollTo().performClick()
        compose.onNodeWithTag("interests.child.1").assertIsSelected()
        compose.onNodeWithTag("interests.child.0").assertIsNotSelected()
        compose.onNodeWithTag("interest.space").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("interest.ocean").performScrollTo().assertIsOff().performClick()
        // Leave the form at its top: feedback must not require scrolling down to discover it.
        compose.onNodeWithTag("interests.child.1").performScrollTo()
        compose.onNodeWithTag("interests.saveError").assertDoesNotExist()
        assertControlFullyVisible("interests.save", 80.dp)
        compose.onNodeWithTag("interests.save").assertIsEnabled().performClick()

        val expected = setOf("dinosaurs", "animals") to setOf("ocean")
        compose.runOnIdle {
            assertEquals(listOf(expected), attempts)
            assertEquals(0, cancelCalls)
        }
        compose.onNodeWithTag("interests.saveError")
            .assertTextEquals("관심사를 저장하지 못했어요. 다시 눌러 주세요.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertErrorFullyVisible()
        // Do not scroll either fixed footer control into view before checking its bounds.
        assertControlFullyVisible("interests.save", 80.dp)
        assertControlFullyVisible("interests.skip", 64.dp)
        compose.onNodeWithTag("interests.save").assertIsEnabled()
        compose.onNodeWithTag("interests.skip").assertIsEnabled()
        capture("interest-save-error")

        // Switching children only inspects the drafts; it must not silently retry or discard them.
        compose.onNodeWithTag("interests.child.0").performScrollTo().performClick()
        compose.onNodeWithTag("interests.child.0").assertIsSelected()
        compose.onNodeWithTag("interests.child.1").assertIsNotSelected()
        compose.onNodeWithTag("interest.dinosaurs").performScrollTo().assertIsOn()
        compose.onNodeWithTag("interest.animals").performScrollTo().assertIsOn()
        compose.onNodeWithTag("interest.ocean").performScrollTo().assertIsOff()
        compose.onNodeWithTag("interests.child.1").performScrollTo().performClick()
        compose.onNodeWithTag("interests.child.1").assertIsSelected()
        compose.onNodeWithTag("interests.child.0").assertIsNotSelected()
        compose.onNodeWithTag("interest.space").performScrollTo().assertIsOff()
        compose.onNodeWithTag("interest.ocean").performScrollTo().assertIsOn()
        compose.onNodeWithTag("interest.animals").performScrollTo().assertIsOff()
        assertErrorFullyVisible()
        compose.runOnIdle {
            assertEquals("Viewing both drafts must not retry saving.", listOf(expected), attempts)
            acceptSave = true
        }
        compose.onNodeWithTag("interests.saveError").assertIsDisplayed()
        assertControlFullyVisible("interests.save", 80.dp)
        compose.onNodeWithTag("interests.save").performClick()
        compose.onNodeWithTag("interests.saveError").assertDoesNotExist()
        compose.runOnIdle {
            // Acceptance is the fixture callback's result, not a claim that a file was persisted.
            assertEquals(listOf(expected, expected), attempts)
            assertEquals(0, cancelCalls)
        }
    }

    private fun assertErrorFullyVisible() {
        val tag = "interests.saveError"
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        assertEquals("$tag is vertically clipped.", node.size.height.toFloat(), node.boundsInRoot.height, 0.5f)
        assertEquals("$tag is horizontally clipped.", node.size.width.toFloat(), node.boundsInRoot.width, 0.5f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            action -> assertTrue("$tag must expose its rendered text layout.", action(layouts))
        }
        assertTrue(layouts.isNotEmpty())
        for (layout in layouts) {
            val complete = layout.lineCount > 0 && !layout.multiParagraph.didExceedMaxLines &&
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = false) == layout.layoutInput.text.length
            val linesFit = (0 until layout.lineCount).all { line ->
                layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= layout.size.width.toFloat() &&
                    layout.getLineTop(line) >= 0f && layout.getLineBottom(line) <= layout.size.height.toFloat() &&
                    !layout.isLineEllipsized(line)
            }
            assertTrue("$tag clips, ellipsizes, or omits text.",
                complete && linesFit && layout.multiParagraph.height <= layout.size.height.toFloat())
            assertTrue("$tag must retain its readable base font size.", layout.layoutInput.style.fontSize.value >= 20f)
        }
    }

    private fun assertControlFullyVisible(tag: String, minimumHeight: Dp) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed()
            .assertHeightIsAtLeast(minimumHeight).fetchSemanticsNode()
        assertEquals("$tag is vertically clipped.", node.size.height.toFloat(), node.boundsInRoot.height, 0.5f)
        assertEquals("$tag is horizontally clipped.", node.size.width.toFloat(), node.boundsInRoot.width, 0.5f)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        // Only screenshot settling; no audio, input permission or persistence operation is exercised.
        SystemClock.sleep(300)
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(context.getExternalFilesDir(null), "setup-detail-screenshots")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
