package dev.homedj.app

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
    SCHEDULE("Schedule", R.drawable.ic_schedule),
    LIGHTS("Lights", R.drawable.ic_lightbulb),
    SETTINGS("Settings", R.drawable.ic_settings),
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

/** Top title, scrolling content (pull down to refresh when [onRefresh] is set) and an optional bottom bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenFrame(
    title: String,
    subtitle: String = "HomeDJ",
    bottomBar: @Composable () -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Each page keeps its own scroll position.
    val scroll = rememberSaveable(title, saver = ScrollState.Saver) { ScrollState(0) }
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
        val body = @Composable {
            Column(Modifier.fillMaxSize().imePadding().then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)) {
                Column(
                    Modifier.fillMaxWidth().then(if (scrollable) Modifier else Modifier.weight(1f)).widthIn(max = 760.dp)
                        .align(Alignment.CenterHorizontally).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
            }
        }
        if (onRefresh == null) {
            Box(Modifier.fillMaxSize().padding(insets)) { body() }
        } else {
            PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize().padding(insets)) { body() }
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
        Text("You can change this later in Settings.",
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
    sortState: PlaylistSortUiState,
    onSort: (playlist: String, PlaylistSort) -> Unit,
    status: ServerPlaybackUiState,
    youtubeState: YouTubeUiState,
    remoteRunning: Boolean,
    remoteAddress: String,
    remotePairing: RemotePairingUiState,
    onReplacePairingCode: () -> Unit,
    onRemoteToggle: (Boolean) -> Unit,
    batteryUnrestricted: Boolean,
    onBackgroundAccess: () -> Unit,
    changing: Boolean,
    onPickAudio: () -> Unit,
    onPlaySharedDownloads: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onSeek: (Long) -> Unit,
    onConnectYouTube: () -> Unit,
    onDisconnectYouTube: () -> Unit,
    onSelectPlaylist: (String) -> Unit,
    onImportPlaylist: (String) -> Unit,
    onDownloadPlaylist: (String) -> Unit,
    onStreamTrack: (YouTubeTrack) -> Unit,
    onPlayTracks: (List<YouTubeTrack>, Int) -> Unit,
    onDownloadTrack: (YouTubeTrack) -> Unit,
    onDownloadSelected: () -> Unit,
    onRefreshSelected: () -> Unit,
    onCancelSaves: (List<YouTubeTrack>) -> Unit,
    onRemoveDownloads: (List<YouTubeTrack>, String?) -> Unit,
    onAutoEnabled: (Boolean) -> Unit,
    onToggleAutoPlaylist: (String) -> Unit,
    onSwitch: () -> Unit,
    scheduleState: ScheduleUiState,
    onResumeSchedule: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onPlayNext: (YouTubeTrack) -> Unit,
    onAddToQueue: (YouTubeTrack) -> Unit,
    onSaveSlot: (id: String?, start: Int, end: Int, volume: Int, playlistIds: List<String>, days: Set<Int>) -> Unit,
    onDeleteSlot: (String) -> Unit,
    onAllowExactAlarms: () -> Unit,
    breakSettings: MusicBreakSettings,
    breakResumesAt: Long?,
    onBreaks: (MusicBreakSettings) -> Unit,
    activity: List<ActivityEntry>,
    presenceCard: @Composable () -> Unit,
    lightsPage: @Composable () -> Unit,
    dashboard: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Hoisted out of the Home tab so the open playlist survives switching tabs.
    var homeView by rememberSaveable { mutableStateOf(HOME_GRID) }
    var schedulePlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduleRevision by rememberSaveable { mutableIntStateOf(scheduleState.revision) }
    LaunchedEffect(scheduleState.revision) {
        if (scheduleRevision != scheduleState.revision) {
            scheduleRevision = scheduleState.revision
            schedulePlaylist = null
        }
    }
    schedulePlaylist?.let { playlistId ->
        SlotEditor(
            slot = null, state = scheduleState, playlists = youtubeState.playlists,
            initialPlaylistIds = listOf(playlistId),
            onImportPlaylist = onImportPlaylist, onSave = onSaveSlot, onDelete = onDeleteSlot,
            onAllowExactAlarms = onAllowExactAlarms, onDismiss = { schedulePlaylist = null },
        )
    }
    val snapshot = status.snapshot
    val nowPlaying = NowPlayingUi(
        title = snapshot.title,
        artist = snapshot.artist.ifBlank { status.scheduleLabel?.let { "Scheduled · $it" }.orEmpty() },
        artworkUrl = snapshot.artworkUrl,
        hasTrack = snapshot.hasTrack,
        playing = snapshot.isPlaying,
        canPlayPause = status.connected && snapshot.hasTrack,
        canPrevious = status.connected && snapshot.hasPrevious,
        canNext = status.connected && snapshot.hasNext,
        shuffle = snapshot.shuffle,
        canShuffle = status.connected && snapshot.queueSize > 1,
        positionMs = snapshot.positionMs,
        durationMs = snapshot.durationMs,
        positionAt = snapshot.positionAt,
        canSeek = status.connected && snapshot.hasTrack,
    )
    val actions = remember(onPlayPause, onNext, onPrevious, onShuffle, onSeek) {
        PlayerActions(onPlayPause, onNext, onPrevious, onShuffle, onSeek)
    }
    MusicShell(
        subtitle = "HomeDJ · Samsung",
        scrollable = tab != 0 || homeView == HOME_GRID,
        tab = tab,
        onTab = { tab = it },
        nowPlaying = nowPlaying,
        actions = actions,
        queue = { UpNextSection(status.upNext, onJumpTo) },
        refreshing = youtubeState.loadingPlaylists,
        onRefresh = if (tab == HomeDjPage.HOME.ordinal && homeView == HOME_GRID && youtubeState.authorized)
            onConnectYouTube else null,
    ) {
        if (!status.connected) {
            Text("Connecting to the music player…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when (tab) {
            0 -> {
                if (homeView == HOME_GRID) dashboard()
                YouTubeSection(
                    sortState = sortState,
                    onSort = onSort,
                    state = youtubeState,
                    view = homeView,
                    onViewChange = { homeView = it },
                    enabled = status.connected && !changing,
                    onPickAudio = onPickAudio,
                    onSelectPlaylist = onSelectPlaylist,
                    onImportPlaylist = onImportPlaylist,
                    onDownloadPlaylist = onDownloadPlaylist,
                    onStreamTrack = onStreamTrack,
                    onPlayTracks = onPlayTracks,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onDownloadTrack = onDownloadTrack,
                    onDownloadSelected = onDownloadSelected,
                    onRefreshSelected = onRefreshSelected,
                    onCancelSaves = onCancelSaves,
                    onRemoveDownloads = onRemoveDownloads,
                    onToggleAutoPlaylist = onToggleAutoPlaylist,
                    onAddToSchedule = { schedulePlaylist = it },
                )
            }
            1 -> {
                ScheduleStatusCard(status, scheduleState.slots.isNotEmpty(), onResumeSchedule)
                ScheduleSection(
                    state = scheduleState,
                    playlists = youtubeState.playlists,
                    onImportPlaylist = onImportPlaylist,
                    onSave = onSaveSlot,
                    onDelete = onDeleteSlot,
                    onAllowExactAlarms = onAllowExactAlarms,
                )
                MusicBreaksCard(breakSettings, breakResumesAt, enabled = !changing, onChange = onBreaks)
                ActivityFeedCard(activity)
            }
            2 -> lightsPage()
            3 -> {
                YouTubeAccountCard(youtubeState, onConnectYouTube, onDisconnectYouTube, onAutoEnabled)
                RemoteServerSection(
                    running = remoteRunning,
                    ip = remoteAddress,
                    pairing = remotePairing,
                    onReplaceCode = onReplacePairingCode,
                    onToggle = onRemoteToggle,
                    batteryUnrestricted = batteryUnrestricted,
                    onBackgroundAccess = onBackgroundAccess,
                )
                presenceCard()
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
