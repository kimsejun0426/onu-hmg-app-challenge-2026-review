package dev.carplaymate.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class LetterPhase { READY, OUTPUT, WAIT, INPUT_START, LISTENING, PROCESSING, INPUT_ERROR, OUTPUT_ERROR }
internal enum class LetterMode { VOICE, LISTEN, READ }
internal enum class MixedAudioPart { MUSIC, VOICE, TAIL }

internal data class LetterPresentation<S>(
    val turn: LetterTurn<S>,
    val phase: LetterPhase,
    val touch: Boolean = false,
    val issue: String? = null,
    val speaking: Boolean = false,
    val soundOnly: Boolean = false,
    val currentCue: String? = null,
    val pendingEnding: Boolean = false,
    val mixedPart: MixedAudioPart? = null,
)

/** Only authored game enums and flags are saved, never transcript, capture or audio. */
internal fun JumpState.letterCheckpoint(): String =
    listOf(step.name, guess?.name.orEmpty(), ending?.name.orEmpty(), assisted.toString(), paused.toString())
        .joinToString("|") + if (revealedWithHelp) "|true" else ""

internal fun restoreLetter(saved: String?): JumpState? = runCatching {
    val fields = saved?.split('|') ?: return null
    require(fields.size in 5..6)
    // Preserve every old five-field canonical token byte for byte. Only true needs the extension.
    val revealedWithHelp = fields.size == 6
    if (revealedWithHelp) require(fields[5] == "true")
    val state = JumpState(JumpStep.valueOf(fields[0]), fields[1].takeIf(String::isNotEmpty)?.let(JumpGuess::valueOf),
        fields[2].takeIf(String::isNotEmpty)?.let(JumpEnding::valueOf),
        fields[3].toBooleanStrict(), fields[4].toBooleanStrict(), revealedWithHelp)
    require(!revealedWithHelp || state.step in setOf(JumpStep.DIRECT, JumpStep.END))
    state
}.getOrNull()

/** Only a native completion callback finishes a letter. Progress may outlive its nominal duration. */
internal class LetterPlaybackProgress(private val durationMs: Long, private val startedAtMs: Long) {
    private var highWaterMs = 0L
    private var lastAdvanceMs = startedAtMs
    private var sawPosition = false

    fun stalled(nowMs: Long, positionMs: Long?): Boolean {
        if (positionMs != null && positionMs >= 0) {
            sawPosition = true
            val bounded = positionMs.coerceAtMost(durationMs)
            if (bounded > highWaterMs) {
                highWaterMs = bounded
                lastAdvanceMs = nowMs
            }
        }
        // Some renderers temporarily have no position. Give one nominal clip from actual start;
        // once a timeline is observed, identical, backwards and missing samples cannot renew it.
        val deadline = if (sawPosition) lastAdvanceMs + STALL_MS else startedAtMs + durationMs + STALL_MS
        return nowMs >= deadline
    }

    companion object {
        const val STALL_MS = 12_000L
    }
}

