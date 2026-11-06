package dev.carplaymate.demo

import org.junit.Assert.*
import org.junit.Test

/** Callback/lease ordering only: no AudioTrack, microphone, SDK or physical listening. */
class WelcomeVoicePreviewTest {
    private val audio = BundledAudio(WelcomeVoicePreview.AUDIO_ID, WelcomeVoicePreview.ASSET, "a".repeat(64), 9000)
    private class Focus : PlaybackAudioFocus {
        var held = false
        var granted = true
        var acquired = 0
        var released = 0
        var synchronousLoss = false
        val losses = mutableListOf<() -> Unit>()
        override fun acquire(onLost: () -> Unit): Boolean {
            acquired++
            losses += onLost
            held = granted
            if (synchronousLoss) onLost()
            return granted
        }
        override fun release() { held = false; released++ }
    }
    private class Player : StoryScenePlayer {
        data class Call(val complete: () -> Unit, val failure: () -> Unit, val speech: (Boolean) -> Unit)
        val calls = mutableListOf<Call>()
        var playing = false
        var stops = 0
        var shutdowns = 0
        var throwOnPlay = false
        override fun play(cue: BundledAudio, narration: BundledAudio, complete: () -> Unit,
                          failure: () -> Unit, speechState: (Boolean) -> Unit) = error("Greeting must not mix a cue")
        override fun playNarration(narration: BundledAudio, complete: () -> Unit,
                                   failure: () -> Unit, speechState: (Boolean) -> Unit) {
            calls += Call(complete, failure, speechState)
            playing = true
            if (throwOnPlay) throw IllegalStateException("Synthetic player failure")
        }
        override fun stop() { playing = false; stops++ }
        override fun shutdown() { playing = false; shutdowns++ }
    }
    private inner class Harness {
        val player = Player()
        val focus = Focus()
        var provideAudio = true
        val preview = WelcomeVoicePreview(player, focus) { audio.takeIf { provideAudio } }
        fun start() { preview.setAvailable(true); preview.toggle() }
        fun assertQuiet(phase: WelcomePreviewPhase = WelcomePreviewPhase.IDLE) {
            assertFalse(player.playing || focus.held || preview.state.value.speaking)
            assertEquals(phase, preview.state.value.phase)
        }
    }

    @Test fun unknownVehicleStateAndAnyOwnedStoryOrCapturePreventWelcomePreview() {
        val ready = DeveloperUiState(vehicleAccess = VehicleAccessState.NON_AUTOMOTIVE_DEMO)
        assertTrue(canPreviewWelcome(ready))
        val blocked = listOf(
            DeveloperUiState(), ready.copy(onboarded = true), ready.copy(busy = true),
            ready.copy(quiet = false), ready.copy(fullUiAllowed = false),
            ready.copy(microphoneListening = true), ready.copy(captureStage = CaptureStage.PROCESSING),
            ready.copy(outputStage = OutputStage.PREPARING), ready.copy(familyDeletionPending = true),
            ready.copy(localStorageProblem = true),
        )
        blocked.forEach { assertFalse(canPreviewWelcome(it)) }
    }

    @Test fun initialRenderAndEligibilityReturnNeverAcquireOrAutoplay() {
        val h = Harness()
        h.preview.toggle()
        h.preview.setAvailable(true)
        h.preview.setAvailable(false)
        h.preview.setAvailable(true)
        h.assertQuiet()
        assertEquals(0, h.focus.acquired)
        assertTrue(h.player.calls.isEmpty())
    }

    @Test fun secondTapCancelsPreparationAndLateCallbacksCannotRestartIt() {
        val h = Harness(); h.start()
        assertEquals(WelcomePreviewPhase.PREPARING, h.preview.state.value.phase)
        val old = h.player.calls.single()
        h.preview.toggle()
        h.assertQuiet()
        old.speech(true); old.complete(); old.failure()
        h.assertQuiet()
        assertEquals(1, h.focus.acquired)
        assertEquals(1, h.focus.released)
    }

    @Test fun stopThenRestartRejectsEveryCallbackFromTheEarlierGreeting() {
        val h = Harness(); h.start()
        val first = h.player.calls.single()
        first.speech(true)
        assertTrue(h.preview.state.value.speaking)
        h.preview.toggle(); h.preview.toggle()
        val second = h.player.calls.last()
        first.complete(); first.failure(); first.speech(true); first.speech(false)
        assertTrue(h.player.playing && h.focus.held)
        assertEquals(WelcomePreviewPhase.PREPARING, h.preview.state.value.phase)
        second.speech(true)
        assertTrue(h.preview.state.value.speaking)
        second.complete()
        h.assertQuiet()
        assertEquals(2, h.focus.acquired)
        assertEquals(2, h.focus.released)
    }

