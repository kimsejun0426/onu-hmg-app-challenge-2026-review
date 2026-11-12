package dev.carplaymate.demo

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Complete-plan output; adjacent authored cue/narration may share one bounded scene. */
internal class ComposedSpeechOutput(
    private val narration: NarrationAssets,
    private val clips: StoryClipPlayer,
    private val focus: PlaybackAudioFocus,
    private val onInterrupted: () -> Unit,
    baseFactory: (PlaybackAudioFocus, () -> Unit) -> SpeechOutput,
    private val timer: CaptureTimer = MainThreadCaptureTimer(),
    private val scenes: StoryScenePlayer? = null,
) : SpeechOutput {
    private var closed = false
    private var active: Attempt? = null
    private var ownsFocus = false
    // Only app-authored cue IDs from a fully completed plan; never speech text or child input.
    // Recovery prompts retain a turn/epoch; actual choices, resume and explicit stop reset it.
    private data class CompletedCues(val turnId: String, val turnEpoch: Long, val ids: Set<String>) {
        fun matches(plan: SpeechPlan) = turnId == plan.turnId && turnEpoch == plan.turnEpoch
    }
    private var completedCues: CompletedCues? = null
    private val mutableNarration = MutableStateFlow<SpeechNarrationState?>(null)
    override val narrationState: StateFlow<SpeechNarrationState?> = mutableNarration
    private val borrowedFocus = object : PlaybackAudioFocus {
        override fun acquire(onLost: () -> Unit) = !closed && active != null && ownsFocus
        override fun release() = Unit // The complete plan, not an individual TTS chunk, owns it.
    }
    private val base = baseFactory(borrowedFocus) { interrupt() }
    override val status: StateFlow<String> = narration.failure?.let { MutableStateFlow(it.message) } ?: base.status
    override val readyState: StateFlow<Boolean> = narration.failure?.let { MutableStateFlow(false) } ?: base.readyState
    override val availabilityFailure: SpeechOutputFailure? get() = narration.failure ?: base.availabilityFailure

    private class Attempt(
        val plan: SpeechPlan,
        val generation: Long,
        val parts: List<NarrationPart>,
        val complete: () -> Unit,
        val failure: (SpeechOutputFailure) -> Unit,
        val started: () -> Unit,
        val expiresAt: Long,
        val preserveOriginalPlan: Boolean,
    ) {
        var next = 0
        var step = 0L
        var startNotified = false
        var timeout: Runnable? = null
    }

    override fun refresh() {
        checkMain()
        if (closed) return
        stop()
        base.refresh() // Capability refresh never plays clips or acquires a lease.
    }

    override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
                       failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) {
        checkMain()
        if (active != null) completedCues = null // A replacement is not a completed repeat.
        halt()
        completedCues = completedCues?.takeIf { it.matches(plan) }
        if (closed) { stop(); failure(SpeechOutputFailure("음성 연결이 종료됐어요.", RecoveryCause.OUTPUT_UNAVAILABLE)); return }
        availabilityFailure?.let { stop(); failure(it); return }
        if (!readyState.value || plan.segments.isEmpty() || plan.segments.any { it.text.isBlank() }) {
            stop()
            failure(SpeechOutputFailure("안내할 내용을 확인하지 못해 조용히 멈췄어요.")); return
        }
        val resolved = plan.segments.flatMap { narration.narration.resolve(it.text) }
            .filterNot { it is NarrationPart.Text && it.text.isBlank() }
            .filterNot { it is NarrationPart.Audio && it.spokenText == null &&
                it.audio.id in completedCues?.ids.orEmpty() }
        val preserveOriginalPlan = resolved.none { it is NarrationPart.Audio }
        val parts = if (preserveOriginalPlan) listOf(NarrationPart.Text(plan.segments.joinToString("\n") { it.text })) else resolved
        if (parts.isEmpty()) { stop(); failure(SpeechOutputFailure("안내할 내용을 확인하지 못해 조용히 멈췄어요.")); return }
        val estimated = parts.sumOf { when (it) {
            is NarrationPart.Text -> it.text.length * 220L
            // Summing the full cue and voice durations bounds the slower overlapping scene.
            is NarrationPart.Audio -> if (scenes != null) narratedSceneMillis(it.audio.durationMillis)
                else it.audio.durationMillis
        } }
        val limit = (estimated + 15_000L).coerceIn(25_000L, 120_000L)
        val current = Attempt(plan, generation, parts, complete, failure, started, timer.nowMillis() + limit, preserveOriginalPlan)
        active = current
        val granted = try { focus.acquire { if (active === current) interrupt() } } catch (_: Exception) { false }
        if (active !== current) return
        ownsFocus = granted
        if (!granted) { fail(current, SpeechOutputFailure("오디오 사용이 허용되지 않아 조용히 멈췄어요.", RecoveryCause.AUDIO_FOCUS)); return }
        current.timeout = Runnable { fail(current, timeoutFailure()) }.also {
            timer.postDelayed(it, (current.expiresAt - timer.nowMillis()).coerceAtLeast(0L))
        }
        advance(current)
    }

    private fun accepting(current: Attempt, step: Long? = null): Boolean {
        if (closed || active !== current || (step != null && current.step != step)) return false
        if (timer.nowMillis() >= current.expiresAt) { fail(current, timeoutFailure()); return false }
        return true
    }

    private fun advance(current: Attempt) {
        if (!accepting(current)) return
        if (current.next >= current.parts.size) {
            val heard = completedCues?.takeIf { it.matches(current.plan) }?.ids.orEmpty() +
                current.parts.filterIsInstance<NarrationPart.Audio>()
                    .filter { it.spokenText == null }.map { it.audio.id }
            halt() // Close all output and release the lease before input can be requested.
            completedCues = if (heard.isEmpty()) null else
                CompletedCues(current.plan.turnId, current.plan.turnEpoch, heard)
            current.complete()
            return
        }
        val part = current.parts[current.next++]
        val nextNarration = (current.parts.getOrNull(current.next) as? NarrationPart.Audio)
            ?.takeIf { it.spokenText != null }
        val cue = (part as? NarrationPart.Audio)?.takeIf {
            it.spokenText == null && scenes != null && nextNarration != null
        }
        if (cue != null) current.next++ // Only this exact adjacent pair is consumed together.
        val step = ++current.step
        // A completed spoken part must not keep the mouth active through a cue or preparation.
        mutableNarration.value = SpeechNarrationState(current.plan.playbackId, current.generation, false)
        val done = {
            if (accepting(current, step)) {
                ++current.step // Duplicate callbacks cannot advance the following part.
                advance(current)
            }
        }
        val start = {
            if (accepting(current, step)) {
                mutableNarration.value = SpeechNarrationState(current.plan.playbackId, current.generation, true)
                // Retain the original once-per-plan started contract for protocol/presentation.
                if (!current.startNotified) {
                    current.startNotified = true
                    current.started()
                }
            }
        }
        val speechState: (Boolean) -> Unit = { speaking ->
            if (speaking) start()
            else if (accepting(current, step)) {
                mutableNarration.value = SpeechNarrationState(current.plan.playbackId, current.generation, false)
            }
        }
        try {
            if (cue != null && nextNarration != null) {
                checkNotNull(scenes).play(cue.audio, nextNarration.audio, done,
                    { if (accepting(current, step)) fail(current, SpeechOutputFailure("이야기 소리를 재생하지 못해 조용히 멈췄어요.")) },
                    speechState,
                )
            } else when (part) {
                is NarrationPart.Text -> base.speak(
                    if (current.preserveOriginalPlan) current.plan else current.plan.copy(playbackId = "${current.plan.playbackId}:part:${current.next}",
                        segments = listOf(SpeechSegment("part-${current.next}", part.text, true))),
                    current.generation, done,
                    { if (accepting(current, step)) fail(current, it) }, start,
                )
                is NarrationPart.Audio -> if (part.spokenText != null && scenes != null) {
                    // The same bounded player reports real voice edges for ordinary clips too.
                    // Quiet padding remains output-owned; it never opens input early.
                    scenes.playNarration(part.audio, done,
                        { if (accepting(current, step)) fail(current, SpeechOutputFailure("이야기 음원 재생이 끝나지 않아 조용히 멈췄어요.")) },
                        speechState,
                    )
                } else clips.play(part.audio, done,
                    { if (accepting(current, step)) fail(current, SpeechOutputFailure("이야기 음원 재생이 끝나지 않아 조용히 멈췄어요.")) },
                    { if (part.spokenText != null) start() },
                )
            }
        } catch (_: Exception) {
            if (accepting(current, step)) fail(current, SpeechOutputFailure("음성 안내를 시작하지 못해 조용히 멈췄어요."))
        }
    }

    private fun fail(current: Attempt, failure: SpeechOutputFailure) {
        if (active !== current) return
        stop()
        current.failure(failure)
    }

    private fun interrupt() {
        if (active == null) return
        stop() // Fence every callback and halt every source before notifying the controller.
        onInterrupted()
    }

    override fun stop() {
        checkMain()
        completedCues = null
        halt()
    }

    /** Stop every source immediately. Only normal plan completion may retain cue delivery. */
    private fun halt() {
        val previous = active
        active = null
        mutableNarration.value = null
        previous?.timeout?.let(timer::remove)
        ownsFocus = false
        runCatching { scenes?.stop() }
        runCatching { clips.stop() }
        runCatching { base.stop() }
        focus.release()
    }

    override fun shutdown() {
        checkMain()
        stop()
        closed = true
        scenes?.shutdown()
        clips.shutdown()
        base.shutdown()
    }

    private fun timeoutFailure() = SpeechOutputFailure("음성 안내가 끝나지 않아 조용히 멈췄어요.")
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    companion object {
        fun create(context: Context, onInterrupted: () -> Unit,
                   baseFactory: (PlaybackAudioFocus, () -> Unit) -> SpeechOutput): SpeechOutput {
            val assets = loadNarrationAssets(context)
            // An absent/empty optional bundle leaves the original provider and plan unchanged.
            if (assets.failure == null && assets.narration.isEmpty) {
                return baseFactory(AndroidPlaybackAudioFocus(context), onInterrupted)
            }
            return ComposedSpeechOutput(assets, AndroidClipPlayer(context),
                AndroidPlaybackAudioFocus(context), onInterrupted, baseFactory,
                scenes = AndroidScenePlayer(context))
        }
    }
}
