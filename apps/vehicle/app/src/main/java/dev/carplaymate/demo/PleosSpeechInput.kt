package dev.carplaymate.demo

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** Availability is a device capability check, never a server-mode fallback. */
internal object PleosSpeechRuntime {
    const val PACKAGE = "ai.pleos.playground.caas"
    const val ACTION = "ai.pleos.playground.caas.intent.action.SPEECH_START"
    const val STT_PERMISSION = "pleos.car.permission.STT_SERVICE"
    const val TTS_PERMISSION = "pleos.car.permission.TTS_SERVICE"

    fun unavailableReason(
        context: Context,
        permission: String,
        checkPermission: Boolean = true,
    ): String? = unavailableFailure(context, permission, checkPermission)?.message

    @Suppress("DEPRECATION")
    fun unavailableFailure(
        context: Context,
        permission: String,
        checkPermission: Boolean = true,
    ): SpeechAvailabilityFailure? {
        val isOutput = permission == TTS_PERMISSION
        fun unavailable(message: String) = SpeechAvailabilityFailure(message,
            if (isOutput) RecoveryCause.OUTPUT_UNAVAILABLE else RecoveryCause.INPUT_UNAVAILABLE)
        if (!BuildConfig.PLEOS_ENABLED) return unavailable("Pleos 음성은 시연용 APK에서 사용할 수 있어요.")
        val pm = context.packageManager
        val info =
            runCatching { pm.getPackageInfo(PACKAGE, 0) }.getOrNull()
                ?: return unavailable("이 기기에는 Pleos 차량 음성 서비스가 없어요.")
        if (info.longVersionCode < 4_001_029L) {
            return unavailable("Pleos 차량 음성 서비스를 4.1.29 이상으로 업데이트해 주세요.")
        }
        val services =
            runCatching { pm.queryIntentServices(Intent(ACTION).setPackage(PACKAGE), 0) }
                .getOrDefault(emptyList())
        if (
            services.none {
                it.serviceInfo?.let { service ->
                    service.enabled && service.exported && service.applicationInfo.enabled
                } == true
            }
        ) {
            return unavailable("Pleos 차량 음성 서비스에 연결할 수 없어요. 기기의 서비스 설정을 확인해 주세요.")
        }
        if (
            checkPermission &&
                context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        ) {
            return SpeechAvailabilityFailure(
                "Pleos 차량 음성 서비스 권한이 없어요. 보호자 설정과 차량의 앱 권한을 확인해 주세요.",
                if (isOutput) RecoveryCause.OUTPUT_PERMISSION else RecoveryCause.INPUT_PERMISSION,
            )
        }
        return null
    }
}

/** Small test seam; production always delegates to the published 3.3.1 SDK. */
internal interface PleosSttEngine {
    fun initialize(listener: OnInitializeListener)

    fun addListener(listener: ResultListener)

    fun removeListener(listener: ResultListener)

    fun setLocale(locale: Locale)

    fun request()

    fun stop()

    fun release()
}

private class OnDevicePleosStt(context: Context) : PleosSttEngine {
    private val sdk = SpeechToText(context, Mode.ON_DEVICE)

    override fun initialize(listener: OnInitializeListener) = sdk.initialize(listener)

    override fun addListener(listener: ResultListener) = sdk.addListener(listener)

    override fun removeListener(listener: ResultListener) = sdk.removeListener(listener)

    override fun setLocale(locale: Locale) = sdk.setLocale(locale)

    override fun request() = sdk.request()

    override fun stop() = sdk.stop()

    override fun release() = sdk.release()
}

