package dev.homedj.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
internal fun YouTubeSection(
    state: YouTubeUiState,
    showPlaylists: Boolean,
    onShowPlaylistsChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectPlaylist: (String) -> Unit,
    onImportPlaylist: (String) -> Unit,
    onDownloadPlaylist: (String) -> Unit,
    onStreamTrack: (YouTubeTrack) -> Unit,
    onPlayNext: (YouTubeTrack) -> Unit,
    onAddToQueue: (YouTubeTrack) -> Unit,
    onDownloadTrack: (YouTubeTrack) -> Unit,
    onDownloadSelected: () -> Unit,
    onAutoEnabled: (Boolean) -> Unit,
    onToggleAutoPlaylist: (String) -> Unit,
) {
    var playlistLink by rememberSaveable { mutableStateOf("") }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var showLink by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect YouTube?") },
            text = { Text("HomeDJ will revoke access, erase saved playlist details and stop automatic downloads. Already downloaded audio stays on this phone.") },
            confirmButton = {
                Button(onClick = {
                    confirmDisconnect = false
                    onDisconnect()
                }) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") }
            },
        )
    }

    val selected = state.selectedPlaylistId
    if (selected != null && !showPlaylists) {
        BackHandler { onShowPlaylistsChange(true) }
        IconButton(onClick = { onShowPlaylistsChange(true) }) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "All playlists")
        }
        val tracks = state.tracks
        PlaylistHeader(
            title = state.playlists.firstOrNull { it.id == selected }?.title ?: "Playlist",
            subtitle = if (state.loadingTracks) "Loading songs…" else "${tracks.size} songs",
            artworkUrl = tracks.firstOrNull()?.id?.let(::youTubeArtworkUrl),
        ) {
            val ready = !state.loadingTracks && tracks.isNotEmpty()
            Button(onClick = { tracks.firstOrNull()?.let(onStreamTrack) }, enabled = ready,
                modifier = Modifier.weight(1f)) { IconLabel(R.drawable.ic_play, "Play") }
            OutlinedButton(onClick = onDownloadSelected, enabled = ready,
                modifier = Modifier.weight(1f)) { IconLabel(R.drawable.ic_download, "Save offline") }
        }
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
        if (!state.loadingTracks && tracks.isEmpty()) Text("No songs are available in this playlist.")
        Column {
            tracks.take(50).forEach { track ->
                val saved = track.id in state.offlineIds
                SongRow(
                    title = track.title,
                    artworkUrl = youTubeArtworkUrl(track.id),
                    onClick = { onStreamTrack(track) },
                    subtitle = when {
                        saved -> "Saved offline"
                        track.id in state.savingIds -> "Saving…"
                        else -> null
                    },
                    saved = saved,
                    onMenu = { menuFor = track.id },
                )
            }
        }
        if (tracks.size > 50) {
            Text("Showing the first 50 of ${tracks.size} songs.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        tracks.firstOrNull { it.id == menuFor }?.let { track ->
            val saved = track.id in state.offlineIds
            SongMenuSheet(
                title = track.title,
                artworkUrl = youTubeArtworkUrl(track.id),
                actions = buildList {
                    add(SongMenuAction(if (saved) "Play offline" else "Play", R.drawable.ic_play) { onStreamTrack(track) })
                    add(SongMenuAction("Play next", R.drawable.ic_playlist_play) { onPlayNext(track) })
                    add(SongMenuAction("Add to queue", R.drawable.ic_playlist_add) { onAddToQueue(track) })
                    if (!saved && track.id !in state.savingIds) {
                        add(SongMenuAction("Save offline", R.drawable.ic_download) { onDownloadTrack(track) })
                    }
                },
                onDismiss = { menuFor = null },
            )
        }
        return
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = true, onClick = {}, label = { Text("Playlists") })
        Spacer(Modifier.weight(1f))
        if (state.authorized) {
            IconButton(onClick = onConnect, enabled = !state.loadingPlaylists) {
                Icon(painterResource(R.drawable.ic_sync), contentDescription = "Sync playlists")
            }
        }
        IconButton(onClick = { showLink = !showLink }) {
            Icon(painterResource(R.drawable.ic_add),
                contentDescription = if (showLink) "Hide playlist link" else "Add playlist by link")
        }
        if (state.authorized) {
            IconButton(onClick = { showOptions = !showOptions }) {
                Icon(painterResource(R.drawable.ic_tune),
                    contentDescription = if (showOptions) "Hide library settings" else "Library settings")
            }
        }
    }
    if (!state.authorized) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bring in your playlists", style = MaterialTheme.typography.titleMedium)
                Text("Connect YouTube on Samsung to sync your playlists and save available audio.")
                Button(onClick = onConnect) { Text("Connect YouTube") }
            }
        }
        if (state.savedSnapshot) {
            Text("Showing saved playlist details. Connect to refresh them.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (state.authorized && !state.autoEnabled && state.autoDownloadIds.isNotEmpty()) {
        Text("Automatic downloads are off. Enable them in Library settings.",
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
                enabled = playlistLink.isNotBlank() && !state.loadingPlaylists) {
                Text("Browse link")
            }
            OutlinedButton(onClick = { onDownloadPlaylist(playlistLink) },
                enabled = playlistLink.isNotBlank()) {
                Text("Save link offline")
            }
        }
    }
    if (state.authorized && showOptions) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic sync", style = MaterialTheme.typography.titleMedium)
                        Text("About every 12 hours on unmetered Wi-Fi.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.autoEnabled,
                        onCheckedChange = onAutoEnabled)
                }
                Text("Only download audio you have permission to save. Protected or unavailable songs cannot be downloaded.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirmDisconnect = true }) {
                    Text("Disconnect and erase playlists")
                }
            }
        }
    }
    if (state.loadingPlaylists) Text("Syncing playlists…")
    else if (state.playlists.isEmpty()) Text("No playlists here yet. Connect or sync your account.")
    if (!state.loadingPlaylists) {
        AdaptiveGrid(state.playlists) { playlist, modifier ->
            PlaylistTile(
                title = playlist.title,
                subtitle = "${playlist.itemCount} songs" +
                    if (playlist.id in state.autoDownloadIds) " · Auto-save" else "",
                onClick = {
                    onShowPlaylistsChange(false)
                    onSelectPlaylist(playlist.id)
                },
                modifier = modifier,
                selected = playlist.id == selected,
            )
        }
    }
}

@Composable
private fun Messages(state: YouTubeUiState) {
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}
