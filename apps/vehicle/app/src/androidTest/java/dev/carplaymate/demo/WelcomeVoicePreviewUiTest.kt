package dev.carplaymate.demo

import android.app.Dialog
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI/lifecycle with fake output only; never plays an asset, requests permission, or records. */
@RunWith(AndroidJUnit4::class)
class WelcomeVoicePreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var cleanup: WelcomeVoicePreview? = null
    private var coveringDialog: Dialog? = null
    private class Focus : PlaybackAudioFocus {
        var held = false
        var acquires = 0
        var loss: (() -> Unit)? = null
        override fun acquire(onLost: () -> Unit): Boolean {
            acquires++; held = true; loss = onLost; return true
        }
        override fun release() { held = false }
    }
    private class Player : StoryScenePlayer {
        data class Call(val done: () -> Unit, val fail: () -> Unit, val speech: (Boolean) -> Unit)
        val calls = mutableListOf<Call>()
        var playing = false
        var shutdowns = 0
        override fun play(cue: BundledAudio, narration: BundledAudio, complete: () -> Unit,
                          failure: () -> Unit, speechState: (Boolean) -> Unit) = error("No welcome cue")
        override fun playNarration(narration: BundledAudio, complete: () -> Unit,
                                   failure: () -> Unit, speechState: (Boolean) -> Unit) {
            playing = true; calls += Call(complete, failure, speechState)
        }
        override fun stop() { playing = false }
        override fun shutdown() { playing = false; shutdowns++ }
    }
    private inner class Harness {
        val player = Player()
        val focus = Focus()
        val preview = WelcomeVoicePreview(player, focus) {
            BundledAudio(WelcomeVoicePreview.AUDIO_ID, WelcomeVoicePreview.ASSET, "a".repeat(64), 9000)
        }
        var visible by mutableStateOf(true)
        var ui by mutableStateOf(DeveloperUiState(vehicleAccess = VehicleAccessState.NON_AUTOMOTIVE_DEMO))
        val saved = mutableListOf<Pair<String, String?>>()
        fun render() {
            cleanup = preview
            compose.activity.setContent {
                PlaymateTheme {
                    Surface(Modifier.fillMaxSize(), color = PlaymateColors.Ivory) {
                        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                            val wide = maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp
                            if (visible) {
                                OnuJourneyBackdrop("arrival", Modifier.fillMaxSize())
                                Column(Modifier.fillMaxSize()) {
                                    FamilyDemoNotice()
                                    FamilyHeader("welcome", wide, {}, {})
                                    WelcomeScreen(ui, wide,
                                        Modifier.weight(1f).padding(horizontal = if (wide) 32.dp else 20.dp),
                                        welcomePreview = preview,
                                    ) { first, second ->
                                        assertFalse("Stop must precede the next step.", player.playing || focus.held)
                                        saved += first to second
                                        visible = false
                                        true
                                    }
                                }
                            } else Text("다음 준비 단계")
                        }
                    }
                }
            }
            awaitReady()
        }
        fun awaitReady() {
            compose.waitUntil(5_000) { preview.state.value.available }
            compose.onNodeWithTag("welcome.voicePreview").performScrollTo().assertIsEnabled()
        }
        fun start() {
            compose.onNodeWithTag("welcome.voicePreview").performScrollTo().performClick()
            compose.runOnIdle { assertTrue(player.playing && focus.held) }
        }
        fun quiet() {
            assertFalse(player.playing || focus.held || preview.state.value.speaking)
            assertEquals(WelcomePreviewPhase.IDLE, preview.state.value.phase)
        }
    }

    @After fun closeOnlyThisFixture() {
        instrumentation.runOnMainSync {
            coveringDialog?.dismiss(); coveringDialog = null
            cleanup?.shutdown(); cleanup = null
        }
    }

    @Test fun explicitGreetingCanStopRetryFinishAndLeaveWithoutChangingTheDraft() {
        val h = Harness(); h.render()
        compose.onAllNodesWithTag("welcome.voicePreview").assertCountEquals(1)
        compose.onNodeWithTag("welcome.voicePreview").assertTextEquals("오누 목소리 듣기")
        compose.runOnIdle {
            h.quiet(); assertEquals(0, h.focus.acquires); assertTrue(h.saved.isEmpty())
        }
        capture("welcome-idle")
        h.start()
        compose.onNodeWithTag("welcome.voicePreview").assertTextEquals("준비 중 · 정지")
        compose.runOnIdle { h.player.calls.last().speech(true) }
        compose.onNodeWithTag("welcome.voicePreview").assertTextEquals("듣는 중 · 정지")
        capture("welcome-playing")
        compose.onNodeWithTag("welcome.voicePreview").performClick()
        compose.runOnIdle {
            h.quiet()
            h.player.calls.first().done(); h.player.calls.first().speech(true)
            h.quiet()
        }
        h.start()
        compose.runOnIdle { h.player.calls.last().fail() }
        compose.onNodeWithTag("welcome.voicePreviewError").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("welcome.voicePreview").assertTextEquals("목소리 다시 듣기")
        compose.runOnIdle { assertFalse(h.player.playing || h.focus.held) }
        capture("welcome-retry")
        h.start()
        compose.runOnIdle { h.player.calls.last().done(); h.quiet() }
        compose.onNodeWithTag("welcome.voicePreviewError").assertDoesNotExist()
        compose.onNodeWithTag("welcome.first").performScrollTo().performTextReplacement("별")
        compose.onNodeWithTag("welcome.first").performImeAction()
        h.awaitReady(); h.start()
        compose.onNodeWithTag("welcome.save").performScrollTo().performClick()
        compose.onNodeWithText("다음 준비 단계").assertIsDisplayed()
        compose.runOnIdle {
            h.quiet()
            assertEquals(listOf("별" to null), h.saved)
            assertEquals(1, h.player.shutdowns)
            assertFalse(h.preview.state.value.available)
        }
    }

    @Test fun focusWindowAndBackgroundLossStopWithoutAutomaticRestart() {
        val h = Harness(); h.render(); h.start()
        compose.runOnIdle { h.focus.loss!!.invoke(); h.quiet() }
        assertEquals(1, h.focus.acquires)
        h.start()
        instrumentation.runOnMainSync {
            coveringDialog = Dialog(compose.activity).apply {
                setContentView(TextView(context).apply { text = "다른 창" })
                show()
            }
        }
        compose.waitUntil(5_000) { !h.preview.state.value.available }
        h.quiet()
        instrumentation.runOnMainSync { coveringDialog!!.dismiss(); coveringDialog = null }
        h.awaitReady()
        compose.runOnIdle { h.quiet(); assertEquals(2, h.focus.acquires) }
        h.start()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse(h.preview.state.value.available)
        h.quiet()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        h.awaitReady()
        compose.runOnIdle { h.quiet(); assertEquals(3, h.focus.acquires) }
        h.start()
        compose.runOnIdle { h.ui = h.ui.copy(outputStage = OutputStage.STARTED) }
        compose.waitUntil(5_000) { !h.preview.state.value.available }
        compose.runOnIdle {
            h.quiet()
            h.ui = h.ui.copy(outputStage = null)
        }
        h.awaitReady()
        compose.runOnIdle { h.quiet(); assertEquals(4, h.focus.acquires); h.visible = false }
        compose.onNodeWithText("다음 준비 단계").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, h.player.shutdowns) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        SystemClock.sleep(300)
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(context.getExternalFilesDir(null), "welcome-preview-screenshots")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
