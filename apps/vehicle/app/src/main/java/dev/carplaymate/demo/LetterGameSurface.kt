package dev.carplaymate.demo

import androidx.compose.foundation.Image
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
) {
    val sceneArt = if (rainLetter) R.drawable.onu_letter_rain else R.drawable.onu_letter_shore
    val mood = when {
        paused -> OnuMood.RESTING
        listening -> OnuMood.LISTENING
        speaking -> OnuMood.STORYTELLING
        soundPlaying -> OnuMood.THINKING
        processing -> OnuMood.THINKING
        complete -> OnuMood.CELEBRATING
        else -> OnuMood.READY
    }
    val scroll = rememberScrollState()
    LaunchedEffect(scrollKey, paused) { scroll.scrollTo(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(PlaymateColors.Ivory).testTag("letter.surface")) {
        val spacious = maxWidth >= 740.dp && maxWidth / LocalDensity.current.fontScale >= 630.dp
        val compactHeight = maxHeight < 420.dp
        val artHeight = (maxHeight * .24f).coerceIn(74.dp, 172.dp)
        // Keep the spoken question in place when its answers appear.
        val panelTop = ((maxHeight / LocalDensity.current.fontScale - 400.dp) * .37f).coerceIn(4.dp, 260.dp)
        val navigation: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 52.dp).testTag("letter.home")) {
                    FamilyIcon(FamilyIcon.HOME, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("홈으로", color = PlaymateColors.Forest, fontSize = 18.sp)
                }
                Spacer(Modifier.weight(1f))
                if (!paused && !complete && stage > 0) {
                    TextButton(onClick = onPauseOrResume, modifier = Modifier.heightIn(min = 52.dp).testTag("letter.pause")) {
                        FamilyIcon(FamilyIcon.PAUSE, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("잠깐 쉬기", color = PlaymateColors.Forest, fontSize = 18.sp)
                    }
                }
            }
        }
        val panel: @Composable ColumnScope.() -> Unit = {
            LetterProgress(stage)
            if (status.isNotBlank()) Row(
                Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (statusIcon != null) FamilyIcon(statusIcon, Modifier.size(22.dp))
                Text(status, Modifier.testTag("letter.status"),
                    style = MaterialTheme.typography.titleSmall, color = PlaymateColors.Forest)
            }
            if (precedingClues.isNotBlank()) Text(
                precedingClues, Modifier.padding(bottom = 10.dp).testTag("letter.clues"),
                style = MaterialTheme.typography.bodyMedium, color = PlaymateColors.Ink,
            )
            Text(
                title, Modifier.semantics { heading() }.testTag("letter.title"),
                style = if (spacious) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                color = PlaymateColors.DeepForest,
            )
            if (line.isNotBlank() && line != title) Text(
                line, Modifier.padding(top = 8.dp, bottom = 12.dp).testTag("letter.line"),
                style = if (spacious) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium, color = PlaymateColors.Ink,
            )
            when {
                paused -> Button(onClick = onPauseOrResume, modifier = Modifier.heightIn(min = 54.dp).testTag("letter.resume")) {
                    Text("이어 하기", style = MaterialTheme.typography.titleSmall)
                }
                showChoices && complete -> {
                    choices.forEachIndexed { index, choice ->
                        if (index == 0) Button(onClick = { onChoice(index) },
                            modifier = Modifier.padding(top = 6.dp).heightIn(min = 60.dp).testTag("letter.choice.$index")) {
                            Text(choice.label, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(8.dp))
                            FamilyIcon(FamilyIcon.NEXT, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        } else TextButton(onClick = { onChoice(index) },
                            modifier = Modifier.heightIn(min = 52.dp).testTag("letter.choice.$index")) {
                            Text(choice.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                showChoices && choices.size == 1 -> Button(
                    onClick = { onChoice(0) },
                    modifier = Modifier.padding(top = 8.dp).heightIn(min = 58.dp).testTag("letter.choice.0"),
                ) { Text(choices.first().label, style = MaterialTheme.typography.titleSmall) }
                showChoices && choices.isNotEmpty() -> LetterChoices(choices, onChoice)
                canShowChoices -> TextButton(onClick = onShowChoices, modifier = Modifier.heightIn(min = 52.dp).testTag("letter.showChoices")) {
                    Text("화면으로 고르기", color = PlaymateColors.Forest, style = MaterialTheme.typography.titleSmall)
                }
            }
            if (canReplay && !paused) TextButton(
                onClick = onReplay, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp).testTag("letter.replay"),
            ) {
                FamilyIcon(FamilyIcon.SOUND, Modifier.size(20.dp))
                Spacer(Modifier.width(7.dp))
                Text("다시 듣기", color = PlaymateColors.Forest, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (spacious) {
            Row(Modifier.fillMaxSize()) {
                LetterScene(sceneArt, mood, speaking && !paused, Modifier.fillMaxHeight().weight(.43f), fadeSide = true)
                Column(Modifier.weight(.57f).fillMaxHeight().padding(horizontal = 22.dp)) {
                    navigation()
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                            .padding(top = panelTop, end = 8.dp, bottom = if (compactHeight) 12.dp else 32.dp),
                        content = panel,
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { navigation() }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                    LetterScene(sceneArt, mood, speaking && !paused,
                        Modifier.fillMaxWidth().height(artHeight), fadeSide = false)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), content = panel)
                }
            }
        }
    }
}

@Composable
private fun LetterScene(art: Int, mood: OnuMood, speaking: Boolean, modifier: Modifier, fadeSide: Boolean) {
    BoxWithConstraints(modifier.clipToBounds()) {
        Image(
            painterResource(art), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
        )
        val blend = if (fadeSide) Brush.horizontalGradient(
            0f to Color.Transparent, .76f to Color.Transparent, 1f to PlaymateColors.Ivory,
        ) else Brush.verticalGradient(
            0f to Color.Transparent, .76f to Color.Transparent, 1f to PlaymateColors.Ivory,
        )
        Box(Modifier.fillMaxSize().background(blend))
        val floorOffset = if (art == R.drawable.onu_letter_rain) .13f else .075f
        OnuCompanion(
            modifier = Modifier.align(if (fadeSide) Alignment.Center else Alignment.BottomStart)
                .offset(y = if (fadeSide) maxHeight * floorOffset else 0.dp).padding(horizontal = 12.dp).fillMaxHeight(if (fadeSide) .68f else .9f).aspectRatio(1f),
            mood = mood, decorative = true, motion = true, narrationActive = speaking,
        )
    }
}

@Composable
private fun LetterProgress(stage: Int) {
    Row(
        Modifier.width(96.dp).padding(top = 4.dp, bottom = 10.dp).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(stage.coerceIn(0, 4).toFloat(), 0f..4f, 3)
        }, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(4) { index ->
            Box(Modifier.weight(1f).height(3.dp).background(
                if (index < stage) PlaymateColors.Forest else PlaymateColors.SoftLine,
            ))
        }
    }
}

@Composable
private fun LetterChoices(choices: List<JumpChoice>, onChoice: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)) {
        val paired = choices.size == 2 && maxWidth / LocalDensity.current.fontScale >= 480.dp
        val shape = MaterialTheme.shapes.medium
        val answer: @Composable (Int, Modifier) -> Unit = { index, slot ->
            val choice = choices[index]
            Row(
                slot.heightIn(min = 76.dp).clip(shape)
                    .background(PlaymateColors.Paper).border(1.dp, PlaymateColors.Line, shape)
                    .clickable(role = Role.Button) { onChoice(index) }
                    .testTag("letter.choice.$index").padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LetterChoiceDrawing(choice.id)
                Text(choice.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = PlaymateColors.DeepForest)
            }
        }
        if (paired) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
