package dev.carplaymate.demo

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstJumpSessionTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = inst.runOnMainSync(action)

    private class Player : StoryClipPlayer {
        data class Part(val id: String, val finish: () -> Unit, val fail: () -> Unit, val start: () -> Unit)
        val parts = mutableListOf<Part>()
        var position: Long? = null
        override fun positionMillis() = position
        override fun play(audio: BundledAudio, complete: () -> Unit, failure: () -> Unit, started: () -> Unit) {
            parts += Part(audio.id, complete, failure, started)
        }
        override fun stop() {}
        override fun shutdown() {}
    }

    private class Mic(val opened: () -> Unit = {}) : SpeechCaptureInput {
        data class Ask(val ticket: CaptureTicket, val final: (CaptureTicket, String) -> Unit,
                       val fail: (CaptureTicket, SpeechInputFailure) -> Unit, val stage: (CaptureTicket, CaptureStage) -> Unit)
        val asks = mutableListOf<Ask>()
        override val available = true
        override val source = "controlled-test"
        override fun unavailableReason(checkPermission: Boolean): String? = null
        override fun open(ticket: CaptureTicket, onStage: (CaptureTicket, CaptureStage) -> Unit,
                          onFinal: (CaptureTicket, String) -> Unit, onFailure: (CaptureTicket, SpeechInputFailure) -> Unit) {
            asks += Ask(ticket, onFinal, onFailure, onStage)
            onStage(ticket, CaptureStage.LISTENING)
            opened()
        }
        override fun close() {}
    }

    private val silentSignals = object : CaptureSignalOutput {
        override fun ready() {}
        override fun processing() {}
        override fun stop() {}
        override fun release() {}
    }

    private fun Harness(mode: LetterMode = LetterMode.VOICE, saved: JumpState? = null) = EpisodeHarness(JumpEpisode, mode, saved)

    private inner class EpisodeHarness<S : Any>(episode: LetterEpisode<S>, val mode: LetterMode, saved: S? = null,
        lookup: (String) -> BundledAudio = { id -> BundledAudio(id, id, "unused", 5000) }) {
        val player = Player()
        val mic = Mic()
        var allowed = true
        var output = false
        var input = false
        val records = mutableListOf<String>()
        val session = LetterSession(episode, player, lookup,
            object : PlaybackAudioFocus {
                override fun acquire(onLost: () -> Unit): Boolean { assertFalse(input); output = true; return true }
                override fun release() { output = false }
            }, mic, { object : CaptureFocus {
                override fun acquire(): Boolean { assertFalse(output); input = true; return true }
                override fun release() { input = false }
            } }, CaptureSignals(silentSignals), { allowed }, { records += it }, mode, saved)
        fun open() = session.choose("open")
        fun finishOpening() { repeat(3) { player.parts.last().finish() } }
    }

    @Test fun transportUsesNativeStartAndClearsEachClipBeforePreparingTheNext() = main {
        val h = Harness()
        val seen = mutableListOf<LetterTransportSnapshot>()
        val observation = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            h.session.transport.collect { seen += it }
        }
        try {
            val initial = h.session.transport.value
            assertEquals(LetterTransportPhase.STOPPED, initial.phase)
            h.open()
            val preparation = h.session.transport.value
            assertEquals(initial.sessionId, preparation.sessionId)
            assertEquals(initial.revision + 1L, preparation.revision)
            assertEquals(LetterTransportPhase.PREPARING, preparation.phase)
            assertEquals("opening", preparation.cue)
            assertEquals(5000L, preparation.durationMs)
            h.player.position = 111L
            assertNull(preparation.positionMs)
            assertNull(h.session.transportPositionMillis())

            val opening = h.player.parts.last()
            opening.start()
            assertEquals(LetterTransportPhase.PLAYING, h.session.transport.value.phase)
            assertEquals(111L, h.session.transport.value.positionMs)
            h.player.position = 456L
            assertEquals(456L, h.session.transportPositionMillis())
            val beforeCompletion = seen.size
            opening.finish()
            val betweenClips = seen.drop(beforeCompletion).first()
            assertEquals(LetterTransportPhase.PREPARING, betweenClips.phase)
            assertNull(betweenClips.cue)
            assertNull(betweenClips.durationMs)
            assertNull(betweenClips.positionMs)
            val splashPreparation = h.session.transport.value
            assertEquals("splash", splashPreparation.cue)
            assertEquals(LetterTransportPhase.PREPARING, splashPreparation.phase)
            assertNull(h.session.transportPositionMillis())
            opening.start(); opening.fail(); opening.finish()
            assertEquals(splashPreparation, h.session.transport.value)

            val splash = h.player.parts.last()
            splash.start()
            assertFalse(h.session.state.value.speaking)
            assertEquals(LetterTransportPhase.PLAYING, h.session.transport.value.phase)
            splash.finish()
            val question = h.player.parts.last()
            question.start(); question.finish()
            assertEquals(LetterTransportPhase.INPUT, h.session.transport.value.phase)
            assertNull(h.session.transport.value.cue)
            assertNull(h.session.transport.value.durationMs)
            assertNull(h.session.transport.value.positionMs)
            assertNull(h.session.transportPositionMillis())
            assertEquals(1, h.mic.asks.size)
            val ask = h.mic.asks.single()
            ask.stage(ask.ticket, CaptureStage.PROCESSING)
            assertEquals(LetterTransportPhase.PROCESSING, h.session.transport.value.phase)
            ask.stage(ask.ticket, CaptureStage.LISTENING)
            assertEquals(LetterTransportPhase.PROCESSING, h.session.transport.value.phase)

            val revision = h.session.transport.value.revision
            h.session.pause()
            val paused = h.session.transport.value
            assertEquals(initial.sessionId, paused.sessionId)
            assertEquals(revision + 1L, paused.revision)
            assertEquals(LetterTransportPhase.STOPPED, paused.phase)
            ask.final(ask.ticket, "물소리가 들렸어")
            ask.stage(ask.ticket, CaptureStage.LISTENING)
            question.start(); question.finish()
            assertEquals(paused, h.session.transport.value)
            h.session.close()
            assertEquals(paused.revision + 1L, h.session.transport.value.revision)
            assertEquals(LetterTransportPhase.STOPPED, h.session.transport.value.phase)
            assertNull(h.session.transportPositionMillis())
        } finally {
            observation.cancel()
            h.session.close()
        }
    }

    @Test fun transportErrorsAndAssistedReplayNeverReuseAnOldClipTimeline() = main {
        val h = Harness()
        try {
            h.open()
            val first = h.player.parts.last()
            val revision = h.session.transport.value.revision
            first.fail()
            val failed = h.session.transport.value
            assertEquals(revision + 1L, failed.revision)
            assertEquals(LetterTransportPhase.ERROR, failed.phase)
            assertNull(failed.cue)
            assertNull(failed.durationMs)
            assertNull(failed.positionMs)
            first.start(); first.finish()
            assertEquals(failed, h.session.transport.value)

            h.session.replay()
            h.finishOpening()
            val ask = h.mic.asks.last()
            ask.fail(ask.ticket, SpeechInputFailure("controlled unavailable"))
            assertEquals(LetterTransportPhase.ERROR, h.session.transport.value.phase)
            h.session.touchAssistance()
            assertTrue(h.session.state.value.touch)
            assertEquals(LetterTransportPhase.STOPPED, h.session.transport.value.phase)
            h.session.replay()
            assertTrue(h.session.state.value.touch)
            assertEquals(LetterTransportPhase.PREPARING, h.session.transport.value.phase)
            h.player.position = 0L
            h.player.parts.last().start()
            assertEquals(LetterTransportPhase.PLAYING, h.session.transport.value.phase)
            val beforeClose = h.session.transport.value.revision
            h.session.close()
            val closed = h.session.transport.value
            assertEquals(beforeClose + 1L, closed.revision)
            assertEquals(LetterTransportPhase.STOPPED, closed.phase)
            assertNull(closed.cue)
            assertNull(closed.durationMs)
            assertNull(closed.positionMs)
            h.player.parts.last().start(); h.player.parts.last().finish()
            assertEquals(closed, h.session.transport.value)
        } finally { h.session.close() }
    }

    @Test fun transportOfReadAndCompletedLettersStaysStoppedWithoutInventingPlayback() = main {
        val reading = Harness(LetterMode.READ)
        val completed = Harness(saved = JumpState(JumpStep.END, ending = JumpEnding.SOFT))
        try {
            assertNotEquals(reading.session.transport.value.sessionId, completed.session.transport.value.sessionId)
            reading.open()
            assertEquals(LetterTransportPhase.STOPPED, reading.session.transport.value.phase)
            assertTrue(reading.player.parts.isEmpty())
            assertTrue(reading.mic.asks.isEmpty())
            assertEquals(LetterTransportPhase.STOPPED, completed.session.transport.value.phase)
            assertNull(completed.session.transportPositionMillis())
            completed.session.activationFailed("controlled activation failure")
            assertEquals(LetterTransportPhase.ERROR, completed.session.transport.value.phase)
            assertNull(completed.session.transport.value.cue)
        } finally {
            reading.session.close()
            completed.session.close()
        }
    }

    @Test fun onlyTheLastCueOpensOneWindowAndAStaleCallbackCannotAnswerTheNextTurn() = main {
        val h = Harness()
        try {
            h.open()
            assertEquals(listOf("opening"), h.player.parts.map { it.id })
            val first = h.player.parts.last()
            first.start()
            assertTrue(h.session.state.value.speaking)
            first.finish()
            val splash = h.player.parts.last()
            assertEquals("splash", splash.id)
            splash.start()
            assertFalse(h.session.state.value.speaking)
            first.finish()
            assertEquals(2,h.player.parts.size)
            assertTrue(h.mic.asks.isEmpty())
            splash.finish()
            val question = h.player.parts.last()
            assertEquals("water_q", question.id)
            assertTrue(h.mic.asks.isEmpty())
            question.finish()
            question.finish()
            assertEquals(1,h.mic.asks.size)
            val ask = h.mic.asks.single()
            h.session.choose("wet")
            assertEquals(JumpStep.WATER,h.session.state.value.turn.state.step)
            ask.final(ask.ticket,"물소리가 들렸어")
            assertEquals(JumpStep.GUESS,h.session.state.value.turn.state.step)
            ask.final(ask.ticket,"펭귄")
            assertEquals(JumpStep.GUESS,h.session.state.value.turn.state.step)
            assertTrue(h.records.all { it.matches(Regex("[A-Z_|a-z]+")) })
        } finally { h.session.close() }
    }

    @Test fun authoredAnswerEchoIsTransientAndReplayCannotRestoreItFromCheckpoint() = main {
        val h = Harness()
        try {
            h.open(); h.finishOpening()
            val ask = h.mic.asks.single()
            ask.final(ask.ticket, "물소리가 들렸어")
            assertEquals("물소리", h.session.state.value.turn.answerLabel)
            assertTrue(h.records.all { !it.contains("물소리") && !it.contains("들렸어") })
            h.session.state.value.turn.cues.forEach { _ -> h.player.parts.last().finish() }
            h.session.replay()
            assertNull(h.session.state.value.turn.answerLabel)
            h.session.pause()
            assertNull(h.session.state.value.turn.answerLabel)
            val restored = Harness(saved = restoreLetter(h.records.last()))
            try { assertNull(restored.session.state.value.turn.answerLabel) }
            finally { restored.session.close() }
        } finally { h.session.close() }
    }

    @Test fun repeatedSilencePausesBeforeAnyEndingOrReplyCanBeSaved() = main {
        val h = Harness()
        try {
            h.open(); h.finishOpening()
            repeat(2) {
                val ask = h.mic.asks.last()
                ask.fail(ask.ticket, SpeechInputFailure("No response in controlled input", "NO_RESPONSE"))
                if (it == 0) h.session.state.value.turn.cues.forEach { _ -> h.player.parts.last().finish() }
            }
            val now = h.session.state.value
            assertTrue(now.turn.state.paused)
            assertEquals(JumpStep.WATER, now.turn.state.step)
            assertNull(now.turn.state.ending)
            assertNull(now.turn.answerLabel)
            assertFalse(h.output); assertFalse(h.input)
            assertTrue(h.records.none { restoreLetter(it)?.step == JumpStep.END })
        } finally { h.session.close() }
    }

    @Test fun replayStartsAtTheQuestionAfterHearingTheLetterAndRetainsFailedOpening() = main {
        val h = EpisodeHarness(RainEpisode(JumpEnding.BOLD), LetterMode.LISTEN)
        try {
            h.open()
            h.player.parts.last().fail()
            h.session.choose("drops")
            h.session.touchAssistance()
            assertEquals(RainStep.DROPS, h.session.state.value.turn.state.step)
            assertFalse(h.session.state.value.touch)
            h.session.replay()
            for (cue in listOf("opening", "memory_bold", "rain_drops", "drops_q")) {
                assertEquals(cue, h.player.parts.last().id)
                h.player.parts.last().finish()
            }
            h.session.replay()
            for (cue in listOf("rain_drops", "drops_q")) {
                assertEquals(cue, h.player.parts.last().id)
                h.player.parts.last().finish()
            }
            h.session.choose("wiper")
            assertEquals("rain_hint", h.player.parts.last().id)
            h.session.state.value.turn.cues.forEach { _ -> h.player.parts.last().finish() }
            h.session.replay()
            assertEquals("rain_hint", h.player.parts.last().id)
        } finally { h.session.close() }
    }

    @Test fun lateListeningStageDoesNotInviteAnotherAnswerAfterProcessingStarted() = main {
        val h = Harness()
        try {
            h.open()
            h.finishOpening()
            val ask = h.mic.asks.single()
            ask.stage(ask.ticket, CaptureStage.PROCESSING)
            ask.stage(ask.ticket, CaptureStage.LISTENING)
            assertEquals(LetterPhase.PROCESSING, h.session.state.value.phase)
            ask.final(ask.ticket, "물소리")
            assertEquals(JumpStep.GUESS, h.session.state.value.turn.state.step)
            ask.stage(ask.ticket, CaptureStage.LISTENING)
            assertEquals(LetterPhase.OUTPUT, h.session.state.value.phase)
            assertEquals(1, h.mic.asks.size)
        } finally { h.session.close() }
    }

    @Test fun restrictionDuringPlaybackBlocksLateCompletionAndRestoreWaitsForExplicitResume() = main {
        val h = Harness()
        try {
            h.open()
            val outstanding = h.player.parts.last()
            h.allowed = false
            outstanding.finish()
            assertTrue(h.session.state.value.turn.state.paused)
            assertTrue(h.mic.asks.isEmpty())
            val saved = restoreLetter(h.records.last())
            assertNotNull(saved)
            h.allowed = true
            outstanding.finish()
            assertTrue(h.session.state.value.turn.state.paused)
            val restored = Harness(saved = saved)
            try {
                assertTrue(restored.session.state.value.turn.state.paused)
                assertTrue(restored.player.parts.isEmpty())
                restored.session.resume()
                restored.session.resume()
                assertEquals(1, restored.player.parts.size)
                assertEquals("splash", restored.player.parts.last().id)
                assertFalse(restored.session.state.value.turn.state.paused)
            } finally { restored.session.close() }
        } finally { h.session.close() }
    }

    @Test fun aSpeechServiceErrorAndTouchAssistanceDoNotManufactureAnAnswer() = main {
        val h = Harness()
        try {
            h.open()
            h.finishOpening()
            val ask=h.mic.asks.single()
            ask.fail(ask.ticket, SpeechInputFailure("Unavailable"))
            assertEquals(LetterPhase.INPUT_ERROR,h.session.state.value.phase)
            assertEquals(JumpStep.WATER,h.session.state.value.turn.state.step)
            h.session.touchAssistance()
            assertTrue(h.session.state.value.touch)
            ask.final(ask.ticket,"해달")
            assertEquals(JumpStep.WATER,h.session.state.value.turn.state.step)
            h.session.choose("steps")
            assertEquals(JumpStep.GUESS,h.session.state.value.turn.state.step)
        } finally { h.session.close() }
        val read = Harness(LetterMode.READ)
        try {
            read.open()
            assertTrue(read.mic.asks.isEmpty())
            assertTrue(read.player.parts.isEmpty())
            read.session.choose("wet")
            assertEquals(JumpStep.GUESS,read.session.state.value.turn.state.step)
        } finally { read.session.close() }
    }

    @Test fun aFinaleIsNotSavedUntilPlaybackCompletesAndAnInterruptedEndingCanBeRedirected() = main {
        val start = JumpState(step = JumpStep.DIRECT, guess = JumpGuess.PENGUIN, paused = true)
        val h = Harness(LetterMode.LISTEN, start)
        try {
            h.session.resume()
            repeat(2) { h.player.parts.last().finish() }
            h.session.choose("soft")
            val firstFinale = h.player.parts.last()
            assertEquals("finale_soft", firstFinale.id)
            assertTrue(h.session.state.value.pendingEnding)
            assertEquals(JumpStep.DIRECT,restoreLetter(h.records.last())!!.step)
            firstFinale.fail()
            assertEquals(LetterPhase.OUTPUT_ERROR,h.session.state.value.phase)
            h.session.choose("restart")
            assertTrue(h.session.state.value.pendingEnding)
            h.session.replay()
            val retried = h.player.parts.last()
            h.session.pause()
            retried.finish()
            assertTrue(h.session.state.value.turn.state.paused)
            assertEquals(JumpStep.DIRECT,restoreLetter(h.records.last())!!.step)
            h.session.resume()
            repeat(2) { h.player.parts.last().finish() }
            h.session.choose("bold")
            val final = h.player.parts.last()
            assertEquals("finale_bold", final.id)
            assertTrue(h.session.state.value.pendingEnding)
            assertEquals(LetterTransportPhase.PREPARING, h.session.transport.value.phase)
            final.start()
            assertEquals(LetterTransportPhase.PLAYING, h.session.transport.value.phase)
            final.finish()
            assertEquals(LetterTransportPhase.STOPPED, h.session.transport.value.phase)
            assertNull(h.session.transport.value.cue)
            assertNull(h.session.transportPositionMillis())
            assertFalse(h.session.state.value.pendingEnding)
            assertEquals(JumpEnding.BOLD,restoreLetter(h.records.last())!!.ending)
            assertEquals(JumpStep.END,restoreLetter(h.records.last())!!.step)
        } finally { h.session.close() }
        val read = Harness(LetterMode.READ, start)
        try {
            read.session.resume()
            read.session.choose("soft")
            assertFalse(read.session.state.value.pendingEnding)
            assertEquals(JumpStep.END,restoreLetter(read.records.last())!!.step)
        } finally { read.session.close() }
    }

    @Test fun aRealDialogStealsWindowFocusAndClosingItDoesNotResumeTheParkedLetter() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val lost = CountDownLatch(1)
        val regained = CountDownLatch(1)
        var harness: EpisodeHarness<JumpState>? = null
        var dialog: android.app.Dialog? = null
        var unobserve: (() -> Unit)? = null
        var watchGain: android.view.ViewTreeObserver.OnWindowFocusChangeListener? = null
        try {
            val firstFocus = CountDownLatch(1)
            scenario.onActivity { host ->
                val view = host.window.decorView
                if (view.hasWindowFocus()) firstFocus.countDown()
                else {
                    val first = object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
                        override fun onWindowFocusChanged(focused: Boolean) {
                            if (focused) {
                                view.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                                firstFocus.countDown()
                            }
                        }
                    }
                    view.viewTreeObserver.addOnWindowFocusChangeListener(first)
                }
            }
            assertTrue("The activity must own the window before the test takes focus away",firstFocus.await(8,TimeUnit.SECONDS))
            scenario.onActivity { host ->
                val view = host.window.decorView
                assertTrue(view.hasWindowFocus())
                val h = Harness().also { harness = it }
                h.open()
                unobserve = observeFirstJumpWindow(view) { h.session.pause(); lost.countDown() }
                watchGain = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                    if (focused) regained.countDown()
                }.also { view.viewTreeObserver.addOnWindowFocusChangeListener(it) }
                dialog = android.app.Dialog(host).apply {
                    setContentView(android.widget.TextView(host).apply { text = "창 포커스 검사" })
                    show()
                }
            }
            assertTrue(lost.await(8,TimeUnit.SECONDS))
            scenario.onActivity { host ->
                val h = checkNotNull(harness)
                assertTrue(host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                assertTrue(h.session.state.value.turn.state.paused)
                h.player.parts.first().finish()
                assertTrue(h.mic.asks.isEmpty())
                dialog?.dismiss()
            }
            assertTrue(regained.await(8,TimeUnit.SECONDS))
            main { val h=checkNotNull(harness); assertTrue(h.session.state.value.turn.state.paused); assertEquals(1,h.player.parts.size) }
        } finally {
            scenario.onActivity { host ->
                unobserve?.invoke()
                watchGain?.let(host.window.decorView.viewTreeObserver::removeOnWindowFocusChangeListener)
                dialog?.dismiss()
                harness?.session?.close()
            }
            scenario.close()
        }
    }


    @Test fun fiveScopedRecognizedAnswersFinishTheVoiceRoundOnlyAfterTheBroadcastEnds() = main {
        val h = Harness()
        try {
            h.open()
            val spoken = listOf(
                JumpStep.WATER to "발소리",
                JumpStep.GUESS to "해달",
                JumpStep.QUESTION to "깃털이 있어?",
                JumpStep.IDENTIFY to "펭귄",
                JumpStep.DIRECT to "성큼성큼, 작게 퐁당",
            )
            spoken.forEachIndexed { index, (stage, answer) ->
                val turn = h.session.state.value.turn
                assertEquals(stage, turn.state.step)
                assertTrue(turn.cues.isNotEmpty())
                turn.cues.forEachIndexed { cueIndex, expectedCue ->
                    assertFalse("No input while any authored clip remains", h.input)
                    assertTrue(h.output)
                    assertEquals(index, h.mic.asks.size)
                    val clip = h.player.parts.last()
                    assertEquals(expectedCue, clip.id)
                    clip.start()
                    clip.finish()
                    if (cueIndex < turn.cues.lastIndex) assertEquals(index, h.mic.asks.size)
                }
                assertEquals(index + 1, h.mic.asks.size)
                assertFalse(h.output)
                assertTrue(h.input)
                val current = h.mic.asks.last()
                current.final(current.ticket, answer)
                assertFalse("The previous input was released before the next cue", h.input)
            }
            assertEquals(JumpStep.END, h.session.state.value.turn.state.step)
            assertTrue(h.session.state.value.pendingEnding)
            assertEquals(JumpStep.DIRECT, restoreLetter(h.records.last())!!.step)
            assertEquals("finale_bold", h.player.parts.last().id)
            h.player.parts.last().finish()
            assertFalse(h.session.state.value.pendingEnding)
            val saved = restoreLetter(h.records.last())!!
            assertEquals(JumpStep.END, saved.step)
            assertEquals(JumpEnding.BOLD, saved.ending)
            assertEquals(5, h.mic.asks.size)
            assertFalse(h.input)
            assertFalse(h.output)
            assertTrue(h.records.none { record -> spoken.any { (_, word) -> word in record } })
        } finally { h.session.close() }
    }


    @Test fun aRainAnswerCannotOpenBeforeItsSoundAndTheWrongTempoGetsAnotherListen() = main {
        val h = EpisodeHarness(RainEpisode(JumpEnding.BOLD), LetterMode.VOICE)
        fun answer(text: String, step: RainStep) {
            val turn = h.session.state.value.turn
            assertEquals(step, turn.state.step)
            val count = h.mic.asks.size
            turn.cues.forEach { cue ->
                val clip = h.player.parts.last()
                assertEquals(cue, clip.id)
                assertEquals(count, h.mic.asks.size)
                assertFalse(h.input)
                clip.start()
                if (RainGame.isEffect(cue)) assertFalse(h.session.state.value.speaking)
                clip.finish()
            }
            assertEquals(count + 1, h.mic.asks.size)
            val window = h.mic.asks.last()
            assertEquals("rain_music", window.ticket.context.sessionId)
            window.final(window.ticket, text)
            assertFalse(h.input)
        }
        try {
            h.open()
            assertTrue("remember the selected first broadcast", "memory_bold" in h.session.state.value.turn.cues)
            answer("빗방울", RainStep.DROPS)
            answer("와이퍼", RainStep.WIPER)
            answer("점점 느려졌어", RainStep.TEMPO)
            assertTrue(h.session.state.value.turn.state.assisted)
            answer("점점 빨라졌어", RainStep.TEMPO)
            answer("와이퍼가 먼저", RainStep.DIRECT)
            assertTrue(h.session.state.value.pendingEnding)
            assertEquals(RainStep.DIRECT, restoreRain(h.records.last())!!.step)
            val finale = h.player.parts.last()
            assertEquals("finale_wiper", finale.id)
            finale.start()
            assertFalse(h.session.state.value.speaking)
            finale.finish()
            assertFalse(h.session.state.value.pendingEnding)
            assertEquals(RainEnding.WIPER, restoreRain(h.records.last())!!.ending)
            h.session.choose("remix")
            answer("빗방울 먼저", RainStep.DIRECT)
            assertEquals("finale_drops", h.player.parts.last().id)
            h.player.parts.last().finish()
            assertEquals(RainEnding.DROPS, restoreRain(h.records.last())!!.ending)
            assertTrue(h.records.none { "빗방울" in it || "와이퍼" in it })
        } finally { h.session.close() }
    }

    @Test fun mixedFinaleFollowsMediaPositionIncludingReplayAndIgnoresLateEvents() {
        val window = AudioSpeechWindow(5880, 10590)
        val episode = RainEpisode(JumpEnding.BOLD)
        lateinit var h: EpisodeHarness<RainState>
        lateinit var finished: Player.Part
        fun settle(part: MixedAudioPart) {
            val until = SystemClock.elapsedRealtime() + 2000
            while (h.session.state.value.mixedPart != part && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
            assertEquals(part, h.session.state.value.mixedPart)
        }
        main {
            val atDirector = RainState(RainStep.DIRECT, JumpEnding.BOLD, paused = true)
            h = EpisodeHarness(episode, LetterMode.LISTEN, atDirector) { id ->
                BundledAudio(id, id, "controlled", 10695, if (id.startsWith("finale_")) window else null)
            }
        }
        try {
            main {
                h.session.resume()
                h.player.parts.last().finish()
                h.session.choose("wiper")
                finished = h.player.parts.last()
                h.player.position = 0
                finished.start()
                assertEquals("와이퍼가 먼저 연주하는 중", letterCaption(h.mode, episode, h.session.state.value))
                assertFalse(h.session.state.value.speaking)
                assertTrue(h.session.state.value.pendingEnding)
                h.player.position = window.startMillis + 2
            }
            settle(MixedAudioPart.VOICE)
            main {
                assertTrue(h.session.state.value.speaking)
                assertEquals(RainGame.caption("finale_wiper").substringAfter("] "), letterCaption(h.mode,episode,h.session.state.value))
                assertEquals(RainStep.DIRECT, restoreRain(h.records.last())!!.step)
                h.player.position = window.endMillis
            }
            settle(MixedAudioPart.TAIL)
            main {
                assertFalse(h.session.state.value.speaking)
                assertTrue(h.session.state.value.pendingEnding)
                finished.finish()
                assertEquals(RainStep.END, restoreRain(h.records.last())!!.step)
                h.session.replay()
                h.player.position = 0
                h.player.parts.last().start()
                assertEquals(MixedAudioPart.MUSIC,h.session.state.value.mixedPart)
                assertEquals("와이퍼가 먼저 연주하는 중", letterCaption(h.mode,episode,h.session.state.value))
                assertFalse(h.session.state.value.pendingEnding)
                h.player.position = window.startMillis + 50
            }
            settle(MixedAudioPart.VOICE)
            main {
                val replay = h.player.parts.last()
                h.session.pause()
                replay.start()
                replay.finish()
                finished.finish()
            }
            SystemClock.sleep(300)
            main {
                assertEquals(LetterPhase.READY,h.session.state.value.phase)
                assertNull(h.session.state.value.mixedPart)
                assertFalse(h.session.state.value.speaking)
            }
        } finally { main { h.session.close() } }
    }

    @Test fun actualMixedFinaleChangesCaptionDuringPlaybackOnly() {
        val app = inst.targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val episode = RainEpisode(JumpEnding.BOLD)
        val catalog = FirstJumpAssets(app, episode.id)
        val measurements = org.json.JSONArray()
        try {
            for ((ending, cue) in listOf(RainEnding.DROPS to "finale_drops", RainEnding.WIPER to "finale_wiper")) {
                val asset = requireNotNull(catalog.audio(cue))
                val window = requireNotNull(asset.speechWindow)
                val player = AndroidClipPlayer(app)
                var session: LetterSession<RainState>? = null
                var lastMusic = -1L
                var firstSpoken = -1L
                var finished = false
                try {
                    main {
                        session = LetterSession(episode,player,catalog::audio,AndroidPlaybackAudioFocus(app),Mic(),
                            { lost -> AndroidCaptureFocus(app,lost) },CaptureSignals(silentSignals),{true},{},LetterMode.LISTEN,
                            RainState(RainStep.END,JumpEnding.BOLD,ending))
                        session!!.replay()
                    }
                    val deadline = SystemClock.elapsedRealtime()+22_000
                    while (!finished && SystemClock.elapsedRealtime()<deadline) {
                        main {
                            val view = session!!.state.value
                            val head = player.positionMillis()
                            val text = letterCaption(LetterMode.LISTEN,episode,view)
                            if (head != null && view.mixedPart == MixedAudioPart.MUSIC) {
                                assertFalse(view.speaking)
                                assertTrue(text.endsWith("먼저 연주하는 중"))
                                lastMusic = head
                            }
                            if (head != null && view.speaking && firstSpoken < 0) {
                                assertEquals(MixedAudioPart.VOICE,view.mixedPart)
                                assertEquals(RainGame.caption(cue).substringAfter("] "),text)
                                firstSpoken = head
                            }
                            finished = view.phase == LetterPhase.WAIT && view.mixedPart == MixedAudioPart.TAIL
                        }
                        if (!finished) SystemClock.sleep(45)
                    }
                    assertTrue("Actual clip must finish",finished)
                    assertTrue("Actual music samples must precede the sign-off",lastMusic in 1L..(window.startMillis+500))
                    assertTrue("Sign-off must appear when audible",firstSpoken in window.startMillis..(window.startMillis+650))
                    measurements.put(org.json.JSONObject().put("cue",cue).put("expected_start_ms",window.startMillis)
                        .put("expected_end_ms",window.endMillis).put("last_music_caption_ms",lastMusic)
                        .put("first_spoken_caption_ms",firstSpoken))
                } finally { main { session?.close() } }
            }
            java.io.File(app.filesDir,"rain-finale-actual.json").writeText(measurements.toString(2))
        } finally { scenario.close() }
    }

    @Test fun actualReplyRemembersThePreviousJumpAndPlaysTheRainBeforeInput() {
        val app = inst.targetContext
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val ready = CountDownLatch(1)
        val mic = Mic { ready.countDown() }
        val episode = RainEpisode(JumpEnding.BOLD)
        val assets = FirstJumpAssets(app, episode.id)
        val opening = episode.choose(episode.initial().state, "open").cues
        assertTrue("memory_bold" in opening)
        assertTrue("rain_drops" in opening)
        val duration = opening.sumOf { requireNotNull(assets.audio(it)).durationMillis }
        var session: LetterSession<RainState>? = null
        val start = SystemClock.elapsedRealtime()
        try {
            main {
                session = LetterSession(episode, AndroidClipPlayer(app), assets::audio, AndroidPlaybackAudioFocus(app),
                    mic, { lost -> AndroidCaptureFocus(app, lost) }, CaptureSignals(silentSignals),
                    { true }, {}, LetterMode.VOICE, null)
                session!!.choose("open")
                assertTrue(mic.asks.isEmpty())
            }
            assertTrue(ready.await(45, TimeUnit.SECONDS))
            assertTrue(SystemClock.elapsedRealtime() - start >= duration - 350)
            main { assertEquals(LetterPhase.LISTENING,session!!.state.value.phase); assertEquals(1,mic.asks.size) }
        } finally { main { session?.close() }; activity.close() }
    }

    @Test fun actualBundledThreePartOpeningCompletesBeforeTheControlledInputCanOpen() {
        val app = inst.targetContext
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val done = CountDownLatch(1)
        val mic = Mic { done.countDown() }
        val catalog = FirstJumpAssets(app)
        var session: LetterSession<JumpState>? = null
        val openingMs = listOf("opening","splash","water_q").sumOf { checkNotNull(catalog.audio(it)).durationMillis }
        val started = SystemClock.elapsedRealtime()
        try {
            main {
                session = LetterSession(JumpEpisode, AndroidClipPlayer(app), catalog::audio, AndroidPlaybackAudioFocus(app),
                    mic, { lost -> AndroidCaptureFocus(app,lost) }, CaptureSignals(silentSignals), { true }, {}, LetterMode.VOICE, null)
                session!!.choose("open")
                assertTrue(mic.asks.isEmpty())
            }
            assertTrue("Three authored clips must actually play before recognition",done.await(35,TimeUnit.SECONDS))
            assertTrue("The input must not open early",SystemClock.elapsedRealtime()-started >= openingMs-350L)
            main { assertEquals(LetterPhase.LISTENING,session!!.state.value.phase); assertEquals(1,mic.asks.size) }
        } finally { main { session?.close() }; activity.close() }
    }
}
