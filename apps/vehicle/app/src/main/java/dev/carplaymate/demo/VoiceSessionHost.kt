package dev.carplaymate.demo

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Process-local ownership only: no pending start, consent, or Activity is persisted.
 * All entry points run on the main thread, as do Service and Activity callbacks.
 */
internal class VoiceSessionHost(
    private val application: Application,
    controllerFactory: ((CoroutineScope) -> VoiceSessionController)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val controller = controllerFactory?.invoke(scope) ?: VoiceSessionController(application, initialClient = null,
        requireForegroundHostForVoice = true, sessionScope = scope)
    private val handler = Handler(Looper.getMainLooper())
    private var visibleActivity = WeakReference<Activity>(null)
    private var visibilityGeneration = 0L
    private var pending: PendingStart? = null
    private var service: VoiceSessionService? = null
    private var stoppingService: VoiceSessionService? = null
    private var dispatching = false
    private var closed = false
    private var mediaPauseLease: MediaPauseLease? = null
    private var mediaPauseTimeout: Runnable? = null

    private data class PendingStart(
        val token: String,
        val visibilityGeneration: Long,
        val action: () -> Unit,
        val timeout: Runnable,
        val expiresAtElapsed: Long,
        var startRequested: Boolean = false,
    )

    init {
        scope.launch {
            controller.ui.collect { state ->
                // Observation may bind a confirmed paused turn or revoke its existing lease.
                // It never creates a lease, grants an owner, extends a deadline, or starts I/O.
                if (!dispatching && service != null && pending == null) {
                    if (mediaPauseLease != null && !refreshMediaPauseLease()) releaseService()
                    else if (mediaPauseLease == null && state.quiet && !state.busy) releaseService()
                }
                service?.updatePlaybackState(state)
            }
        }
    }

    fun activityResumed(activity: Activity) {
        checkMainThread()
        if (closed) return
        visibilityGeneration++
        visibleActivity = WeakReference(activity)
    }

    fun activityPaused(activity: Activity) {
        checkMainThread()
        if (visibleActivity.get() !== activity) return
        visibleActivity.clear()
        visibilityGeneration++
        // A start still waiting for promotion has no background-start authorization.
        if (pending != null) {
            cancelExplicitAction()
            controller.pause("USER")
        }
    }

    fun requiredVoicePermissions(): Array<String> =
        (controller.requiredVoicePermissions().toList() +
            listOf(Manifest.permission.RECORD_AUDIO).filter { !granted(it) }).distinct().toTypedArray()

    fun runExplicitVoiceAction(action: () -> Unit) {
        checkMainThread()
        if (closed) return
        // Keep the first still-authorized activation while its Service start is in flight.
        // Replacing its token would make the older queued START stop the same service.
        // Recheck every condition; a changed screen, permission or mode is not coalesced.
        pending?.let { if (isAuthorized(it)) return }
        cancelExplicitAction()
        clearMediaPauseLease()
        if (!hasVisibleActivity() || !hasVoiceModeAccess()) {
            fail(permissionDenied = false)
            return
        }
        if (!hasPermissions()) {
            fail(permissionDenied = true)
            return
        }
        if (service != null) {
            execute(action)
            return
        }
        val token = UUID.randomUUID().toString()
        val timeout = Runnable {
            if (pending?.token == token) {
                cancelExplicitAction()
                fail(permissionDenied = false)
            }
        }
        pending = PendingStart(token, visibilityGeneration, action, timeout,
            SystemClock.elapsedRealtime() + PROMOTION_WAIT_MILLIS)
        handler.postDelayed(timeout, PROMOTION_WAIT_MILLIS)
        dispatchPending(token)
    }

    private fun dispatchPending(token: String) {
        val request = pending?.takeIf { it.token == token } ?: return
        if (stoppingService != null || request.startRequested) return
        if (!isAuthorized(request)) {
            cancelExplicitAction()
            fail(permissionDenied = !hasPermissions())
            return
        }
        request.startRequested = true
        try {
            // This is the same bounded UI request, not a retry or restored consent.
            application.startForegroundService(Intent(application, VoiceSessionService::class.java)
                .setAction(VoiceSessionService.ACTION_START)
                .putExtra(VoiceSessionService.EXTRA_TOKEN, token))
        } catch (_: SecurityException) {
            cancelExplicitAction()
            fail(permissionDenied = true)
        } catch (_: RuntimeException) {
            cancelExplicitAction()
            fail(permissionDenied = false)
        }
    }

    /** Rechecked immediately before and after Service.startForeground, never from a saved Intent. */
    fun canPromote(token: String): Boolean {
        checkMainThread()
        val request = pending ?: return false
        return request.token == token && request.startRequested && stoppingService == null &&
            isAuthorized(request)
    }

    private fun isAuthorized(request: PendingStart): Boolean = !closed &&
        SystemClock.elapsedRealtime() < request.expiresAtElapsed &&
        request.visibilityGeneration == visibilityGeneration && hasVisibleActivity() &&
        hasVoiceModeAccess() && hasPermissions()

    /** A superseded START may not stop the service awaited by a newer authorized UI request. */
    fun hasNewerPromotableRequest(token: String): Boolean {
        checkMainThread()
        val request = pending ?: return false
        return request.token != token && isAuthorized(request)
    }

    fun servicePromoted(owner: VoiceSessionService, token: String): Boolean {
        checkMainThread()
        if (!canPromote(token)) return false
        val request = pending ?: return false
        handler.removeCallbacks(request.timeout)
        pending = null
        service = owner
        execute(request.action)
        if (service === owner) owner.updatePlaybackState(controller.ui.value)
        return service === owner
    }

    fun servicePromotionFailed(token: String, permissionDenied: Boolean) {
        checkMainThread()
        if (pending?.token != token) return
        cancelExplicitAction()
        fail(permissionDenied || !hasPermissions())
    }

    fun ownsService(owner: VoiceSessionService): Boolean = service === owner

    fun serviceStopped(owner: VoiceSessionService) {
        checkMainThread()
        if (service !== owner) return
        service = null
        cancelExplicitAction()
        clearMediaPauseLease()
        try { controller.pause("USER") } finally { controller.setForegroundHost(false) }
    }

    /** Called before stopSelf; a new direct action must wait for this owner's destruction. */
    fun serviceFinishing(owner: VoiceSessionService) {
        checkMainThread()
        // Also closes an owner if platform teardown failed after promotion.
        if (service === owner) serviceStopped(owner)
        if (service == null && stoppingService == null) stoppingService = owner
    }

    /** Completion of teardown is not consent: only an unexpired current UI request can proceed. */
    fun serviceDestroyed(owner: VoiceSessionService) {
        checkMainThread()
        if (stoppingService !== owner) return
        stoppingService = null
        val token = pending?.token ?: return
        // Leave the old onDestroy callback before asking Android for a new Service instance.
        handler.post { dispatchPending(token) }
    }

    /** Only an explicit media/notification pause can retain this already promoted owner. */
    fun pauseFromMediaControl(owner: VoiceSessionService) {
        checkMainThread()
        if (closed || service !== owner) return
        if (mediaPauseLease != null) {
            if (!refreshMediaPauseLease()) releaseService()
            else owner.updatePlaybackState(controller.ui.value)
            return // A second pause cannot renew the original deadline.
        }
        val id = controller.mediaPauseCandidateId()
        if (id == null || !hasPermissions()) {
            pause("USER")
            return
        }
        cancelExplicitAction()
        val deadline = SystemClock.elapsedRealtime() + MediaPauseLease.DURATION_MILLIS
        val previousDispatching = dispatching
        dispatching = true
        try {
            // Close capture/output synchronously before persistence and before exposing play.
            controller.pause("USER")
            mediaPauseLease = MediaPauseLease(UUID.randomUUID().toString(), id,
                controller.mediaStopGeneration, deadline)
            val token = checkNotNull(mediaPauseLease).token
            mediaPauseTimeout = object : Runnable {
                override fun run() {
                    if (mediaPauseLease?.token != token) return
                    if (!refreshMediaPauseLease()) releaseService()
                    else handler.postDelayed(this,
                        (deadline - SystemClock.elapsedRealtime()).coerceIn(0L, 1_000L))
                }
            }.also { handler.postDelayed(it, (deadline - SystemClock.elapsedRealtime()).coerceIn(0L, 1_000L)) }
        } finally {
            dispatching = previousDispatching
        }
        if (!refreshMediaPauseLease()) releaseService()
        else owner.updatePlaybackState(controller.ui.value)
    }

    fun hasRetainedMediaPause(owner: VoiceSessionService): Boolean {
        checkMainThread()
        return service === owner && refreshMediaPauseLease()
    }

    fun mediaResumeToken(owner: VoiceSessionService): String? {
        checkMainThread()
        if (service !== owner || !refreshMediaPauseLease()) return null
        return mediaPauseLease?.takeIf { it.paused != null }?.token
    }

    /** No startForegroundService call: a stopped or replaced owner can never use this path. */
    fun resumeFromMediaControl(owner: VoiceSessionService, token: String): Boolean {
        checkMainThread()
        if (closed || service !== owner || mediaPauseLease?.token != token) return false
        if (!refreshMediaPauseLease()) {
            releaseService()
            return false
        }
        val lease = mediaPauseLease ?: return false
        val target = controller.mediaPausedSession() ?: return false
        if (!lease.permits(token, target, SystemClock.elapsedRealtime()) || !hasPermissions()) {
            releaseService()
            return false
        }
        clearMediaPauseLease() // Consume once before any command can suspend.
        val previousDispatching = dispatching
        dispatching = true
        val accepted = try { controller.resumeFromMediaControl(target) }
        finally { dispatching = previousDispatching }
        if (!accepted || (controller.ui.value.quiet && !controller.ui.value.busy)) releaseService()
        else owner.updatePlaybackState(controller.ui.value)
        return accepted
    }

    private fun refreshMediaPauseLease(): Boolean {
        val lease = mediaPauseLease ?: return false
        if (closed || service == null || !lease.validAt(SystemClock.elapsedRealtime()) ||
            !hasPermissions() || !controller.canRetainMediaPause(lease.sessionId, lease.stopGeneration)) return false
        val paused = controller.mediaPausedSession()
        if (paused != null) {
            mediaPauseLease = lease.bind(paused, SystemClock.elapsedRealtime()) ?: return false
        } else if (lease.paused != null) return false
        return true
    }

    private fun clearMediaPauseLease() {
        mediaPauseTimeout?.let(handler::removeCallbacks)
        mediaPauseTimeout = null
        mediaPauseLease = null
    }

    fun pause(reason: String = "QUIET_REQUEST") {
        checkMainThread()
        cancelExplicitAction()
        clearMediaPauseLease()
        // Output/capture stop synchronously, before storage or service teardown.
        try { controller.pause(reason) } finally { releaseService() }
    }

    fun cancelExplicitAction() {
        checkMainThread()
        pending?.let { handler.removeCallbacks(it.timeout) }
        pending = null
    }

    private fun execute(action: () -> Unit) {
        dispatching = true
        try {
            controller.setForegroundHost(true, restrictedVoiceSupported = false)
            action()
        } catch (_: RuntimeException) {
            fail(permissionDenied = false)
        } finally {
            dispatching = false
            val state = controller.ui.value
            if (state.quiet && !state.busy) releaseService()
        }
    }

    private fun fail(permissionDenied: Boolean) {
        try { controller.foregroundHostFailed(permissionDenied) } finally { releaseService() }
    }

    private fun releaseService() {
        clearMediaPauseLease()
        val previous = service
        service = null
        try { controller.setForegroundHost(false) } finally { previous?.finishFromHost() }
    }

    private fun hasVisibleActivity(): Boolean = visibleActivity.get()?.let {
        !it.isFinishing && !it.isDestroyed
    } == true

    private fun hasPermissions(): Boolean = controller.voiceSessionPermissionsGranted()

    private fun hasVoiceModeAccess(): Boolean = controller.ui.value.let {
        it.voiceInput && !it.simulation && it.fullUiAllowed &&
            !it.familyDeletionPending && !it.localStorageProblem
    }

    private fun granted(permission: String) =
        application.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Explicit owner shutdown only; clearing a screen's ViewModel does not call this. */
    fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        cancelExplicitAction()
        visibleActivity.clear()
        releaseService()
        try { controller.close() } finally { scope.cancel() }
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    companion object {
        private const val PROMOTION_WAIT_MILLIS = 4_000L
    }
}
