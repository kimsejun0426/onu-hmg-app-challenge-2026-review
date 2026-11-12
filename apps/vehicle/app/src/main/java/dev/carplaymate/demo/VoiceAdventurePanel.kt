package dev.carplaymate.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The voice conversation lives in the world, without a separate dashboard or choice cards. */
@Composable
internal fun VoiceAdventurePanel(
    ui: DeveloperUiState,
    art: String,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Boolean = true,
) {
    val snapshot = ui.snapshot ?: return
    var transcript by remember(snapshot.turnId, snapshot.phase) { mutableStateOf(false) }
    var manual by remember(snapshot.turnId, snapshot.phase) { mutableStateOf(false) }
    val canChoose = snapshot.phase == "LISTENING" && !ui.busy && !ui.quiet &&
        snapshot.presentation.optString("input_kind") != "player_name"
    val actions = snapshot.presentation.optJSONArray("actions")
    val choices = if (actions == null) emptyList() else
        List(actions.length()) { actions.getJSONObject(it).getString("label") }
    val spoken = snapshot.speechPlan?.segments?.joinToString("\n\n") { it.text }
        ?: ui.authoredDialogue.ifBlank { snapshot.presentation.optString("prompt") }
    // A visual caption only. The complete authored dialogue remains spoken and available below.
    val caption = spoken.substringAfterLast("\n\n").substringAfterLast(". ")
        .ifBlank { "오누와 함께하는 이야기" }

    BoxWithConstraints(modifier.testTag("family.voice.panel")) {
        if (backdrop) OnuJourneyBackdrop(art, Modifier.fillMaxSize(), activeAdventure = !ui.quiet && snapshot.status == "ACTIVE")
        val landscape = maxWidth >= 640.dp && maxWidth > maxHeight
        if (landscape) {
            val compact = maxHeight < 420.dp
            val statusBesideQuestion = maxHeight >= 475.dp && maxWidth / LocalDensity.current.fontScale >= 820.dp
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(if (compact) .35f else .38f).fillMaxHeight()) {
                    BoxWithConstraints(
                        Modifier.weight(1f).fillMaxWidth().testTag("family.voice.stage"),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val companionSize = minOf(maxHeight, maxWidth * 1.04f, 520.dp)
                        OnuJourneyCharacter(ui, Modifier.size(companionSize))
                    }
                    // On short screens keep the status below the character to preserve question height.
                    if (!statusBesideQuestion) Box(Modifier.fillMaxWidth().heightIn(min = if (compact) 82.dp else 76.dp)
                        .padding(horizontal = if (compact) 8.dp else 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart) {
                        VoiceStatus(ui)
                    }
                }
                VoiceDialogue(
                    ui, caption, spoken, choices, canChoose, transcript, manual,
                    { transcript = !transcript; if (transcript) manual = false },
                    { manual = !manual; if (manual) transcript = false },
                    { manual = false; onChoose(it) },
                    Modifier.weight(if (compact) .65f else .62f).fillMaxHeight()
                        .padding(start = if (compact) 12.dp else 24.dp,
                            end = if (compact) 12.dp else 24.dp,
                            top = if (compact) 8.dp else 20.dp, bottom = 8.dp),
                    showStatus = statusBesideQuestion,
                    compact = compact,
                    centerDialogue = true,
                )
            }
        } else {
            // Give a short portrait conversation a visible companion. Larger type and
            // expanded assistance retain more room for the full dialogue and its controls.
            val sceneShare = if (maxHeight < 480.dp || transcript || manual) .26f
                else (.44f / LocalDensity.current.fontScale.coerceAtLeast(1f)).coerceIn(.26f, .40f)
            Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    Modifier.fillMaxWidth().weight(sceneShare).testTag("family.voice.stage"),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    val companionSize = minOf(maxHeight, maxWidth * .72f, 440.dp)
                    OnuJourneyCharacter(ui, Modifier.size(companionSize))
                }
                VoiceDialogue(
                    ui, caption, spoken, choices, canChoose, transcript, manual,
                    { transcript = !transcript; if (transcript) manual = false },
                    { manual = !manual; if (manual) transcript = false },
                    { manual = false; onChoose(it) },
                    Modifier.fillMaxWidth().weight(1f - sceneShare)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun VoiceDialogue(
    ui: DeveloperUiState,
    caption: String,
    spoken: String,
    choices: List<String>,
    canChoose: Boolean,
    transcript: Boolean,
    manual: Boolean,
    onTranscript: () -> Unit,
    onManual: () -> Unit,
    onChoose: (String) -> Unit,
    modifier: Modifier,
    showStatus: Boolean = true,
    compact: Boolean = false,
    centerDialogue: Boolean = false,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(ui.snapshot?.sessionId, ui.snapshot?.turnId, ui.snapshot?.speechPlan?.playbackId) {
        scroll.scrollTo(0)
    }
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp,
            if (centerDialogue) Alignment.CenterVertically else Alignment.Top),
    ) {
        if (showStatus) VoiceStatus(ui)
        // Natural height joins a short question to its answers. Longer dialogue can scroll
        // without moving the speech examples or the explicit assistance controls off screen.
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth()
                    .familyScrollIndicator(scroll, trailingOffset = 8.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (transcript) {
                    Text(spoken, style = MaterialTheme.typography.bodyLarge, color = PlaymateColors.Ink,
                        modifier = Modifier.testTag("family.voice.transcriptText"))
                } else {
                    Text(caption, style = MaterialTheme.typography.headlineSmall,
                        color = PlaymateColors.DeepForest,
                        modifier = Modifier.testTag("family.voice.question").semantics { heading() })
                }
                if (manual && canChoose) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (ui.microphoneListening) "목소리 입력도 켜져 있어요. 쉬려면 ‘잠깐 쉬기’를 눌러 주세요."
                        else "화면으로 선택을 도와줄 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PlaymateColors.Muted,
                    )
                    choices.forEachIndexed { index, label ->
                        OutlinedButton(
                            onClick = { onChoose(label) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                                .testTag("family.voice.assist.$index"),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, PlaymateColors.Line),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = PlaymateColors.Paper.copy(alpha = .88f),
                                contentColor = PlaymateColors.DeepForest,
                            ),
                        ) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            // An unboxed spoken example is a suggestion to say, never an inactive button.
            if (canChoose && !manual && choices.isNotEmpty()) {
                val snapshot = ui.snapshot
                val name = snapshot?.takeIf {
                    (it.game.optJSONArray("players")?.length() ?: 0) > 1
                }?.let { "${currentPlayer(it).first}, " }.orEmpty()
                Text(
                    choices.joinToString(" 또는 ") { "“$name$it”" },
                    modifier = Modifier.fillMaxWidth().testTag("family.voice.examples"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlaymateColors.DeepForest,
                )
            }
        }
        // A single support row belongs to the dialogue, rather than separate dashboard cards.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                onClick = onTranscript,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                    .testTag("family.voice.transcript")
                    .semantics { stateDescription = if (transcript) "펼쳐짐" else "접힘" },
            ) {
                Text(if (transcript) "대사 접기" else "대사 보기",
                    Modifier.weight(1f, fill = false), textAlign = TextAlign.Center)
                Spacer(Modifier.width(6.dp))
                FamilyIcon(FamilyIcon.NEXT, Modifier.size(18.dp).rotate(if (transcript) -90f else 90f),
                    tint = LocalContentColor.current)
            }
            if (canChoose) TextButton(
                onClick = onManual,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                    .testTag("family.voice.manual")
                    .semantics { stateDescription = if (manual) "펼쳐짐" else "접힘" },
            ) {
                Text(if (manual) "선택 접기" else "화면으로 선택",
                    Modifier.weight(1f, fill = false), textAlign = TextAlign.Center)
                Spacer(Modifier.width(6.dp))
                FamilyIcon(FamilyIcon.NEXT, Modifier.size(18.dp).rotate(if (manual) -90f else 90f),
                    tint = LocalContentColor.current)
            }
        }
    }
}

