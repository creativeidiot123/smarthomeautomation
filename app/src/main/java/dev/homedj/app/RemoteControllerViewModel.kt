package dev.homedj.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class RemotePlaylist(val id: String, val title: String, val count: Int)
data class RemoteSong(val id: String, val title: String, val saved: Boolean)

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
    val error: String? = null,
    val notice: String? = null,
    val browserMode: String = "catalog",
    val browserLoading: Boolean = false,
    val selectedPlaylistId: String? = null,
    val selectedPlaylistTitle: String = "",
    val playlists: List<RemotePlaylist> = emptyList(),
    val songs: List<RemoteSong> = emptyList(),
    val browserTotal: Int = 0,
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
        connection?.cancel()
        val version = ++generation
        browseVersion++
        val address = host.trim()
        val key = RemoteChannel.decodeKey(code)
        if (!RemotePrefs.validPrivateIpv4(address) || key == null) {
            mutableState.value = mutableState.value.copy(
                connected = false, error = "Enter the server's private Wi-Fi IPv4 and pairing code."
            )
            return
        }
        val previous = mutableState.value
        val samePeer = previous.host == address && previous.pairingCode == code.trim()
        mutableState.value = if (samePeer) {
            previous.copy(connected = false, browserLoading = false, error = null)
        } else RemoteUiState(host = address, pairingCode = code.trim())
        connection = viewModelScope.launch {
            var failures = 0
            while (isActive && version == generation) {
                refresh.tryReceive() // This poll answers any refresh requested before it.
                try {
                    val response = exchange(address, key, "status")
                    if (!response.optBoolean("ok")) throw IllegalStateException("Rejected")
                    if (version != generation) break
                    val firstConnection = !mutableState.value.connected
                    failures = 0
                    if (prefs.savedHost != address || prefs.savedClientKey != code.trim()) {
                        prefs.saveClient(address, code)
                    }
                    show(response)
                    val currentBrowse = mutableState.value
                    if (firstConnection && currentBrowse.needsBrowse()) {
                        browse(currentBrowse.browserMode, currentBrowse.selectedPlaylistId)
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    if (version != generation) break
                    failures++
                    Log.w("HomeDJRemote", "Remote status failed: " +
                        failure.javaClass.simpleName + " consecutive=" + failures.coerceAtMost(6))
                    if (showsDisconnected(failures, mutableState.value.connected)) {
                        browseVersion++
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

    fun command(action: String, percent: Int? = null) {
        val current = mutableState.value
        val key = RemoteChannel.decodeKey(current.pairingCode) ?: return
        if (!current.connected) return
        val version = generation
        viewModelScope.launch {
            try {
                val response = exchange(current.host, key, action, percent = percent)
                if (version != generation) return@launch
                if (response.optBoolean("ok")) {
                    show(response)
                    mutableState.value = mutableState.value.copy(error = null)
                } else mutableState.value = mutableState.value.copy(
                    error = response.optString("error", "Remote command rejected")
                )
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

    fun loadMore() {
        val current = mutableState.value
        if (current.browserLoading || !current.connected) return
        val loaded = if (current.browserMode == "catalog") current.playlists.size else current.songs.size
        if (loaded < current.browserTotal) browse(current.browserMode, current.selectedPlaylistId, loaded)
    }

    private fun browse(mode: String, id: String? = null, offset: Int = 0) {
        val current = mutableState.value
        val key = RemoteChannel.decodeKey(current.pairingCode) ?: return
        if (!current.connected) return
        val requestVersion = ++browseVersion
        val connectionVersion = generation
        val selectedTitle = if (mode == "tracks")
            current.playlists.firstOrNull { it.id == id }?.title ?: current.selectedPlaylistTitle
            else ""
        mutableState.value = current.copy(
            browserMode = mode, selectedPlaylistId = if (mode == "tracks") id else null,
            selectedPlaylistTitle = selectedTitle, browserLoading = true, error = null,
            playlists = if (offset == 0 && mode == "catalog") emptyList() else current.playlists,
            songs = if (offset == 0) emptyList() else current.songs,
            browserTotal = if (offset == 0) 0 else current.browserTotal,
        )
        viewModelScope.launch {
            try {
                val response = exchange(current.host, key, mode, id = id, offset = offset)
                if (connectionVersion != generation || requestVersion != browseVersion) return@launch
                if (!response.optBoolean("ok")) {
                    mutableState.value = mutableState.value.copy(
                        browserLoading = false, error = response.optString("error", "Could not browse library")
                    )
                    return@launch
                }
                val values = response.optJSONArray("items")
                val playlists = mutableListOf<RemotePlaylist>()
                val songs = mutableListOf<RemoteSong>()
                if (values != null) for (index in 0 until values.length()) {
                    val item = values.optJSONObject(index) ?: continue
                    if (mode == "catalog") playlists.add(RemotePlaylist(
                        item.optString("id"), item.optString("title"), item.optInt("count")
                    ))
                    else songs.add(RemoteSong(
                        item.optString("id"), item.optString("title"), item.optBoolean("saved")
                    ))
                }
                val previous = mutableState.value
                mutableState.value = previous.copy(
                    browserLoading = false, browserTotal = response.optInt("total", 0),
                    playlists = if (mode == "catalog")
                        (if (offset == 0) emptyList() else previous.playlists) + playlists
                        else previous.playlists,
                    songs = if (mode != "catalog")
                        (if (offset == 0) emptyList() else previous.songs) + songs
                        else previous.songs,
                )
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

    fun libraryAction(action: String, id: String) {
        val current = mutableState.value
        val key = RemoteChannel.decodeKey(current.pairingCode) ?: return
        if (!current.connected) return
        val version = generation
        viewModelScope.launch {
            try {
                val response = exchange(current.host, key, action, id = id)
                if (version != generation) return@launch
                mutableState.value = mutableState.value.copy(
                    notice = if (response.optBoolean("ok"))
                        response.optString("message", "Command accepted") else null,
                    error = if (response.optBoolean("ok")) null
                        else response.optString("error", "Command rejected"),
                )
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

    private suspend fun exchange(
        host: String, key: SecretKeySpec, command: String,
        percent: Int? = null, id: String? = null, offset: Int? = null,
    ): JSONObject = mutex.withLock {
        withContext(Dispatchers.IO) {
            val request = JSONObject().put("command", command)
            if (percent != null) request.put("percent", percent)
            if (id != null) request.put("id", id)
            if (offset != null) request.put("offset", offset)
            RemoteChannel.exchange(host, key, request)
        }
    }

    private fun show(response: JSONObject) {
        mutableState.value = mutableState.value.copy(
            connected = true,
            title = response.optString("title", "No track selected"),
            playing = response.optBoolean("playing"),
            hasTrack = response.optBoolean("hasTrack"),
            queueSize = response.optInt("queueSize"),
            shuffle = response.optBoolean("shuffle"),
            volume = response.optInt("volume"),
            casting = response.optBoolean("casting"),
            error = if (mutableState.value.connected) mutableState.value.error else null,
        )
    }

    fun stop() {
        started = false
        if (networkRegistered) {
            connectivity.unregisterNetworkCallback(wifiCallback)
            networkRegistered = false
        }
        generation++
        browseVersion++
        connection?.cancel()
        connection = null
        mutableState.value = mutableState.value.copy(connected = false, browserLoading = false)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

/** One missed poll on a live link is a Wi-Fi blip, not an outage; a link never confirmed fails fast. */
internal fun showsDisconnected(consecutiveFailures: Int, connected: Boolean): Boolean =
    !connected || consecutiveFailures >= 2

/** Reconnecting keeps what is already loaded; only an empty or interrupted section is fetched again. */
internal fun RemoteUiState.needsBrowse(): Boolean =
    if (browserMode == "catalog") playlists.isEmpty() else songs.isEmpty()

/** Fast initial recovery, capped retries while offline, slower healthy polling. */
internal fun statusRetryDelayMillis(consecutiveFailures: Int): Long = when {
    consecutiveFailures <= 0 -> 6_000L
    consecutiveFailures >= 6 -> 30_000L
    else -> 1_000L shl (consecutiveFailures - 1)
}
