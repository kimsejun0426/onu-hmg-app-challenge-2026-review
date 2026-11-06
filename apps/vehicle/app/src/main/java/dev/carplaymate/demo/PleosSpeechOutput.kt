package dev.carplaymate.demo

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal interface PleosTtsEngine {
    fun initialize(listener: OnInitializeListener)
    fun addListener(listener: EventListener)
    fun removeListener(listener: EventListener)
    fun setLocale(locale: Locale)
    fun setAudioAttributes(attributes: AudioAttributes)
    fun speak(text: String)
    fun stop()
    fun release()
}

private class OnDevicePleosTts(context: Context) : PleosTtsEngine {
    private val sdk = TextToSpeech(context, Mode.ON_DEVICE)
    override fun initialize(listener: OnInitializeListener) = sdk.initialize(listener)
    override fun addListener(listener: EventListener) = sdk.addEventListener(listener)
    override fun removeListener(listener: EventListener) = sdk.removeEventListener(listener)
    override fun setLocale(locale: Locale) = sdk.setLocale(locale)
    override fun setAudioAttributes(attributes: AudioAttributes) = sdk.setAudioAttributes(attributes)
    override fun speak(text: String) = sdk.speak(text, true)
    override fun stop() = sdk.stop()
    override fun release() = sdk.release()
}

/** Retained as a source-compatible name for existing adapter fixtures. */
internal interface PleosPlaybackFocus : PlaybackAudioFocus

private fun speechAttributes(): AudioAttributes = storyAudioAttributes()

