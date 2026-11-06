package dev.carplaymate.demo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi

enum class CaptureStage { LISTENING, PROCESSING }
data class SpeechInputFailure(
    val message: String,
    val pauseReason: String = "USER",
    val cause: RecoveryCause? = if (pauseReason == "NO_RESPONSE") null else RecoveryCause.INPUT_FAILED,
)

/** Test seam for callback and cleanup behavior; production uses the on-device factory only. */
internal interface AndroidSpeechRecognizerEngine {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun cancel()
    fun destroy()
}

@RequiresApi(Build.VERSION_CODES.S)
private class OnDeviceAndroidRecognizer(context: Context) : AndroidSpeechRecognizerEngine {
    private val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    override fun setRecognitionListener(listener: RecognitionListener) = recognizer.setRecognitionListener(listener)
    override fun startListening(intent: Intent) = recognizer.startListening(intent)
    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()
}

private fun androidInputUnavailableFailure(context: Context, checkPermission: Boolean): SpeechInputFailure? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return SpeechInputFailure("기기 음성 입력은 Android 12(API 31) 이상에서만 사용할 수 있어요. 보호자 설정에서 다른 방식을 선택해 주세요.", cause = RecoveryCause.INPUT_UNAVAILABLE)
    }
    if (!runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)) {
        return SpeechInputFailure("기기에 온디바이스 음성 인식 서비스가 없어요. 보호자 설정에서 다른 방식을 선택해 주세요.", cause = RecoveryCause.INPUT_UNAVAILABLE)
    }
    if (checkPermission && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return SpeechInputFailure("마이크 권한이 없어 음성 입력을 사용할 수 없어요.", "MIC_PERMISSION", RecoveryCause.INPUT_PERMISSION)
    }
    return null
}

