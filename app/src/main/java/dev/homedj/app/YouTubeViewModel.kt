package dev.homedj.app

import android.accounts.Account
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class YouTubeUiState(
    val authorized: Boolean = false,
    val loadingPlaylists: Boolean = false,
    val playlists: List<YouTubePlaylist> = emptyList(),
    val selectedPlaylistId: String? = null,
    val loadingTracks: Boolean = false,
    val tracks: List<YouTubeTrack> = emptyList(),
    val error: String? = null,
    val savedSnapshot: Boolean = false,
    val lastSyncedAt: Long = 0L,
    val autoEnabled: Boolean = false,
    val autoDownloadIds: Set<String> = emptySet(),
    val offlineIds: Set<String> = emptySet(),
    /** Saves still queued or running on this phone, live from WorkManager. */
    val savingIds: Set<String> = emptySet(),
    val notice: String? = null,
)

/** Screen-scoped projection; tokens, identity and metadata never leave process memory. */
class YouTubeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = YouTubePlaylistRepository()
    private val savedStore = SavedPlaylistStore.shared(application)
    private val auto = AutoLibraryConfig(application)
    private val mutableState = MutableStateFlow(YouTubeUiState())
    private val savingIds = OfflineAudioLibrary.pendingIds(application)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val state: StateFlow<YouTubeUiState> = combine(mutableState, savingIds) { ui, saving ->
        ui.copy(savingIds = saving)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, mutableState.value)
    private var accessToken: String? = null
    var authorizedAccount: Account? = null
        private set
    private var playlistJob: Job? = null
    private var trackJob: Job? = null

    init {
        showSavedCatalog()
        // A playlist added from the OnePlus (or a worker) appears here without reopening the screen.
        viewModelScope.launch { savedStore.revision.drop(1).collect { reloadSavedPlaylists() } }
        viewModelScope.launch { showFinishedSaves() }
    }

    /** A shown song whose save left WorkManager's pending set turns "Saved" once its file exists. */
    private suspend fun showFinishedSaves() {
        var before = emptySet<String>()
        savingIds.collect { now ->
            val finished = before - now
            before = now
            if (finished.isEmpty()) return@collect
            val shown = mutableState.value.tracks.mapTo(hashSetOf(), YouTubeTrack::id)
            val saved = withContext(Dispatchers.IO) {
                finished.filter { it in shown && OfflineAudioLibrary.existing(getApplication(), it) != null }
            }
            if (saved.isNotEmpty()) mutableState.update { it.copy(offlineIds = it.offlineIds + saved) }
        }
    }

    private suspend fun reloadSavedPlaylists() {
        try {
            val saved = savedStore.read()
            val account = authorizedAccount?.name
            // While signed in, only this account's snapshot may replace what the API returned.
            if (accessToken != null &&
                (account.isNullOrBlank() || saved.ownerKey != SavedPlaylistStore.accountKey(account))
            ) return
            mutableState.value = mutableState.value.copy(playlists = saved.playlists, lastSyncedAt = saved.syncedAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJ", "Saved YouTube catalog reload failed: " + failure.javaClass.simpleName)
        }
    }

    fun setAutoEnabled(enabled: Boolean) {
        auto.setEnabled(enabled)
        mutableState.value = mutableState.value.copy(autoEnabled = enabled)
    }

    fun toggleAutoPlaylist(id: String) {
        auto.togglePlaylist(id)
        mutableState.value = mutableState.value.copy(autoDownloadIds = auto.playlistIds)
    }

    /** Saved metadata is displayed while offline; it is not a playable media cache. */
    fun showSavedCatalog() {
        viewModelScope.launch {
            try {
                val saved = savedStore.read()
                if (accessToken == null) {
                    mutableState.value = YouTubeUiState(
                        playlists = saved.playlists,
                        savedSnapshot = true,
                        lastSyncedAt = saved.syncedAt,
                        autoEnabled = auto.enabled,
                        autoDownloadIds = auto.playlistIds,
                    )
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.w("HomeDJ", "Saved YouTube catalog failed to load: " + failure.javaClass.simpleName)
            }
        }
    }

    /** Explicit account disconnect purges stored metadata and in-memory authentication. */
    fun disconnectAndEraseCatalog() {
        clear()
        auto.erase()
        viewModelScope.launch {
            try {
                savedStore.erase()
                if (accessToken == null) mutableState.value = YouTubeUiState()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.w("HomeDJ", "Could not erase YouTube snapshot: " + failure.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(error = "Could not erase saved YouTube playlists")
            }
        }
    }

    fun authorized(token: String, account: Account?) {
        if (token.isBlank()) {
            authorizationError("Google did not grant YouTube access")
            return
        }
        playlistJob?.cancel()
        trackJob?.cancel()
        accessToken = token
        authorizedAccount = account
        val accountName = account?.name
        if (accountName.isNullOrBlank()) auto.setEnabled(false)
        else auto.bindAccount(accountName)
        mutableState.value = YouTubeUiState(authorized = true, autoEnabled = auto.enabled, autoDownloadIds = auto.playlistIds)
        refresh()
    }

    fun refresh() {
        val token = accessToken ?: return
        val accountName = authorizedAccount?.name
        playlistJob?.cancel()
        trackJob?.cancel()
        mutableState.value = mutableState.value.copy(
            loadingPlaylists = true,
            selectedPlaylistId = null,
            tracks = emptyList(),
            loadingTracks = false,
            error = null,
        )
        playlistJob = viewModelScope.launch {
            try {
                val playlists = repository.playlists(token)
                val saved = if (accountName.isNullOrBlank()) null
                    else savedStore.replaceOwned(accountName, playlists)
                mutableState.value = mutableState.value.copy(
                    playlists = saved?.playlists ?: playlists, loadingPlaylists = false, error = null,
                    savedSnapshot = false, lastSyncedAt = saved?.syncedAt ?: 0L,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJ", "YouTube playlist fetch failed: " + failure.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(
                    loadingPlaylists = false, error = messageFor(failure),
                )
            }
        }
    }

    /** Imports an accessible public/unlisted playlist; without a token the extractor reads it. */
    fun importPlaylist(link: String) {
        val id = YouTubePlaylistLinks.idFromLink(link)
        if (id == null) {
            authorizationError("Paste a valid YouTube or YouTube Music playlist link.")
            return
        }
        val token = accessToken ?: return importPublicPlaylist(id)
        val accountName = authorizedAccount?.name
        playlistJob?.cancel()
        trackJob?.cancel()
        mutableState.value = mutableState.value.copy(
            loadingPlaylists = true, loadingTracks = false, tracks = emptyList(),
            selectedPlaylistId = null, error = null,
        )
        playlistJob = viewModelScope.launch {
            try {
                val playlist = repository.playlistById(token, id)
                if (playlist == null) {
                    mutableState.value = mutableState.value.copy(
                        loadingPlaylists = false,
                        error = "Playlist not found or not accessible to this account.",
                    )
                    return@launch
                }
                val saved = if (accountName.isNullOrBlank()) null
                    else savedStore.importPlaylist(accountName, playlist)
                val matching = saved?.takeIf { it.ownerKey == SavedPlaylistStore.accountKey(accountName.orEmpty()) }
                mutableState.value = mutableState.value.copy(
                    playlists = matching?.playlists
                        ?: (listOf(playlist) + mutableState.value.playlists).distinctBy(YouTubePlaylist::id),
                    loadingPlaylists = false,
                )
                RemoteActivity.record(RemoteActivity.SAMSUNG, "Added playlist " + playlist.title)
                selectPlaylist(playlist.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJ", "YouTube playlist import failed: " + failure.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(
                    loadingPlaylists = false, error = messageFor(failure),
                )
            }
        }
    }

    private fun importPublicPlaylist(id: String) {
        playlistJob?.cancel()
        trackJob?.cancel()
        mutableState.value = mutableState.value.copy(
            loadingPlaylists = true, loadingTracks = false, tracks = emptyList(),
            selectedPlaylistId = null, error = null,
        )
        playlistJob = viewModelScope.launch {
            try {
                val linked = NativeAudioSource.playlist(id)
                if (linked == null || linked.tracks.isEmpty()) {
                    mutableState.value = mutableState.value.copy(
                        loadingPlaylists = false,
                        error = "Playlist not found, private, or without playable songs.",
                    )
                    return@launch
                }
                val saved = savedStore.importPublic(linked)
                mutableState.value = mutableState.value.copy(playlists = saved.playlists, loadingPlaylists = false)
                RemoteActivity.record(RemoteActivity.SAMSUNG, "Added playlist " + linked.playlist.title)
                selectPlaylist(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJ", "Public playlist import failed: " + failure.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(
                    loadingPlaylists = false,
                    error = "Could not read this playlist. Check the link and connection.",
                )
            }
        }
    }

    fun selectPlaylist(id: String) {
        if (mutableState.value.playlists.none { it.id == id }) return
        val token = accessToken
        val accountName = authorizedAccount?.name
        trackJob?.cancel()
        mutableState.value = mutableState.value.copy(
            selectedPlaylistId = id, tracks = emptyList(), loadingTracks = true, error = null,
        )
        trackJob = viewModelScope.launch {
            try {
                val tracks = if (token == null) {
                    savedStore.read().tracks[id].orEmpty()
                } else {
                    repository.tracks(token, id).also { fetched ->
                        if (!accountName.isNullOrBlank()) try {
                            savedStore.storeTracks(accountName, id, fetched)
                        } catch (failure: IOException) {
                            // Offline snapshot is best-effort; still show what was fetched.
                            Log.w("HomeDJ", "Saving playlist tracks failed: " + failure.javaClass.simpleName)
                        }
                    }
                }
                val offline = withContext(Dispatchers.IO) {
                    tracks.filter { OfflineAudioLibrary.existing(getApplication(), it.id) != null }
                        .mapTo(hashSetOf()) { it.id }
                }
                mutableState.value = mutableState.value.copy(
                    tracks = tracks, loadingTracks = false, error = null,
                    offlineIds = mutableState.value.offlineIds + offline,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJ", "YouTube track fetch failed: " + failure.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(
                    loadingTracks = false, error = messageFor(failure),
                )
            }
        }
    }

    /** User-requested offline saves are durable WorkManager jobs, independent of this screen. */
    fun saveTrackOffline(track: YouTubeTrack) {
        if (!NativeAudioSource.validId(track.id)) return
        OfflineAudioLibrary.enqueue(getApplication(), track)
        mutableState.value = mutableState.value.copy(notice = "Queued download: " + track.title)
        RemoteActivity.record(RemoteActivity.SAMSUNG, "Saving " + track.title)
    }

    fun saveSelectedOffline() {
        val selected = mutableState.value
        if (selected.selectedPlaylistId == null) return
        val tracks = selected.tracks.filter { NativeAudioSource.validId(it.id) }
            .distinctBy(YouTubeTrack::id).take(250)
        tracks.forEach { OfflineAudioLibrary.enqueue(getApplication(), it) }
        mutableState.value = mutableState.value.copy(
            notice = "Queued " + tracks.size + " songs for offline saving.",
        )
        RemoteActivity.record(RemoteActivity.SAMSUNG, "Saving " + tracks.size + " songs")
    }

    /** Playlist metadata from the YouTube API when connected, else the extractor. */
    fun downloadPlaylistLink(link: String) {
        val id = YouTubePlaylistLinks.idFromLink(link)
        val token = accessToken
        if (id == null) {
            authorizationError("Paste a valid YouTube or YouTube Music playlist link.")
            return
        }
        viewModelScope.launch {
            try {
                val tracks = (if (token == null) NativeAudioSource.playlist(id)?.tracks.orEmpty()
                    else repository.tracks(token, id))
                    .filter { NativeAudioSource.validId(it.id) }
                    .distinctBy(YouTubeTrack::id).take(250)
                if (accessToken != token) return@launch
                tracks.forEach { OfflineAudioLibrary.enqueue(getApplication(), it) }
                mutableState.value = mutableState.value.copy(
                    notice = "Queued " + tracks.size + " songs from the linked playlist.",
                )
                RemoteActivity.record(RemoteActivity.SAMSUNG, "Saving " + tracks.size + " songs from a link")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("HomeDJ", "In-app playlist download queue failed: " + error.javaClass.simpleName)
                authorizationError("Unable to read this playlist for offline saving.")
            }
        }
    }

    fun authorizationError(message: String) {
        mutableState.value = mutableState.value.copy(error = message)
    }

    fun clear() {
        playlistJob?.cancel()
        trackJob?.cancel()
        accessToken = null
        authorizedAccount = null
        mutableState.value = YouTubeUiState()
    }

    private fun messageFor(failure: Exception): String =
        when ((failure as? YouTubeHttpFailure)?.statusCode) {
            401 -> "YouTube authorization expired. Connect again."
            403 -> "YouTube access denied. Check API access and account permissions."
            else -> "Could not load YouTube playlists. Check the connection and try again."
        }

    override fun onCleared() {
        clear()
        super.onCleared()
    }
}
