package dev.carplaymate.demo

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun <S : Any> SoundLetterRoute(
    episode: LetterEpisode<S>,
    model: DeveloperViewModel,
    ui: DeveloperUiState,
    saved: String?,
    onSave: (String) -> Unit,
    onHome: () -> Unit,
    onReply: (() -> Unit)? = null,
) {
    val family = ui.children.map { it.id }
    key(family, episode.id) {
        LetterFamilyRoute(episode, model, ui, family, saved, onSave, onHome, onReply)
    }
}

internal fun <S> letterCaption(mode: LetterMode, episode: LetterEpisode<S>, view: LetterPresentation<S>): String {
    if (mode == LetterMode.READ) return view.turn.text
    val cue = view.currentCue ?: return view.turn.text
    return when (view.mixedPart) {
        MixedAudioPart.MUSIC -> if (cue == "finale_wiper") "와이퍼가 먼저 연주하는 중" else "빗방울이 먼저 연주하는 중"
        MixedAudioPart.VOICE, MixedAudioPart.TAIL -> episode.caption(cue).substringAfter("] ")
        null -> episode.caption(cue)
    }
}

/** A focus-only loss can occur on the vehicle even while the Activity remains RESUMED. */
internal fun observeFirstJumpWindow(view: View, onLost: () -> Unit): () -> Unit {
    val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) onLost() }
    view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
    return { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
}

@Composable
private fun <S : Any> LetterFamilyRoute(episode: LetterEpisode<S>, model: DeveloperViewModel, ui: DeveloperUiState,
    family: List<String>, saved: String?, onSave: (String) -> Unit, onHome: () -> Unit, onReply: (() -> Unit)?) {
    val app = LocalContext.current.applicationContext
    val window = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mode = when {
        ui.simulation -> LetterMode.READ
        ui.voiceInput -> LetterMode.VOICE
        else -> LetterMode.LISTEN
    }
    val session = remember(model, lifecycle, mode, window, family) {
        LetterSession.create(app, episode, mode, episode.decode(saved), allowed = {
            val current = model.ui.value
            window.hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                current.children.map { it.id } == family && current.fullUiAllowed && current.onboarded &&
                current.quiet && !current.familyDeletionPending && !current.localStorageProblem
        }, checkpoint = onSave)
    }
    DisposableEffect(session, lifecycle, window) {
        val stopObservingWindow = observeFirstJumpWindow(window, session::pause)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) session.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            stopObservingWindow()
            lifecycle.removeObserver(observer)
            session.pause()
            session.close()
        }
    }
    fun home() { session.pause(); onHome() }
    BackHandler { home() }
    val view by session.state.collectAsState()
    val turn = view.turn
    val paused = episode.paused(turn.state)
    val complete = episode.stage(turn.state) == 4 && !view.pendingEnding
    val playing = view.phase == LetterPhase.OUTPUT
    val stage = if (view.pendingEnding) 3 else episode.stage(turn.state)
    val choices = buildList {
        if (complete && onReply != null) add(JumpChoice("open_reply", "펭귄의 답장 열기"))
        addAll(turn.choices.filterNot { it.id in setOf("rest", "resume", "again") })
    }
    val status = view.issue ?: when {
        paused -> "잠깐 쉬는 중"
        playing && view.soundOnly -> episode.effectStatus
        playing -> "오누가 들려주는 중"
        view.phase == LetterPhase.INPUT_START -> "마이크 준비 중"
        view.phase == LetterPhase.LISTENING -> "네 차례야. 오누에게 말해 줘."
        view.phase == LetterPhase.PROCESSING -> "오누가 네 말을 생각하는 중"
        complete -> "소리 편지를 다 들었어"
        stage == 0 -> episode.invitation
        mode == LetterMode.READ -> "읽고 골라 줘"
        else -> "네 차례야. 하나 골라 줘."
    }
    val caption = letterCaption(mode, episode, view)
    // A read-only turn still needs every clue. In audio modes only the line being
    // heard (or just asked) should be the heading, without a second version of the question.
    val readingQuestion = mode == LetterMode.READ && turn.acceptsAnswer && turn.cues.isNotEmpty()
    val cueHeading = mode != LetterMode.READ && view.currentCue != null && !paused &&
        view.phase != LetterPhase.OUTPUT_ERROR &&
        (turn.acceptsAnswer || playing)
    val heading = when {
        readingQuestion -> episode.caption(turn.cues.last())
        cueHeading -> caption
        view.pendingEnding -> episode.finale
        else -> turn.title
    }
    val precedingClues = if (readingQuestion)
        turn.cues.dropLast(1).joinToString(" ", transform = episode::caption) else ""
    val body = when {
        readingQuestion || cueHeading -> ""
        view.phase == LetterPhase.OUTPUT_ERROR -> ""
        else -> caption
    }
    val statusIcon = when {
        paused -> FamilyIcon.PAUSE
        view.phase == LetterPhase.LISTENING -> FamilyIcon.MIC
        playing -> FamilyIcon.SOUND
        complete -> FamilyIcon.CHECK
        mode == LetterMode.READ && stage > 0 -> FamilyIcon.READ
        else -> null
    }
    LetterGameSurface(title = heading, line = body, precedingClues = precedingClues,
        status = status, statusIcon = statusIcon, stage = stage, scrollKey = turn.title,
        choices = choices.map { it.label },
        onChoice = { i -> choices.getOrNull(i)?.let { choice ->
            if (choice.id == "open_reply" && complete) { session.pause(); onReply?.invoke() }
            else session.choose(choice.id)
        } },
        onBack = ::home, onPauseOrResume = session::pauseOrResume, paused = paused, complete = complete,
        showChoices = !paused && !playing && !view.pendingEnding &&
            view.phase != LetterPhase.OUTPUT_ERROR && (!turn.acceptsAnswer || mode != LetterMode.VOICE || view.touch),
        onReplay = session::replay,
        canReplay = mode != LetterMode.READ && !playing && !paused && turn.cues.isNotEmpty(),
        onShowChoices = session::touchAssistance,
        canShowChoices = mode == LetterMode.VOICE && turn.acceptsAnswer && !view.touch && !playing && !paused && view.phase != LetterPhase.OUTPUT_ERROR,
        listening = view.phase == LetterPhase.LISTENING, speaking = view.speaking,
        processing = view.phase == LetterPhase.PROCESSING, soundPlaying = playing && view.soundOnly,
        rainLetter = episode.id == "rain_music")
}
