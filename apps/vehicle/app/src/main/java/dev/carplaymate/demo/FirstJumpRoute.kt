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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun <S : Any> SoundLetterRoute(
    episode: LetterEpisode<S>,
    model: DeveloperViewModel,
    ui: DeveloperUiState,
    saved: String?,
    takeEntryResume: (() -> String?)?,
    onSave: (String) -> Unit,
    onHome: () -> Unit,
    onReply: (() -> Unit)? = null,
) {
    val family = ui.children.map { it.id }
    key(family, episode.id) {
        LetterFamilyRoute(episode, model, ui, family, saved, takeEntryResume, onSave, onHome, onReply)
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
    family: List<String>, saved: String?, takeEntryResume: (() -> String?)?, onSave: (String) -> Unit, onHome: () -> Unit, onReply: (() -> Unit)?) {
    val app = LocalContext.current.applicationContext
    val window = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mode = when {
        ui.simulation -> LetterMode.READ
        ui.voiceInput -> LetterMode.VOICE
        else -> LetterMode.LISTEN
    }
    val host = if (mode == LetterMode.VOICE) (app as? OnuApplication)?.letterSessionHost else null
    val session = remember(model, lifecycle, mode, window, family, episode.id) {
        host?.bind(episode, family, episode.decode(saved)) ?: LetterSession.create(app, episode, mode,
            episode.decode(saved), allowed = {
                val current = model.ui.value
                window.hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    current.children.map { it.id } == family && current.fullUiAllowed && current.onboarded &&
                    current.quiet && !current.familyDeletionPending && !current.localStorageProblem
            }, checkpoint = onSave)
    }
    DisposableEffect(session, lifecycle, window, host) {
        val stopObservingWindow = if (host == null) observeFirstJumpWindow(window, session::pause) else ({})
        val observer = LifecycleEventObserver { _, event ->
            if (host == null && (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP)) session.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            stopObservingWindow()
            lifecycle.removeObserver(observer)
            if (host == null) { session.pause(); session.close() } else host.detach(session)
        }
    }
    fun explicit(action: () -> Unit) { if (host != null) host.runExplicit(session, action) else action() }
    LaunchedEffect(session) {
        val takeRequest = takeEntryResume ?: return@LaunchedEffect
        val revision = session.transport.value.revision
        // Navigating away from a saved adventure can still be committing its quiet checkpoint.
        val idle = withTimeoutOrNull(4_000L) { model.ui.first { !it.busy } } != null
        val requested = episode.decode(takeRequest()) ?: return@LaunchedEffect
        val current = model.ui.value
        if (!idle || current.busy || current.failure || !current.quiet || !current.fullUiAllowed ||
            !current.onboarded || !current.interactionConfigured || current.children.map { it.id } != family ||
            current.simulation != ui.simulation || current.voiceInput != ui.voiceInput ||
            current.familyDeletionPending || current.localStorageProblem || !window.hasWindowFocus() ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val expected = episode.choose(requested, "rest").state
        if (episode.stage(requested) in 1..3 && episode.paused(expected) &&
            session.state.value.turn.state == expected && session.transport.value.revision == revision) {
            explicit { if (session.state.value.turn.state == expected) session.resume() }
        }
    }
    fun stop() { if (host != null) host.stop(session) else session.pause() }
    fun home() { stop(); onHome() }
    BackHandler { home() }
    val view by session.state.collectAsState()
    // The host persists without UI. Reattachment also refreshes this screen's saved-state mirror.
    val saveCurrent by rememberUpdatedState(onSave)
    LaunchedEffect(host, session, view.turn.state, view.pendingEnding) {
        if (host != null && !view.pendingEnding) saveCurrent(episode.encode(view.turn.state))
    }
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
        playing -> "이야기하는 중"
        view.phase == LetterPhase.INPUT_START -> "마이크 준비 중"
        view.phase == LetterPhase.LISTENING -> "오누가 듣고 있어. 말해 줘."
        view.phase == LetterPhase.PROCESSING -> "잠깐만 기다려 줘"
        complete -> ""
        stage == 0 -> ""
        mode == LetterMode.READ -> "읽고 답을 골라 줘"
        mode == LetterMode.LISTEN || view.touch -> "답을 눌러 골라 줘"
        else -> "네 차례야"
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
    val showChoices = !paused && !playing && !view.pendingEnding &&
        view.phase != LetterPhase.OUTPUT_ERROR && (!turn.acceptsAnswer || mode != LetterMode.VOICE || view.touch)
    val canShowChoices = mode == LetterMode.VOICE && turn.acceptsAnswer && !view.touch && !playing && !paused &&
        view.phase != LetterPhase.OUTPUT_ERROR
    LetterGameSurface(title = heading, line = body, precedingClues = precedingClues,
        status = status, statusIcon = statusIcon, stage = stage, scrollKey = turn.title,
        choices = choices,
        onChoice = { i -> choices.getOrNull(i)?.let { choice ->
            if (choice.id == "open_reply" && complete) { stop(); onReply?.invoke() }
            else explicit { session.choose(choice.id) }
        } },
        onBack = ::home, onPauseOrResume = { if (paused) explicit(session::resume) else stop() }, paused = paused, complete = complete,
        showChoices = showChoices,
        onReplay = { explicit(session::replay) },
        canReplay = mode != LetterMode.READ && !playing && !paused && turn.cues.isNotEmpty(),
        onShowChoices = { explicit(session::touchAssistance) },
        canShowChoices = canShowChoices,
        listening = view.phase == LetterPhase.LISTENING, speaking = view.speaking,
        processing = view.phase == LetterPhase.PROCESSING, soundPlaying = playing && view.soundOnly,
        groupVoiceHelp = mode == LetterMode.VOICE && canShowChoices && view.issue == null,
        rainLetter = episode.id == "rain_music",
        answerLabel = turn.answerLabel, awaitingAnswer = turn.acceptsAnswer && (showChoices || canShowChoices))
}
