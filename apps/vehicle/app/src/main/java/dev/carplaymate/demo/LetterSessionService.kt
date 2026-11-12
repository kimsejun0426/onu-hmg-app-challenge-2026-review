package dev.carplaymate.demo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.SystemClock
import android.os.Bundle

/** Private microphone owner. The browser projects state but never grants capture permission. */
class LetterSessionService : Service() {
    private val host get() = (application as OnuApplication).letterSessionHost
    private var token: String? = null
    private var finishing = false
    private var foregroundAt = 0L
    private var mediaBound = false
    private val notificationHandler = Handler(Looper.getMainLooper())
    private var notificationUpdate: Runnable? = null
    private var shownResume: String? = null
    private val mediaConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
        override fun onServiceDisconnected(name: ComponentName?) { if (host.owns(this@LetterSessionService)) host.stop() }
        override fun onBindingDied(name: ComponentName?) { if (host.owns(this@LetterSessionService)) host.stop() }
        override fun onNullBinding(name: ComponentName?) { if (host.owns(this@LetterSessionService)) host.stop() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val supplied = intent?.getStringExtra(EXTRA_TOKEN)
        if (finishing) {
            host.promotionFailed(supplied)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            if (supplied != null && supplied == token && host.owns(this)) host.pauseFromMediaControl()
            else if (!host.owns(this)) finishFromHost()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            if (supplied != null && supplied == token && host.owns(this))
                intent.getStringExtra(EXTRA_RESUME)?.let(host::resumeFromMediaControl)
            else if (!host.owns(this)) finishFromHost()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP && supplied != null && supplied == token && host.owns(this)) {
            host.stop()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || supplied == null || !host.canPromote(supplied)) {
            if (supplied != null && host.hasNewerStart(supplied)) return START_NOT_STICKY
            host.promotionFailed(supplied)
            if (!host.owns(this)) finishFromHost()
            return START_NOT_STICKY
        }
        try {
            token = supplied
            val notification = notification(supplied)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION_ID, notification)
            foregroundAt = SystemClock.elapsedRealtime()
            if (!host.promoted(this, supplied)) finishFromHost()
            else if (BuildConfig.LETTER_MEDIA_BROWSER_ENABLED) {
                mediaBound = bindService(Intent(this, LetterMediaBrowserService::class.java)
                    .setAction(android.service.media.MediaBrowserService.SERVICE_INTERFACE),
                    mediaConnection, Context.BIND_AUTO_CREATE)
                if (!mediaBound) host.stop()
            }
        } catch (_: RuntimeException) {
            host.promotionFailed(supplied)
            finishFromHost()
        }
        return START_NOT_STICKY
    }

    internal fun controlsHealthy(): Boolean = !finishing && token != null &&
        (SystemClock.elapsedRealtime() - foregroundAt < 3_000L || runCatching {
            getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id == NOTIFICATION_ID && it.notification.extras.getString(EXTRA_TOKEN) == token
            }
        }.getOrDefault(false))

    internal fun updateMediaPause(resumeToken: String?) {
        if (finishing || token == null || shownResume == resumeToken || notificationUpdate != null) return
        notificationUpdate = Runnable {
            notificationUpdate = null
            val ownerToken = token ?: return@Runnable
            if (finishing || !host.owns(this)) return@Runnable
            val latest = host.mediaState.value.resumeToken
            if (shownResume == latest) return@Runnable
            runCatching {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(ownerToken, latest))
            }.onSuccess { shownResume = latest }
        }.also { notificationHandler.postDelayed(it, 1_000L) }
    }

    private fun notification(value: String, resume: String? = null): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "오누 소리 편지", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false)
            })
        val stop = PendingIntent.getService(this, 0, Intent(this, LetterSessionService::class.java)
            .setAction(ACTION_STOP).setData(Uri.parse("onu-private://letter/$value"))
            .putExtra(EXTRA_TOKEN, value), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("오누 소리 편지").setContentText(if (resume == null) "질문이 끝난 뒤에만 답을 들어요."
                else "잠깐 쉬는 중이에요. 이어가면 질문을 다시 들려줘요.")
            .addExtras(Bundle().apply { putString(EXTRA_TOKEN, value) })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC)
        if (resume == null) {
            val pause = PendingIntent.getService(this, 2, Intent(this, LetterSessionService::class.java)
                .setAction(ACTION_PAUSE).setData(Uri.parse("onu-private://letter/$value/pause"))
                .putExtra(EXTRA_TOKEN, value), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "잠깐 쉬기", pause).build())
        }
        builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "편지 닫기", stop).build())
        if (resume != null) {
            val again = PendingIntent.getService(this, 1, Intent(this, LetterSessionService::class.java)
                .setAction(ACTION_RESUME).setData(Uri.parse("onu-private://letter/$value/resume/$resume"))
                .putExtra(EXTRA_TOKEN, value).putExtra(EXTRA_RESUME, resume),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_play, "질문 다시 듣기", again).build())
        }
        return builder.build()
    }

    internal fun finishFromHost() {
        if (finishing) return
        finishing = true
        host.finishing(this)
        token = null
        notificationUpdate?.let(notificationHandler::removeCallbacks)
        notificationUpdate = null
        if (mediaBound) {
            mediaBound = false
            runCatching { unbindService(mediaConnection) }
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } finally { stopSelf() }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (host.owns(this)) host.stop()
        finishFromHost()
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        try {
            if (host.owns(this)) host.stop()
            finishFromHost()
        } finally {
            try { super.onDestroy() } finally { host.destroyed(this) }
        }
    }
    companion object {
        internal const val CONTROLS_REQUIRED = "알림의 쉬기 버튼이 필요해. 보호자와 기기 설정에서 오누 알림을 켠 뒤 다시 시작해 줘."
        internal fun requiredNotificationPermissions(context: Context): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

        internal fun controlsAvailable(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            requiredNotificationPermissions(context).isEmpty() && manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }.getOrDefault(false)

        internal const val ACTION_START = "dev.carplaymate.demo.letter.START"
        internal const val ACTION_PAUSE = "dev.carplaymate.demo.letter.PAUSE"
        internal const val ACTION_RESUME = "dev.carplaymate.demo.letter.RESUME"
        internal const val EXTRA_RESUME = "letter_resume_token"
        internal const val ACTION_STOP = "dev.carplaymate.demo.letter.STOP"
        internal const val EXTRA_TOKEN = "letter_start_token"
        private const val CHANNEL_ID = "onu-sound-letter"
        private const val NOTIFICATION_ID = 2202
    }
}