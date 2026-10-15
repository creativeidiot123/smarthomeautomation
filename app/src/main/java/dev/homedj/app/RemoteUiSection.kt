package dev.homedj.app

import android.text.format.DateFormat
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Date

@Composable
internal fun RemoteServerSection(running: Boolean, ip: String, code: String, onToggle: (Boolean) -> Unit) {
    var revealCode by remember { mutableStateOf(false) }
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

/** Terminal-style list of Samsung's recent changes from both phones, newest first. */
@Composable
internal fun ActivityFeedCard(entries: List<ActivityEntry>, modifier: Modifier = Modifier) {
    val time = DateFormat.getTimeFormat(LocalContext.current)
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Activity", style = MaterialTheme.typography.titleMedium)
            if (entries.isEmpty()) {
                Text("No changes yet. What either phone changes on Samsung shows up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            entries.take(12).forEach { entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(time.format(Date(entry.at)), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(entry.source, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (entry.source == RemoteActivity.ONEPLUS) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary)
                    Text(entry.text, Modifier.weight(1f), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
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
    onSeek: (Long) -> Unit,
    onBrowsePlaylists: () -> Unit,
    onBrowseDownloads: () -> Unit,
    onBrowseTracks: (String) -> Unit,
    onLoadMore: () -> Unit,
    onLibraryAction: (String, String) -> Unit,
    onDownloadChange: (remove: Boolean, kind: String, id: String) -> Unit,
    onImportLink: (String) -> Unit,
    onBreaks: (MusicBreakSettings) -> Unit,
    onSwitch: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var host by remember { mutableStateOf(state.host) }
    var code by remember { mutableStateOf(state.pairingCode) }
    // Samsung's status carries no song ID, so the player shows placeholder art on this phone.
    val nowPlaying = NowPlayingUi(
        title = state.title,
        artist = state.artist.ifBlank { if (state.casting) "Live audio from this phone" else "Playing on Samsung" },
        artworkUrl = null,
        hasTrack = state.hasTrack,
        playing = state.playing,
        canPlayPause = state.connected && state.hasTrack,
        canPrevious = state.connected && state.hasTrack && !state.casting,
        canNext = state.connected && state.hasTrack,
        shuffle = state.shuffle,
        canShuffle = state.connected && state.queueSize > 1 && !state.casting,
        positionMs = state.positionMs,
        durationMs = if (state.casting) -1L else state.durationMs,
        positionAt = state.positionAt,
        canSeek = state.connected && state.hasTrack && !state.casting,
    )
    val actions = remember(onCommand, onSeek) {
        PlayerActions(
            playPause = { onCommand("playPause", null) },
            next = { onCommand("next", null) },
            previous = { onCommand("previous", null) },
            shuffle = { onCommand("shuffle", null) },
            seek = onSeek,
        )
    }
    MusicShell(
        subtitle = "HomeDJ · OnePlus",
        tab = tab,
        onTab = { tab = it },
        nowPlaying = nowPlaying,
        actions = actions,
        playerExtra = { SamsungVolume(state, onCommand) },
        refreshing = state.browserLoading,
        onRefresh = if (tab == HomeDjPage.HOME.ordinal && state.browserMode == "catalog" && state.connected)
            onBrowsePlaylists else null,
    ) {
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
                    Button(onClick = { tab = HomeDjPage.SETTINGS.ordinal }) { Text("Open settings") }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when (tab) {
            0 -> ControllerLibrary(
                state = state,
                onBrowsePlaylists = onBrowsePlaylists,
                onBrowseDownloads = onBrowseDownloads,
                onBrowseTracks = onBrowseTracks,
                onLoadMore = onLoadMore,
                onLibraryAction = onLibraryAction,
                onDownloadChange = onDownloadChange,
                onImportLink = onImportLink,
            )
            1 -> {
                SectionTitle("Samsung volume")
                SamsungVolume(state, onCommand)
                SectionTitle("Cast OnePlus audio")
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Send compatible app audio to Samsung over Wi-Fi.")
                        FilledTonalButton(
                            onClick = if (castState.active || castState.connecting) onStopCast else onStartCast,
                            enabled = state.connected || castState.active || castState.connecting,
                        ) {
                            IconLabel(R.drawable.ic_cast, when {
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
                if (state.connected) {
                    MusicBreaksCard(state.breaks, state.breakResumesAt, enabled = !changing, onChange = onBreaks)
                    state.activity?.let { ActivityFeedCard(it) }
                }
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
                        Text("Find both values in Samsung → Settings. Both phones must be on the same Wi-Fi.",
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

/** Samsung's media volume in 10% steps, the same commands the controller always sent. */
@Composable
private fun SamsungVolume(state: RemoteUiState, onCommand: (String, Int?) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onCommand("volume", (state.volume - 10).coerceAtLeast(0)) },
            enabled = state.connected && state.volume > 0) {
            Icon(painterResource(R.drawable.ic_volume_down), contentDescription = "Lower Samsung volume")
        }
        Text("${state.volume}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        IconButton(onClick = { onCommand("volume", (state.volume + 10).coerceAtMost(100)) },
            enabled = state.connected && state.volume < 100) {
            Icon(painterResource(R.drawable.ic_volume_up), contentDescription = "Raise Samsung volume")
        }
    }
}
