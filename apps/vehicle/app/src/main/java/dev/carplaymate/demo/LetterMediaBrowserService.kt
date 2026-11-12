package dev.carplaymate.demo

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.service.media.MediaBrowserService
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Standard media projection of a live letter. This service never creates a microphone owner. */
class LetterMediaBrowserService : MediaBrowserService() {
    private val host get() = (application as OnuApplication).letterSessionHost
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var media: MediaSession
    private var destroyed = false
    private var lastMetadata: Pair<String?, String>? = null

    override fun onCreate() {
        super.onCreate()
        media = MediaSession(this, "OnuLetter").apply {
            // CarMediaService can read the token immediately. It must never see a null state.
            setPlaybackState(PlaybackState.Builder().setActions(0L)
                .setState(PlaybackState.STATE_STOPPED, 0L, 0f).build())
            setSessionActivity(PendingIntent.getActivity(this@LetterMediaBrowserService, 0,
                Intent(this@LetterMediaBrowserService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            setCallback(object : MediaSession.Callback() {
                private fun trusted() = !destroyed && trustedController()
                override fun onPause() { if (trusted()) host.pauseFromMediaControl() }
                override fun onStop() { if (trusted()) host.stop() }
                // Stable MBS tokens cannot distinguish an old queued PLAY from a new one.
                // Only the visible, one-shot custom action is permission to resume this owner.
                override fun onPlay() = Unit
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = Unit
                override fun onPlayFromSearch(query: String?, extras: Bundle?) = Unit
                override fun onPlayFromUri(uri: android.net.Uri?, extras: Bundle?) = Unit
                override fun onPrepare() = Unit
                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) = Unit
                override fun onPrepareFromSearch(query: String?, extras: Bundle?) = Unit
                override fun onPrepareFromUri(uri: android.net.Uri?, extras: Bundle?) = Unit
                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (!trusted()) return
                    when {
                        action == PAUSE_ACTION -> host.pauseFromMediaControl()
                        action == STOP_ACTION -> host.stop()
                        action.startsWith(RESUME_PREFIX) && action.length <= RESUME_PREFIX.length + 36 ->
                            host.resumeFromMediaControl(action.removePrefix(RESUME_PREFIX))
                    }
                }
                @Suppress("DEPRECATION")
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    if (event.keyCode !in setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP)) return false
                    if (trusted() && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_STOP -> host.stop()
                            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> host.pauseFromMediaControl()
                        }
                    }
                    return true // PLAY cannot restart capture, including hardware toggles after pause.
                }
            }, Handler(Looper.getMainLooper()))
        }
        sessionToken = media.sessionToken
        scope.launch { host.mediaState.collect { render(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A host may start as well as bind. Never retain a started service or restore capture.
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? =
        if (trustedIdentity(clientPackageName, clientUid, -1)) BrowserRoot(ROOT, null) else null

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        if (!trustedBrowser()) { result.sendResult(mutableListOf()); return }
        result.sendResult(if (parentId == ROOT) mutableListOf(
            item("first_jump", "바다에서 온 소리 편지", "오누에서 시작 · 펭귄의 첫 점프를 함께 중계해요"),
            item("rain_music", "펭귄에게서 온 답장", "오누에서 시작 · 빗방울과 와이퍼로 만드는 방송"),
        ) else mutableListOf())
    }

    override fun onLoadItem(itemId: String, result: Result<MediaBrowser.MediaItem>) {
        if (!trustedBrowser()) { result.sendResult(null); return }
        result.sendResult(when (itemId) {
            "first_jump" -> item(itemId, "바다에서 온 소리 편지", "오누에서 편지를 시작해 주세요")
            "rain_music" -> item(itemId, "펭귄에게서 온 답장", "오누에서 편지를 시작해 주세요")
            else -> null
        })
    }

    private fun item(id: String, title: String, description: String) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(description).build(),
        0) // Informational: selection alone cannot start a microphone owner.

    private fun trustedBrowser(): Boolean = try {
        val caller = currentBrowserInfo
        trustedIdentity(caller.packageName, caller.uid, caller.pid)
    } catch (_: RuntimeException) { false }

    private fun trustedController(): Boolean = try {
        val caller = media.currentControllerInfo
        trustedIdentity(caller.packageName, caller.uid, caller.pid) &&
            (caller.uid == Process.myUid() ||
                getSystemService(MediaSessionManager::class.java).isTrustedForMediaControl(caller))
    } catch (_: RuntimeException) { false }

    private fun trustedIdentity(name: String, uid: Int, pid: Int): Boolean =
        name in packageManager.getPackagesForUid(uid).orEmpty() &&
            (uid == Process.myUid() || uid == Process.SYSTEM_UID ||
                checkPermission(Manifest.permission.MEDIA_CONTENT_CONTROL, pid, uid) == PackageManager.PERMISSION_GRANTED)

    private fun render(value: LetterMediaState) {
        if (destroyed) return
        val transport = value.transport
        val state = when {
            !value.owned -> PlaybackState.STATE_STOPPED
            value.resumeToken != null -> PlaybackState.STATE_PAUSED
            else -> when (transport?.phase) {
                LetterTransportPhase.PREPARING -> PlaybackState.STATE_BUFFERING
                LetterTransportPhase.PLAYING -> PlaybackState.STATE_PLAYING
                LetterTransportPhase.INPUT, LetterTransportPhase.PROCESSING -> PlaybackState.STATE_PAUSED
                LetterTransportPhase.ERROR -> PlaybackState.STATE_ERROR
                else -> PlaybackState.STATE_STOPPED
            }
        }
        val status = when {
            !value.owned -> "오누에서 편지를 시작해 주세요"
            value.resumeToken != null -> "잠깐 쉬는 중 · 이어가면 질문을 다시 들려줘요"
            transport?.phase == LetterTransportPhase.INPUT -> "아이가 답할 차례예요"
            transport?.phase == LetterTransportPhase.PROCESSING -> "답을 확인하는 중"
            transport?.phase == LetterTransportPhase.PLAYING -> "오누와 소리 편지를 나누는 중"
            transport?.phase == LetterTransportPhase.PREPARING -> "소리를 준비하는 중"
            transport?.phase == LetterTransportPhase.ERROR -> "앱에서 편지를 다시 열어 주세요"
            else -> "편지가 멈춰 있어요"
        }
        val builder = PlaybackState.Builder().setActions(if (!value.owned) 0L else
            PlaybackState.ACTION_STOP or if (value.resumeToken == null) PlaybackState.ACTION_PAUSE else 0L)
            .setState(state, transport?.positionMs ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
        if (value.owned) {
            val token = value.resumeToken
            if (token != null) builder.addCustomAction(RESUME_PREFIX + token, "질문 다시 듣기", android.R.drawable.ic_media_play)
            else builder.addCustomAction(PAUSE_ACTION, "잠깐 쉬기", android.R.drawable.ic_media_pause)
            builder.addCustomAction(STOP_ACTION, "편지 닫기", android.R.drawable.ic_menu_close_clear_cancel)
        }
        media.setPlaybackState(builder.build())
        val metadataKey = value.episodeId to status
        if (lastMetadata != metadataKey) {
            lastMetadata = metadataKey
            media.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, value.episodeId.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_TITLE, value.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, status)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, status).build())
        }
        media.isActive = value.owned
    }

    override fun onDestroy() {
        destroyed = true
        scope.coroutineContext[Job]?.cancel()
        try {
            // The private owner holds a binding while live; unexpected binding loss stops it.
            // Normal idle-browser teardown must never stop a later, unrelated owner.
            media.setPlaybackState(PlaybackState.Builder().setActions(0L)
                .setState(PlaybackState.STATE_STOPPED, 0L, 0f).build())
            media.isActive = false
            media.release()
        } finally { super.onDestroy() }
    }

    companion object {
        internal const val ROOT = "onu_letters"
        internal const val PAUSE_ACTION = "dev.carplaymate.letter.PAUSE"
        internal const val STOP_ACTION = "dev.carplaymate.letter.STOP"
        internal const val RESUME_PREFIX = "dev.carplaymate.letter.RESUME:"
    }
}
