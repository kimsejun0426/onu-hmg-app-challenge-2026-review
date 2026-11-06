package dev.carplaymate.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Topic marks for small cards, quiet scenery for the persistent voice companion stage. */
@Composable
internal fun WorldArtwork(
    key: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    narrative: Boolean = false,
) {
    Box(
        modifier.semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (narrative) AdventureScenery(key, Modifier.fillMaxSize())
        else TopicIcon(key, Modifier.size(88.dp))
    }
}

@Composable
internal fun FamilyCompanion(modifier: Modifier = Modifier, sleeping: Boolean = false) {
    OnuCompanion(modifier, if (sleeping) OnuMood.RESTING else OnuMood.READY)
}

internal enum class FamilyIcon {
    HOME,
    EXPLORE,
    MEMORY,
    FAMILY,
    BACK,
    NEXT,
    SOUND,
    MIC,
    READ,
    PAUSE,
    CHECK,
    SPARK,
}

@Composable
internal fun FamilyIcon(
    kind: FamilyIcon,
    modifier: Modifier = Modifier,
    tint: Color = PlaymateColors.Forest,
) {
    Canvas(modifier.size(28.dp)) {
        val u = size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, p(x1, y1), p(x2, y2), 2.2f * u, StrokeCap.Round)
        fun path(vararg points: IconPoint, close: Boolean = false) {
            drawPath(
                Path().apply {
                    moveTo(points[0].x * u, points[0].y * u)
                    points.drop(1).forEach { lineTo(it.x * u, it.y * u) }
                    if (close) this.close()
                },
                tint,
                style = Stroke(2.1f * u, cap = StrokeCap.Round),
            )
        }
        when (kind) {
            FamilyIcon.HOME -> {
                path(IconPoint(3f, 11f), IconPoint(12f, 3f), IconPoint(21f, 11f))
                path(
                    IconPoint(5f, 10f),
                    IconPoint(5f, 21f),
                    IconPoint(10f, 21f),
                    IconPoint(10f, 15f),
                    IconPoint(14f, 15f),
                    IconPoint(14f, 21f),
                    IconPoint(19f, 21f),
                    IconPoint(19f, 10f),
                )
            }
            FamilyIcon.EXPLORE -> {
                drawCircle(tint, 9 * u, p(12f, 12f), style = Stroke(2 * u))
                path(
                    IconPoint(8f, 16f),
                    IconPoint(10f, 9f),
                    IconPoint(17f, 7f),
                    IconPoint(14f, 14f),
                    close = true,
                )
            }
            FamilyIcon.MEMORY -> {
                path(
                    IconPoint(3f, 5f),
                    IconPoint(10f, 5f),
                    IconPoint(12f, 7f),
                    IconPoint(14f, 5f),
                    IconPoint(21f, 5f),
                    IconPoint(21f, 20f),
                    IconPoint(14f, 20f),
                    IconPoint(12f, 22f),
                    IconPoint(10f, 20f),
                    IconPoint(3f, 20f),
                    close = true,
                )
                line(12f, 7f, 12f, 20f)
            }
            FamilyIcon.FAMILY -> {
                drawCircle(tint, 3 * u, p(8f, 7f), style = Stroke(2 * u))
                drawCircle(tint, 2.5f * u, p(17f, 9f), style = Stroke(2 * u))
                drawArc(
                    tint,
                    180f,
                    180f,
                    false,
                    p(2f, 13f),
                    androidx.compose.ui.geometry.Size(12 * u, 12 * u),
                    style = Stroke(2 * u),
                )
                drawArc(
                    tint,
                    180f,
                    180f,
                    false,
                    p(13f, 15f),
                    androidx.compose.ui.geometry.Size(8 * u, 8 * u),
                    style = Stroke(2 * u),
                )
            }
            FamilyIcon.BACK -> {
                line(19f, 12f, 5f, 12f)
                line(5f, 12f, 11f, 6f)
                line(5f, 12f, 11f, 18f)
            }
            FamilyIcon.NEXT -> {
                line(5f, 12f, 19f, 12f)
                line(19f, 12f, 13f, 6f)
                line(19f, 12f, 13f, 18f)
            }
            FamilyIcon.PAUSE -> {
                line(8f, 5f, 8f, 19f)
                line(16f, 5f, 16f, 19f)
            }
            FamilyIcon.CHECK -> path(IconPoint(4f, 12f), IconPoint(9f, 17f), IconPoint(20f, 6f))
            FamilyIcon.MIC -> {
                drawRoundRect(
                    tint,
                    p(9f, 3f),
                    androidx.compose.ui.geometry.Size(6 * u, 12 * u),
                    androidx.compose.ui.geometry.CornerRadius(3 * u),
                    style = Stroke(2 * u),
                )
                drawArc(
                    tint,
                    0f,
                    180f,
                    false,
                    p(5f, 8f),
                    androidx.compose.ui.geometry.Size(14 * u, 11 * u),
                    style = Stroke(2 * u),
                )
                line(12f, 19f, 12f, 22f)
            }
            FamilyIcon.SOUND -> {
                path(
                    IconPoint(3f, 9f),
                    IconPoint(7f, 9f),
                    IconPoint(12f, 5f),
                    IconPoint(12f, 19f),
                    IconPoint(7f, 15f),
                    IconPoint(3f, 15f),
                    close = true,
                )
                drawArc(
                    tint,
                    -60f,
                    120f,
                    false,
                    p(10f, 5f),
                    androidx.compose.ui.geometry.Size(12 * u, 14 * u),
                    style = Stroke(2 * u),
                )
            }
            FamilyIcon.READ -> {
                path(
                    IconPoint(5f, 3f),
                    IconPoint(19f, 3f),
                    IconPoint(19f, 21f),
                    IconPoint(5f, 21f),
                    close = true,
                )
                line(8f, 8f, 16f, 8f)
                line(8f, 12f, 16f, 12f)
                line(8f, 16f, 13f, 16f)
            }
            FamilyIcon.SPARK -> {
                path(
                    IconPoint(12f, 2f),
                    IconPoint(15f, 9f),
                    IconPoint(22f, 12f),
                    IconPoint(15f, 15f),
                    IconPoint(12f, 22f),
                    IconPoint(9f, 15f),
                    IconPoint(2f, 12f),
                    IconPoint(9f, 9f),
                    close = true,
                )
            }
        }
    }
}

private data class IconPoint(val x: Float, val y: Float)
