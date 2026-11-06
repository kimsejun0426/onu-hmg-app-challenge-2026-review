package dev.carplaymate.demo

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual offline TTS engine callbacks; no claim about acoustic latency or perceived voice quality. */
@RunWith(AndroidJUnit4::class)
class SpeechOutputTest {
    @Test fun realOfflineKoreanTtsCompletesOnceAndStopCancelsTheNextPlayback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("speechOutputTests") == "true")
        // API 35 correctly rejects focus for background-only instrumentation. Test the foreground app.
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val holder = AtomicReference<AndroidTtsOutput>()
        val failures = AtomicReference<String?>()
        val completions = AtomicInteger()
        val complete = CountDownLatch(1)
        instrumentation.runOnMainSync {
            holder.set(AndroidTtsOutput(instrumentation.targetContext) { failures.set("Unexpected focus interruption") })
        }
        val output = holder.get()
        try {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!output.readyState.value && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("Install a Korean offline TTS voice before running this explicit test.", output.readyState.value)
            val plan = SpeechPlan(UUID.randomUUID().toString(), "tts-test-turn", 1,
                listOf(SpeechSegment("greeting", "안녕. 함께 모험을 떠나 볼까?", true)))
            instrumentation.runOnMainSync {
                output.speak(plan, 1, { completions.incrementAndGet(); complete.countDown() },
                    { failures.set(it.message); complete.countDown() })
            }
            assertTrue("Actual TTS did not finish.", complete.await(30, TimeUnit.SECONDS))
            assertEquals(null, failures.get())
            assertEquals(1, completions.get())
            val longPlan = plan.copy(playbackId = UUID.randomUUID().toString(),
                segments = listOf(SpeechSegment("long", "잠깐 쉬고 싶을 때는 함께 조용히 기다릴게. ".repeat(8), true)))
            instrumentation.runOnMainSync {
                output.speak(longPlan, 2, { completions.incrementAndGet() }, { failures.set(it.message) })
                output.stop()
            }
            SystemClock.sleep(800)
            assertEquals("Cancelled playback must never acknowledge completion.", 1, completions.get())
            assertEquals(null, failures.get())
            instrumentation.runOnMainSync { output.refresh() }
            SystemClock.sleep(400)
            assertEquals("Voice capability refresh must not start playback.", 1, completions.get())
        } finally {
            instrumentation.runOnMainSync { output.shutdown() }
            activity.close()
        }
    }
}
