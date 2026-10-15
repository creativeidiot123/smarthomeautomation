package dev.homedj.app

import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RemoteServerSection(running: Boolean, ip: String, code: String, onToggle: (Boolean) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Control from OnePlus", style = MaterialTheme.typography.titleLarge)
            Text(if (running) "Paired LAN access is running" else "Remote access is stopped")
            Text("Server IP: " + ip)
            Text("Port: 47188")
            Text("Pairing code (keep private):")
            SelectionContainer { Text(code) }
            Text("On OnePlus, enter this IP and code. Both phones must be on the same private Wi-Fi.")
            Button(onClick = { onToggle(!running) }) {
                Text(if (running) "Stop remote access" else "Start remote access")
            }
        }
    }
}

@Composable
internal fun RemoteControllerScreen(
    changing: Boolean,
    state: RemoteUiState,
    onConnect: (String, String) -> Unit,
    onCommand: (String, Int?) -> Unit,
    onSwitch: () -> Unit,
) {
    var host by remember { mutableStateOf(state.host) }
    var code by remember { mutableStateOf(state.pairingCode) }
    ScreenFrame {
        Text("Controller mode", style = MaterialTheme.typography.titleLarge)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connect to Samsung", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = host,
                    onValueChange = { if (it.length <= 15) host = it },
                    label = { Text("Server Wi-Fi IPv4 address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 64) code = it },
                    label = { Text("Server pairing code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onConnect(host, code) }, enabled = !changing) {
                    Text(if (state.connected) "Reconnect" else "Connect")
                }
                Text(if (state.connected) "Connected over encrypted LAN" else "Not connected")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Playing on Samsung", style = MaterialTheme.typography.labelLarge)
                Text(state.title, style = MaterialTheme.typography.titleLarge)
                Text("Queue: " + state.queueSize + " tracks")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onCommand("previous", null) }, enabled = state.connected && state.hasTrack,
                    ) { Text("Previous") }
                    Button(
                        onClick = { onCommand("playPause", null) }, enabled = state.connected && state.hasTrack,
                    ) { Text(if (state.playing) "Pause" else "Play") }
                    OutlinedButton(
                        onClick = { onCommand("next", null) }, enabled = state.connected && state.hasTrack,
                    ) { Text("Next") }
                }
                OutlinedButton(
                    onClick = { onCommand("shuffle", null) }, enabled = state.connected && state.queueSize > 1,
                ) { Text(if (state.shuffle) "Shuffle: on" else "Shuffle: off") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onCommand("volume", (state.volume - 10).coerceAtLeast(0)) },
                        enabled = state.connected,
                    ) { Text("Volume −") }
                    Text(state.volume.toString() + "%")
                    OutlinedButton(
                        onClick = { onCommand("volume", (state.volume + 10).coerceAtMost(100)) },
                        enabled = state.connected,
                    ) { Text("Volume +") }
                }
            }
        }
        Text("Audio plays only on the Samsung. Open a local audio queue there to begin.")
        TextButton(onClick = onSwitch, enabled = !changing) { Text("Switch to Server mode") }
    }
}
