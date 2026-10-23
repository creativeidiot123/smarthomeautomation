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
import androidx.compose.ui.unit.dp
import java.util.Date

@Composable
internal fun RemoteServerSection(
    running: Boolean,
    ip: String,
    pairing: RemotePairingUiState,
    onReplaceCode: () -> Unit,
    onToggle: (Boolean) -> Unit,
    batteryUnrestricted: Boolean,
    onBackgroundAccess: () -> Unit,
) {
    var revealCode by remember(pairing.serverCode) { mutableStateOf(false) }
    val code = pairing.serverCode
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connect your OnePlus", style = MaterialTheme.typography.titleLarge)
            Text(
                if (running) "Remote control is on" else "Remote control is off",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Both phones must use the same private Wi-Fi.")
            Text("Samsung IP: $ip", style = MaterialTheme.typography.bodyLarge)
            Text("Port: ${RemoteChannel.PORT}", style = MaterialTheme.typography.bodyLarge)
            ServerPairingQr(ip, code, pairing.ready && !pairing.busy)
            Text("Pairing code", style = MaterialTheme.typography.labelLarge)
            if (revealCode) {
                SelectionContainer {
                    Text(if (code.length == 26) code.chunked(5).joinToString(" ") else code,
                        fontFamily = FontFamily.Monospace)
                }
            } else {
                Text("Hidden for privacy", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { revealCode = !revealCode }) {
                Text(if (revealCode) "Hide pairing code" else "Show pairing code")
            }
            if (pairing.ready && code.length != 26) {
                ShorterPairingCodeButton(!running && !pairing.busy, onReplaceCode)
            }
            pairing.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Pair once. The code stays saved across app restarts and updates.")
            Button(onClick = { onToggle(!running) }, enabled = pairing.ready && !pairing.busy) {
                Text(if (running) "Turn off remote control" else "Turn on remote control")
            }
            Text("Keep Samsung online overnight", style = MaterialTheme.typography.titleMedium)
            Text(if (batteryUnrestricted) "Android idle access is allowed."
                else "Allow background use so Android can receive commands with the screen off.")
            FilledTonalButton(onClick = onBackgroundAccess) {
                Text(if (batteryUnrestricted) "Open app battery settings" else "Allow background use")
            }
            Text("On Samsung, set HomeDJ battery use to Unrestricted and add it to Never sleeping apps " +
                "under Battery > Background usage limits. Remote access keeps the CPU awake while on; " +
                "keep this server plugged in.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    sortState: PlaylistSortUiState,
    onSort: (playlist: String, PlaylistSort) -> Unit,
    changing: Boolean,
    state: RemoteUiState,
    castState: CastState,
    onStartCast: () -> Unit,
    onStopCast: () -> Unit,
    pairing: RemotePairingUiState,
    onPairHost: (String) -> Unit,
    onPairPort: (String) -> Unit,
    onPairCode: (String) -> Unit,
    onScanPairing: () -> Unit,
    onConnect: () -> Unit,
    onCommand: (String, Int?) -> Unit,
    onSeek: (Long) -> Unit,
    onBrowsePlaylists: () -> Unit,
    onBrowseDownloads: () -> Unit,
    onBrowseTracks: (String) -> Unit,
    onRetryBrowse: () -> Unit,
    onLibraryAction: (String, String) -> Unit,
    onDownloadChange: (remove: Boolean, kind: String, id: String) -> Unit,
    onImportLink: (String) -> Unit,
    onRefreshPlaylist: (String) -> Unit,
    onBreaks: (MusicBreakSettings) -> Unit,
    onSwitch: () -> Unit,
    presenceCard: @Composable () -> Unit,
    lightsPage: @Composable () -> Unit,
    dashboard: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Samsung's status carries no song ID, so the player shows placeholder art on this phone.
    val nowPlaying = NowPlayingUi(
        title = state.title,
        artist = state.artist.ifBlank { if (state.casting) "Live audio from this phone" else "Playing on Samsung" },
        artworkUrl = null,
        hasTrack = state.hasTrack,
        playing = state.playing,
        canPlayPause = state.connected && state.hasTrack,
        canPrevious = state.connected && state.hasTrack,
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
        scrollable = tab != 0 || state.browserMode == "catalog",
        tab = tab,
        onTab = { tab = it },
        nowPlaying = nowPlaying,
        actions = actions,
        playerExtra = { SamsungVolume(state, onCommand) },
        refreshing = state.browserLoading,
        onRefresh = if (tab == HomeDjPage.HOME.ordinal && state.browserMode == "catalog" && state.connected)
            onRetryBrowse else null,
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
        if (tab == 0 && state.browserMode == "catalog") dashboard()
        when (tab) {
            0 -> ControllerLibrary(
                sortState = sortState,
                onSort = onSort,
                state = state,
                onBrowsePlaylists = onBrowsePlaylists,
                onBrowseDownloads = onBrowseDownloads,
                onBrowseTracks = onBrowseTracks,
                onRetryBrowse = onRetryBrowse,
                onLibraryAction = onLibraryAction,
                onDownloadChange = onDownloadChange,
                onImportLink = onImportLink,
                onRefreshPlaylist = onRefreshPlaylist,
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
            2 -> lightsPage()
            3 -> {
                RemotePairingForm(pairing, state.connected && pairing.host.trim() == state.host &&
                    RemotePairingCode.normalize(pairing.code) == state.pairingCode, !changing,
                    onPairHost, onPairPort, onPairCode, onScanPairing, onConnect)
                presenceCard()
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
