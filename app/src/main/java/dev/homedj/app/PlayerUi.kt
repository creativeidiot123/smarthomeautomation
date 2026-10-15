package dev.homedj.app

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.delay

/** What the mini and full player show: a projection of Samsung's Media3 player, never a second owner. */
internal data class NowPlayingUi(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val hasTrack: Boolean,
    val playing: Boolean,
    val canPlayPause: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val shuffle: Boolean,
    val canShuffle: Boolean,
    /** Position at [positionAt] (`elapsedRealtime`); -1 [durationMs] hides the seek bar. */
    val positionMs: Long = 0L,
    val durationMs: Long = -1L,
    val positionAt: Long = 0L,
    val canSeek: Boolean = false,
)

internal class PlayerActions(
    val playPause: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val shuffle: () -> Unit,
    val seek: (Long) -> Unit,
)

/**
 * Bottom navigation, a mini player above it while a song is loaded, and the full player over
 * everything. [queue] fills the player's queue sheet; [playerExtra] sits under the controls.
 */
@Composable
internal fun MusicShell(
    subtitle: String,
    tab: Int,
    onTab: (Int) -> Unit,
    nowPlaying: NowPlayingUi,
    actions: PlayerActions,
    queue: (@Composable ColumnScope.() -> Unit)? = null,
    playerExtra: @Composable ColumnScope.() -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // A queue that ends closes the player, so the next song does not reopen it on its own.
    LaunchedEffect(nowPlaying.hasTrack) { if (!nowPlaying.hasTrack) expanded = false }
    Box(Modifier.fillMaxSize()) {
        ScreenFrame(
            title = HomeDjPage.entries[tab].label,
            subtitle = subtitle,
            refreshing = refreshing,
            onRefresh = onRefresh,
            bottomBar = {
                Column {
                    AnimatedVisibility(nowPlaying.hasTrack, enter = expandVertically(), exit = shrinkVertically()) {
                        MiniPlayer(nowPlaying, actions, onExpand = { expanded = true })
                    }
                    NavigationBar {
                        HomeDjPage.entries.forEachIndexed { index, page ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { onTab(index) },
                                icon = { Icon(painterResource(page.icon), contentDescription = null) },
                                label = { Text(page.label, maxLines = 1) },
                            )
                        }
                    }
                }
            },
            content = content,
        )
        AnimatedVisibility(
            expanded && nowPlaying.hasTrack,
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = IntOffset.VisibilityThreshold)) { it },
            exit = slideOutVertically { it },
        ) {
            FullPlayer(nowPlaying, actions, onCollapse = { expanded = false }, queue = queue, extra = playerExtra)
        }
    }
}

