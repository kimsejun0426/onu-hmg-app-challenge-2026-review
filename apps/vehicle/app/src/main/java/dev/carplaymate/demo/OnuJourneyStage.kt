package dev.carplaymate.demo

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** A continuous authored place; the washes reserve contrast for overlaid words and controls. */
@Composable
internal fun OnuJourneyBackdrop(
    art: String,
    modifier: Modifier = Modifier,
    bottomScrim: Boolean = true,
    setupIntro: Boolean = false,
    activeAdventure: Boolean = false,
) {
    BoxWithConstraints(modifier.clipToBounds()) {
        if (art == "arrival") Image(
            painterResource(R.drawable.onu_arrival_world), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
        ) else WorldArtwork(art, Modifier.fillMaxSize(), narrative = true)
        if (bottomScrim) {
            // Match the page layout; the IME may change height without changing its columns.
            val landscape = maxWidth >= 840.dp && maxWidth / LocalDensity.current.fontScale >= 680.dp
            val paper = PlaymateColors.Ivory
            if (landscape) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to Color.Transparent,
                (if (activeAdventure) .28f else .23f) to Color.Transparent,
                (if (activeAdventure) .41f else .39f) to paper.copy(alpha = .90f),
                .56f to paper.copy(alpha = .98f),
                1f to paper.copy(alpha = .99f),
            )))
            // Setup captions sit directly in the world. Fade the whole scene, not a text-sized card.
            val setupWash = landscape && setupIntro
            val shortSetup = setupWash && maxHeight < 650.dp
            val fadeStart = when {
                activeAdventure && landscape -> .72f
                shortSetup -> .28f
                setupWash -> .43f
                landscape -> .55f
                else -> .27f
            }
            val fadeEnd = when {
                activeAdventure && landscape -> .98f
                shortSetup -> .50f
                setupWash -> .68f
                landscape -> .80f
                else -> .40f
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Transparent,
                fadeStart to Color.Transparent,
                fadeEnd to paper.copy(alpha = .94f),
                1f to paper,
            )))
            // Keep navigation and the progress heading readable over detailed branches.
            val horizonEnd = with(LocalDensity.current) {
                when {
                    activeAdventure -> 220.dp
                    landscape -> 280.dp
                    else -> 320.dp
                }.toPx()
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to paper.copy(alpha = .98f),
                .78f to paper.copy(alpha = .95f),
                1f to Color.Transparent,
                endY = horizonEnd,
            )))
        }
    }
}

/** Reported app state drives the same character in the same place throughout the journey. */
@Composable
internal fun OnuJourneyCharacter(
    ui: DeveloperUiState,
    modifier: Modifier = Modifier,
    mood: OnuMood = onuMood(ui),
) {
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height)
            // ContentScale.Fit centers the square source art inside a non-square layout slot.
            val artTop = (size.height - side) / 2f
            drawOval(
                PlaymateColors.DeepForest.copy(alpha = .12f),
                topLeft = Offset((size.width - side * .44f) / 2f, artTop + side * .945f),
                size = Size(side * .44f, side * .035f),
            )
        }
        // The finite state transition never indicates audio amplitude or inferred emotion.
        Crossfade(
            targetState = mood,
            modifier = Modifier.fillMaxSize().testTag("family.voice.onu"),
            animationSpec = tween(160),
            label = "onuReportedState",
        ) { mood ->
            OnuCompanion(
                Modifier.fillMaxSize(), mood,
                motion = true,
                narrationActive = ui.narrationActive && !ui.quiet && !ui.busy &&
                    !ui.simulation && ui.fullUiAllowed && ui.outputStage == OutputStage.STARTED,
            )
        }
    }
}
