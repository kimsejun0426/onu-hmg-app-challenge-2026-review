package dev.carplaymate.demo

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * A place to meet Onu before choosing a story. The entrance is a finite visual
 * transition only: it never requests speech, changes a mode, or advances a turn.
 * Compose's animation clock applies the system duration scale, including zero.
 */
@Composable
internal fun OnuArrivalScene(
    modifier: Modifier = Modifier,
    art: String? = null,
    mood: OnuMood = OnuMood.READY,
    entrance: Boolean = true,
    shape: Shape = RoundedCornerShape(28.dp),
) {
    var entered by remember(art) { mutableStateOf(false) }
    LaunchedEffect(art, entrance) { entered = true }
    val progress by animateFloatAsState(
        targetValue = if (!entrance || entered) 1f else 0f,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "onuArrival",
    )
    val arrivalDistance = with(LocalDensity.current) { 12.dp.toPx() }
    BoxWithConstraints(
        modifier.clip(shape),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                // A single settling movement; no continuous pan or simulated vehicle motion.
                scaleX = 1f + (1f - progress) * .025f
                scaleY = scaleX
            },
        ) {
            if (art == null) {
                Image(
                    painter = painterResource(R.drawable.onu_arrival_world),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    alignment = Alignment.Center,
                    contentScale = ContentScale.Crop,
                )
            } else {
                WorldArtwork(art, Modifier.fillMaxSize(), narrative = true)
            }
        }
        val companionSize = minOf(maxHeight * .96f, maxWidth * .84f, 400.dp)
        // A quiet contact shadow connects the cutout to the illustrated foreground.
        Box(
            Modifier.width(companionSize * .42f).height(10.dp).offset(y = (-8).dp)
                .graphicsLayer { alpha = .5f + progress * .5f }
                .background(Color(0x241B3935), RoundedCornerShape(50)),
        )
        OnuCompanion(
            modifier = Modifier.size(companionSize).padding(bottom = 6.dp).graphicsLayer {
                alpha = .35f + progress * .65f
                translationY = (1f - progress) * arrivalDistance
            },
            mood = mood,
            decorative = true,
            motion = entrance,
        )
    }
}
