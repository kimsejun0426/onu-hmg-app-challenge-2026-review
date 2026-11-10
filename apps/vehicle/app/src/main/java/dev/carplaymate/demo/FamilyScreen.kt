package dev.carplaymate.demo

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun FamilyScreen(model: DeveloperViewModel, onHide: () -> Unit) {
    val ui by model.ui.collectAsState()
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("home") }
    var permissionProblem by remember { mutableStateOf<String?>(null) }
    var restartPack by rememberSaveable { mutableStateOf<String?>(null) }
    var letterRecord by rememberSaveable { mutableStateOf<String?>(null) }
    var rainRecord by rememberSaveable { mutableStateOf<String?>(null) }
    val letterProgress = remember(context) { FirstJumpProgress(context) }
    val rainProgress = remember(context) { FirstJumpProgress(context, "rain_music") }
    val letterFamily = ui.children.joinToString(",") { it.id }
    val prefix = "$letterFamily#"
    val lettersEligible = ui.onboarded && !ui.familyDeletionPending && !ui.localStorageProblem && letterFamily.isNotEmpty()
    val storedJump = remember(letterFamily, lettersEligible) { if (lettersEligible) letterProgress.read(letterFamily) else null }
    val storedRain = remember(letterFamily, lettersEligible) { if (lettersEligible) rainProgress.read(letterFamily) else null }
    fun scoped(record: String?) = record?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
    val jumpToken = scoped(letterRecord) ?: storedJump
    val rainToken = scoped(rainRecord) ?: storedRain
    val jumpState = restoreLetter(jumpToken)
    val lastJump = jumpState?.takeIf { it.step == JumpStep.END }?.ending
    val rainState = restoreRain(rainToken)
    fun ownsLetters(): Boolean = model.ui.value.let { current ->
        current.onboarded && !current.familyDeletionPending && !current.localStorageProblem &&
            current.children.joinToString(",") { it.id } == letterFamily
    }
    fun deliverReply(origin: JumpEnding) {
        if (!ownsLetters() || scoped(rainRecord) != null) return
        val initial = RainGame.initial(origin).state.rainCheckpoint()
        rainRecord = prefix + rainProgress.initialize(letterFamily, initial)
    }
    // A completed first letter from a previous app version also delivers the reply on upgrade.
    LaunchedEffect(letterFamily, lettersEligible, lastJump) {
        if (lettersEligible && lastJump != null && rainToken == null) deliverReply(lastJump)
    }
    val permissionReturns = rememberSaveable(saver = FamilyPermissionReturns.Saver) {
        FamilyPermissionReturns()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    fun requestFamily(): String? = model.ui.value.let { state ->
        if (!state.onboarded || state.familyDeletionPending || state.localStorageProblem) null
        else state.children.takeIf { it.isNotEmpty() }?.joinToString("|") { it.id }
    }
    fun invalidatePermissionReturn() {
        permissionReturns.invalidate(requestFamily())
        permissionProblem = null
    }
    fun deleteFamily() {
        invalidatePermissionReturn()
        letterRecord = null
        rainRecord = null
        model.forgetFamily()
    }
    fun skipInterests(): Boolean {
        invalidatePermissionReturn()
        return model.skipInterests()
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            grants ->
            permissionReturns.receive(FamilyPermissionReturns.Kind.VOICE,
                grants.isNotEmpty() && grants.values.all { it }, requestFamily())
        }
    val outputPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            grants ->
            permissionReturns.receive(FamilyPermissionReturns.Kind.OUTPUT,
                grants.isNotEmpty() && grants.values.all { it }, requestFamily())
        }
    val appSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionReturns.receive(FamilyPermissionReturns.Kind.SETTINGS, true, requestFamily())
    }
    LaunchedEffect(permissionReturns.pendingToken, lifecycle, lifecycleState, ui.vehicleAccess, ui.fullUiAllowed, ui.busy,
        ui.onboarded, ui.children.map { it.id }, ui.familyDeletionPending, ui.localStorageProblem) {
        val current = model.ui.value
        val result = permissionReturns.take(requestFamily(),
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                current.fullUiAllowed && !current.busy) ?: return@LaunchedEffect
        when (result.kind) {
            FamilyPermissionReturns.Kind.VOICE -> {
                model.microphonePermissionResult(result.granted)
                permissionProblem = if (result.granted) null else
                    "음성 입력 권한을 켜지 않았어요. 다시 음성 방식을 누르거나 기기 앱 권한을 확인해 주세요."
            }
            FamilyPermissionReturns.Kind.OUTPUT -> {
                permissionProblem = if (result.granted) null else
                    "음성 안내 권한을 켜지 않았어요. 기기 앱 권한을 확인하거나 다른 방식을 직접 고를 수 있어요."
                model.retrySpeech()
            }
            FamilyPermissionReturns.Kind.SETTINGS -> {
                permissionProblem = null
                model.retrySpeech() // Recheck only; never select a mode or resume.
            }
        }
    }
    val openAppPermissions: () -> Unit = {
        if (ui.fullUiAllowed && !ui.busy &&
            permissionReturns.begin(FamilyPermissionReturns.Kind.SETTINGS, requestFamily())) {
            model.pause("USER")
            try {
                appSettings.launch(familyAppPermissionsIntent(context.packageName))
            } catch (_: ActivityNotFoundException) {
                permissionReturns.cancel(FamilyPermissionReturns.Kind.SETTINGS)
                permissionProblem = "기기 앱 설정을 열지 못했어요. 기기 설정에서 오누의 앱 권한을 확인해 주세요."
            } catch (_: SecurityException) {
                permissionReturns.cancel(FamilyPermissionReturns.Kind.SETTINGS)
                permissionProblem = "이 기기에서 앱 설정을 열 수 없어요. 기기 설정에서 오누의 앱 권한을 확인해 주세요."
            }
        }
    }
    val requestVoice: () -> Unit = {
        invalidatePermissionReturn()
        if (!model.voiceInputSupported())
            permissionProblem = model.ui.value.voiceInputProblem
        else {
            val required = model.requiredVoicePermissions()
            if (required.isEmpty()) {
                permissionProblem = null
                model.useVoiceInput(true)
            } else if (permissionReturns.begin(FamilyPermissionReturns.Kind.VOICE, requestFamily())) {
                model.pause("MIC_PERMISSION")
                permission.launch(required)
            }
        }
    }
    val requestOutput: () -> Unit = {
        invalidatePermissionReturn()
        val required = model.requiredOutputPermissions()
        if (required.isEmpty()) {
            permissionProblem = null
            model.retrySpeech()
        } else if (permissionReturns.begin(FamilyPermissionReturns.Kind.OUTPUT, requestFamily())) {
            model.pause("USER")
            outputPermission.launch(required)
        }
    }
    val selectListen: () -> Unit = {
        invalidatePermissionReturn()
        model.useVoiceInput(false)
    }
    val selectRead: () -> Unit = {
        invalidatePermissionReturn()
        model.useSimulation(true)
    }
    fun afterInterests() = if (model.ui.value.interactionConfigured) "settings" else "interaction"
    fun navigate(destination: String) {
        model.leaveAdventureScreen()
        page = if (destination == "home" && ui.onboarded && !ui.interactionConfigured) "interaction" else destination
    }
    fun openFirstLetter() {
        if (ownsLetters() && model.ui.value.fullUiAllowed && !model.ui.value.busy) navigate("letter")
    }
    val openReply: (() -> Unit)? = if (rainState != null || lastJump != null) ({
        if (ownsLetters() && model.ui.value.fullUiAllowed && !model.ui.value.busy) {
            lastJump?.let(::deliverReply)
            navigate("rainLetter")
        }
    }) else null
    fun start(packId: String) {
        if (!ui.interactionConfigured) { page = "interaction"; return }
        if (ui.busy || (!ui.speechReady && !ui.simulation)) return
        model.startFamilyAdventure(packId)
        if (model.ui.value.busy) page = "play"
    }
    fun resume(sessionId: String) {
        if (!ui.interactionConfigured) { page = "interaction"; return }
        if (ui.busy || (!ui.speechReady && !ui.simulation)) return
        model.resumeAdventure(sessionId)
        if (model.ui.value.busy) page = "play"
    }
    fun detail(packId: String) {
        if (model.selectAdventure(packId)) page = "detail"
    }
    LaunchedEffect(ui.onboarded, ui.interestSetupNeeded, ui.interactionConfigured) {
        if (!ui.onboarded) page = "home"
        else if (ui.interestSetupNeeded) page = "interests"
        else if (!ui.interactionConfigured) page = "interaction"
    }
    LaunchedEffect(ui.snapshot?.sessionId, ui.quiet) {
        if (!ui.quiet && ui.snapshot != null) page = "play"
    }
    if (!ui.fullUiAllowed) {
        BackHandler { onHide() }
        if (ui.minimalUiRequired) VoiceOnlySessionScreen() else VehicleRestrictedScreen(ui.vehicleAccess)
        return
    }
    if (ui.familyDeletionPending) {
        BackHandler(onBack = onHide)
        FamilyDeletionScreen(ui, onRetry = ::deleteFamily)
        return
    }
    if (ui.localStorageProblem) {
        BackHandler { onHide() }
        StorageRecoveryScreen(
            ui,
            onRetry = model::retryLocalStorage,
            onDelete = ::deleteFamily,
        )
        return
    }
    BackHandler {
        when (page) {
            "home" -> onHide()
            "developer", "journal", "notices" -> navigate("settings")
            "detail" -> navigate("library")
            "interests" -> {
                // Back only leaves this screen. Completing or skipping interests is explicit.
                if (ui.interactionConfigured) navigate("settings") else onHide()
            }
            "interaction" -> if (canFinishInteractionSetup(ui)) navigate("home") else onHide()
            else -> navigate("home")
        }
    }
    Surface(Modifier.fillMaxSize(), color = PlaymateColors.Ivory) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()
        ) {
            val wide = maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp
            if (!ui.onboarded || page in setOf("home", "play", "interaction")) {
                val saved = ui.savedAdventures.firstOrNull { it.sessionId == ui.snapshot?.sessionId } ?: ui.savedAdventures.firstOrNull()
                val pack = if (page == "play") ui.snapshot?.game?.optString("pack_id")
                    else saved?.packId ?: ui.recommendations.firstOrNull()?.packId
                val world = when {
                    !ui.onboarded || page == "interaction" -> "arrival"
                    page == "home" && shouldFeatureFirstJump(ui.children, saved != null) ->
                        if (rainState != null || lastJump != null) "rain_music" else "first_jump"
                    else -> ui.adventures.firstOrNull { it.packId == pack }?.artKey ?: "dinosaurs"
                }
                OnuJourneyBackdrop(
                    world, Modifier.fillMaxSize(),
                    setupIntro = ui.onboarded && page == "interaction",
                )
            }
            Column(Modifier.fillMaxSize()) {
                if (ui.vehicleAccess == VehicleAccessState.NON_AUTOMOTIVE_DEMO && page !in setOf("play", "letter", "rainLetter"))
                    FamilyDemoNotice()
                if (page !in setOf("play", "letter", "rainLetter") && !(page == "interests" && !ui.interactionConfigured)) FamilyHeader(
                    if (!ui.onboarded) "welcome" else page,
                    wide,
                    onHome = {
                        if (page == "interests" && !ui.interactionConfigured) onHide()
                        else navigate(when (page) {
                            "interests", "journal", "notices" -> "settings"
                            "detail" -> "library"
                            else -> "home"
                        })
                    },
                    onSettings = { navigate("settings") },
                )
                if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
                if (ui.failure && page !in setOf("play", "letter", "rainLetter", "settings", "interaction", "notices"))
                    FamilyProblem(
                        ui,
                        onRetry = {
                            if ((ui.voiceInput && ui.voiceInputProblem != null) || (!ui.simulation && !ui.speechReady))
                                navigate(if (ui.interactionConfigured) "settings" else "interaction")
                            else if (ui.useLocal) model.useLocalBackend() else model.restore()
                        },
                        onSettings = { navigate("settings") },
                    )
                if (!ui.onboarded)
                    WelcomeScreen(
                        ui,
                        wide,
                        Modifier.weight(1f).padding(horizontal = if (wide) 32.dp else 20.dp),
                    ) { first, second ->
                        invalidatePermissionReturn()
                        model.saveFamily(first, second).also { if (it) page = "interests" }
                    }
                else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (page) {
                            "interests" ->
                                InterestSetupScreen(
                                    ui,
                                    wide,
                                    Modifier.fillMaxSize(),
                                    onSave = { first, second ->
                                        invalidatePermissionReturn()
                                        model.saveInterests(first, second).also {
                                            if (it) page = afterInterests()
                                        }
                                    },
                                    onSkip = {
                                        invalidatePermissionReturn()
                                        if (!ui.interestSetupNeeded) page = "settings"
                                        else if (skipInterests()) page = afterInterests()
                                    },
                                    editing = !ui.interestSetupNeeded,
                                )
                            "interaction" -> InteractionSetupScreen(
                                ui, wide, permissionProblem, requestVoice, selectListen, selectRead,
                                requestOutput, openAppPermissions,
                                onDone = { if (canFinishInteractionSetup(ui)) navigate("home") },
                            )
                            "letter" -> SoundLetterRoute(JumpEpisode, model, ui, jumpToken, onSave = { value ->
                                if (ownsLetters() && letterRecord != prefix + value) {
                                    // Seed before a replay can replace the END checkpoint with READY.
                                    restoreLetter(value)?.takeIf { it.step == JumpStep.END }?.ending?.let(::deliverReply)
                                    letterRecord = prefix + value
                                    letterProgress.save(letterFamily, value)
                                }
                            }, onHome = { navigate("home") }, onReply = {
                                if (ownsLetters() && model.ui.value.fullUiAllowed) {
                                    lastJump?.let(::deliverReply)
                                    if (scoped(rainRecord) != null || rainState != null) navigate("rainLetter")
                                }
                            })
                            "rainLetter" -> {
                                if (rainState != null || lastJump != null) {
                                    val rain = remember(rainState?.previousJump, lastJump) { RainEpisode(rainState?.previousJump ?: lastJump) }
                                    SoundLetterRoute(rain, model, ui, rainToken, onSave = { value ->
                                        if (ownsLetters() && rainRecord != prefix + value) {
                                            rainRecord = prefix + value
                                            rainProgress.save(letterFamily, value)
                                        }
                                    }, onHome = { navigate("home") })
                                } else LaunchedEffect(Unit) { navigate("home") }
                            }
                            "library" -> AdventureLibrary(ui, wide, ::detail, ::openFirstLetter, openReply, jumpState?.step, rainState?.step)
                            "detail" ->
                                AdventureDetail(
                                    ui,
                                    wide,
                                    ::start,
                                    ::resume,
                                    { restartPack = it },
                                    { navigate("settings") },
                                )
                            "journal" -> FamilyMemory(ui, wide, jumpState, rainState,
                                onLetter = ::openFirstLetter, onReply = openReply, onLibrary = { navigate("library") })
                            "notices" -> OpenSourceNotices(
                                Modifier.fillMaxSize()
                                    .padding(horizontal = if (wide) 32.dp else 20.dp),
                            )
                            "settings" ->
                                FamilySettings(
                                    ui,
                                    wide,
                                    Modifier.fillMaxSize()
                                        .padding(horizontal = if (wide) 32.dp else 20.dp),
                                    permissionProblem,
                                    requestVoice,
                                    onListen = selectListen,
                                    onRead = selectRead,
                                    onSave = { first, second ->
                                        invalidatePermissionReturn()
                                        model.saveFamily(first, second)
                                    },
                                    onInterests = { navigate("interests") },
                                    onMemory = { navigate("journal") },
                                    onNotices = { navigate("notices") },
                                    onAppPermissions = openAppPermissions,
                                    onDelete = {
                                        deleteFamily()
                                        page = "home"
                                    },
                                    onAdvanced = { page = "developer" },
                                    onRetry = requestOutput,
                                    onLocal = model::useLocalBackend,
                                )
                            "developer" -> if (BuildConfig.DEBUG) DeveloperScreen(model)
                            "play" ->
                                ImmersiveAdventure(
                                    ui,
                                    wide,
                                    onPause = { model.pause() },
                                    onResume = model::resume,
                                    onChoose = model::chooseAction,
                                    onReadDone = model::simulatePlaybackCompletion,
                                    onHome = { navigate("home") },
                                    onSettings = { navigate("settings") },
                                    onFeedback = model::setAdventureFeedback,
                                backdrop = false,
                                )
                            else ->
                                FamilyLanding(
                                    ui,
                                    wide,
                                    ::start,
                                    ::resume,
                                    onLibrary = { navigate("library") },
                                    onSettings = { navigate("settings") },
                                    onLetter = ::openFirstLetter,
                                    onReply = openReply,
                                    replyInProgress = rainState != null && rainState.step !in setOf(RainStep.READY, RainStep.END),
                                )
                        }
                    }
                }
            }
        }
    }
    if (restartPack != null)
        AlertDialog(
            onDismissRequest = { restartPack = null },
            title = { Text("새로운 길로 떠날까요?") },
            text = { Text("이 모험의 이전 진행 대신 처음부터 시작해요. 다른 이야기의 진행은 그대로 남아요.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pack = restartPack
                        restartPack = null
                        if (pack != null) start(pack)
                    },
                    enabled = !ui.busy,
                    modifier = Modifier.heightIn(min = 72.dp),
                ) {
                    Text("새 모험 시작")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { restartPack = null },
                    modifier = Modifier.heightIn(min = 72.dp),
                ) {
                    Text("돌아가기")
                }
            },
        )
}

