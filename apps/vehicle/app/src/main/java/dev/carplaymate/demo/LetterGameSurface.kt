package dev.carplaymate.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun LetterGameSurface(
    title: String,
    line: String,
    precedingClues: String,
    status: String,
    statusIcon: FamilyIcon?,
    scrollKey: String,
    stage: Int,
    choices: List<JumpChoice>,
    onChoice: (Int) -> Unit,
    onBack: () -> Unit,
    onPauseOrResume: () -> Unit,
    paused: Boolean,
    complete: Boolean,
    showChoices: Boolean,
    onReplay: () -> Unit,
    canReplay: Boolean,
    onShowChoices: () -> Unit,
    canShowChoices: Boolean,
    listening: Boolean = false,
    speaking: Boolean = false,
    processing: Boolean = false,
    soundPlaying: Boolean = false,
    rainLetter: Boolean = false,
    answerLabel: String? = null,
    awaitingAnswer: Boolean = false,
    groupVoiceHelp: Boolean = false,
) {
    val sceneArt = if (rainLetter) R.drawable.onu_letter_rain else R.drawable.onu_letter_shore
    val mood = when {
        paused -> OnuMood.RESTING
        listening -> OnuMood.LISTENING
        speaking -> OnuMood.STORYTELLING
        soundPlaying -> OnuMood.THINKING
        processing -> OnuMood.THINKING
        complete -> OnuMood.CELEBRATING
        awaitingAnswer -> OnuMood.THINKING
        else -> OnuMood.READY
    }
    val scroll = rememberScrollState()
    var moreActions by remember(scrollKey, complete, paused) { mutableStateOf(false) }
    var helpOpen by remember(scrollKey, groupVoiceHelp, paused) { mutableStateOf(false) }
    LaunchedEffect(scrollKey, paused) { scroll.scrollTo(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(PlaymateColors.Ivory).testTag("letter.surface")) {
        val fontScale = LocalDensity.current.fontScale
        val spacious = maxWidth >= 760.dp && maxWidth / fontScale >= 680.dp
        val inlineChapter = maxWidth / fontScale >= 650.dp
        val iconOnlyNav = maxWidth / fontScale < 340.dp
        val gutter = if (spacious) 28.dp else 14.dp
        val heroHeight = (maxHeight * .23f).coerceIn(76.dp, 210.dp)
        val questionTop = ((maxHeight - 460.dp * fontScale) * .26f).coerceIn(4.dp, 180.dp)
        val waitingForAnswer = awaitingAnswer && !paused && !complete
        val bubble = RoundedCornerShape(topStart = 16.dp, topEnd = 32.dp, bottomStart = 32.dp, bottomEnd = 32.dp)
        Image(painterResource(sceneArt), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // Keep the illustration visible behind the conversation. Text sits on opaque surfaces.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Transparent, Color.Transparent, PlaymateColors.DeepForest.copy(alpha = .16f),
        ))))
        val dialogue: @Composable ColumnScope.() -> Unit = {
            if (!inlineChapter) {
                LetterProgress(stage, rainLetter)
                Spacer(Modifier.height(10.dp))
            }
            val edge = if (spacious) 14.dp else 0.dp
            Column(Modifier.fillMaxWidth().padding(start = edge).drawBehind {
                if (spacious) {
                    val pointer = Path().apply {
                        moveTo(1.dp.toPx(), 30.dp.toPx())
                        lineTo(-edge.toPx(), 36.dp.toPx())
                        lineTo(1.dp.toPx(), 53.dp.toPx())
                        close()
                    }
                    drawPath(pointer, PlaymateColors.Paper)
                }
            }.background(PlaymateColors.Paper, bubble)
                .padding(horizontal = if (spacious) 28.dp else 20.dp, vertical = if (spacious) 24.dp else 16.dp)) {
                if (status.isNotBlank() && !waitingForAnswer) {
                    LetterStatus(status, statusIcon, speaking, onDark = false)
                } else if (waitingForAnswer) {
                    Text("오누", style = MaterialTheme.typography.labelMedium, color = PlaymateColors.Forest)
                }
                if (precedingClues.isNotBlank()) Text(
                    precedingClues, Modifier.padding(top = 8.dp).testTag("letter.clues"),
                    style = MaterialTheme.typography.bodyMedium, color = PlaymateColors.Ink,
                )
                Text(
                    title, Modifier.padding(top = 6.dp).semantics { heading() }.testTag("letter.title"),
                    style = MaterialTheme.typography.headlineLarge,
                    fontSize = if (spacious) 42.sp else 32.sp, lineHeight = if (spacious) 56.sp else 44.sp,
                    color = PlaymateColors.DeepForest,
                )
                if (line.isNotBlank() && line != title) Text(
                    line, Modifier.padding(top = 8.dp).testTag("letter.line"),
                    style = if (spacious) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    color = PlaymateColors.Ink,
                )
            }
            if (!paused && !answerLabel.isNullOrBlank()) {
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) {
                    Text("방금 고른 답 · $answerLabel", Modifier.background(PlaymateColors.OnuCream, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp).testTag("letter.answerEcho"),
                        style = MaterialTheme.typography.bodySmall, color = PlaymateColors.DeepForest)
                }
            }
            if (waitingForAnswer && status.isNotBlank()) {
                Column(Modifier.fillMaxWidth().padding(start = edge, top = 10.dp)
                    .background(PlaymateColors.DeepForest, RoundedCornerShape(24.dp))
                    .padding(horizontal = if (spacious) 20.dp else 14.dp, vertical = 14.dp)) {
                    LetterStatus(status, statusIcon, speaking = false, onDark = true)
                    if (showChoices && choices.isNotEmpty()) LetterChoices(choices, onChoice)
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = edge, top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    paused -> LetterPrimaryAction("이어 하기", "letter.resume", onPauseOrResume)
                    showChoices && complete -> {
                        val replyIndex = choices.indexOfFirst { it.id == "open_reply" }
                        if (replyIndex >= 0) LetterPrimaryAction(choices[replyIndex].label, "letter.choice.$replyIndex") {
                            onChoice(replyIndex)
                        } else LetterPrimaryAction("오늘은 여기까지", "letter.finish", onBack)
                    }
                    showChoices && !waitingForAnswer && choices.size == 1 ->
                        LetterPrimaryAction(choices.first().label, "letter.choice.0") { onChoice(0) }
                    showChoices && (!waitingForAnswer || status.isBlank()) && choices.isNotEmpty() -> LetterChoices(choices, onChoice)
                }
                if (groupVoiceHelp) LetterSecondaryAction(
                    if (helpOpen) "보호자 도움 접기" else "보호자 도움", "letter.help",
                    expanded = helpOpen, onClick = { helpOpen = !helpOpen },
                )
                if (canShowChoices && (!groupVoiceHelp || helpOpen)) LetterSecondaryAction(
                    "화면으로 고르기", "letter.showChoices", onClick = onShowChoices,
                )
                if (canReplay && !paused && (!groupVoiceHelp || helpOpen)) LetterSecondaryAction(
                    "다시 듣기", "letter.replay", FamilyIcon.SOUND,
                    onClick = { moreActions = false; helpOpen = false; onReplay() },
                )
                if (showChoices && complete && choices.any { it.id != "open_reply" }) {
                    LetterSecondaryAction(if (moreActions) "다르게 해 보기 접기" else "다르게 해 보기",
                        "letter.moreActions", expanded = moreActions, onClick = { moreActions = !moreActions })
                    if (moreActions) Column(Modifier.fillMaxWidth().background(PlaymateColors.Paper, RoundedCornerShape(20.dp))
                        .padding(8.dp).testTag("letter.moreActionsPanel")) {
                        choices.forEachIndexed { index, choice ->
                            if (choice.id != "open_reply") TextButton(onClick = { moreActions = false; onChoice(index) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("letter.choice.$index")) {
                                Text(choice.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LetterSecondaryAction("홈으로", "letter.home", FamilyIcon.HOME, iconOnly = iconOnlyNav, onClick = onBack)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (inlineChapter) LetterProgress(stage, rainLetter)
                }
                if (!paused && !complete && stage > 0)
                    LetterSecondaryAction("잠깐 쉬기", "letter.pause", FamilyIcon.PAUSE, iconOnly = iconOnlyNav, onClick = onPauseOrResume)
            }
            if (spacious) Row(Modifier.fillMaxWidth().weight(1f)) {
                LetterScene(mood, speaking && !paused, Modifier.weight(.42f).fillMaxHeight(), wide = true, listening = listening)
                Column(Modifier.weight(.58f).fillMaxHeight().verticalScroll(scroll)
                    .padding(start = 6.dp, end = gutter, top = questionTop, bottom = 24.dp)) {
                    Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(), content = dialogue)
                }
            } else Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
                LetterScene(mood, speaking && !paused, Modifier.fillMaxWidth().height(heroHeight), wide = false, listening = listening)
                Column(Modifier.fillMaxWidth().padding(start = gutter, end = gutter, bottom = 20.dp), content = dialogue)
            }
        }
    }
}

