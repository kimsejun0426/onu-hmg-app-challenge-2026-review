package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow

/** Playback progress reported by the output adapter; not a physical audio measurement. */
enum class OutputStage { PREPARING, STARTED }

/** UI-only attempt; the previous snapshot and durable checkpoint remain intact. */
enum class NewAdventureStart { PREPARING, STOPPED, FAILED }

data class DeveloperUiState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val quiet: Boolean = true,
    val simulation: Boolean = false,
    val voiceInput: Boolean = false,
    val interactionConfigured: Boolean = false,
    val voiceInputProblem: String? = null,
    val microphoneListening: Boolean = false,
    val captureStage: CaptureStage? = null,
    val outputStage: OutputStage? = null,
    /** Current valid spoken part only; false for cues, preparation, reading and unsupported ports. */
    val narrationActive: Boolean = false,
    val inputStatus: String = "마이크 꺼짐 · 편하게 쉬어도 좋아요.",
    val snapshot: Snapshot? = null,
    val newAdventureStart: NewAdventureStart? = null,
    /** App-authored dialogue for the current turn; never a child's captured utterance. */
    val authoredDialogue: String = "",
    val message: String = "함께 떠날 준비가 되었어요.",
    val onboarded: Boolean = false,
    val firstNickname: String = "",
    val secondNickname: String = "",
    val twoPlayers: Boolean = false,
    val useLocal: Boolean = true,
    val hasSavedSession: Boolean = false,
    val speechReady: Boolean = false,
    /** Current capability, separate from the last interruption. Checking it never resumes playback. */
    val speechProblem: RecoveryCause? = null,
    val speechStatus: String = "기기 음성을 확인하고 있어요.",
    val failure: Boolean = false,
    /** Last interrupted attempt, in UI memory only; not cleared by settings or capability checks. */
    val recoveryCause: RecoveryCause? = null,
    val completedAdventures: Int = 0,
    val adventures: List<AdventureCard> = emptyList(),
    val selectedPackId: String = DEFAULT_ADVENTURE_ID,
    val journal: List<FamilyJournalEntry> = emptyList(),
    val savedAdventures: List<SavedAdventure> = emptyList(),
    val children: List<ChildProfile> = emptyList(),
    val interestSetupNeeded: Boolean = false,
    val recommendations: List<AdventureRecommendation> = emptyList(),
    val adventureFeedback: List<AdventureFeedbackEntry> = emptyList(),
    val feedbackSaving: Boolean = false,
    val familyDeletionPending: Boolean = false,
    val localStorageProblem: Boolean = false,
    val vehicleAccess: VehicleAccessState = VehicleAccessState.UNKNOWN,
    val vehicleUx: VehicleUxSnapshot = VehicleUxSnapshot(vehicleAccess),
    val fullUiAllowed: Boolean = vehicleAccess.allowsInteraction,
    val voiceSessionAllowed: Boolean = false,
    val minimalUiRequired: Boolean = false,
)

/** UI facade. A shared host may retain the session after this ViewModel is cleared. */
class DeveloperViewModel : AndroidViewModel {
    private val controller: VoiceSessionController
    private val ownsController: Boolean
    private val host: VoiceSessionHost?

    internal constructor(
        application: Application,
        initialClient: GameClient?,
        beforeLocalLoad: suspend () -> Unit = {},
        deletionCheckpoint: suspend (FamilyDeletionStep) -> Unit = {},
        outputFactory: ((() -> Unit) -> SpeechOutput)? = null,
        vehicleAccessProvider: VehicleAccessProvider = VehicleAccessProviders.create(application),
        inputFactory: (() -> SpeechCaptureInput)? = null,
        captureSignalOutput: CaptureSignalOutput? = null,
        inputFocusFactory: ((() -> Unit) -> CaptureFocus)? = null,
    ) : super(application) {
        ownsController = true
        host = null
        controller = VoiceSessionController(
            application = application,
            initialClient = initialClient,
            beforeLocalLoad = beforeLocalLoad,
            deletionCheckpoint = deletionCheckpoint,
            outputFactory = outputFactory,
            vehicleAccessProvider = vehicleAccessProvider,
            inputFactory = inputFactory,
            captureSignalOutput = captureSignalOutput,
            inputFocusFactory = inputFocusFactory,
            sessionScope = viewModelScope,
        )
    }