/** One utterance at a time; never uses the default/network recognition factory. */
class AndroidOnDeviceSpeechInput internal constructor(
    private val engineFactory: () -> AndroidSpeechRecognizerEngine,
    private val capability: (Boolean) -> String?,
    private val timer: CaptureTimer = MainThreadCaptureTimer(),
    private val typedCapability: ((Boolean) -> SpeechInputFailure?)? = null,
) : SpeechCaptureInput {
    constructor(context: Context) : this(
        {
            // Keep the factory guarded independently of the capability preflight in open().
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                OnDeviceAndroidRecognizer(context.applicationContext)
            } else {
                error("On-device speech recognition requires Android 12 or later.")
            }
        },
        { check -> androidInputUnavailableFailure(context.applicationContext, check)?.message },
        typedCapability = { check -> androidInputUnavailableFailure(context.applicationContext, check) },
    )

    private var recognizer: AndroidSpeechRecognizerEngine? = null
    private var timeout: Runnable? = null
    private var deadline: CaptureDeadline? = null
    override val source = "android_on_device"
    override val available: Boolean get() = unavailableReason() == null

    override fun unavailableReason(checkPermission: Boolean): String? = unavailableFailure(checkPermission)?.message

    override fun unavailableFailure(checkPermission: Boolean): SpeechInputFailure? =
        if (typedCapability != null) typedCapability.invoke(checkPermission)
        else capability(checkPermission)?.let { SpeechInputFailure(it, cause = RecoveryCause.INPUT_UNAVAILABLE) }

    override fun open(
        ticket: CaptureTicket,
        onStage: (CaptureTicket, CaptureStage) -> Unit,
        onFinal: (CaptureTicket, String) -> Unit,
        onFailure: (CaptureTicket, SpeechInputFailure) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val startedAtMillis = timer.nowMillis()
        close()
        val unavailable = unavailableFailure()
        if (unavailable != null) {
            onFailure(ticket, unavailable)
            return
        }
        try {
            val current = engineFactory()
            recognizer = current
            val progress = RecognitionProgress()
            fun fail(failure: SpeechInputFailure) {
                if (recognizer !== current) return
                close()
                onFailure(ticket, failure)
            }
            val currentDeadline = CaptureDeadline(startedAtMillis, timer) {
                fail(SpeechInputFailure("한 번의 듣기 시간이 길어져 조용히 멈췄어요. 이어하기로 다시 시도해 주세요.", cause = RecoveryCause.INPUT_TIMEOUT))
            }
            deadline = currentDeadline
            if (!currentDeadline.start()) return
            fun acceptsCallback(): Boolean = recognizer === current && currentDeadline.accepting()
            fun armTimeout(milliseconds: Long) {
                if (!acceptsCallback()) return
                timeout?.let(timer::remove)
                timeout = object : Runnable {
                    override fun run() {
                        if (timeout !== this || !acceptsCallback()) return
                        fail(if (progress.waitingWithoutSpeech) {
                            SpeechInputFailure("듣기 시간이 지나 조용히 멈췄어요. 이어하기로 다시 시도해 주세요.", "NO_RESPONSE")
                        } else {
                            SpeechInputFailure("음성 인식의 준비 또는 처리가 지연되어 조용히 멈췄어요. 이어하기로 다시 시도해 주세요.", cause = RecoveryCause.INPUT_TIMEOUT)
                        })
                    }
                }.also { timer.postDelayed(it, milliseconds) }
            }
            current.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!acceptsCallback()) return
                    progress.readyForSpeech()
                    armTimeout(12_000L)
                    if (!acceptsCallback()) return
                    onStage(ticket, CaptureStage.LISTENING)
                }
                override fun onBeginningOfSpeech() {
                    if (!acceptsCallback()) return
                    progress.beginningOfSpeech()
                    armTimeout(20_000L)
                }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (!acceptsCallback()) return
                    progress.endOfSpeech()
                    armTimeout(10_000L)
                    if (!acceptsCallback()) return
                    onStage(ticket, CaptureStage.PROCESSING)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    if (acceptsCallback()) fail(failureFor(error, progress))
                }
                override fun onResults(results: Bundle?) {
                    if (!acceptsCallback()) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (text.isBlank() || text.length > 512) {
                        fail(SpeechInputFailure("음성을 유효한 문장으로 확인하지 못해 조용히 멈췄어요. 이어하기로 다시 시도해 주세요."))
                        return
                    }
                    close()
                    onFinal(ticket, text)
                }
            })
            armTimeout(10_000L)
            if (!acceptsCallback()) return
            current.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Supplementary hint only. The on-device factory above is the actual boundary.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (_: SecurityException) {
            close()
            onFailure(ticket, SpeechInputFailure("마이크 권한을 확인한 뒤 다시 선택해 주세요.", "MIC_PERMISSION", RecoveryCause.INPUT_PERMISSION))
        } catch (_: Exception) {
            close()
            onFailure(ticket, SpeechInputFailure("기기 음성 인식을 시작하지 못해 조용히 멈췄어요."))
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        deadline?.cancel()
        deadline = null
        timeout?.let(timer::remove)
        timeout = null
        val old = recognizer
        recognizer = null // Fence callbacks before cancel/destroy can dispatch an error.
        if (old != null) {
            runCatching { old.cancel() }
            runCatching { old.destroy() }
        }
    }

    private fun failureFor(error: Int, progress: RecognitionProgress): SpeechInputFailure = when (error) {
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            if (progress.waitingWithoutSpeech) SpeechInputFailure(
                "듣기 시간이 지나 조용히 멈췄어요. 이어하기로 다시 시도해 주세요.", "NO_RESPONSE")
            else SpeechInputFailure("음성 인식 처리가 끝나지 않아 조용히 멈췄어요.", cause = RecoveryCause.INPUT_TIMEOUT)
        SpeechRecognizer.ERROR_NO_MATCH ->
            SpeechInputFailure("음성을 문장으로 인식하지 못해 조용히 멈췄어요. 이어하기로 다시 시도해 주세요.")
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            SpeechInputFailure("마이크 권한이 없어 조용히 멈췄어요.", "MIC_PERMISSION", RecoveryCause.INPUT_PERMISSION)
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            SpeechInputFailure("기기의 한국어 오프라인 인식 모델을 사용할 수 없어요. 보호자 설정에서 다른 방식을 선택해 주세요.", cause = RecoveryCause.INPUT_LANGUAGE)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            SpeechInputFailure("다른 기능이 음성 인식을 사용 중이에요. 조용히 멈췄어요.", cause = RecoveryCause.INPUT_BUSY)
        else -> SpeechInputFailure("음성 인식을 완료하지 못해 조용히 멈췄어요. 이어하기로 다시 시도해 주세요.")
    }
}
