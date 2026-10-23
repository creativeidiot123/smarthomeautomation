package dev.homedj.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class RemotePlaylist(val id: String, val title: String, val count: Int, val artworkUrl: String = "")
data class RemoteSong(
    val id: String,
    val title: String,
    val saved: Boolean,
    val saving: Boolean = false,
    val artist: String = "",
    val durationSeconds: Int = 0,
    val failed: Boolean = false,
)

data class RemoteUiState(
    val host: String = "",
    val pairingCode: String = "",
    val connected: Boolean = false,
    val title: String = "No server connected",
    val playing: Boolean = false,
    val hasTrack: Boolean = false,
    val queueSize: Int = 0,
    val shuffle: Boolean = false,
    val volume: Int = 0,
    val casting: Boolean = false,
    /** Samsung's break settings; null until a Samsung build that supports breaks replies. */
    val breaks: MusicBreakSettings? = null,
    /** Wall-clock time a running break ends, for display only. */
    val breakResumesAt: Long? = null,
    /** Samsung's music left before its next break at [positionAt]; null when unknown or from an older build. */
    val nextBreakSeconds: Long? = null,
    val error: String? = null,
    val notice: String? = null,
    val browserMode: String = "catalog",
    val browserLoading: Boolean = false,
    val browserCached: Boolean = false,
    val selectedPlaylistId: String? = null,
    val selectedPlaylistTitle: String = "",
    val playlists: List<RemotePlaylist> = emptyList(),
    val songs: List<RemoteSong> = emptyList(),
    val browserTotal: Int = 0,
    val importingLink: Boolean = false,
    val refreshingPlaylist: Boolean = false,
    /** Samsung's recent changes from either phone, newest first; null on a Samsung build without it. */
    val activity: List<ActivityEntry>? = null,
    /** Samsung's catalog revision; a change re-reads the playlist list. Null on older builds. */
    val libraryRevision: Long? = null,
    val downloadsRevision: Long? = null,
    /** Download revision actually reflected in [songs], independent of newer status replies. */
    val songsDownloadsRevision: Long? = null,
    /** Index of the song shown, sent with skips so a stale tap cannot skip twice; -1 if unknown. */
    val trackIndex: Int = -1,
    val artist: String = "",
    /** Samsung's song position when [positionAt] (`elapsedRealtime`) was read; -1 duration on older builds. */
    val positionMs: Long = 0L,
    val durationMs: Long = -1L,
    val positionAt: Long = 0L,
    /** Whole-playlist counts from Samsung for the shown playlist; -1 when an older Samsung omits them. */
    val savedCount: Int = -1,
    val savingCount: Int = 0,
)

class RemoteControllerViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = RemotePrefs(application)
    private val mutableState = MutableStateFlow(
        RemoteUiState(host = prefs.savedHost, pairingCode = prefs.savedClientKey)
    )
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var connection: Job? = null
    private var generation = 0
    private var browseVersion = 0
    private var browseJob: Job? = null
    private val browseCache = RemoteBrowseCache(application.filesDir)
    private var started = false
    private var networkRegistered = false
    // Wakes the poll loop early (Wi-Fi back, a failed command) without restarting it.
    private val refresh = Channel<Unit>(Channel.CONFLATED)
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh.trySend(Unit)
        }
    }

    fun start() {
        if (started) return
        started = true
        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiCallback,
            )
            networkRegistered = true
        } catch (failure: RuntimeException) {
            Log.w("HomeDJRemote", "Wi-Fi reconnect listener unavailable: " + failure.javaClass.simpleName)
        }
        val current = mutableState.value
        if (current.host.isNotBlank() && current.pairingCode.isNotBlank()) {
            connect(current.host, current.pairingCode)
        }
    }

    fun connect(host: String, code: String) {
        val address = host.trim()
        val normalizedCode = RemotePairingCode.normalize(code)
        val key = RemoteChannel.decodeKey(code)
        if (!RemotePrefs.validPrivateIpv4(address) || key == null) {
            mutableState.update { it.copy(error = "Enter the server's private Wi-Fi IPv4 and pairing code.") }
            return
        }
        connection?.cancel()
        browseJob?.cancel()
        val version = ++generation
        browseVersion++
        val previous = mutableState.value
        val samePeer = previous.host == address && previous.pairingCode == normalizedCode
        mutableState.value = if (samePeer) {
            previous.copy(connected = false, browserLoading = false, error = null)
        } else RemoteUiState(host = address, pairingCode = normalizedCode)
        connection = viewModelScope.launch {
            val cached = browseCache.read(browsePeerKey(address, normalizedCode), null, "catalog", null)
            if (version != generation) return@launch
            if (mutableState.value.playlists.isEmpty() && cached != null) {
                mutableState.update { cached.show(it, "catalog", false).copy(browserCached = true) }
            }
            var failures = 0
            var libraryChecked = false
            while (isActive && version == generation) {
                refresh.tryReceive() // This poll answers any refresh requested before it.
                try {
                    val response = exchange(address, key, "status")
                    if (!response.optBoolean("ok")) throw IllegalStateException("Rejected")
                    if (version != generation) break
                    val firstConnection = !mutableState.value.connected
                    failures = 0
                    if (prefs.savedHost != address || prefs.savedClientKey != normalizedCode) {
                        prefs.saveClient(address, normalizedCode)
                    }
                    show(response)
                    val currentBrowse = mutableState.value
                    if (firstConnection && !currentBrowse.browserLoading && (currentBrowse.needsBrowse() || !libraryChecked)) {
                        browse(currentBrowse.browserMode, currentBrowse.selectedPlaylistId)
                    }
                    libraryChecked = true
                    refreshSongs(address, key)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    if (version != generation) break
                    failures++
                    Log.w("HomeDJRemote", "Remote status failed: " +
                        failure.javaClass.simpleName + " consecutive=" + failures.coerceAtMost(6))
                    if (showsDisconnected(failures, mutableState.value.connected)) {
                        browseVersion++
                        browseJob?.cancel()
                        mutableState.value = mutableState.value.copy(
                            connected = false, browserLoading = false,
                            error = "Server unavailable or pairing code incorrect."
                        )
                    }
                }
                withTimeoutOrNull(statusRetryDelayMillis(failures)) { refresh.receive() }
            }
        }
    }

    fun setBreaks(settings: MusicBreakSettings) = command("setBreaks", breaks = settings)

    fun command(action: String, percent: Int? = null, breaks: MusicBreakSettings? = null, position: Long? = null) {
        val current = mutableState.value
        val key = RemoteChannel.decodeKey(current.pairingCode) ?: return
        if (!current.connected) return
        val version = generation
        viewModelScope.launch {
            try {
                val response = exchange(current.host, key, commandRequest(action, current, percent, breaks, position))
                if (version != generation) return@launch
                if (response.optBoolean("ok")) {
                    show(response)
                    mutableState.value = mutableState.value.copy(error = null)
                } else {
                    mutableState.value = mutableState.value.copy(
                        error = response.optString("error", "Remote command rejected")
                    )
                    // A refusal usually means this screen was stale; show Samsung's truth now.
                    refresh.trySend(Unit)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (version != generation) return@launch
                Log.w("HomeDJRemote", "Remote command failed: " + failure.javaClass.simpleName)
                // Unknown outcome: re-read Samsung's status now; the poll decides if the link is down.
                mutableState.value = mutableState.value.copy(error = "Command failed. Check Wi-Fi connection.")
                refresh.trySend(Unit)
            }
        }
    }

    fun browsePlaylists() = browse("catalog")
    fun browseDownloads() = browse("downloads")
    fun browseTracks(id: String) = browse("tracks", id)

    fun retryBrowse() {
        val current = mutableState.value
        if (!current.browserLoading) browse(current.browserMode, current.selectedPlaylistId, force = true)
    }

    private fun browse(mode: String, id: String? = null, force: Boolean = false) {
        val current = mutableState.value
        val key = RemoteChannel.decodeKey(current.pairingCode) ?: return
        browseJob?.cancel()
        val requestVersion = ++browseVersion
        val connectionVersion = generation
        val selectedTitle = if (mode == "tracks")
            current.playlists.firstOrNull { it.id == id }?.title ?: current.selectedPlaylistTitle
            else ""
        mutableState.value = current.copy(
            browserMode = mode, selectedPlaylistId = if (mode == "tracks") id else null,
            selectedPlaylistTitle = selectedTitle, browserLoading = true, error = null,
            songs = emptyList(), browserTotal = 0, savedCount = -1, savingCount = 0, browserCached = false,
        )
        browseJob = viewModelScope.launch {
            try {
                val peer = browsePeerKey(current.host, current.pairingCode)
                val cached = browseCache.read(peer, current.libraryRevision, mode, id)
                if (connectionVersion != generation || requestVersion != browseVersion) return@launch
                val reusable = !force && current.libraryRevision != null && cached?.reusable(mode, current.downloadsRevision) == true
                if (cached != null) mutableState.update {
                    cached.show(it, mode, current.connected && !reusable).copy(browserCached = !current.connected || !reusable)
                }
                if (!current.connected || reusable) {
                    mutableState.update { it.copy(browserLoading = false) }
                    return@launch
                }
                val page = loadRemoteBrowse(mode, fetch = { offset ->
                    exchange(current.host, key, mode, id = id, offset = offset).also {
                        if (connectionVersion != generation || requestVersion != browseVersion) throw CancellationException()
                    }
                }).copy(downloadsRevision = current.downloadsRevision)
                mutableState.update { page.show(it, mode, false) }
                browseCache.save(peer, current.libraryRevision, mode, id, page)
                prefetchMusicArtwork(getApplication(), page.playlists.map { it.artworkUrl } +
                    page.songs.mapNotNull { youTubeArtworkUrl(it.id) })
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (connectionVersion != generation || requestVersion != browseVersion) return@launch
                Log.w("HomeDJRemote", "Library browse failed: " + failure.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(
                    browserLoading = false, error = "Could not browse Samsung library."
                )
                refresh.trySend(Unit)
            }
        }
    }

    fun seek(positionMs: Long) = command("seek", position = positionMs)

    /** Stops (`remove` false) or deletes Samsung's download of one song or the shown playlist. */
    fun changeDownload(remove: Boolean, kind: String, id: String) =
        libraryAction(if (remove) "removeSaved" else "cancelSave", id, kind)

    fun libraryAction(action: String, id: String, kind: String? = null) {
        val current = mutableState.value
        val key = RemoteChannel.decodeKey(current.pairingCode) ?: return
        if (!current.connected) return
        val version = generation
        viewModelScope.launch {
            try {
                val request = JSONObject().put("command", action).put("id", id)
                if (kind != null) request.put("kind", kind)
                // Optional field: Samsung queues the shown playlist from this song; older builds ignore it.
                if (action == "playTrack" && current.browserMode == "tracks") {
                    current.selectedPlaylistId?.let { request.put("playlist", it) }
                }
                val response = exchange(current.host, key, request)
                if (version != generation) return@launch
                if (response.optBoolean("ok")) mutableState.update { it.withSavesQueued(action, id, kind) }
                mutableState.value = mutableState.value.copy(
                    notice = if (response.optBoolean("ok"))
                        response.optString("message", "Command accepted") else null,
                    error = if (response.optBoolean("ok")) null
                        else downloadChangeError(action, response.optString("error", "Command rejected")),
                )
                if (response.optBoolean("ok") && action in setOf("saveTrack", "savePlaylist", "cancelSave", "removeSaved")) {
                    browseCache.invalidateDownloads()
                    refresh.trySend(Unit)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (version != generation) return@launch
                Log.w("HomeDJRemote", "Library command failed: " + failure.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(
                    error = "Server did not confirm the command. Check connection."
                )
                refresh.trySend(Unit)
            }
        }
    }

    /** Samsung reads the linked playlist online, so its reply may take far longer than a command. */
    fun importPlaylistLink(link: String) {
        val current = mutableState.value
        val key = RemoteChannel.decodeKey(current.pairingCode) ?: return
        if (!current.connected || current.importingLink) return
        if (YouTubePlaylistLinks.idFromLink(link) == null) {
            mutableState.update {
                it.copy(notice = null, error = "Paste a valid YouTube or YouTube Music playlist link.")
            }
            return
        }
        val version = generation
        mutableState.update { it.copy(importingLink = true, notice = null, error = null) }
        viewModelScope.launch {
            try {
                // Outside [mutex]: a slow import must not hold back status polls or other commands.
                val request = JSONObject().put("command", "importPlaylist").put("link", link.trim())
                val response = withContext(Dispatchers.IO) {
                    RemoteChannel.exchange(current.host, key, request, replyTimeoutMillis = 45_000)
                }
                if (version != generation) return@launch
                val ok = response.optBoolean("ok")
                mutableState.update {
                    it.copy(
                        notice = if (ok) response.optString("message", "Playlist added") else null,
                        error = if (ok) null else importLinkError(response.optString("error")),
                    )
                }
                if (ok) browse("catalog", force = true)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (version != generation) return@launch
                Log.w("HomeDJRemote", "Playlist link import failed: " + failure.javaClass.simpleName)
                // Unknown outcome: Samsung may still finish; the catalog shows the truth.
                mutableState.update { it.copy(error = "Samsung did not confirm. Check Playlists in a moment.") }
            } finally {
                mutableState.update { it.copy(importingLink = false) }
            }
        }
    }

    /** Samsung re-reads the playlist online, so like a link import the reply may take a while. */
    fun refreshPlaylist(id: String) {
        val current = mutableState.value
        val key = RemoteChannel.decodeKey(current.pairingCode) ?: return
        if (!current.connected || current.refreshingPlaylist) return
        val version = generation
        mutableState.update { it.copy(refreshingPlaylist = true, notice = null, error = null) }
        viewModelScope.launch {
            try {
                // Outside [mutex], like an import: status polls and other commands keep going.
                val request = JSONObject().put("command", "refreshPlaylist").put("id", id)
                val response = withContext(Dispatchers.IO) {
                    RemoteChannel.exchange(current.host, key, request, replyTimeoutMillis = 45_000)
                }
                if (version != generation) return@launch
                val ok = response.optBoolean("ok")
                mutableState.update { it.copy(notice = if (ok) response.optString("message", "Playlist refreshed") else null,
                    error = if (ok) null else refreshPlaylistError(response.optString("error"))) }
                if (ok && mutableState.value.browserMode == "tracks" && mutableState.value.selectedPlaylistId == id)
                    browse("tracks", id, force = true)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (version != generation) return@launch
                Log.w("HomeDJRemote", "Playlist refresh failed: " + failure.javaClass.simpleName)
                // Unknown outcome: Samsung may still finish; reopening the playlist shows the truth.
                mutableState.update { it.copy(error = "Samsung did not confirm. Reopen the playlist in a moment.") }
            } finally {
                mutableState.update { it.copy(refreshingPlaylist = false) }
            }
        }
    }

    /** Re-reads the loaded rows [songsToRefresh] picks, so "Saving…" and "Downloaded" follow Samsung live. */
    private suspend fun refreshSongs(host: String, key: SecretKeySpec) {
        val current = mutableState.value
        val rows = current.songsToRefresh() ?: return
        val requestVersion = browseVersion
        val fresh = mutableListOf<RemoteSong>()
        var counts: Pair<Int, Int>? = null
        var total = current.browserTotal
        var offset = rows.first
        while (offset <= rows.last || (current.browserMode == "downloads" && offset < total)) {
            val response = exchange(host, key, current.browserMode, id = current.selectedPlaylistId, offset = offset)
            val items = response.optJSONArray("items")
            if (!response.optBoolean("ok") || items == null) return
            counts = response.optInt("savedCount", -1) to response.optInt("savingCount", 0)
            total = response.optInt("total", total)
            if (items.length() == 0) break
            for (index in 0 until items.length()) items.optJSONObject(index)?.let { fresh.add(remoteSong(it)) }
            offset += items.length()
        }
        if (requestVersion != browseVersion) return
        mutableState.update { it.withFreshSongs(fresh, total, counts)
            .copy(songsDownloadsRevision = current.downloadsRevision) }
    }

    private suspend fun exchange(
        host: String, key: SecretKeySpec, command: String, id: String? = null, offset: Int? = null,
    ): JSONObject {
        val request = JSONObject().put("command", command)
        if (id != null) request.put("id", id)
        if (offset != null) request.put("offset", offset)
        return exchange(host, key, request)
    }

    private suspend fun exchange(host: String, key: SecretKeySpec, request: JSONObject): JSONObject =
        mutex.withLock { withContext(Dispatchers.IO) { RemoteChannel.exchange(host, key, request) } }

    private fun show(response: JSONObject) {
        val previous = mutableState.value
        val library = if (response.has("library")) response.optLong("library") else null
        mutableState.value = mutableState.value.copy(
            connected = true,
            title = response.optString("title", "No track selected"),
            playing = response.optBoolean("playing"),
            hasTrack = response.optBoolean("hasTrack"),
            queueSize = response.optInt("queueSize"),
            shuffle = response.optBoolean("shuffle"),
            volume = response.optInt("volume"),
            casting = response.optBoolean("casting"),
            breaks = response.optJSONObject("breaks")?.let(MusicBreakSettings::fromJson),
            breakResumesAt = response.optLong("breakSeconds", 0).takeIf { it > 0 }
                ?.let { System.currentTimeMillis() + it * 1_000 },
            nextBreakSeconds = if (response.has("nextBreakSeconds")) response.optLong("nextBreakSeconds") else null,
            error = if (mutableState.value.connected) mutableState.value.error else null,
            activity = RemoteActivity.fromJson(response.optJSONArray("activity"), System.currentTimeMillis()),
            libraryRevision = library,
            downloadsRevision = if (response.has("downloads")) response.optLong("downloads") else null,
            trackIndex = response.optInt("index", -1),
            artist = response.optString("artist").take(200),
            positionMs = response.optLong("position", 0L).coerceAtLeast(0),
            durationMs = response.optLong("duration", -1L).takeIf { it > 0 } ?: -1L,
            positionAt = SystemClock.elapsedRealtime(),
        )
        // A playlist added on either phone shows up here without a manual refresh.
        if (libraryChanged(previous.libraryRevision, library)) browse(previous.browserMode, previous.selectedPlaylistId)
    }

    fun stop() {
        started = false
        if (networkRegistered) {
            connectivity.unregisterNetworkCallback(wifiCallback)
            networkRegistered = false
        }
        generation++
        browseVersion++
        browseJob?.cancel()
        connection?.cancel()
        connection = null
        mutableState.value = mutableState.value.copy(connected = false, browserLoading = false)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

/** A Samsung build without link imports answers "Unknown command". */
internal fun importLinkError(error: String): String = when (error) {
    "Unknown command" -> "Update HomeDJ on Samsung to add playlist links."
    "" -> "Samsung could not add this playlist."
    else -> error
}

/** One missed poll on a live link is a Wi-Fi blip, not an outage; a link never confirmed fails fast. */
internal fun showsDisconnected(consecutiveFailures: Int, connected: Boolean): Boolean =
    !connected || consecutiveFailures >= 2

/** Reconnecting keeps what is already loaded; only an empty or interrupted section is fetched again. */
internal fun RemoteUiState.needsBrowse(): Boolean =
    if (browserMode == "catalog") playlists.isEmpty() else songs.isEmpty()

/** Fast initial recovery, capped retries while offline, slower healthy polling. */
internal fun statusRetryDelayMillis(consecutiveFailures: Int): Long = when {
    consecutiveFailures <= 0 -> 3_000L
    consecutiveFailures >= 6 -> 30_000L
    else -> 1_000L shl (consecutiveFailures - 1)
}

/** True only when a known revision moved; the first reply or an older Samsung is not a change. */
internal fun libraryChanged(previous: Long?, next: Long?): Boolean =
    previous != null && next != null && previous != next

/**
 * Sends the state the user tapped on, not a toggle: if Samsung changed meanwhile, the newer
 * Samsung applies the intent (or refuses a skip of a song it already left). Older builds ignore
 * the extra fields and toggle as before.
 */
internal fun commandRequest(
    action: String, shown: RemoteUiState, percent: Int? = null, breaks: MusicBreakSettings? = null,
    position: Long? = null,
): JSONObject {
    val request = JSONObject().put("command", action)
    when (action) {
        "playPause" -> request.put("play", !shown.playing)
        "shuffle" -> request.put("on", !shown.shuffle)
        "next", "previous", "seek" -> if (shown.trackIndex >= 0) request.put("index", shown.trackIndex)
    }
    if (position != null) request.put("position", position)
    if (percent != null) request.put("percent", percent)
    if (breaks != null) request.put("breaks", breaks.toJson())
    return request
}
