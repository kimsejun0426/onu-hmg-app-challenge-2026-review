package dev.carplaymate.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Explain only the current profiles represented by this recommendation; never infer shared tastes. */
internal fun homeRecommendationReason(
    packId: String,
    recommendation: AdventureRecommendation?,
    children: List<ChildProfile>,
    hasSavedAdventure: Boolean,
): String {
    if (hasSavedAdventure) return "함께 고른 길에서 다시 만나요."
    val fallback = "마음이 가는 이야기로 시작해요."
    if (recommendation == null || recommendation.packId != packId) return fallback
    val current = children.take(2)
    val currentIds = current.map { it.id }.toSet()
    val matchedIds = recommendation.matchedChildIds.toSet()
    // A removed child's nickname may be reused. IDs, not names, identify the current match.
    if (matchedIds.any { it !in currentIds }) return fallback
    if (recommendation.shared && current.size == 2 && currentIds.size == 2 && matchedIds == currentIds) {
        return "${current.joinToString("·") { it.nickname }}의 관심사를 함께 반영했어요."
    }
    return recommendation.reasons.firstOrNull() ?: fallback
}

internal fun shouldFeatureFirstJump(children: List<ChildProfile>, hasSavedAdventure: Boolean): Boolean =
    !hasSavedAdventure && children.isNotEmpty() && children.all { it.interests.isEmpty() || "ocean" in it.interests }

private const val LETTER_HOME_ACTION = "바다에서 온 첫 편지"

