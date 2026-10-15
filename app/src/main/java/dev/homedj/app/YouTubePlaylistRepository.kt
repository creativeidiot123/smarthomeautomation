package dev.homedj.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class YouTubePlaylist(val id: String, val title: String, val itemCount: Int)
data class YouTubeTrack(val id: String, val title: String)

internal data class YouTubePage<T>(val items: List<T>, val nextToken: String?)

internal object YouTubeResponseParser {
    fun playlists(json: String): YouTubePage<YouTubePlaylist> {
        val response = JSONObject(json)
        val items = response.getJSONArray("items")
        val playlists = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val title = item.optJSONObject("snippet")?.optString("title")?.trim().orEmpty()
                if (id.isEmpty() || title.isEmpty()) continue
                add(
                    YouTubePlaylist(
                        id,
                        title,
                        item.optJSONObject("contentDetails")?.optInt("itemCount", 0) ?: 0,
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
                if (id.isNotEmpty() && title.isNotEmpty()) add(YouTubeTrack(id, title))
            }
        }
        return YouTubePage(tracks, response.optString("nextPageToken").takeIf { it.isNotBlank() })
    }
}

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
        loadAll(
            token,
            "playlistItems",
            "playlistId=" + URLEncoder.encode(playlistId, "UTF-8"),
            YouTubeResponseParser::tracks,
        )

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
