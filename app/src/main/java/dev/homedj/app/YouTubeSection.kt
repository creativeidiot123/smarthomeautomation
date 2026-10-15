package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun YouTubeSection(
    state: YouTubeUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectPlaylist: (String) -> Unit,
    onImportPlaylist: (String) -> Unit,
    onDownloadPlaylist: (String) -> Unit,
    onStreamTrack: (YouTubeTrack) -> Unit,
    onDownloadTrack: (YouTubeTrack) -> Unit,
    onDownloadSelected: () -> Unit,
    onAutoEnabled: (Boolean) -> Unit,
    onToggleAutoPlaylist: (String) -> Unit,
) {
    var playlistLink by rememberSaveable { mutableStateOf("") }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("YouTube playlists", style = MaterialTheme.typography.titleLarge)
            Text("Browse YouTube playlists, play tracks or download audio directly to this phone.")
            OutlinedTextField(
                value = playlistLink,
                onValueChange = { if (it.length <= 2048) playlistLink = it },
                label = { Text("YouTube Music playlist URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = { onDownloadPlaylist(playlistLink) },
                enabled = state.authorized && playlistLink.isNotBlank(),
            ) { Text("Save linked playlist offline") }
            Text(
                "No Termux is required. HomeDJ stores playable audio in its private storage. " +
                    "Only save music you have permission to download. " +
                    "Restricted or DRM-protected tracks are not supported.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!state.authorized) {
                Button(onClick = onConnect) { Text("Connect YouTube account") }
                if (state.playlists.isNotEmpty()) {
                    Text(
                        "Saved playlist snapshot (may be outdated). Connect to refresh metadata. " +
                            "Songs still need a local audio file for playback.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onConnect) { Text("Sync playlists") }
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect and erase saved playlists") }
                }
                OutlinedButton(onClick = { onAutoEnabled(!state.autoEnabled) }) {
                    Text(if (state.autoEnabled) "Disable automatic sync/downloads"
                        else "Enable 12-hour automatic sync (unmetered Wi-Fi)")
                }
                Text(
                    "Background playlist sync is approximate. Selected playlists use HomeDJ's " +
                        "native download workers when on unmetered Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { onImportPlaylist(playlistLink) },
                    enabled = !state.loadingPlaylists && playlistLink.isNotBlank(),
                ) { Text("Browse playlist link") }
            }
            if (state.loadingPlaylists) Text("Loading playlists…")
            if (!state.loadingPlaylists && state.authorized && state.playlists.isEmpty()) {
                Text("No playlists found for this YouTube channel.")
            }
            if (!state.loadingPlaylists) {
                for (playlist in state.playlists) {
                    OutlinedButton(
                        onClick = { onSelectPlaylist(playlist.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(playlist.title + " (" + playlist.itemCount + ")")
                    }
                    if (state.authorized) {
                        OutlinedButton(onClick = { onToggleAutoPlaylist(playlist.id) }) {
                            Text(if (playlist.id in state.autoDownloadIds) "Stop auto-download: "
                                + playlist.title else "Auto-download: " + playlist.title)
                        }
                    }
                }
            }
            if (state.loadingTracks) Text("Loading tracks…")
            if (state.selectedPlaylistId != null && !state.loadingTracks) {
                Text(
                    "Tracks (" + state.tracks.size + ")",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.tracks.isEmpty()) Text("No cached/available tracks in this playlist.")
                if (state.tracks.isNotEmpty()) {
                    Button(onClick = onDownloadSelected) { Text("Save selected playlist offline") }
                }
                state.tracks.take(50).forEachIndexed { index, track ->
                    Text((index + 1).toString() + ". " + track.title)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onStreamTrack(track) }) {
                            Text(if (track.id in state.offlineIds) "Play offline" else "Play")
                        }
                        OutlinedButton(onClick = { onDownloadTrack(track) }) {
                            Text(if (track.id in state.offlineIds) "Saved" else "Save")
                        }
                    }
                }
                if (state.tracks.size > 50) {
                    Text("Showing the first 50 tracks of " + state.tracks.size + ".")
                }
            }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
