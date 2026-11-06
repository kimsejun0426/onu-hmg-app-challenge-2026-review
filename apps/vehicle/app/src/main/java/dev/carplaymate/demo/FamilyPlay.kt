package dev.carplaymate.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

@Composable
internal fun ImmersiveAdventure(
    ui: DeveloperUiState,
    wide: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onChoose: (String) -> Unit,
    onReadDone: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    onFeedback: (String, String, AdventureFeedback?) -> Unit,
    backdrop: Boolean = true,
) {
    val snapshot = ui.snapshot
    val newStart = ui.newAdventureStart != null
    val closed = !newStart && snapshot?.status == "ENDED"
    val ended = closed && snapshot?.game?.optBoolean("finished") == true
    val card = ui.adventures.firstOrNull { it.packId == snapshot?.game?.optString("pack_id") }
    val art = snapshot?.presentation?.optString("art_key")?.ifBlank { null } ?: card?.artKey ?: "dinosaurs"
    val reading = !newStart && ui.simulation && snapshot?.speechPlan != null && !ui.quiet
    Box(Modifier.fillMaxSize().testTag("family.play")) {
        if (backdrop) OnuJourneyBackdrop(art, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            if (!ui.voiceInput || ui.simulation || ui.quiet)
                FamilyHeader("play", wide, onHome, onSettings)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    ui.newAdventureStart == NewAdventureStart.PREPARING -> NewAdventurePreparation()
                    newStart -> CalmPause(ui, wide, onHome, onSettings, onHome, newStart = true)
                    ended -> AdventureEnding(ui, wide, art, onHome, onFeedback)
                    closed -> CalmPause(ui, wide, onHome, onSettings, onHome, finishedEarly = true)
                    ui.quiet && !ui.busy -> CalmPause(ui, wide, onResume, onSettings, onHome)
                    snapshot == null -> Column(Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        OnuCompanion(Modifier.size(192.dp), OnuMood.THINKING)
                        Text("우리의 이야기를 준비하고 있어요.", style = MaterialTheme.typography.titleLarge)
                    }
                    else -> Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 28.dp else 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AdventureProgress(ui, card, onChoose)
                        if (ui.voiceInput && !ui.simulation)
                            VoiceAdventurePanel(ui, art, onChoose, Modifier.weight(1f).fillMaxWidth(), backdrop = false)
                        else ReadingJourney(ui, wide, onChoose, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
            // Ordinary, settled rest already explains its state beside the explicit resume
            // action. Keep the stop/control area during preparation and every recovery case.
            val settledRest = ui.quiet && !ui.busy && !newStart && !ui.failure &&
                ui.recoveryCause == null && (ui.simulation || ui.speechReady) &&
                (ui.simulation || !ui.voiceInput || ui.voiceInputProblem == null)
            if (!closed && !settledRest) JourneyControls(ui, wide, reading, onPause, onReadDone)
        }
    }
}

/** One scene owns the companion, authored dialogue and the available next action. */
@Composable
private fun ReadingJourney(ui: DeveloperUiState, wide: Boolean, onChoose: (String) -> Unit, modifier: Modifier) {
    val snapshot = ui.snapshot ?: return
    val listening = snapshot.phase == "LISTENING" && !ui.busy && !ui.quiet
    BoxWithConstraints(modifier) {
        val companionHeight = (maxHeight * .25f).coerceIn(72.dp, 200.dp)
        val dialogue: @Composable ColumnScope.() -> Unit = {
            StoryPanel(ui, "", Modifier.weight(1f, fill = false).fillMaxWidth())
            if (listening) AdventureChoices(snapshot, onChoose, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            OnuJourneyCharacter(ui, Modifier.weight(.36f).fillMaxHeight())
            Column(Modifier.weight(.64f).fillMaxHeight(), verticalArrangement = Arrangement.Center, content = dialogue)
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OnuJourneyCharacter(ui, Modifier.fillMaxWidth().height(companionHeight))
            dialogue()
        }
    }
}

/** Large hit areas remain available without turning the pause action into the visual headline. */
@Composable
private fun JourneyControls(ui: DeveloperUiState, wide: Boolean, reading: Boolean,
                            onPause: () -> Unit, onReadDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = if (wide) 28.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (ui.quiet && ui.newAdventureStart != NewAdventureStart.PREPARING)
            Row(Modifier.weight(1f).heightIn(min = 80.dp).testTag("family.pauseStatus"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                FamilyIcon(FamilyIcon.PAUSE, tint = PlaymateColors.Muted)
                Spacer(Modifier.width(12.dp))
                Text("쉬고 있어요", style = MaterialTheme.typography.labelLarge, color = PlaymateColors.Muted)
            }
        else OutlinedButton(onClick = onPause,
            modifier = Modifier.weight(if (reading) .85f else 1f).heightIn(min = 80.dp).testTag("family.pause"),
            shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, PlaymateColors.Forest.copy(alpha = .28f)),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = PlaymateColors.Ivory.copy(alpha = .64f),
                contentColor = PlaymateColors.Forest), contentPadding = PaddingValues(14.dp)) {
            FamilyIcon(FamilyIcon.PAUSE)
            Spacer(Modifier.width(12.dp))
            Text("잠깐 쉬기", style = MaterialTheme.typography.labelLarge)
        }
        if (reading) Button(onClick = onReadDone, enabled = !ui.busy,
            modifier = Modifier.weight(if (wide) 1.5f else 1.35f).heightIn(min = 80.dp).testTag("family.readDone"),
            shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PlaymateColors.Forest, contentColor = PlaymateColors.Paper)) {
            Text("다 읽었어요 · 다음으로", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (wide) FamilyIcon(FamilyIcon.NEXT, tint = PlaymateColors.Paper)
        } else if (wide) Text(if (ui.quiet) "내가 준비됐을 때, 다시 함께."
            else if (ui.microphoneListening) "짧게 대답해도 괜찮아요." else "언제든 쉬어도 괜찮아요.",
            Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Muted)
    }
}

