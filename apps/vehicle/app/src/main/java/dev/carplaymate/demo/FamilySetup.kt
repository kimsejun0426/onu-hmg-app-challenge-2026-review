package dev.carplaymate.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp

@Composable
internal fun WelcomeScreen(
    ui: DeveloperUiState,
    wide: Boolean,
    modifier: Modifier,
    welcomePreview: WelcomeVoicePreview? = null,
    onSave: (String, String?) -> Boolean,
) {
    val preview = rememberWelcomeVoicePreview(ui, welcomePreview)
    val previewState by preview.state.collectAsState()
    val focusManager = LocalFocusManager.current
    var first by rememberSaveable { mutableStateOf("") }
    var second by rememberSaveable { mutableStateOf("") }
    var two by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val welcomeScroll = rememberScrollState()
    val secondNicknameFocus = remember { FocusRequester() }
    BoxWithConstraints(
        modifier.fillMaxSize().testTag("family.onboarding"),
        contentAlignment = Alignment.Center,
    ) {
        val spacious = wide && spaciousSetup(maxWidth, maxHeight, LocalDensity.current.fontScale)
        val shortViewport = maxHeight < 500.dp
        val sceneHeight = when {
            spacious -> (maxHeight * .39f).coerceIn(310.dp, 420.dp)
            wide && !shortViewport -> 330.dp
            wide -> 210.dp
            shortViewport -> 128.dp
            else -> 180.dp
        }
        Column(
            Modifier.widthIn(max = 1440.dp).fillMaxWidth()
                .then(if (spacious) Modifier else Modifier.fillMaxHeight())
                .familyScrollIndicator(welcomeScroll, trailingOffset = 8.dp)
                .verticalScroll(welcomeScroll)
                .padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            AdaptiveColumns(
                wide,
                first = {
                    Surface(
                        modifier = Modifier.onuWelcomeIntroScrim(sceneHeight),
                        color = Color.Transparent,
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            OnuCompanion(Modifier.fillMaxWidth().height(sceneHeight).testTag("welcome.world"),
                                if (previewState.phase == WelcomePreviewPhase.PLAYING) OnuMood.STORYTELLING else OnuMood.READY,
                                decorative = true, motion = true, narrationActive = previewState.speaking)
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "안녕, 나는 오누야.",
                                    style = (if (spacious) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium).copy(
                                        lineBreak = LineBreak.Heading.copy(wordBreak = LineBreak.WordBreak.Phrase),
                                    ),
                                    color = PlaymateColors.Ink,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    "가는 길에 나랑 소리 퀴즈 풀어 볼래? 단서를 들려줄게.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PlaymateColors.Ink,
                                )
                                OnuWelcomeVoiceControl(previewState, preview::toggle)
                            }
                        }
                    }
                },
                second = {
                    Surface(
                        color = Color.Transparent,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(if (wide) 24.dp else 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // One compact heading keeps the first action visible on short dashboards.
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OnuSetupProgress(1)
                                Text(
                                    "어떻게 불러 줄까요?",
                                    style = (if (spacious) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium).copy(
                                        lineBreak = LineBreak.Heading.copy(wordBreak = LineBreak.WordBreak.Phrase),
                                    ),
                                    modifier = Modifier.semantics { heading() },
                                )
                            }
                            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SelectButton("한 명", !two, { two = false; error = null }, Modifier.weight(1f))
                                SelectButton("두 명", two, { two = true; error = null }, Modifier.weight(1f))
                            }
                            OutlinedTextField(
                                first,
                                {
                                    first = it.take(12)
                                    error = null
                                },
                                label = { Text(if (two) "첫 번째 아이의 별명" else "아이의 별명") },
                                placeholder = { Text("예: 별이") },
                                textStyle = MaterialTheme.typography.bodyLarge,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = if (two) ImeAction.Next else ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onNext = { if (two) secondNicknameFocus.requestFocus() else focusManager.clearFocus() },
                                    onDone = { focusManager.clearFocus() },
                                ),
                                modifier =
                                    Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("welcome.first"),
                            )
                            if (two)
                                OutlinedTextField(
                                    second,
                                    {
                                        second = it.take(12)
                                        error = null
                                    },
                                    label = { Text("두 번째 아이의 별명") },
                                    placeholder = { Text("예: 달이") },
                                    textStyle = MaterialTheme.typography.bodyLarge,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .heightIn(min = 88.dp)
                                            .focusRequester(secondNicknameFocus)
                                            .testTag("welcome.second"),
                                )
                            if (error != null)
                                Text(
                                    error.orEmpty(),
                                    modifier = Modifier.testTag("welcome.saveError")
                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            PrimaryButton(
                                "다음 · 관심사 고르기",
                                {
                                    // Even a validation error must not leave the greeting playing.
                                    preview.stop()
                                    val a = first.trim()
                                    val b = second.trim().takeIf { two }
                                    val problem = FamilyProfile.problem(a, b)
                                    error =
                                        when {
                                            problem != null -> problem
                                            !onSave(a, b) -> "이 기기에 별명을 저장하지 못했어요. 다시 시도해 주세요."
                                            else -> {
                                                focusManager.clearFocus()
                                                null
                                            }
                                        }
                                },
                                enabled = !ui.busy,
                                modifier = Modifier.testTag("welcome.save"),
                            )
                            Text(
                                "별명과 관심사는 이 기기에 암호화해 저장해요.\n보호자 설정에서 수정·삭제할 수 있어요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PlaymateColors.Muted,
                            )
                            Text(
                                "처음 설정은 차가 멈춰 있을 때 보호자와 함께해요.",
                                modifier = Modifier.background(
                                    PlaymateColors.Ivory.copy(alpha = .97f), RoundedCornerShape(8.dp),
                                ).padding(horizontal = 10.dp, vertical = 6.dp),
                                style = if (spacious) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                                color = PlaymateColors.Ink,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun FamilySettings(
    ui: DeveloperUiState,
    wide: Boolean,
    modifier: Modifier,
    permissionProblem: String?,
    requestVoice: () -> Unit,
    onListen: () -> Unit,
    onRead: () -> Unit,
    onSave: (String, String?) -> Boolean,
    onDelete: () -> Unit,
    onAdvanced: () -> Unit,
    onRetry: () -> Unit,
    onLocal: () -> Unit,
    onInterests: () -> Unit,
    onMemory: () -> Unit,
    onNotices: () -> Unit,
    onAppPermissions: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var first by rememberSaveable(ui.firstNickname) { mutableStateOf(ui.firstNickname) }
    var second by rememberSaveable(ui.secondNickname) { mutableStateOf(ui.secondNickname) }
    var two by rememberSaveable(ui.twoPlayers) { mutableStateOf(ui.twoPlayers) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var profileSaveFailed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val settingsScroll = rememberScrollState()
    val secondNicknameFocus = remember { FocusRequester() }
    Column(
        modifier
            .testTag("family.settings")
            .familyScrollIndicator(settingsScroll, trailingOffset = 8.dp)
            .verticalScroll(settingsScroll)
            .padding(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Text(
            "우리 가족에게 맞게",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text("설정 중에는 마이크가 꺼져 있어요. 음성 놀이에서 아이가 대답할 차례에만 켜져요.", color = PlaymateColors.Muted)
        AdaptiveColumns(
            wide,
            first = {
                SettingsSection("함께하는 방식") {
                    InteractionChoices(ui, permissionProblem,
                        { profileSaveFailed = false; requestVoice() },
                        { profileSaveFailed = false; onListen() },
                        { profileSaveFailed = false; onRead() },
                        { profileSaveFailed = false; onRetry() },
                        { profileSaveFailed = false; onAppPermissions() },
                        showFailure = !profileSaveFailed)
                }
            },
            second = {
                SettingsSection("함께할 가족") {
                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SelectButton(
                            "한 명",
                            !two,
                            {
                                two = false
                                savedMessage = null
                            },
                            Modifier.weight(1f),
                        )
                        SelectButton(
                            "두 명",
                            two,
                            {
                                two = true
                                savedMessage = null
                            },
                            Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        first,
                        {
                            first = it.take(12)
                            savedMessage = null
                        },
                        label = { Text("첫 번째 별명") },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = if (two) ImeAction.Next else ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onNext = { if (two) secondNicknameFocus.requestFocus() else focusManager.clearFocus() },
                            onDone = { focusManager.clearFocus() },
                        ),
                        modifier =
                            Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("settings.first"),
                    )
                    if (two)
                        OutlinedTextField(
                            second,
                            {
                                second = it.take(12)
                                savedMessage = null
                            },
                            label = { Text("두 번째 별명") },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier =
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 88.dp)
                                    .focusRequester(secondNicknameFocus)
                                    .testTag("settings.second"),
                        )
                    OutlinedButton(
                        onClick = {
                            profileSaveFailed = !onSave(first.trim(), second.trim().takeIf { two })
                            savedMessage =
                                if (!profileSaveFailed) {
                                    focusManager.clearFocus()
                                    "가족 별명을 저장했어요. 새 모험부터 함께해요."
                                } else null
                        },
                        enabled = !ui.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    ) {
                        Text("가족 별명 저장")
                    }
                    if (savedMessage != null)
                        Text(
                            savedMessage.orEmpty(),
                            modifier = Modifier.testTag("settings.profileSaved")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                            color = PlaymateColors.Leaf,
                        )
                    // Keep the model's validation/storage failure authoritative, in one location.
                    if (profileSaveFailed && ui.failure)
                        Text(
                            ui.message,
                            modifier = Modifier.testTag("settings.profileError")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    Text(
                        "두 명일 때는 이름을 먼저 말해요. 이름은 차례를 정하는 약속이며, 사람의 목소리를 구별하는 기능은 아니에요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PlaymateColors.Muted,
                    )
                }
            },
        )
        SettingsSection("아이의 관심사") {
            ui.children.forEach { child ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(child.nickname, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !child.interestsConfigured -> "아직 고르지 않았어요"
                            child.interests.isEmpty() -> "관심사 선택 없이 이용 중"
                            else ->
                                InterestOptions.all
                                    .filter { it.id in child.interests }
                                    .joinToString(" · ") { it.label }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = PlaymateColors.Muted,
                    )
                }
            }
            Text(
                "아이별로 고른 관심사와 직접 남긴 반응을 참고해 모험을 추천해요.",
                style = MaterialTheme.typography.bodySmall,
                color = PlaymateColors.Muted,
            )
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    profileSaveFailed = false
                    onInterests()
                },
                enabled = !ui.busy,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("settings.interests"),
            ) {
                Text("관심사 바꾸기")
            }
        }
        SettingsSection("우리 가족의 기록") {
            OutlinedButton(
                onClick = { focusManager.clearFocus(); profileSaveFailed = false; onMemory() },
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("settings.memory"),
            ) {
                FamilyIcon(FamilyIcon.MEMORY)
                Spacer(Modifier.width(12.dp))
                Text("지난 이야기 펼치기")
            }
            Text(
                if (ui.useLocal)
                    "별명, 관심사, 직접 남긴 반응과 모험 기록은 이 기기에 암호화해 저장해요. 원음과 말한 문장은 기록으로 남기지 않아요. 같은 기기 사용자 프로필을 쓰는 사람은 앱에서 기록을 볼 수 있어요."
                else
                    "고급 설정의 개발 서버에 연결되어 있어요. 입력한 선택과 인식된 문장은 설정한 서버로 전달돼요. 기기 안에서만 진행하려면 아래에서 바꿔 주세요.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!ui.useLocal)
                OutlinedButton(
                    onClick = { profileSaveFailed = false; onLocal() },
                    enabled = !ui.busy,
                    modifier = Modifier.heightIn(min = 72.dp),
                ) {
                    Text("이 기기에서만 진행하기")
                }
            Text(
                "차량의 주행 제한이 켜지면 모험이 쉬어요. 정차한 뒤 이어갈 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = PlaymateColors.Muted,
            )
            TextButton(
                onClick = {
                    focusManager.clearFocus()
                    confirmDelete = true
                },
                enabled = !ui.busy,
                modifier = Modifier.heightIn(min = 72.dp),
            ) {
                Text("이 기기의 가족과 모험 기록 지우기", color = MaterialTheme.colorScheme.error)
            }
        }
        OutlinedButton(
            onClick = {
                focusManager.clearFocus()
                profileSaveFailed = false
                onNotices()
            },
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("settings.notices"),
        ) {
            Text("오픈소스 고지")
        }
        if (BuildConfig.DEBUG)
            TextButton(onClick = { profileSaveFailed = false; onAdvanced() }, modifier = Modifier.heightIn(min = 72.dp)) {
                Text(
                    "고급 · 개발 연결 및 진단",
                    color = PlaymateColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
    }
    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            modifier = Modifier.testTag("settings.deleteDialog"),
            title = { Text("이 기기의 기록을 지울까요?") },
            text = {
                Text(
                    "가족 별명, 관심사, 직접 남긴 반응과 저장된 모험을 지워요. 지운 기록은 되돌릴 수 없어요.",
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        profileSaveFailed = false
                        onDelete()
                    },
                    enabled = !ui.busy,
                    modifier = Modifier.heightIn(min = 72.dp),
                ) {
                    Text("기록 지우기")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDelete = false },
                    modifier = Modifier.heightIn(min = 72.dp),
                ) {
                    Text("그대로 두기")
                }
            },
        )
}

@Composable
internal fun InteractionSetupScreen(
    ui: DeveloperUiState,
    wide: Boolean,
    permissionProblem: String?,
    requestVoice: () -> Unit,
    onListen: () -> Unit,
    onRead: () -> Unit,
    onRetry: () -> Unit,
    onAppPermissions: () -> Unit,
    onDone: () -> Unit,
) {
    val scroll = rememberScrollState()
    val selectedMode = when {
        !ui.interactionConfigured -> "함께할 방식을 골라 주세요"
        ui.simulation -> "선택한 방식 · 읽으며 체험"
        ui.voiceInput -> "선택한 방식 · 음성으로 함께"
        else -> "선택한 방식 · 듣고 선택하기"
    }
    OnuSetupScene(
        step = 3, title = "어떻게 대답할까요?",
        detail = "놀이를 시작하면 고른 방식으로 오누와 함께해요.",
        wide = wide, modifier = Modifier.testTag("family.interactionSetup"),
    ) { area, spacious ->
        BoxWithConstraints(area) {
            val compactChoices = wide && maxHeight < 500.dp
            LaunchedEffect(compactChoices, ui.interactionConfigured, ui.simulation, ui.voiceInput,
                permissionProblem, ui.failure, ui.voiceInputProblem, ui.speechReady, ui.recoveryCause) {
                if (compactChoices) scroll.scrollTo(0)
            }
            val formSize = if (spacious) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
            Column(formSize, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (compactChoices) CompactInteractionChoices(ui, requestVoice, onListen, onRead)
                Column(Modifier.weight(1f, fill = !spacious).fillMaxWidth()
                    .familyScrollIndicator(scroll, trailingOffset = 6.dp).verticalScroll(scroll)
                    .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (compactChoices) {
                        // Keep every choice visible while longer recovery/help content can scroll.
                        InteractionRecoveryNotice(ui)
                        InteractionAvailability(ui, permissionProblem, onRetry, onAppPermissions)
                        val detail = when {
                            !ui.interactionConfigured -> "원하는 방식을 골라 주세요."
                            ui.simulation -> "소리 없이, 보호자와 읽으며 선택해요."
                            ui.voiceInput -> voiceModeDetail(ui)
                            else -> "이야기를 듣고 화면에서 선택해요."
                        }
                        Text(detail, style = MaterialTheme.typography.bodySmall,
                            color = PlaymateColors.Muted, modifier = Modifier.testTag("interaction.compact.detail"))
                        InteractionListeningNotice(ui)
                    } else {
                        InteractionChoices(ui, permissionProblem, requestVoice, onListen, onRead,
                            onRetry, onAppPermissions)
                    }
                    Text("함께하는 방식은 보호자 설정에서 언제든 바꿀 수 있어요.",
                        style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Muted)
                }
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selectedMode, style = MaterialTheme.typography.labelMedium,
                        color = PlaymateColors.Forest, modifier = Modifier.testTag("interaction.selectedMode"))
                    PrimaryButton("이 방식으로 준비 마치기", onDone,
                        enabled = canFinishInteractionSetup(ui),
                        trailingIcon = FamilyIcon.CHECK,
                        modifier = Modifier.testTag("interaction.done"))
                }
            }
        }
    }
}

