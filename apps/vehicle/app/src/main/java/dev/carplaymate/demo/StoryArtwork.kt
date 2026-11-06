package dev.carplaymate.demo

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Small, bundled illustrations. They do not fetch artwork, fonts, or tracking resources. */
@Composable
internal fun CompanionMark(modifier: Modifier = Modifier, sleeping: Boolean = false) {
    OnuCompanion(modifier, if (sleeping) OnuMood.RESTING else OnuMood.READY)
}

@Composable
internal fun DinosaurIsland(modifier: Modifier = Modifier, night: Boolean = false, found: Boolean = false) {
    Canvas(modifier.semantics { contentDescription = if (night) "별빛 아래 쉬는 공룡 연구소" else "물가와 숲이 이어지는 상상 속 공룡 연구소" }) {
        // Tighter framing keeps faces readable instead of shrinking them into empty sky.
        val unit = minOf(size.width / 560f, size.height / 410f)
        translate((size.width - 560 * unit) / 2 - 20 * unit, (size.height - 410 * unit) / 2 - 15 * unit) {
            scale(unit, unit, pivot = Offset.Zero) {
                val air = if (night) Color(0xFFDCE2DC) else Color(0xFFE7ECDf)
                drawCircle(air, 183f, Offset(312f, 207f))
                drawCircle(if (night) PlaymateColors.Paper else PlaymateColors.Amber, 33f, Offset(461f, 85f))
                if (night) drawCircle(air, 30f, Offset(475f, 72f))
                cloud(109f, 97f, 0.8f)
                cloud(345f, 48f, 0.55f)
                drawOval(Color(0xFFD4DCCF), Offset(72f, 353f), Size(470f, 46f))
                val island = Path().apply {
                    moveTo(60f, 291f); cubicTo(72f, 234f, 162f, 218f, 200f, 207f)
                    cubicTo(258f, 188f, 266f, 178f, 330f, 194f)
                    cubicTo(399f, 211f, 498f, 215f, 541f, 274f)
                    cubicTo(575f, 319f, 508f, 365f, 429f, 377f)
                    cubicTo(339f, 394f, 195f, 388f, 118f, 354f)
                    cubicTo(73f, 334f, 53f, 312f, 60f, 291f); close()
                }
                drawPath(island, Color(0xFFC7D3B8))
                drawPath(island, Color(0xFF819674), style = Stroke(3f))
                drawPath(Path().apply {
                    moveTo(69f, 298f); cubicTo(193f, 275f, 242f, 343f, 350f, 374f)
                    lineTo(410f, 378f); cubicTo(302f, 317f, 207f, 250f, 87f, 270f); close()
                }, Color(0xFFACC9C1))
                drawPath(Path().apply {
                    moveTo(136f, 341f); cubicTo(183f, 335f, 196f, 309f, 245f, 294f)
                    cubicTo(286f, 280f, 341f, 290f, 373f, 270f)
                    cubicTo(403f, 251f, 415f, 235f, 438f, 231f)
                }, PlaymateColors.Sand, style = Stroke(26f, cap = StrokeCap.Round))
                // The little glass research house and its quiet forest.
                tree(164f, 211f, 0.83f); tree(120f, 232f, 0.66f)
                tree(472f, 258f, 0.83f); tree(514f, 282f, 0.6f)
                tree(346f, 209f, 0.65f)
                drawRoundRect(Color(0xFF6E8C72), Offset(365f, 180f), Size(81f, 58f), CornerRadius(7f))
                drawPath(Path().apply { moveTo(357f, 181f); lineTo(406f, 144f); lineTo(454f, 181f); close() }, PlaymateColors.Forest)
                drawRoundRect(PlaymateColors.Sand, Offset(382f, 194f), Size(17f, 22f), CornerRadius(4f))
                drawRoundRect(Color(0xFFAFC5B9), Offset(412f, 194f), Size(19f, 43f), CornerRadius(4f))
                drawLine(PlaymateColors.Forest, Offset(421f, 196f), Offset(421f, 233f), 5f)
                scale(1.22f, 1.22f, Offset(230f, 242f)) { dinosaur(230f, 242f) }
                // A little transport robot waits at the fork.
                scale(1.2f, 1.2f, Offset(413f, 298f)) {
                    drawOval(Color(0xFFB7C5A8), Offset(382f, 308f), Size(60f, 13f))
                    drawRoundRect(PlaymateColors.Amber, Offset(390f, 280f), Size(44f, 31f), CornerRadius(10f))
                    drawCircle(PlaymateColors.Forest, 7f, Offset(399f, 313f))
                    drawCircle(PlaymateColors.Forest, 7f, Offset(427f, 313f))
                    drawCircle(PlaymateColors.Forest, 4f, Offset(403f, 293f))
                    drawCircle(PlaymateColors.Forest, 4f, Offset(422f, 293f))
                    drawLine(PlaymateColors.Forest, Offset(413f, 281f), Offset(413f, 272f), 4f, StrokeCap.Round)
                    drawCircle(PlaymateColors.Forest, 4f, Offset(413f, 270f))
                }
                if (found) {
                    drawOval(PlaymateColors.Paper, Offset(326f, 286f), Size(52f, 70f))
                    drawOval(PlaymateColors.Forest, Offset(326f, 286f), Size(52f, 70f), style = Stroke(3.5f))
                    drawOval(PlaymateColors.Amber, Offset(338f, 310f), Size(13f, 18f))
                    spark(314f, 291f); spark(385f, 338f)
                }
                drawLine(Color(0xFF76917B), Offset(99f, 320f), Offset(103f, 307f), 3f, StrokeCap.Round)
                drawLine(Color(0xFF76917B), Offset(103f, 319f), Offset(111f, 311f), 3f, StrokeCap.Round)
                spark(510f, 154f)
            }
        }
    }
}

