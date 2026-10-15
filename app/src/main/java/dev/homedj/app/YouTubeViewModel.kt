package dev.homedj.app

import android.accounts.Account
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

/** Screen-scoped projection; tokens, identity and metadata never leave process memory. */
class YouTubeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = YouTubePlaylistRepository()
    private val savedStore = SavedPlaylistStore(application)
    private val mutableState = MutableStateFlow(YouTubeUiState())
    val state = mutableState.asStateFlow()
    private var accessToken: String? = null
    var authorizedAccount: Account? = null
        private set
    private var playlistJob: Job? = null
    private var trackJob: Job? = null

    init { showSavedCatalog() }

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
        mutableState.value = YouTubeUiState(authorized = true)
        refresh()
    }

    fun refresh() {
        val token = accessToken ?: return
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
                val saved = savedStore.replaceOwned(playlists)
                mutableState.value = mutableState.value.copy(
                    playlists = saved.playlists, loadingPlaylists = false, error = null,
                    savedSnapshot = false, lastSyncedAt = saved.syncedAt,
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

    /** Imports an accessible public/unlisted playlist into this in-memory browsing session. */
    fun importPlaylist(link: String) {
        val id = YouTubePlaylistLinks.idFromLink(link)
        if (id == null) {
            authorizationError("Paste a valid YouTube or YouTube Music playlist link.")
            return
        }
        val token = accessToken ?: return
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
                val saved = savedStore.importPlaylist(playlist)
                mutableState.value = mutableState.value.copy(
                    playlists = saved.playlists, loadingPlaylists = false,
                )
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

    fun selectPlaylist(id: String) {
        if (mutableState.value.playlists.none { it.id == id }) return
        val token = accessToken
        trackJob?.cancel()
        mutableState.value = mutableState.value.copy(
            selectedPlaylistId = id, tracks = emptyList(), loadingTracks = true, error = null,
        )
        trackJob = viewModelScope.launch {
            try {
                val tracks = if (token == null) {
                    savedStore.read().tracks[id].orEmpty()
                } else {
                    repository.tracks(token, id).also { savedStore.storeTracks(id, it) }
                }
                mutableState.value = mutableState.value.copy(
                    tracks = tracks, loadingTracks = false, error = null,
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
