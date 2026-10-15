package dev.homedj.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import java.util.concurrent.atomic.AtomicLong

/**
 * Media3 resolves a YouTube ID only when that queue item starts loading, avoiding expired
 * GoogleVideo URLs in a long playlist. The service owns this resolver and its short-lived cache.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class HomeDjMediaResolver(context: Context) : ResolvingDataSource.Resolver {
    private val app = context.applicationContext
    /** A null uri remembers a refusal so Media3's load retries do not re-query YouTube. */
    private data class Cached(val uri: Uri?, val expiresAt: Long)
    private val links = mutableMapOf<String, Cached>()
    private val requestNumber = AtomicLong()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = videoIdFromUri(dataSpec.uri.toString()) ?: return dataSpec
        OfflineAudioLibrary.existing(app, id)?.let { return dataSpec.withUri(Uri.fromFile(it)) }
        // Network extraction runs outside the lock so one slow track never blocks skipping to another.
        val cached = synchronized(links) {
            val now = SystemClock.elapsedRealtime()
            links.values.removeAll { it.expiresAt <= now }
            links[id]
        }
        val uri = if (cached != null) {
            cached.uri ?: throw TrackUnavailableException("Recently refused by YouTube")
        } else {
            val resolved = try {
                Uri.parse(NativeAudioSource.resolveBlocking(id).url)
            } catch (refused: TrackUnavailableException) {
                remember(id, null)
                throw refused
            }
            remember(id, resolved)
            resolved
        }
        return youtubeRequest(dataSpec, uri)
    }

    /** Drops a signed link the CDN rejected so the next load extracts a fresh one. */
    fun invalidate(id: String) {
        synchronized(links) { links.remove(id) }
    }

    private fun remember(id: String, uri: Uri?) {
        synchronized(links) { links[id] = Cached(uri, SystemClock.elapsedRealtime() + LINK_TTL_MILLIS) }
    }

    /** NewPipe's YoutubeHttpDataSource request shape for progressive streams: POST, rn counter, headers. */
    private fun youtubeRequest(dataSpec: DataSpec, uri: Uri): DataSpec {
        val numbered = withRequestNumber(uri.toString(), requestNumber.getAndIncrement())
        return dataSpec.buildUpon()
            .setUri(Uri.parse(numbered))
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(NativeAudioSource.STREAM_POST_BODY)
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + NativeAudioSource.streamHeaders(numbered))
            .build()
    }

    companion object {
        private const val PREFIX = "homedj://youtube/"
        private const val LINK_TTL_MILLIS = 4 * 60_000L

        fun trackUri(id: String): Uri {
            require(NativeAudioSource.validId(id)) { "Invalid video ID" }
            return Uri.parse(PREFIX + id)
        }

        fun videoIdFromUri(value: String): String? =
            value.takeIf { it.startsWith(PREFIX) }
                ?.removePrefix(PREFIX)
                ?.takeIf(NativeAudioSource::validId)

        internal fun withRequestNumber(url: String, number: Long): String =
            if ("/videoplayback" in url && "&rn=" !in url) "$url&rn=$number" else url
    }
}

/** The one way to queue a YouTube song: its stable ID URI plus title, artist and artwork for every controller. */
internal fun youTubeMediaItem(track: YouTubeTrack, extras: Bundle? = null): MediaItem =
    MediaItem.Builder().setUri(HomeDjMediaResolver.trackUri(track.id))
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.ifBlank { null })
            .setArtworkUri(youTubeArtworkUrl(track.id)?.let(Uri::parse))
            .setExtras(extras)
            .build())
        .build()
