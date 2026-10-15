package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun RemoteServerSection(running: Boolean, ip: String, code: String, onToggle: (Boolean) -> Unit) {
    var revealCode by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connect your OnePlus", style = MaterialTheme.typography.titleLarge)
            Text(
                if (running) "Remote control is on" else "Remote control is off",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Both phones must use the same private Wi-Fi.")
            Text("Samsung IP: $ip", style = MaterialTheme.typography.bodyLarge)
            Text("Port: 47188", style = MaterialTheme.typography.bodySmall)
            Text("Pairing code", style = MaterialTheme.typography.labelLarge)
            if (revealCode) {
                SelectionContainer { Text(code) }
            } else {
                Text("Hidden for privacy", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { revealCode = !revealCode }) {
                Text(if (revealCode) "Hide pairing code" else "Show pairing code")
            }
            Button(onClick = { onToggle(!running) }) {
                Text(if (running) "Turn off remote control" else "Turn on remote control")
            }
        }
    }
}

@Composable
internal fun RemoteControllerScreen(
    changing: Boolean,
    state: RemoteUiState,
    castState: CastState,
    onStartCast: () -> Unit,
    onStopCast: () -> Unit,
    onConnect: (String, String) -> Unit,
    onCommand: (String, Int?) -> Unit,
    onBrowsePlaylists: () -> Unit,
    onBrowseDownloads: () -> Unit,
    onBrowseTracks: (String) -> Unit,
    onLoadMore: () -> Unit,
    onLibraryAction: (String, String) -> Unit,
    onSwitch: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var host by remember { mutableStateOf(state.host) }
    var code by remember { mutableStateOf(state.pairingCode) }
    ScreenFrame("OnePlus · Controller") {
        Text(
            if (state.connected) "Connected to Samsung over private Wi-Fi"
            else "Not connected to Samsung",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.connected) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connect to hear music", style = MaterialTheme.typography.titleMedium)
                    Text("Start remote control on Samsung, then enter its Wi-Fi address and pairing code.")
                    Button(onClick = { tab = 2 }) { Text("Open setup") }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HomeDjTabs(tab) { tab = it }
        when (tab) {
            0 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("PLAYING ON SAMSUNG", style = MaterialTheme.typography.labelLarge)
                        Text(state.title, style = MaterialTheme.typography.headlineSmall)
                        Text("${state.queueSize} songs in queue")
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onCommand("previous", null) },
                                enabled = state.connected && state.hasTrack && !state.casting) {
                                Text("Previous")
                            }
                            Button(onClick = { onCommand("playPause", null) },
                                enabled = state.connected && state.hasTrack) {
                                Text(if (state.playing) "Pause" else "Play")
                            }
                            TextButton(onClick = { onCommand("next", null) },
                                enabled = state.connected && state.hasTrack) { Text("Next") }
                        }
                        OutlinedButton(onClick = { onCommand("shuffle", null) },
                            enabled = state.connected && state.queueSize > 1 && !state.casting) {
                            Text(if (state.shuffle) "Shuffle on" else "Shuffle off")
                        }
                    }
                }
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Samsung volume", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = {
                                onCommand("volume", (state.volume - 10).coerceAtLeast(0))
                            }, enabled = state.connected && state.volume > 0) { Text("−10") }
                            Text("${state.volume}%", style = MaterialTheme.typography.titleLarge)
                            OutlinedButton(onClick = {
                                onCommand("volume", (state.volume + 10).coerceAtMost(100))
                            }, enabled = state.connected && state.volume < 100) { Text("+10") }
                        }
                    }
                }
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cast OnePlus audio", style = MaterialTheme.typography.titleMedium)
                        Text("Send compatible app audio to Samsung over Wi-Fi.")
                        FilledTonalButton(
                            onClick = if (castState.active || castState.connecting) onStopCast else onStartCast,
                            enabled = state.connected || castState.active || castState.connecting,
                        ) {
                            Text(when {
                                castState.active -> "Stop casting"
                                castState.connecting -> "Cancel casting"
                                else -> "Start casting"
                            })
                        }
                        Text(when {
                            castState.active -> "Casting to Samsung"
                            castState.connecting -> "Connecting live audio…"
                            else -> "Not casting"
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        castState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text("Some apps block capture. Calls and protected media cannot be cast.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            1 -> {
                Text("Music on Samsung", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onBrowsePlaylists, enabled = state.connected) {
                        Text("Playlists")
                    }
                    OutlinedButton(onClick = onBrowseDownloads, enabled = state.connected) {
                        Text("Offline songs")
                    }
                }
                if (state.browserLoading) Text("Loading music…")
                when (state.browserMode) {
                    "catalog" -> {
                        if (!state.browserLoading && state.playlists.isEmpty()) {
                            Text("No saved playlists yet. Connect and sync YouTube on Samsung first.")
                        }
                        state.playlists.forEach { playlist ->
                            Card(onClick = { onBrowseTracks(playlist.id) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp)) {
                                Column(Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(playlist.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${playlist.count} songs · Tap to browse",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    else -> {
                        if (state.browserMode == "tracks") {
                            TextButton(onClick = onBrowsePlaylists, enabled = state.connected) {
                                Text("← All playlists")
                            }
                            Text(state.selectedPlaylistTitle,
                                style = MaterialTheme.typography.headlineSmall)
                            state.selectedPlaylistId?.let { playlistId ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onLibraryAction("playPlaylist", playlistId) },
                                        enabled = state.connected) { Text("Play playlist") }
                                    OutlinedButton(onClick = { onLibraryAction("savePlaylist", playlistId) },
                                        enabled = state.connected) { Text("Save offline") }
                                }
                            }
                        } else {
                            Text("Offline songs", style = MaterialTheme.typography.titleMedium)
                        }
                        if (!state.browserLoading && state.songs.isEmpty()) {
                            Text("No songs found here.")
                        }
                        state.songs.forEach { song ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(song.title, style = MaterialTheme.typography.bodyLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onLibraryAction("playTrack", song.id) },
                                        enabled = state.connected) {
                                        Text(if (song.saved) "Play offline" else "Play")
                                    }
                                    if (!song.saved) {
                                        OutlinedButton(onClick = { onLibraryAction("saveTrack", song.id) },
                                            enabled = state.connected) { Text("Save") }
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                val loaded = if (state.browserMode == "catalog") state.playlists.size else state.songs.size
                if (loaded < state.browserTotal) {
                    OutlinedButton(onClick = onLoadMore,
                        enabled = state.connected && !state.browserLoading) {
                        Text("Show more (${loaded}/${state.browserTotal})")
                    }
                }
                state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Text("Online songs need Samsung's internet connection.",
                    style = MaterialTheme.typography.bodySmall)
            }
            2 -> {
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Connect to Samsung", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = host,
                            onValueChange = { if (it.length <= 15) host = it },
                            label = { Text("Samsung Wi-Fi IP") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { if (it.length <= 64) code = it },
                            label = { Text("Pairing code") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { onConnect(host, code) },
                            enabled = !changing && host.isNotBlank() && code.isNotBlank()) {
                            Text(if (state.connected) "Reconnect" else "Connect")
                        }
                        Text("Find both values in Samsung → Setup. Both phones must be on the same Wi-Fi.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = onSwitch, enabled = !changing) {
                    Text("Change to Server mode")
                }
            }
        }
    }
}
