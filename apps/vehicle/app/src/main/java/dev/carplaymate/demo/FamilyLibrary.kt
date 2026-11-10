package dev.carplaymate.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
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
) {
    val saved = ui.savedAdventures.firstOrNull { it.sessionId == ui.snapshot?.sessionId } ?: ui.savedAdventures.firstOrNull()
    val letterFeatured = onLetter != null && shouldFeatureFirstJump(ui.children, saved != null)
    val replyFeatured = letterFeatured && onReply != null
    val replyAction = if (replyInProgress) "펭귄의 답장 이어가기" else "펭귄의 답장 열기"
    val recommended = ui.recommendations.firstOrNull()
    val card = ui.adventures.firstOrNull { it.packId == (saved?.packId ?: recommended?.packId) } ?: ui.adventures.firstOrNull()
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize().testTag("family.home")) {
        val compactWide = wide && maxHeight < 500.dp
        val spacious = wide && maxWidth >= 1000.dp && maxHeight >= 600.dp
        val invitationSpacing = when {
            compactWide -> 8.dp
            spacious -> 16.dp
            else -> 12.dp
        }
        val companionHeight = when {
            spacious -> (maxHeight * .68f).coerceAtMost(600.dp)
            wide -> maxHeight.coerceAtMost(460.dp)
            else -> (maxHeight * .26f).coerceIn(120.dp, 250.dp)
        }
        val invitation: @Composable ColumnScope.() -> Unit = {
            if (letterFeatured || card != null) {
                Text(when {
                    replyFeatured -> "펭귄에게서 온 답장"
                    letterFeatured -> "바다에서 온 소리 편지"
                    saved != null -> "지난번 이야기, 이어서"
                    else -> "오늘 오누와 함께"
                },
                    style = MaterialTheme.typography.labelMedium, color = PlaymateColors.Leaf)
                Text(when {
                    replyFeatured -> "비 오는 날의 방송 음악"
                    letterFeatured -> "첫 점프를 들려줘!"
                    else -> checkNotNull(card).title
                },
                    style = (if (spacious) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineLarge).copy(
                    lineBreak = LineBreak.Heading.copy(wordBreak = LineBreak.WordBreak.Phrase)),
                    modifier = Modifier.testTag("recommendation.title").semantics { heading() })
                Text(when {
                    replyFeatured -> "펭귄이 다음 방송에 쓸 음악을 부탁했어. 우리 차의 빗소리로 만들어 보자."
                    letterFeatured -> "바위 끝에 선 친구가 우리 차에 소리를 보냈어. 누가 보냈는지 같이 맞혀 볼래?"
                    else -> checkNotNull(card).subtitle
                }, style = MaterialTheme.typography.bodyMedium, color = PlaymateColors.Ink)
                if (!letterFeatured && card != null) Text(homeRecommendationReason(card.packId, recommended, ui.children, saved != null),
                    style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Muted,
                    modifier = Modifier.testTag("recommendation.reason"))
                if (saved != null) {
                    SavedAdventureFamilyChange(saved, ui.children, "recommendation.participants")
                }
                if (onReply != null && !replyFeatured) {
                    TextButton(onClick = onReply, enabled = !ui.busy,
                        modifier = Modifier.heightIn(min = 54.dp).testTag("family.rainLetter")) {
                        FamilyIcon(FamilyIcon.SOUND)
                        Spacer(Modifier.width(8.dp))
                        Text(replyAction, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if ((!letterFeatured || replyFeatured) && onLetter != null) {
                    TextButton(onClick = onLetter, enabled = !ui.busy,
                        modifier = Modifier.heightIn(min = 52.dp).testTag("family.firstJump")) {
                        Text(LETTER_HOME_ACTION, style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else Text("이야기를 준비하고 있어요", style = MaterialTheme.typography.headlineMedium)
        }
        val invitationActions: @Composable ColumnScope.() -> Unit = {
            if (saved != null) SavedAdventureParticipants(saved, "recommendation.participants")
            if (card != null || letterFeatured) {
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
                            letterFeatured -> "편지 보러 가기"
                            !ui.speechReady && !ui.simulation -> "함께할 방식 선택하기"
                            saved != null -> "이어서 함께하기"
                            else -> "함께 모험 시작하기"
                        },
                        {
                            if (replyFeatured) onReply?.invoke()
                            else if (letterFeatured) onLetter?.invoke()
                            else if (!ui.speechReady && !ui.simulation) onSettings()
                            else if (saved != null) onResume(saved.sessionId)
                            else card?.let { onStart(it.packId) }
                        },
                        !ui.busy,
                        slot.testTag(when {
                            replyFeatured -> "family.rainLetter"
                            letterFeatured -> "family.firstJump"
                            else -> "family.start"
                        }),
                    )
                }
                val libraryAction: @Composable (Modifier) -> Unit = { slot ->
                    TextButton(
                        onClick = onLibrary, enabled = !ui.busy,
                        modifier = slot.heightIn(min = 64.dp).testTag("family.libraryLink"),
                    ) {
                        Text("다른 이야기 고르기", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (spacious && !letterFeatured) {
                    ActionPair(first = startAction, second = libraryAction)
                } else {
                    startAction(Modifier.fillMaxWidth())
                    libraryAction(Modifier.fillMaxWidth())
                }
            }
        }
        if (wide) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.widthIn(max = 1360.dp).fillMaxSize()
                    .padding(horizontal = if (spacious) 40.dp else 24.dp,
                        vertical = if (compactWide) 8.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (spacious) 36.dp else 20.dp),
            ) {
                Box(
                    Modifier.weight(if (compactWide) .34f else .40f)
                        .height(companionHeight).testTag("home.invitation"),
                    contentAlignment = Alignment.Center,
                ) {
                    OnuCompanion(Modifier.fillMaxSize().padding(if (spacious) 4.dp else 16.dp),
                        OnuMood.READY, decorative = true)
                }
                Box(Modifier.weight(if (compactWide) .66f else .60f).fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart) {
                    Column(
                        Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(invitationSpacing, Alignment.CenterVertically),
                    ) {
                        Column(
                            Modifier.weight(1f, fill = false).fillMaxWidth()
                                .familyScrollIndicator(scroll).verticalScroll(scroll),
                            verticalArrangement = Arrangement.spacedBy(invitationSpacing),
                            content = invitation,
                        )
                        invitationActions()
                    }
                }
            }
        } else Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().familyScrollIndicator(scroll).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OnuCompanion(Modifier.fillMaxWidth().height(companionHeight).testTag("home.invitation"), OnuMood.READY, decorative = true)
                invitation()
            }
            invitationActions()
        }
    }
}

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

/** One illustrated place and one quiet reading surface form a single story invitation. */
@Composable
private fun StoryFeature(
    art: String,
    wide: Boolean,
    previewHeight: androidx.compose.ui.unit.Dp,
    compact: Boolean = false,
    entrance: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = if (entrance) Modifier.testTag("home.invitation") else Modifier,
        color = PlaymateColors.Paper,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, PlaymateColors.SoftLine),
    ) {
        if (wide) {
            Row(Modifier.fillMaxWidth(),
                verticalAlignment = if (compact) Alignment.Top else Alignment.CenterVertically) {
                // Give the home invitation a generous stage; a short viewport keeps room for text.
                StoryPreview(art,
                    Modifier.weight(if (entrance && !compact) 1.05f else .8f).height(previewHeight),
                    entrance = entrance,
                    shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                Column(
                    Modifier.weight(if (entrance && !compact) .95f else 1.2f)
                        .padding(horizontal = 24.dp, vertical = if (compact) 10.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                    content = content,
                )
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                StoryPreview(art, Modifier.fillMaxWidth().height(previewHeight), entrance = entrance,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                Column(Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
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
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().testTag("family.library")
            .familyScrollIndicator(scroll).verticalScroll(scroll)
            .padding(if (wide) 28.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("소리 편지", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        val jumpAction = when (jumpStep) {
            null, JumpStep.READY -> "소리를 듣고 첫 점프를 중계해 줘."
            JumpStep.END -> "완성한 점프 중계 다시 듣기"
            else -> "지난번 단서에서 이어가기"
        }
        val rainAction = when (rainStep) {
            null, RainStep.READY -> "차창 소리로 방송 음악을 만들자."
            RainStep.END -> "완성한 방송 음악 다시 듣기"
            else -> "지난번 단서에서 이어가기"
        }
        val first: @Composable (Modifier) -> Unit = { slot ->
            LibraryLetter("first_jump", "첫 점프를 들려줘!", jumpAction, !ui.busy, slot, onLetter)
        }
        if (onReply != null && wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                first(Modifier.weight(1f))
                LibraryLetter("rain_music", "비 오는 날의 방송 음악", rainAction, !ui.busy, Modifier.weight(1f), onReply)
            }
        } else {
            first(Modifier.fillMaxWidth())
            if (onReply != null) LibraryLetter("rain_music", "비 오는 날의 방송 음악", rainAction, !ui.busy, Modifier.fillMaxWidth(), onReply)
        }
        Text("모험 이야기", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        ordered.chunked(if (wide) 2 else 1).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { card ->
                    AdventureTile(card, ui.savedAdventures.firstOrNull { it.packId == card.packId },
                        Modifier.weight(1f), !ui.busy, { onDetail(card.packId) })
                }
                if (wide && row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LibraryLetter(art: String, title: String, detail: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.testTag("library.letter.$art"),
        color = PlaymateColors.Paper, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, PlaymateColors.SoftLine)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AdventureScenery(art, Modifier.size(84.dp))
            Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = PlaymateColors.Ink)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Forest)
            }
            FamilyIcon(FamilyIcon.NEXT)
        }
    }
}

@Composable
private fun AdventureTile(
    card: AdventureCard,
    saved: SavedAdventure?,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled,
        modifier = modifier.testTag("adventure.${card.packId}"),
        shape = RoundedCornerShape(28.dp), color = PlaymateColors.Paper,
        border = BorderStroke(1.dp, PlaymateColors.SoftLine)) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Keep the setting recognizable without pushing its title below the first viewport.
                val coverHeight = (maxWidth * .36f).coerceIn(144.dp, 192.dp)
                WorldArtwork(card.artKey, Modifier.fillMaxWidth().height(coverHeight), narrative = true)
            }
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Eyebrow(card.interestTags.joinToString(" · ") { topicLabel(it) })
                Text(card.title, style = MaterialTheme.typography.titleLarge)
                Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Ink)
                HorizontalDivider(color = PlaymateColors.SoftLine)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (saved != null) "이어갈 이야기 살펴보기" else "이야기 살펴보기",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = PlaymateColors.Forest)
                    FamilyIcon(FamilyIcon.NEXT)
                }
            }
        }
    }
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
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().testTag("family.detail")) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val shortViewport = wide && maxHeight < 320.dp
            val previewHeight = minOf(
                if (wide) 320.dp else 180.dp,
                if (wide) (maxHeight - 40.dp).coerceAtLeast(120.dp)
                else (maxHeight * .32f).coerceAtLeast(120.dp),
            )
            Column(
                Modifier.fillMaxSize().familyScrollIndicator(scroll).verticalScroll(scroll)
                    .padding(if (wide) 28.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StoryFeature(
                    art = card.artKey,
                    wide = wide,
                    previewHeight = previewHeight,
                    compact = shortViewport,
                ) {
                    Eyebrow(card.interestTags.joinToString(" · ") { topicLabel(it) })
                    Text(card.title, style = MaterialTheme.typography.headlineLarge)
                    Text(card.description, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FamilyIcon(if (ui.simulation) FamilyIcon.READ else FamilyIcon.SOUND,
                            modifier = Modifier.size(22.dp), tint = PlaymateColors.Muted)
                        Text(modeName(ui), style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Muted)
                    }
                    if (saved != null) SavedAdventureFamilyChange(saved, ui.children,
                        "adventure.participants")
                    if (card.hintScorePenalty > 0) Text(
                        "힌트를 쓰면 이번 선택에서 얻는 점수가 최대 ${card.hintScorePenalty}점 줄어요.",
                        style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Muted)
                }
            }
        }
        Surface(color = PlaymateColors.Paper) {
            Column(Modifier.fillMaxWidth().padding(horizontal = if (wide) 28.dp else 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            }
        }
    }
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
