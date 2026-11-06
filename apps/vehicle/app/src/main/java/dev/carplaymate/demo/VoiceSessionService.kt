package dev.carplaymate.demo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent

/** Private non-sticky owner. Media resume never creates or promotes a microphone service. */
class VoiceSessionService : Service() {
    private val host: VoiceSessionHost get() = (application as OnuApplication).voiceSessionHost
    private var mediaSession: MediaSession? = null
    private var activeToken: String? = null
    private var finishing = false
    private var playbackState: Int? = null
    private var playbackActions: Long? = null
    private var sessionResumeToken: String? = null
    private var mediaGeneration = 0L
    private val notificationHandler = Handler(Looper.getMainLooper())
    private var notificationTask: Runnable? = null
    private var notificationRevision = 0L
    private var desiredNotification: NotificationUpdate? = null

    private data class NotificationUpdate(
        val ownerToken: String,
        val mediaGeneration: Long,
        val retained: Boolean,
        val resumeToken: String?,
        val revision: Long,
        var attempts: Int = 0,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        if (finishing) {
            if (intent?.action == ACTION_START && token != null)
                host.servicePromotionFailed(token, permissionDenied = false)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                if (token != null && token == activeToken) stopVoice()
                else if (!host.ownsService(this)) finishFromHost()
            }
            ACTION_PAUSE -> {
                if (token != null && token == activeToken && host.ownsService(this))
                    host.pauseFromMediaControl(this)
                else if (!host.ownsService(this)) finishFromHost()
            }
            ACTION_RESUME -> {
                // An old PendingIntent may instantiate a Service; it cannot create an owner.
                if (token != null && activeToken != null && host.ownsService(this))
                    host.resumeFromMediaControl(this, token)
                else if (!host.ownsService(this)) finishFromHost()
            }
            ACTION_START -> {
                if (token == null || !host.canPromote(token)) {
                    if (token != null && host.hasNewerPromotableRequest(token)) return START_NOT_STICKY
                    if (token != null) host.servicePromotionFailed(token, permissionDenied = false)
                    if (!host.ownsService(this)) finishFromHost()
                    return START_NOT_STICKY
                }
                try {
                    ensureMediaSession()
                    val initial = NotificationUpdate(token, mediaGeneration, retained = false,
                        resumeToken = null, revision = ++notificationRevision, attempts = 1)
                    val notification = notification(initial)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    // The initial FGS post is immediate. Read it back before sending a duplicate.
                    lastNotificationPostElapsed = SystemClock.elapsedRealtime()
                    desiredNotification = initial
                    activeToken = token
                    mediaSession?.isActive = true
                    if (!host.servicePromoted(this, token)) finishFromHost()
                    else scheduleNotificationCheck()
                } catch (_: SecurityException) {
                    host.servicePromotionFailed(token, permissionDenied = true)
                    finishFromHost()
                } catch (_: RuntimeException) {
                    host.servicePromotionFailed(token, permissionDenied = false)
                    finishFromHost()
                }
            }
            else -> if (!host.ownsService(this)) finishFromHost()
        }
        return START_NOT_STICKY
    }

    private fun ensureMediaSession(resumeToken: String? = null) {
        if (mediaSession != null && sessionResumeToken == resumeToken) return
        // A paused lease gets a fresh media token. Queued callbacks from an older token cannot resume it.
        mediaGeneration++
        val generation = mediaGeneration
        mediaSession?.let { it.isActive = false; it.release() }
        sessionResumeToken = resumeToken
        playbackState = null
        playbackActions = null
        mediaSession = MediaSession(this, "OnuVoiceSession").apply {
            setCallback(object : MediaSession.Callback() {
                private fun current(): Boolean = !finishing && generation == mediaGeneration &&
                    activeToken != null && host.ownsService(this@VoiceSessionService)
                private fun pause() { if (current()) host.pauseFromMediaControl(this@VoiceSessionService) }
                private fun play() {
                    if (current() && resumeToken != null && trustedPlaybackController())
                        host.resumeFromMediaControl(this@VoiceSessionService, resumeToken)
                }
                override fun onPause() = pause()
                override fun onStop() { if (current()) stopVoice() }
                override fun onPlay() = play()
                @Suppress("DEPRECATION")
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event != null && event.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PLAY,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                        if (current() && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            when (event.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_STOP -> stopVoice()
                                KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
                                KeyEvent.KEYCODE_MEDIA_PLAY -> play()
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (resumeToken != null) play() else pause()
                            }
                        }
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            }, Handler(Looper.getMainLooper()))
            isActive = activeToken != null && host.ownsService(this@VoiceSessionService)
        }
    }

    /** Notification-listener access alone is not permission to resume an answer session. */
    private fun trustedPlaybackController(): Boolean = try {
        val remote = checkNotNull(mediaSession).currentControllerInfo
        val packages = packageManager.getPackagesForUid(remote.uid).orEmpty()
        val privileged = remote.uid == Process.SYSTEM_UID ||
            checkPermission(Manifest.permission.MEDIA_CONTENT_CONTROL, remote.pid, remote.uid) ==
                PackageManager.PERMISSION_GRANTED
        remote.packageName in packages && privileged &&
            getSystemService(MediaSessionManager::class.java).isTrustedForMediaControl(remote)
    } catch (_: RuntimeException) {
        false
    }

    internal fun updatePlaybackState(state: DeveloperUiState) {
        if (finishing) return
        val retained = host.hasRetainedMediaPause(this)
        val resumeToken = host.mediaResumeToken(this)
        if (finishing) return
        ensureMediaSession(resumeToken)
        val session = mediaSession ?: return
        val next = when {
            retained -> PlaybackState.STATE_PAUSED
            state.quiet -> PlaybackState.STATE_STOPPED
            state.outputStage == OutputStage.PREPARING -> PlaybackState.STATE_BUFFERING
            state.outputStage == OutputStage.STARTED -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED // Listening is not playback or a resumable pause.
        }
        val actions = PlaybackState.ACTION_STOP or when {
            retained && resumeToken != null -> PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE
            retained -> 0L
            !state.quiet -> PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
            else -> 0L
        }
        if (playbackState != next || playbackActions != actions) {
            playbackState = next
            playbackActions = actions
            session.setPlaybackState(PlaybackState.Builder().setActions(actions)
                .setState(next, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (next == PlaybackState.STATE_PLAYING) 1f else 0f).build())
        }
        val token = activeToken ?: return
        val previous = desiredNotification
        if (previous == null || previous.ownerToken != token || previous.mediaGeneration != mediaGeneration ||
            previous.retained != retained || previous.resumeToken != resumeToken) {
            desiredNotification = NotificationUpdate(token, mediaGeneration, retained, resumeToken,
                ++notificationRevision)
        }
        scheduleNotificationCheck()
    }

    /** Coalesce to the latest state. A successful notify call is not proof Android kept the update. */
    private fun scheduleNotificationCheck() {
        if (finishing || activeToken == null || !host.ownsService(this) || notificationTask != null) return
        notificationTask = Runnable {
            notificationTask = null
            publishLatestNotification()
        }.also { task ->
            val remaining = NOTIFICATION_INTERVAL_MILLIS -
                (SystemClock.elapsedRealtime() - lastNotificationPostElapsed)
            notificationHandler.postDelayed(task, remaining.coerceAtLeast(0L))
        }
    }

    private fun current(update: NotificationUpdate): Boolean = !finishing &&
        desiredNotification === update && activeToken == update.ownerToken &&
        mediaGeneration == update.mediaGeneration && host.ownsService(this)

    @Suppress("DEPRECATION")
    private fun notificationApplied(update: NotificationUpdate): Boolean =
        getSystemService(NotificationManager::class.java).activeNotifications.any { posted ->
            posted.id == NOTIFICATION_ID && posted.packageName == packageName &&
                posted.notification.extras.getLong(EXTRA_NOTIFICATION_REVISION, -1L) == update.revision &&
                posted.notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) ==
                    mediaSession?.sessionToken
        }

    // Only live MediaStyle/session notifications: Android's POST_NOTIFICATIONS media exemption applies.
    // Updates are still rate limited; see developer.android.com/develop/ui/compose/notifications/create-notification#Updating.
    @android.annotation.SuppressLint("NotificationPermission")
    private fun publishLatestNotification() {
        val update = desiredNotification ?: return
        if (!current(update)) return
        try {
            // A lease may have expired since the last UI emission. Recompute without starting I/O.
            if (update.retained != host.hasRetainedMediaPause(this) || update.resumeToken != host.mediaResumeToken(this)) {
                updatePlaybackState(host.controller.ui.value)
                return
            }
            if (!current(update) || notificationApplied(update)) return
            if (update.attempts >= MAX_NOTIFICATION_ATTEMPTS) {
                stopVoice() // No usable current controls: close input/output and release this owner.
                return
            }
            update.attempts++
            lastNotificationPostElapsed = SystemClock.elapsedRealtime()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(update))
            scheduleNotificationCheck()
        } catch (_: RuntimeException) {
            // Never retain a live voice owner whose control notification cannot be maintained.
            if (current(update)) stopVoice()
        }
    }

    private fun notification(update: NotificationUpdate): Notification {
        val token = update.ownerToken
        val retained = update.retained
        val resumeToken = update.resumeToken
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "오누 음성 동행",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "음성 동행의 짧은 쉬기, 이어하기와 끝내기를 표시해요."
            setSound(null, null)
            enableVibration(false)
        })
        fun command(action: String, request: Int, value: String): PendingIntent = PendingIntent.getService(this, request,
            Intent(this, VoiceSessionService::class.java).setAction(action)
                .setData(Uri.parse("onu-private://voice/$request/$value")).putExtra(EXTRA_TOKEN, value),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = command(ACTION_STOP, 0, token)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            // Notification rendering is asynchronous; stale copy must not assert that the mic is off.
            .setContentTitle("오누 음성 동행")
            .setContentText("잠깐 쉬기·이어하기·끝내기로 조절해요.")
            .addExtras(Bundle().apply { putLong(EXTRA_NOTIFICATION_REVISION, update.revision) })
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (retained && resumeToken != null)
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_play, "이어하기",
                command(ACTION_RESUME, 2, resumeToken)).build())
        else if (!retained)
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "잠깐 쉬기",
                command(ACTION_PAUSE, 1, token)).build())
        builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "끝내기", stop).build())
        return builder.setStyle(Notification.MediaStyle().setMediaSession(checkNotNull(mediaSession).sessionToken)
            .setShowActionsInCompactView(0)).build()
    }

    private fun stopVoice() {
        if (finishing) return
        try { host.serviceStopped(this) } finally { finishFromHost() }
    }

    internal fun finishFromHost() {
        if (finishing) return
        finishing = true
        activeToken = null
        mediaGeneration++
        val previous = mediaSession
        mediaSession = null
        sessionResumeToken = null
        playbackState = null
        playbackActions = null
        notificationTask?.let(notificationHandler::removeCallbacks)
        notificationTask = null
        desiredNotification = null
        try {
            host.serviceFinishing(this)
        } finally {
            try {
                try { previous?.isActive = false } finally { previous?.release() }
            } finally {
                try { stopForeground(STOP_FOREGROUND_REMOVE) } finally { stopSelf() }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!finishing) host.cancelExplicitAction()
        stopVoice()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            try { host.serviceStopped(this) } finally { finishFromHost() }
        } finally {
            try { super.onDestroy() } finally { host.serviceDestroyed(this) }
        }
    }

    companion object {
        internal const val ACTION_START = "dev.carplaymate.demo.voice.START"
        internal const val ACTION_STOP = "dev.carplaymate.demo.voice.STOP"
        internal const val ACTION_PAUSE = "dev.carplaymate.demo.voice.PAUSE"
        internal const val ACTION_RESUME = "dev.carplaymate.demo.voice.RESUME"
        internal const val EXTRA_TOKEN = "voice_start_token"
        private const val CHANNEL_ID = "onu-voice-session"
        private const val NOTIFICATION_ID = 2201
        private const val EXTRA_NOTIFICATION_REVISION = "dev.carplaymate.demo.voice.notification_revision"
        private const val NOTIFICATION_INTERVAL_MILLIS = 1_000L
        private const val MAX_NOTIFICATION_ATTEMPTS = 3
        // Process-local rate bookkeeping only; never persisted or treated as voice authorization.
        private var lastNotificationPostElapsed = 0L
    }
}