@Composable
private fun MiniPlayer(nowPlaying: NowPlayingUi, actions: PlayerActions, onExpand: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Open player", onClick = onExpand).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Artwork(nowPlaying.artworkUrl, Modifier.size(50.dp))
            Column(Modifier.weight(1f)) {
                Text(nowPlaying.title, Modifier.basicMarquee(), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (nowPlaying.artist.isNotBlank()) {
                    Text(nowPlaying.artist, Modifier.basicMarquee(), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SmallFilledButton(R.drawable.ic_skip_previous, "Previous", nowPlaying.canPrevious, actions.previous)
                SmallFilledButton(if (nowPlaying.playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (nowPlaying.playing) "Pause" else "Play", nowPlaying.canPlayPause, actions.playPause)
                SmallFilledButton(R.drawable.ic_skip_next, "Next", nowPlaying.canNext, actions.next)
            }
        }
    }
}

@Composable
private fun SmallFilledButton(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(22.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullPlayer(
    nowPlaying: NowPlayingUi,
    actions: PlayerActions,
    onCollapse: () -> Unit,
    queue: (@Composable ColumnScope.() -> Unit)?,
    extra: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onCollapse)
    val collapse by rememberUpdatedState(onCollapse)
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val closeAfter = with(density) { 120.dp.toPx() }
    val queueAfter = with(density) { 48.dp.toPx() }
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize().graphicsLayer { translationY = dragOffset }, color = colors.surfaceContainerLow) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val artSize = min(maxWidth - 40.dp, maxHeight * 0.5f)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Dragging the header or art down closes the player; the rest of the page scrolls.
                Column(Modifier.pointerInput(closeAfter) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffset > closeAfter) collapse()
                            dragOffset = 0f
                        },
                        onDragCancel = { dragOffset = 0f },
                    ) { change, amount ->
                        change.consume()
                        dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                    }
                }) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCollapse) {
                            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = "Close player")
                        }
                        Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.size(48.dp))
                    }
                    Crossfade(nowPlaying.artworkUrl, Modifier.align(Alignment.CenterHorizontally).padding(20.dp),
                        label = "artwork") { url ->
                        Artwork(url, Modifier.size(artSize), large = true, shape = RoundedCornerShape(16.dp))
                    }
                }
                // Swiping up over the song details and controls opens the queue.
                Column(
                    Modifier.padding(horizontal = 12.dp).pointerInput(queue != null) {
                        if (queue == null) return@pointerInput
                        var moved = 0f
                        detectVerticalDragGestures(onDragStart = { moved = 0f }) { change, amount ->
                            moved += amount
                            if (moved < -queueAfter) {
                                change.consume()
                                showQueue = true
                            }
                        }
                    },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(nowPlaying.title, Modifier.basicMarquee(), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                    if (nowPlaying.artist.isNotBlank()) {
                        Text(nowPlaying.artist, Modifier.basicMarquee(), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold, maxLines = 1, color = colors.onSurfaceVariant)
                    }
                    SeekBar(nowPlaying, actions.seek)
                    PlayerControls(nowPlaying, actions)
                    if (queue != null) {
                        FilledTonalButton(onClick = { showQueue = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            IconLabel(R.drawable.ic_queue_music, "Queue")
                        }
                    }
                    extra()
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }
    if (showQueue && queue != null) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).navigationBarsPadding(),
                content = queue)
        }
    }
}

/** A draggable position bar; seeks once on release and holds the new spot until Samsung reports it. */
@Composable
private fun SeekBar(nowPlaying: NowPlayingUi, onSeek: (Long) -> Unit) {
    val duration = nowPlaying.durationMs
    if (duration <= 0) return
    val position by produceState(nowPlaying.positionMs, nowPlaying.positionMs, nowPlaying.positionAt,
        nowPlaying.playing, duration) {
        while (true) {
            value = displayedPosition(nowPlaying.positionMs, nowPlaying.positionAt, SystemClock.elapsedRealtime(),
                nowPlaying.playing, duration)
            if (!nowPlaying.playing) break
            delay(500)
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var pending by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(nowPlaying.positionAt) { pending = null }
    val fraction = dragging ?: pending ?: (position.toFloat() / duration).coerceIn(0f, 1f)
    Column {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let {
                    pending = it
                    onSeek((it * duration).toLong())
                }
                dragging = null
            },
            enabled = nowPlaying.canSeek,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration((fraction * duration).toLong() / 1_000), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatDuration(duration / 1_000), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlayerControls(nowPlaying: NowPlayingUi, actions: PlayerActions) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconToggleButton(checked = nowPlaying.shuffle, onCheckedChange = { actions.shuffle() },
            enabled = nowPlaying.canShuffle) {
            Icon(painterResource(R.drawable.ic_shuffle), contentDescription = "Shuffle",
                tint = if (nowPlaying.shuffle) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton(onClick = actions.previous, enabled = nowPlaying.canPrevious,
            modifier = Modifier.size(56.dp)) {
            Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous",
                modifier = Modifier.size(30.dp))
        }
        FilledIconButton(onClick = actions.playPause, enabled = nowPlaying.canPlayPause,
            modifier = Modifier.size(80.dp), shape = RoundedCornerShape(28.dp)) {
            Icon(painterResource(if (nowPlaying.playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (nowPlaying.playing) "Pause" else "Play", modifier = Modifier.size(40.dp))
        }
        FilledTonalIconButton(onClick = actions.next, enabled = nowPlaying.canNext, modifier = Modifier.size(56.dp)) {
            Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next", modifier = Modifier.size(30.dp))
        }
        // Keeps play/pause centered; Samsung has no repeat mode to put here.
        Spacer(Modifier.size(48.dp))
    }
}
