package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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

    Text("YouTube Music", style = MaterialTheme.typography.titleLarge)
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
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Account connected", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onConnect, enabled = !state.loadingPlaylists) { Text("Sync playlists") }
        }
    }
    if (state.authorized && !state.autoEnabled && state.autoDownloadIds.isNotEmpty()) {
        Text("Automatic downloads are off. Enable them in Library settings.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

    val selected = state.selectedPlaylistId
    if (selected != null && !showPlaylists) {
        TextButton(onClick = { onShowPlaylistsChange(true) }) { Text("← All playlists") }
        Text(state.playlists.firstOrNull { it.id == selected }?.title ?: "Playlist",
            style = MaterialTheme.typography.headlineSmall)
        if (state.loadingTracks) {
            Text("Loading songs…")
        } else {
            Text("${state.tracks.size} songs",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.tracks.isNotEmpty()) {
                FilledTonalButton(onClick = onDownloadSelected) { Text("Save playlist offline") }
            } else {
                Text("No songs are available in this playlist.")
            }
            state.tracks.take(50).forEach { track ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(track.title, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onStreamTrack(track) }) {
                            Text(if (track.id in state.offlineIds) "Play offline" else "Play")
                        }
                        if (track.id !in state.offlineIds) {
                            OutlinedButton(onClick = { onDownloadTrack(track) }) { Text("Save") }
                        } else {
                            Text("Saved", modifier = Modifier.align(Alignment.CenterVertically),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onPlayNext(track) }) { Text("Play next") }
                        TextButton(onClick = { onAddToQueue(track) }) { Text("Add to queue") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (state.tracks.size > 50) {
                Text("Showing the first 50 of ${state.tracks.size} songs.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        Text("Your playlists", style = MaterialTheme.typography.titleMedium)
        if (state.loadingPlaylists) Text("Syncing playlists…")
        else if (state.playlists.isEmpty()) Text("No playlists here yet. Connect or sync your account.")
        if (!state.loadingPlaylists) {
            state.playlists.forEach { playlist ->
                Card(
                    onClick = {
                        onShowPlaylistsChange(false)
                        onSelectPlaylist(playlist.id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (playlist.id == selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                ) {
                    Column(Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(playlist.title, style = MaterialTheme.typography.titleMedium)
                        Text("${playlist.itemCount} songs · Tap to browse",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.authorized) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (state.autoEnabled) "Auto-save on Wi-Fi" else "Include in auto-save",
                                    style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = playlist.id in state.autoDownloadIds,
                                    onCheckedChange = { onToggleAutoPlaylist(playlist.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.authorized) {
        TextButton(onClick = { showLink = !showLink }) {
            Text(if (showLink) "Hide playlist link" else "Browse playlist by link")
        }
        if (showLink) {
            OutlinedTextField(
                value = playlistLink,
                onValueChange = { if (it.length <= 2048) playlistLink = it },
                label = { Text("YouTube playlist URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onImportPlaylist(playlistLink) },
                enabled = playlistLink.isNotBlank() && !state.loadingPlaylists) {
                Text("Browse link")
            }
            OutlinedButton(onClick = { onDownloadPlaylist(playlistLink) },
                enabled = playlistLink.isNotBlank()) {
                Text("Save link offline")
            }
        }
        TextButton(onClick = { showOptions = !showOptions }) {
            Text(if (showOptions) "Hide library settings" else "Library settings")
        }
        if (showOptions) {
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
    }
}
