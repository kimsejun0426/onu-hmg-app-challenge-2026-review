package dev.carplaymate.demo

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Session owner independent of Activity/ViewModel lifetime; entry points remain main-thread calls. */
internal class VoiceSessionController(
    private val application: Application,
    initialClient: GameClient?,
    private val beforeLocalLoad: suspend () -> Unit = {},
    private val deletionCheckpoint: suspend (FamilyDeletionStep) -> Unit = {},
    private val outputFactory: ((() -> Unit) -> SpeechOutput)? = null,
    private val vehicleAccessProvider: VehicleAccessProvider =
        VehicleAccessProviders.create(application),
    inputFactory: (() -> SpeechCaptureInput)? = null,
    captureSignalOutput: CaptureSignalOutput? = null,
    inputFocusFactory: ((() -> Unit) -> CaptureFocus)? = null,
    private val requireForegroundHostForVoice: Boolean = false,
    sessionScope: CoroutineScope,
) {
    // Own child jobs, never the caller's scope; the host controls this lifetime.
    private val sessionJob = SupervisorJob(sessionScope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(sessionScope.coroutineContext + sessionJob)
    private var closed = false

    private val localCheckpoint = LocalCheckpoint(application, "recovery_local_v1")
    private val remoteCheckpoint = LocalCheckpoint(application)
    private val family = FamilyPreferences(application)
    private val interactionPreferences = InteractionPreferences(application)
    private val deletionJournal = FamilyDeletionJournal(application)
    private var familyDeletionPending = deletionJournal.pending
    private var deletionRunning = false
    private val initialProfile = if (familyDeletionPending) null else family.profile
    private val initialInteraction =
        if (familyDeletionPending) InteractionPreference() else interactionPreferences.read()
    private val localClient = LocalGameClient(application)
    private val operations = mutableSetOf<Job>()

    private fun checkpointFor(client: GameClient?) =
        if (client === localClient) localCheckpoint else remoteCheckpoint

    private val checkpoint
        get() = checkpointFor(api)

    private val gate = OutputGate()
    private val input = MockSpeechInput()
    private val providers = SpeechProviders(application)
    private val deviceInput = inputFactory?.invoke() ?: providers.input()
    private val captureSignals = CaptureSignals(captureSignalOutput ?: AndroidCaptureSignals())
    private val captureGate = CaptureGate()
    private var api: GameClient? = initialClient ?: localClient
    private var pauseJob: Job? = null
    private var activePlayback: String? = null
    private var localLoadEpoch = 0L
    private var localLoadPending = false
    private var localLoadJob: Job? = null
    private var vehicleLifetime = 0L
    private var vehicleSnapshot = vehicleAccessProvider.initialSnapshot
    private val vehicleAccessState get() = vehicleSnapshot.access
    private var vehicleObserving = false
    private var observedDisplayId: Int? = null
    private var voiceHost = VoiceHostState(uiVisible = !requireForegroundHostForVoice,
        voiceModeSelected = initialInteraction.mode == InteractionMode.VOICE)
    private val initialPolicy = VehicleInteractionPolicy.evaluate(vehicleSnapshot, voiceHost)
    private val interactionPolicy: VehicleInteractionPolicy
        get() = VehicleInteractionPolicy.evaluate(vehicleSnapshot,
            voiceHost.copy(voiceModeSelected = mutableUi.value.voiceInput))
    private val fullUiAllowed get() = !closed && voiceHost.uiVisible && interactionPolicy.allowsFullUi
    private val sessionAllowed: Boolean
        get() = !closed && if (mutableUi.value.voiceInput) interactionPolicy.allowsVoiceSession &&
            (!requireForegroundHostForVoice || (voiceHost.foregroundServiceRunning &&
                voiceHost.explicitlyStartedSession))
        else fullUiAllowed
    private val mutableUi =
        MutableStateFlow(
            DeveloperUiState(
                connected = true,
                busy = true,
                voiceInput = initialInteraction.mode == InteractionMode.VOICE,
                interactionConfigured = initialInteraction.configured,
                voiceInputProblem = deviceInput.unavailableReason(),
                simulation = initialInteraction.mode == InteractionMode.READING,
                onboarded = initialProfile != null,
                firstNickname = initialProfile?.first.orEmpty(),
                secondNickname = initialProfile?.second.orEmpty(),
                twoPlayers = initialProfile?.second != null,
                children = initialProfile?.children.orEmpty(),
                interestSetupNeeded =
                    initialProfile != null &&
                        (initialProfile.children.isEmpty() ||
                            initialProfile.children.any { !it.interestsConfigured }),
                useLocal = initialClient == null,
                familyDeletionPending = familyDeletionPending,
                localStorageProblem =
                    !familyDeletionPending &&
                        (family.readIssue != null || initialInteraction.unavailable),
                vehicleAccess = vehicleAccessState,
                vehicleUx = vehicleSnapshot,
                fullUiAllowed = initialPolicy.allowsFullUi,
                voiceSessionAllowed = initialPolicy.allowsVoiceSession && !requireForegroundHostForVoice,
                minimalUiRequired = initialPolicy.requiresMinimalUi,
            )
        )
    val ui: StateFlow<DeveloperUiState> = mutableUi
    private val speech =
        outputFactory?.invoke { pause("AUDIO_FOCUS") } ?: providers.output { pause("AUDIO_FOCUS") }
    private val inputFocus = inputFocusFactory?.invoke { pause("AUDIO_FOCUS") }
        ?: AndroidCaptureFocus(application) { pause("AUDIO_FOCUS") }
    val speechStatus = speech.status

    init {
        if (!familyDeletionPending && !checkpoint.forceQuietOnRestore()) {
            mutableUi.value = mutableUi.value.copy(message = "로컬 복구 기록을 저장할 수 없어 재생을 차단했어요.")
        }
        sessionScope.launch {
            speech.readyState.collect {
                if (closed) return@collect
                mutableUi.value = mutableUi.value.copy(speechReady = it,
                    speechProblem = speech.availabilityFailure?.cause)
            }
        }
        sessionScope.launch {
            speech.status.collect {
                if (closed) return@collect
                mutableUi.value = mutableUi.value.copy(speechStatus = it,
                    speechProblem = speech.availabilityFailure?.cause)
            }
        }
        speech.narrationState?.let { states ->
            sessionScope.launch { states.collect { updateNarrationState(it) } }
        }
        if (familyDeletionPending) forgetFamily()
        else if (initialClient == null) loadLocalProgress()
        else mutableUi.value = mutableUi.value.copy(busy = false)
    }

    private fun launchOperation(block: suspend CoroutineScope.() -> Unit): Job =
        sessionScope.launch(block = block).also { job ->
            operations += job
            job.invokeOnCompletion { operations.remove(job) }
        }

    private fun loadLocalProgress() {
        localLoadJob?.cancel()
        val epoch = ++localLoadEpoch
        localLoadPending = true
        fun currentLoad() = !closed && epoch == localLoadEpoch && api === localClient
        mutableUi.value = mutableUi.value.copy(busy = true)
        localLoadJob = launchOperation {
            try {
                withContext(Dispatchers.IO) {
                    if (!localCheckpoint.forceQuietOnRestore())
                        throw ApiException(500, "LOCAL_CHECKPOINT_UNAVAILABLE")
                    localCheckpoint.sessionId
                    localCheckpoint.pendingCommandIds
                    if (!family.migrate()) throw ApiException(500, "LOCAL_PROFILE_UNAVAILABLE")
                }
                beforeLocalLoad()
                if (!currentLoad()) return@launchOperation
                val adventures = localClient.adventures()
                if (!currentLoad()) return@launchOperation
                publishProfile(family.profile)
                // The catalog remains available if an old store needs explicit deletion.
                mutableUi.value = mutableUi.value.copy(adventures = adventures)
                val overview = localClient.restoreAndOverview()
                var saved = overview.snapshot
                val journal = overview.journal
                val savedAdventures = overview.savedAdventures
                val feedback = overview.feedback
                if (!currentLoad()) return@launchOperation
                val preferred = interactionPreferences.read()
                if (preferred.unavailable) throw ApiException(500, "LOCAL_INTERACTION_UNAVAILABLE")
                // A concurrent explicit pause can have published a newer quiet snapshot.
                val visible = mutableUi.value.snapshot
                if (
                    saved != null &&
                        visible?.sessionId == saved.sessionId &&
                        visible.version > saved.version
                )
                    saved = visible
                if (saved != null) checkpointSaved(localCheckpoint.session(saved.sessionId))
                checkpointSaved(localCheckpoint.clearPending())
                localLoadPending = false
                mutableUi.value =
                    mutableUi.value.copy(
                        snapshot = saved,
                        newAdventureStart = null,
                        authoredDialogue = dialogueFor(saved),
                        busy = pauseJob?.isActive == true,
                        quiet = true,
                        voiceInput = preferred.mode == InteractionMode.VOICE,
                        interactionConfigured = preferred.configured,
                        voiceInputProblem = deviceInput.unavailableReason(),
                        simulation = preferred.mode == InteractionMode.READING,
                        inputStatus = restoredModeStatus(preferred.mode),
                        localStorageProblem = false,
                        failure = false,
                        completedAdventures = journal.size,
                        adventures = adventures,
                        journal = journal,
                        savedAdventures = savedAdventures,
                        adventureFeedback = feedback,
                        selectedPackId = saved?.game?.optString("pack_id") ?: DEFAULT_ADVENTURE_ID,
                        hasSavedSession = saved != null && saved.status != "ENDED",
                        message =
                            if (saved != null) "지난 모험을 기억하고 있어요. 준비되면 이어가요." else "함께 떠날 준비가 되었어요.",
                    )
                refreshRecommendations()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentLoad()) {
                    localLoadPending = false
                    handleFailure(error, gate.generation)
                }
            } finally {
                if (epoch == localLoadEpoch) localLoadPending = false
            }
        }
    }

    private fun invalidateLocalLoad() {
        localLoadEpoch += 1
        localLoadPending = false
        localLoadJob?.cancel()
        localLoadJob = null
    }

    fun saveFamily(first: String, second: String?): Boolean {
        if (closed) return false
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return false
        val problem = FamilyProfile.problem(first, second, existing = family.profile)
        if (problem != null) {
            mutableUi.value = mutableUi.value.copy(message = problem, failure = true)
            return false
        }
        val profile = FamilyProfile(first.trim(), second?.trim())
        if (!family.save(profile)) {
            handleFailure(ApiException(500, "LOCAL_PROFILE_UNAVAILABLE"), gate.generation)
            return false
        }
        publishProfile(family.profile)
        mutableUi.value =
            mutableUi.value.copy(failure = false, message = "가족 정보를 저장했어요. 새 별명은 다음 모험부터 사용해요.")
        return true
    }

    fun saveInterests(first: Set<String>, second: Set<String> = emptySet()): Boolean {
        if (closed) return false
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return false
        if (
            !InterestOptions.ids.containsAll(first + second) ||
                (!mutableUi.value.twoPlayers && second.isNotEmpty())
        ) {
            mutableUi.value =
                mutableUi.value.copy(failure = true, message = "가족 정보와 관심사 선택을 확인해 주세요.")
            return false
        }
        if (!family.saveInterests(first, second)) {
            handleFailure(ApiException(500, "LOCAL_PROFILE_UNAVAILABLE"), gate.generation)
            return false
        }
        publishProfile(family.profile)
        mutableUi.value =
            mutableUi.value.copy(failure = false, message = "직접 고른 관심사를 저장했어요. 다음 추천에 반영해요.")
        return true
    }

    fun skipInterests(): Boolean {
        if (closed) return false
        val current = family.profile ?: return false
        return saveInterests(
            if (current.firstInterestsConfigured) current.firstInterests else emptySet(),
            if (current.secondInterestsConfigured) current.secondInterests else emptySet(),
        )
    }

    private fun publishProfile(profile: FamilyProfile?) {
        val children = profile?.children.orEmpty()
        mutableUi.value =
            mutableUi.value.copy(
                onboarded = profile != null,
                firstNickname = profile?.first.orEmpty(),
                secondNickname = profile?.second.orEmpty(),
                twoPlayers = profile?.second != null,
                children = children,
                interestSetupNeeded =
                    profile != null &&
                        (children.isEmpty() || children.any { !it.interestsConfigured }),
            )
        refreshRecommendations()
    }

    private fun refreshRecommendations() {
        val state = mutableUi.value
        mutableUi.value =
            state.copy(
                recommendations =
                    InterestMatching.recommend(
                        state.adventures,
                        state.children,
                        state.journal.map {
                            CompletedAdventure(it.packId, it.completedAtEpochMillis)
                        },
                        state.adventureFeedback,
                    )
            )
    }

    fun setAdventureFeedback(sessionId: String, childId: String, feedback: AdventureFeedback?) {
        if (closed) return
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return
        if (api !== localClient || mutableUi.value.feedbackSaving || mutableUi.value.busy) return
        mutableUi.value = mutableUi.value.copy(feedbackSaving = true)
        launchOperation {
            try {
                localClient.setFeedback(sessionId, childId, feedback)
                val entries = localClient.feedback()
                if (familyDeletionPending) return@launchOperation
                if (api !== localClient) {
                    mutableUi.value = mutableUi.value.copy(feedbackSaving = false)
                    return@launchOperation
                }
                mutableUi.value =
                    mutableUi.value.copy(
                        adventureFeedback = entries,
                        feedbackSaving = false,
                        failure = false,
                        message =
                            if (feedback == null) "모험 반응을 지웠어요." else "직접 알려준 반응을 다음 추천에 반영할게요.",
                    )
                refreshRecommendations()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (familyDeletionPending) return@launchOperation
                mutableUi.value = mutableUi.value.copy(feedbackSaving = false)
                if (error is ApiException && error.code.startsWith("LOCAL_"))
                    handleFailure(error, gate.generation)
                else
                    mutableUi.value =
                        mutableUi.value.copy(
                            failure = true,
                            message = "모험 반응을 저장하지 못했어요. 완료한 모험의 참가자를 확인해 주세요.",
                        )
            }
        }
    }

    fun startFamilyAdventure() {
        if (closed) return
        startFamilyAdventure(mutableUi.value.selectedPackId)
    }

    fun startFamilyAdventure(packId: String) {
        if (closed) return
        val state = mutableUi.value
        if (!state.onboarded) return
        startPack(state.firstNickname, state.secondNickname.takeIf { state.twoPlayers }, packId)
    }

    fun selectAdventure(packId: String): Boolean {
        if (closed) return false
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return false
        val state = mutableUi.value
        if (
            state.busy ||
                pauseJob?.isActive == true ||
                state.adventures.none { it.packId == packId }
        )
            return false
        if (!state.useLocal && packId != DEFAULT_ADVENTURE_ID) {
            mutableUi.value =
                state.copy(failure = true, message = "이 모험은 기기 안에서 진행해요. 기기 내 모험으로 전환해 주세요.")
            return false
        }
        if (!state.quiet) pause("USER")
        mutableUi.value = mutableUi.value.copy(selectedPackId = packId, failure = false, newAdventureStart = null)
        return true
    }

    /** Explicitly resumes the selected pack's saved session; switching never replays a choice. */
    fun resumeAdventure(sessionId: String) {
        if (closed) return
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return
        if (api !== localClient || mutableUi.value.busy || pauseJob?.isActive == true) return
        if (!sessionAllowed || !ensureVoiceInputAvailable()) return
        val previous = mutableUi.value.snapshot
        mutableUi.value = mutableUi.value.copy(newAdventureStart = null)
        val generation = localStop()
        mutableUi.value = mutableUi.value.copy(busy = true, failure = false)
        launchOperation {
            try {
                if (
                    previous != null &&
                        previous.sessionId != sessionId &&
                        previous.status != "ENDED"
                ) {
                    ensurePaused(localClient, localClient.snapshot(previous.sessionId))
                }
                if (!gate.isCurrent(generation)) return@launchOperation
                val target = localClient.snapshot(sessionId)
                if (!gate.isCurrent(generation)) return@launchOperation
                if (target.status == "ENDED") {
                    mutableUi.value =
                        mutableUi.value.copy(
                            busy = false,
                            failure = true,
                            message = "이미 마친 모험이에요. 기억에서 함께한 이야기를 보거나 새 모험을 시작해요.",
                        )
                    return@launchOperation
                }
                checkpointSaved(localCheckpoint.clearPending())
                checkpointSaved(localCheckpoint.session(sessionId))
                val paused = ensurePaused(localClient, target)
                if (!gate.isCurrent(generation)) return@launchOperation
                localClient.restore(
                    sessionId
                ) // Persist which pack is selected, without granting output.
                if (!gate.isCurrent(generation)) return@launchOperation
                val resumed = send(localClient, paused, "session.resume", JSONObject())
                if (!gate.isCurrent(generation)) return@launchOperation
                checkpointSaved(localCheckpoint.clearPending())
                mutableUi.value =
                    mutableUi.value.copy(selectedPackId = resumed.game.getString("pack_id"))
                permitAndAccept(resumed)
            } catch (error: Exception) {
                handleFailure(error, generation)
            }
        }
    }

    fun retryLocalStorage() {
        if (closed) return
        if (familyDeletionPending || !fullUiAllowed || mutableUi.value.busy)
            return
        localStop()
        api = localClient
        loadLocalProgress()
    }

    fun useLocalBackend() {
        if (closed) return
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return
        val previousClient = api
        val previous = mutableUi.value.snapshot
        invalidateLocalLoad()
        localStop()
        pauseJob?.cancel()
        api = localClient
        mutableUi.value =
            mutableUi.value.copy(
                connected = true,
                useLocal = true,
                snapshot = null,
                authoredDialogue = "",
                failure = false,
                hasSavedSession = false,
            )
        if (
            previousClient != null && previousClient !== localClient && previous?.status == "ACTIVE"
        ) {
            launchOperation {
                try {
                    ensurePaused(previousClient, previous)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
        loadLocalProgress()
    }

    /** Called only after the guardian confirms deletion in settings. */
    fun forgetFamily() {
        if (closed) return
        if (!familyDeletionPending && !fullUiAllowed) return
        if (
            deletionRunning ||
                (!familyDeletionPending && (mutableUi.value.busy || pauseJob?.isActive == true))
        )
            return
        if (!deletionJournal.begin()) {
            mutableUi.value =
                mutableUi.value.copy(failure = true, message = "삭제 준비를 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.")
            return
        }
        familyDeletionPending = true
        deletionRunning = true
        invalidateLocalLoad()
        localStop()
        val pending = operations.filter { it.isActive }.toList()
        api = localClient
        // No previous profile or snapshot is exposed while erasure is pending, including failures.
        mutableUi.value =
            DeveloperUiState(
                connected = true,
                busy = true,
                familyDeletionPending = true,
                vehicleAccess = vehicleAccessState,
                vehicleUx = vehicleSnapshot,
                fullUiAllowed = interactionPolicy.allowsFullUi,
                speechReady = speech.readyState.value,
                speechStatus = speech.status.value,
                adventures = mutableUi.value.adventures,
                message = "가족 정보와 모험 기록을 지우고 있어요.",
            )
        launchOperation {
            try {
                pending.forEach { it.join() }
                deletionCheckpoint(FamilyDeletionStep.BEFORE_GAME)
                localClient.deleteAll()
                deletionCheckpoint(FamilyDeletionStep.AFTER_GAME)
                check(
                    withContext(Dispatchers.IO) {
                        listOf(localCheckpoint.clear(), remoteCheckpoint.clear()).all { it }
                    }
                )
                deletionCheckpoint(FamilyDeletionStep.AFTER_CHECKPOINTS)
                check(withContext(Dispatchers.IO) { family.clear() })
                deletionCheckpoint(FamilyDeletionStep.AFTER_PROFILE)
                check(withContext(Dispatchers.IO) { deletionJournal.complete() })
                familyDeletionPending = false
                deletionRunning = false
                mutableUi.value =
                    DeveloperUiState(
                        connected = true,
                        speechReady = speech.readyState.value,
                        speechStatus = speech.status.value,
                        voiceInputProblem = deviceInput.unavailableReason(),
                        vehicleAccess = vehicleAccessState,
                        vehicleUx = vehicleSnapshot,
                        fullUiAllowed = interactionPolicy.allowsFullUi,
                        adventures = mutableUi.value.adventures,
                        message = "이 기기의 가족 정보와 모험 기록을 지웠어요.",
                    )
                if (mutableUi.value.adventures.isEmpty()) loadLocalProgress()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                deletionRunning = false
                mutableUi.value =
                    DeveloperUiState(
                        connected = true,
                        familyDeletionPending = true,
                        vehicleAccess = vehicleAccessState,
                        vehicleUx = vehicleSnapshot,
                        fullUiAllowed = interactionPolicy.allowsFullUi,
                        speechReady = speech.readyState.value,
                        speechStatus = speech.status.value,
                        adventures = mutableUi.value.adventures,
                        failure = true,
                        message = "삭제를 마치지 못했어요. 다시 삭제하면 남은 기록까지 지워요.",
                    )
            }
        }
    }

    fun retrySpeech() {
        if (closed) return
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return
        pause()
        speech.refresh()
        refreshVoiceAvailability()
        mutableUi.value = mutableUi.value.copy(failure = false,
            speechReady = speech.readyState.value, speechProblem = speech.availabilityFailure?.cause,
            message = "기기 음성을 다시 확인하고 있어요. 준비되면 직접 이어가 주세요.")
    }

    /** Guardian/passenger alternative input. Never disguises a tap as microphone recognition. */
    fun chooseAction(label: String) {
        if (closed) return
        if (mutableUi.value.localStorageProblem || !fullUiAllowed) return
        val state = mutableUi.value
        val snapshot = state.snapshot ?: return
        if (state.busy || state.quiet || snapshot.phase != "LISTENING") return
        val actions = snapshot.presentation.optJSONArray("actions") ?: return
        if (
            label !in setOf("다시", "힌트") &&
                (0 until actions.length()).none {
                    actions.getJSONObject(it).optString("label") == label
                }
        )
            return
        val players = snapshot.game.getJSONArray("players")
        val actor =
            (0 until players.length())
                .map { players.getJSONObject(it) }
                .firstOrNull {
                    it.optString("player_id") == snapshot.game.optString("expected_player_id")
                } ?: return
        val ticket = captureGate.active ?: return
        submitCaptured(
            ticket,
            FinalUtterance(
                UUID.randomUUID().toString(),
                "${actor.getString("nickname")}, $label",
                ticket.context.listenWindowId,
            ),
            "touch",
        )
    }

    fun configure(baseUrl: String, token: String) {
        if (closed) return
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return
        val configured =
            try {
                ApiClient(baseUrl, token)
            } catch (_: Exception) {
                mutableUi.value =
                    mutableUi.value.copy(failure = true, message = "서버 주소와 개발 토큰을 확인해 주세요.")
                return
            }
        invalidateLocalLoad()
        localStop()
        pauseJob?.cancel()
        api = configured
        mutableUi.value =
            mutableUi.value.copy(
                connected = true,
                useLocal = false,
                snapshot = null,
                authoredDialogue = "",
                hasSavedSession = remoteCheckpoint.sessionId != null,
                failure = false,
                message = "외부 서버에 연결했어요. 새 모험 또는 이어하기를 선택해 주세요.",
            )
    }

    fun useSimulation(value: Boolean) {
        if (closed) return
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return
        // An explicit re-selection can recover from a failed attempt to switch modes.
        // It must pass through the same persistence and capability checks as a new choice.
        if (mutableUi.value.simulation == value && mutableUi.value.interactionConfigured &&
            !mutableUi.value.failure) return
        selectInteractionMode(
            if (value) InteractionMode.READING
            else if (mutableUi.value.voiceInput) InteractionMode.VOICE
            else InteractionMode.LISTEN_AND_SELECT,
        )
    }

    fun voiceInputSupported(): Boolean {
        if (closed) return false
        if (mutableUi.value.localStorageProblem || !fullUiAllowed)
            return false
        val problem = deviceInput.unavailableReason(checkPermission = false)
        refreshVoiceAvailability()
        if (problem != null) mutableUi.value = mutableUi.value.copy(inputStatus = problem)
        return problem == null
    }

    /** Capability inspection only; neither a recognition request nor a mode change. */
    private fun refreshVoiceAvailability() {
        mutableUi.value = mutableUi.value.copy(voiceInputProblem = deviceInput.unavailableReason())
    }

    fun requiredVoicePermissions(): Array<String> = providers.inputPermissions()

    fun requiredOutputPermissions(): Array<String> = providers.outputPermissions()

    /** The requestable-permission list is not proof that all SDK grants still exist. */
    internal fun voiceSessionPermissionsGranted(): Boolean {
        fun granted(permission: String) =
            application.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.RECORD_AUDIO) && (!providers.usesPleos ||
            (granted(PleosSpeechRuntime.STT_PERMISSION) && granted(PleosSpeechRuntime.TTS_PERMISSION)))
    }

    /** Selection alone never starts recording; a new adventure or explicit resume is required. */
    fun useVoiceInput(enabled: Boolean) {
        if (closed) return
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return
        selectInteractionMode(if (enabled) InteractionMode.VOICE else InteractionMode.LISTEN_AND_SELECT)
    }

    private fun restoredModeStatus(mode: InteractionMode): String = when (mode) {
        InteractionMode.VOICE -> "음성으로 함께 · 이어하기 전까지 마이크는 꺼져 있어요."
        InteractionMode.READING -> "읽으며 체험 · 마이크 꺼짐"
        InteractionMode.LISTEN_AND_SELECT -> "듣고 선택하기 · 마이크 꺼짐"
    }

    private fun selectInteractionMode(mode: InteractionMode) {
        pause()
        if (!interactionPreferences.save(mode)) {
            mutableUi.value = mutableUi.value.copy(
                failure = true,
                localStorageProblem = mutableUi.value.localStorageProblem || interactionPreferences.read().unavailable,
                message = "함께할 방식을 저장하지 못했어요. 이전 설정을 유지하고 잠시 쉬어요.",
                inputStatus = "마이크 꺼짐 · 방식 저장을 다시 시도해 주세요.",
            )
            return
        }
        val problem = if (mode == InteractionMode.VOICE) deviceInput.unavailableReason() else null
        mutableUi.value = mutableUi.value.copy(
            voiceInput = mode == InteractionMode.VOICE,
            interactionConfigured = true,
            voiceInputProblem = deviceInput.unavailableReason(),
            simulation = mode == InteractionMode.READING,
            inputStatus = problem ?: restoredModeStatus(mode),
            message = if (problem == null) "함께할 방식을 기억했어요. 새 모험이나 이어하기로 시작해요."
                else "음성 방식을 기억했지만 지금은 시작할 수 없어요. $problem",
            failure = problem != null,
        )
    }

    /** Preference survives unavailable hardware; only an explicit activation checks capability. */
    private fun ensureVoiceInputAvailable(): Boolean {
        if (!mutableUi.value.voiceInput) return true
        val problem = deviceInput.unavailableFailure()
        mutableUi.value = mutableUi.value.copy(voiceInputProblem = problem?.message)
        if (problem == null) return true
        val cause = problem.cause ?: RecoveryCause.INPUT_UNAVAILABLE
        pause(problem.pauseReason)
        mutableUi.value = mutableUi.value.copy(
            failure = true,
            recoveryCause = cause,
            inputStatus = cause.notice().detail,
            message = cause.notice().detail,
        )
        return false
    }

    fun microphonePermissionResult(granted: Boolean) {
        if (closed) return
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return
        if (granted) useVoiceInput(true) else pause("MIC_PERMISSION")
        speech.refresh()
        refreshVoiceAvailability()
        if (!granted)
            mutableUi.value =
                mutableUi.value.copy(
                    failure = true,
                    recoveryCause = RecoveryCause.INPUT_PERMISSION,
                    inputStatus = "음성 권한을 받지 못했어요. 이전 이용 방식은 유지하며 마이크는 꺼져 있어요.",
                    message = "음성 권한을 확인하거나 설정에서 다른 함께할 방식을 직접 골라 주세요.",
                )
    }

    fun start(first: String, second: String?) {
        if (closed) return
        startPack(first, second, mutableUi.value.selectedPackId)
    }

    private fun startPack(first: String, second: String?, packId: String) {
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return
        val client = api ?: return
        if (mutableUi.value.busy || pauseJob?.isActive == true) return
        if (!sessionAllowed || !ensureVoiceInputAvailable()) return
        if (
            (client !== localClient && packId != DEFAULT_ADVENTURE_ID) ||
                (client === localClient && mutableUi.value.adventures.none { it.packId == packId })
        ) {
            mutableUi.value =
                mutableUi.value.copy(
                    failure = true,
                    message = "선택한 모험을 사용할 수 없어요. 모험 목록을 다시 확인해 주세요.",
                )
            return
        }
        val problem = FamilyProfile.problem(first, second, existing = family.profile)
        if (problem != null) {
            mutableUi.value = mutableUi.value.copy(message = problem, failure = true)
            return
        }
        val generation = localStop()
        mutableUi.value =
            mutableUi.value.copy(busy = true, failure = false, selectedPackId = packId,
                newAdventureStart = NewAdventureStart.PREPARING)
        val saved = checkpointFor(client)
        val chosenNames = listOfNotNull(first.trim(), second?.trim())
        val children = mutableUi.value.children
        val profileIds =
            if (children.map { it.nickname } == chosenNames)
                children.mapIndexed { index, child -> "p${index + 1}" to child.id }.toMap()
            else emptyMap()
        launchOperation {
            try {
                saved.sessionId?.let { old ->
                    try {
                        val existing = client.snapshot(old)
                        if (existing.status != "ENDED") ensurePaused(client, existing)
                    } catch (e: ApiException) {
                        // A reset development DB or a different family must not block a fresh
                        // session.
                        if (e.status != 404) throw e
                    }
                }
                if (!gate.isCurrent(generation)) return@launchOperation
                val next =
                    if (client === localClient)
                        localClient.createFamilyAdventure(
                            playersBody(first, second),
                            packId,
                            profileIds,
                        )
                    else client.createAdventure(playersBody(first, second), packId)
                if (!gate.isCurrent(generation)) {
                    // A stop/configuration change during POST must not activate this late session.
                    // Fence it independently; do not overwrite the current session/checkpoint.
                    pauseDetachedCreation(client, next)
                    return@launchOperation
                }
                checkpointSaved(saved.session(next.sessionId))
                checkpointSaved(saved.clearPending())
                permitAndAccept(next)
            } catch (e: Exception) {
                handleFailure(e, generation)
            }
        }
    }

    /** Restore always stays quiet; inspecting a server snapshot is never consent to play. */
    fun restore() {
        if (closed) return
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return
        val client = api ?: return
        if (mutableUi.value.busy || pauseJob?.isActive == true) return
        val id =
            readCheckpointSession()
                ?: run {
                    if (mutableUi.value.localStorageProblem) return
                    mutableUi.value = mutableUi.value.copy(message = "저장된 세션이 없어요. 새 모험을 시작하세요.")
                    return
                }
        mutableUi.value = mutableUi.value.copy(newAdventureStart = null)
        val generation = localStop()
        mutableUi.value = mutableUi.value.copy(busy = true)
        launchOperation {
            try {
                inspectPending(client, id)
                val current = ensurePaused(client, client.snapshot(id))
                if (gate.isCurrent(generation)) {
                    checkpointSaved(checkpointFor(client).clearPending())
                    mutableUi.value =
                        mutableUi.value.copy(
                            snapshot = current,
                            authoredDialogue = dialogueFor(current),
                            busy = false,
                            quiet = true,
                            selectedPackId = current.game.getString("pack_id"),
                            hasSavedSession = current.status != "ENDED",
                            failure = false,
                            message = "진행을 확인했어요. 이어하기를 눌러야 다시 재생돼요.",
                        )
                }
            } catch (e: Exception) {
                handleFailure(e, generation)
            }
        }
    }

    fun resume() {
        if (closed) return
        if (
            familyDeletionPending ||
                mutableUi.value.localStorageProblem ||
                !fullUiAllowed
        )
            return
        val client = api ?: return
        val id = mutableUi.value.snapshot?.sessionId ?: readCheckpointSession() ?: return
        resumeSession(client, id)
    }

    /** Only a currently owned, running voice session may enter a short media pause. */
    internal fun mediaPauseCandidateId(): String? {
        val state = mutableUi.value
        return state.snapshot?.takeIf {
            mediaVoiceAccess() && !gate.quiet && it.status == "ACTIVE"
        }?.sessionId
    }

    internal val mediaStopGeneration: Long get() = gate.generation

    private fun mediaVoiceAccess(): Boolean = !closed && !familyDeletionPending &&
        !localLoadPending && !deletionRunning && !mutableUi.value.localStorageProblem &&
        !mutableUi.value.failure && mutableUi.value.voiceInput && !mutableUi.value.simulation &&
        mutableUi.value.speechReady && mutableUi.value.speechProblem == null &&
        mutableUi.value.voiceInputProblem == null && voiceHost.foregroundServiceRunning &&
        voiceHost.explicitlyStartedSession && sessionAllowed && voiceSessionPermissionsGranted()

    internal fun canRetainMediaPause(sessionId: String, stopGeneration: Long): Boolean =
        mediaVoiceAccess() && gate.quiet && gate.generation == stopGeneration &&
            mutableUi.value.snapshot?.let { it.sessionId == sessionId && it.status != "ENDED" } == true

    internal fun mediaPausedSession(): MediaPausedSession? {
        val state = mutableUi.value
        val current = state.snapshot ?: return null
        if (!mediaVoiceAccess() || !gate.quiet || state.busy || current.status != "PAUSED") return null
        return MediaPausedSession(current.sessionId, current.version, current.turnEpoch, gate.generation)
    }

    /** No checkpoint fallback and no UI grant. The existing live FGS must still own this paused turn. */
    internal fun resumeFromMediaControl(target: MediaPausedSession): Boolean {
        if (mediaPausedSession() != target) return false
        val client = api ?: return false
        return resumeSession(client, target.sessionId, target)
    }

    /** Recheck after suspension; a lost grant or policy must leave no pending activation. */
    private fun ensureMediaResumeAccess(): Boolean {
        if (!voiceSessionPermissionsGranted()) {
            foregroundHostFailed(permissionDenied = true)
            return false
        }
        if (!mediaVoiceAccess()) {
            pause("USER")
            return false
        }
        return ensureVoiceInputAvailable()
    }

    private fun resumeSession(client: GameClient, id: String, mediaTarget: MediaPausedSession? = null): Boolean {
        if (mutableUi.value.busy || pauseJob?.isActive == true) return false
        if (!sessionAllowed || !ensureVoiceInputAvailable()) return false
        mutableUi.value = mutableUi.value.copy(newAdventureStart = null)
        val generation = localStop()
        mutableUi.value = mutableUi.value.copy(busy = true)
        launchOperation {
            try {
                inspectPending(client, id)
                val current = client.snapshot(id)
                val paused = if (mediaTarget == null) ensurePaused(client, current) else {
                    if (current.sessionId != mediaTarget.sessionId || current.version != mediaTarget.version ||
                        current.turnEpoch != mediaTarget.turnEpoch || current.status != "PAUSED") {
                        if (gate.isCurrent(generation)) mutableUi.value = mutableUi.value.copy(busy = false)
                        return@launchOperation
                    }
                    current
                }
                if (!gate.isCurrent(generation)) return@launchOperation
                if (paused.status == "ENDED") {
                    mutableUi.value =
                        mutableUi.value.copy(
                            snapshot = paused,
                            authoredDialogue = dialogueFor(paused),
                            busy = false,
                            message = "완료한 모험이에요. 새 모험을 시작할 수 있어요.",
                        )
                    return@launchOperation
                }
                if (!sessionAllowed) {
                    pause("USER")
                    return@launchOperation
                }
                if (mediaTarget != null && !ensureMediaResumeAccess()) return@launchOperation
                val resumed = send(client, paused, "session.resume", JSONObject())
                if (!gate.isCurrent(generation)) return@launchOperation
                if (mediaTarget != null && !ensureMediaResumeAccess()) return@launchOperation
                checkpointSaved(checkpointFor(client).clearPending())
                mutableUi.value =
                    mutableUi.value.copy(selectedPackId = resumed.game.getString("pack_id"))
                permitAndAccept(resumed)
            } catch (e: Exception) {
                handleFailure(e, generation)
            }
        }
        return true
    }

    private fun checkpointSaved(saved: Boolean) {
        if (!saved) throw ApiException(500, "LOCAL_CHECKPOINT_UNAVAILABLE")
    }

    private fun readCheckpointSession(): String? =
        try {
            checkpoint.sessionId
        } catch (error: Exception) {
            handleFailure(error, gate.generation)
            null
        }

    /** Never queued behind a general action. Output is cancelled before any HTTP operation. */
    fun pause(reason: String = "QUIET_REQUEST") {
        if (closed) return
        if (reason == "AUDIO_FOCUS" && !gate.quiet) {
            mutableUi.value = mutableUi.value.copy(recoveryCause = RecoveryCause.AUDIO_FOCUS, failure = true)
        }
        val generation = localStop()
        if (familyDeletionPending || mutableUi.value.localStorageProblem) return
        val client = api ?: return
        val id = mutableUi.value.snapshot?.sessionId ?: readCheckpointSession() ?: return
        pauseJob?.cancel()
        mutableUi.value = mutableUi.value.copy(busy = true, message = "잠시 쉬어가요. 모험을 기억하고 있어요.")
        pauseJob = launchOperation {
            try {
                val paused = ensurePaused(client, client.snapshot(id), reason)
                if (gate.isCurrent(generation)) {
                    mutableUi.value =
                        mutableUi.value.copy(
                            snapshot = paused,
                            authoredDialogue = dialogueFor(paused),
                            busy = localLoadPending,
                            quiet = true,
                            hasSavedSession = paused.status != "ENDED",
                            message = "조용히 쉬는 중 · 이어하기 전까지 재생하지 않아요.",
                        )
                    refreshLocalRecords(paused)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.code.startsWith("LOCAL_")) {
                    handleFailure(e, generation)
                    return@launchOperation
                }
                if (gate.isCurrent(generation))
                    mutableUi.value =
                        mutableUi.value.copy(
                            busy = localLoadPending,
                            quiet = true,
                            failure = true,
                            recoveryCause = recoveryFor(e, client === localClient),
                            message = "소리는 멈췄어요. 진행 저장을 확인하지 못했으니 다시 연결한 뒤 이어가 주세요.",
                        )
            }
        }
    }

    internal fun dismissNewAdventureStart() {
        if (!closed && mutableUi.value.quiet)
            mutableUi.value = mutableUi.value.copy(newAdventureStart = null)
    }

    fun submit(text: String) {
        if (closed) return
        if (mutableUi.value.localStorageProblem || !fullUiAllowed) return
        if (mutableUi.value.voiceInput) return
        val ticket = captureGate.active ?: return
        val capture =
            runCatching { input.capture(text) }
                .getOrElse {
                    return
                }
        submitCaptured(ticket, capture, input.source)
    }

    private fun submitCaptured(ticket: CaptureTicket, capture: FinalUtterance, source: String) {
        if (mutableUi.value.localStorageProblem || !sessionAllowed) return
        if (
            capture.listenWindowId != ticket.context.listenWindowId ||
                !captureGate.consume(ticket, currentCaptureContext())
        )
            return
        if (isQuietRequest(capture.text)) {
            pause()
            return
        }
        val client = api ?: return
        val snapshot = mutableUi.value.snapshot ?: return
        val generation = ticket.context.outputGeneration
        closeInput()
        mutableUi.value =
            mutableUi.value.copy(
                busy = true,
                microphoneListening = false,
                inputStatus = "입력을 전달하는 중 · 마이크 꺼짐",
            )
        launchOperation {
            try {
                val payload =
                    JSONObject()
                        .put("text", capture.text)
                        .put("is_final", true)
                        .put("capture_id", capture.captureId)
                        .put("listen_window_id", capture.listenWindowId)
                        .put("locale", "ko-KR")
                        .put("input_source", source)
                val next = send(client, snapshot, "action.submit", payload)
                if (gate.accepts(generation)) accept(next)
            } catch (e: Exception) {
                handleFailure(e, generation)
            }
        }
    }

    /** Explicit mock callback; never represented as real audio playback. */
    fun simulatePlaybackCompletion() {
        if (closed) return
        if (mutableUi.value.localStorageProblem || !fullUiAllowed) return
        if (!mutableUi.value.simulation || mutableUi.value.busy || gate.quiet) return
        val snapshot = mutableUi.value.snapshot ?: return
        val plan = snapshot.speechPlan ?: return
        if (snapshot.phase !in setOf("PROMPTING", "FEEDBACK") || activePlayback != plan.playbackId)
            return
        completePlayback(snapshot, plan, gate.generation)
    }

    private fun permitAndAccept(snapshot: Snapshot) {
        accept(snapshot, explicitActivation = true)
    }

    /** A playback acknowledgement removes its plan but must not restore an obsolete question. */
    private fun dialogueFor(next: Snapshot?): String {
        if (next == null) return ""
        next.speechPlan?.let { return it.segments.joinToString("\n") { segment -> segment.text } }
        val state = mutableUi.value
        val previous = state.snapshot
        return if (
            previous?.sessionId == next.sessionId &&
                previous.turnId == next.turnId &&
                previous.turnEpoch == next.turnEpoch &&
                state.authoredDialogue.isNotBlank()
        ) state.authoredDialogue
        else next.presentation.optString("prompt", "")
    }

    private fun accept(next: Snapshot, explicitActivation: Boolean = false) {
        if (closed) return
        if (mutableUi.value.localStorageProblem || !sessionAllowed) {
            localStop()
            return
        }
        val previous = mutableUi.value.snapshot
        if (previous?.sessionId == next.sessionId && previous.version > next.version) {
            if (explicitActivation) mutableUi.value = mutableUi.value.copy(
                busy = false, recoveryCause = mutableUi.value.recoveryCause ?: RecoveryCause.PROGRESS)
            return
        }
        if (next.status != "ACTIVE") {
            localStop()
            mutableUi.value =
                mutableUi.value.copy(
                    snapshot = next,
                    newAdventureStart = null,
                    authoredDialogue = dialogueFor(next),
                    busy = false,
                    hasSavedSession = next.status != "ENDED",
                    message = if (next.status == "ENDED") "모험 완료 · 진행이 저장됐어요." else "조용히 쉬는 중",
                )
            refreshLocalRecords(next, includeJournal = next.status == "ENDED")
            return
        }
        if (explicitActivation) {
            checkpointSaved(checkpoint.permit())
            gate.permitExplicitly()
        }
        mutableUi.value =
            mutableUi.value.copy(
                snapshot = next,
                newAdventureStart = null,
                authoredDialogue = dialogueFor(next),
                busy = false,
                quiet = gate.quiet,
                outputStage = if (
                    previous?.sessionId == next.sessionId &&
                    previous.turnId == next.turnId && previous.turnEpoch == next.turnEpoch &&
                    activePlayback != null && activePlayback == next.speechPlan?.playbackId
                ) mutableUi.value.outputStage else null,
                hasSavedSession = true,
                failure = false,
                recoveryCause = if (explicitActivation) null else mutableUi.value.recoveryCause,
                message = "우리의 모험이 이어지고 있어요.",
            )
        updateNarrationState(speech.narrationState?.value)
        refreshLocalRecords(next)
        if (gate.quiet) return
        if (next.phase == "LISTENING" && next.listenWindowId != null) {
            activePlayback = null
            beginCapture()
            return
        }
        closeInput()
        val plan = next.speechPlan ?: return
        if (next.phase !in setOf("PROMPTING", "FEEDBACK")) return
        if (plan.turnId != next.turnId || plan.turnEpoch != next.turnEpoch) {
            localStop()
            mutableUi.value = mutableUi.value.copy(failure = true, recoveryCause = RecoveryCause.PROGRESS,
                message = "이전 차례의 음성을 차단했어요. 진행을 다시 확인하세요.")
            return
        }
        if (activePlayback == plan.playbackId) return
        activePlayback = plan.playbackId
        val generation = gate.replacePlan()
        if (mutableUi.value.simulation) {
            mutableUi.value =
                mutableUi.value.copy(
                    outputStage = null,
                    narrationActive = false,
                    message = "천천히 읽고 준비되면 다음으로 넘어가요.",
                    inputStatus = "읽으며 체험 · 마이크 꺼짐",
                )
        } else {
            mutableUi.value =
                mutableUi.value.copy(
                    outputStage = OutputStage.PREPARING,
                    narrationActive = false,
                    message = "오누가 목소리를 준비하고 있어요. 잠깐 쉬기를 누르면 멈춰요.",
                    inputStatus = "목소리 준비 중 · 마이크 꺼짐",
                    microphoneListening = false,
                )
            speech.speak(
                plan,
                generation,
                complete = {
                    if (gate.accepts(generation)) completePlayback(next, plan, generation)
                },
                failure = { failure ->
                    if (gate.accepts(generation)) {
                        pause("USER")
                        mutableUi.value = mutableUi.value.copy(message = failure.cause.notice().detail,
                            recoveryCause = failure.cause, failure = true)
                    }
                },
                started = {
                    if (playbackIsCurrent(next, plan, generation) &&
                        !mutableUi.value.busy && mutableUi.value.outputStage == OutputStage.PREPARING
                    ) {
                        mutableUi.value = mutableUi.value.copy(
                            outputStage = OutputStage.STARTED,
                            message = "오누가 이야기를 들려줘요. 잠깐 쉬기를 누르면 멈춰요.",
                            inputStatus = "안내 재생 중 · 마이크 꺼짐",
                        )
                    }
                },
            )
        }
    }

    private fun refreshLocalRecords(expected: Snapshot, includeJournal: Boolean = false) {
        if (closed) return
        if (api !== localClient) return
        launchOperation {
            try {
                val saved = localClient.savedAdventures()
                val journal = if (includeJournal) localClient.journal() else null
                val current = mutableUi.value.snapshot
                if (
                    api !== localClient ||
                        current?.sessionId != expected.sessionId ||
                        current.version != expected.version
                )
                    return@launchOperation
                mutableUi.value =
                    mutableUi.value.copy(
                        savedAdventures = saved,
                        journal = journal ?: mutableUi.value.journal,
                        completedAdventures = journal?.size ?: mutableUi.value.completedAdventures,
                    )
                refreshRecommendations()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val current = mutableUi.value.snapshot
                if (
                    api === localClient &&
                        current?.sessionId == expected.sessionId &&
                        current.version == expected.version
                ) {
                    mutableUi.value =
                        mutableUi.value.copy(
                            failure = true,
                            message = "모험 진행은 저장했어요. 기억 목록을 다시 확인하려면 앱을 다시 열어 주세요.",
                        )
                }
            }
        }
    }

    private fun updateNarrationState(signal: SpeechNarrationState?) {
        if (closed) return
        val state = mutableUi.value
        val snapshot = state.snapshot
        val plan = snapshot?.speechPlan
        val eligible = !state.simulation && state.voiceInput && !state.busy &&
            fullUiAllowed && !state.minimalUiRequired && snapshot != null && plan != null &&
            playbackIsCurrent(snapshot, plan, gate.generation)
        if (eligible && signal != null &&
            (signal.playbackId != plan?.playbackId || !gate.accepts(signal.generation))) return
        val speaking = eligible && signal?.active == true
        if (state.narrationActive != speaking) mutableUi.value = state.copy(narrationActive = speaking)
    }

    private fun playbackIsCurrent(snapshot: Snapshot, plan: SpeechPlan, generation: Long): Boolean {
        val state = mutableUi.value
        val current = state.snapshot ?: return false
        return !closed && gate.accepts(generation) && !state.quiet && !state.localStorageProblem &&
            sessionAllowed && activePlayback == plan.playbackId &&
            current.status == "ACTIVE" && current.phase in setOf("PROMPTING", "FEEDBACK") &&
            current.sessionId == snapshot.sessionId && current.turnId == plan.turnId &&
            current.turnEpoch == plan.turnEpoch && current.speechPlan?.playbackId == plan.playbackId
    }

    private fun completePlayback(snapshot: Snapshot, plan: SpeechPlan, generation: Long) {
        if (mutableUi.value.localStorageProblem || !sessionAllowed) return
        val client = api ?: return
        if (!playbackIsCurrent(snapshot, plan, generation) || mutableUi.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, outputStage = null, narrationActive = false,
            inputStatus = "다음 장면 준비 중 · 마이크 꺼짐")
        launchOperation {
            try {
                val next =
                    send(
                        client,
                        snapshot,
                        "playback.completed",
                        JSONObject().put("playback_id", plan.playbackId),
                    )
                if (gate.accepts(generation)) accept(next)
            } catch (e: Exception) {
                handleFailure(e, generation)
            }
        }
    }

    private fun currentCaptureContext(): CaptureContext? {
        if (closed) return null
        if (mutableUi.value.localStorageProblem || !sessionAllowed)
            return null
        val state = mutableUi.value
        val current = state.snapshot ?: return null
        val window = current.listenWindowId ?: return null
        if (gate.quiet || state.busy || current.status != "ACTIVE" || current.phase != "LISTENING")
            return null
        return CaptureContext(
            current.sessionId,
            current.version,
            current.turnId,
            current.turnEpoch,
            window,
            gate.generation,
        )
    }

    private fun beginCapture() {
        val context = currentCaptureContext() ?: return
        if (captureGate.active?.context == context) return
        closeInput()
        val ticket = captureGate.open(context)
        if (!mutableUi.value.voiceInput) {
            input.open(context.listenWindowId)
            mutableUi.value = mutableUi.value.copy(inputStatus = "마음에 드는 선택을 골라 주세요. 마이크는 꺼져 있어요.")
            return
        }
        if (!ensureVoiceInputAvailable()) return
        if (!inputFocus.acquire()) {
            pause("AUDIO_FOCUS")
            mutableUi.value =
                mutableUi.value.copy(failure = true, inputStatus = "다른 소리를 듣고 있어 잠시 쉬고 있어요.")
            return
        }
        mutableUi.value =
            mutableUi.value.copy(
                inputStatus = "마이크 준비 중 · ‘듣는 중’ 표시 뒤 말씀해 주세요.",
                microphoneListening = false,
            )
        captureSignals.open(ticket)
        deviceInput.open(
            ticket,
            onStage = { received, stage ->
                if (captureGate.accepts(received, currentCaptureContext())) {
                    captureSignals.stage(received, stage)
                    mutableUi.value =
                        mutableUi.value.copy(
                            microphoneListening = stage == CaptureStage.LISTENING,
                            captureStage = stage,
                            inputStatus =
                                if (stage == CaptureStage.LISTENING) when {
                                    mutableUi.value.snapshot?.presentation?.optString("input_kind") == "player_name" ->
                                        "듣는 중 · 이름만 말씀해 주세요."
                                    mutableUi.value.snapshot?.game?.optJSONArray("players")?.length() == 1 ->
                                        "듣는 중 · 선택을 말씀해 주세요."
                                    else -> "듣는 중 · 이름과 선택을 말씀해 주세요."
                                }
                                else "인식 결과를 확인하는 중",
                        )
                }
            },
            onFinal = { received, text ->
                submitCaptured(
                    received,
                    FinalUtterance(
                        UUID.randomUUID().toString(),
                        text,
                        received.context.listenWindowId,
                    ),
                    deviceInput.source,
                )
            },
            onFailure = { received, failure ->
                if (captureGate.consume(received, currentCaptureContext())) {
                    pause(failure.pauseReason)
                    mutableUi.value =
                        mutableUi.value.copy(
                            inputStatus = failure.cause?.notice()?.detail ?: "잠깐 쉬어가요. 준비됐을 때 다시 이어가요.",
                            recoveryCause = failure.cause ?: mutableUi.value.recoveryCause,
                            failure = failure.pauseReason != "NO_RESPONSE",
                        )
                }
            },
        )
    }

    private fun closeInput() {
        captureGate.close()
        captureSignals.close()
        input.close()
        deviceInput.close()
        inputFocus.release()
        mutableUi.value = mutableUi.value.copy(microphoneListening = false, captureStage = null)
    }

    private fun isQuietRequest(text: String): Boolean {
        val players = mutableUi.value.snapshot?.game?.optJSONArray("players")
        val names =
            if (players == null) emptyList()
            else
                List(players.length()) {
                    players.optJSONObject(it)?.optString("nickname").orEmpty()
                }
        return QuietRequest.matches(text, names)
    }

    private suspend fun send(
        client: GameClient,
        current: Snapshot,
        type: String,
        payload: JSONObject,
    ): Snapshot {
        check(type == "session.pause" || sessionAllowed) {
            "Vehicle access is blocked."
        }
        val id = UUID.randomUUID().toString()
        checkpointSaved(checkpointFor(client).addPending(id))
        val response =
            client.command(current.sessionId, commandEnvelope(current, id, type, payload))
        if (response.optString("command_status") != "PENDING")
            checkpointSaved(checkpointFor(client).removePending(id))
        val raw = Snapshot.from(response)
        val received = if (client === localClient) localClient.decorateSnapshot(raw) else raw
        val local = mutableUi.value.snapshot
        return if (local?.sessionId == received.sessionId && local.version > received.version)
            client.snapshot(current.sessionId)
        else received
    }

    private suspend fun ensurePaused(
        client: GameClient,
        initial: Snapshot,
        reason: String = "QUIET_REQUEST",
    ): Snapshot {
        var current = initial
        repeat(3) {
            // Even a PAUSED snapshot can have a delayed resume in flight. Commit a new epoch.
            if (current.status == "ENDED") return current
            try {
                val result =
                    send(client, current, "session.pause", JSONObject().put("reason", reason))
                check(result.status in setOf("PAUSED", "ENDED")) { "Pause is not confirmed." }
                return result
            } catch (e: ApiException) {
                if (e.status != 409) throw e
                // A changed version is a NEW command/body; never reuse the earlier command_id.
                current = client.snapshot(initial.sessionId)
            }
        }
        error("Pause state has not yet been confirmed.")
    }

    private suspend fun pauseDetachedCreation(client: GameClient, initial: Snapshot) {
        var current = initial
        repeat(3) {
            if (current.status in setOf("PAUSED", "ENDED")) return
            try {
                val command =
                    commandEnvelope(
                        current,
                        UUID.randomUUID().toString(),
                        "session.pause",
                        JSONObject().put("reason", "QUIET_REQUEST"),
                    )
                current = Snapshot.from(client.command(current.sessionId, command))
            } catch (e: ApiException) {
                if (e.status != 409) return
                current =
                    try {
                        client.snapshot(initial.sessionId)
                    } catch (_: Exception) {
                        return
                    }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return // No output is possible locally. Server idle-session cleanup remains a
                // release task.
            }
        }
    }

    private suspend fun inspectPending(client: GameClient, session: String) {
        for (id in checkpointFor(client).pendingCommandIds) {
            try {
                val receipt = client.receipt(session, id)
                if (receipt.optString("command_status") != "PENDING")
                    checkpointSaved(checkpointFor(client).removePending(id))
            } catch (e: ApiException) {
                // 404 is not proof a delayed request cannot arrive; ensurePaused fences its epoch
                // next.
                if (e.status != 404) throw e
            }
        }
    }

    private fun localStop(): Long {
        val generation = gate.stop()
        closeInput()
        speech.stop()
        activePlayback = null
        val saved = familyDeletionPending || checkpoint.quiet()
        mutableUi.value =
            mutableUi.value.copy(
                quiet = true,
                newAdventureStart = if (mutableUi.value.newAdventureStart == NewAdventureStart.PREPARING)
                    NewAdventureStart.STOPPED else mutableUi.value.newAdventureStart,
                busy = localLoadPending || deletionRunning,
                microphoneListening = false,
                outputStage = null,
                narrationActive = false,
                localStorageProblem =
                    mutableUi.value.localStorageProblem || (!saved && api === localClient),
                inputStatus = "마이크 꺼짐 · 이어하기 전까지 듣지 않아요.",
                message =
                    if (familyDeletionPending) mutableUi.value.message
                    else if (saved) "조용히 쉬는 중" else "출력 차단됨 · 로컬 저장 실패를 확인해 주세요.",
            )
        return generation
    }

    private fun handleFailure(error: Exception, generation: Long) {
        if (closed) return
        if (error is CancellationException) throw error
        if (familyDeletionPending) return
        if (!gate.isCurrent(generation)) return
        localStop()
        val storageProblem =
            api === localClient &&
                error is ApiException &&
                error.code in
                    setOf(
                        "LOCAL_STORAGE_FULL",
                        "LOCAL_STORAGE_UNAVAILABLE",
                        "LOCAL_STORAGE_VERSION_UNAVAILABLE",
                        "LOCAL_PROFILE_UNAVAILABLE",
                        "LOCAL_INTERACTION_UNAVAILABLE",
                        "LOCAL_CHECKPOINT_UNAVAILABLE",
                        "CONTENT_VERSION_UNAVAILABLE",
                    )
        mutableUi.value =
            mutableUi.value.copy(
                failure = true,
                newAdventureStart = mutableUi.value.newAdventureStart?.let { NewAdventureStart.FAILED },
                localStorageProblem = mutableUi.value.localStorageProblem || storageProblem,
                recoveryCause = if (storageProblem) mutableUi.value.recoveryCause
                    else recoveryFor(error, api === localClient),
                message =
                    when (error) {
                        is ApiException ->
                            when (error.code) {
                                "LOCAL_STORAGE_FULL" -> "기기의 모험 기록 공간이 가득 찼어요. 기존 기록은 지우지 않았어요."
                                "LOCAL_STORAGE_VERSION_UNAVAILABLE" ->
                                    "이 기록은 다른 버전에서 만들어졌어요. 최신 오누로 다시 확인해 주세요."
                                "LOCAL_PROFILE_UNAVAILABLE",
                                "LOCAL_INTERACTION_UNAVAILABLE",
                                "LOCAL_STORAGE_UNAVAILABLE",
                                "LOCAL_CHECKPOINT_UNAVAILABLE" ->
                                    "저장된 기록을 안전하게 읽지 못했어요. 기록을 덮어쓰거나 새 가족으로 바꾸지 않았어요."
                                "invalid_players",
                                "INVALID_PLAYERS" -> "별명을 확인해 주세요. 서로 구별되는 1~12글자 이름을 사용해요."
                                "CONTENT_VERSION_UNAVAILABLE",
                                "content_version_mismatch" -> "이 모험에 맞는 콘텐츠가 필요해요. 앱 버전을 확인해 주세요."
                                "ADVENTURE_NOT_SUPPORTED" ->
                                    "선택한 모험을 이 연결에서 사용할 수 없어요. 기기 내 모험으로 전환해 주세요."
                                "SESSION_SUPERSEDED" ->
                                    "이 모험은 새로 시작한 기록이 있어요. 모험 목록에서 최근 진행을 이어가 주세요."
                                else -> "진행을 확인하지 못해 잠시 쉬고 있어요. 다시 이어가면 저장된 모험을 확인해요."
                            }
                        else -> "진행을 저장하거나 재생하지 못해 잠시 쉬고 있어요. 다시 이어가 주세요."
                    },
            )
    }

    private fun recoveryFor(error: Exception, local: Boolean): RecoveryCause = when {
        error is ApiException -> when (error.code) {
            "SESSION_SUPERSEDED" -> RecoveryCause.SESSION_REPLACED
            "CONTENT_VERSION_UNAVAILABLE", "content_version_mismatch" -> RecoveryCause.CONTENT_UNAVAILABLE
            "ADVENTURE_NOT_SUPPORTED" -> RecoveryCause.ADVENTURE_UNSUPPORTED
            "invalid_players", "INVALID_PLAYERS" -> RecoveryCause.PROFILE
            else -> if (local || error.status in 400..499) RecoveryCause.PROGRESS else RecoveryCause.CONNECTION
        }
        local -> RecoveryCause.PROGRESS
        else -> RecoveryCause.CONNECTION
    }

    /** UI attachment is independent of the foreground audio owner. No Activity is retained. */
    @Suppress("DEPRECATION")
    fun foreground(context: Context) {
        if (closed) return
        voiceHost = voiceHost.copy(uiVisible = true)
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
            ?.defaultDisplay
        val displayId = display?.displayId
        if (vehicleObserving && observedDisplayId == displayId) {
            publishInteractionPolicy()
            return
        }
        val epoch = ++vehicleLifetime
        vehicleAccessProvider.close()
        vehicleObserving = true
        observedDisplayId = displayId
        updateVehicleSnapshot(vehicleAccessProvider.initialSnapshot)
        val observationContext = display?.let { context.applicationContext.createDisplayContext(it) }
            ?: context.applicationContext
        vehicleAccessProvider.startObserving(observationContext) { next ->
            if (!closed && epoch == vehicleLifetime) updateVehicleSnapshot(next)
        }
    }

    private fun updateVehicleSnapshot(next: VehicleUxSnapshot) {
        if (closed) return
        vehicleSnapshot = next
        publishInteractionPolicy()
    }

    private fun publishInteractionPolicy() {
        val policy = interactionPolicy
        mutableUi.value = mutableUi.value.copy(vehicleAccess = vehicleSnapshot.access,
            vehicleUx = vehicleSnapshot, fullUiAllowed = policy.allowsFullUi,
            voiceSessionAllowed = sessionAllowed && mutableUi.value.voiceInput,
            minimalUiRequired = policy.requiresMinimalUi && sessionAllowed)
        updateNarrationState(speech.narrationState?.value)
        if (!sessionAllowed && hasActiveOrPendingSession()) pause("USER")
    }

    private fun hasActiveOrPendingSession() = !mutableUi.value.quiet ||
        (mutableUi.value.busy && !localLoadPending && !deletionRunning && pauseJob?.isActive != true)

    /** Only the private service host calls this after actual foreground promotion. */
    fun setForegroundHost(running: Boolean, restrictedVoiceSupported: Boolean = false) {
        if (closed) return
        val lostOwner = voiceHost.foregroundServiceRunning && !running
        voiceHost = voiceHost.copy(foregroundServiceRunning = running,
            explicitlyStartedSession = running, restrictedVoiceSupported = running && restrictedVoiceSupported)
        if (lostOwner && hasActiveOrPendingSession()) pause("USER")
        publishInteractionPolicy()
        if (!running && !voiceHost.uiVisible) detachVehicleObserver()
    }

    fun foregroundHostFailed(permissionDenied: Boolean) {
        if (closed) return
        setForegroundHost(false)
        pause("USER")
        mutableUi.value = mutableUi.value.copy(failure = true,
            recoveryCause = if (permissionDenied) RecoveryCause.INPUT_PERMISSION else RecoveryCause.PROGRESS,
            message = if (permissionDenied) "음성 권한을 확인한 뒤 직접 다시 시작해 주세요."
                else "음성 진행을 준비하지 못했어요. 앱을 연 상태에서 다시 시작해 주세요.",
            inputStatus = "마이크 꺼짐 · 다시 시작하기 전까지 듣지 않아요.")
    }

    fun background() {
        if (closed) return
        voiceHost = voiceHost.copy(uiVisible = false)
        publishInteractionPolicy()
        if (!voiceHost.foregroundServiceRunning) detachVehicleObserver()
    }

    private fun detachVehicleObserver() {
        vehicleLifetime++
        vehicleObserving = false
        observedDisplayId = null
        vehicleAccessProvider.close()
        vehicleSnapshot = vehicleAccessProvider.initialSnapshot
        publishInteractionPolicy()
    }

    /** One-way shutdown. It never resumes/loads a session or cancels the host's scope. */
    fun close() {
        if (closed) return
        closed = true
        vehicleLifetime++
        invalidateLocalLoad()
        sessionJob.cancel()
        try {
            localStop()
        } finally {
            // Release all owned adapters even if one cleanup hook fails.
            try {
                captureSignals.release()
            } finally {
                try {
                    vehicleAccessProvider.close()
                } finally {
                    speech.shutdown()
                }
            }
        }
    }
}
