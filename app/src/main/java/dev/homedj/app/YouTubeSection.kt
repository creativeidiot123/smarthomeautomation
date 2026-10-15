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
import androidx.compose.material3.CardDefaults
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
    view: String,
    onViewChange: (String) -> Unit,
    enabled: Boolean,
    onConnect: () -> Unit,
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
    onCancelSaves: (List<YouTubeTrack>) -> Unit,
    onRemoveDownloads: (List<YouTubeTrack>, String?) -> Unit,
    onToggleAutoPlaylist: (String) -> Unit,
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
                PlaylistPage(
                    title = playlist?.title ?: "Playlist",
                    countText = if (state.loadingTracks) "Loading songs…" else songCountText(tracks.size, saved),
                    artworkUrl = playlist?.artworkUrl?.ifBlank { null } ?: tracks.firstOrNull()?.id?.let(::youTubeArtworkUrl),
                    songs = tracks.map { it.toPageSong(it.id in state.offlineIds, it.id in state.savingIds) },
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
                countText = songCountText(songs.size, 0),
                artworkUrl = null,
                placeholder = R.drawable.ic_download_done,
                songs = songs.map { it.toPageSong(saved = true, saving = false, art = state.downloadArt[it.id]) },
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
        else -> LibraryGrid(state, enabled, onConnect, onPickAudio, onImportPlaylist, onDownloadPlaylist,
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
    onConnect: () -> Unit,
    onPickAudio: () -> Unit,
    onImportPlaylist: (String) -> Unit,
    onDownloadPlaylist: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    var showLink by rememberSaveable { mutableStateOf(false) }
    var playlistLink by rememberSaveable { mutableStateOf("") }
    if (!state.authorized) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sign in to see your playlists", style = MaterialTheme.typography.titleMedium)
                Text(if (state.savedSnapshot && state.playlists.isNotEmpty())
                    "Showing saved playlists. Connect YouTube to refresh them."
                    else "Connect YouTube on Samsung, or add a public playlist by link.")
                Button(onClick = onConnect) { Text("Connect YouTube") }
            }
        }
    }
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
        Text("No playlists yet. Connect YouTube or add a playlist link.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** YouTube account, automatic sync and disconnect, on Samsung's Settings tab. */
@Composable
internal fun YouTubeAccountCard(
    state: YouTubeUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAutoEnabled: (Boolean) -> Unit,
) {
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
            if (!state.authorized) {
                Text("Not connected. Playlists added by link work without an account.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onConnect) { Text("Connect YouTube") }
                return@Column
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Account connected", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onConnect, enabled = !state.loadingPlaylists) { Text("Sync playlists") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic sync", style = MaterialTheme.typography.titleMedium)
                    Text("About every 12 hours on unmetered Wi-Fi.", style = MaterialTheme.typography.bodySmall)
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

private fun YouTubeTrack.toPageSong(saved: Boolean, saving: Boolean, art: String? = null) = PageSong(
    id = id,
    title = title,
    details = songDetails(artist, durationSeconds),
    artworkUrl = art ?: youTubeArtworkUrl(id),
    saved = saved,
    saving = saving && !saved,
)
