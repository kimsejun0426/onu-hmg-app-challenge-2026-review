package dev.carplaymate.demo

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test boundary around the platform engine; production always selects an installed offline voice.
 */
internal interface AndroidTtsEngine {
    fun prepareOfflineKoreanVoice(attributes: AudioAttributes): Boolean

    fun setListener(listener: UtteranceProgressListener)

    fun playSilence(milliseconds: Long, utteranceId: String): Boolean

    fun speak(text: String, utteranceId: String): Boolean

    fun stop()

    fun shutdown()
}

private class PlatformAndroidTtsEngine(context: Context, initialized: (Int) -> Unit) :
    AndroidTtsEngine {
    private val tts = TextToSpeech(context) { result -> initialized(result) }

    override fun prepareOfflineKoreanVoice(attributes: AudioAttributes): Boolean {
        val voice =
            tts.voices
                .orEmpty()
                .filter {
                    it.locale.language == "ko" &&
                        !it.isNetworkConnectionRequired &&
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                }
                .sortedWith(
                    compareByDescending<Voice> { it.quality }
                        .thenBy { it.latency }
                        .thenBy { it.name }
                )
                .firstOrNull() ?: return false
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) return false
        return tts.setAudioAttributes(attributes) == TextToSpeech.SUCCESS &&
            tts.setSpeechRate(0.92f) == TextToSpeech.SUCCESS
    }

    override fun setListener(listener: UtteranceProgressListener) {
        check(tts.setOnUtteranceProgressListener(listener) == TextToSpeech.SUCCESS)
    }

    override fun playSilence(milliseconds: Long, utteranceId: String) =
        tts.playSilentUtterance(milliseconds, TextToSpeech.QUEUE_FLUSH, utteranceId) !=
            TextToSpeech.ERROR

    override fun speak(text: String, utteranceId: String) =
        tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), utteranceId) != TextToSpeech.ERROR

    override fun stop() {
        tts.stop()
    }

    override fun shutdown() {
        tts.shutdown()
    }
}

/** Allows deterministic timeout injection without sleeping or contacting a speech service. */
internal interface AndroidTtsDeadlines {
    fun schedule(task: Runnable, milliseconds: Long)

    fun cancel(task: Runnable)
}

private class MainThreadTtsDeadlines : AndroidTtsDeadlines {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(task: Runnable, milliseconds: Long) {
        handler.postDelayed(task, milliseconds)
    }

    override fun cancel(task: Runnable) {
        handler.removeCallbacks(task)
    }
}