/** One explicitly owned letter. The serial invalidates callbacks before revoking either lease. */
internal class LetterSession<S : Any>(
    private val episode: LetterEpisode<S>,
    private val player: StoryClipPlayer,
    private val lookup: (String) -> BundledAudio?,
    private val focus: PlaybackAudioFocus,
    private val microphone: SpeechCaptureInput,
    private val captureFocusFactory: (() -> Unit) -> CaptureFocus,
    private val signals: CaptureSignals,
    private val allowed: () -> Boolean,
    private val checkpoint: (String) -> Unit,
    val mode: LetterMode,
    saved: S?,
) {
    private val timer = Handler(Looper.getMainLooper())
    private val gate = CaptureGate()
    private val inputFocus = captureFocusFactory { pause() }
    private var serial = 0L
    private var clipToken = 0L
    private val transportSessionId = UUID.randomUUID().toString()
    private var transportCue: String? = null
    private var transportClip: BundledAudio? = null
    private var nativeClipStarted = false
    // A broadcast earns its ending only after the final local clip actually completes.
    private var endingOrigin: S? = null
    private var timeout: Runnable? = null
    private var watchPosition: Runnable? = null
    private var closed = false
    private val restored = saved?.let {
        if (episode.stage(it) in setOf(0, 4) || episode.paused(it)) episode.resume(it)
        else episode.choose(it, "rest")
    } ?: episode.initial()
    private val mutable = MutableStateFlow(LetterPresentation(restored, LetterPhase.READY))
    val state: StateFlow<LetterPresentation<S>> = mutable
    private val mutableTransport = MutableStateFlow(
        LetterTransportSnapshot(transportSessionId, serial, LetterTransportPhase.STOPPED))
    val transport: StateFlow<LetterTransportSnapshot> = mutableTransport

    /** Read the native position only while this session's current clip is actually running. */
    fun transportPositionMillis(): Long? {
        if (closed || !nativeClipStarted || transportClip == null ||
            mutable.value.phase != LetterPhase.OUTPUT) return null
        return runCatching { player.positionMillis()?.takeIf { it >= 0L } }.getOrNull()
    }

    private fun publish(presentation: LetterPresentation<S>) {
        mutable.value = presentation
        publishTransport()
    }

    /** Same main-thread transition as presentation; observation never acquires focus or starts I/O. */
    private fun publishTransport(phaseOverride: LetterTransportPhase? = null) {
        val presentation = mutable.value
        val phase = phaseOverride ?: when {
            closed || episode.paused(presentation.turn.state) -> LetterTransportPhase.STOPPED
            presentation.phase == LetterPhase.OUTPUT ->
                if (nativeClipStarted) LetterTransportPhase.PLAYING else LetterTransportPhase.PREPARING
            presentation.phase in setOf(LetterPhase.INPUT_START, LetterPhase.LISTENING) -> LetterTransportPhase.INPUT
            presentation.phase == LetterPhase.PROCESSING -> LetterTransportPhase.PROCESSING
            presentation.phase in setOf(LetterPhase.INPUT_ERROR, LetterPhase.OUTPUT_ERROR) ||
                presentation.issue != null -> LetterTransportPhase.ERROR
            else -> LetterTransportPhase.STOPPED
        }
        val output = phase == LetterTransportPhase.PREPARING || phase == LetterTransportPhase.PLAYING
        mutableTransport.value = LetterTransportSnapshot(
            sessionId = transportSessionId,
            revision = serial,
            phase = phase,
            cue = transportCue.takeIf { output },
            durationMs = transportClip?.durationMillis?.takeIf { output },
            positionMs = if (phase == LetterTransportPhase.PLAYING) transportPositionMillis() else null,
        )
    }

    private fun clearTransportClip() {
        nativeClipStarted = false
        transportCue = null
        transportClip = null
    }

    fun choose(id: String) {
        if (!usable()) return
        val now = mutable.value
        if (now.phase == LetterPhase.OUTPUT || now.phase == LetterPhase.OUTPUT_ERROR || episode.paused(now.turn.state) || now.pendingEnding) return
        if (now.turn.choices.none { it.id == id }) return
        // Active voice windows can be answered on screen only after explicit assistance.
        if (mode == LetterMode.VOICE && now.turn.acceptsAnswer && !now.touch) return
        present(episode.choose(now.turn.state, id))
    }

    fun replay() {
        if (!usable()) return
        val now = mutable.value
        if (episode.paused(now.turn.state) || now.phase == LetterPhase.OUTPUT) return
        // After the first question, replay the sound and question without the greeting.
        // A hint or a failed opening still needs its original sequence.
        val turn = if (now.phase != LetterPhase.OUTPUT_ERROR && now.turn.cues.firstOrNull() == "opening")
            episode.resume(now.turn.state) else now.turn.copy(answerLabel = null)
        present(turn, now.touch)
    }

    fun touchAssistance() {
        if (!usable()) return
        val now = mutable.value
        if (!now.turn.acceptsAnswer || now.phase in setOf(LetterPhase.OUTPUT, LetterPhase.OUTPUT_ERROR) || episode.paused(now.turn.state)) return
        revoke()
        publish(now.copy(phase = LetterPhase.WAIT, touch = true, issue = null))
    }

    fun resume() {
        if (!usable()) return
        val now = mutable.value.turn
        if (episode.paused(now.state)) present(episode.choose(now.state, "resume"))
    }

    /** Failure before FGS promotion cannot claim that audio or capture began. */
    fun activationFailed(message: String = "음성을 시작하지 못했어. 보호자와 앱의 음성 권한을 확인해 줘.") {
        if (closed) return
        revoke()
        publish(mutable.value.copy(phase = LetterPhase.READY, speaking = false,
            soundOnly = false,
            issue = message))
    }

    fun pause() {
        if (closed) return
        revoke()
        val now = endingOrigin?.let(episode::resume) ?: mutable.value.turn
        endingOrigin = null
        if (episode.paused(now.state) || episode.stage(now.state) in setOf(0, 4)) {
            publish(LetterPresentation(now, LetterPhase.READY))
            return
        }
        val resting = episode.choose(now.state, "rest")
        checkpoint(episode.encode(resting.state))
        publish(LetterPresentation(resting, LetterPhase.READY))
    }

    fun close() {
        if (closed) return
        closed = true
        revoke()
        signals.release()
        player.shutdown()
    }

    private fun usable(): Boolean {
        if (closed) return false
        if (!allowed()) { pause(); return false }
        return true
    }

    private fun accepts(key: Long): Boolean = key == serial && usable()

    private fun present(turn: LetterTurn<S>, touch: Boolean = false) {
        val previous = mutable.value.turn.state
        revoke()
        val key = serial
        endingOrigin = if (mode != LetterMode.READ && episode.stage(turn.state) == 4 && turn.cues.isNotEmpty())
            endingOrigin ?: previous.takeIf { episode.stage(it) != 4 } else null
        checkpoint(episode.encode(endingOrigin ?: turn.state))
        publish(LetterPresentation(turn, LetterPhase.READY, touch, pendingEnding = endingOrigin != null))
        if (episode.paused(turn.state)) return
        if (mode == LetterMode.READ || turn.cues.isEmpty()) { awaitAnswer(key); return }
        publish(mutable.value.copy(phase = LetterPhase.OUTPUT))
        if (!runCatching { focus.acquire { if (key == serial) pause() } }.getOrDefault(false)) {
            if (accepts(key)) outputProblem()
            return
        }
        if (accepts(key)) playClip(key, 0)
    }

    private fun playClip(key: Long, index: Int) {
        if (!accepts(key)) return
        val cues = mutable.value.turn.cues
        if (index == cues.size) {
            focus.release()
            awaitAnswer(key)
            return
        }
        val soundOnly = episode.isEffect(cues[index])
        val finale = episode.id == "rain_music" && cues[index] in setOf("finale_drops", "finale_wiper")
        clearTransportClip()
        transportCue = cues[index]
        publish(mutable.value.copy(speaking = false, soundOnly = soundOnly, currentCue = cues[index],
            mixedPart = if (finale) MixedAudioPart.MUSIC else null))
        val asset = runCatching { lookup(cues[index]) }.getOrNull()
        if (asset == null) { outputProblem(); return }
        transportClip = asset
        publishTransport()
        val token = ++clipToken
        fun current() = token == clipToken && accepts(key)
        val preparationTimeout = Runnable { if (current()) outputProblem() }
        timeout = preparationTimeout
        timer.postDelayed(preparationTimeout, 15_000L)
        try {
            player.play(asset, {
                if (current()) {
                    ++clipToken
                    stopWatchingPosition()
                    clearClipTimeout()
                    // Completion closes the old timeline before the next clip can prepare.
                    clearTransportClip()
                    publishTransport(LetterTransportPhase.PREPARING)
                    playClip(key, index + 1)
                }
            }, { if (current()) outputProblem() }, {
                if (current() && timeout === preparationTimeout) {
                    nativeClipStarted = true
                    publishTransport()
                    clearClipTimeout()
                    val progress = LetterPlaybackProgress(asset.durationMillis, SystemClock.uptimeMillis())
                    val playbackWatch = object : Runnable {
                        override fun run() {
                            if (timeout !== this || !current()) return
                            if (progress.stalled(SystemClock.uptimeMillis(), player.positionMillis())) outputProblem()
                            else {
                                publishTransport() // Sample actual position; never advance it from elapsed time.
                                timer.postDelayed(this, 1_000L)
                            }
                        }
                    }
                    timeout = playbackWatch
                    timer.postDelayed(playbackWatch, 1_000L)
                    val window = asset.speechWindow
                    if (window != null) trackMixedVoice(window, ::current)
                    else if (!soundOnly) publish(mutable.value.copy(speaking = true))
                }
            })
        } catch (_: Exception) { if (current()) outputProblem() }
    }

    /** Scheduling only wakes this observer. The native playback position determines every transition. */
    private fun trackMixedVoice(window: AudioSpeechWindow, current: () -> Boolean) {
        stopWatchingPosition()
        val observer = object : Runnable {
            override fun run() {
                if (watchPosition !== this || !current()) return
                val position = player.positionMillis()
                if (position == null) { timer.postDelayed(this, 100); return }
                val part = when {
                    position < window.startMillis -> MixedAudioPart.MUSIC
                    position < window.endMillis -> MixedAudioPart.VOICE
                    else -> MixedAudioPart.TAIL
                }
                publish(mutable.value.copy(mixedPart = part, speaking = part == MixedAudioPart.VOICE,
                    soundOnly = part == MixedAudioPart.MUSIC))
                val boundary = when (part) {
                    MixedAudioPart.MUSIC -> window.startMillis
                    MixedAudioPart.VOICE -> window.endMillis
                    MixedAudioPart.TAIL -> { watchPosition = null; return }
                }
                timer.postDelayed(this, (boundary - position).coerceIn(25L, 220L))
            }
        }
        watchPosition = observer
        observer.run()
    }

    private fun clearClipTimeout() {
        timeout?.let(timer::removeCallbacks)
        timeout = null
    }

    private fun stopWatchingPosition() {
        watchPosition?.let(timer::removeCallbacks)
        watchPosition = null
    }

    private fun outputProblem() {
        revoke()
        publish(mutable.value.copy(phase = LetterPhase.OUTPUT_ERROR, speaking = false, soundOnly = false,
            issue = "소리를 재생하지 못했어. 다시 들어 볼까?"))
    }

    private fun awaitAnswer(key: Long) {
        if (!accepts(key)) return
        val turn = mutable.value.turn
        if (endingOrigin != null) {
            checkpoint(episode.encode(turn.state))
            endingOrigin = null
        }
        publish(mutable.value.copy(phase = LetterPhase.WAIT, speaking = false, soundOnly = false,
            pendingEnding = false, mixedPart = mutable.value.mixedPart?.let { MixedAudioPart.TAIL }))
        if (!turn.acceptsAnswer || mode != LetterMode.VOICE || mutable.value.touch) return
        val blocked = microphone.unavailableFailure()
        if (blocked != null) { inputProblem(); return }
        if (!runCatching { inputFocus.acquire() }.getOrDefault(false)) {
            if (accepts(key)) inputProblem()
            return
        }
        if (!accepts(key)) return
        val context = CaptureContext(episode.id, key, episode.stepName(turn.state), key, "letter-$key", key)
        val ticket = gate.open(context)
        signals.open(ticket)
        publish(mutable.value.copy(phase = LetterPhase.INPUT_START))
        try {
            microphone.open(ticket, { received, stage ->
                if (accepts(key) && gate.accepts(received, context)) {
                    signals.stage(received, stage)
                    // A late ready callback cannot invite a second answer once speech has ended.
                    if (stage == CaptureStage.PROCESSING || mutable.value.phase != LetterPhase.PROCESSING) {
                        publish(mutable.value.copy(phase = if (stage == CaptureStage.LISTENING)
                            LetterPhase.LISTENING else LetterPhase.PROCESSING))
                    }
                }
            }, { received, text ->
                if (accepts(key) && gate.consume(received, context)) {
                    if (QuietRequest.matches(text)) pause()
                    else present(episode.hear(turn.state, text))
                }
            }, { received, failure ->
                if (accepts(key) && gate.consume(received, context)) {
                    if (failure.pauseReason == "NO_RESPONSE" && failure.cause == null)
                        present(episode.hear(turn.state, null))
                    else inputProblem()
                }
            })
        } catch (_: Exception) { if (accepts(key)) inputProblem() }
    }

    private fun inputProblem() {
        revoke()
        publish(mutable.value.copy(phase = LetterPhase.INPUT_ERROR,
            issue = "목소리를 듣지 못해서 잠깐 쉬고 있어."))
    }

    private fun revoke() {
        ++serial
        ++clipToken
        clearTransportClip()
        stopWatchingPosition()
        clearClipTimeout()
        gate.close()
        signals.close()
        microphone.close()
        inputFocus.release()
        player.stop()
        focus.release()
        publishTransport(LetterTransportPhase.STOPPED)
    }

    companion object {
        fun <S : Any> create(context: Context, episode: LetterEpisode<S>, mode: LetterMode, saved: S?, allowed: () -> Boolean,
                   checkpoint: (String) -> Unit): LetterSession<S> {
            val app = context.applicationContext
            val clips = FirstJumpAssets(app, episode.id)
            return LetterSession(episode, AndroidClipPlayer(app), clips::audio, AndroidPlaybackAudioFocus(app),
                SpeechProviders(app).input(), { lost -> AndroidCaptureFocus(app, lost) },
                CaptureSignals(AndroidCaptureSignals()), allowed, checkpoint, mode, saved)
        }
    }
}