    constructor(application: Application) : super(application) {
        host = (application as? OnuApplication)?.voiceSessionHost
        ownsController = host == null
        controller = host?.controller ?: VoiceSessionController(application, initialClient = null,
            sessionScope = viewModelScope)
    }

    internal constructor(
        application: Application,
        controller: VoiceSessionController,
        ownsController: Boolean,
    ) : super(application) {
        this.controller = controller
        this.ownsController = ownsController
        host = null
    }

    val ui: StateFlow<DeveloperUiState> get() = controller.ui
    val speechStatus: StateFlow<String> get() = controller.speechStatus

    fun saveFamily(first: String, second: String?): Boolean {
        host?.cancelExplicitAction()
        return controller.saveFamily(first, second)
    }
    fun saveInterests(first: Set<String>, second: Set<String> = emptySet()): Boolean {
        host?.cancelExplicitAction()
        return controller.saveInterests(first, second)
    }
    fun skipInterests(): Boolean {
        host?.cancelExplicitAction()
        return controller.skipInterests()
    }
    fun setAdventureFeedback(sessionId: String, childId: String, feedback: AdventureFeedback?) =
        controller.setAdventureFeedback(sessionId, childId, feedback)
    fun startFamilyAdventure() = explicitAction { controller.startFamilyAdventure() }
    fun startFamilyAdventure(packId: String) = explicitAction { controller.startFamilyAdventure(packId) }
    fun selectAdventure(packId: String): Boolean {
        host?.cancelExplicitAction()
        return controller.selectAdventure(packId)
    }
    fun resumeAdventure(sessionId: String) = explicitAction { controller.resumeAdventure(sessionId) }
    fun retryLocalStorage() {
        host?.cancelExplicitAction()
        controller.retryLocalStorage()
    }
    fun useLocalBackend() {
        host?.cancelExplicitAction()
        controller.useLocalBackend()
    }
    fun forgetFamily() {
        host?.cancelExplicitAction()
        controller.forgetFamily()
    }
    fun retrySpeech() {
        host?.cancelExplicitAction()
        controller.retrySpeech()
    }
    fun chooseAction(label: String) = controller.chooseAction(label)
    fun configure(baseUrl: String, token: String) {
        host?.cancelExplicitAction()
        controller.configure(baseUrl, token)
    }
    fun useSimulation(value: Boolean) {
        host?.cancelExplicitAction()
        controller.useSimulation(value)
    }
    fun voiceInputSupported(): Boolean = controller.voiceInputSupported()
    fun requiredVoicePermissions(): Array<String> =
        host?.requiredVoicePermissions() ?: controller.requiredVoicePermissions()
    fun requiredOutputPermissions(): Array<String> = controller.requiredOutputPermissions()
    fun useVoiceInput(enabled: Boolean) {
        host?.cancelExplicitAction()
        controller.useVoiceInput(enabled)
    }
    fun microphonePermissionResult(granted: Boolean) {
        host?.cancelExplicitAction()
        controller.microphonePermissionResult(granted)
    }
    fun start(first: String, second: String?) = explicitAction { controller.start(first, second) }
    fun restore() {
        host?.cancelExplicitAction()
        controller.restore()
    }
    fun resume() = explicitAction { controller.resume() }
    fun pause(reason: String = "QUIET_REQUEST") {
        if (host != null) host.pause(reason) else controller.pause(reason)
    }
    /** Leaving play explicitly stops work before dropping its transient start notice. */
    fun leaveAdventureScreen() {
        pause()
        controller.dismissNewAdventureStart()
    }
    fun submit(text: String) = controller.submit(text)
    fun simulatePlaybackCompletion() = controller.simulatePlaybackCompletion()
    fun foreground(context: Context) = controller.foreground(context)
    fun background() = controller.background()

    private fun explicitAction(action: () -> Unit) {
        // Revoke the other audio owner before an older adventure can begin any input/output.
        (getApplication<Application>() as? OnuApplication)?.letterSessionHost?.stop()
        val current = controller.ui.value
        if (host != null && current.voiceInput && !current.simulation)
            host.runExplicitVoiceAction(action)
        else action()
    }

    override fun onCleared() {
        try {
            if (ownsController) controller.close()
        } finally {
            super.onCleared()
        }
    }
}
