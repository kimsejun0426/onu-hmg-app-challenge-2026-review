package dev.carplaymate.demo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo

/** Selects installed vehicle service once; a broken vehicle service is never hidden by fallback. */
class SpeechProviders(private val context: Context) {
    @Suppress("DEPRECATION")
    val usesPleos = runCatching {
        context.packageManager.getPackageInfo(PleosSpeechRuntime.PACKAGE, 0)
    }.isSuccess

    fun input(): SpeechCaptureInput = if (usesPleos) PleosSpeechInput(context) else AndroidOnDeviceSpeechInput(context)
    fun output(onInterrupted: () -> Unit): SpeechOutput =
        ComposedSpeechOutput.create(context, onInterrupted) { focus, interrupted ->
            if (usesPleos) PleosSpeechOutput(context, interrupted, focus)
            else AndroidTtsOutput(context, interrupted, focus)
        }

    fun inputPermissions(): Array<String> = requestable(if (usesPleos)
        listOf(PleosSpeechRuntime.STT_PERMISSION, PleosSpeechRuntime.TTS_PERMISSION)
        else listOf(Manifest.permission.RECORD_AUDIO))
    fun outputPermissions(): Array<String> = requestable(if (usesPleos)
        listOf(PleosSpeechRuntime.TTS_PERMISSION) else emptyList())

    @Suppress("DEPRECATION")
    private fun requestable(permissions: List<String>): Array<String> = permissions.filter { name ->
        context.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED &&
            runCatching {
                context.packageManager.getPermissionInfo(name, 0).protectionLevel and
                    PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
    }.toTypedArray()
}
