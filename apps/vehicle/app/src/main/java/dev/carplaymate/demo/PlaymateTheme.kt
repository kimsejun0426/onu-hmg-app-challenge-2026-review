package dev.carplaymate.demo

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object PlaymateColors {
    val Ivory = Color(0xFFF7F4ED)
    val Paper = Color(0xFFFFFEFC)
    val Forest = Color(0xFF245A54)
    val DeepForest = Color(0xFF1B3935)
    val Tangerine = Color(0xFFF5D4B6)
    val Leaf = Color(0xFF31675E)
    val Sage = Color(0xFFD7E5DC)
    val Mist = Color(0xFFECF2ED)
    val Amber = Color(0xFFE6AF67)
    val OnuCream = Color(0xFFFFF6E4)
    val OnuOchre = Color(0xFFD7A54B)
    val Sand = Color(0xFFF1E9D9)
    val Ink = Color(0xFF1F302A)
    val Muted = Color(0xFF485950)
    val Line = Color(0xFF82928B)
    val SoftLine = Color(0xFFDCE3DA)
    val Apricot = Color(0xFFF5D4B6)
}

@Composable
internal fun PlaymateTheme(content: @Composable () -> Unit) {
    // Let Text inherit Material's content color (especially on forest-colored buttons).
    val base = TextStyle(
        fontFamily = FontFamily.SansSerif,
        // The authored UI is Korean even when the vehicle's system locale is English.
        // Use locale-aware phrase wrapping without inserting characters into story text.
        localeList = LocaleList("ko-KR"),
        lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase),
        letterSpacing = 0.sp,
    )
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = PlaymateColors.Forest,
                onPrimary = PlaymateColors.Paper,
                primaryContainer = PlaymateColors.Sage,
                onPrimaryContainer = PlaymateColors.Forest,
                secondary = PlaymateColors.Leaf,
                onSecondary = PlaymateColors.Paper,
                secondaryContainer = PlaymateColors.Mist,
                onSecondaryContainer = PlaymateColors.Forest,
                background = PlaymateColors.Ivory,
                onBackground = PlaymateColors.Ink,
                surface = PlaymateColors.Paper,
                onSurface = PlaymateColors.Ink,
                surfaceVariant = PlaymateColors.Mist,
                onSurfaceVariant = PlaymateColors.Muted,
                surfaceTint = PlaymateColors.Forest,
                surfaceContainerHigh = PlaymateColors.Mist,
                outline = PlaymateColors.Line,
                error = Color(0xFF8D4A3D),
            ),
        typography =
            Typography(
                displayLarge =
                    base.copy(fontSize = 44.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold),
                displayMedium =
                    base.copy(fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold),
                headlineLarge =
                    base.copy(fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold),
                headlineMedium =
                    base.copy(
                        fontSize = 32.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                headlineSmall =
                    base.copy(
                        fontSize = 30.sp,
                        lineHeight = 40.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                titleLarge =
                    base.copy(
                        fontSize = 28.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                titleMedium =
                    base.copy(
                        fontSize = 26.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                titleSmall =
                    base.copy(
                        fontSize = 24.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                bodyLarge = base.copy(fontSize = 26.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium),
                bodyMedium = base.copy(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium),
                bodySmall = base.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal),
                labelLarge =
                    base.copy(
                        fontSize = 24.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                labelMedium =
                    base.copy(
                        fontSize = 20.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                labelSmall =
                    base.copy(fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
            ),
        shapes =
            Shapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
        content = content,
    )
}