/** Offline Korean TTS. This is not a Pleos SDK adapter. */
class AndroidTtsOutput
internal constructor(
    context: Context,
    private val onInterrupted: () -> Unit,
    private val engineFactory: (Context, (Int) -> Unit) -> AndroidTtsEngine,
    private val deadlines: AndroidTtsDeadlines,
    private val focus: PlaybackAudioFocus = AndroidPlaybackAudioFocus(context),
) : SpeechOutput {
    constructor(
        context: Context,
        onInterrupted: () -> Unit,
    ) : this(context, onInterrupted, ::PlatformAndroidTtsEngine, MainThreadTtsDeadlines())

    internal constructor(context: Context, onInterrupted: () -> Unit, focus: PlaybackAudioFocus) :
        this(context, onInterrupted, ::PlatformAndroidTtsEngine, MainThreadTtsDeadlines(), focus)

    private val application = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutableStatus = MutableStateFlow("한국어 기기 음성 확인 중")
    override val status: StateFlow<String> = mutableStatus
    private val mutableReady = MutableStateFlow(false)
    override val readyState: StateFlow<Boolean> = mutableReady
    private var preparationFailure = SpeechOutputFailure("한국어 기기 음성을 준비하고 있어요.", RecoveryCause.OUTPUT_UNAVAILABLE)
    override val availabilityFailure: SpeechOutputFailure?
        get() = if (ready) null else preparationFailure
    private var ready = false
    private var closed = false
    private var initialization = 0L
    private var initializationTimeout: Runnable? = null
    private var watchdog: Runnable? = null
    private var engine: AndroidTtsEngine? = null
    private var pending: Playback? = null
    private var playbackSequence = 0L

    private data class Playback(
        val ids: MutableSet<String>,
        val spokenIds: Set<String>,
        val complete: () -> Unit,
        val failure: (SpeechOutputFailure) -> Unit,
        val started: () -> Unit,
    ) {
        var startNotified = false
    }

    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    private fun acquireFocus(): Boolean = focus.acquire {
        stop()
        onInterrupted()
    }

    private fun releaseFocus() = focus.release()

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        val generation = ++initialization
        clearInitializationTimeout()
        ready = false
        preparationFailure = SpeechOutputFailure("한국어 기기 음성을 준비하고 있어요.", RecoveryCause.OUTPUT_UNAVAILABLE)
        mutableReady.value = false
        mutableStatus.value = "한국어 기기 음성 확인 중"
        engine?.shutdown()
        engine = null
        initializationTimeout =
            Runnable {
                    failInitialization(generation, "기기 음성 준비가 지연됐어요. 읽으며 체험하거나 음성 설정에서 다시 확인해 주세요.")
                }
                .also { deadlines.schedule(it, INITIALIZATION_TIMEOUT_MILLIS) }
        val initialized: (Int) -> Unit = { result ->
            handler.post {
                if (closed || generation != initialization || initializationTimeout == null)
                    return@post
                val tts = engine
                if (result != TextToSpeech.SUCCESS || tts == null) {
                    failInitialization(generation, "음성을 준비하지 못했어요. 읽으며 체험을 선택할 수 있어요.")
                    return@post
                }
                val prepared = try {
                    tts.prepareOfflineKoreanVoice(attributes)
                } catch (_: SecurityException) {
                    failInitialization(generation, "기기 음성 안내 권한을 확인해 주세요.", RecoveryCause.OUTPUT_PERMISSION)
                    return@post
                } catch (_: Exception) {
                    failInitialization(generation, "기기 음성 안내를 준비하지 못했어요.")
                    return@post
                }
                if (!prepared) {
                    failInitialization(generation, "오프라인 한국어 음성 없음 · 기기에 음성을 설치해 주세요", RecoveryCause.OUTPUT_LANGUAGE)
                    return@post
                }
                clearInitializationTimeout()
                ready = true
                mutableReady.value = true
                mutableStatus.value = "한국어 음성 준비 완료"
            }
        }
        try {
            engine = engineFactory(application, initialized)
            engine?.setListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        handler.post {
                            if (closed || generation != initialization) return@post
                            val current = pending ?: return@post
                            // The silent preroll also has onStart; only an unfinished spoken
                            // segment in this request can announce the first start.
                            if (utteranceId == null || utteranceId !in current.spokenIds ||
                                utteranceId !in current.ids || current.startNotified) return@post
                            current.startNotified = true
                            current.started()
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            val current = pending ?: return@post
                            if (
                                utteranceId != null &&
                                    current.ids.remove(utteranceId) &&
                                    current.ids.isEmpty()
                            ) {
                                pending = null
                                watchdog?.let(deadlines::cancel)
                                watchdog = null
                                releaseFocus()
                                current.complete()
                            }
                        }
                    }

                    @Deprecated("Required legacy callback")
                    override fun onError(utteranceId: String?) = reportError(utteranceId)

                    override fun onError(utteranceId: String?, errorCode: Int) =
                        reportError(utteranceId)

                    override fun onStop(utteranceId: String?, interrupted: Boolean) =
                        reportError(utteranceId)
                }
            )
        } catch (_: SecurityException) {
            failInitialization(generation, "기기 음성 안내 권한을 확인해 주세요.", RecoveryCause.OUTPUT_PERMISSION)
        } catch (_: Exception) {
            failInitialization(generation, "음성을 준비하지 못했어요. 읽으며 체험을 선택할 수 있어요.")
        }
    }

    private fun clearInitializationTimeout() {
        initializationTimeout?.let(deadlines::cancel)
        initializationTimeout = null
    }

    private fun failInitialization(generation: Long, message: String, cause: RecoveryCause = RecoveryCause.OUTPUT_UNAVAILABLE) {
        if (closed || generation != initialization || initializationTimeout == null) return
        // Invalidate before shutdown: a disconnected engine can still deliver its onInit callback.
        initialization++
        clearInitializationTimeout()
        ready = false
        preparationFailure = SpeechOutputFailure(message, cause)
        mutableReady.value = false
        mutableStatus.value = message
        val previous = engine
        engine = null
        runCatching { previous?.shutdown() }
    }

    /** Explicit settings action only; refreshing capability never starts playback. */
    override fun refresh() {
        if (closed) return
        stop()
        initializeEngine()
    }

    private fun reportError(id: String?) {
        handler.post {
            val current = pending ?: return@post
            if (id != null && id in current.ids) {
                stop()
                current.failure(SpeechOutputFailure("음성 재생이 완료되지 않아 조용히 멈췄어요."))
            }
        }
    }

    override fun speak(
        plan: SpeechPlan,
        generation: Long,
        complete: () -> Unit,
        failure: (SpeechOutputFailure) -> Unit,
        started: () -> Unit,
    ) {
        stop()
        val tts = engine
        if (!ready || tts == null) {
            failure(preparationFailure)
            return
        }
        if (plan.segments.isEmpty()) {
            failure(SpeechOutputFailure("안내할 내용을 확인하지 못해 조용히 멈췄어요."))
            return
        }
        if (!acquireFocus()) {
            failure(SpeechOutputFailure("오디오 사용이 허용되지 않아 조용히 멈췄어요.", RecoveryCause.AUDIO_FOCUS))
            return
        }
        // A replaced request must have distinct platform IDs even if a caller reuses its plan.
        val prefix = "${plan.playbackId}:$generation:${++playbackSequence}"
        val silenceId = "$prefix:preroll"
        val utteranceIds = plan.segments.map { "$prefix:${it.segmentId}" }
        pending = Playback((utteranceIds + silenceId).toMutableSet(), utteranceIds.toSet(), complete, failure, started)
        // A missing engine callback must not leave a family stuck indefinitely.
        val limit =
            (plan.segments.sumOf { it.text.length } * 220L + 15_000L).coerceIn(25_000L, 120_000L)
        watchdog =
            Runnable {
                    if (pending != null) {
                        stop()
                        failure(SpeechOutputFailure("음성 안내가 끝나지 않아 잠시 쉬고 있어요. 다시 이어가거나 읽으며 체험해 주세요."))
                    }
                }
                .also { deadlines.schedule(it, limit) }
        // One pre-roll per new playback. Do not insert silence between continuous chunks.
        if (!tts.playSilence(180L, silenceId)) {
            reportError(silenceId)
            return
        }
        plan.segments.forEachIndexed { index, segment ->
            if (!tts.speak(segment.text, utteranceIds[index])) {
                reportError(utteranceIds[index])
                return
            }
        }
    }

    override fun stop() {
        pending = null
        watchdog?.let(deadlines::cancel)
        watchdog = null
        engine?.stop()
        releaseFocus()
    }

    override fun shutdown() {
        stop()
        ready = false
        preparationFailure = SpeechOutputFailure("기기 음성 연결이 종료됐어요. 앱을 다시 열어 주세요.", RecoveryCause.OUTPUT_UNAVAILABLE)
        closed = true
        initialization++
        clearInitializationTimeout()
        mutableReady.value = false
        engine?.shutdown()
        engine = null
    }

    companion object {
        internal const val INITIALIZATION_TIMEOUT_MILLIS = 10_000L
    }
}