@Composable
internal fun FamilyLanding(
    ui: DeveloperUiState,
    wide: Boolean,
    onStart: (String) -> Unit,
    onResume: (String) -> Unit,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    onLetter: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    replyInProgress: Boolean = false,
    letterInProgress: Boolean = false,
) {
    val saved = ui.savedAdventures.firstOrNull { it.sessionId == ui.snapshot?.sessionId } ?: ui.savedAdventures.firstOrNull()
    val letterFeatured = onLetter != null && shouldFeatureFirstJump(ui.children, saved != null)
    val replyFeatured = letterFeatured && onReply != null
    val replyAction = if (replyInProgress) "펭귄의 답장 이어가기" else "펭귄의 답장 열기"
    val recommended = ui.recommendations.firstOrNull()
    val card = ui.adventures.firstOrNull { it.packId == (saved?.packId ?: recommended?.packId) } ?: ui.adventures.firstOrNull()
    val sideStories = buildList {
        if (onReply != null && !replyFeatured) add(StoryLink("rain_music", replyAction,
            null, "family.rainLetter", onReply))
        if (onLetter != null && (!letterFeatured || replyFeatured)) add(StoryLink("first_jump",
            if (letterInProgress) "첫 편지 이어가기" else LETTER_HOME_ACTION,
            null, "family.firstJump", onLetter))
    }
    val art = when {
        replyFeatured -> "rain_music"
        letterFeatured -> "first_jump"
        else -> card?.artKey ?: "dinosaurs"
    }
    val canStart = card != null || letterFeatured
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize().testTag("family.home"), contentAlignment = Alignment.Center) {
        val scale = LocalDensity.current.fontScale
        val spacious = wide && maxWidth / scale >= 950.dp && maxHeight / scale >= 540.dp
        val short = maxHeight / scale < 520.dp
        val gap = if (short) 6.dp else 12.dp
        val compactCoverHeight = (maxHeight * .20f).coerceIn(100.dp, 180.dp)
        val info: @Composable ColumnScope.() -> Unit = {
            if (canStart) {
                Text(when {
                    replyFeatured -> "펭귄에게서 온 답장"
                    letterFeatured -> "바다에서 온 소리 편지"
                    saved != null -> "지난번 이야기, 이어서"
                    else -> "오늘 오누와 함께"
                }, style = MaterialTheme.typography.labelMedium, color = PlaymateColors.Leaf)
                Text(when {
                    replyFeatured -> "비 오는 날의 방송 음악"
                    letterFeatured -> "첫 점프를 들려줘!"
                    else -> checkNotNull(card).title
                }, style = (if (spacious) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineLarge)
                    .copy(lineBreak = LineBreak.Heading.copy(wordBreak = LineBreak.WordBreak.Phrase)),
                    modifier = Modifier.testTag("recommendation.title").semantics { heading() })
                if (!letterFeatured && card != null) {
                    Text(if (saved != null) saved.progressLabel()
                        else homeRecommendationReason(card.packId, recommended, ui.children, false),
                        style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Forest,
                        modifier = Modifier.testTag("recommendation.reason"))
                }
                Text(when {
                    replyFeatured -> "펭귄이 다음 방송에 쓸 음악을 부탁했어. 우리 차의 빗소리로 만들어 보자."
                    letterFeatured -> "바위 끝에 선 친구가 우리 차에 소리를 보냈어. 누가 보냈는지 같이 맞혀 볼래?"
                    else -> adventureInvitation(checkNotNull(card))
                }, style = MaterialTheme.typography.bodyMedium, color = PlaymateColors.Ink)
                if (saved != null) SavedAdventureFamilyChange(saved, ui.children, "recommendation.participants")
            } else Text("이야기를 준비하고 있어요", style = MaterialTheme.typography.headlineMedium)
        }
        val libraryAction: @Composable (Modifier) -> Unit = { slot ->
            OutlinedButton(onClick = onLibrary, enabled = !ui.busy,
                modifier = slot.heightIn(min = 64.dp).testTag("family.libraryLink"),
                shape = RoundedCornerShape(18.dp)) {
                FamilyIcon(FamilyIcon.EXPLORE)
                Spacer(Modifier.width(8.dp))
                Text("다른 이야기 고르기", style = MaterialTheme.typography.labelLarge)
            }
        }
        val controls: @Composable ColumnScope.() -> Unit = {
            if (saved != null) SavedAdventureParticipants(saved, "recommendation.participants")
            if (canStart) {
                val selectedMode = when {
                    !ui.interactionConfigured -> "함께할 방식을 먼저 골라 주세요"
                    ui.simulation -> "선택한 방식 · 읽으며 체험"
                    ui.voiceInput -> "선택한 방식 · 음성으로 함께"
                    else -> "선택한 방식 · 듣고 선택하기"
                }
                Text(selectedMode, style = MaterialTheme.typography.labelMedium,
                    color = PlaymateColors.Forest, modifier = Modifier.testTag("family.home.mode"))
                val startAction: @Composable (Modifier) -> Unit = { slot ->
                    PrimaryButton(
                        when {
                            replyFeatured -> replyAction
                            letterFeatured -> if (letterInProgress) "첫 편지 이어가기" else "편지 보러 가기"
                            !ui.speechReady && !ui.simulation -> "함께할 방식 선택하기"
                            saved != null -> "이어서 함께하기"
                            else -> "함께 모험 시작하기"
                        },
                        {
                            if (replyFeatured) onReply?.invoke()
                            else if (letterFeatured) onLetter?.invoke()
                            else if (!ui.speechReady && !ui.simulation) onSettings()
                            else if (saved != null) onResume(saved.sessionId) else card?.let { onStart(it.packId) }
                        }, !ui.busy, slot.testTag(when {
                            replyFeatured -> "family.rainLetter"
                            letterFeatured -> "family.firstJump"
                            else -> "family.start"
                        }),
                    )
                }
                if (spacious) startAction(Modifier.fillMaxWidth())
                else ActionPair(first = startAction, second = libraryAction)
            }
        }
        val compactText: @Composable ColumnScope.() -> Unit = {
            info()
            sideStories.forEach { StoryLinkCard(it, !ui.busy, Modifier.fillMaxWidth()) }
        }
        if (spacious) {
            Column(Modifier.widthIn(max = 1440.dp).fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(32.dp),
                    color = PlaymateColors.Paper, border = BorderStroke(1.dp, PlaymateColors.SoftLine)) {
                    Row(Modifier.fillMaxSize()) {
                        StoryPreview(art, Modifier.weight(.50f).fillMaxHeight().testTag("home.invitation"),
                            entrance = true, shape = RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp))
                        Column(Modifier.weight(.50f).fillMaxHeight().padding(horizontal = 32.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                            Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                                .familyScrollIndicator(scroll).verticalScroll(scroll).padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp), content = info)
                            controls()
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    sideStories.forEach { StoryLinkCard(it, !ui.busy, Modifier.weight(1f)) }
                    libraryAction(if (sideStories.isEmpty()) Modifier.fillMaxWidth() else Modifier.weight(1f))
                }
            }
        } else if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                StoryPreview(art, Modifier.weight(.32f).fillMaxHeight(.88f).testTag("home.invitation"))
                Column(Modifier.weight(.68f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                        .familyScrollIndicator(scroll).verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(gap), content = compactText)
                    controls()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(gap)) {
                Column(Modifier.weight(1f).fillMaxWidth().familyScrollIndicator(scroll).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(gap)) {
                    if (!short) StoryPreview(art, Modifier.fillMaxWidth()
                        .height(compactCoverHeight).testTag("home.invitation"))
                    info()
                    if (short) StoryPreview(art, Modifier.fillMaxWidth().height(96.dp).testTag("home.invitation"))
                    sideStories.forEach { StoryLinkCard(it, !ui.busy, Modifier.fillMaxWidth()) }
                }
                controls()
            }
        }
    }
}

