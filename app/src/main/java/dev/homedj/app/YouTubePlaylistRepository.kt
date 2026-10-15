package dev.homedj.app

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** [artworkUrl] is YouTube's cover image when known (validated by [youTubeImageUrl]), else blank. */
data class YouTubePlaylist(val id: String, val title: String, val itemCount: Int, val artworkUrl: String = "")
/** [artist] and [durationSeconds] are optional details: blank and 0 when unknown (older snapshots, failures). */
data class YouTubeTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val durationSeconds: Int = 0,
)

internal data class YouTubePage<T>(val items: List<T>, val nextToken: String?)

internal object YouTubeResponseParser {
    fun playlists(json: String): YouTubePage<YouTubePlaylist> {
        val response = JSONObject(json)
        val items = response.getJSONArray("items")
        val playlists = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val snippet = item.optJSONObject("snippet")
                val title = snippet?.optString("title")?.trim().orEmpty()
                if (id.isEmpty() || title.isEmpty()) continue
                val thumbnails = snippet?.optJSONObject("thumbnails")
                val cover = listOf("medium", "high", "default").firstNotNullOfOrNull { size ->
                    youTubeImageUrl(thumbnails?.optJSONObject(size)?.optString("url"))
                }
                add(
                    YouTubePlaylist(
                        id,
                        title,
                        item.optJSONObject("contentDetails")?.optInt("itemCount", 0) ?: 0,
                        cover.orEmpty(),
                    )
                )
            }
        }
        return YouTubePage(playlists, response.optString("nextPageToken").takeIf { it.isNotBlank() })
    }

    fun tracks(json: String): YouTubePage<YouTubeTrack> {
        val response = JSONObject(json)
        val items = response.getJSONArray("items")
        val tracks = buildList {
            for (index in 0 until items.length()) {
                val snippet = items.optJSONObject(index)?.optJSONObject("snippet") ?: continue
                val id = snippet.optJSONObject("resourceId")?.optString("videoId")?.trim().orEmpty()
                val title = snippet.optString("title").trim()
                val artist = cleanArtist(snippet.optString("videoOwnerChannelTitle"))
                if (id.isNotEmpty() && title.isNotEmpty()) add(YouTubeTrack(id, title, artist))
            }
        }
        return YouTubePage(tracks, response.optString("nextPageToken").takeIf { it.isNotBlank() })
    }

    /** Video ID to length in seconds from a `videos?part=contentDetails` reply. */
    fun durations(json: String): Map<String, Int> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyMap()
        return buildMap {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val seconds = isoDurationSeconds(item.optJSONObject("contentDetails")?.optString("duration").orEmpty())
                if (seconds > 0) put(item.optString("id"), seconds)
            }
        }
    }
}

/** ISO-8601 video length such as `PT1H2M3S`; 0 when absent, live (`P0D`) or malformed. */
internal fun isoDurationSeconds(value: String): Int {
    val match = Regex("""PT(?:(\d{1,3})H)?(?:(\d{1,4})M)?(?:(\d{1,5})S)?""").matchEntire(value) ?: return 0
    val (hours, minutes, seconds) = match.destructured
    val total = (hours.toIntOrNull() ?: 0) * 3_600 + (minutes.toIntOrNull() ?: 0) * 60 + (seconds.toIntOrNull() ?: 0)
    return total.coerceIn(0, MAX_TRACK_SECONDS)
}

/** Auto-generated music channels are named "Artist - Topic"; the artist is the useful part. */
internal fun cleanArtist(channel: String?): String =
    channel.orEmpty().trim().removeSuffix(" - Topic").trim().take(200)

internal const val MAX_TRACK_SECONDS = 24 * 3_600

internal class YouTubeHttpFailure(val statusCode: Int) : IOException("YouTube HTTP " + statusCode)

/** Ephemeral read-only YouTube metadata. No tokens or playlist data are written to disk. */
internal class YouTubePlaylistRepository {
    suspend fun playlists(token: String): List<YouTubePlaylist> =
        loadAll(token, "playlists", "mine=true", YouTubeResponseParser::playlists)

    suspend fun playlistById(token: String, playlistId: String): YouTubePlaylist? =
        loadAll(
            token,
            "playlists",
            "id=" + URLEncoder.encode(playlistId, "UTF-8"),
            YouTubeResponseParser::playlists,
        ).firstOrNull()

    suspend fun tracks(token: String, playlistId: String): List<YouTubeTrack> =
        withDurations(token, loadAll(
            token,
            "playlistItems",
            "playlistId=" + URLEncoder.encode(playlistId, "UTF-8"),
            YouTubeResponseParser::tracks,
        ))

    /** One extra call per 50 songs; a failure leaves lengths unknown instead of failing the playlist. */
    private suspend fun withDurations(token: String, tracks: List<YouTubeTrack>): List<YouTubeTrack> {
        val lengths = HashMap<String, Int>()
        try {
            tracks.map(YouTubeTrack::id).filter(NativeAudioSource::validId).distinct().chunked(50).forEach { ids ->
                loadAll(token, "videos", "id=" + ids.joinToString(",")) { json ->
                    YouTubePage(YouTubeResponseParser.durations(json).toList(), null)
                }.forEach { (id, seconds) -> lengths[id] = seconds }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJ", "Song lengths unavailable: " + failure.javaClass.simpleName)
        }
        return tracks.map { track -> lengths[track.id]?.let { track.copy(durationSeconds = it) } ?: track }
    }

    private suspend fun <T> loadAll(
        token: String,
        endpoint: String,
        selector: String,
        parse: (String) -> YouTubePage<T>,
    ): List<T> = withContext(Dispatchers.IO) {
        val output = mutableListOf<T>()
        val seenTokens = mutableSetOf<String>()
        var nextToken: String? = null
        var pageCount = 0
        do {
            coroutineContext.ensureActive()
            if (++pageCount > 100) throw IOException("YouTube playlist pagination limit reached")
            val address = buildString {
                append("https://www.googleapis.com/youtube/v3/")
                append(endpoint)
                append("?part=snippet,contentDetails&maxResults=50&")
                append(selector)
                nextToken?.let {
                    append("&pageToken=")
                    append(URLEncoder.encode(it, "UTF-8"))
                }
            }
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer " + token)
            connection.setRequestProperty("Accept", "application/json")
            try {
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) throw YouTubeHttpFailure(status)
                val page = parse(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                output.addAll(page.items)
                page.nextToken?.let { token ->
                    if (!seenTokens.add(token)) throw IOException("YouTube returned a repeated page")
                }
                nextToken = page.nextToken
            } finally {
                connection.disconnect()
            }
        } while (nextToken != null)
        output
    }
}
