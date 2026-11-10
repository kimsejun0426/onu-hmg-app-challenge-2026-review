package dev.carplaymate.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var model: DeveloperViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[DeveloperViewModel::class.java]
        setContent {
            VehicleWindow {
                PlaymateTheme { FamilyScreen(model, onHide = { moveTaskToBack(true) }) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as? OnuApplication)?.voiceSessionHost?.activityResumed(this)
        model.foreground(this)
    }

    // A parked-app restriction may pause the activity without stopping it.
    override fun onPause() {
        (application as? OnuApplication)?.voiceSessionHost?.activityPaused(this)
        if (::model.isInitialized) model.background()
        super.onPause()
    }
}
