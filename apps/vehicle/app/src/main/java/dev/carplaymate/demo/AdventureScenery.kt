package dev.carplaymate.demo

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/** Bundled decorative places. Required story details remain in the authored spoken dialogue. */
@Composable
internal fun AdventureScenery(key: String, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(worldIllustration(key)),
        contentDescription = null,
        modifier = modifier,
        alignment = Alignment.Center,
        contentScale = ContentScale.Crop,
    )
}

private fun worldIllustration(key: String): Int = when (key) {
    "space", "space-return-signal" -> R.drawable.onu_world_space
    "stories", "starlight-post-office" -> R.drawable.onu_world_stories
    "ocean", "undersea-lighthouse" -> R.drawable.onu_world_ocean
    "animals", "animal-footprint-garden" -> R.drawable.onu_world_animals
    "vehicles", "little-car-workshop" -> R.drawable.onu_world_vehicles
    "little-picnic", "onu-little-picnic" -> R.drawable.onu_world_picnic
    else -> R.drawable.onu_dinosaur_world
}