/** Vehicle Speech Service adapter. Each playback owns its SDK instance and callbacks. */
class PleosSpeechOutput internal constructor(
    private val engineFactory: () -> PleosTtsEngine,
    private val capability: () -> String?,
    private val focus: PlaybackAudioFocus,
    private val onInterrupted: () -> Unit,
    private val typedCapability: (() -> SpeechOutputFailure?)? = null,
    private val timer: CaptureTimer = MainThreadCaptureTimer(),
) : SpeechOutput {
    constructor(context: Context, onInterrupted: () -> Unit) : this(
        context, onInterrupted, AndroidPlaybackAudioFocus(context.applicationContext),
    )

    internal constructor(context: Context, onInterrupted: () -> Unit, focus: PlaybackAudioFocus) : this(
        { OnDevicePleosTts(context.applicationContext) },
        { PleosSpeechRuntime.unavailableReason(context.applicationContext, PleosSpeechRuntime.TTS_PERMISSION) },
        focus,
        onInterrupted,
        typedCapability = {
            PleosSpeechRuntime.unavailableFailure(context.applicationContext,
                PleosSpeechRuntime.TTS_PERMISSION)?.let { SpeechOutputFailure(it.message, it.cause) }
        },
    )

    private val handler = Handler(Looper.getMainLooper())
    private val mutableStatus = MutableStateFlow("Pleos 한국어 음성 확인 중")
    override val status: StateFlow<String> = mutableStatus
    private val mutableReady = MutableStateFlow(false)
    override val readyState: StateFlow<Boolean> = mutableReady
    private var preparationFailure = SpeechOutputFailure("Pleos 한국어 음성을 준비하고 있어요.", RecoveryCause.OUTPUT_UNAVAILABLE)
    override val availabilityFailure: SpeechOutputFailure?
        get() = if (mutableReady.value) null else preparationFailure
    private var closed = false
    private var active: Attempt? = null

    private class Attempt(
        val engine: PleosTtsEngine,
        val playbackId: String?,
        val generation: Long?,
        val text: String?,
        val complete: (() -> Unit)?,
        val failure: ((SpeechOutputFailure) -> Unit)?,
        val onStarted: (() -> Unit)?,
    ) {
        var listener: EventListener? = null
        var timeout: Runnable? = null
        var initialized = false
        @Volatile var requested = false
        var startNotified = false
    }

    init { refresh() }

    private fun unavailableFailure(): SpeechOutputFailure? =
        if (typedCapability != null) typedCapability.invoke()
        else capability()?.let { SpeechOutputFailure(it, RecoveryCause.OUTPUT_UNAVAILABLE) }

    override fun refresh() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        cancelAll()
        preparationFailure = SpeechOutputFailure("Pleos 한국어 음성을 준비하고 있어요.", RecoveryCause.OUTPUT_UNAVAILABLE)
        mutableReady.value = false
        mutableStatus.value = "Pleos 한국어 음성 확인 중"
        unavailableFailure()?.let {
            preparationFailure = it
            mutableStatus.value = it.message
            return
        }
        // Initialization only. A capability refresh cannot speak or acquire audio focus.
        begin(null, null, null, null, null, null)
    }

    override fun speak(plan: SpeechPlan, generation: Long, complete: () -> Unit,
                       failure: (SpeechOutputFailure) -> Unit, started: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        cancelAll()
        if (closed) {
            failure(SpeechOutputFailure("음성 연결이 종료됐어요. 앱을 다시 열어 주세요.", RecoveryCause.OUTPUT_UNAVAILABLE))
            return
        }
        unavailableFailure()?.let {
            preparationFailure = it
            mutableReady.value = false
            mutableStatus.value = it.message
            failure(it)
            return
        }
        if (plan.segments.isEmpty() || plan.segments.any { it.text.isBlank() }) {
            failure(SpeechOutputFailure("안내할 내용을 확인하지 못해 조용히 멈췄어요."))
            return
        }
        // One completed SDK request covers the complete ordered plan. SDK onDone has no segment ID.
        begin(plan.playbackId, generation, plan.segments.joinToString("\n") { it.text }, complete, failure, started)
    }

    private fun begin(playbackId: String?, generation: Long?, text: String?,
                      complete: (() -> Unit)?, failure: ((SpeechOutputFailure) -> Unit)?, started: (() -> Unit)?) {
        val current = try {
            Attempt(engineFactory(), playbackId, generation, text, complete, failure, started).also { active = it }
        } catch (_: SecurityException) {
            preparationFailure = SpeechOutputFailure("Pleos 음성 서비스 권한을 확인해 주세요.", RecoveryCause.OUTPUT_PERMISSION)
            mutableReady.value = false
            mutableStatus.value = preparationFailure.message
            failure?.invoke(preparationFailure)
            return
        } catch (_: Exception) {
            preparationFailure = SpeechOutputFailure("Pleos 음성 출력을 준비하지 못했어요.", RecoveryCause.OUTPUT_UNAVAILABLE)
            mutableReady.value = false
            mutableStatus.value = preparationFailure.message
            failure?.invoke(preparationFailure)
            return
        }
        fun dispatch(action: () -> Unit) {
            handler.post { if (!closed && active === current) action() }
        }
        fun dispatchRequested(action: () -> Unit) {
            // Fence at receipt and again on the main thread: registration events are not playback.
            if (!current.requested) return
            dispatch { if (current.requested) action() }
        }
        fun fail(message: String, cause: RecoveryCause = RecoveryCause.OUTPUT_FAILED) {
            if (active !== current) return
            cancelAll()
            preparationFailure = SpeechOutputFailure(message, cause)
            mutableReady.value = false
            mutableStatus.value = message
            current.failure?.invoke(preparationFailure)
        }
        fun timeout(milliseconds: Long) {
            current.timeout?.let(timer::remove)
            current.timeout = Runnable {
                fail("Pleos 음성 안내가 끝나지 않아 잠시 쉬고 있어요. 음성 설정을 확인해 주세요.")
            }.also { timer.postDelayed(it, milliseconds) }
        }
        val listener = object : EventListener {
            override fun onReady() = Unit
            override fun onStart() = dispatchRequested {
                if (current.playbackId == null || current.generation == null ||
                    current.startNotified) return@dispatchRequested
                current.startNotified = true
                current.onStarted?.invoke()
            }
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onDone() = dispatchRequested {
                if (current.playbackId == null || current.generation == null) return@dispatchRequested
                dispose(current, cancel = false)
                current.complete?.invoke()
            }
            override fun onStop() = dispatchRequested {
                // A platform-driven stop is an interruption, never a completed game prompt.
                stop()
                mutableStatus.value = "차량 음성이 중단되어 조용히 쉬고 있어요."
                current.failure?.invoke(SpeechOutputFailure(mutableStatus.value, RecoveryCause.OUTPUT_FAILED))
            }
            override fun onError(errorType: ErrorType) = dispatch { fail(failureMessage(errorType), failureCause(errorType)) }
        }
        current.listener = listener
        timeout(10_000L)
        try {
            current.engine.initialize(object : OnInitializeListener {
                override fun onSuccess() = dispatch {
                    if (current.initialized) return@dispatch
                    current.initialized = true
                    try {
                        current.engine.setLocale(Locale.KOREA)
                        current.engine.setAudioAttributes(speechAttributes())
                        mutableReady.value = true
                        mutableStatus.value = "Pleos 온디바이스 한국어 음성 준비 완료"
                        if (current.text == null) {
                            dispose(current, cancel = false)
                        } else {
                            current.engine.addListener(listener)
                            val gained = focus.acquire {
                                if (active === current) {
                                    stop() // Fence and halt before informing the ViewModel.
                                    onInterrupted()
                                }
                            }
                            if (!gained) {
                                fail("차량 오디오를 사용할 수 없어 조용히 멈췄어요.", RecoveryCause.AUDIO_FOCUS)
                            } else if (active === current) {
                                timeout((current.text.length * 220L + 15_000L).coerceIn(25_000L, 120_000L))
                                if (closed || active !== current) return@dispatch
                                // Synchronous callbacks inside speak belong to this request.
                                current.requested = true
                                current.engine.speak(current.text)
                            }
                        }
                    } catch (_: SecurityException) {
                        fail("Pleos 음성 서비스 권한을 확인해 주세요.", RecoveryCause.OUTPUT_PERMISSION)
                    } catch (_: Exception) {
                        fail("Pleos 음성 안내를 시작하지 못해 조용히 멈췄어요.")
                    }
                }
                override fun onFailure(errorType: ErrorType) = dispatch { fail(failureMessage(errorType), failureCause(errorType)) }
            })
        } catch (_: SecurityException) {
            fail("Pleos 음성 서비스 권한을 확인해 주세요.", RecoveryCause.OUTPUT_PERMISSION)
        } catch (_: Exception) {
            fail("Pleos 음성 출력을 초기화하지 못했어요.")
        }
    }

    private fun dispose(current: Attempt, cancel: Boolean) {
        if (active !== current) return
        active = null // Invalidate before stop/remove/release, including synchronous callbacks.
        current.timeout?.let(timer::remove)
        current.timeout = null
        if (cancel && current.requested) runCatching { current.engine.stop() }
        current.listener?.let { runCatching { current.engine.removeListener(it) } }
        runCatching { current.engine.release() }
        focus.release()
    }

    override fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        // Pausing/navigating cannot cancel a silent capability check and leave readiness pending.
        // It owns no playback, audio focus, or microphone; success never starts a story.
        active?.takeIf { it.text != null }?.let { dispose(it, cancel = true) }
        focus.release()
    }

    private fun cancelAll() {
        active?.let { dispose(it, cancel = true) }
        focus.release()
    }

    override fun shutdown() {
        check(Looper.myLooper() == Looper.getMainLooper())
        cancelAll()
        closed = true
        preparationFailure = SpeechOutputFailure("음성 연결이 종료됐어요. 앱을 다시 열어 주세요.", RecoveryCause.OUTPUT_UNAVAILABLE)
        mutableReady.value = false
    }

    private fun failureCause(error: ErrorType): RecoveryCause = when (error) {
        ErrorType.PERMISSION_DENIED, ErrorType.UNAUTHENTICATED -> RecoveryCause.OUTPUT_PERMISSION
        ErrorType.UNAVAILABLE_LANGUAGE -> RecoveryCause.OUTPUT_LANGUAGE
        ErrorType.ON_DEVICE_ENGINE_ERROR, ErrorType.NOT_SUPPORTED_API -> RecoveryCause.OUTPUT_UNAVAILABLE
        else -> RecoveryCause.OUTPUT_FAILED
    }

    private fun failureMessage(error: ErrorType): String = when (error) {
        ErrorType.PERMISSION_DENIED -> "Pleos 음성 서비스 권한이 없어 조용히 멈췄어요."
        ErrorType.UNAVAILABLE_LANGUAGE -> "차량의 한국어 음성 안내를 준비하지 못했어요."
        ErrorType.ON_DEVICE_ENGINE_ERROR, ErrorType.NOT_SUPPORTED_API ->
            "차량의 온디바이스 음성 출력을 사용할 수 없어요."
        ErrorType.UNAUTHENTICATED -> "차량 음성 서비스의 사용 권한을 확인해 주세요."
        else -> "Pleos 음성 안내를 완료하지 못했어요. 음성 설정에서 다시 확인해 주세요."
    }
}