@Composable
private fun NewAdventurePreparation() {
    Column(
        Modifier.fillMaxSize().testTag("family.newAdventure.preparing")
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        OnuCompanion(Modifier.size(192.dp), OnuMood.THINKING)
        Text("새 이야기를 준비하고 있어요", style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center)
        Text("아직 이야기를 시작하지 않았어요. 잠깐 쉬어도 괜찮아요.",
            style = MaterialTheme.typography.bodyMedium, color = PlaymateColors.Muted,
            textAlign = TextAlign.Center)
    }
}

@Composable
private fun AdventureProgress(
    ui: DeveloperUiState,
    card: AdventureCard?,
    onChoose: (String) -> Unit,
) {
    val snapshot = ui.snapshot ?: return
    val total =
        snapshot.presentation.optInt("total_choices", card?.totalChoices ?: 1).coerceAtLeast(1)
    val turn = snapshot.game.optInt("turn_index").coerceIn(0, total)
    var help by remember(snapshot.turnId) { mutableStateOf(false) }
    val canAsk = snapshot.phase == "LISTENING" && !ui.busy && !ui.quiet
    val hintPenalty = snapshot.presentation.optInt("hint_score_penalty")
    val turnTitle = if (snapshot.game.optBoolean("finished")) "함께 만든 마지막 장면"
        else "${currentPlayer(snapshot).first}의 차례예요"
    val titleStyle = if (ui.voiceInput) MaterialTheme.typography.titleMedium
        else MaterialTheme.typography.headlineMedium
    val buttonStyle = MaterialTheme.typography.labelLarge
    val repeatLabel = if (ui.simulation) "다시 읽기" else "다시 듣기"
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val title: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            turnTitle,
            modifier.testTag("family.turnTitle").semantics { heading() },
            style = titleStyle,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val assistance: @Composable RowScope.() -> Unit = {
        if (hintPenalty == 0) {
            TextButton(
                onClick = { onChoose("다시") },
                enabled = canAsk,
                modifier = Modifier.heightIn(min = 64.dp).testTag("family.repeat"),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(repeatLabel, style = buttonStyle)
            }
            OutlinedButton(
                onClick = { onChoose("힌트") },
                enabled = canAsk,
                modifier = Modifier.heightIn(min = 64.dp).testTag("family.help"),
                contentPadding = PaddingValues(horizontal = 20.dp),
                shape = RoundedCornerShape(22.dp),
            ) {
                Text("도와줘", style = buttonStyle)
            }
        } else
            Box {
                TextButton(
                    onClick = { help = true },
                    enabled = canAsk,
                    modifier = Modifier.heightIn(min = 64.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("도움", style = buttonStyle)
                }
                DropdownMenu(expanded = help, onDismissRequest = { help = false }) {
                    DropdownMenuItem(
                        text = { Text(if (ui.simulation) "다시 읽기" else "다시 들려줘") },
                        onClick = {
                            help = false
                            onChoose("다시")
                        },
                        modifier = Modifier.heightIn(min = 72.dp),
                    )
                    if (hintPenalty > 0)
                        Text(
                            "힌트를 쓰면 이번 선택에서 얻는 점수가 최대 ${hintPenalty}점 줄어요.",
                            modifier = Modifier.widthIn(max = 340.dp).padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = PlaymateColors.Muted,
                        )
                    DropdownMenuItem(
                        text = { Text("힌트가 필요해") },
                        onClick = {
                            help = false
                            onChoose("힌트")
                        },
                        modifier = Modifier.heightIn(min = 72.dp),
                    )
                }
            }
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Preserve the whole child name at every font size. Voice uses one calm
            // shared line when it fits; longer names get the full width above assistance.
            // Reading keeps its existing two-line allowance before separating the rows.
            val controlsWidth = if (hintPenalty == 0) {
                textMeasurer.measure(repeatLabel, style = buttonStyle).size.width +
                    textMeasurer.measure("도와줘", style = buttonStyle).size.width +
                    with(density) { (24.dp + 40.dp + 12.dp).roundToPx() }
            } else {
                textMeasurer.measure("도움", style = buttonStyle).size.width +
                    with(density) { 24.dp.roundToPx() }
            }
            val availableTitleWidth = (constraints.maxWidth - controlsWidth -
                with(density) { 12.dp.roundToPx() }).coerceAtLeast(1)
            val separateTitle = textMeasurer.measure(
                turnTitle, style = titleStyle,
                constraints = Constraints(maxWidth = availableTitleWidth),
            ).lineCount > (if (ui.voiceInput) 1 else 2)
            if (separateTitle) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    title(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        content = assistance)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    title(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = assistance)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                snapshot.presentation.optString("scene_title").ifBlank { card?.title ?: "우리의 모험" },
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = PlaymateColors.Leaf,
            )
            Surface(color = PlaymateColors.Mist, shape = RoundedCornerShape(10.dp)) {
                Text(
                    "$turn / $total",
                    modifier = Modifier.padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = PlaymateColors.Forest,
                )
            }
        }
        LinearProgressIndicator(
            progress = { turn.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
            color = PlaymateColors.Leaf,
            trackColor = PlaymateColors.Sage,
            // The default far-end marker looks like progress before the first choice.
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun StoryPanel(ui: DeveloperUiState, art: String, modifier: Modifier) {
    val snapshot = ui.snapshot ?: return
    val scroll = rememberScrollState()
    var readMore by remember(snapshot.turnId, snapshot.phase) { mutableIntStateOf(0) }
    LaunchedEffect(snapshot.turnId, snapshot.phase, readMore) {
        if (readMore == 0) scroll.scrollTo(0)
        else
            scroll.animateScrollTo(
                (scroll.value + scroll.viewportSize).coerceAtMost(scroll.maxValue)
            )
    }
    val text =
        snapshot.speechPlan?.segments?.joinToString("\n\n") { it.text }
            ?: snapshot.presentation.optString("prompt")
    Surface(modifier, color = Color.Transparent) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxHeight < 280.dp
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp)) {
                SpeechPresence(ui, compact)
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .testTag("family.storyScroll"),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(
                        text.ifBlank { "다음 이야기를 준비하고 있어요." },
                        style = MaterialTheme.typography.bodyLarge,
                        color = PlaymateColors.Ink,
                        modifier = Modifier.testTag("family.storyText"),
                    )
                    if (ui.simulation)
                        Text(
                            "읽으며 체험 · 소리를 재생하지 않아요",
                            style = MaterialTheme.typography.bodySmall,
                            color = PlaymateColors.Muted,
                        )
                }
                if (scroll.canScrollForward)
                    TextButton(
                        onClick = { readMore++ },
                        modifier =
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("family.readMore"),
                    ) {
                        Text("아래로 내려 더 읽기 ↓", style = MaterialTheme.typography.labelMedium)
                    }
            }
        }
    }
}

