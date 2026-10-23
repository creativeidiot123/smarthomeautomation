package dev.homedj.app

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import java.io.File
import android.content.Context
import android.util.Log
import coil3.decode.BlackholeDecoder
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Configure once before artwork is composed; Coil retains only the application context. */
internal fun configureMusicImageLoader() {
    SingletonImageLoader.setSafe { context ->
        val appContext = context.applicationContext
        ImageLoader.Builder(appContext)
            .diskCache { musicArtworkDiskCache(appContext.filesDir) }
            .build()
    }
}

/** Public, URL-keyed thumbnails survive system cache cleanup; Coil owns atomic writes and LRU. */
internal fun musicArtworkDiskCache(filesDir: File): DiskCache = DiskCache.Builder()
    .directory(File(filesDir, "artwork_cache"))
    .maxSizeBytes(128L * 1024 * 1024)
    .build()

/** Two cancellable requests at a time, disk only; never decode a whole library into memory. */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
internal suspend fun prefetchMusicArtwork(context: Context, urls: List<String>) = coroutineScope {
    val images = urls.mapNotNull(::youTubeImageUrl).distinct().take(5_000)
    val loader = SingletonImageLoader.get(context.applicationContext)
    repeat(2) { worker ->
        launch {
            var failed = false
            for (index in worker until images.size step 2) {
                coroutineContext.ensureActive()
                val request = ImageRequest.Builder(context.applicationContext).data(images[index])
                    .memoryCachePolicy(CachePolicy.DISABLED).decoderFactory(BlackholeDecoder.Factory()).build()
                if (loader.execute(request) is ErrorResult) failed = true
            }
            if (failed) Log.w("HomeDJ", "Artwork prefetch incomplete")
        }
    }
}
