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
) {
    var playlistLink by rememberSaveable { mutableStateOf("") }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("YouTube playlists", style = MaterialTheme.typography.titleLarge)
            Text("Browse account playlists through Google or send a playlist URL to Termux for local audio downloads.")
            OutlinedTextField(
                value = playlistLink,
                onValueChange = { if (it.length <= 2048) playlistLink = it },
                label = { Text("YouTube Music playlist URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = { onDownloadPlaylist(playlistLink) },
                enabled = playlistLink.isNotBlank(),
            ) { Text("Download playlist in Termux") }
            Text(
                "Requires Termux, yt-dlp, FFmpeg and shared-storage access on this phone. " +
                    "Use only for media you have permission to download. " +
                    "After completion, choose the saved files using the local audio picker.",
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
                }
            }
            if (state.loadingTracks) Text("Loading tracks…")
            if (state.selectedPlaylistId != null && !state.loadingTracks) {
                Text(
                    "Tracks (" + state.tracks.size + ")",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.tracks.isEmpty()) Text("No cached/available tracks in this playlist.")
                state.tracks.take(50).forEachIndexed { index, track ->
                    Text((index + 1).toString() + ". " + track.title)
                }
                if (state.tracks.size > 50) {
                    Text("Showing the first 50 tracks of " + state.tracks.size + ".")
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