private fun DrawScope.tree(x: Float, y: Float, factor: Float) {
    translate(x, y) { scale(factor, factor, Offset.Zero) {
        drawLine(PlaymateColors.Forest, Offset.Zero, Offset(0f, -54f), 11f, StrokeCap.Round)
        drawOval(Color(0xFF526F4A), Offset(-27f, -104f), Size(54f, 72f))
        drawOval(Color(0xFF829D73), Offset(-19f, -102f), Size(35f, 65f))
    } }
}

private fun DrawScope.cloud(x: Float, y: Float, factor: Float) {
    translate(x, y) { scale(factor, factor, Offset.Zero) {
        drawCircle(PlaymateColors.Paper, 19f, Offset.Zero)
        drawCircle(PlaymateColors.Paper, 27f, Offset(30f, -10f))
        drawCircle(PlaymateColors.Paper, 20f, Offset(58f, 0f))
        drawRoundRect(PlaymateColors.Paper, Offset(-4f, 0f), Size(70f, 18f), CornerRadius(9f))
    } }
}

private fun DrawScope.dinosaur(x: Float, y: Float) {
    translate(x, y) {
        drawOval(Color(0xFFABBCA0), Offset(-66f, 28f), Size(132f, 23f))
        drawPath(Path().apply { moveTo(-52f, 6f); quadraticTo(-88f, 3f, -100f, -19f); quadraticTo(-77f, 32f, -39f, 30f); close() }, PlaymateColors.Leaf)
        drawOval(PlaymateColors.Leaf, Offset(-64f, -27f), Size(99f, 63f))
        drawRoundRect(PlaymateColors.Leaf, Offset(-46f, 12f), Size(18f, 35f), CornerRadius(8f))
        drawRoundRect(PlaymateColors.Leaf, Offset(6f, 11f), Size(18f, 36f), CornerRadius(8f))
        drawOval(Color(0xFF55764F), Offset(1f, -50f), Size(55f, 72f))
        drawOval(Color(0xFF8CA47C), Offset(15f, -25f), Size(62f, 46f))
        drawPath(Path().apply { moveTo(29f, -29f); lineTo(33f, -49f); lineTo(42f, -26f); close() }, PlaymateColors.Sand)
        drawPath(Path().apply { moveTo(49f, -22f); lineTo(61f, -39f); lineTo(61f, -15f); close() }, PlaymateColors.Sand)
        drawPath(Path().apply { moveTo(69f, -1f); lineTo(83f, -6f); lineTo(73f, 7f); close() }, PlaymateColors.Sand)
        drawCircle(PlaymateColors.Paper, 7f, Offset(56f, -6f))
        drawCircle(PlaymateColors.Forest, 4f, Offset(57f, -6f))
        drawArc(PlaymateColors.Forest, 45f, 110f, false, Offset(60f, 3f), Size(11f, 9f), style = Stroke(3.5f, cap = StrokeCap.Round))
        drawCircle(Color(0xFF648660), 6f, Offset(-27f, -10f))
        drawCircle(Color(0xFF648660), 4f, Offset(-42f, 0f))
    }
}

private fun DrawScope.spark(x: Float, y: Float) {
    drawLine(Color(0xFFA87525), Offset(x - 8f, y), Offset(x + 8f, y), 4f, StrokeCap.Round)
    drawLine(Color(0xFFA87525), Offset(x, y - 8f), Offset(x, y + 8f), 4f, StrokeCap.Round)
}