@Composable
internal fun FamilyDeletionScreen(ui: DeveloperUiState, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxSize().testTag("family-deletion"), color = PlaymateColors.Ivory) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(
                    if (ui.busy) "가족 기록을 지우고 있어요" else "삭제를 마저 진행해 주세요",
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (!ui.busy) Text(ui.message, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "삭제가 끝날 때까지 가족 정보와 모험을 열지 않아요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlaymateColors.Muted,
                )
                if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else
                    PrimaryButton(
                        "다시 삭제하기",
                        onRetry,
                        modifier = Modifier.testTag("retry-family-deletion"),
                    )
            }
        }
    }
}

@Composable
private fun FamilyProblem(ui: DeveloperUiState, onRetry: () -> Unit, onSettings: () -> Unit) {
    Surface(
        color = PlaymateColors.Sand,
        modifier = Modifier.fillMaxWidth().testTag("family.problem"),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text(ui.message, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(
                    onClick = onRetry,
                    enabled = !ui.busy,
                    modifier = Modifier.heightIn(min = 64.dp),
                ) {
                    Text("다시 확인")
                }
                TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text("보호자 설정")
                }
            }
        }
    }
}

@Composable
internal fun FamilyDemoNotice() {
    Text(
        "일반 기기 체험 · 차량 상태 미연결",
        style = MaterialTheme.typography.bodySmall,
        color = PlaymateColors.Muted,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
    )
}