@Composable
private fun LetterScene(mood: OnuMood, speaking: Boolean, modifier: Modifier, wide: Boolean, listening: Boolean) {
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = if (wide) Alignment.Center else Alignment.BottomStart) {
        if (listening) Box(Modifier.fillMaxHeight(.9f).aspectRatio(1f)
            .background(Brush.radialGradient(listOf(PlaymateColors.OnuCream.copy(alpha = .65f), Color.Transparent))))
        OnuCompanion(
            modifier = Modifier.offset(y = if (wide) maxHeight * .055f else 0.dp).padding(horizontal = 12.dp)
                .fillMaxHeight(if (wide) .82f else 1f).aspectRatio(1f),
            mood = mood, decorative = true, motion = true, narrationActive = speaking,
        )
    }
}

@Composable
private fun LetterStatus(text: String, icon: FamilyIcon?, speaking: Boolean, onDark: Boolean) {
    val color = if (onDark) PlaymateColors.Paper else PlaymateColors.Forest
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) FamilyIcon(icon, Modifier.size(24.dp), tint = color)
        if (speaking) Text("오누", style = MaterialTheme.typography.labelMedium, color = color)
        Text(text, Modifier.testTag("letter.status"), style = MaterialTheme.typography.titleSmall, color = color)
    }
}

