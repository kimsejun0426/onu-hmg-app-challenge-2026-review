package dev.carplaymate.demo

import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Size the app to its assigned vehicle window. The driving view occupies a separate window. */
@Composable
internal fun VehicleWindow(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val automotive = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    }
    if (!automotive) {
        content()
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val system = LocalDensity.current
        val factor = minOf(maxWidth / 1280.dp, maxHeight / 760.dp).coerceIn(1f, 1.25f)
        val density = remember(system, factor) { Density(system.density * factor, system.fontScale) }
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}

/** Use the approved transparent wordmark; exclude the generous margin around the source art. */
@Composable
internal fun OnuWordmark(modifier: Modifier) {
    val mark = ImageBitmap.imageResource(R.drawable.onu_wordmark)
    Canvas(modifier.semantics { contentDescription = "오누"; heading() }) {
        drawImage(
            image = mark,
            srcOffset = IntOffset(345, 185),
            srcSize = IntSize(1085, 570),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}
