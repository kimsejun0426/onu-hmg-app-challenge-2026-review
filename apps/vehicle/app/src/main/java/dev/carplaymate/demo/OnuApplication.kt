package dev.carplaymate.demo

import android.app.Application

/** The application, rather than a recreated screen, owns an explicitly started voice session. */
open class OnuApplication : Application() {
    internal open val voiceSessionHost: VoiceSessionHost by lazy { VoiceSessionHost(this) }
}