internal fun canFinishInteractionSetup(ui: DeveloperUiState): Boolean =
    !ui.busy && ui.interactionConfigured && !ui.failure &&
        (ui.simulation || (ui.speechReady && (!ui.voiceInput || ui.voiceInputProblem == null)))

private fun voiceModeDetail(ui: DeveloperUiState): String =
    if (ui.twoPlayers) "오누의 질문에 말로 답해요. 차례 놀이에서는 별명도 함께 말해요."
    else "오누의 질문을 듣고 말로 답해요."

@Composable
private fun InteractionChoices(
    ui: DeveloperUiState,
    permissionProblem: String?,
    requestVoice: () -> Unit,
    onListen: () -> Unit,
    onRead: () -> Unit,
    onRetry: () -> Unit,
    onAppPermissions: () -> Unit,
    showFailure: Boolean = true,
) {
    InteractionRecoveryNotice(ui)
    ModeOption("음성으로 함께", voiceModeDetail(ui),
        ui.interactionConfigured && ui.voiceInput, !ui.busy, requestVoice)
    ModeOption("듣고 선택하기", "이야기를 듣고 화면에서 선택해요.",
        ui.interactionConfigured && !ui.simulation && !ui.voiceInput, !ui.busy, onListen)
    ModeOption("읽으며 체험", "소리 없이, 보호자와 읽으며 선택해요.",
        ui.interactionConfigured && ui.simulation, !ui.busy, onRead)
    InteractionListeningNotice(ui)
    InteractionAvailability(ui, permissionProblem, onRetry, onAppPermissions, showFailure)
}

