package dev.homedj.app

import android.accounts.Account
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    /** The open playlist is being read again from YouTube. */
    val refreshingTracks: Boolean = false,
    val tracks: List<YouTubeTrack> = emptyList(),
    val error: String? = null,
    val savedSnapshot: Boolean = false,
    val lastSyncedAt: Long = 0L,
    val autoEnabled: Boolean = false,
    val autoDownloadIds: Set<String> = emptySet(),
    /** Video IDs with a downloaded file, from [downloads]. */
    val offlineIds: Set<String> = emptySet(),
    /** Saves still queued or running on this phone, live from WorkManager. */
    val savingIds: Set<String> = emptySet(),
    /** Songs whose last save failed after every retry, live from WorkManager. */
    val failedIds: Set<String> = emptySet(),
    val notice: String? = null,
    /** Every downloaded song, newest first, from the files on this phone. */
    val downloads: List<YouTubeTrack> = emptyList(),
    /** Video ID to the `file://` artwork saved with its download, for viewing offline. */
    val downloadArt: Map<String, String> = emptyMap(),
)

/** Screen-scoped projection; tokens, identity and metadata never leave process memory. */
class YouTubeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = YouTubePlaylistRepository()
    private val savedStore = SavedPlaylistStore.shared(application)
    private val auto = AutoLibraryConfig(application)
    private val mutableState = MutableStateFlow(YouTubeUiState())
    private val savingIds = OfflineAudioLibrary.pendingIds(application)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val failedIds = OfflineAudioLibrary.failedIds(application)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    /** Kept apart from [mutableState], which account changes reset; the files outlive any account. */
    private val downloads = MutableStateFlow(emptyList<YouTubeTrack>() to emptyMap<String, String>())
    val state: StateFlow<YouTubeUiState> = combine(mutableState, savingIds, failedIds, downloads) { ui, saving, failed, saved ->
        ui.copy(savingIds = saving, failedIds = failed, downloads = saved.first, downloadArt = saved.second,
            offlineIds = saved.first.mapTo(hashSetOf(), YouTubeTrack::id))
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
        // Saves finishing or removed on either phone turn rows "Downloaded" or back without reopening.
        viewModelScope.launch { OfflineAudioLibrary.revision.collect { reloadDownloads() } }
    }

    private suspend fun reloadDownloads() {
        try {
            val app = getApplication<Application>()
            val tracks = OfflineAudioLibrary.savedTracks(app)
            val art = withContext(Dispatchers.IO) {
                tracks.mapNotNull { track ->
                    OfflineSongInfo.artFile(app, track.id).takeIf { it.isFile }
                        ?.let { track.id to android.net.Uri.fromFile(it).toString() }
                }.toMap()
            }
            downloads.value = tracks to art
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJOffline", "Downloaded songs list failed: " + failure.javaClass.simpleName)
        }
    }

    /** Stops pending saves of these songs; files already downloaded stay. */
    fun cancelSaves(tracks: List<YouTubeTrack>) {
        viewModelScope.launch { OfflineAudioLibrary.cancel(getApplication(), tracks.map(YouTubeTrack::id)) }
        mutableState.update { it.copy(notice = "Stopped saving " + tracks.size + " songs.") }
        RemoteActivity.record(RemoteActivity.SAMSUNG, "Stopped saving " + tracks.size + " songs")
    }

    /**
     * Deletes downloads after the user confirmed. A whole playlist ([playlistId]) also leaves
     * auto-save, so the next sync does not download it again.
     */
    fun removeDownloads(tracks: List<YouTubeTrack>, playlistId: String? = null) {
        val ids = tracks.map(YouTubeTrack::id)
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val removed = if (playlistId != null) OfflineAudioLibrary.removePlaylist(app, playlistId, ids)
                    else OfflineAudioLibrary.remove(app, ids)
                mutableState.update {
                    it.copy(autoDownloadIds = auto.playlistIds, notice = "Removed " + songCount(removed) + ".")
                }
                RemoteActivity.record(RemoteActivity.SAMSUNG, "Removed " + songCount(removed))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJOffline", "Download removal failed: " + failure.javaClass.simpleName)
                mutableState.update { it.copy(error = "Could not remove the downloads. Try again.") }
            }
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
            mutableState.update { it.copy(playlists = saved.playlists, lastSyncedAt = saved.syncedAt,
                tracks = saved.tracks[it.selectedPlaylistId] ?: it.tracks) }
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
                // Recover a batch interrupted before all song jobs were durably queued.
                PlaylistDownloads.continueLater(getApplication())
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
        // Wait for an in-flight owned-playlist sync instead of cancelling it.
        val previous = playlistJob
        trackJob?.cancel()
        mutableState.value = mutableState.value.copy(
            loadingPlaylists = true, loadingTracks = false, tracks = emptyList(),
            selectedPlaylistId = null, error = null,
        )
        playlistJob = viewModelScope.launch {
            try {
                previous?.join()
                mutableState.value = mutableState.value.copy(loadingPlaylists = true)
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
        // Wait for an in-flight owned-playlist sync instead of cancelling it.
        val previous = playlistJob
        trackJob?.cancel()
        mutableState.value = mutableState.value.copy(
            loadingPlaylists = true, loadingTracks = false, tracks = emptyList(),
            selectedPlaylistId = null, error = null,
        )
        playlistJob = viewModelScope.launch {
            try {
                previous?.join()
                mutableState.value = mutableState.value.copy(loadingPlaylists = true)
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
                val saved = savedStore.read()
                val cached = if (token != null && accountName.isNullOrBlank()) null
                    else cachedPlaylistTracks(saved, id, accountName)
                val tracks = if (cached != null || token == null) {
                    cached.orEmpty()
                } else {
                    repository.tracks(token, id).also { fetched ->
                        // Like a refresh, so songs new to a downloaded playlist still queue.
                        if (!accountName.isNullOrBlank()) try {
                            PlaylistRefresh.commit(getApplication(), SavedPlaylistStore.accountKey(accountName), id, fetched, null)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            // Offline snapshot is best-effort; still show what was fetched.
                            Log.w("HomeDJ", "Saving playlist tracks failed: " + failure.javaClass.simpleName)
                        }
                    }
                }
                mutableState.value = mutableState.value.copy(tracks = tracks, loadingTracks = false, error = null)
                prefetchMusicArtwork(getApplication(), tracks.mapNotNull { youTubeArtworkUrl(it.id) })
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

    /**
     * Refresh on the open playlist: reads it again (with the account, else as a link) and shows the
     * stored songs; songs new to a downloaded playlist queue for Wi-Fi. A second tap meanwhile is ignored.
     */
    fun refreshSelectedPlaylist() {
        val current = mutableState.value
        val id = current.selectedPlaylistId ?: return
        if (current.refreshingTracks || current.loadingTracks) return
        val token = accessToken
        mutableState.update { it.copy(refreshingTracks = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val outcome = PlaylistRefresh.refresh(getApplication(), token, id)
                val tracks = savedStore.read().tracks[id]
                mutableState.update {
                    val shown = if (it.selectedPlaylistId == id && tracks != null) tracks else it.tracks
                    if (outcome is RefreshOutcome.Done) it.copy(tracks = shown, notice = refreshMessage(outcome))
                    else it.copy(error = refreshError(outcome))
                }
                if (outcome is RefreshOutcome.Done && outcome.queued > 0) {
                    RemoteActivity.record(RemoteActivity.SAMSUNG, "Saving " + outcome.queued + " new songs")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJ", "Playlist refresh failed: " + failure.javaClass.simpleName)
                mutableState.update { it.copy(error = "Could not refresh this playlist. Check the connection.") }
            } finally {
                mutableState.update { it.copy(refreshingTracks = false) }
            }
        }
    }

    /** User-requested offline saves are durable WorkManager jobs, independent of this screen. */
    fun saveTrackOffline(track: YouTubeTrack) {
        if (!NativeAudioSource.validId(track.id)) return
        viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { OfflineAudioLibrary.enqueue(getApplication(), track) }) {
                mutableState.update { it.copy(notice = "Already downloaded: " + track.title) }
                return@launch
            }
            mutableState.update { it.copy(notice = "Queued download: " + track.title) }
            RemoteActivity.record(RemoteActivity.SAMSUNG, "Saving " + track.title)
        }
    }

    /**
     * Only songs without a file are queued, so "Download the rest" never repeats saved ones; failed
     * songs are tried again. Past one batch, [PlaylistDownloads] queues the rest as saves end.
     */
    fun saveSelectedOffline() {
        val selected = mutableState.value
        val playlistId = selected.selectedPlaylistId ?: return
        val tracks = selected.tracks
        viewModelScope.launch {
            try {
                val started = PlaylistDownloads.start(getApplication(), playlistId, tracks)
                mutableState.update {
                    it.copy(notice = queuedNotice(started.queued, "for offline saving") + batchNote(started.later))
                }
                if (started.queued > 0) RemoteActivity.record(RemoteActivity.SAMSUNG, "Saving " + started.queued + " songs")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJOffline", "Playlist download start failed: " + failure.javaClass.simpleName)
                mutableState.update { it.copy(error = "Could not start the downloads. Try again.") }
            }
        }
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
                val queued = withContext(Dispatchers.IO) {
                    tracks.count { OfflineAudioLibrary.enqueue(getApplication(), it) }
                }
                mutableState.update { it.copy(notice = queuedNotice(queued, "from the linked playlist")) }
                if (queued > 0) RemoteActivity.record(RemoteActivity.SAMSUNG, "Saving " + queued + " songs from a link")
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