@Composable
private fun WorldStage(ui: DeveloperUiState, art: String, modifier: Modifier) {
    VoiceCompanionStage(ui, art, modifier)
}

@Composable
private fun SpeechPresence(ui: DeveloperUiState, compact: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when {
                ui.busy -> "다음 장면을 준비하고 있어요"
                ui.microphoneListening -> "지금 듣고 있어요"
                ui.snapshot?.phase == "LISTENING" && ui.voiceInput -> "목소리를 받을 준비 중이에요"
                ui.snapshot?.phase == "LISTENING" -> "우리의 선택을 기다리고 있어요"
                ui.simulation -> "함께 읽는 이야기"
                ui.outputStage == OutputStage.STARTED -> "오누가 이야기를 들려줘요"
                ui.outputStage == OutputStage.PREPARING -> "오누가 목소리를 준비하고 있어요"
                else -> "다음 장면을 준비하고 있어요"
            },
            style = MaterialTheme.typography.labelMedium,
            color = PlaymateColors.Forest,
        )
    }
}

@Composable
private fun AdventureChoices(snapshot: Snapshot, onChoose: (String) -> Unit, modifier: Modifier) {
    val array = snapshot.presentation.optJSONArray("actions")
    val choices =
        if (array == null) emptyList()
        else List(array.length()) { array.getJSONObject(it).getString("label") }
    val scroll = rememberScrollState()
    LaunchedEffect(snapshot.turnId) { scroll.scrollTo(0) }
    Column(modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        choices.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { column, label ->
                    OutlinedButton(
                        onClick = { onChoose(label) },
                        modifier =
                            Modifier.weight(1f)
                                .heightIn(min = 80.dp)
                                .testTag("family.choice.${row*2+column}"),
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, PlaymateColors.Leaf.copy(alpha = .45f)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = PlaymateColors.OnuCream.copy(alpha = .9f),
                                contentColor = PlaymateColors.Forest,
                            ),
                    ) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CalmPause(
    ui: DeveloperUiState,
    wide: Boolean,
    onResume: () -> Unit,
    onSettings: () -> Unit,
    onHome: () -> Unit,
    finishedEarly: Boolean = false,
    newStart: Boolean = false,
) {
    val hasResumeTarget = !newStart && (ui.snapshot != null || ui.hasSavedSession)
    val recovery = if (finishedEarly || (newStart && !ui.failure)) null else ui.recoveryCause?.notice(hasResumeTarget)
    val outputUnavailable = !finishedEarly && !ui.speechReady && !ui.simulation
    val outputNotice = (ui.speechProblem ?: RecoveryCause.OUTPUT_UNAVAILABLE).notice()
    val returnHome = finishedEarly || !hasResumeTarget || recovery?.returnHome == true
    // Current capability chooses the action. A past interruption must not block a repaired mode.
    val inputUnavailable = !finishedEarly && !ui.simulation && ui.voiceInput && ui.voiceInputProblem != null
    val settingsPrimary = !returnHome && (inputUnavailable || outputUnavailable)
    val settingsLabel = when {
        inputUnavailable && outputUnavailable -> "이용 방식 확인하기"
        inputUnavailable -> "음성 입력 설정 확인하기"
        outputUnavailable -> "음성 안내 설정 확인하기"
        else -> recovery?.settingsLabel ?: "이용 방식 확인하기"
    }
    // Ordinary rest is still part of the journey. Recovery keeps its explicit current-capability
    // actions below; a decorative state must never imply that input or output has resumed.
    if (recovery == null && !ui.failure && !inputUnavailable && !outputUnavailable && !newStart) {
        JourneyReflection(ui, wide, "family.quiet") {
            Eyebrow(if (finishedEarly) "오늘 함께한 시간" else "우리 이야기, 잠깐 쉬는 중")
            Text(if (finishedEarly) "오늘은 여기까지" else "잠깐, 쉬어가요",
                modifier = Modifier.testTag("family.quiet.title"),
                style = MaterialTheme.typography.headlineLarge)
            Text(if (finishedEarly) "오늘 함께한 시간, 고마워요. 다음 놀이에서 다시 만나요."
                else "여기까지 함께 고른 길은 남아 있어요.\n준비되면, 여기서 이어가요.",
                modifier = Modifier.testTag("family.quiet.reason"),
                style = MaterialTheme.typography.bodyLarge, color = PlaymateColors.Muted)
            PrimaryButton(if (returnHome) "우리 가족 홈으로" else "준비됐어요 · 이어하기",
                if (returnHome) onHome else onResume,
                enabled = !ui.busy && (returnHome || ui.speechReady || ui.simulation),
                modifier = Modifier.testTag("family.quiet.resume"),
                trailingIcon = if (returnHome) FamilyIcon.HOME else FamilyIcon.NEXT)
        }
        return
    }
    Column(
        Modifier.fillMaxSize()
            .testTag("family.quiet")
            .background(PlaymateColors.Ivory.copy(alpha = .92f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (wide) 32.dp else 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val title: @Composable (Modifier) -> Unit = { modifier ->
            Text(
                recovery?.title ?: if (newStart) "새 모험은 아직 시작 전이에요"
                else if (ui.failure) "다시 준비할게요" else "잠깐, 쉬어가요",
                modifier.testTag("family.quiet.title"),
                style = MaterialTheme.typography.headlineLarge,
            )
        }
        if (recovery != null) {
            Eyebrow("마지막으로 멈춘 이유")
            title(Modifier.fillMaxWidth())
        } else if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Surface(color = PlaymateColors.OnuCream, shape = CircleShape) {
                    OnuPortrait(Modifier.size(88.dp), OnuMood.RESTING)
                }
                title(Modifier.weight(1f))
            }
        } else {
            // A portrait must not take width away from a large Korean recovery heading.
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Surface(color = PlaymateColors.OnuCream, shape = CircleShape) {
                    OnuPortrait(Modifier.size(88.dp), OnuMood.RESTING)
                }
                if (recovery != null) Eyebrow("마지막으로 멈춘 이유")
            }
            title(Modifier.fillMaxWidth())
        }
        if (inputUnavailable) Text(
            "지금은 음성으로 대답을 받을 수 없어요.",
            modifier = Modifier.widthIn(max = 840.dp).testTag("family.quiet.inputProblem"),
            style = MaterialTheme.typography.bodyLarge,
        )
        PrimaryButton(
            when {
                returnHome -> "우리 가족 홈으로"
                settingsPrimary -> settingsLabel
                else -> "준비됐어요 · 이어하기"
            },
            when {
                returnHome -> onHome
                settingsPrimary -> onSettings
                else -> onResume
            },
            !ui.busy && (returnHome || settingsPrimary || ui.speechReady || ui.simulation),
            Modifier.widthIn(max = 680.dp)
                .testTag(if (settingsPrimary) "family.quiet.settings" else "family.quiet.resume"),
            trailingIcon = if (settingsPrimary) FamilyIcon.FAMILY else if (returnHome) FamilyIcon.HOME else FamilyIcon.NEXT,
        )
        Text(
            if (newStart) "새 이야기는 아직 시작 전이에요. 홈에서 이어갈 이야기나 새 모험을 골라 주세요."
            else recovery?.detail ?: if (ui.failure) "진행을 확인한 뒤, 직접 이어갈 수 있어요."
            else if (finishedEarly) "오늘 함께한 시간, 고마워요. 다음 놀이에서 다시 만나요."
            else "여기까지 함께 고른 길은 남아 있어요.\n준비되면, 여기서 이어가요.",
            modifier = Modifier.widthIn(max = 840.dp).testTag("family.quiet.reason"),
            style = MaterialTheme.typography.bodyLarge,
            color = PlaymateColors.Muted,
        )
        if (outputUnavailable) Text(
            "지금은 음성 안내를 사용할 수 없어요. ${outputNotice.detail}",
            modifier = Modifier.widthIn(max = 840.dp).testTag("family.quiet.outputProblem"),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!finishedEarly && !settingsPrimary &&
            (inputUnavailable || outputUnavailable || recovery?.settingsLabel != null || (recovery == null && ui.failure)))
            TextButton(onClick = onSettings, enabled = !ui.busy,
                modifier = Modifier.heightIn(min = 72.dp).testTag("family.quiet.settings")) {
                Text(settingsLabel)
            }
    }
}

@Composable
private fun AdventureEnding(
    ui: DeveloperUiState,
    wide: Boolean,
    art: String,
    onHome: () -> Unit,
    onFeedback: (String, String, AdventureFeedback?) -> Unit,
) {
    val snapshot = ui.snapshot ?: return
    val memory = ui.journal.firstOrNull { it.sessionId == snapshot.sessionId }
    var feedbackOpen by rememberSaveable(snapshot.sessionId) { mutableStateOf(false) }
    JourneyReflection(ui, wide, "family.ending", mood = OnuMood.CELEBRATING) {
        Eyebrow("오늘 함께 만든 이야기")
        Text(snapshot.presentation.optString("ending_title").ifBlank { "함께 만든 발견" },
            style = MaterialTheme.typography.headlineLarge)
        Text(snapshot.presentation.optString("ending_summary").ifBlank {
            "우리가 고른 길이 하나의 이야기가 되었어요."
        }, style = MaterialTheme.typography.bodyLarge)
        PrimaryButton("오늘은 여기까지", onHome, modifier = Modifier.testTag("family.ending.home"),
            trailingIcon = FamilyIcon.CHECK)
        val participants = memory?.participantProfiles.orEmpty().filter { person ->
            person.profileId != null && ui.children.any { it.id == person.profileId }
        }
        if (participants.isNotEmpty()) {
            TextButton(onClick = { feedbackOpen = !feedbackOpen },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .testTag("family.ending.feedbackToggle")
                    .semantics { stateDescription = if (feedbackOpen) "펼쳐짐" else "접힘" }) {
                Text(if (feedbackOpen) "취향 남기기 접기" else "다음 이야기 취향 남기기")
            }
            if (feedbackOpen) {
                Text("다음 이야기를 고를 때 참고해요. 남기지 않아도 괜찮아요.",
                    style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Muted)
                participants.forEach { person ->
                    val id = requireNotNull(person.profileId)
                    val feedback = ui.adventureFeedback
                        .firstOrNull { it.sessionId == snapshot.sessionId && it.childId == id }?.feedback
                    Text(person.nickname, style = MaterialTheme.typography.titleMedium)
                    ActionPair(
                        first = { slot ->
                            FeedbackButton("좋았어요", feedback == AdventureFeedback.LIKED,
                                !ui.feedbackSaving, slot.testTag("feedback.$id.liked")) {
                                onFeedback(snapshot.sessionId, id,
                                    if (feedback == AdventureFeedback.LIKED) null else AdventureFeedback.LIKED)
                            }
                        },
                        second = { slot ->
                            FeedbackButton("다음엔 다른 주제", feedback == AdventureFeedback.TRY_ANOTHER_TOPIC,
                                !ui.feedbackSaving, slot.testTag("feedback.$id.another")) {
                                onFeedback(snapshot.sessionId, id,
                                    if (feedback == AdventureFeedback.TRY_ANOTHER_TOPIC) null else AdventureFeedback.TRY_ANOTHER_TOPIC)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Keep the companion, result and explicit next action together, including long summaries. */
@Composable
private fun JourneyReflection(
    ui: DeveloperUiState,
    wide: Boolean,
    tag: String,
    mood: OnuMood = onuMood(ui),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize().testTag(tag)
        .padding(horizontal = if (wide) 28.dp else 20.dp, vertical = 12.dp)) {
        val portraitHeight = (maxHeight * .36f).coerceIn(104.dp, 260.dp)
        val dialogue: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.Center) {
                Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                    .familyScrollIndicator(scroll).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OnuJourneyCharacter(ui, Modifier.weight(.38f).fillMaxHeight().padding(16.dp), mood = mood)
            dialogue(Modifier.weight(.62f).fillMaxHeight().padding(horizontal = 16.dp))
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OnuJourneyCharacter(ui, Modifier.fillMaxWidth().height(portraitHeight), mood = mood)
            dialogue(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun FeedbackButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 80.dp).semantics { this.selected = selected },
        shape = RoundedCornerShape(22.dp),
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) PlaymateColors.Forest else PlaymateColors.Line,
            ),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = if (selected) PlaymateColors.Mist else PlaymateColors.Paper
            ),
    ) {
        if (selected) {
            FamilyIcon(FamilyIcon.CHECK)
            Spacer(Modifier.width(10.dp))
        }
        Text(label)
    }
}
