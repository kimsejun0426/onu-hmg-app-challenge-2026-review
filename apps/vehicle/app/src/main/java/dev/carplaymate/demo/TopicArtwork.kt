package dev.carplaymate.demo

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate

/** A single 32-unit, rounded-line vocabulary; topic marks are labels, never story clues. */
@Composable
internal fun TopicIcon(
    key: String,
    modifier: Modifier = Modifier,
    tint: Color = PlaymateColors.Forest,
) {
    Canvas(modifier) {
        val u = size.minDimension / 32f
        val pen = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun pt(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(a: Float, b: Float, c: Float, d: Float) =
            drawLine(tint, pt(a, b), pt(c, d), pen.width, StrokeCap.Round)
        fun shape(block: Path.() -> Unit) = drawPath(Path().apply(block), tint, style = pen)
        translate((size.width - 32 * u) / 2, (size.height - 32 * u) / 2) {
            when (key) {
                "little-picnic" -> {
                    drawCircle(tint.copy(alpha = .12f), 5 * u, pt(24f, 7f))
                    drawCircle(tint, 3 * u, pt(24f, 7f), style = pen)
                    shape {
                        moveTo(3 * u, 26 * u)
                        cubicTo(8 * u, 9 * u, 16 * u, 10 * u, 22 * u, 26 * u)
                    }
                    shape {
                        moveTo(17 * u, 26 * u)
                        cubicTo(21 * u, 19 * u, 26 * u, 18 * u, 30 * u, 26 * u)
                    }
                    line(3f, 27f, 30f, 27f)
                }
                "space" -> {
                    drawCircle(tint.copy(alpha = .08f), 9 * u, pt(16f, 16f))
                    drawCircle(tint, 8 * u, pt(16f, 16f), style = pen)
                    shape {
                        moveTo(9 * u, 10 * u)
                        cubicTo(-2 * u, 16 * u, 6 * u, 25 * u, 22 * u, 20 * u)
                        cubicTo(33 * u, 16 * u, 29 * u, 10 * u, 24 * u, 11 * u)
                    }
                    drawCircle(tint, 1.2f * u, pt(27f, 5f))
                }
                "animals" -> {
                    drawOval(tint, pt(6f, 5f), Size(5 * u, 7 * u), style = pen)
                    drawOval(tint, pt(14f, 3f), Size(5 * u, 7 * u), style = pen)
                    drawOval(tint, pt(22f, 7f), Size(5 * u, 7 * u), style = pen)
                    shape {
                        moveTo(8 * u, 21 * u)
                        cubicTo(10 * u, 17 * u, 12 * u, 13 * u, 16 * u, 14 * u)
                        cubicTo(21 * u, 14 * u, 26 * u, 22 * u, 23 * u, 26 * u)
                        cubicTo(21 * u, 29 * u, 18 * u, 25 * u, 15 * u, 26 * u)
                        cubicTo(8 * u, 29 * u, 5 * u, 26 * u, 8 * u, 21 * u)
                        close()
                    }
                }
                "vehicles" -> {
                    shape {
                        moveTo(5 * u, 15 * u)
                        lineTo(9 * u, 7 * u)
                        lineTo(23 * u, 7 * u)
                        lineTo(27 * u, 15 * u)
                        lineTo(28 * u, 24 * u)
                        lineTo(4 * u, 24 * u)
                        close()
                    }
                    line(5f, 15f, 27f, 15f)
                    line(8f, 24f, 8f, 28f)
                    line(24f, 24f, 24f, 28f)
                    line(8f, 19f, 11f, 19f)
                    line(21f, 19f, 24f, 19f)
                }
                "ocean",
                "undersea-lighthouse",
                "undersea_lighthouse" -> {
                    for (y in listOf(11f, 19f, 27f)) shape {
                        moveTo(3 * u, y * u)
                        cubicTo(8 * u, (y - 6) * u, 11 * u, (y + 6) * u, 16 * u, y * u)
                        cubicTo(21 * u, (y - 6) * u, 24 * u, (y + 6) * u, 29 * u, y * u)
                    }
                }
                "stories",
                "starlight-post-office",
                "starlight_post" -> {
                    shape {
                        moveTo(16 * u, 9 * u)
                        cubicTo(11 * u, 6 * u, 7 * u, 5 * u, 3 * u, 6 * u)
                        lineTo(3 * u, 25 * u)
                        cubicTo(7 * u, 24 * u, 11 * u, 25 * u, 16 * u, 28 * u)
                        cubicTo(21 * u, 25 * u, 25 * u, 24 * u, 29 * u, 25 * u)
                        lineTo(29 * u, 6 * u)
                        cubicTo(25 * u, 5 * u, 21 * u, 6 * u, 16 * u, 9 * u)
                        close()
                    }
                    line(16f, 9f, 16f, 28f)
                    line(7f, 13f, 12f, 15f)
                    line(20f, 15f, 25f, 13f)
                }
                else -> {
                    shape {
                        moveTo(4 * u, 23 * u)
                        lineTo(8 * u, 20 * u)
                        lineTo(16 * u, 20 * u)
                        lineTo(16 * u, 8 * u)
                        cubicTo(16 * u, 4 * u, 22 * u, 3 * u, 25 * u, 6 * u)
                        lineTo(28 * u, 8 * u)
                        lineTo(28 * u, 13 * u)
                        lineTo(22 * u, 13 * u)
                        lineTo(22 * u, 26 * u)
                        lineTo(18 * u, 26 * u)
                        lineTo(17 * u, 23 * u)
                        lineTo(11 * u, 23 * u)
                        lineTo(10 * u, 26 * u)
                        lineTo(6 * u, 26 * u)
                        close()
                    }
                    drawCircle(tint, .9f * u, pt(23f, 8f))
                }
            }
        }
    }
}
