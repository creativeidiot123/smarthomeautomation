package dev.homedj.app

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-private, atomic snapshot of playlist METADATA, never access tokens or downloadable audio.
 * The latest authorized YouTube response replaces owned playlists. Explicit URL imports are kept
 * separately and survived refresh. A disconnect erases the whole snapshot.
 */
internal data class SavedPlaylistCatalog(
    val owned: List<YouTubePlaylist> = emptyList(),
    val imported: List<YouTubePlaylist> = emptyList(),
    val tracks: Map<String, List<YouTubeTrack>> = emptyMap(),
    val syncedAt: Long = 0L,
) {
    val playlists: List<YouTubePlaylist>
        get() = (imported + owned).distinctBy(YouTubePlaylist::id)
}

internal object SavedPlaylistCodec {
    fun encode(catalog: SavedPlaylistCatalog): String = JSONObject()
        .put("version", 1)
        .put("syncedAt", catalog.syncedAt)
        .put("owned", playlistArray(catalog.owned))
        .put("imported", playlistArray(catalog.imported))
        .put("tracks", JSONObject().also { target ->
            catalog.tracks.forEach { (id, songs) ->
                target.put(id, JSONArray().also { array ->
                    songs.forEach { song ->
                        array.put(JSONObject().put("id", song.id).put("title", song.title))
                    }
                })
            }
        }).toString()

    fun decode(json: String): SavedPlaylistCatalog {
        val root = JSONObject(json)
        require(root.optInt("version") == 1) { "Unsupported playlist snapshot" }
        val owned = parsePlaylists(root.optJSONArray("owned"))
        val imported = parsePlaylists(root.optJSONArray("imported"))
        val allIds = (owned + imported).mapTo(hashSetOf(), YouTubePlaylist::id)
        val tracks = buildMap {
            val objectTracks = root.optJSONObject("tracks") ?: JSONObject()
            val keys = objectTracks.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                if (id !in allIds) continue
                val array = objectTracks.optJSONArray(id) ?: continue
                val songs = buildList {
                    for (index in 0 until minOf(array.length(), 5_000)) {
                        val value = array.optJSONObject(index) ?: continue
                        val songId = value.optString("id")
                        val title = value.optString("title")
                        if (songId.isNotBlank() && title.isNotBlank()) {
                            add(YouTubeTrack(songId.take(150), title.take(300)))
                        }
                    }
                }
                put(id, songs)
            }
        }
        return SavedPlaylistCatalog(
            owned = owned,
            imported = imported,
            tracks = tracks,
            syncedAt = root.optLong("syncedAt", 0L).coerceAtLeast(0),
        )
    }

    private fun playlistArray(playlists: List<YouTubePlaylist>): JSONArray =
        JSONArray().also { array ->
            playlists.forEach {
                array.put(JSONObject().put("id", it.id).put("title", it.title).put("count", it.itemCount))
            }
        }

    private fun parsePlaylists(array: JSONArray?): List<YouTubePlaylist> = buildList {
        if (array == null) return@buildList
        for (index in 0 until minOf(array.length(), 5_000)) {
            val value = array.optJSONObject(index) ?: continue
            val id = value.optString("id")
            val title = value.optString("title")
            if (id.isNotBlank() && title.isNotBlank()) {
                add(YouTubePlaylist(id.take(150), title.take(300), value.optInt("count").coerceAtLeast(0)))
            }
        }
    }
}

internal class SavedPlaylistStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "youtube_playlists.json"))
    private val mutex = Mutex()
    private var cache: SavedPlaylistCatalog? = null

    suspend fun read(): SavedPlaylistCatalog = withContext(Dispatchers.IO) {
        mutex.withLock { snapshot() }
    }

    suspend fun replaceOwned(playlists: List<YouTubePlaylist>): SavedPlaylistCatalog =
        mutate { previous ->
            val next = previous.copy(owned = playlists, syncedAt = System.currentTimeMillis())
            next.copy(tracks = previous.tracks.filterKeys { id -> next.playlists.any { it.id == id } })
        }

    suspend fun importPlaylist(playlist: YouTubePlaylist): SavedPlaylistCatalog =
        mutate { previous ->
            previous.copy(imported = (listOf(playlist) + previous.imported).distinctBy(YouTubePlaylist::id))
        }

    suspend fun storeTracks(id: String, tracks: List<YouTubeTrack>): SavedPlaylistCatalog =
        mutate { previous ->
            if (previous.playlists.none { it.id == id }) previous
            else previous.copy(tracks = previous.tracks + (id to tracks))
        }

    suspend fun erase() = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete()
            cache = SavedPlaylistCatalog()
        }
    }

    private suspend fun mutate(change: (SavedPlaylistCatalog) -> SavedPlaylistCatalog): SavedPlaylistCatalog =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = change(snapshot())
                val bytes = SavedPlaylistCodec.encode(next).toByteArray(Charsets.UTF_8)
                if (bytes.size > 4_000_000) throw IOException("Playlist snapshot exceeded storage limit")
                val output = file.startWrite()
                try {
                    output.write(bytes)
                    file.finishWrite(output)
                } catch (failure: Exception) {
                    file.failWrite(output)
                    throw failure
                }
                cache = next
                next
            }
        }

    private fun snapshot(): SavedPlaylistCatalog {
        cache?.let { return it }
        val stored = try {
            if (!file.baseFile.exists()) SavedPlaylistCatalog()
            else if (file.baseFile.length() > 4_000_000) throw IOException("Oversized playlist snapshot")
            else SavedPlaylistCodec.decode(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (failure: Exception) {
            Log.w("HomeDJ", "Saved playlist snapshot unreadable: " + failure.javaClass.simpleName)
            SavedPlaylistCatalog()
        }
        cache = stored
        return stored
    }
}