    @Test fun focusLossAndAvailabilityLossStopOutputWithoutAutoResume() {
        for (whileSpeaking in listOf(false, true)) {
            val h = Harness(); h.start()
            val call = h.player.calls.single()
            if (whileSpeaking) call.speech(true)
            h.focus.losses.single()()
            h.assertQuiet()
            h.preview.setAvailable(false); h.preview.setAvailable(true)
            call.speech(true); call.complete()
            h.assertQuiet()
            assertEquals(1, h.focus.acquired)
            h.preview.toggle()
            assertEquals(2, h.player.calls.size)
            h.preview.setAvailable(false)
            h.assertQuiet()
            h.preview.setAvailable(true)
            assertEquals(2, h.player.calls.size)
        }
    }

    @Test fun deniedFocusAndMissingClipNeverStartThePlayer() {
        val denied = Harness().also { it.focus.granted = false }; denied.start()
        denied.assertQuiet(WelcomePreviewPhase.FAILED)
        assertTrue(denied.player.calls.isEmpty())
        val missing = Harness().also { it.provideAudio = false }; missing.start()
        missing.assertQuiet(WelcomePreviewPhase.FAILED)
        assertEquals(0, missing.focus.acquired)
        assertTrue(missing.player.calls.isEmpty())
    }

    @Test fun completionAndFailureCloseOutputBeforeReturningToTheIdleOrRetryState() {
        val h = Harness(); h.start()
        h.player.calls.single().speech(true)
        h.player.calls.single().complete()
        h.assertQuiet()
        h.preview.toggle()
        h.player.calls.last().failure()
        h.assertQuiet(WelcomePreviewPhase.FAILED)
        h.preview.toggle()
        h.player.calls.last().speech(true)
        assertTrue(h.preview.state.value.speaking)
        h.preview.stop()
        h.assertQuiet()
        assertEquals(3, h.focus.acquired)
        assertEquals(3, h.focus.released)
    }

    @Test fun synchronousFocusLossOrPlayerExceptionCannotLeakItsLease() {
        val lost = Harness().also { it.focus.synchronousLoss = true }; lost.start()
        lost.assertQuiet()
        assertTrue(lost.player.calls.isEmpty())
        assertEquals(1, lost.focus.released)
        val broken = Harness().also { it.player.throwOnPlay = true }; broken.start()
        broken.assertQuiet(WelcomePreviewPhase.FAILED)
        broken.player.calls.single().speech(true)
        broken.assertQuiet(WelcomePreviewPhase.FAILED)
        assertEquals(1, broken.focus.released)
    }

    @Test fun disposalIsFinalAndOldLossCallbacksCannotStopANewerAttempt() {
        val h = Harness(); h.start()
        val oldLoss = h.focus.losses.single()
        h.preview.stop(); h.preview.toggle()
        oldLoss()
        assertTrue(h.player.playing && h.focus.held)
        val last = h.player.calls.last()
        h.preview.shutdown(); h.preview.shutdown()
        last.speech(true); last.complete(); last.failure()
        h.preview.setAvailable(true); h.preview.toggle()
        h.assertQuiet()
        assertFalse(h.preview.state.value.available)
        assertEquals(1, h.player.shutdowns)
        assertEquals(2, h.player.calls.size)
    }

    @Test fun exactGreetingMustResolveToOneExpectedAssetWithoutCueOrTtsFragments() {
        fun clip(text: String = WelcomeVoicePreview.TEXT, asset: BundledAudio = audio, cue: BundledAudio? = null) =
            NarrationClip(text, BundledNarration.sha256(text.toByteArray(Charsets.UTF_8)), asset, cue)
        assertEquals(audio, WelcomeVoicePreview.greetingFrom(NarrationAssets(BundledNarration(listOf(clip())))))
        assertNull(WelcomeVoicePreview.greetingFrom(NarrationAssets(BundledNarration.EMPTY)))
        assertNull(WelcomeVoicePreview.greetingFrom(NarrationAssets(BundledNarration(listOf(
            clip(asset = audio.copy(asset = "story_audio/other_voice.wav")),
        )))))
        val cue = BundledAudio("cue", "story_audio/cue.wav", "b".repeat(64), 1000)
        assertNull(WelcomeVoicePreview.greetingFrom(NarrationAssets(BundledNarration(listOf(clip(cue = cue))))))
        assertNull(WelcomeVoicePreview.greetingFrom(NarrationAssets(BundledNarration(listOf(
            clip(text = "안녕, 나는 오누야."),
        )))))
    }
}
