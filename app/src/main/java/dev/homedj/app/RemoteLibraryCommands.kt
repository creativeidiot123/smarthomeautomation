package dev.homedj.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
                        val pending = OfflineAudioLibrary.pendingIds(app).first()
                        page(saved.tracks[id].orEmpty(), offset) {
                            val local = OfflineAudioLibrary.existing(app, it.id) != null
                            // "saving" is optional: an older controller ignores it.
                            JSONObject().put("id", it.id).put("title", it.title)
                                .put("saved", local).put("saving", !local && it.id in pending)
                        }
                    }
                    else -> {
                        val files = OfflineAudioLibrary.allLocalMedia(app, saved.titles())
                        page(files, offset) {
                            val videoId = it.uri.lastPathSegment?.substringBeforeLast('.') ?: ""
                            JSONObject().put("id", videoId)
                                .put("title", it.title)
                                .put("saved", true)
                        }
                    }
                }
            }
        }

        if (action == "importPlaylist") return importLink(request.optString("link"))
        if (action !in setOf("playTrack", "playPlaylist", "saveTrack", "savePlaylist")) {
            return error("Unknown library command")
        }
        val saved = catalog.read()
        // Optional "playlist": the tapped song's playlist is queued from it, so next/previous work.
        val listed = request.optString("playlist").takeIf { action == "playTrack" && it.isNotEmpty() }
            ?.takeIf { playlist -> saved.playlists.any { it.id == playlist } }
            ?.let { playlistQueue(saved.tracks[it].orEmpty(), id) }
        val start = listed?.start ?: 0
        val tracks = if (listed != null) listed.tracks else when (action) {
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
        // Titles make the reply readable in both phones' activity feeds.
        val name = if (action.endsWith("Track")) tracks[start].title
            else saved.playlists.firstOrNull { it.id == id }?.title ?: "playlist"
        if (action.startsWith("save")) {
            withContext(Dispatchers.IO) {
                tracks.forEach { OfflineAudioLibrary.enqueue(app, it) }
            }
            return JSONObject().put("ok", true).put("message",
                if (action == "saveTrack") "Saving $name on Samsung"
                else "Saving ${tracks.size} songs from $name on Samsung")
        }
        val items = tracks.map {
            MediaItem.Builder().setUri(HomeDjMediaResolver.trackUri(it.id))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title)
                    .setArtworkUri(youTubeArtworkUrl(it.id)?.let(Uri::parse)).build()).build()
        }
        if (listed != null) player.setMediaItems(items, start, C.TIME_UNSET) else player.setMediaItems(items)
        player.prepare()
        player.play()
        return JSONObject().put("ok", true)
            .put("message", if (action == "playTrack") "Playing $name on Samsung"
                else "Playing $name (${tracks.size} songs) on Samsung" +
                    if (saved.tracks[id].orEmpty().size > tracks.size) " (first ${tracks.size} only)" else "")
    }

    /** Samsung parses the link itself and reads the playlist without an account. */
    private suspend fun importLink(link: String): JSONObject {
        val id = YouTubePlaylistLinks.idFromLink(link) ?: return error("Not a YouTube playlist link")
        return try {
            val linked = NativeAudioSource.playlist(id)
            if (linked == null || linked.tracks.isEmpty()) {
                return error("Playlist not found, private, or without playable songs")
            }
            // Reading the playlist takes seconds; the role may have changed meanwhile.
            if (ModeStore(app).mode.first() != HomeDjMode.SERVER) return error("Server role inactive")
            catalog.importPublic(linked)
            JSONObject().put("ok", true).put("id", id)
                .put("message", "Added ${linked.playlist.title} (${linked.tracks.size} songs)")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJRemote", "Remote playlist import failed: " + failure.javaClass.simpleName)
            error("Samsung could not read this playlist. Check the link and Samsung's internet.")
        }
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
