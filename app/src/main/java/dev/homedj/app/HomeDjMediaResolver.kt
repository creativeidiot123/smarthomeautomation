package dev.homedj.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource

/**
 * Media3 resolves a YouTube ID only when that queue item starts loading, avoiding expired
 * GoogleVideo URLs in a long playlist. The service owns this resolver and its short-lived cache.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class HomeDjMediaResolver(context: Context) : ResolvingDataSource.Resolver {
    private val app = context.applicationContext
    private data class Cached(val uri: Uri, val expiresAt: Long)
    private val links = mutableMapOf<String, Cached>()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = videoIdFromUri(dataSpec.uri.toString()) ?: return dataSpec
        OfflineAudioLibrary.existing(app, id)?.let { return dataSpec.withUri(Uri.fromFile(it)) }
        // Network extraction runs outside the lock so one slow track never blocks skipping to another.
        synchronized(links) {
            val now = SystemClock.elapsedRealtime()
            links.values.removeAll { it.expiresAt <= now }
            links[id]?.uri
        }?.let { return dataSpec.withUri(it) }
        val resolved = Uri.parse(NativeAudioSource.resolveBlocking(id).url)
        synchronized(links) { links[id] = Cached(resolved, SystemClock.elapsedRealtime() + 4 * 60_000L) }
        return dataSpec.withUri(resolved)
    }

    companion object {
        private const val PREFIX = "homedj://youtube/"

        fun trackUri(id: String): Uri {
            require(NativeAudioSource.validId(id)) { "Invalid video ID" }
            return Uri.parse(PREFIX + id)
        }

        fun videoIdFromUri(value: String): String? =
            value.takeIf { it.startsWith(PREFIX) }
                ?.removePrefix(PREFIX)
                ?.takeIf(NativeAudioSource::validId)
    }
}
