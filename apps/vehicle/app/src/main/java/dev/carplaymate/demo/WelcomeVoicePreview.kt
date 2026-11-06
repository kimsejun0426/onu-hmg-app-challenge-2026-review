package dev.carplaymate.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class WelcomePreviewPhase { IDLE, PREPARING, PLAYING, FAILED }

internal data class WelcomePreviewState(
    val available: Boolean = false,
    val phase: WelcomePreviewPhase = WelcomePreviewPhase.IDLE,
    val speaking: Boolean = false,
)

/**
 * One explicitly requested, local greeting before family setup. This owns its player/focus
 * only while the welcome page is eligible; it has no speech recognizer, TTS, session or storage.
 * Calls and player callbacks are serialized by the UI's main thread, like StoryScenePlayer.
 */
internal class WelcomeVoicePreview(
    private val player: StoryScenePlayer,
    private val focus: PlaybackAudioFocus,
    private val greeting: () -> BundledAudio?,
) {
    private val mutableState = MutableStateFlow(WelcomePreviewState())
    val state: StateFlow<WelcomePreviewState> = mutableState
    private class Attempt
    private var active: Attempt? = null
    private var ownsFocus = false
    private var closed = false

    /** Eligibility returning never restarts a stopped greeting. */
    fun setAvailable(available: Boolean) {
        val permitted = available && !closed
        if (!permitted) stop()
        mutableState.value = mutableState.value.copy(available = permitted)
    }

    fun toggle() {
        if (active != null) { stop(); return }
        if (closed || !mutableState.value.available) return
        val request = Attempt().also { active = it }
        mutableState.value = mutableState.value.copy(phase = WelcomePreviewPhase.PREPARING, speaking = false)
        val audio = try { greeting() } catch (_: Exception) { null }
        if (active !== request) return
        if (audio == null) { finish(request, WelcomePreviewPhase.FAILED); return }
        val granted = try {
            focus.acquire { if (active === request) finish(request, WelcomePreviewPhase.IDLE) }
        } catch (_: Exception) { false }
        // A synchronous focus loss may invalidate this request before acquire returns.
        if (active !== request) { focus.release(); return }
        ownsFocus = granted
        if (!granted) {
            focus.release()
            finish(request, WelcomePreviewPhase.FAILED)
            return
        }
        try {
            player.playNarration(audio,
                complete = { finish(request, WelcomePreviewPhase.IDLE) },
                failure = { finish(request, WelcomePreviewPhase.FAILED) },
                speechState = { speaking ->
                    if (active === request && !closed) mutableState.value = mutableState.value.copy(
                        phase = WelcomePreviewPhase.PLAYING, speaking = speaking,
                    )
                },
            )
        } catch (_: Exception) { finish(request, WelcomePreviewPhase.FAILED) }
    }

    /** Invalidate callbacks before stopping output and relinquishing its focus. */
    private fun finish(request: Attempt, phase: WelcomePreviewPhase) {
        if (active !== request) return
        active = null
        try { player.stop() } finally {
            if (ownsFocus) {
                ownsFocus = false
                focus.release()
            }
            mutableState.value = mutableState.value.copy(phase = phase, speaking = false)
        }
    }

    fun stop() {
        val request = active
        if (request != null) finish(request, WelcomePreviewPhase.IDLE)
        else mutableState.value = mutableState.value.copy(phase = WelcomePreviewPhase.IDLE, speaking = false)
    }

    fun shutdown() {
        if (closed) return
        closed = true
        try { setAvailable(false) } finally { player.shutdown() }
    }

    companion object {
        const val TEXT = "안녕, 나는 오누야. 우리 차에서 함께 모험하자. 네가 고른 길로 나도 같이 갈게."
        const val AUDIO_ID = "onu_welcome_b2"
        const val ASSET = "story_audio/onu_welcome_b2.wav"

        /** The greeting is a single exact clip, with no cue or device TTS fragments. */
        fun greetingFrom(assets: NarrationAssets): BundledAudio? {
            if (assets.failure != null) return null
            val clip = assets.narration.resolve(TEXT).singleOrNull() as? NarrationPart.Audio ?: return null
            return clip.audio.takeIf {
                clip.spokenText == TEXT && it.id == AUDIO_ID && it.asset == ASSET
            }
        }
    }
}