/** One SDK instance per capture: SDK callbacks have no request identifier. */
class PleosSpeechInput
internal constructor(
    private val engineFactory: () -> PleosSttEngine,
    private val capability: (Boolean) -> String?,
    private val timer: CaptureTimer = MainThreadCaptureTimer(),
    private val typedCapability: ((Boolean) -> SpeechInputFailure?)? = null,
) : SpeechCaptureInput {
    constructor(
        context: Context
    ) : this(
        { OnDevicePleosStt(context.applicationContext) },
        { check ->
            PleosSpeechRuntime.unavailableReason(
                context.applicationContext,
                PleosSpeechRuntime.STT_PERMISSION,
                check,
            )
        },
        typedCapability = { check ->
            PleosSpeechRuntime.unavailableFailure(context.applicationContext,
                PleosSpeechRuntime.STT_PERMISSION, check)?.let {
                SpeechInputFailure(it.message,
                    if (it.cause == RecoveryCause.INPUT_PERMISSION) "MIC_PERMISSION" else "USER", it.cause)
            }
        },
    )

    private val handler = Handler(Looper.getMainLooper())
    private var active: Capture? = null
    override val source = "pleos_stt"
    override val available: Boolean
        get() = unavailableReason() == null

    override fun unavailableReason(checkPermission: Boolean): String? = unavailableFailure(checkPermission)?.message

    override fun unavailableFailure(checkPermission: Boolean): SpeechInputFailure? =
        if (typedCapability != null) typedCapability.invoke(checkPermission)
        else capability(checkPermission)?.let { SpeechInputFailure(it, cause = RecoveryCause.INPUT_UNAVAILABLE) }

    private class Capture(val ticket: CaptureTicket, val engine: PleosSttEngine) {
        var listener: ResultListener? = null
        var timeout: Runnable? = null
        var deadline: CaptureDeadline? = null
        @Volatile var requested = false
        var initialized = false
        var ended = false
    }

    override fun open(
        ticket: CaptureTicket,
        onStage: (CaptureTicket, CaptureStage) -> Unit,
        onFinal: (CaptureTicket, String) -> Unit,
        onFailure: (CaptureTicket, SpeechInputFailure) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val startedAtMillis = timer.nowMillis()
        close()
        unavailableFailure()?.let {
            onFailure(ticket, it)
            return
        }
        val current =
            try {
                Capture(ticket, engineFactory()).also { active = it }
            } catch (_: SecurityException) {
                onFailure(ticket, SpeechInputFailure("Pleos 음성 서비스 권한을 확인해 주세요.", "MIC_PERMISSION", RecoveryCause.INPUT_PERMISSION))
                return
            } catch (_: Exception) {
                onFailure(ticket, SpeechInputFailure("Pleos 음성 입력을 준비하지 못했어요. 조용히 멈췄어요."))
                return
            }

        fun fail(message: String, reason: String = "USER", cause: RecoveryCause = RecoveryCause.INPUT_FAILED) {
            if (active !== current) return
            close()
            onFailure(current.ticket, SpeechInputFailure(message, reason, cause))
        }
        val deadline = CaptureDeadline(startedAtMillis, timer) {
            fail("한 번의 듣기 시간이 길어져 조용히 멈췄어요. 이어하기로 다시 시도해 주세요.", cause = RecoveryCause.INPUT_TIMEOUT)
        }
        current.deadline = deadline
        if (!deadline.start()) return
        fun acceptsCallback(): Boolean = active === current && deadline.accepting()
        fun armTimeout(milliseconds: Long) {
            if (!acceptsCallback()) return
            current.timeout?.let(timer::remove)
            current.timeout =
                object : Runnable {
                    override fun run() {
                        if (current.timeout !== this || !acceptsCallback()) return
                        // SDK TIMEOUT does not distinguish silence from service/engine failure.
                        fail("Pleos 음성 인식이 끝나지 않아 조용히 멈췄어요. 이어하기로 다시 시도해 주세요.", cause = RecoveryCause.INPUT_TIMEOUT)
                    }
                }.also { timer.postDelayed(it, milliseconds) }
        }
        fun dispatch(action: () -> Unit) {
            handler.post { if (acceptsCallback()) action() }
        }
        fun dispatchRequested(action: () -> Unit) {
            // SDK callbacks may arrive on another thread while listener registration is running.
            // A pre-request event must not become current merely by waiting in the main queue.
            if (!current.requested) return
            dispatch { if (current.requested) action() }
        }

        val listener =
            object : ResultListener {
                override fun onReady() = dispatchRequested {
                    if (!current.ended) {
                        armTimeout(20_000L)
                        if (!acceptsCallback()) return@dispatchRequested
                        onStage(current.ticket, CaptureStage.LISTENING)
                    }
                }

                override fun onStartedRecognition() = dispatchRequested {
                    if (!current.ended) {
                        armTimeout(20_000L)
                        if (!acceptsCallback()) return@dispatchRequested
                        onStage(current.ticket, CaptureStage.LISTENING)
                    }
                }

                override fun onUpdated(stt: String, completed: Boolean) = dispatchRequested {
                    if (!completed) {
                        // Intermediate text is not an end-of-capture event. Keep the last confirmed
                        // listening/ended state, including for a late partial after
                        // onEndedRecognition.
                        // No partial transcript is retained or submitted as a game action.
                        return@dispatchRequested
                    }
                    val text = stt.trim()
                    if (text.isBlank() || text.length > 512) {
                        fail("말한 내용을 유효한 문장으로 확인하지 못했어요. 이어하기로 다시 시도해 주세요.")
                    } else {
                        close() // Fence and stop the microphone before delivering the only final.
                        onFinal(current.ticket, text)
                    }
                }

                override fun onEndedRecognition() = dispatchRequested {
                    if (!current.ended) {
                        current.ended = true
                        // A final result can arrive after the end notification; keep a bounded
                        // grace period.
                        onStage(current.ticket, CaptureStage.PROCESSING)
                        armTimeout(2_000L)
                    }
                }

                override fun onError(errorType: ErrorType) = dispatch {
                    fail(
                        failureMessage(errorType),
                        if (errorType == ErrorType.PERMISSION_DENIED) "MIC_PERMISSION" else "USER",
                        failureCause(errorType),
                    )
                }

                override fun onUpdatedEpdData(on: Long, off: Long) = Unit

                override fun onUpdatedRms(rms: Double) = Unit
            }
        current.listener = listener
        armTimeout(10_000L)
        if (!acceptsCallback()) return
        try {
            current.engine.initialize(
                object : OnInitializeListener {
                    override fun onSuccess() = dispatch {
                        if (current.initialized) return@dispatch
                        current.initialized = true
                        try {
                            current.engine.setLocale(Locale.KOREA)
                            if (!acceptsCallback()) return@dispatch
                            current.engine.addListener(listener)
                            armTimeout(10_000L)
                            if (!acceptsCallback()) return@dispatch
                            // Set immediately before request so callbacks inside that call are valid.
                            current.requested = true
                            current.engine.request()
                        } catch (_: SecurityException) {
                            fail("Pleos 음성 서비스 권한을 확인해 주세요.", "MIC_PERMISSION", RecoveryCause.INPUT_PERMISSION)
                        } catch (_: Exception) {
                            fail("Pleos 음성 입력을 시작하지 못해 조용히 멈췄어요.")
                        }
                    }

                    override fun onFailure(errorType: ErrorType) = dispatch {
                        fail(
                            failureMessage(errorType),
                            if (errorType == ErrorType.PERMISSION_DENIED) "MIC_PERMISSION"
                            else "USER",
                            failureCause(errorType),
                        )
                    }
                }
            )
        } catch (_: SecurityException) {
            fail("Pleos 음성 서비스 권한을 확인해 주세요.", "MIC_PERMISSION", RecoveryCause.INPUT_PERMISSION)
        } catch (_: Exception) {
            fail("Pleos 음성 입력을 초기화하지 못해 조용히 멈췄어요.")
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val previous = active
        active = null // Invalidate before stop/remove/release can synchronously call back.
        if (previous != null) {
            previous.deadline?.cancel()
            previous.timeout?.let(timer::remove)
            previous.timeout = null
            if (previous.requested) runCatching { previous.engine.stop() }
            previous.listener?.let { runCatching { previous.engine.removeListener(it) } }
            runCatching { previous.engine.release() }
        }
    }

    private fun failureCause(error: ErrorType): RecoveryCause = when (error) {
        ErrorType.PERMISSION_DENIED, ErrorType.UNAUTHENTICATED -> RecoveryCause.INPUT_PERMISSION
        ErrorType.UNAVAILABLE_LANGUAGE -> RecoveryCause.INPUT_LANGUAGE
        ErrorType.ON_DEVICE_ENGINE_ERROR, ErrorType.NOT_SUPPORTED_API -> RecoveryCause.INPUT_UNAVAILABLE
        ErrorType.TIMEOUT -> RecoveryCause.INPUT_TIMEOUT
        else -> RecoveryCause.INPUT_FAILED
    }

    private fun failureMessage(error: ErrorType): String =
        when (error) {
            ErrorType.PERMISSION_DENIED -> "Pleos 음성 서비스 권한이 없어 조용히 멈췄어요."
            ErrorType.UNAVAILABLE_LANGUAGE -> "차량의 한국어 음성 인식 언어를 준비하지 못했어요."
            ErrorType.ON_DEVICE_ENGINE_ERROR,
            ErrorType.NOT_SUPPORTED_API -> "차량의 온디바이스 음성 인식을 사용할 수 없어 조용히 멈췄어요."
            ErrorType.UNAUTHENTICATED -> "차량 음성 서비스의 사용 권한을 확인해 주세요."
            else -> "Pleos 음성 인식을 완료하지 못했어요. 이어하기로 다시 시도해 주세요."
        }
}