@Composable
internal fun FamilyHeader(
    page: String,
    wide: Boolean,
    onHome: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = if (wide) 28.dp else 16.dp, vertical = 4.dp)
            .heightIn(min = 68.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (page !in setOf("home", "welcome", "interaction"))
            TextButton(
                onClick = onHome,
                modifier = Modifier.heightIn(min = 72.dp).testTag("family.homeNav"),
            ) {
                FamilyIcon(FamilyIcon.BACK)
                Spacer(Modifier.width(8.dp))
                Text(when (page) { "interests", "journal", "notices" -> "설정"; "detail" -> "이야기"; else -> "홈" })
            }
        if (page in setOf("home", "welcome")) {
            Box(Modifier.weight(1f)) {
                OnuWordmark(Modifier.size(width = 102.dp, height = 54.dp))
            }
        } else if (page != "interests") Column(Modifier.weight(1f)) {
            Text(
                when (page) {
                    "play" -> "우리의 이야기"
                    "library" -> "이야기 고르기"
                    "journal" -> "지난 이야기"
                    "notices" -> "오픈소스 고지"
                    "settings" -> "보호자 설정"
                    "interaction" -> "함께하는 방식"
                    else -> "오누"
                },
                style = MaterialTheme.typography.titleMedium,
                color = PlaymateColors.Forest,
                modifier = Modifier.semantics { heading() }.then(
                    if (page == "notices") Modifier.testTag("notices.title") else Modifier,
                ),
            )
        }
        if (page !in setOf("welcome", "settings", "developer", "interests", "journal", "interaction", "notices"))
            TextButton(
                onClick = onSettings,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = PlaymateColors.Mist, contentColor = PlaymateColors.Forest,
                ),
                contentPadding = PaddingValues(horizontal = if (wide) 18.dp else 16.dp, vertical = 12.dp),
                modifier =
                    Modifier.heightIn(min = 64.dp).testTag("family.settingsNav").then(
                        if (wide) Modifier else Modifier.semantics { contentDescription = "보호자 설정" },
                    ),
            ) {
                FamilyIcon(FamilyIcon.FAMILY)
                if (wide) {
                    Spacer(Modifier.width(10.dp))
                    Text("보호자 설정")
                }
            }
    }
}

internal fun familyAppPermissionsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
