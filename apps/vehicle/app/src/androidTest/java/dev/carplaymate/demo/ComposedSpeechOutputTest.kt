package dev.carplaymate.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic composition/focus tests; no microphone, real speech, or child audio. */
@RunWith(AndroidJUnit4::class)
class ComposedSpeechOutputTest {
    private val phrase = "오누랑 상상 속 공룡 연구소에 가자."
    private val voice = BundledAudio("voice", "story_audio/voice.wav", "a".repeat(64), 1000)
    private val cue = BundledAudio("cue", "story_audio/cue.wav", "b".repeat(64), 500)
    private val plainPhrase = "온실 문이 열렸어. 로봇 옆 상자에 알 모형이 있네."
    private val plainVoice = BundledAudio("plain_voice", "story_audio/plain_voice.wav", "c".repeat(64), 2000)
    private fun plan(text: String = phrase, id: String = "plan") = SpeechPlan(id, "turn", 8,
        listOf(SpeechSegment("question", text, true)))
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private class FakeFocus : PlaybackAudioFocus {
        var acquires = 0
        var releases = 0
        var granted = true
        var held = false
        var loss: (() -> Unit)? = null
        var beforeGrant: () -> Unit = {}
        override fun acquire(onLost: () -> Unit): Boolean {
            acquires++; loss = onLost; beforeGrant(); held = granted; return granted
        }
        override fun release() { releases++; held = false }
    }
    private class FakeClips : StoryClipPlayer {
        data class Request(val audio: BundledAudio, val done: () -> Unit, val error: () -> Unit, val start: () -> Unit)
        val requests = mutableListOf<Request>()
        var stops = 0
        override fun play(audio: BundledAudio, complete: () -> Unit, failure: () -> Unit, started: () -> Unit) {
            requests += Request(audio, complete, failure, started)
        }
        override fun stop() { stops++ }
        override fun shutdown() { stop() }
    }
    private class FakeScenes : StoryScenePlayer {
        data class Request(val cue: BundledAudio?, val narration: BundledAudio,
                           val done: () -> Unit, val error: () -> Unit, val speech: (Boolean) -> Unit)
        val requests = mutableListOf<Request>()
        var stops = 0
        var playing = false
        private var requestNumber = 0
        override fun play(cue: BundledAudio, narration: BundledAudio, complete: () -> Unit,
                          failure: () -> Unit, speechState: (Boolean) -> Unit) =
            record(cue, narration, complete, failure, speechState)
        override fun playNarration(narration: BundledAudio, complete: () -> Unit,
                                   failure: () -> Unit, speechState: (Boolean) -> Unit) =
            record(null, narration, complete, failure, speechState)
        private fun record(cue: BundledAudio?, narration: BundledAudio, complete: () -> Unit,
                           failure: () -> Unit, speechState: (Boolean) -> Unit) {
            val number = ++requestNumber
            playing = true
            requests += Request(cue, narration, { if (number == requestNumber) playing = false; complete() }, failure, speechState)
        }
        override fun stop() { stops++; requestNumber++; playing = false }
        override fun shutdown() { stop() }
    }
    private class FakeBase(private val lease: PlaybackAudioFocus) : SpeechOutput {
        data class Request(val plan: SpeechPlan, val done: () -> Unit, val error: (SpeechOutputFailure) -> Unit, val start: () -> Unit)
        override val status = MutableStateFlow("ready")
        override val readyState = MutableStateFlow(true)
        val requests = mutableListOf<Request>()
        var stops = 0
        var refreshes = 0
        override fun refresh() { refreshes++ }
        override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
                           failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) {
            assertTrue("TTS must borrow the current complete-plan lease", lease.acquire {})
            requests += Request(plan, { lease.release(); complete() }, failure, started)
        }
        override fun stop() { stops++; lease.release() }
        override fun shutdown() { stop(); readyState.value = false }
    }
    private class Clock : CaptureTimer {
        var now = 0L
        val tasks = mutableListOf<Runnable>()
        val delays = mutableListOf<Long>()
        override fun nowMillis() = now
        override fun postDelayed(callback: Runnable, delayMillis: Long) { tasks += callback; delays += delayMillis }
        override fun remove(callback: Runnable) { tasks.remove(callback) }
    }
    private inner class Harness(sceneEnabled: Boolean = false) {
        val focus = FakeFocus()
        val clips = FakeClips()
        val scenes = FakeScenes()
        val clock = Clock()
        lateinit var base: FakeBase
        var completions = 0
        var completionSawClosedOutput = true
        var starts = 0
        var failures = 0
        var interrupts = 0
        val output = ComposedSpeechOutput(
            NarrationAssets(BundledNarration(listOf(
                NarrationClip(phrase, BundledNarration.sha256(phrase.toByteArray(Charsets.UTF_8)), voice, cue),
                NarrationClip(plainPhrase, BundledNarration.sha256(plainPhrase.toByteArray(Charsets.UTF_8)), plainVoice),
            ))),
            clips, focus, { interrupts++ }, { lease, _ -> FakeBase(lease).also { base = it } }, clock, if (sceneEnabled) scenes else null,
        )
        fun speak(value: SpeechPlan = plan()) = output.speak(value, 5, {
            completions++
            completionSawClosedOutput = completionSawClosedOutput && !focus.held && !scenes.playing
        }, { failures++ }, { starts++ })
    }
    private fun withHarness(sceneEnabled: Boolean = false, action: (Harness) -> Unit) = main {
        val h = Harness(sceneEnabled)
        try { action(h) } finally { h.output.shutdown() }
    }

    @Test fun originalPlanCompletesOnlyAfterTtsCueNarrationAndRemainingQuestion() = withHarness { h ->
        h.speak(plan("Nova, $phrase 지금 출발할까?"))
        assertEquals(1, h.base.requests.size)
        assertEquals(0, h.clips.requests.size)
        val first = h.base.requests.single()
        first.start(); first.done()
        assertEquals("cue", h.clips.requests[0].audio.id)
        assertTrue(h.focus.held)
        h.clips.requests[0].start(); h.clips.requests[0].done()
        assertEquals("voice", h.clips.requests[1].audio.id)
        h.clips.requests[1].start(); h.clips.requests[1].done()
        assertEquals(0, h.completions)
        assertEquals(2, h.base.requests.size)
        first.done() // A late duplicate must not finish the final question.
        assertEquals(0, h.completions)
        h.base.requests.last().done()
        h.base.requests.last().done()
        assertEquals(1, h.completions)
        assertEquals(1, h.starts)
        assertEquals(1, h.focus.acquires)
        assertFalse(h.focus.held)
    }

    @Test fun cueAloneNeverSignalsSpokenStartOrPlanCompletion() = withHarness { h ->
        h.speak()
        h.clips.requests[0].start()
        assertEquals(0, h.starts)
        h.clips.requests[0].done()
        assertEquals(0, h.completions)
        h.clips.requests[1].start(); h.clips.requests[1].start()
        assertEquals(1, h.starts)
        h.clips.requests[1].done()
        assertEquals(1, h.completions)
    }

    @Test fun stopFencesDelayedCueStartDoneAndError() = withHarness { h ->
        h.speak(); val old = h.clips.requests.single()
        h.output.stop(); old.start(); old.done(); old.error()
        assertEquals(1, h.clips.requests.size)
        assertEquals(0, h.completions + h.starts + h.failures + h.interrupts)
        assertFalse(h.focus.held)
    }

    @Test fun focusLossDuringCueStopsAllSourcesAndNeverAutoResumes() = withHarness { h ->
        h.speak(); val old = h.clips.requests.single()
        val clipStops = h.clips.stops; val baseStops = h.base.stops
        h.focus.loss!!.invoke(); h.focus.loss!!.invoke()
        old.done(); old.start()
        assertEquals(1, h.interrupts)
        assertTrue(h.clips.stops > clipStops && h.base.stops > baseStops)
        assertEquals(0, h.completions + h.starts)
        assertEquals(1, h.clips.requests.size)
    }

    @Test fun focusLossDuringTtsNeverStartsQueuedCue() = withHarness { h ->
        h.speak(plan("Nova, $phrase")); val old = h.base.requests.single()
        h.focus.loss!!.invoke(); old.done()
        assertEquals(1, h.interrupts)
        assertEquals(0, h.clips.requests.size)
        assertEquals(0, h.completions)
    }

    @Test fun replacedRequestCannotAdvanceCurrentRequestEvenWithReusedPlanId() = withHarness { h ->
        h.speak(); val old = h.clips.requests.single()
        h.speak(); val current = h.clips.requests.last()
        old.done(); old.start(); old.error()
        assertEquals(2, h.clips.requests.size)
        assertEquals(0, h.starts + h.failures + h.completions)
        current.done(); h.clips.requests.last().done()
        assertEquals(1, h.completions)
    }

    @Test fun clipFailureStopsAndDoesNotSilentlySkipToInputOrTts() = withHarness { h ->
        h.speak(); val failed = h.clips.requests.single()
        failed.error(); failed.done()
        assertEquals(1, h.failures)
        assertEquals(0, h.completions)
        assertEquals(0, h.base.requests.size)
        assertFalse(h.focus.held)
    }

    @Test fun ttsFailureStopsBeforeLocalCue() = withHarness { h ->
        h.speak(plan("Nova, $phrase")); val failed = h.base.requests.single()
        failed.error(SpeechOutputFailure("synthetic failure")); failed.done()
        assertEquals(1, h.failures)
        assertEquals(0, h.clips.requests.size)
        assertEquals(0, h.completions)
    }

    @Test fun delayedCompletionPastAbsoluteDeadlineFailsWithoutTimerDelivery() = withHarness { h ->
        h.speak(); val old = h.clips.requests.single()
        h.clock.now = 120_001
        old.done()
        assertEquals(1, h.failures)
        assertEquals(0, h.completions)
        assertFalse(h.focus.held)
        assertTrue(h.clock.tasks.isEmpty())
    }

    @Test fun refreshIsSilentAndFencesOldPlayback() = withHarness { h ->
        h.output.refresh()
        assertEquals(0, h.focus.acquires)
        assertTrue(h.clips.requests.isEmpty() && h.base.requests.isEmpty())
        h.speak(); val old = h.clips.requests.single()
        h.output.refresh(); old.done()
        assertEquals(0, h.completions)
        assertEquals(2, h.base.refreshes)
    }

    @Test fun deniedFocusNeverStartsAnySource() = withHarness { h ->
        h.focus.granted = false; h.speak()
        assertEquals(1, h.failures)
        assertTrue(h.clips.requests.isEmpty() && h.base.requests.isEmpty())
        assertEquals(0, h.completions)
    }

    @Test fun unmatchedPlanPreservesExactSegmentsAndRequiredFlags() = withHarness { h ->
        val original = plan("처음 보는 원문이야.").copy(segments = listOf(
            SpeechSegment("a", "  이름과 원문을 보존해. ", false), SpeechSegment("b", "두 번째 문장이야.", true)))
        h.speak(original)
        assertEquals(original, h.base.requests.single().plan)
        assertTrue(h.clips.requests.isEmpty())
        h.base.requests.single().done()
        assertEquals(1, h.completions)
    }

    @Test fun narrationStateIsFalseDuringCuesAndEveryNextPartPreparation() = withHarness { h ->
        h.speak(plan("Nova, $phrase 민준아, 준비됐니?"))
        fun active() = h.output.narrationState.value?.active == true
        assertFalse(active())
        val first = h.base.requests.single()
        first.start()
        assertTrue(active())
        assertEquals("plan", h.output.narrationState.value?.playbackId)
        assertEquals(5L, h.output.narrationState.value?.generation)
        first.done()
        assertFalse(active())
        h.clips.requests[0].start()
        assertFalse(active())
        h.clips.requests[0].done()
        assertFalse(active())
        val voice = h.clips.requests[1]
        voice.start()
        assertTrue(active())
        voice.done()
        assertFalse(active())
        first.start(); voice.start() // Late starts must not animate the next TTS preparation.
        assertFalse(active())
        h.base.requests.last().start()
        assertTrue(active())
        assertEquals(1, h.starts) // Existing once-per-plan contract is unchanged.
        h.base.requests.last().done()
        assertNull(h.output.narrationState.value)
        assertEquals(1, h.completions)
    }

    @Test fun focusLossAndReplacementClearNarrationBeforeLateCallbacks() = withHarness { h ->
        h.speak()
        h.clips.requests[0].done()
        val old = h.clips.requests[1]
        old.start()
        assertEquals(true, h.output.narrationState.value?.active)
        h.focus.loss!!.invoke()
        assertNull(h.output.narrationState.value)
        old.start(); old.done()
        assertNull(h.output.narrationState.value)
        h.speak(plan(id = "replacement"))
        old.start()
        assertEquals(false, h.output.narrationState.value?.active)
        assertEquals("replacement", h.output.narrationState.value?.playbackId)
        h.output.stop()
        assertNull(h.output.narrationState.value)
        assertEquals(0, h.completions)
    }

    @Test fun focusAcquisitionTimeIsNotAddedAgainToAbsoluteWatchdog() = withHarness { h ->
        h.focus.beforeGrant = { h.clock.now = 10_000 }
        h.speak()
        assertEquals(listOf(15_000L), h.clock.delays)
        h.clock.now = 25_000
        h.clock.tasks.single().run()
        assertEquals(1, h.failures)
        assertEquals(0, h.completions)
        assertNull(h.output.narrationState.value)
        assertFalse(h.focus.held)
    }

    @Test fun focusGrantedAfterDeadlineCannotStartAnyAudio() = withHarness { h ->
        h.focus.beforeGrant = { h.clock.now = 25_001 }
        h.speak()
        assertTrue(h.clips.requests.isEmpty() && h.base.requests.isEmpty())
        assertEquals(1, h.failures)
        assertEquals(0, h.completions)
        assertNull(h.output.narrationState.value)
        assertFalse(h.focus.held)
    }

    @Test fun mixedSceneKeepsNicknameAndQuestionOutsideThePairAndCompletesAfterAllOutput() = withHarness(true) { h ->
        h.speak(plan("Nova, $phrase 지금 출발할까?"))
        val prefix = h.base.requests.single()
        assertTrue(h.scenes.requests.isEmpty())
        prefix.start(); prefix.done()
        val scene = h.scenes.requests.single()
        assertEquals(cue, scene.cue)
        assertEquals(voice, scene.narration)
        assertTrue(h.clips.requests.isEmpty())
        assertFalse(h.output.narrationState.value!!.active)
        scene.speech(true)
        assertTrue(h.output.narrationState.value!!.active)
        scene.speech(false) // A cue tail is still owned; speech finishing does not permit input.
        assertFalse(h.output.narrationState.value!!.active)
        assertTrue(h.scenes.playing && h.focus.held)
        assertEquals(0, h.completions)
        assertEquals(1, h.base.requests.size)
        scene.done()
        assertFalse(h.scenes.playing)
        val question = h.base.requests.last()
        assertEquals(" 지금 출발할까?", question.plan.segments.single().text)
        assertEquals(2, h.base.requests.size)
        scene.done(); scene.speech(true); scene.error()
        assertEquals(0, h.failures + h.completions)
        assertFalse(h.output.narrationState.value!!.active)
        question.start(); question.done(); question.done()
        assertEquals(1, h.completions)
        assertEquals(1, h.starts)
        assertFalse(h.scenes.playing || h.focus.held)
        assertTrue(h.completionSawClosedOutput)
        assertNull(h.output.narrationState.value)
    }

    @Test fun mixedSceneCueLeadAndTailCannotAcknowledgeInputOrAnimateSpeech() = withHarness(true) { h ->
        h.speak()
        val scene = h.scenes.requests.single()
        assertEquals(0, h.starts + h.completions)
        assertFalse(h.output.narrationState.value!!.active)
        scene.speech(true); scene.speech(true)
        assertEquals(1, h.starts)
        scene.speech(false)
        assertEquals(0, h.completions)
        assertFalse(h.output.narrationState.value!!.active)
        assertTrue(h.focus.held)
        scene.done(); scene.done(); scene.speech(true); scene.error()
        assertEquals(1, h.completions)
        assertEquals(0, h.failures)
        assertFalse(h.scenes.playing || h.focus.held)
        assertTrue(h.completionSawClosedOutput)
        assertNull(h.output.narrationState.value)
    }

    @Test fun stoppingOrReplacingMixedSceneFencesLateEventsFromTheSamePlaybackId() = withHarness(true) { h ->
        h.speak(); val first = h.scenes.requests.single()
        h.output.stop()
        first.speech(true); first.done(); first.error()
        assertEquals(0, h.starts + h.completions + h.failures)
        h.speak(); val second = h.scenes.requests.last()
        h.speak(); val third = h.scenes.requests.last()
        second.speech(true); second.error(); second.done()
        assertEquals(0, h.starts + h.completions + h.failures)
        assertEquals(3, h.scenes.requests.size)
        third.speech(true); third.done()
        assertEquals(1, h.starts)
        assertEquals(1, h.completions)
        assertFalse(h.focus.held)
    }

    @Test fun focusLossAndSceneFailureCloseEverySourceBeforeNotifyingWithoutInput() = withHarness(true) { h ->
        h.speak(); val first = h.scenes.requests.single()
        first.speech(true)
        val previousStops = h.scenes.stops
        h.focus.loss!!.invoke(); h.focus.loss!!.invoke()
        assertEquals(1, h.interrupts)
        assertTrue(h.scenes.stops > previousStops)
        assertFalse(h.scenes.playing || h.focus.held)
        assertTrue(h.completionSawClosedOutput)
        assertNull(h.output.narrationState.value)
        first.done(); first.error()
        h.speak(); val second = h.scenes.requests.last()
        second.error(); second.done(); second.speech(true)
        assertEquals(1, h.failures)
        assertEquals(0, h.completions)
        assertFalse(h.scenes.playing || h.focus.held)
        assertTrue(h.base.requests.isEmpty() && h.clips.requests.isEmpty())
    }

    @Test fun mixedScenePastDeadlineClosesBeforeAnyCompletionCallback() = withHarness(true) { h ->
        h.speak(); val scene = h.scenes.requests.single()
        h.clock.now = 25_001
        scene.done(); scene.speech(true); scene.error()
        assertEquals(1, h.failures)
        assertEquals(0, h.completions)
        assertFalse(h.scenes.playing || h.focus.held)
        assertTrue(h.clock.tasks.isEmpty())
    }

    @Test fun mixedOutputStillPreservesAnUnmatchedPlanExactly() = withHarness(true) { h ->
        val original = plan("  이름과 알려지지 않은 원문이야. ").copy(segments = listOf(
            SpeechSegment("optional", "  첫 안내야. ", false), SpeechSegment("question", "계속 갈까?", true)))
        h.speak(original)
        assertEquals(original, h.base.requests.single().plan)
        assertTrue(h.scenes.requests.isEmpty() && h.clips.requests.isEmpty())
        h.base.requests.single().done()
        assertEquals(1, h.completions)
    }

    @Test fun standaloneQuietEdgesDoNotAnimateSpeechOrCompleteInputBeforeTheFinalFrame() = withHarness(true) { h ->
        h.speak(plan(plainPhrase))
        val scene = h.scenes.requests.single()
        assertNull(scene.cue)
        assertEquals(plainVoice, scene.narration)
        assertTrue(h.clips.requests.isEmpty() && h.base.requests.isEmpty())
        assertFalse(h.output.narrationState.value!!.active)
        assertEquals(0, h.starts + h.completions)
        scene.speech(true)
        assertTrue(h.output.narrationState.value!!.active)
        assertEquals(1, h.starts)
        scene.speech(false)
        assertFalse(h.output.narrationState.value!!.active)
        assertTrue(h.focus.held && h.scenes.playing)
        assertEquals(0, h.completions)
        scene.done(); scene.done(); scene.speech(true)
        assertEquals(1, h.completions)
        assertTrue(h.completionSawClosedOutput)
        assertNull(h.output.narrationState.value)
    }

    @Test fun standaloneBetweenDynamicTtsAndMixedSceneKeepsOnePlanAndOneFocusLease() = withHarness(true) { h ->
        h.speak(plan("별아, $plainPhrase $phrase"))
        val prefix = h.base.requests.single()
        prefix.start(); prefix.done()
        val ordinary = h.scenes.requests.single()
        assertNull(ordinary.cue)
        ordinary.speech(true); ordinary.speech(false)
        assertEquals(1, h.scenes.requests.size)
        assertEquals(0, h.completions)
        ordinary.done()
        val mixed = h.scenes.requests.last()
        assertEquals(cue, mixed.cue)
        assertFalse(h.output.narrationState.value!!.active)
        ordinary.speech(true); ordinary.done(); ordinary.error()
        assertEquals(0, h.failures + h.completions)
        assertFalse(h.output.narrationState.value!!.active)
        mixed.speech(true); mixed.done()
        assertEquals(1, h.completions)
        assertEquals(1, h.starts)
        assertEquals(1, h.focus.acquires)
        assertTrue(h.completionSawClosedOutput)
    }

    @Test fun standaloneStopAndReplacementFenceEveryOldSpeechAndCompletionEvent() = withHarness(true) { h ->
        h.speak(plan(plainPhrase)); val stopped = h.scenes.requests.single()
        h.output.stop()
        stopped.speech(true); stopped.done(); stopped.error()
        assertNull(h.output.narrationState.value)
        h.speak(plan(plainPhrase)); val replaced = h.scenes.requests.last()
        h.speak(plan(plainPhrase)); val current = h.scenes.requests.last()
        replaced.speech(true); replaced.speech(false); replaced.done(); replaced.error()
        assertEquals(0, h.starts + h.completions + h.failures)
        current.speech(true); current.speech(false); current.done()
        assertEquals(1, h.starts)
        assertEquals(1, h.completions)
        assertTrue(h.completionSawClosedOutput)
    }

    @Test fun standaloneFocusLossAndErrorCloseOutputWithoutSkippingToInput() = withHarness(true) { h ->
        h.speak(plan(plainPhrase)); val interrupted = h.scenes.requests.single()
        interrupted.speech(true)
        h.focus.loss!!.invoke()
        assertEquals(1, h.interrupts)
        assertNull(h.output.narrationState.value)
        assertFalse(h.focus.held || h.scenes.playing)
        interrupted.done(); interrupted.speech(true)
        h.speak(plan(plainPhrase)); val failed = h.scenes.requests.last()
        failed.error(); failed.speech(true); failed.done()
        assertEquals(1, h.failures)
        assertEquals(0, h.completions)
        assertFalse(h.focus.held || h.scenes.playing)
        assertNull(h.output.narrationState.value)
    }

    @Test fun completedCueDoesNotReplayForTheSameTurnAfterAnInterveningWait() = withHarness(true) { h ->
        h.speak(); h.scenes.requests.single().done()
        assertEquals(1, h.completions)
        h.speak(plan("천천히 생각해도 괜찮아.", "wait"))
        h.base.requests.single().done()
        h.speak(plan(id = "repeat"))
        val repeated = h.scenes.requests.last()
        assertNull("A completed entrance must not interrupt the repeated question", repeated.cue)
        assertEquals(voice, repeated.narration)
        assertEquals(2, h.scenes.requests.size)
        assertEquals(2, h.completions)
        repeated.speech(true); repeated.speech(false)
        assertEquals("Speech ending is not output completion", 2, h.completions)
        repeated.done()
        assertEquals(3, h.completions)
        assertTrue(h.completionSawClosedOutput)
        assertEquals(0, h.failures)
    }

    @Test fun aNewTurnOrEpochRestoresTheEntranceEvenForTheSameWords() = withHarness(true) { h ->
        h.speak(); h.scenes.requests.last().done()
        h.speak(plan(id = "repeat"))
        assertNull(h.scenes.requests.last().cue)
        h.scenes.requests.last().done()
        h.speak(plan(id = "next-turn").copy(turnId = "next-turn"))
        assertEquals(cue, h.scenes.requests.last().cue)
        h.scenes.requests.last().done()
        h.speak(plan(id = "new-epoch").copy(turnId = "next-turn", turnEpoch = 9))
        assertEquals(cue, h.scenes.requests.last().cue)
        h.scenes.requests.last().done()
        assertEquals(4, h.completions)
    }

    @Test fun explicitStopsAndRejectedRequestsClearCompletedCueDelivery() = withHarness(true) { h ->
        h.speak(); h.scenes.requests.last().done()
        h.output.stop()
        h.speak(plan(id = "after-stop"))
        assertEquals(cue, h.scenes.requests.last().cue)
        h.scenes.requests.last().done()
        h.output.refresh()
        h.speak(plan(id = "after-refresh"))
        assertEquals(cue, h.scenes.requests.last().cue)
        h.scenes.requests.last().done()
        assertEquals(3, h.completions)
        assertEquals(1, h.base.refreshes)
        h.speak(plan(id = "invalid").copy(segments = emptyList()))
        assertEquals(1, h.failures)
        h.speak(plan(id = "after-invalid"))
        assertEquals(cue, h.scenes.requests.last().cue)
        h.scenes.requests.last().done()
        h.base.readyState.value = false
        h.speak(plan(id = "unavailable"))
        assertEquals(2, h.failures)
        h.base.readyState.value = true
        h.speak(plan(id = "after-unavailable"))
        assertEquals(cue, h.scenes.requests.last().cue)
        h.scenes.requests.last().done()
        assertEquals(5, h.completions)
    }

    @Test fun incompleteReplacementAndItsLateCallbacksCannotConsumeTheCue() = withHarness(true) { h ->
        h.speak(); val replaced = h.scenes.requests.last()
        replaced.speech(true)
        h.speak(plan(id = "replacement")); val current = h.scenes.requests.last()
        assertEquals(cue, current.cue)
        replaced.speech(false); replaced.done(); replaced.error(); replaced.speech(true)
        assertEquals(0, h.completions + h.failures)
        assertEquals(2, h.scenes.requests.size)
        assertEquals("replacement", h.output.narrationState.value?.playbackId)
        current.speech(true); current.done()
        h.speak(plan(id = "after-completion"))
        val unfinishedRepeat = h.scenes.requests.last()
        assertNull(unfinishedRepeat.cue)
        h.speak(plan(id = "replace-repeat"))
        assertEquals("Replacing an unfinished repeat resets its remembered delivery", cue, h.scenes.requests.last().cue)
        unfinishedRepeat.done(); unfinishedRepeat.error(); unfinishedRepeat.speech(true)
        assertEquals(1, h.completions)
        assertEquals(0, h.failures)
        h.scenes.requests.last().done()
        assertEquals(2, h.completions)
    }

    @Test fun aLaterPartFailureClearsCueDeliveryAndCannotAcknowledgeThePlan() = withHarness(true) { h ->
        h.speak(plan("$phrase 이제 어디로 갈까?"))
        val scene = h.scenes.requests.single()
        scene.speech(true); scene.done()
        val question = h.base.requests.single()
        question.error(SpeechOutputFailure("fixture"))
        question.done(); scene.done()
        assertEquals(1, h.failures)
        assertEquals(0, h.completions)
        h.speak(plan(id = "retry-after-failure"))
        assertEquals(cue, h.scenes.requests.last().cue)
        h.scenes.requests.last().done()
        assertEquals(1, h.completions)
    }

    @Test fun sceneTailFinishesBeforeTheNextNarrationAndOldCallbacksStayFenced() = withHarness(true) { h ->
        h.speak(plan("$phrase $plainPhrase"))
        val entrance = h.scenes.requests.single()
        entrance.speech(true); entrance.speech(false)
        assertEquals(1, h.scenes.requests.size)
        assertTrue(h.scenes.playing && h.focus.held)
        assertEquals(0, h.completions)
        entrance.done()
        val next = h.scenes.requests.last()
        assertEquals(plainVoice, next.narration)
        assertNull(next.cue)
        assertEquals(2, h.scenes.requests.size)
        entrance.done(); entrance.error(); entrance.speech(true)
        assertEquals(0, h.completions + h.failures)
        assertFalse(h.output.narrationState.value!!.active)
        next.speech(true); next.speech(false); next.done()
        assertEquals(1, h.completions)
        assertTrue(h.completionSawClosedOutput)
        assertFalse(h.focus.held || h.scenes.playing)
    }
}
