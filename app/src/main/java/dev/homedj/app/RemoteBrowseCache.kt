package dev.homedj.app

import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject

/** A complete, disposable browsing projection. Samsung still validates every action. */
internal data class RemoteBrowsePage(
    val playlists: List<RemotePlaylist> = emptyList(),
    val songs: List<RemoteSong> = emptyList(),
    val total: Int = 0,
    val savedCount: Int = -1,
    val savingCount: Int = 0,
    /** In-memory download marks are reusable only while Samsung reports this same revision. */
    val downloadsRevision: Long? = null,
) {
    fun reusable(mode: String, downloads: Long?): Boolean = mode == "catalog" ||
        (downloads != null && downloadsRevision == downloads && songs.none { it.saving })

    fun show(state: RemoteUiState, mode: String, loading: Boolean): RemoteUiState = state.copy(
        playlists = if (mode == "catalog") playlists else state.playlists,
        songs = if (mode == "catalog") emptyList() else songs,
        browserTotal = total, savedCount = savedCount, savingCount = savingCount, browserLoading = loading,
        songsDownloadsRevision = if (mode == "catalog") null else downloadsRevision,
        browserCached = false,
    )
}

/** One complete list. Transfer chunks stay private, including replies from older 40-row servers. */
internal suspend fun loadRemoteBrowse(
    mode: String,
    fetch: suspend (Int) -> JSONObject,
): RemoteBrowsePage {
    val playlists = mutableListOf<RemotePlaylist>()
    val songs = mutableListOf<RemoteSong>()
    var offset = 0
    var expectedTotal: Int? = null
    do {
        coroutineContext.ensureActive()
        val reply = fetch(offset)
        if (!reply.optBoolean("ok")) throw IOException("Library browse rejected")
        val items = reply.optJSONArray("items") ?: throw IOException("Missing library page")
        val total = reply.optInt("total", -1)
        if (total !in 0..5_000 || (expectedTotal != null && expectedTotal != total) ||
            items.length() > total - offset ||
            (items.length() == 0 && offset < total)) throw IOException("Invalid library page")
        expectedTotal = total
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: throw IOException("Invalid library item")
            if (mode == "catalog") playlists.add(RemotePlaylist(item.optString("id"),
                item.optString("title").take(300), item.optInt("count").coerceAtLeast(0),
                youTubeImageUrl(item.optString("art")).orEmpty()))
            else songs.add(remoteSong(item))
        }
        offset += items.length()
        if (offset >= total) return RemoteBrowsePage(playlists, songs, total,
            reply.optInt("savedCount", -1), reply.optInt("savingCount", 0))
    } while (true)
}

/** No addresses/keys/tokens in the cache: only a hash binding metadata to the paired peer. */
internal fun browsePeerKey(host: String, code: String): String = MessageDigest.getInstance("SHA-256")
    .digest((host.trim() + "\n" + code.trim()).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun browseCacheMatches(peer: String, revision: Long, expectedPeer: String, expectedRevision: Long?): Boolean =
    peer == expectedPeer && (expectedRevision == null || revision == expectedRevision)

internal object RemoteBrowseCodec {
    fun encode(page: RemoteBrowsePage): JSONObject = JSONObject()
        .put("total", page.total)
        .put("playlists", JSONArray().also { values -> page.playlists.forEach {
            values.put(SavedPlaylistCodec.playlistJson(YouTubePlaylist(it.id, it.title, it.count, it.artworkUrl)))
        } })
        // Download state is live, so it is deliberately not restored as a current fact.
        .put("songs", JSONArray().also { values -> page.songs.forEach {
            values.put(SavedPlaylistCodec.trackJson(YouTubeTrack(it.id, it.title, it.artist, it.durationSeconds)))
        } })

    fun decode(value: JSONObject): RemoteBrowsePage {
        val playlists = value.getJSONArray("playlists")
        val songs = value.getJSONArray("songs")
        val total = value.getInt("total")
        require(total in 0..5_000 && playlists.length() <= 5_000 && songs.length() <= 5_000)
        require(maxOf(playlists.length(), songs.length()) == total)
        return RemoteBrowsePage(
            playlists = List(playlists.length()) { index -> playlists.getJSONObject(index).let {
                RemotePlaylist(it.getString("id"), it.getString("title").take(300), it.optInt("count"),
                    youTubeImageUrl(it.optString("art")).orEmpty())
            } },
            songs = List(songs.length()) { remoteSong(songs.getJSONObject(it)) }, total = total,
        )
    }

}

/** One typed memory cache backed by one atomic file, at most 4 MB, scoped to peer and revision. */
internal class RemoteBrowseCache(filesDir: File) {
    private val file = AtomicFile(File(filesDir, "remote_browse_cache.json"))
    private val mutex = Mutex()
    private val lists = linkedMapOf<String, RemoteBrowsePage>()
    private var loaded = false
    private var peer = ""
    private var revision = -1L

    suspend fun invalidateDownloads() = mutex.withLock {
        lists.entries.forEach { it.setValue(it.value.copy(downloadsRevision = null)) }
    }

    suspend fun read(expectedPeer: String, expectedRevision: Long?, mode: String, id: String?): RemoteBrowsePage? =
        withContext(Dispatchers.IO) { mutex.withLock {
            load()
            if (browseCacheMatches(peer, revision, expectedPeer, expectedRevision))
                lists["$mode:${id.orEmpty()}"] else null
        } }

    suspend fun save(nextPeer: String, nextRevision: Long?, mode: String, id: String?, page: RemoteBrowsePage) =
        withContext(Dispatchers.IO) { mutex.withLock {
            load()
            val next = if (browseCacheMatches(peer, revision, nextPeer, nextRevision ?: -1)) LinkedHashMap(lists) else linkedMapOf()
            val key = "$mode:${id.orEmpty()}"
            next.remove(key)
            next[key] = page
            try {
                fun bytes(): ByteArray = JSONObject().put("version", 1).put("peer", nextPeer)
                    .put("revision", nextRevision ?: -1).put("pages", JSONObject().also { pages ->
                        next.forEach { (name, content) -> pages.put(name, RemoteBrowseCodec.encode(content)) }
                    }).toString().toByteArray(Charsets.UTF_8)
                var encoded = bytes()
                while (encoded.size > MAX_BYTES && next.size > 1) {
                    next.remove(next.keys.first { it != key })
                    encoded = bytes()
                }
                if (encoded.size > MAX_BYTES) return@withLock
                coroutineContext.ensureActive()
                val output = file.startWrite()
                try {
                    output.write(encoded)
                    file.finishWrite(output)
                } catch (failure: Exception) {
                    file.failWrite(output)
                    throw failure
                }
                lists.clear()
                lists.putAll(next)
                peer = nextPeer
                revision = nextRevision ?: -1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJRemote", "Save browse cache failed: " + failure.javaClass.simpleName)
            }
        } }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!file.baseFile.exists()) return
            if (file.baseFile.length() > MAX_BYTES) throw IOException("Oversized browse cache")
            val root = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            require(root.getInt("version") == 1)
            val pages = root.getJSONObject("pages")
            val decoded = pages.keys().asSequence().associateWith { RemoteBrowseCodec.decode(pages.getJSONObject(it)) }
            lists.putAll(decoded)
            peer = root.getString("peer")
            revision = root.optLong("revision", -1)
        } catch (failure: Exception) {
            lists.clear()
            Log.w("HomeDJRemote", "Read browse cache failed: " + failure.javaClass.simpleName)
        }
    }

    private companion object { const val MAX_BYTES = 4_000_000 }
}
