package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Samsung's Home tab: the playlist grid, or one playlist ([HOME_PLAYLIST]) or the downloads ([HOME_DOWNLOADS]). */
internal const val HOME_GRID = "grid"
internal const val HOME_PLAYLIST = "playlist"
internal const val HOME_DOWNLOADS = "downloads"

@Composable
internal fun YouTubeSection(
    state: YouTubeUiState,
    sortState: PlaylistSortUiState,
    onSort: (playlist: String, PlaylistSort) -> Unit,
    view: String,
    onViewChange: (String) -> Unit,
    enabled: Boolean,
    onPickAudio: () -> Unit,
    onSelectPlaylist: (String) -> Unit,
    onImportPlaylist: (String) -> Unit,
    onDownloadPlaylist: (String) -> Unit,
    onStreamTrack: (YouTubeTrack) -> Unit,
    onPlayTracks: (List<YouTubeTrack>, Int) -> Unit,
    onPlayNext: (YouTubeTrack) -> Unit,
    onAddToQueue: (YouTubeTrack) -> Unit,
    onDownloadTrack: (YouTubeTrack) -> Unit,
    onDownloadSelected: () -> Unit,
    onRefreshSelected: () -> Unit,
    onCancelSaves: (List<YouTubeTrack>) -> Unit,
    onRemoveDownloads: (List<YouTubeTrack>, String?) -> Unit,
    onToggleAutoPlaylist: (String) -> Unit,
    onAddToSchedule: (String) -> Unit,
) {
    // The song whose "Remove download" waits for confirmation.
    var removeSong by rememberSaveable { mutableStateOf<String?>(null) }
    val songMenu = { track: YouTubeTrack ->
        val saved = track.id in state.offlineIds || state.downloads.any { it.id == track.id }
        buildList {
            add(SongMenuAction("Play next", R.drawable.ic_playlist_play) { onPlayNext(track) })
            add(SongMenuAction("Add to queue", R.drawable.ic_playlist_add) { onAddToQueue(track) })
            when {
                saved -> add(SongMenuAction("Remove download", R.drawable.ic_delete) { removeSong = track.id })
                track.id in state.savingIds -> add(SongMenuAction("Cancel download", R.drawable.ic_close) {
                    onCancelSaves(listOf(track))
                })
                else -> add(SongMenuAction("Download", R.drawable.ic_download) { onDownloadTrack(track) })
            }
        }
    }
    removeSong?.let { id -> (state.tracks + state.downloads).firstOrNull { it.id == id } }?.let { track ->
        ConfirmDialog("Remove download?", "This deletes the downloaded audio of \"${track.title}\" from Samsung.",
            "Remove", onConfirm = { onRemoveDownloads(listOf(track), null) }, onDone = { removeSong = null })
    }

    when (view) {
        HOME_PLAYLIST -> {
            val selected = state.selectedPlaylistId
            if (selected == null) {
                // A sync cleared the selection; go back to the grid after this frame.
                LaunchedEffect(Unit) { onViewChange(HOME_GRID) }
            } else {
                val playlist = state.playlists.firstOrNull { it.id == selected }
                val tracks = state.tracks
                val saved = tracks.count { it.id in state.offlineIds }
                val saving = tracks.count { it.id in state.savingIds && it.id !in state.offlineIds }
                val pageSongs = remember(tracks, state.offlineIds, state.savingIds, state.failedIds) {
                    tracks.map { it.toPageSong(it.id in state.offlineIds, it.id in state.savingIds, failed = it.id in state.failedIds) }
                }
                PlaylistPage(
                    title = playlist?.title ?: "Playlist",
                    sortKey = selected,
                    sortState = sortState,
                    onSort = onSort,
                    countText = if (state.loadingTracks) "Loading songs…" else songCountText(tracks.size, saved, saving),
                    artworkUrl = playlist?.artworkUrl?.ifBlank { null } ?: tracks.firstOrNull()?.id?.let(::youTubeArtworkUrl),
                    songs = pageSongs,
                    loading = state.loadingTracks,
                    enabled = enabled,
                    onBack = { onViewChange(HOME_GRID) },
                    onPlaySong = { song -> tracks.firstOrNull { it.id == song.id }?.let(onStreamTrack) },
                    songMenu = { song -> tracks.firstOrNull { it.id == song.id }?.let(songMenu).orEmpty() },
                    onPlay = { tracks.firstOrNull()?.let(onStreamTrack) },
                    onShuffle = { tracks.randomOrNull()?.let(onStreamTrack) },
                    download = downloadState(tracks.size, saved, saving),
                    onDownload = onDownloadSelected,
                    onCancelDownload = { onCancelSaves(tracks) },
                    onRemoveDownload = { onRemoveDownloads(tracks, selected) },
                    onAddToSchedule = { onAddToSchedule(selected) },
                    onRefresh = onRefreshSelected,
                    refreshing = state.refreshingTracks,
                    below = {
                        if (state.authorized) {
                            val auto = selected in state.autoDownloadIds
                            FilterChip(
                                selected = auto,
                                onClick = { onToggleAutoPlaylist(selected) },
                                label = { Text(if (state.autoEnabled) "Auto-save on Wi-Fi" else "Include in auto-save") },
                                leadingIcon = if (auto) {
                                    { Icon(painterResource(R.drawable.ic_download_done), contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else null,
                            )
                        }
                        Messages(state)
                    },
                )
            }
        }
        HOME_DOWNLOADS -> {
            val songs = state.downloads
            PlaylistPage(
                title = "Downloaded",
                sortKey = DOWNLOADED_SORT_KEY,
                sortState = sortState,
                onSort = onSort,
                countText = songCountText(songs.size, 0),
                artworkUrl = null,
                placeholder = R.drawable.ic_download_done,
                songs = remember(songs, state.downloadArt) {
                    songs.map { it.toPageSong(saved = true, saving = false, art = state.downloadArt[it.id]) }
                },
                loading = false,
                enabled = enabled,
                onBack = { onViewChange(HOME_GRID) },
                onPlaySong = { song -> onPlayTracks(songs, songs.indexOfFirst { it.id == song.id }) },
                songMenu = { song -> songs.firstOrNull { it.id == song.id }?.let(songMenu).orEmpty() },
                onPlay = { onPlayTracks(songs, 0) },
                onShuffle = { if (songs.isNotEmpty()) onPlayTracks(songs, songs.indices.random()) },
                below = { Messages(state) },
            )
        }
        else -> LibraryGrid(state, enabled, onPickAudio, onImportPlaylist, onDownloadPlaylist,
            onOpenDownloads = { onViewChange(HOME_DOWNLOADS) },
            onOpenPlaylist = { id ->
                onViewChange(HOME_PLAYLIST)
                onSelectPlaylist(id)
            })
    }
}

@Composable
private fun LibraryGrid(
    state: YouTubeUiState,
    enabled: Boolean,
    onPickAudio: () -> Unit,
    onImportPlaylist: (String) -> Unit,
    onDownloadPlaylist: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    var showLink by rememberSaveable { mutableStateOf(false) }
    var playlistLink by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Playlists", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onPickAudio, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_music_note), contentDescription = "Play audio files from this phone")
        }
        FilledTonalIconButton(onClick = { showLink = !showLink }) {
            Icon(painterResource(if (showLink) R.drawable.ic_close else R.drawable.ic_playlist_add),
                contentDescription = if (showLink) "Hide playlist link" else "Add playlist by link")
        }
    }
    if (state.authorized && !state.autoEnabled && state.autoDownloadIds.isNotEmpty()) {
        Text("Automatic downloads are off. Turn them on in Settings.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Messages(state)
    if (showLink) {
        OutlinedTextField(
            value = playlistLink,
            onValueChange = { if (it.length <= 2048) playlistLink = it },
            label = { Text("YouTube playlist URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onImportPlaylist(playlistLink) },
                enabled = playlistLink.isNotBlank() && !state.loadingPlaylists) { Text("Add playlist") }
            OutlinedButton(onClick = { onDownloadPlaylist(playlistLink) },
                enabled = playlistLink.isNotBlank()) { Text("Download only") }
        }
    }
    if (state.loadingPlaylists) Text("Syncing playlists…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    AdaptiveGrid(listOf<YouTubePlaylist?>(null) + if (state.loadingPlaylists) emptyList() else state.playlists) { playlist, modifier ->
        if (playlist == null) {
            PlaylistCard("Downloaded", songCountText(state.downloads.size, 0).ifBlank { "No songs yet" },
                onClick = onOpenDownloads, modifier = modifier, placeholder = R.drawable.ic_download_done)
        } else {
            PlaylistCard(
                title = playlist.title,
                subtitle = songCountText(playlist.itemCount, 0).ifBlank { "Empty" } +
                    if (playlist.id in state.autoDownloadIds) " • Auto-save" else "",
                onClick = { onOpenPlaylist(playlist.id) },
                modifier = modifier,
                artworkUrl = playlist.artworkUrl.ifBlank { null },
            )
        }
    }
    if (!state.loadingPlaylists && state.playlists.isEmpty()) {
        Text("No playlists yet. Add a playlist link.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Sync, auto-save and disconnect for a Google account granted earlier, on Samsung's Settings tab.
 * HomeDJ offers no way to connect one; without an account nothing is shown.
 */
@Composable
internal fun YouTubeAccountCard(
    state: YouTubeUiState,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onAutoEnabled: (Boolean) -> Unit,
) {
    if (!state.authorized) return
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    if (confirmDisconnect) {
        ConfirmDialog("Disconnect YouTube?",
            "HomeDJ will revoke access, erase saved playlist details and stop automatic downloads. " +
                "Already downloaded audio stays on this phone.", "Disconnect",
            onConfirm = onDisconnect, onDone = { confirmDisconnect = false })
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("YouTube", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Account connected", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onSync, enabled = !state.loadingPlaylists) { Text("Sync playlists") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-save playlists", style = MaterialTheme.typography.titleMedium)
                    Text("Playlists marked Auto-save download in full on unmetered Wi-Fi. Every playlist refreshes hourly.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.autoEnabled, onCheckedChange = onAutoEnabled)
            }
            Text("Only download audio you have permission to save. Protected or unavailable songs cannot be downloaded.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect and erase playlists") }
        }
    }
}

@Composable
private fun Messages(state: YouTubeUiState) {
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

private fun YouTubeTrack.toPageSong(saved: Boolean, saving: Boolean, art: String? = null, failed: Boolean = false) = PageSong(
    id = id,
    title = title,
    details = songDetails(artist, durationSeconds),
    artworkUrl = art ?: youTubeArtworkUrl(id),
    saved = saved,
    saving = saving && !saved,
    artist = artist,
    durationSeconds = durationSeconds,
    failed = failed,
)
