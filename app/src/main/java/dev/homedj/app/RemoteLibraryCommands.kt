package dev.homedj.app

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.session.MediaController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Read-only browsing and authenticated media commands against the Samsung's local library. */
internal class RemoteLibraryCommands(private val app: Context) {
    private val catalog = SavedPlaylistStore.shared(app)

    suspend fun handle(request: JSONObject, player: MediaController): JSONObject {
        val action = request.optString("command")
        val id = request.optString("id")
        val offset = request.optInt("offset", -1)
        if (action in setOf("catalog", "tracks", "downloads")) {
            if (!validOffset(offset)) return error("Invalid page")
            return withContext(Dispatchers.IO) {
                val saved = catalog.read()
                when (action) {
                    "catalog" -> remoteLibraryPage(saved.playlists, offset) { SavedPlaylistCodec.playlistJson(it) }
                    "tracks" -> {
                        if (saved.playlists.none { it.id == id }) return@withContext error("Unknown playlist")
                        val pending = OfflineAudioLibrary.pendingIds(app).first()
                        val failed = OfflineAudioLibrary.failedIds(app).first()
                        val songs = saved.tracks[id].orEmpty()
                        val local = OfflineAudioLibrary.savedIds(app)
                        // "saving", "failed", "artist", "duration" and the counts are optional: older controllers ignore them.
                        remoteLibraryPage(songs, offset) {
                            val saving = it.id !in local && it.id in pending
                            SavedPlaylistCodec.trackJson(it)
                                .put("saved", it.id in local).put("saving", saving)
                                .put("failed", it.id !in local && !saving && it.id in failed)
                        }.put("savedCount", songs.count { it.id in local })
                            .put("savingCount", songs.count { it.id !in local && it.id in pending })
                    }
                    else -> remoteLibraryPage(OfflineAudioLibrary.savedTracks(app), offset) {
                        SavedPlaylistCodec.trackJson(it).put("saved", true)
                    }
                }
            }
        }

        if (action == "importPlaylist") return importLink(request.optString("link"))
        if (action == "refreshPlaylist") return refreshPlaylist(id)
        if (action == "cancelSave" || action == "removeSaved") {
            return changeDownload(action == "removeSaved", request.optString("kind"), id)
        }
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
                    .let { if (action == "savePlaylist") it else it.take(MAX_PLAYLIST_SONGS) }
            }
        }
        if (tracks.isEmpty()) return error("No cached songs. Open this playlist on Samsung to sync it.")
        // Titles make the reply readable in both phones' activity feeds.
        val name = if (action.endsWith("Track")) tracks[start].title
            else saved.playlists.firstOrNull { it.id == id }?.title ?: "playlist"
        if (action == "saveTrack") {
            val queued = withContext(Dispatchers.IO) { OfflineAudioLibrary.enqueue(app, tracks[0]) }
            return JSONObject().put("ok", true).put("message",
                if (queued) "Saving $name on Samsung" else "$name is already downloaded on Samsung")
        }
        if (action == "savePlaylist") {
            // Songs Samsung already has are skipped; past one batch the rest follow as saves end.
            val started = PlaylistDownloads.start(app, id, tracks)
            return JSONObject().put("ok", true).put("message",
                if (started.queued == 0) "All of $name is already downloaded on Samsung"
                else "Saving ${started.queued} songs from $name on Samsung." + batchNote(started.later))
        }
        val items = tracks.map { youTubeMediaItem(it) }
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

    /**
     * Newer controllers only (an older Samsung answers "Unknown command"). Samsung reads the
     * playlist online itself, with its own account's silent token when the playlist is the
     * account's, so the reply can take seconds; the controller only names a saved playlist.
     */
    private suspend fun refreshPlaylist(id: String): JSONObject {
        val saved = catalog.read()
        if (saved.playlists.none { it.id == id }) return error("Unknown playlist")
        return try {
            val token = silentYouTubeAccess(app)
                ?.takeIf { saved.ownerKey == SavedPlaylistStore.accountKey(it.account) }?.token
            when (val outcome = PlaylistRefresh.refresh(app, token, id)) {
                is RefreshOutcome.Done -> JSONObject().put("ok", true).put("message", refreshMessage(outcome))
                    .put("added", outcome.added).put("queued", outcome.queued)
                else -> error(refreshError(outcome))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJRemote", "Remote playlist refresh failed: " + failure.javaClass.simpleName)
            error("Samsung could not refresh this playlist. Check Samsung's internet.")
        }
    }

    /**
     * Newer controllers only (an older Samsung answers "Unknown command"). Samsung decides which
     * songs a playlist means from its own catalog; the controller only names the playlist or song.
     */
    private suspend fun changeDownload(remove: Boolean, kind: String, id: String): JSONObject {
        val saved = catalog.read()
        val (ids, name) = when (kind) {
            "playlist" -> {
                val playlist = saved.playlists.firstOrNull { it.id == id } ?: return error("Unknown playlist")
                saved.tracks[id].orEmpty().map(YouTubeTrack::id) to playlist.title
            }
            "track" -> {
                val local = withContext(Dispatchers.IO) { OfflineAudioLibrary.existing(app, id) != null }
                if (!isTrackAllowed(saved, id, local)) return error("Song is not in the saved library")
                listOf(id) to (saved.songs()[id]?.title ?: "song")
            }
            else -> return error("Unknown download target")
        }
        if (!remove) {
            OfflineAudioLibrary.cancel(app, ids)
            return JSONObject().put("ok", true).put("message", "Stopped saving $name on Samsung")
        }
        val removed = if (kind == "playlist") OfflineAudioLibrary.removePlaylist(app, id, ids)
            else OfflineAudioLibrary.remove(app, ids)
        return JSONObject().put("ok", true).put("message",
            if (kind == "track") "Removed $name from Samsung" else "Removed " + songCount(removed) + " of $name from Samsung")
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
