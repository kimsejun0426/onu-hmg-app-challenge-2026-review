package dev.carplaymate.demo

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Render-only reported-state fixtures: no speaker, microphone, recognition or child data.
 * The clock and background are frozen between pixel captures. Assertions describe visible
 * activity/stillness, not resource IDs, frame numbers, mouth geometry or animation cadence.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OnuActingUiTest {
    private val motionScale = TestMotionScale()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>(effectContext = motionScale)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun reportedSpeechMovesButGapsQuietReadingAndBackgroundStayStill() {
        compose.mainClock.autoAdvance = false
        val speaking = speakingFixture()
        val ui = mutableStateOf(speaking)
        val owner = resumedOwner()
        compose.activity.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PlaymateTheme {
                    Box(Modifier.fillMaxSize().background(PlaymateColors.Ivory), Alignment.Center) {
                        VoiceCompanionStage(ui.value, "dinosaurs", Modifier.size(320.dp))
                        Text("제어된 발화 화면 · 소리·마이크 없음",
                            Modifier.align(Alignment.BottomCenter).padding(16.dp),
                            color = PlaymateColors.Muted)
                    }
                }
            }
        }
        advance(400) // Settle the separate state crossfade before comparing the character.
        assertAnimated("family.voice.stage")
        description(OnuMood.STORYTELLING)
        presentReviewIfRequested()

        // A finished spoken part can leave the complete plan running through a cue or gap.
        compose.runOnUiThread { ui.value = speaking.copy(narrationActive = false) }
        advance(400)
        assertStill("family.voice.stage", "A spoken-part gap must not keep moving the mouth")
        description(OnuMood.STORYTELLING)

        for ((label, stopped, mood) in listOf(
            Triple("quiet", speaking.copy(quiet = true), OnuMood.RESTING),
            Triple("reading", speaking.copy(simulation = true), OnuMood.READING),
            Triple("preparing", speaking.copy(outputStage = OutputStage.PREPARING), OnuMood.THINKING),
            Triple("unavailable full UI", speaking.copy(fullUiAllowed = false), OnuMood.STORYTELLING),
        )) {
            // Start each transition from an active spoken state, not from another stopped state.
            compose.runOnUiThread { ui.value = speaking }
            advance(400)
            compose.runOnUiThread { ui.value = stopped }
            advance(400)
            assertStill("family.voice.stage", "$label must remain still after its finite state transition")
            description(mood)
        }

        compose.runOnUiThread { ui.value = speaking }
        advance(400)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        advance(400)
        assertStill("family.voice.stage", "A non-resumed owner must not keep acting")
        description(OnuMood.STORYTELLING)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        advance(400)
        assertAnimated("family.voice.stage")
        compose.runOnUiThread { ui.value = speaking.copy(narrationActive = false) }
        advance(400)
    }

    @Test fun reducedMotionOptOutAndFinishedGreetingPreserveStillAccessibleArtwork() {
        compose.mainClock.autoAdvance = false
        motionScale.scaleFactor = 0f
        val settings = mutableStateOf(Acting(OnuMood.STORYTELLING, motion = true, narration = true))
        val owner = resumedOwner()
        compose.activity.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PlaymateTheme {
                    Box(Modifier.fillMaxSize().background(PlaymateColors.Ivory), Alignment.Center) {
                        val state = settings.value
                        OnuCompanion(
                            Modifier.size(320.dp).background(PlaymateColors.Ivory).testTag("acting.character"),
                            state.mood, motion = state.motion, narrationActive = state.narration,
                        )
                    }
                }
            }
        }
        advance(400)
        assertStill("acting.character", "Duration scale zero must keep reported speech still")
        description(OnuMood.STORYTELLING)

        compose.runOnUiThread {
            motionScale.scaleFactor = 1f
            settings.value = Acting(OnuMood.STORYTELLING, motion = false, narration = true)
        }
        advance(400)
        assertStill("acting.character", "Existing callers that did not opt into motion must stay still")
        description(OnuMood.STORYTELLING)

        compose.runOnUiThread { settings.value = Acting(OnuMood.STORYTELLING, motion = true, narration = true) }
        advance(400)
        assertAnimated("acting.character")
        compose.runOnUiThread { motionScale.scaleFactor = 0f }
        advance(400)
        assertStill("acting.character", "Enabling reduced motion during speech must stop the acting")
        description(OnuMood.STORYTELLING)

        compose.runOnUiThread {
            motionScale.scaleFactor = 1f
            settings.value = Acting(OnuMood.READY, motion = true, narration = false)
        }
        // The greeting must settle; it must not become an attention-seeking idle loop.
        advance(2_000)
        assertStill("acting.character", "The greeting must finish and stay still while waiting")
        description(OnuMood.READY)
    }

    private fun speakingFixture(): DeveloperUiState {
        val library = LocalAdventureLibrary(context)
        val rules = library.rules(DEFAULT_ADVENTURE_ID)
        val game = rules.newGame(playersBody("시험별", null).getJSONArray("players"))
        val snapshot = Snapshot("acting-ui", 1, "turn-1", 1, "ACTIVE", "PROMPTING", null,
            null, game, rules.present(game))
        return DeveloperUiState(quiet = false, busy = false, simulation = false, voiceInput = true,
            microphoneListening = false, outputStage = OutputStage.STARTED, narrationActive = true,
            snapshot = library.decorate(snapshot), onboarded = true, fullUiAllowed = true)
    }

    private fun resumedOwner(): FixtureLifecycle {
        lateinit var owner: FixtureLifecycle
        compose.runOnUiThread {
            owner = FixtureLifecycle()
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        return owner
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        // Android drawing is not driven by MainTestClock; wait for that draw before capture.
        compose.waitForIdle()
    }

    private fun pixels(tag: String): Pixels {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val values = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(values, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Pixels(bitmap.width, bitmap.height, values)
    }

    private fun assertAnimated(tag: String) {
        val first = pixels(tag)
        var changed = false
        // Broad sampling window: no assertion depends on the implementation's pose sequence.
        repeat(5) { sample ->
            advance(137)
            val next = pixels(tag)
            changed = changed || !first.sameAs(next)
            val bodyStill = first.lowerBodySameAs(next)
            val evidence = if (bodyStill) "" else runCatching {
                writePixelDiagnostic(tag, sample + 1, first, next)
            }.fold({ " · diagnostic: $it" }, { " · diagnostic write failed: ${it.javaClass.simpleName}" })
            assertTrue("Spoken facial acting must keep the lower body and its background still$evidence",
                bodyStill)
        }
        assertTrue("Reported active narration must produce visibly different character frames", changed)
    }

    /** Failure evidence only: assertions and captured pixel values are left unchanged. */
    private fun writePixelDiagnostic(tag: String, sample: Int, before: Pixels, after: Pixels): String {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "acting-ui-diagnostics")
        check(directory.isDirectory || directory.mkdirs())
        val prefix = "lower-body-${tag.replace('.', '-')}-${SystemClock.uptimeMillis()}"
        fun save(frame: Pixels, suffix: String) {
            val bitmap = Bitmap.createBitmap(frame.values, frame.width, frame.height, Bitmap.Config.ARGB_8888)
            try {
                File(directory, "$prefix-$suffix.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        save(before, "before")
        save(after, "after")
        val report = JSONObject()
            .put("tag", tag).put("sample_index", sample)
            .put("device_time_ms", System.currentTimeMillis())
            .put("virtual_time_ms", compose.mainClock.currentTime)
            .put("before_width", before.width).put("before_height", before.height)
            .put("after_width", after.width).put("after_height", after.height)
            .put("dimensions_match", before.width == after.width && before.height == after.height)
            .put("all_pixels", pixelDifference(before, after, 0))
            .put("lower_body", pixelDifference(before, after, (before.height * .55f).toInt()))
        val reportFile = File(directory, "$prefix.json")
        reportFile.writeText(report.toString(2), Charsets.UTF_8)
        return reportFile.absolutePath
    }

    private fun pixelDifference(before: Pixels, after: Pixels, startY: Int): JSONObject {
        val width = minOf(before.width, after.width)
        val height = minOf(before.height, after.height)
        var changed = 0
        var maxRgbDelta = 0
        var maxAlphaDelta = 0
        var rgbDeltaSum = 0L
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in startY.coerceIn(0, height) until height) for (x in 0 until width) {
            val a = before.values[y * before.width + x]
            val b = after.values[y * after.width + x]
            val red = kotlin.math.abs(((a ushr 16) and 255) - ((b ushr 16) and 255))
            val green = kotlin.math.abs(((a ushr 8) and 255) - ((b ushr 8) and 255))
            val blue = kotlin.math.abs((a and 255) - (b and 255))
            maxRgbDelta = maxOf(maxRgbDelta, red, green, blue)
            maxAlphaDelta = maxOf(maxAlphaDelta, kotlin.math.abs((a ushr 24) - (b ushr 24)))
            rgbDeltaSum += red + green + blue
            if (a != b) {
                changed++
                minX = minOf(minX, x); minY = minOf(minY, y)
                maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
            }
        }
        val compared = width * (height - startY.coerceIn(0, height))
        return JSONObject().put("compared_pixels", compared).put("changed_pixels", changed)
            .put("max_rgb_delta", maxRgbDelta).put("max_alpha_delta", maxAlphaDelta)
            .put("mean_absolute_rgb_delta", if (compared == 0) 0.0 else rgbDeltaSum.toDouble() / (compared * 3L))
            .put("bounds_inclusive", if (changed == 0) JSONObject.NULL else JSONObject()
                .put("left", minX).put("top", minY).put("right", maxX).put("bottom", maxY))
    }

    private fun assertStill(tag: String, message: String) {
        val first = pixels(tag)
        for (interval in listOf(137L, 283L, 541L)) {
            advance(interval)
            assertTrue(message, first.sameAs(pixels(tag)))
        }
    }

    private fun description(mood: OnuMood) {
        compose.onAllNodesWithContentDescription(mood.description, useUnmergedTree = true).assertCountEquals(1)
    }

    /**
     * Optional device-recording window, only inside the test APK. Run the first method with
     * `-e onuActingReview true` while an external screen recorder is already running.
     * Keeps this controlled, silent speaking fixture on screen for three wall-clock seconds.
     */
    private fun presentReviewIfRequested() {
        if (InstrumentationRegistry.getArguments().getString("onuActingReview") != "true") return
        val began = SystemClock.uptimeMillis()
        var rendered = 0L
        while (true) {
            val elapsed = (SystemClock.uptimeMillis() - began).coerceAtMost(3_000L)
            if (elapsed > rendered) {
                compose.mainClock.advanceTimeBy(elapsed - rendered, ignoreFrameDuration = true)
                compose.waitForIdle()
                rendered = elapsed
            }
            if (elapsed >= 3_000L) return
            SystemClock.sleep(16)
        }
    }

    private data class Acting(val mood: OnuMood, val motion: Boolean, val narration: Boolean)
    private class TestMotionScale : MotionDurationScale {
        @Volatile override var scaleFactor: Float = 1f
    }
    private class FixtureLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Pixels(val width: Int, val height: Int, val values: IntArray) {
        fun sameAs(other: Pixels) = width == other.width && height == other.height && values.contentEquals(other.values)
        fun lowerBodySameAs(other: Pixels): Boolean {
            if (width != other.width || height != other.height) return false
            // A broad lower-body region, independent of the authored mouth-mask coordinates.
            val lowerBody = (height * .55f).toInt() * width
            // The gradient's offscreen compositing can round an RGB channel by one step.
            // Evidence: lower-body-acting-character-1846395.json (221/46080 pixels, max 1,
            // unchanged alpha). Keep dimensions/alpha exact; larger pose differences still fail.
            return (lowerBody until values.size).all {
                val a = values[it]
                val b = other.values[it]
                (a ushr 24) == (b ushr 24) &&
                    kotlin.math.abs(((a ushr 16) and 255) - ((b ushr 16) and 255)) <= 1 &&
                    kotlin.math.abs(((a ushr 8) and 255) - ((b ushr 8) and 255)) <= 1 &&
                    kotlin.math.abs((a and 255) - (b and 255)) <= 1
            }
        }
    }
}
