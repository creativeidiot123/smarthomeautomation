package dev.homedj.app

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Read-only browsing and authenticated media commands against the Samsung's local library. */
internal class RemoteLibraryCommands(private val app: Context) {
    private val catalog = SavedPlaylistStore.shared(app)
    private val pageSize = 40

    suspend fun handle(request: JSONObject, player: MediaController): JSONObject {
        val action = request.optString("command")
        val id = request.optString("id")
        val offset = request.optInt("offset", -1)
        if (action in setOf("catalog", "tracks", "downloads")) {
            if (!validOffset(offset)) return error("Invalid page")
            return withContext(Dispatchers.IO) {
                val saved = catalog.read()
                when (action) {
                    "catalog" -> page(saved.playlists, offset) {
                        JSONObject().put("id", it.id).put("title", it.title).put("count", it.itemCount)
                    }
                    "tracks" -> {
                        if (saved.playlists.none { it.id == id }) return@withContext error("Unknown playlist")
                        page(saved.tracks[id].orEmpty(), offset) {
                            JSONObject().put("id", it.id).put("title", it.title)
                                .put("saved", OfflineAudioLibrary.existing(app, it.id) != null)
                        }
                    }
                    else -> {
                        val names = saved.tracks.values.flatten().associateBy(YouTubeTrack::id)
                        val files = OfflineAudioLibrary.allLocalMedia(app)
                        page(files, offset) {
                            val videoId = it.uri.lastPathSegment?.substringBeforeLast('.') ?: ""
                            JSONObject().put("id", videoId)
                                .put("title", names[videoId]?.title ?: videoId)
                                .put("saved", true)
                        }
                    }
                }
            }
        }

        if (action !in setOf("playTrack", "playPlaylist", "saveTrack", "savePlaylist")) {
            return error("Unknown library command")
        }
        val saved = catalog.read()
        val tracks = when (action) {
            "playTrack", "saveTrack" -> {
                if (!NativeAudioSource.validId(id)) return error("Invalid song")
                val known = saved.tracks.values.asSequence().flatten().firstOrNull { it.id == id }
                val local = OfflineAudioLibrary.existing(app, id)
                if (!isTrackAllowed(saved, id, local != null)) return error("Song is not in the saved library")
                listOf(known ?: YouTubeTrack(id, id))
            }
            else -> {
                if (saved.playlists.none { it.id == id }) return error("Unknown playlist")
                saved.tracks[id].orEmpty()
                    .filter { NativeAudioSource.validId(it.id) }
                    .distinctBy(YouTubeTrack::id)
                    .take(if (action == "savePlaylist") 250 else 500)
            }
        }
        if (tracks.isEmpty()) return error("No cached songs. Open this playlist on Samsung to sync it.")
        if (action.startsWith("save")) {
            withContext(Dispatchers.IO) {
                tracks.forEach { OfflineAudioLibrary.enqueue(app, it) }
            }
            return JSONObject().put("ok", true)
                .put("message", "Queued ${tracks.size} downloads on Samsung")
        }
        player.setMediaItems(tracks.map {
            MediaItem.Builder().setUri(HomeDjMediaResolver.trackUri(it.id))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build()).build()
        })
        player.prepare()
        player.play()
        return JSONObject().put("ok", true)
            .put("message", "Playing ${tracks.size} songs on Samsung" +
                if (action == "playPlaylist" && saved.tracks[id].orEmpty().size > tracks.size)
                    " (first ${tracks.size} only)" else "")
    }

    private fun <T> page(items: List<T>, offset: Int, make: (T) -> JSONObject): JSONObject {
        val array = JSONArray()
        items.drop(offset).take(pageSize).forEach { array.put(make(it)) }
        return JSONObject().put("ok", true).put("items", array)
            .put("total", items.size).put("offset", offset)
    }

    internal companion object {
        fun validOffset(value: Int): Boolean = value in 0..5_000
        fun isTrackAllowed(saved: SavedPlaylistCatalog, id: String, fileExists: Boolean): Boolean =
            NativeAudioSource.validId(id) && (fileExists ||
                saved.tracks.values.any { songs -> songs.any { it.id == id } })
    }

    private fun error(reason: String): JSONObject =
        JSONObject().put("ok", false).put("error", reason)
}
