package dev.homedj.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal enum class HomeDjPage(val label: String) {
    PLAYER("Player"),
    LIBRARY("Library"),
    SETUP("Setup"),
}

/** Use the phone's accessible Material You colors without changing the app's media owners. */
@Composable
internal fun HomeDjTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
internal fun ScreenFrame(title: String, content: @Composable ColumnScope.() -> Unit) {
    Scaffold { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("HomeDJ", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.headlineMedium)
            }
            Column(
                Modifier.fillMaxWidth().widthIn(max = 760.dp)
                    .align(Alignment.CenterHorizontally).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun HomeDjTabs(selected: Int, onSelect: (Int) -> Unit) {
    TabRow(selectedTabIndex = selected) {
        HomeDjPage.entries.forEachIndexed { index, page ->
            Tab(selected = selected == index, onClick = { onSelect(index) },
                text = { Text(page.label, maxLines = 1) })
        }
    }
}

@Composable
internal fun RoleLoadingScreen(error: String?) {
    ScreenFrame("Starting") {
        Text(error ?: "Loading device settings…")
    }
}

@Composable
internal fun RolePicker(changing: Boolean, onServer: () -> Unit, onController: () -> Unit) {
    ScreenFrame("Choose this phone's role") {
        Text("You can change this later in Setup.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Play music here", style = MaterialTheme.typography.titleLarge)
                Text("Server · Samsung connected to your speakers. Stores songs and keeps music playing.")
                Button(onClick = onServer, enabled = !changing) { Text("Use as server") }
            }
        }
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Control another phone", style = MaterialTheme.typography.titleLarge)
                Text("Controller · OnePlus on the same Wi-Fi. Browse, play and adjust Samsung's volume.")
                FilledTonalButton(onClick = onController, enabled = !changing) { Text("Use as controller") }
            }
        }
    }
}

@Composable
internal fun ServerScreen(
    status: ServerPlaybackUiState,
    youtubeState: YouTubeUiState,
    remoteRunning: Boolean,
    remoteAddress: String,
    remotePairingCode: String,
    onRemoteToggle: (Boolean) -> Unit,
    changing: Boolean,
    onPickAudio: () -> Unit,
    onPlayDownloads: () -> Unit,
    onPlaySharedDownloads: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onConnectYouTube: () -> Unit,
    onDisconnectYouTube: () -> Unit,
    onSelectPlaylist: (String) -> Unit,
    onImportPlaylist: (String) -> Unit,
    onDownloadPlaylist: (String) -> Unit,
    onStreamTrack: (YouTubeTrack) -> Unit,
    onDownloadTrack: (YouTubeTrack) -> Unit,
    onDownloadSelected: () -> Unit,
    onAutoEnabled: (Boolean) -> Unit,
    onToggleAutoPlaylist: (String) -> Unit,
    onSwitch: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showPlaylists by rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    ScreenFrame("Samsung · Server") {
        Text(
            if (status.connected) "Ready to play on this phone"
            else "Connecting to the music player…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HomeDjTabs(tab) { tab = it }
        when (tab) {
            0 -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("NOW PLAYING", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(status.snapshot.title, style = MaterialTheme.typography.headlineSmall)
                        Text("${status.snapshot.queueSize} songs in queue",
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onPrevious,
                                enabled = status.connected && status.snapshot.hasPrevious) { Text("Previous") }
                            Button(onClick = onPlayPause,
                                enabled = status.connected && status.snapshot.hasTrack) {
                                Text(if (status.snapshot.isPlaying) "Pause" else "Play")
                            }
                            TextButton(onClick = onNext,
                                enabled = status.connected && status.snapshot.hasNext) { Text("Next") }
                        }
                        OutlinedButton(onClick = onShuffle,
                            enabled = status.connected && status.snapshot.queueSize > 1) {
                            Text(if (status.snapshot.shuffle) "Shuffle on" else "Shuffle off")
                        }
                    }
                }
                Text("Play something", style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = onPlayDownloads, enabled = status.connected && !changing,
                    modifier = Modifier.fillMaxWidth()) { Text("Play offline songs") }
                OutlinedButton(onClick = onPickAudio, enabled = status.connected && !changing,
                    modifier = Modifier.fillMaxWidth()) { Text("Choose audio files") }
                Text("Your YouTube playlists and tracks are in Library.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            1 -> YouTubeSection(
                state = youtubeState,
                showPlaylists = showPlaylists,
                onShowPlaylistsChange = { showPlaylists = it },
                onConnect = onConnectYouTube,
                onDisconnect = onDisconnectYouTube,
                onSelectPlaylist = onSelectPlaylist,
                onImportPlaylist = onImportPlaylist,
                onDownloadPlaylist = onDownloadPlaylist,
                onStreamTrack = onStreamTrack,
                onDownloadTrack = onDownloadTrack,
                onDownloadSelected = onDownloadSelected,
                onAutoEnabled = onAutoEnabled,
                onToggleAutoPlaylist = onToggleAutoPlaylist,
            )
            2 -> {
                RemoteServerSection(
                    running = remoteRunning,
                    ip = remoteAddress,
                    code = remotePairingCode,
                    onToggle = onRemoteToggle,
                )
                OutlinedButton(onClick = onPlaySharedDownloads,
                    enabled = status.connected && !changing,
                    modifier = Modifier.fillMaxWidth()) { Text("Play older shared audio") }
                Text("Local music keeps playing without the controller. Remote access is optional.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onSwitch, enabled = !changing) { Text("Change to Controller mode") }
            }
        }
    }
}