@Composable
private fun VoiceStatus(ui: DeveloperUiState) {
    val icon = when {
        ui.quiet -> FamilyIcon.PAUSE
        ui.microphoneListening -> FamilyIcon.MIC
        ui.outputStage == OutputStage.STARTED -> FamilyIcon.SOUND
        else -> FamilyIcon.SPARK
    }
    // Static, explicitly reported status. No ambient waveform or assumed listening activity.
    Row(Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(12.dp),
            color = if (ui.microphoneListening && !ui.quiet) PlaymateColors.Forest else PlaymateColors.Mist) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                FamilyIcon(icon, Modifier.size(24.dp),
                    tint = if (ui.microphoneListening && !ui.quiet) PlaymateColors.Paper else PlaymateColors.Forest)
            }
        }
        Text(
            if (ui.snapshot?.phase == "LISTENING") ui.inputStatus
            else if (ui.busy) "다음 장면을 준비하고 있어요"
            else if (ui.outputStage == OutputStage.STARTED) "오누가 이야기를 들려줘요"
            else if (ui.outputStage == OutputStage.PREPARING) "오누가 목소리를 준비하고 있어요"
            else "다음 장면을 준비하고 있어요",
            style = MaterialTheme.typography.labelMedium,
            color = PlaymateColors.Forest,
            modifier = Modifier.weight(1f).testTag("family.voice.status"),
        )
    }
}

/** Compatibility for existing reading previews: the place has no independent card frame. */
@Composable
internal fun VoiceCompanionStage(ui: DeveloperUiState, art: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.testTag("family.voice.stage"), contentAlignment = Alignment.BottomCenter) {
        OnuJourneyBackdrop(art, Modifier.fillMaxSize(), bottomScrim = false)
        val companionSize = minOf(maxHeight, maxWidth * .94f, 520.dp)
        OnuJourneyCharacter(ui, Modifier.size(companionSize))
    }
}
