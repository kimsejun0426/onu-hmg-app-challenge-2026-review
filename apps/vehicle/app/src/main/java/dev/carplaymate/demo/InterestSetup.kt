package dev.carplaymate.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private val interestSelectionSaver =
    listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** Explicit, optional choices for each child; no interests are inferred from a nickname. */
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
    var first by
        rememberSaveable(firstChild.id, stateSaver = interestSelectionSaver) {
            mutableStateOf(firstChild.interests)
        }
    var second by
        rememberSaveable(secondChild?.id, stateSaver = interestSelectionSaver) {
            mutableStateOf(secondChild?.interests.orEmpty())
        }
    var childIndex by rememberSaveable(firstChild.id, secondChild?.id) {
        mutableIntStateOf(if (firstChild.interestsConfigured && secondChild?.interestsConfigured == false) 1 else 0)
    }
    var error by remember { mutableStateOf<String?>(null) }
    val currentChild = if (childIndex == 1 && secondChild != null) secondChild else firstChild
    val selected = if (currentChild.id == firstChild.id) first else second
    val scroll = rememberScrollState()
    LaunchedEffect(currentChild.id) { scroll.scrollTo(0) }

    val initialSetup = !editing && !ui.interactionConfigured
    OnuSetupScene(
        step = if (initialSetup) 2 else null,
        title = "요즘 무엇을 좋아하나요?",
        detail = if (editing) "여러 개 골라도 괜찮아요. 저장하면 추천에 반영돼요."
            else "여러 개 골라도, 나중에 골라도 괜찮아요.",
        wide = wide,
        modifier = modifier,
    ) { contentModifier ->
        Column(contentModifier.fillMaxSize().testTag("family.interests")) {
            Column(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .familyScrollIndicator(scroll, trailingOffset = 4.dp)
                    .verticalScroll(scroll)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (secondChild != null) {
                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectButton(
                            firstChild.nickname,
                            currentChild.id == firstChild.id,
                            { childIndex = 0 },
                            Modifier.weight(1f).testTag("interests.child.0"),
                        )
                        SelectButton(
                            secondChild.nickname,
                            currentChild.id == secondChild.id,
                            { childIndex = 1 },
                            Modifier.weight(1f).testTag("interests.child.1"),
                        )
                    }
                }
                BoxWithConstraints(
                    Modifier.fillMaxWidth().testTag("interests.childTitle")
                        .clearAndSetSemantics {
                            contentDescription = "${currentChild.nickname}의 관심사"
                            heading()
                        },
                ) {
                    // Keep the short dashboard's choices close; enlarged text can fall back to two lines.
                    if (wide && maxWidth / LocalDensity.current.fontScale >= 440.dp) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(currentChild.nickname,
                                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                                style = MaterialTheme.typography.titleMedium)
                            Text("관심사", modifier = Modifier.alignByBaseline(),
                                style = MaterialTheme.typography.labelMedium, color = PlaymateColors.Forest)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(currentChild.nickname, style = MaterialTheme.typography.titleMedium)
                            Text("관심사", style = MaterialTheme.typography.labelMedium, color = PlaymateColors.Forest)
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth / LocalDensity.current.fontScale >= 660.dp) 3 else 2
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        InterestOptions.all.chunked(columns).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { interest ->
                                    InterestChoice(
                                        interest,
                                        interest.id in selected,
                                        enabled = !ui.busy,
                                        modifier = Modifier.weight(1f),
                                    ) { checked ->
                                        val updated =
                                            if (checked) selected + interest.id
                                            else selected - interest.id
                                        if (currentChild.id == firstChild.id) first = updated
                                        else second = updated
                                        error = null
                                    }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                Text(
                    "선택한 관심사로 지금 준비된 모험을 추천해요. 선택은 이 기기에만 저장되고, 보호자 설정에서 바꿀 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PlaymateColors.Muted,
                )
            }
            Surface(color = Color.Transparent) {
                Column(Modifier.fillMaxWidth()) {
                    if (error != null) {
                        Text(
                            error.orEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)
                                .testTag("interests.saveError").semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val save: @Composable (Modifier) -> Unit = { slot ->
                        PrimaryButton(
                            if (initialSetup) "저장하고 다음으로" else "관심사 저장",
                            {
                                error = if (onSave(first, if (secondChild == null) emptySet() else second)) null
                                    else "관심사를 저장하지 못했어요. 다시 눌러 주세요."
                            },
                            enabled = !ui.busy,
                            trailingIcon = if (initialSetup) FamilyIcon.NEXT else FamilyIcon.CHECK,
                            modifier = slot.testTag("interests.save"),
                        )
                    }
                    val skip: @Composable (Modifier) -> Unit = { slot ->
                        TextButton(
                            onClick = onSkip,
                            enabled = !ui.busy,
                            modifier = slot.heightIn(min = 64.dp).testTag("interests.skip"),
                        ) {
                            Text(if (editing) "변경 취소" else "나중에 고르기")
                        }
                    }
                    if (wide)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            save(Modifier.weight(2f))
                            skip(Modifier.weight(1f))
                        }
                    else
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            save(Modifier.fillMaxWidth())
                            skip(Modifier.fillMaxWidth())
                        }
                }
            }
        }
    }
}

@Composable
private fun InterestChoice(
    interest: InterestOption,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onChange: (Boolean) -> Unit,
) {
    Surface(
        modifier =
            modifier
                .heightIn(min = 80.dp)
                .toggleable(
                    value = selected,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onChange,
                )
                .testTag("interest.${interest.id}"),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) PlaymateColors.Mist else PlaymateColors.Paper,
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) PlaymateColors.Forest else PlaymateColors.Line,
            ),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TopicIcon(interest.id, Modifier.size(28.dp))
            Text(interest.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Checkbox(
                selected,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