@Composable
private fun CompactInteractionChoices(
    ui: DeveloperUiState,
    requestVoice: () -> Unit,
    onListen: () -> Unit,
    onRead: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()
        .testTag("interaction.compactChoices"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactModeOption("voice", "음성으로 함께", ui.interactionConfigured && ui.voiceInput, !ui.busy,
            requestVoice, Modifier.weight(1f).fillMaxHeight())
        CompactModeOption("listen", "듣고 선택하기", ui.interactionConfigured && !ui.simulation && !ui.voiceInput,
            !ui.busy, onListen, Modifier.weight(1f).fillMaxHeight())
        CompactModeOption("read", "읽으며 체험", ui.interactionConfigured && ui.simulation, !ui.busy,
            onRead, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun CompactModeOption(
    id: String,
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) PlaymateColors.Mist else PlaymateColors.Paper,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) PlaymateColors.Forest else PlaymateColors.Line),
        modifier = modifier.heightIn(min = 88.dp).testTag("interaction.compact.$id").selectable(
            selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).testTag("interaction.compact.title.$id"))
        }
    }
}

@Composable
private fun InteractionRecoveryNotice(ui: DeveloperUiState) {
    val recovery = ui.recoveryCause?.notice(ui.snapshot != null || ui.hasSavedSession)
    if (recovery != null) {
        Column(Modifier.fillMaxWidth().testTag("interaction.lastRecovery"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("마지막으로 멈춘 이유", style = MaterialTheme.typography.labelLarge, color = PlaymateColors.Muted)
            Text(recovery.title, style = MaterialTheme.typography.titleMedium)
            Text(recovery.detail, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun InteractionListeningNotice(ui: DeveloperUiState) {
    if (ui.interactionConfigured && ui.voiceInput && !ui.simulation) {
        Text("마이크는 오누의 질문이 끝나고 대답할 차례에만 켜져요.",
            style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Forest)
    }
}

@Composable
private fun InteractionAvailability(
    ui: DeveloperUiState,
    permissionProblem: String?,
    onRetry: () -> Unit,
    onAppPermissions: () -> Unit,
    showFailure: Boolean = true,
) {
    // A deliberately selected quiet/reading mode does not need microphone repairs.
    // An explicit failed voice/permission attempt still appears in permissionProblem.
    val inputUnavailable = ui.voiceInput && ui.voiceInputProblem != null
    val outputUnavailable = !ui.simulation && !ui.speechReady
    val recovery = ui.recoveryCause?.notice(ui.snapshot != null || ui.hasSavedSession)
    if (permissionProblem != null) Notice(permissionProblem)
    if (ui.failure && showFailure) Notice(ui.message)
    if (inputUnavailable)
        Text("음성 입력 · ${ui.voiceInputProblem}", modifier = Modifier.testTag("interaction.inputProblem"),
            style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Muted)
    if (outputUnavailable)
        Text("음성 안내 · ${ui.speechStatus}", modifier = Modifier.testTag("interaction.outputProblem"),
            style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Muted)
    val recoveryNeedsPermissions = ui.recoveryCause in setOf(RecoveryCause.INPUT_PERMISSION, RecoveryCause.OUTPUT_PERMISSION)
    if (inputUnavailable || outputUnavailable || permissionProblem != null || recovery?.settingsLabel != null) {
        OutlinedButton(onClick = onRetry, enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("interaction.retry")) {
            Text("기기 음성 다시 확인")
        }
        if (inputUnavailable || outputUnavailable || permissionProblem != null || recoveryNeedsPermissions) TextButton(onClick = onAppPermissions, enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("interaction.appPermissions")) {
            Text("기기 앱 권한 열기")
        }
        if (inputUnavailable || outputUnavailable || permissionProblem != null || recoveryNeedsPermissions)
            Text("권한 요청이 나오지 않으면 기기 앱 설정에서 권한을 확인해 주세요. 돌아온 뒤에도 직접 시작할 때까지 쉬어요.",
                style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Muted)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        HorizontalDivider(color = PlaymateColors.Line)
        content()
    }
}

@Composable
private fun ModeOption(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) PlaymateColors.Mist else PlaymateColors.Paper,
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) PlaymateColors.Forest else PlaymateColors.Line,
            ),
        modifier =
            Modifier.fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick,
                ),
    ) {
        Row(
            Modifier.padding(14.dp).heightIn(min = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = PlaymateColors.Muted,
                )
            }
        }
    }
}