/** Preserve the discovery in the dinosaur story for the first question. */
private fun adventureInvitation(card: AdventureCard): String =
    if (card.packId == "dinosaur-lab-missing-egg") "연구소에서 공룡 알 모형이 사라졌어. 물가와 숲의 흔적을 따라 찾아볼까?"
    else card.description

private fun SavedAdventure.progressLabel(): String =
    if (totalChoices > 0) "${totalChoices}번 중 ${choicesCount.coerceIn(0, totalChoices)}번 선택했어요"
    else "지난번 선택에서 이어가기"

/** Decorative story space; all text stays on a separate plain surface. */
@Composable
internal fun StoryPreview(
    art: String,
    modifier: Modifier,
    mood: OnuMood = OnuMood.READY,
    entrance: Boolean = false,
    shape: Shape = RoundedCornerShape(28.dp),
) {
    OnuArrivalScene(modifier = modifier, art = art, mood = mood, entrance = entrance, shape = shape)
}

/** Keep the controls with their story, including when only the introduction needs to scroll. */
@Composable
private fun StoryFeature(
    art: String,
    wide: Boolean,
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("family.detail")
            .padding(horizontal = if (wide) 28.dp else 16.dp, vertical = if (wide) 16.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val fontScale = LocalDensity.current.fontScale
        val split = wide && maxWidth / fontScale >= 1000.dp && maxHeight / fontScale >= 360.dp
        val coverHeight = if (split) minOf(maxHeight, maxWidth * .54f, 720.dp)
            else (maxHeight * .32f).coerceIn(80.dp, 280.dp)
        Surface(
            Modifier.widthIn(max = 1440.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp), color = PlaymateColors.Paper,
            border = BorderStroke(1.dp, PlaymateColors.SoftLine),
        ) {
            if (split) {
                Row(Modifier.fillMaxWidth().height(coverHeight)) {
                    StoryPreview(art, Modifier.weight(.54f).fillMaxHeight().testTag("adventure.cover"),
                        shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                    Column(
                        Modifier.weight(.46f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    ) {
                        Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                            .familyScrollIndicator(scroll).verticalScroll(scroll).padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
                        actions()
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                        .familyScrollIndicator(scroll).verticalScroll(scroll)) {
                        StoryPreview(art, Modifier.fillMaxWidth().height(coverHeight).testTag("adventure.cover"),
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp), content = actions)
                }
            }
        }
    }
}

internal fun topicLabel(id: String): String =
    when (id) {
        "dinosaurs" -> "공룡"
        "space" -> "우주"
        "animals" -> "동물"
        "vehicles" -> "자동차"
        "ocean" -> "바다"
        "stories" -> "이야기"
        else -> id
    }

@Composable
internal fun AdventureLibrary(
    ui: DeveloperUiState,
    wide: Boolean,
    onDetail: (String) -> Unit,
    onLetter: () -> Unit,
    onReply: (() -> Unit)?,
    jumpStep: JumpStep?,
    rainStep: RainStep?,
) {
    val ranked = ui.recommendations.mapNotNull { match ->
        ui.adventures.firstOrNull { it.packId == match.packId }
    }
    val ordered = (ranked + ui.adventures).distinctBy { it.packId }
    val continueStories = mutableListOf<StoryLink>()
    val letters = mutableListOf<StoryLink>()
    val adventures = mutableListOf<StoryLink>()
    val jumpActive = jumpStep != null && jumpStep != JumpStep.READY && jumpStep != JumpStep.END
    val rainActive = rainStep != null && rainStep != RainStep.READY && rainStep != RainStep.END
    val jump = StoryLink("first_jump", "첫 점프를 들려줘!", when (jumpStep) {
        null, JumpStep.READY -> "소리를 듣고 첫 점프를 중계해 줘."
        JumpStep.END -> "완성한 점프 중계 다시 듣기"
        else -> "지난번 단서에서 이어가기"
    }, "library.letter.first_jump", onLetter)
    if (jumpActive) continueStories.add(jump) else letters.add(jump)
    if (onReply != null) {
        val rain = StoryLink("rain_music", "비 오는 날의 방송 음악", when (rainStep) {
            null, RainStep.READY -> "차창 소리로 방송 음악을 만들자."
            RainStep.END -> "완성한 방송 음악 다시 듣기"
            else -> "지난번 단서에서 이어가기"
        }, "library.letter.rain_music", onReply)
        if (rainActive) continueStories.add(rain) else letters.add(rain)
    }
    for (card in ordered) {
        val saved = ui.savedAdventures.firstOrNull { it.packId == card.packId }
        val link = StoryLink(card.artKey, card.title, saved?.progressLabel() ?: card.subtitle,
            "adventure.${card.packId}", { onDetail(card.packId) },
            meta = if (saved == null) (card.interestTags.map(::topicLabel) + card.durationLabel)
                .filter { it.isNotBlank() }.joinToString(" · ") else null,
            progress = saved?.takeIf { it.totalChoices > 0 }?.let {
                (it.choicesCount.toFloat() / it.totalChoices).coerceIn(0f, 1f)
            })
        if (saved != null) continueStories.add(link) else adventures.add(link)
    }
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize().testTag("family.library"), contentAlignment = Alignment.TopCenter) {
        val paired = wide && maxWidth / LocalDensity.current.fontScale >= 860.dp
        Column(Modifier.widthIn(max = 1440.dp).fillMaxSize().familyScrollIndicator(scroll).verticalScroll(scroll)
            .padding(horizontal = if (wide) 28.dp else 16.dp, vertical = if (wide) 20.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            StoryShelf("이어갈 이야기", continueStories, paired, !ui.busy, continued = true)
            StoryShelf("소리 편지", letters, paired, !ui.busy)
            StoryShelf("모험 이야기", adventures, paired, !ui.busy)
        }
    }
}

private data class StoryLink(
    val art: String,
    val title: String,
    val detail: String?,
    val tag: String,
    val onClick: () -> Unit,
    val meta: String? = null,
    val progress: Float? = null,
)

@Composable
private fun StoryShelf(title: String, stories: List<StoryLink>, paired: Boolean, enabled: Boolean,
    continued: Boolean = false) {
    if (stories.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        for (row in stories.chunked(if (paired) 2 else 1)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { StoryLinkCard(it, enabled, Modifier.weight(1f), continued) }
                if (paired && stories.size > 1 && row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StoryLinkCard(link: StoryLink, enabled: Boolean, modifier: Modifier, continued: Boolean = false) {
    val shortcut = link.detail == null
    Surface(onClick = link.onClick, enabled = enabled, modifier = modifier.testTag(link.tag),
        color = if (continued) PlaymateColors.Mist else PlaymateColors.Paper,
        shape = RoundedCornerShape(if (shortcut) 18.dp else 22.dp),
        border = BorderStroke(1.dp, if (continued) PlaymateColors.Sage else PlaymateColors.SoftLine)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val coverWidth = when {
                shortcut -> (maxWidth * .21f).coerceIn(48.dp, 92.dp)
                maxWidth / LocalDensity.current.fontScale < 360.dp -> (maxWidth * .22f).coerceAtMost(96.dp)
                else -> (maxWidth * .32f).coerceIn(100.dp, 220.dp)
            }
            val minHeight = when {
                shortcut -> 74.dp
                link.meta != null -> 156.dp
                else -> 116.dp
            }
            Row(Modifier.fillMaxWidth().heightIn(min = minHeight).height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically) {
                // Derive the thumbnail height from the wrapped text at the current font scale.
                Box(Modifier.width(coverWidth).fillMaxHeight()) {
                    AdventureScenery(link.art, Modifier.matchParentSize())
                }
                Column(Modifier.weight(1f).padding(horizontal = if (shortcut) 12.dp else 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (!link.meta.isNullOrBlank()) Text(link.meta, style = MaterialTheme.typography.labelMedium,
                        color = PlaymateColors.Forest)
                    Text(link.title, style = if (shortcut) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge,
                        color = PlaymateColors.Ink)
                    if (link.detail != null) Text(link.detail, style = MaterialTheme.typography.bodySmall,
                        color = if (continued) PlaymateColors.Forest else PlaymateColors.Muted)
                    if (link.progress != null) Box(Modifier.fillMaxWidth().padding(top = 3.dp)
                        .height(4.dp).background(PlaymateColors.Sage, RoundedCornerShape(50))) {
                        Box(Modifier.fillMaxWidth(link.progress).fillMaxHeight()
                            .background(PlaymateColors.Forest, RoundedCornerShape(50)))
                    }
                }
                FamilyIcon(FamilyIcon.NEXT, modifier = Modifier.padding(end = 12.dp).size(22.dp))
            }
        }
    }
}

@Composable
private fun LibraryLetter(art: String, title: String, detail: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    StoryLinkCard(StoryLink(art, title, detail, "library.letter.$art", onClick), enabled, modifier)
}

@Composable
internal fun AdventureDetail(
    ui: DeveloperUiState,
    wide: Boolean,
    onStart: (String) -> Unit,
    onResume: (String) -> Unit,
    onRestart: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val card = ui.adventures.firstOrNull { it.packId == ui.selectedPackId } ?: return
    val saved = ui.savedAdventures.firstOrNull { it.packId == card.packId }
    StoryFeature(card.artKey, wide, content = {
        Eyebrow(card.interestTags.joinToString(" · ") { topicLabel(it) })
        Text(card.title, style = MaterialTheme.typography.headlineLarge.copy(
            lineBreak = LineBreak.Heading.copy(wordBreak = LineBreak.WordBreak.Phrase)),
            modifier = Modifier.semantics { heading() })
        Text(adventureInvitation(card), style = MaterialTheme.typography.bodyMedium)
        if (saved != null) SavedAdventureFamilyChange(saved, ui.children, "adventure.participants")
        if (card.hintScorePenalty > 0) Text(
            "힌트를 쓰면 이번 선택에서 얻는 점수가 최대 ${card.hintScorePenalty}점 줄어요.",
            style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val modeIcon = when {
                ui.simulation -> FamilyIcon.READ
                ui.voiceInput -> FamilyIcon.MIC
                else -> FamilyIcon.SOUND
            }
            FamilyIcon(modeIcon, modifier = Modifier.size(22.dp), tint = PlaymateColors.Forest)
            Text(modeName(ui), style = MaterialTheme.typography.labelMedium, color = PlaymateColors.Forest)
        }
    }, actions = {
        if (saved != null) SavedAdventureParticipants(saved, "adventure.participants")
        val startButton: @Composable (Modifier) -> Unit = { slot ->
            PrimaryButton(
                if (!ui.speechReady && !ui.simulation) "함께할 방식 선택하기"
                else if (saved != null) "이 이야기 이어하기" else "이 모험 시작하기",
                {
                    if (!ui.speechReady && !ui.simulation) onSettings()
                    else if (saved != null) onResume(saved.sessionId) else onStart(card.packId)
                },
                !ui.busy, slot.testTag("adventure.start"),
            )
        }
        if (saved != null) ActionPair(
            first = startButton,
            second = { slot ->
                TextButton(onClick = { onRestart(card.packId) }, enabled = !ui.busy,
                    modifier = slot.heightIn(min = 64.dp).testTag("adventure.restart")) {
                    Text("처음부터 새 모험")
                }
            },
        ) else startButton(Modifier)
    })
}

/** Keep the saved participants beside the resume action, independent of the body scroll. */
@Composable
private fun SavedAdventureParticipants(saved: SavedAdventure, tag: String) {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = PlaymateColors.Muted)) { append("함께하던 친구 · ") }
        withStyle(SpanStyle(color = PlaymateColors.Ink, fontWeight = FontWeight.SemiBold)) {
            append(saved.participants.joinToString(" · "))
        }
    }, modifier = Modifier.fillMaxWidth().testTag(tag), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SavedAdventureFamilyChange(saved: SavedAdventure, children: List<ChildProfile>, tag: String) {
    if (saved.participants != children.map { it.nickname }) Surface(
        color = PlaymateColors.Mist, shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            "이어가면 이 친구들의 차례가 유지돼요. 지금 가족으로 놀려면 이야기에서 ‘처음부터 새 모험’을 골라 주세요.",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("$tag.changedFamily"),
            style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Ink,
        )
    }
}

@Composable
internal fun FamilyMemory(
    ui: DeveloperUiState, wide: Boolean, jump: JumpState?, rain: RainState?,
    onLetter: () -> Unit, onReply: (() -> Unit)?, onLibrary: () -> Unit,
) {
    // A delivered reply remembers the completed broadcast even if the first game was restarted.
    val broadcast = jump?.takeIf { it.step == JumpStep.END }?.ending ?: rain?.previousJump
    val hasLetters = broadcast != null || onReply != null
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val entry = ui.journal.firstOrNull { it.sessionId == opened }
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize()
            .testTag("family.journal")
            .familyScrollIndicator(scroll).verticalScroll(scroll)
            .padding(if (wide) 28.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (hasLetters) {
            Text("우리의 소리 편지", style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() })
            if (broadcast != null) {
                val jumpLine = if (broadcast == JumpEnding.BOLD) "성큼성큼, 작게 퐁당!" else "살금살금, 크게 첨벙!"
                LibraryLetter("first_jump", "첫 점프를 들려줘!", "완성한 점프 중계 · $jumpLine",
                    !ui.busy, Modifier.fillMaxWidth(), onLetter)
            }
            if (onReply != null) {
                val detail = when (rain?.step) {
                    null, RainStep.READY -> "펭귄이 보낸 답장 · 편지 열기"
                    RainStep.END -> if (rain?.ending == RainEnding.WIPER) "완성한 방송 음악 · 와이퍼 슥삭부터"
                        else "완성한 방송 음악 · 빗방울 톡톡부터"
                    else -> "함께 만들던 방송 음악 · 이어가기"
                }
                LibraryLetter("rain_music", "비 오는 날의 방송 음악", detail,
                    !ui.busy, Modifier.fillMaxWidth(), onReply)
            }
        }
        if (ui.journal.isEmpty() && !hasLetters) {
            Surface(
                color = PlaymateColors.Mist,
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    FamilyCompanion(Modifier.size(64.dp))
                    Text("아직 펼치지 않은\n우리의 이야기", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "모험을 끝내면, 함께 고른 길과 발견이 이곳에 남아요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PlaymateColors.Muted,
                    )
                    PrimaryButton("첫 기억 만들러 가기", onLibrary)
                }
            }
        } else if (ui.journal.isNotEmpty()) {
            Text(if (hasLetters) "함께 마친 모험" else "같이 고르고, 함께 기억한 날들",
                style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Text(
                "${ui.journal.size}개의 모험을 함께 마쳤어요.",
                style = MaterialTheme.typography.bodyMedium,
                color = PlaymateColors.Muted,
            )
            ui.journal.forEach { item ->
                Surface(
                    onClick = { opened = item.sessionId },
                    color = PlaymateColors.Paper,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, PlaymateColors.Line),
                    modifier = Modifier.fillMaxWidth().testTag("journal.${item.sessionId}"),
                ) {
                    if (wide)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WorldArtwork(
                                item.artKey,
                                Modifier.width(100.dp).height(100.dp),
                                item.title,
                            )
                            MemorySummary(item, Modifier.weight(1f).padding(28.dp))
                        }
                    else
                        Column {
                            WorldArtwork(
                                item.artKey,
                                Modifier.padding(start = 24.dp, top = 24.dp).size(52.dp),
                                item.title,
                            )
                            MemorySummary(item, Modifier.padding(24.dp))
                        }
                }
            }
        }
    }
    if (entry != null)
        AlertDialog(
            onDismissRequest = { opened = null },
            modifier = Modifier.testTag("family.memoryDetail"),
            title = { Text(entry.endingTitle.ifBlank { entry.title }) },
            text = {
                val detailScroll = rememberScrollState()
                Column(
                    Modifier.fillMaxWidth().familyScrollIndicator(detailScroll)
                        .verticalScroll(detailScroll).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(entry.summary, style = MaterialTheme.typography.bodyMedium)
                    entry.choices.forEach { choice ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                choice.sceneTitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = PlaymateColors.Leaf,
                            )
                            Text(
                                "${choice.participant} · ${choice.actionLabel}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { opened = null }, modifier = Modifier.heightIn(min = 72.dp)) {
                    Text("기억 덮어두기")
                }
            },
        )
}

@Composable
private fun MemorySummary(item: FamilyJournalEntry, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            item.completedAtEpochMillis?.let {
                DateFormat.getDateInstance(DateFormat.LONG, Locale.KOREA).format(Date(it))
            } ?: "이전 모험",
            style = MaterialTheme.typography.labelMedium,
            color = PlaymateColors.Leaf,
        )
        Text(item.title, style = MaterialTheme.typography.titleLarge)
        Text(item.endingTitle, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${item.participants.joinToString(" · ")} · ${item.choicesCount}번의 선택",
            style = MaterialTheme.typography.bodySmall,
            color = PlaymateColors.Muted,
        )
    }
}
