package dev.carplaymate.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

internal enum class OnuMood(val description: String) {
    READY("함께할 준비가 된 오누"),
    LISTENING("목소리를 듣고 있는 오누"),
    THINKING("다음 장면을 준비하는 오누"),
    READING("함께 읽는 오누"),
    STORYTELLING("이야기를 함께하는 오누"),
    WAITING("선택을 기다리는 오누"),
    RESTING("쉬고 있는 오누"),
    CELEBRATING("함께한 이야기를 기뻐하는 오누"),
}

/** Listening is shown only while the microphone reports an active capture. */
internal fun onuMood(ui: DeveloperUiState): OnuMood =
    when {
        ui.quiet -> OnuMood.RESTING
        ui.busy || ui.snapshot == null -> OnuMood.THINKING
        ui.microphoneListening -> OnuMood.LISTENING
        ui.captureStage == CaptureStage.PROCESSING -> OnuMood.THINKING
        ui.snapshot.phase == "LISTENING" -> OnuMood.WAITING
        ui.simulation -> OnuMood.READING
        ui.outputStage == OutputStage.STARTED -> OnuMood.STORYTELLING
        else -> OnuMood.THINKING
    }

/**
 * Authored visual acting only, never a microphone level or phoneme lip-sync.
 * Motion is opt-in. Every sequence stops outside RESUMED or when its input changes.
 * A steady closed mouth is the fallback for gaps, reduced motion and unsupported outputs.
 */
@Composable
internal fun OnuCompanion(
    modifier: Modifier = Modifier,
    mood: OnuMood = OnuMood.READY,
    decorative: Boolean = false,
    motion: Boolean = false,
    narrationActive: Boolean = false,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    // Remember keys make cancellation visually immediate, before a delayed coroutine can resume.
    var frame by remember(mood, motion, narrationActive, visible) { mutableIntStateOf(0) }
    LaunchedEffect(mood, motion, narrationActive, visible) {
        if (!motion || !visible) return@LaunchedEffect
        val durationScale = coroutineContext[MotionDurationScale]
        fun scale() = durationScale?.scaleFactor ?: 1f
        if (scale() <= 0f) return@LaunchedEffect
        if (mood == OnuMood.READY) {
            // One short greeting blink. Home and setup do not keep animating while waiting.
            delay((620L * scale()).toLong())
            if (scale() <= 0f) return@LaunchedEffect
            frame = 1
            delay((110L * scale()).toLong())
            frame = 0
        } else if (mood == OnuMood.STORYTELLING && narrationActive) {
            // Restrained hand-drawn syllable poses, gated by the current utterance callbacks.
            val poses = intArrayOf(1, 2, 1, 0, 1, 0)
            val holds = longArrayOf(180, 220, 160, 150, 210, 180)
            while (true) {
                for (index in poses.indices) {
                    if (scale() <= 0f) {
                        frame = 0
                        return@LaunchedEffect
                    }
                    frame = poses[index]
                    delay((holds[index] * scale()).toLong().coerceAtLeast(1L))
                }
            }
        }
    }
    val description = if (decorative) null else mood.description
    if (motion && mood == OnuMood.STORYTELLING) {
        // The silhouette never changes between syllables. Only the soft mouth patch is composited.
        Box(modifier.semantics { if (description != null) contentDescription = description }) {
            Image(painterResource(R.drawable.onu_speaking_closed), null,
                Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            if (frame != 0) Image(
                painterResource(if (frame == 1) R.drawable.onu_speaking_small else R.drawable.onu_storytelling),
                null, Modifier.matchParentSize().onuFacePatch(.446f, .381f, .072f),
                contentScale = ContentScale.Fit,
            )
        }
    } else if (motion && mood == OnuMood.READY && frame == 1) {
        Box(modifier.semantics { if (description != null) contentDescription = description }) {
            Image(painterResource(R.drawable.onu_ready), null,
                Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            // Keep the hand, scarf and head outline still during the single greeting blink.
            for ((x, y) in listOf(.382f to .308f, .543f to .350f)) {
                Image(painterResource(R.drawable.onu_ready_blink), null,
                    Modifier.matchParentSize().onuFacePatch(x, y, .044f),
                    contentScale = ContentScale.Fit)
            }
        }
    } else Image(
        painter = painterResource(onuIllustration(mood)),
        contentDescription = description,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/** Face-first framing for the 32/64 dp presence mark; the bundled PNG stays intact. */
@Composable
internal fun OnuPortrait(
    modifier: Modifier = Modifier,
    mood: OnuMood = OnuMood.READY,
    decorative: Boolean = false,
) {
    // The compact boy poses share a large head in their upper half. Clip the enlarged
    // drawing to the existing slot instead of taking space from the status text.
    Box(modifier.clipToBounds()) {
        Image(
            painter = painterResource(onuIllustration(mood)),
            contentDescription = if (decorative) null else mood.description,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = 1.8f
                scaleY = 1.8f
                transformOrigin = TransformOrigin(.5f, 0f)
            },
            alignment = Alignment.TopCenter,
            contentScale = ContentScale.Fit,
        )
    }
}

private fun onuIllustration(mood: OnuMood): Int = when (mood) {
    OnuMood.READY, OnuMood.READING, OnuMood.WAITING -> R.drawable.onu_ready
    OnuMood.LISTENING -> R.drawable.onu_listening
    OnuMood.THINKING -> R.drawable.onu_thinking
    OnuMood.STORYTELLING -> R.drawable.onu_storytelling
    OnuMood.RESTING -> R.drawable.onu_resting
    OnuMood.CELEBRATING -> R.drawable.onu_delighted
}

/** Coordinates are normalized to the square source art, also when its layout slot is not square. */
private fun Modifier.onuFacePatch(x: Float, y: Float, radius: Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val side = minOf(size.width, size.height)
        val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        drawRect(
            brush = Brush.radialGradient(
                0f to Color.Black, .70f to Color.Black, 1f to Color.Transparent,
                center = origin + Offset(side * x, side * y), radius = side * radius,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
