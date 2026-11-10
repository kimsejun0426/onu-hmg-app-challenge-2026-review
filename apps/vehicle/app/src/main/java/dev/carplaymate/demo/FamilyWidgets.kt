package dev.carplaymate.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

@Composable
internal fun VoiceRecovery(onSettings: () -> Unit, onRead: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = PlaymateColors.Sand.copy(alpha = 0.55f)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("함께할 목소리를 준비해 주세요", style = MaterialTheme.typography.titleMedium)
            Text(
                "한국어 기기 음성을 확인하고 있어요. 음성을 사용할 수 없다면, 보호자와 읽으며 체험할 수 있어요.",
                style = MaterialTheme.typography.bodyMedium,
                color = PlaymateColors.Muted,
            )
            ActionPair(
                first = { slot ->
                    TextButton(onClick = onSettings, modifier = slot.heightIn(min = 72.dp)) {
                        Text("음성 설정")
                    }
                },
                second = { slot ->
                    OutlinedButton(onClick = onRead, modifier = slot.heightIn(min = 72.dp)) {
                        Text("읽으며 체험 선택")
                    }
                },
            )
        }
    }
}

@Composable
internal fun ActionPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth / LocalDensity.current.fontScale >= 440.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        } else
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
    }
}

@Composable
internal fun AdaptiveColumns(
    wide: Boolean,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    if (wide)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.weight(1f)) { first() }
            Box(Modifier.weight(1f)) { second() }
        }
    else
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            first()
            second()
        }
}

@Composable
internal fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    trailingIcon: FamilyIcon? = FamilyIcon.NEXT,
    labelStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 80.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = PlaymateColors.Mist,
            disabledContentColor = PlaymateColors.Muted,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = labelStyle)
        if (trailingIcon != null) {
            Spacer(Modifier.width(12.dp))
            // One action, one surface: the glyph is part of the button, not a second control.
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                FamilyIcon(trailingIcon, tint = LocalContentColor.current)
            }
        }
    }
}

@Composable
internal fun SelectButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 72.dp).semantics {
            this.selected = selected
            role = Role.RadioButton
        },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(2.dp, if (selected) PlaymateColors.Forest else PlaymateColors.Line),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = if (selected) PlaymateColors.Mist else PlaymateColors.Paper
            ),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.size(24.dp).clearAndSetSemantics { },
            colors = RadioButtonDefaults.colors(
                selectedColor = PlaymateColors.Forest,
                unselectedColor = PlaymateColors.Muted,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun Eyebrow(text: String) {
    Surface(color = PlaymateColors.Mist, shape = RoundedCornerShape(10.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = PlaymateColors.Forest)
    }
}

@Composable
internal fun Notice(text: String) {
    Text(
        text,
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(PlaymateColors.Sand.copy(alpha = 0.55f))
                .padding(14.dp),
        style = MaterialTheme.typography.bodySmall,
        color = PlaymateColors.Ink,
    )
}

@Composable
internal fun PauseGlyph(modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "쉬기" }) {
        drawLine(
            PlaymateColors.Paper,
            Offset(size.width * 0.3f, size.height * 0.15f),
            Offset(size.width * 0.3f, size.height * 0.85f),
            4.dp.toPx(),
            StrokeCap.Round,
        )
        drawLine(
            PlaymateColors.Paper,
            Offset(size.width * 0.7f, size.height * 0.15f),
            Offset(size.width * 0.7f, size.height * 0.85f),
            4.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

internal fun modeName(ui: DeveloperUiState): String =
    when {
        ui.simulation -> "읽으며 체험"
        ui.voiceInput -> "음성으로 함께"
        else -> "듣고 선택하기"
    }

internal fun familyNames(ui: DeveloperUiState): String =
    if (ui.twoPlayers) "${ui.firstNickname} · ${ui.secondNickname}" else ui.firstNickname

internal fun currentPlayer(snapshot: Snapshot): Pair<String, String> {
    val expected = snapshot.game.optString("expected_player_id")
    val players = snapshot.game.optJSONArray("players") ?: return "친구" to "탐험가"
    for (i in 0 until players.length()) {
        val player = players.optJSONObject(i) ?: continue
        if (player.optString("player_id") == expected)
            return player.optString("nickname", "친구") to player.optString("role", "탐험가")
    }
    return "친구" to "탐험가"
}

/** A decorative position cue; native verticalScroll retains touch and accessibility actions.
 * Put this before verticalScroll so the cue remains in the viewport. trailingOffset may
 * place it in an existing parent gutter without reducing the width of a long question.
 */
internal fun Modifier.familyScrollIndicator(
    scroll: ScrollState,
    trailingOffset: Dp = 0.dp,
): Modifier = drawWithContent {
    drawContent()
    val maximum = scroll.maxValue
    val viewport = scroll.viewportSize
    if (maximum > 0 && maximum != Int.MAX_VALUE && viewport > 0) {
        val inset = 6.dp.toPx()
        val track = size.height - 2 * inset
        if (track > 0f) {
            val thumb = (track * viewport.toFloat() / (viewport.toFloat() + maximum))
                .coerceIn(minOf(28.dp.toPx(), track), track)
            val progress = (scroll.value.toFloat() / maximum).coerceIn(0f, 1f)
            val top = inset + (track - thumb) * progress
            val x = if (layoutDirection == LayoutDirection.Ltr)
                size.width - 3.dp.toPx() + trailingOffset.toPx()
            else 3.dp.toPx() - trailingOffset.toPx()
            drawLine(PlaymateColors.Sage, Offset(x, inset), Offset(x, inset + track),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(PlaymateColors.Forest, Offset(x, top), Offset(x, top + thumb),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
