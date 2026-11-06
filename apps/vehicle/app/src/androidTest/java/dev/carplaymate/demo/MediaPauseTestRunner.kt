package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Demo-only second component; the normal AndroidJUnitRunner never uses this Application. */
class MediaPauseTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName == "dev.carplaymate.demo.demo")
        check(className == OnuApplication::class.java.name)
        return super.newApplication(cl, MediaPauseTestApplication::class.java.name, context)
    }

    override fun onCreate(arguments: Bundle) {
        check(arguments.getString("familyUiTests") == "true")
        val selected = arguments.getString("class")?.split(',').orEmpty()
        check(selected.isNotEmpty() && selected.all {
            it.substringBefore('#') == MediaPauseResumeServiceTest::class.java.name
        }) { "The fixture Application is only for the explicitly selected media service tests." }
        super.onCreate(arguments)
    }
}

/** All mutable fixture ownership is in androidTest, never a production factory or flag. */
class MediaPauseTestApplication : OnuApplication() {
    internal var fixture: MediaPauseServiceHarness? = null
    internal override val voiceSessionHost: VoiceSessionHost
        get() = checkNotNull(fixture) { "Install the fixture before launching the Activity or Service." }.host
}
