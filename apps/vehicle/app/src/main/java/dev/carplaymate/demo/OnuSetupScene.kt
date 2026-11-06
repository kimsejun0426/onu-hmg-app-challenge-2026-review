package dev.carplaymate.demo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Preparation only: this visual sequence never requests speech, microphone or permissions. */
@Composable
internal fun OnuSetupProgress(step: Int, modifier: Modifier = Modifier, compact: Boolean = false) {
    val label = when (step) { 1 -> "별명"; 2 -> "좋아하는 것"; else -> "함께할 방식" }
    Text(if (compact) "$step / 3 · $label" else "보호자와 준비해요 · $step / 3 · $label", modifier.testTag("setup.step.$step"),
        style = MaterialTheme.typography.labelMedium, color = PlaymateColors.Forest)
}

/** One arrival space. Short viewports keep the full form width and a compact companion. */
@Composable
internal fun OnuSetupScene(
    step: Int?,
    title: String,
    detail: String,
    wide: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = if (wide) 28.dp else 20.dp, vertical = 10.dp)) {
        val compactWide = wide && maxHeight < 500.dp
        val expanded = step != null && wide
        val characterHeight = if (compactWide) (maxHeight * .27f).coerceIn(104.dp, 136.dp)
            else (maxHeight * .43f).coerceIn(150.dp, 300.dp)
        val introScroll = rememberScrollState()
        val titleStyle = MaterialTheme.typography.headlineMedium.copy(
            lineBreak = LineBreak.Heading.copy(wordBreak = LineBreak.WordBreak.Phrase))
        if (expanded) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(.31f).fillMaxHeight().familyScrollIndicator(introScroll).verticalScroll(introScroll)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                    OnuCompanion(Modifier.fillMaxWidth().height(characterHeight), OnuMood.READY, decorative = true)
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OnuSetupProgress(requireNotNull(step), compact = compactWide)
                        Text(title, style = titleStyle, modifier = Modifier.semantics { heading() })
                        Text(detail, style = if (compactWide) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            color = PlaymateColors.Muted)
                    }
                }
                content(Modifier.weight(.69f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (step != null) OnuSetupProgress(step, compact = true)
                        Text(title, style = titleStyle, modifier = Modifier.semantics { heading() })
                    }
                    if (step != null) OnuCompanion(Modifier.size(88.dp),
                        OnuMood.READY, decorative = true)
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Muted)
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** A feathered wash follows the greeting as it scrolls; it never adds a card or changes layout. */
internal fun Modifier.onuWelcomeIntroScrim(characterHeight: Dp): Modifier = drawWithCache {
    val introTop = characterHeight.toPx()
    val introHeight = (size.height - introTop).coerceAtLeast(1f)
    val center = Offset(size.width / 2f, introTop + introHeight / 2f)
    // The oval extends past the text edges. Its transparent rim has no rectangular boundary.
    val radiusX = size.width.coerceAtLeast(1f) * 1.05f
    val radiusY = introHeight * .78f
    val paper = PlaymateColors.Ivory.copy(alpha = .97f)
    val wash = Brush.radialGradient(
        0f to paper, .84f to paper, 1f to paper.copy(alpha = 0f),
        center = center, radius = radiusX,
    )
    onDrawBehind {
        // Draw outside Surface's own clip, but behind its character and text.
        scale(scaleX = 1f, scaleY = radiusY / radiusX, pivot = center) {
            drawCircle(brush = wash, radius = radiusX, center = center)
        }
    }
}

/** A welcome preview cannot overlap an owned story/capture or run on a restricted surface. */
internal fun canPreviewWelcome(ui: DeveloperUiState): Boolean =
    !ui.onboarded && !ui.busy && ui.quiet && ui.fullUiAllowed &&
        !ui.microphoneListening && ui.captureStage == null && ui.outputStage == null &&
        ui.snapshot == null && !ui.familyDeletionPending && !ui.localStorageProblem

/** No focus, output, permissions or microphone are requested just by composing this page. */
@Composable
internal fun rememberWelcomeVoicePreview(
    ui: DeveloperUiState,
    provided: WelcomeVoicePreview? = null,
): WelcomeVoicePreview {
    val context = LocalContext.current.applicationContext
    val preview = remember(context, provided) {
        provided ?: WelcomeVoicePreview(
            AndroidScenePlayer(context), AndroidPlaybackAudioFocus(context),
            greeting = { WelcomeVoicePreview.greetingFrom(loadNarrationAssets(context)) },
        )
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val eligible = canPreviewWelcome(ui)
    DisposableEffect(preview, lifecycle, windowFocused, eligible) {
        fun refresh() = preview.setAvailable(
            eligible && windowFocused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        val observer = LifecycleEventObserver { _, _ -> refresh() }
        lifecycle.addObserver(observer)
        refresh()
        onDispose {
            lifecycle.removeObserver(observer)
            preview.setAvailable(false)
        }
    }
    DisposableEffect(preview) { onDispose { preview.shutdown() } }
    return preview
}

/** A single quiet action lives beside the companion's greeting, not in a separate mode/card. */
@Composable
internal fun OnuWelcomeVoiceControl(state: WelcomePreviewState, onToggle: () -> Unit) {
    val active = state.phase == WelcomePreviewPhase.PREPARING || state.phase == WelcomePreviewPhase.PLAYING
    val label = when (state.phase) {
        WelcomePreviewPhase.IDLE -> "오누 목소리 듣기"
        WelcomePreviewPhase.PREPARING -> "준비 중 · 정지"
        WelcomePreviewPhase.PLAYING -> "듣는 중 · 정지"
        WelcomePreviewPhase.FAILED -> "목소리 다시 듣기"
    }
    TextButton(
        onClick = onToggle,
        enabled = state.available,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("welcome.voicePreview"),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FamilyIcon(if (active) FamilyIcon.PAUSE else FamilyIcon.SOUND)
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        }
    }
    if (state.phase == WelcomePreviewPhase.FAILED) Text(
        "목소리를 들려주지 못했어요. 준비는 계속할 수 있어요.",
        modifier = Modifier.testTag("welcome.voicePreviewError")
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall, color = PlaymateColors.Muted,
    )
}