@Composable
private fun LetterPrimaryAction(label: String, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, shape = RoundedCornerShape(22.dp),
        modifier = Modifier.padding(top = 10.dp).heightIn(min = 64.dp).testTag(tag)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun LetterSecondaryAction(label: String, tag: String, icon: FamilyIcon? = null,
    expanded: Boolean? = null, iconOnly: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    TextButton(onClick = onClick,
        modifier = Modifier.heightIn(min = 56.dp).background(PlaymateColors.Paper, shape)
            .testTag(tag).semantics {
                if (expanded != null) stateDescription = if (expanded) "펼쳐짐" else "접힘"
                if (iconOnly) contentDescription = label
            },
        shape = shape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        if (icon != null) {
            FamilyIcon(icon, Modifier.size(22.dp))
            if (!iconOnly) Spacer(Modifier.width(8.dp))
        }
        if (!iconOnly) Text(label, style = MaterialTheme.typography.labelMedium, color = PlaymateColors.Forest)
    }
}

@Composable
private fun LetterProgress(stage: Int, rainLetter: Boolean) {
    val chapter = when (stage) {
        0 -> if (rainLetter) "펭귄에게서 온 답장" else "바다에서 온 소리 편지"
        1 -> "소리에 귀 기울이기"
        2 -> if (rainLetter) "리듬 따라가기" else "편지 속 친구 찾기"
        3 -> "우리 방송 만들기"
        else -> "함께 만든 방송"
    }
    Column(Modifier.background(PlaymateColors.Paper, RoundedCornerShape(18.dp))
        .padding(horizontal = 16.dp, vertical = 8.dp).testTag("letter.context")) {
        Text(chapter, Modifier.testTag("letter.chapter"), color = PlaymateColors.DeepForest,
            style = MaterialTheme.typography.labelSmall)
        Row(Modifier.width(112.dp).padding(top = 4.dp).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(stage.coerceIn(0, 4).toFloat(), 0f..4f, 3)
        }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { index ->
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(
                    if (index < stage) PlaymateColors.Forest else PlaymateColors.SoftLine))
            }
        }
    }
}

@Composable
private fun LetterChoices(choices: List<JumpChoice>, onChoice: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        val paired = choices.size == 2 && maxWidth / LocalDensity.current.fontScale >= 420.dp
        val shape = RoundedCornerShape(18.dp)
        val answer: @Composable (Int, Modifier) -> Unit = { index, slot ->
            val choice = choices[index]
            Row(slot.heightIn(min = 88.dp).clip(shape).background(PlaymateColors.Paper)
                .border(1.dp, PlaymateColors.SoftLine, shape).clickable(role = Role.Button) { onChoice(index) }
                .testTag("letter.choice.$index").padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).background(PlaymateColors.Mist, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center) { LetterChoiceDrawing(choice.id) }
                Text(choice.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    color = PlaymateColors.DeepForest)
            }
        }
        if (paired) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            answer(0, Modifier.weight(1f).fillMaxHeight())
            answer(1, Modifier.weight(1f).fillMaxHeight())
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.indices.forEach { index -> answer(index, Modifier.fillMaxWidth()) }
        }
    }
}
// Each answer gets the same illustration treatment. The correct answer stays hidden until the child chooses.
@Composable
private fun LetterChoiceDrawing(id: String) {
    if (id == "both") {
        Box(Modifier.size(50.dp)) {
            Image(painterResource(R.drawable.onu_choice_otter), null, Modifier.size(36.dp).align(Alignment.TopStart))
            Image(painterResource(R.drawable.onu_choice_penguin), null, Modifier.size(36.dp).align(Alignment.BottomEnd))
        }
        return
    }
    val drawing = when (id) {
        "wet", "soft" -> R.drawable.onu_choice_splash
        "steps" -> R.drawable.onu_choice_steps
        "otter" -> R.drawable.onu_choice_otter
        "penguin" -> R.drawable.onu_choice_penguin
        "feathers" -> R.drawable.onu_choice_feather
        "wings" -> R.drawable.onu_choice_wing
        "swim" -> R.drawable.onu_choice_swim
        "bold" -> R.drawable.onu_choice_plop
        "drops" -> R.drawable.onu_choice_drops
        "wiper" -> R.drawable.onu_choice_wiper
        "faster" -> R.drawable.onu_choice_faster
        "slower" -> R.drawable.onu_choice_slower
        else -> return
    }
    Image(painterResource(drawing), null, Modifier.size(50.dp))
}
