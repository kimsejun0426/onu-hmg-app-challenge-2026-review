package dev.carplaymate.demo

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** A voice letter has one process-local owner. UI attachment and permission to run are separate. */
internal class LetterSessionHost(
    private val application: Application,
    private val familyState: StateFlow<DeveloperUiState> =
        (application as OnuApplication).voiceSessionHost.controller.ui,
    private val vehicle: VehicleAccessProvider = VehicleAccessProviders.create(application),
    private val permissionsGranted: () -> Boolean = {
        (application as OnuApplication).voiceSessionHost.controller.voiceSessionPermissionsGranted()
    },
    private val controlsAvailable: () -> Boolean = { LetterSessionService.controlsAvailable(application) },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val sessionFactory: ((LetterEpisode<Any>, Any?, () -> Boolean, (String) -> Unit) -> LetterSession<Any>)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var visible = WeakReference<Activity>(null)
    private var observing = false
    private var displayId: Int? = null
    private var vehicleEpoch = 0L
    private var snapshot = vehicle.initialSnapshot
    private var slot: Slot? = null
    private var sessionWatch: Job? = null
    private var transportWatch: Job? = null
    private var mediaLease: LetterMediaLease? = null
    private val mutableMedia = MutableStateFlow(LetterMediaState())
    val mediaState: StateFlow<LetterMediaState> = mutableMedia
    private var service: LetterSessionService? = null
    private var stoppingService: LetterSessionService? = null
    private var pending: Start? = null
    private var dispatching = false
    private var heartbeat: Runnable? = null
    private var closed = false

    private data class Slot(val episode: LetterEpisode<Any>, val family: String, val session: LetterSession<Any>)
    private data class Start(val token: String, val target: LetterSession<*>, val action: () -> Unit,
        val expires: Long, val activity: WeakReference<Activity>, val timeout: Runnable)

    init {
        scope.launch { familyState.collect {
            if (slot != null) {
                if (!identityEligible()) invalidateSlot()
                else if (!eligible()) stop()
            }
        } }
    }

    /** This observer remains attached while a promoted letter survives a hidden Activity. */
    @Suppress("DEPRECATION")
    fun activityResumed(activity: Activity) {
        if (closed) return
        visible = WeakReference(activity)
        val display = (activity.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
        if (observing && displayId == display?.displayId) return
        if (observing) stop()
        val epoch = ++vehicleEpoch
        vehicle.close()
        observing = true
        displayId = display?.displayId
        snapshot = vehicle.initialSnapshot
        val context = display?.let { application.createDisplayContext(it) } ?: application
        vehicle.startObserving(context) { next ->
            if (!closed && epoch == vehicleEpoch) {
                snapshot = next
                if ((service != null || pending != null) && !policy().allowsVoiceSession) stop()
            }
        }
    }

    fun activityPaused(activity: Activity) {
        if (visible.get() !== activity) return
        visible.clear()
        if (pending != null) stop() // A pending request cannot become a background microphone start.
        if (service == null) detachVehicle()
    }

    @Suppress("UNCHECKED_CAST")
    fun <S : Any> bind(episode: LetterEpisode<S>, family: List<String>, saved: S?): LetterSession<S> {
        check(Looper.myLooper() == Looper.getMainLooper())
        val key = family.joinToString(",")
        slot?.takeIf { it.episode.id == episode.id && it.family == key && service != null && eligible() }?.let {
            return it.session as LetterSession<S>
        }
        stop()
        sessionWatch?.cancel()
        transportWatch?.cancel()
        slot?.session?.close()
        slot = null
        val erased = episode as LetterEpisode<Any>
        val progress = FirstJumpProgress(application, episode.id)
        val restored = erased.decode(progress.read(key)) ?: saved
        var created: LetterSession<Any>? = null
        val allowed = { slot?.session === created && service != null && eligible() &&
            permissionsGranted() && controlsAvailable() && policy().allowsVoiceSession }
        val persist: (String) -> Unit = { value ->
            // No Activity, ViewModel or Compose callback is kept by a playing letter.
            if (currentFamily() == key && !familyState.value.familyDeletionPending) {
                progress.save(key, value)
                if (episode.id == "first_jump") restoreLetter(value)?.takeIf { it.step == JumpStep.END }
                    ?.ending?.let { ending -> FirstJumpProgress(application, "rain_music")
                        .initialize(key, RainGame.initial(ending).state.rainCheckpoint()) }
            }
        }
        created = sessionFactory?.invoke(erased, restored, allowed, persist)
            ?: LetterSession.create(application, erased, LetterMode.VOICE, restored, allowed, persist)
        val owned = checkNotNull(created)
        slot = Slot(erased, key, owned)
        sessionWatch = scope.launch {
            owned.state.collect {
                if (slot?.session === owned && service != null) handler.post {
                    // READY/WAIT are also intermediate states inside one synchronous output/input transition.
                    if (slot?.session === owned && service != null && !dispatching) {
                        if (!running() && !retainedMediaPause()) releaseOwner()
                        publishMedia()
                    }
                }
            }
        }
        transportWatch = scope.launch { owned.transport.collect { publishMedia() } }
        publishMedia()
        return owned as LetterSession<S>
    }

    /** Explicit UI action only. Starting a service does not yet grant capture or playback. */
    fun runExplicit(target: LetterSession<*>, action: () -> Unit) {
        if (closed || slot?.session !== target || !visibleNow()) return
        if (!controlsAvailable()) {
            target.activationFailed(LetterSessionService.CONTROLS_REQUIRED)
            return
        }
        if (!eligible() || !permissionsGranted() || !policy().allowsFullUi) {
            target.activationFailed()
            return
        }
        if (pending != null) return
        if (service != null) {
            mediaLease = null
            execute(action)
            return
        }
        val activity = visible.get() ?: return
        val token = UUID.randomUUID().toString()
        val timeout = Runnable { if (pending?.token == token) startFailed() }
        pending = Start(token, target, action, SystemClock.elapsedRealtime() + 4_000L,
            WeakReference(activity), timeout)
        handler.postDelayed(timeout, 4_000L)
        dispatchStart(token)
    }

    private fun dispatchStart(token: String) {
        if (stoppingService != null || !canPromote(token)) return
        try {
            application.startForegroundService(Intent(application, LetterSessionService::class.java)
                .setAction(LetterSessionService.ACTION_START).putExtra(LetterSessionService.EXTRA_TOKEN, token))
        } catch (_: RuntimeException) { startFailed() }
    }

    fun hasNewerStart(token: String): Boolean = pending?.let { it.token != token && canPromote(it.token) } == true

    fun canPromote(token: String): Boolean {
        val start = pending ?: return false
        return !closed && start.token == token && start.target === slot?.session &&
            start.activity.get() === visible.get() && SystemClock.elapsedRealtime() < start.expires &&
            visibleNow() && eligible() && permissionsGranted() && controlsAvailable() && policy().allowsFullUi
    }

    fun promoted(owner: LetterSessionService, token: String): Boolean {
        if (!canPromote(token)) return false
        val start = pending ?: return false
        cancelStart()
        service = owner
        publishMedia()
        execute(start.action)
        if (service === owner) startHeartbeat()
        return service === owner
    }

    fun owns(owner: LetterSessionService) = service === owner
    fun promotionFailed(token: String?) { if (token != null && pending?.token == token) startFailed() }
    fun finishing(owner: LetterSessionService) {
        if (service === owner) { mediaLease = null; service = null; slot?.session?.pause(); publishMedia() }
        if (stoppingService == null) stoppingService = owner
    }
    fun destroyed(owner: LetterSessionService) {
        if (service === owner) stop()
        if (stoppingService === owner) {
            stoppingService = null
            pending?.token?.let { token -> handler.post { dispatchStart(token) } }
        }
    }

    /** Explicit exit, restriction, permission loss and interruptions all revoke before releasing FGS. */
    fun stop(target: LetterSession<*>? = null) {
        if (target != null && slot?.session !== target) return
        cancelStart()
        mediaLease = null
        slot?.session?.pause()
        releaseOwner()
    }

    fun detach(target: LetterSession<*>) {
        if (slot?.session !== target) return
        // Only a live voice owner can outlive this route. Merely selecting VOICE grants nothing.
        if (service == null || !eligible() || !policy().allowsVoiceSession || (!running() && !retainedMediaPause())) stop(target)
    }

    private fun execute(action: () -> Unit) {
        dispatching = true
        try { action() } catch (_: RuntimeException) { startFailed() }
        finally {
            dispatching = false
            if (service != null && !running() && !retainedMediaPause()) releaseOwner()
            publishMedia()
        }
    }

    /** Media pause is distinct from exit. Keep only the already promoted owner, for a bounded time. */
    fun pauseFromMediaControl() {
        if (closed || service == null) return
        if (!mediaControlAllowed()) { stop(); return }
        if (mediaLease != null) { if (!retainedMediaPause()) stop(); return }
        if (!running()) return
        dispatching = true
        try {
            slot?.session?.pause() // Revoke output/input before publishing the resume capability.
            val current = slot ?: return
            if (current.episode.paused(current.session.state.value.turn.state)) {
                val transport = current.session.transport.value
                mediaLease = LetterMediaLease(UUID.randomUUID().toString(), transport.sessionId,
                    transport.revision, elapsedRealtime() + LetterMediaLease.DURATION_MS)
            }
        } finally {
            dispatching = false
            if (!retainedMediaPause()) releaseOwner()
            publishMedia()
        }
    }

    /** Only the current one-shot capability may resume. Browsing and generic PLAY grant nothing. */
    fun resumeFromMediaControl(token: String): Boolean {
        val lease = mediaLease ?: return false
        if (token != lease.token) return false // A stale controller must not disturb the new pause.
        if (!mediaControlAllowed() || !retainedMediaPause()) { stop(); return false }
        val current = slot ?: return false
        mediaLease = null // Consume before running any user code, even if output fails.
        execute { current.session.resume() }
        return running()
    }

    private fun mediaControlAllowed() = !closed && service != null && eligible() &&
        permissionsGranted() && controlsAvailable() && service?.controlsHealthy() == true &&
        policy().allowsVoiceSession

    private fun retainedMediaPause(): Boolean {
        val current = slot ?: return false
        val transport = current.session.transport.value
        return service != null && current.episode.paused(current.session.state.value.turn.state) &&
            mediaLease?.matches(transport, elapsedRealtime()) == true
    }

    /** A public media surface contains only authored episode data, never a child's reply or name. */
    private fun publishMedia() {
        val current = slot
        val owned = service != null && current != null
        val transport = if (owned) current?.session?.transport?.value?.let {
            it.copy(positionMs = current.session.transportPositionMillis())
        } else null
        mutableMedia.value = LetterMediaState(owned, if (owned) current?.episode?.id else null,
            if (owned) current?.episode?.invitation ?: "오누 소리 편지" else "오누 소리 편지",
            transport, mediaLease?.takeIf { retainedMediaPause() }?.token)
        service?.updateMediaPause(mutableMedia.value.resumeToken)
    }

    private fun running(): Boolean {
        val current = slot ?: return false
        val view = current.session.state.value
        return !current.episode.paused(view.turn.state) && !view.touch && view.phase in setOf(
            LetterPhase.OUTPUT, LetterPhase.INPUT_START, LetterPhase.LISTENING, LetterPhase.PROCESSING)
    }

    private fun invalidateSlot() {
        stop()
        sessionWatch?.cancel()
        transportWatch?.cancel()
        slot?.session?.close()
        slot = null
    }

    private fun currentFamily() = familyState.value.children.joinToString(",") { it.id }
    private fun identityEligible(): Boolean {
        val state = familyState.value
        return !closed && slot?.family == currentFamily() && state.onboarded && state.voiceInput &&
            !state.simulation && !state.familyDeletionPending && !state.localStorageProblem
    }

    private fun eligible(): Boolean = identityEligible() && familyState.value.quiet && !familyState.value.busy

    private fun visibleNow() = visible.get()?.let { !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() } == true
    private fun policy() = VehicleInteractionPolicy.evaluate(snapshot, VoiceHostState(
        uiVisible = visibleNow(), foregroundServiceRunning = service != null,
        explicitlyStartedSession = service != null, voiceModeSelected = familyState.value.voiceInput,
        restrictedVoiceSupported = false))

    private fun startHeartbeat() {
        heartbeat?.let(handler::removeCallbacks)
        heartbeat = object : Runnable {
            override fun run() {
                if (heartbeat !== this || service == null) return
                if (!eligible() || !permissionsGranted() || !controlsAvailable() || !policy().allowsVoiceSession ||
                    service?.controlsHealthy() != true || (mediaLease != null && !retainedMediaPause())) stop()
                else { publishMedia(); handler.postDelayed(this, 500L) }
            }
        }.also { handler.postDelayed(it, 500L) }
    }

    private fun startFailed() {
        val target = slot?.session
        stop()
        if (!controlsAvailable()) target?.activationFailed(LetterSessionService.CONTROLS_REQUIRED)
        else target?.activationFailed()
    }

    private fun cancelStart() {
        pending?.timeout?.let(handler::removeCallbacks)
        pending = null
    }

    private fun releaseOwner() {
        heartbeat?.let(handler::removeCallbacks)
        heartbeat = null
        mediaLease = null
        val previous = service
        service = null
        publishMedia()
        previous?.let { stoppingService = it; it.finishFromHost() }
        if (visible.get() == null && pending == null) detachVehicle()
    }

    private fun detachVehicle() {
        ++vehicleEpoch
        observing = false
        displayId = null
        vehicle.close()
        snapshot = vehicle.initialSnapshot
    }

    internal fun close() {
        if (closed) return
        stop()
        closed = true
        sessionWatch?.cancel()
        transportWatch?.cancel()
        slot?.session?.close()
        slot = null
        scope.coroutineContext[Job]?.cancel()
        detachVehicle()
    }
}