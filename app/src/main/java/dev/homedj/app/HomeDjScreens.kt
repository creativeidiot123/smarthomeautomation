package dev.homedj.app

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class HomeDjPage(val label: String, @param:DrawableRes val icon: Int) {
    HOME("Home", R.drawable.ic_home),
    LIBRARY("Library", R.drawable.ic_library_music),
    SETUP("Setup", R.drawable.ic_tune),
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
internal fun ScreenFrame(
    title: String,
    subtitle: String = "HomeDJ",
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            Column(
                Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        },
        bottomBar = bottomBar,
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState()),
        ) {
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
    scheduleState: ScheduleUiState,
    onResumeSchedule: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onPlayNext: (YouTubeTrack) -> Unit,
    onAddToQueue: (YouTubeTrack) -> Unit,
    onSaveSlot: (id: String?, start: Int, end: Int, volume: Int, playlistIds: List<String>) -> Unit,
    onDeleteSlot: (String) -> Unit,
    onAllowExactAlarms: () -> Unit,
    breakSettings: MusicBreakSettings,
    breakResumesAt: Long?,
    onBreaks: (MusicBreakSettings) -> Unit,
    activity: List<ActivityEntry>,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showPlaylists by rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    val snapshot = status.snapshot
    val nowPlaying = NowPlayingUi(
        title = snapshot.title,
        subtitle = status.scheduleLabel?.let { "Scheduled · $it" } ?: "${snapshot.queueSize} songs in queue",
        artworkUrl = snapshot.artworkUrl,
        hasTrack = snapshot.hasTrack,
        playing = snapshot.isPlaying,
        canPlayPause = status.connected && snapshot.hasTrack,
        canPrevious = status.connected && snapshot.hasPrevious,
        canNext = status.connected && snapshot.hasNext,
        shuffle = snapshot.shuffle,
        canShuffle = status.connected && snapshot.queueSize > 1,
    )
    val actions = remember(onPlayPause, onNext, onPrevious, onShuffle) {
        PlayerActions(onPlayPause, onNext, onPrevious, onShuffle)
    }
    MusicShell(
        subtitle = "HomeDJ · Samsung",
        tab = tab,
        onTab = { tab = it },
        nowPlaying = nowPlaying,
        actions = actions,
        playerExtra = { UpNextSection(status.upNext, onJumpTo) },
    ) {
        if (!status.connected) {
            Text("Connecting to the music player…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (tab) {
            0 -> {
                status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                SectionTitle("Play something")
                Column {
                    ShortcutRow(R.drawable.ic_download_done, "Offline songs",
                        "Everything saved on this phone", onPlayDownloads,
                        enabled = status.connected && !changing)
                    ShortcutRow(R.drawable.ic_music_note, "Audio files",
                        "Pick songs from this phone's storage", onPickAudio,
                        enabled = status.connected && !changing)
                    ShortcutRow(R.drawable.ic_library_music, "YouTube playlists",
                        "Browse and save in Library", { tab = HomeDjPage.LIBRARY.ordinal })
                }
                ScheduleStatusCard(status, scheduleState.slots.isNotEmpty(), onResumeSchedule)
                MusicBreaksCard(breakSettings, breakResumesAt, enabled = !changing, onChange = onBreaks)
                ActivityFeedCard(activity)
                ScheduleSection(
                    state = scheduleState,
                    playlists = youtubeState.playlists,
                    onImportPlaylist = onImportPlaylist,
                    onSave = onSaveSlot,
                    onDelete = onDeleteSlot,
                    onAllowExactAlarms = onAllowExactAlarms,
                )
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
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
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
