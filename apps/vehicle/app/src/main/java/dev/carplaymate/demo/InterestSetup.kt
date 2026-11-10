package dev.carplaymate.demo

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val interestSelectionSaver =
    listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** Choices stay separate for each child until the caregiver saves both drafts. */
@Composable
internal fun InterestSetupScreen(
    ui: DeveloperUiState,
    wide: Boolean,
    modifier: Modifier = Modifier,
    onSave: (Set<String>, Set<String>) -> Boolean,
    onSkip: () -> Unit,
    editing: Boolean = false,
) {
    val firstChild = ui.children.firstOrNull()
    if (firstChild == null) {
        Text("가족 정보를 불러오고 있어요.", modifier.padding(24.dp))
        return
    }
    val secondChild = ui.children.getOrNull(1)
    var first by rememberSaveable(firstChild.id, stateSaver = interestSelectionSaver) {
        mutableStateOf(firstChild.interests)
    }
    var second by rememberSaveable(secondChild?.id, stateSaver = interestSelectionSaver) {
        mutableStateOf(secondChild?.interests.orEmpty())
    }
    var childIndex by rememberSaveable(firstChild.id, secondChild?.id) {
        mutableIntStateOf(if (firstChild.interestsConfigured && secondChild?.interestsConfigured == false) 1 else 0)
    }
    var secondVisited by rememberSaveable(firstChild.id, secondChild?.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun editChild(index: Int) {
        childIndex = index
        if (index == 1) secondVisited = true
    }
    val currentChild = if (childIndex == 1 && secondChild != null) secondChild else firstChild
    val selected = if (currentChild.id == firstChild.id) first else second
    val scene = selected.lastOrNull { it in InterestOptions.ids } ?: "dinosaurs"
    val scenePaper by animateColorAsState(interestIllustrations.getValue(scene).paper,
        animationSpec = tween(180), label = "차창 아래 색")
    val initialSetup = !editing && !ui.interactionConfigured
    val scroll = rememberScrollState()
    LaunchedEffect(currentChild.id) { scroll.scrollTo(0) }

    BoxWithConstraints(modifier.fillMaxSize().background(PlaymateColors.OnuCream).testTag("family.interests")) {
        val usableWidth = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f)
        val columns = when {
            usableWidth >= 960.dp -> 6
            usableWidth >= 540.dp -> 3
            usableWidth >= 300.dp -> 2
            else -> 1
        }
        val margin = if (wide) 36.dp else 16.dp
        val panoramic = wide && usableWidth >= 960.dp &&
            maxHeight / LocalDensity.current.fontScale.coerceAtLeast(1f) >= 600.dp
        val inlineAction = initialSetup && usableWidth >= 820.dp &&
            maxHeight / LocalDensity.current.fontScale.coerceAtLeast(1f) >= 600.dp
        val commitChanges = first.isNotEmpty() || second.isNotEmpty() ||
            first != firstChild.interests || second != secondChild?.interests.orEmpty()
        val saveChanges = {
            error = if (onSave(first, if (secondChild == null) emptySet() else second)) null
                else "관심사를 저장하지 못했어요. 다시 눌러 주세요."
        }
        val offerSecond = initialSetup && secondChild != null && childIndex == 0 && first.isNotEmpty() && !secondVisited
        val firstAction: @Composable (Modifier) -> Unit = { slot ->
            PrimaryButton(
                when {
                    offerSecond -> "다음 아이 관심사"
                    commitChanges -> "다음"
                    secondChild != null -> "둘 다 건너뛰기"
                    else -> "건너뛰기"
                },
                {
                    when {
                        offerSecond -> editChild(1)
                        commitChanges -> saveChanges()
                        else -> onSkip()
                    }
                },
                enabled = !ui.busy,
                labelStyle = if (panoramic) MaterialTheme.typography.titleLarge.copy(fontSize = 30.sp, lineHeight = 40.sp)
                    else MaterialTheme.typography.labelLarge,
                modifier = slot.testTag(when {
                    offerSecond -> "interests.nextChild"
                    commitChanges -> "interests.save"
                    else -> "interests.skip"
                }),
            )
        }
        val actionError: @Composable () -> Unit = {
            error?.let { message ->
                Text(message, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    .testTag("interests.saveError").semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val visibleHeight = maxHeight
                // A short viewport scrolls the scenery with the choices. Save and cancel stay reachable.
                val enoughHeight = visibleHeight / LocalDensity.current.fontScale.coerceAtLeast(1f) >=
                    (210 + 78 * (6 / columns) + if (secondChild == null) 0 else 84).dp
                Column(
                    Modifier.fillMaxSize().familyScrollIndicator(scroll).verticalScroll(scroll)
                        .heightIn(min = visibleHeight),
                ) {
                    val sceneSize = if (enoughHeight) Modifier.weight(1f) else
                        Modifier.height((visibleHeight * .38f).coerceIn(108.dp, 260.dp))
                    BoxWithConstraints(sceneSize.fillMaxWidth().then(
                        if (initialSetup) Modifier.testTag("setup.step.2") else Modifier
                    )) {
                        Crossfade(scene, Modifier.matchParentSize().testTag("interests.scene.$scene"), animationSpec = tween(180), label = "관심사 풍경") { topic ->
                            val image = if (topic == "dinosaurs") R.drawable.onu_world_dinosaurs else worldIllustration(topic)
                            Image(painterResource(image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        OnuCompanion(
                            Modifier.align(Alignment.BottomStart).offset(x = maxWidth * .22f)
                                .padding(bottom = maxHeight * .045f).width(maxWidth * .24f).fillMaxHeight(.65f),
                            decorative = true,
                        )
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(min = if (inlineAction) visibleHeight * .27f else 0.dp)
                            .background(Brush.verticalGradient(listOf(scenePaper, lerp(scenePaper, PlaymateColors.OnuCream, .35f))))
                            .padding(horizontal = margin, vertical = if (wide) 20.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(if (wide) 14.dp else 8.dp, Alignment.CenterVertically),
                    ) {
                        if (secondChild != null) {
                            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf(firstChild, secondChild).forEachIndexed { index, child ->
                                    SelectButton(
                                        child.nickname, child.id == currentChild.id, { editChild(index) },
                                        Modifier.weight(1f).testTag("interests.child.$index"),
                                    )
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "요즘 뭘 좋아해?", modifier = Modifier.semantics { heading() },
                                    style = when {
                                        panoramic -> MaterialTheme.typography.headlineLarge.copy(fontSize = 60.sp, lineHeight = 74.sp)
                                        wide -> MaterialTheme.typography.headlineLarge
                                        else -> MaterialTheme.typography.headlineMedium
                                    },
                                    color = PlaymateColors.DeepForest,
                                )
                                Text(if (selected.isEmpty()) "여러 개 골라도 돼." else "고른 관심사 ${selected.size}개 · 다시 누르면 뺄 수 있어.", style = if (panoramic) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                                    color = PlaymateColors.Muted)
                            }
                            if (inlineAction) firstAction(Modifier.widthIn(max = if (panoramic) 260.dp else 230.dp))
                        }
                        if (inlineAction) actionError()
                        Column(Modifier.testTag("interests.childTitle").semantics {
                            contentDescription = "${currentChild.nickname}의 관심사"
                        }) {
                            HorizontalDivider(color = PlaymateColors.SoftLine)
                            InterestOptions.all.chunked(columns).forEach { row ->
                                Row(Modifier.fillMaxWidth()) {
                                    row.forEach { interest ->
                                        InterestChoice(
                                            interest, interest.id in selected, !ui.busy, Modifier.weight(1f),
                                            labelBelow = columns == 6 || usableWidth / columns < 160.dp, panoramic = panoramic,
                                        ) { checked ->
                                            val updated = if (checked) selected + interest.id else selected - interest.id
                                            if (currentChild.id == firstChild.id) first = updated else second = updated
                                            error = null
                                        }
                                    }
                                }
                                HorizontalDivider(color = PlaymateColors.SoftLine)
                            }
                        }
                    }
                }
            }
            if (!inlineAction) Column(Modifier.fillMaxWidth().padding(horizontal = margin, vertical = 8.dp)) {
                actionError()
                val save: @Composable (Modifier) -> Unit = { slot ->
                    PrimaryButton(
                        "관심사 저장",
                        saveChanges,
                        enabled = !ui.busy,
                        trailingIcon = FamilyIcon.CHECK,
                        modifier = slot.testTag("interests.save"),
                    )
                }
                val skip: @Composable (Modifier) -> Unit = { slot ->
                    TextButton(
                        onClick = onSkip, enabled = !ui.busy,
                        modifier = slot.heightIn(min = 64.dp).testTag("interests.skip"),
                    ) { Text(if (editing) "변경 취소" else "나중에 고르기") }
                }
                if (initialSetup) firstAction(Modifier.align(Alignment.End).widthIn(max = 260.dp)) else if (usableWidth >= 400.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        skip(Modifier)
                        Spacer(Modifier.width(16.dp))
                        save(Modifier.widthIn(max = 260.dp))
                    }
                } else {
                    save(Modifier.fillMaxWidth())
                    skip(Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
    }
}

private data class InterestIllustration(val drawable: Int, val ink: Color, val paper: Color)

private val interestIllustrations = mapOf(
    "dinosaurs" to InterestIllustration(R.drawable.onu_token_dinosaurs, Color(0xFF35634A), Color(0xFFD6E9D1)),
    "space" to InterestIllustration(R.drawable.onu_token_space, Color(0xFF514777), Color(0xFFDCD9F2)),
    "animals" to InterestIllustration(R.drawable.onu_token_animals, Color(0xFF765634), Color(0xFFE4E5CD)),
    "vehicles" to InterestIllustration(R.drawable.onu_token_vehicles, Color(0xFF994D31), Color(0xFFF5DBBF)),
    "ocean" to InterestIllustration(R.drawable.onu_token_ocean, Color(0xFF236B74), Color(0xFFCBEAE9)),
    "stories" to InterestIllustration(R.drawable.onu_token_stories, Color(0xFF755473), Color(0xFFEADCEC)),
)

@Composable
private fun InterestChoice(
    interest: InterestOption,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    labelBelow: Boolean,
    panoramic: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val art = interestIllustrations.getValue(interest.id)
    val picture: @Composable () -> Unit = {
        Box(Modifier.size(if (panoramic) 108.dp else if (labelBelow) 72.dp else 62.dp)) {
            Image(painterResource(art.drawable), null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            if (selected) Box(Modifier.align(Alignment.BottomEnd).size(if (panoramic) 28.dp else 22.dp).background(art.ink, CircleShape),
                contentAlignment = Alignment.Center) {
                FamilyIcon(FamilyIcon.CHECK, Modifier.size(if (panoramic) 19.dp else 15.dp), tint = Color.White)
            }
        }
    }
    val label: @Composable () -> Unit = {
        Text(interest.label, style = if (panoramic) MaterialTheme.typography.titleLarge.copy(fontSize = 33.sp, lineHeight = 43.sp)
            else MaterialTheme.typography.titleMedium, color = PlaymateColors.DeepForest)
    }
    Box(
        modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .testTag("interest.${interest.id}").heightIn(min = if (labelBelow) 118.dp else 84.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (labelBelow) Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            picture()
            label()
        } else Row(Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            picture()
            label()
        }
        if (selected) Spacer(Modifier.align(Alignment.BottomCenter).fillMaxWidth(.48f).height(if (panoramic) 4.dp else 3.dp)
            .background(art.ink, CircleShape))
    }
}
