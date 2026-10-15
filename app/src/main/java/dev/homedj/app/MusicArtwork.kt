package dev.homedj.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Corner radius of song and playlist art in lists and grids. */
internal val ThumbnailShape = RoundedCornerShape(6.dp)

private const val ART_HOST = "https://i.ytimg.com/vi/"
private const val SMALL_ART = "/mqdefault.jpg"
private const val LARGE_ART = "/maxresdefault.jpg"

/**
 * YouTube's public 16:9 thumbnail for a song, derived from its video ID only, so artwork is never
 * stored or sent between the phones. Null for anything that is not a valid video ID.
 */
internal fun youTubeArtworkUrl(id: String): String? =
    if (NativeAudioSource.validId(id)) ART_HOST + id + SMALL_ART else null

/** The high-resolution variant for the full-screen player; not every video has one. */
internal fun largeArtworkUrl(url: String): String =
    if (url.startsWith(ART_HOST) && url.endsWith(SMALL_ART)) url.removeSuffix(SMALL_ART) + LARGE_ART else url

/** Square, center-cropped artwork over a tonal placeholder that shows while loading or without art. */
@Composable
internal fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    shape: Shape = ThumbnailShape,
    @DrawableRes placeholder: Int = R.drawable.ic_music_note,
) {
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(placeholder), contentDescription = null,
            modifier = Modifier.fillMaxSize(0.4f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) {
            // Fall back to the always-present small thumbnail when the large one is missing.
            var model by remember(url, large) { mutableStateOf(if (large) largeArtworkUrl(url) else url) }
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { if (model != url) model = url },
            )
        }
    }
}
