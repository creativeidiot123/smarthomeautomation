package dev.homedj.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

/**
 * The OnePlus Home tab: Samsung's playlists as a grid, one playlist, or Samsung's downloads.
 * Everything shown is a page Samsung sent; every action is a LAN command Samsung validates.
 */
@Composable
internal fun ControllerLibrary(
    state: RemoteUiState,
    sortState: PlaylistSortUiState,
    onSort: (playlist: String, PlaylistSort) -> Unit,
    onBrowsePlaylists: () -> Unit,
    onBrowseDownloads: () -> Unit,
    onBrowseTracks: (String) -> Unit,
    onRetryBrowse: () -> Unit,
    onLibraryAction: (String, String) -> Unit,
    onDownloadChange: (remove: Boolean, kind: String, id: String) -> Unit,
    onImportLink: (String) -> Unit,
    onRefreshPlaylist: (String) -> Unit,
) {
    val enabled = state.connected
    var removeSong by rememberSaveable { mutableStateOf<String?>(null) }
    removeSong?.let { id -> state.songs.firstOrNull { it.id == id } }?.let { song ->
        ConfirmDialog("Remove download?", "This deletes the downloaded audio of \"${song.title}\" from Samsung.",
            "Remove", onConfirm = { onDownloadChange(true, "track", song.id) }, onDone = { removeSong = null })
    }
    val songMenu = { song: PageSong ->
        buildList {
            add(SongMenuAction(if (song.saved) "Play offline" else "Play", R.drawable.ic_play) {
                onLibraryAction("playTrack", song.id)
            })
            when {
                song.saved -> add(SongMenuAction("Remove download", R.drawable.ic_delete) { removeSong = song.id })
                song.saving -> add(SongMenuAction("Cancel download", R.drawable.ic_close) {
                    onDownloadChange(false, "track", song.id)
                })
                else -> add(SongMenuAction("Download", R.drawable.ic_download) { onLibraryAction("saveTrack", song.id) })
            }
        }
    }
    val songs = remember(state.songs) { state.songs.map {
        PageSong(it.id, it.title, songDetails(it.artist, it.durationSeconds), youTubeArtworkUrl(it.id),
            saved = it.saved, saving = it.saving && !it.saved, artist = it.artist, durationSeconds = it.durationSeconds,
            failed = it.failed)
    } }
    val footer: @Composable () -> Unit = {
        if (state.browserLoading) Text(if (songs.isEmpty()) "Loading library…" else "Updating library…")
        if (enabled && state.error != null && !state.browserLoading) {
            OutlinedButton(onClick = onRetryBrowse) {
                Text("Retry")
            }
        }
        if (!enabled || state.browserCached) Text(
            if (enabled && state.browserLoading) "Cached library. Checking Samsung for updates…"
            else if (enabled) "Cached library. Retry to check Samsung for updates."
            else "Cached library. Connect to Samsung to play or update songs.",
            style = MaterialTheme.typography.bodySmall)
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }

    when (state.browserMode) {
        "tracks" -> {
            val id = state.selectedPlaylistId.orEmpty()
            // Older Samsung builds send no whole-playlist counts; fall back to the loaded songs.
            val download = if (state.savedCount >= 0) downloadState(state.browserTotal, state.savedCount, state.savingCount)
                else downloadState(songs.size, songs.count { it.saved }, songs.count { it.saving })
            PlaylistPage(
                title = state.selectedPlaylistTitle,
                sortKey = id,
                sortState = sortState,
                onSort = onSort,
                countText = songCountText(state.browserTotal,
                    if (state.savedCount >= 0) state.savedCount else songs.count { it.saved },
                    if (state.savedCount >= 0) state.savingCount else songs.count { it.saving }),
                artworkUrl = state.playlists.firstOrNull { it.id == id }?.artworkUrl?.ifBlank { null }
                    ?: songs.firstOrNull()?.artworkUrl,
                songs = songs,
                loading = state.browserLoading,
                enabled = enabled,
                onBack = onBrowsePlaylists,
                onPlaySong = { onLibraryAction("playTrack", it.id) },
                songMenu = songMenu,
                onPlay = { onLibraryAction("playPlaylist", id) },
                onShuffle = { songs.randomOrNull()?.let { onLibraryAction("playTrack", it.id) } },
                download = download,
                onDownload = { onLibraryAction("savePlaylist", id) },
                onCancelDownload = { onDownloadChange(false, "playlist", id) },
                onRemoveDownload = { onDownloadChange(true, "playlist", id) },
                onRefresh = { onRefreshPlaylist(id) },
                refreshing = state.refreshingPlaylist,
                footer = footer,
            )
        }
        "downloads" -> PlaylistPage(
            title = "Downloaded on Samsung",
            sortKey = DOWNLOADED_SORT_KEY,
            sortState = sortState,
            onSort = onSort,
            countText = songCountText(state.browserTotal, 0),
            artworkUrl = null,
            placeholder = R.drawable.ic_download_done,
            songs = songs,
            loading = state.browserLoading,
            enabled = enabled,
            onBack = onBrowsePlaylists,
            onPlaySong = { onLibraryAction("playTrack", it.id) },
            songMenu = songMenu,
            onPlay = null,
            onShuffle = null,
            footer = footer,
        )
        else -> {
            var showLink by rememberSaveable { mutableStateOf(false) }
            var playlistLink by rememberSaveable { mutableStateOf("") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Playlists", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = { showLink = !showLink }, enabled = enabled) {
                    Icon(painterResource(if (showLink) R.drawable.ic_close else R.drawable.ic_playlist_add),
                        contentDescription = if (showLink) "Hide playlist link" else "Add playlist by link")
                }
            }
            if (showLink) {
                OutlinedTextField(value = playlistLink,
                    onValueChange = { if (it.length <= 2048) playlistLink = it },
                    label = { Text("YouTube playlist link") }, singleLine = true,
                    enabled = enabled, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onImportLink(playlistLink) },
                    enabled = enabled && playlistLink.isNotBlank() && !state.importingLink) {
                    Text(if (state.importingLink) "Adding on Samsung…" else "Add to Samsung")
                }
            }
            if (state.browserLoading) Text("Loading music…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            AdaptiveGrid(listOf<RemotePlaylist?>(null) + state.playlists) { playlist, modifier ->
                if (playlist == null) {
                    PlaylistCard("Downloaded", "On Samsung", onClick = onBrowseDownloads, modifier = modifier,
                        placeholder = R.drawable.ic_download_done)
                } else {
                    PlaylistCard(playlist.title, songCountText(playlist.count, 0).ifBlank { "Empty" },
                        onClick = { onBrowseTracks(playlist.id) }, modifier = modifier,
                        artworkUrl = playlist.artworkUrl.ifBlank { null })
                }
            }
            if (!state.browserLoading && state.playlists.isEmpty() && enabled) {
                Text("No saved playlists yet. Add a playlist link.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            footer()
            Text("Online songs need Samsung's internet connection.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
