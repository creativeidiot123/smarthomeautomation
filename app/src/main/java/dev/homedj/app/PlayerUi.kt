package dev.homedj.app

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** What the mini and full player show: a projection of Samsung's Media3 player, never a second owner. */
internal data class NowPlayingUi(
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val hasTrack: Boolean,
    val playing: Boolean,
    val canPlayPause: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val shuffle: Boolean,
    val canShuffle: Boolean,
)

internal class PlayerActions(
    val playPause: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val shuffle: () -> Unit,
)

/** Bottom navigation, a mini player above it while a song is loaded, and the full player over everything. */
@Composable
internal fun MusicShell(
    subtitle: String,
    tab: Int,
    onTab: (Int) -> Unit,
    nowPlaying: NowPlayingUi,
    actions: PlayerActions,
    playerExtra: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // A queue that ends closes the player, so the next song does not reopen it on its own.
    LaunchedEffect(nowPlaying.hasTrack) { if (!nowPlaying.hasTrack) expanded = false }
    Box(Modifier.fillMaxSize()) {
        ScreenFrame(
            title = HomeDjPage.entries[tab].label,
            subtitle = subtitle,
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
            FullPlayer(nowPlaying, actions, onCollapse = { expanded = false }, extra = playerExtra)
        }
    }
}

@Composable
private fun MiniPlayer(nowPlaying: NowPlayingUi, actions: PlayerActions, onExpand: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.clickable(onClickLabel = "Open player", onClick = onExpand)
                .height(64.dp).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(nowPlaying.artworkUrl, Modifier.size(48.dp))
            Column(Modifier.weight(1f)) {
                Text(nowPlaying.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(nowPlaying.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = actions.playPause, enabled = nowPlaying.canPlayPause) {
                PlayPauseIcon(nowPlaying.playing)
            }
            IconButton(onClick = actions.next, enabled = nowPlaying.canNext) {
                Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next")
            }
        }
    }
}

@Composable
private fun FullPlayer(
    nowPlaying: NowPlayingUi,
    actions: PlayerActions,
    onCollapse: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onCollapse)
    val collapse by rememberUpdatedState(onCollapse)
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 120.dp.toPx() }
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize().graphicsLayer { translationY = dragOffset }, color = colors.surface) {
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.surface)))
                .statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Dragging the header or art down closes the player; the rest of the page scrolls.
            Column(
                Modifier.pointerInput(threshold) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffset > threshold) collapse()
                            dragOffset = 0f
                        },
                        onDragCancel = { dragOffset = 0f },
                    ) { change, amount ->
                        change.consume()
                        dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                    }
                },
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCollapse) {
                        Icon(painterResource(R.drawable.ic_expand_more), contentDescription = "Close player")
                    }
                    Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.size(48.dp))
                }
                Artwork(
                    nowPlaying.artworkUrl,
                    Modifier.fillMaxWidth().widthIn(max = 480.dp).aspectRatio(1f).align(Alignment.CenterHorizontally),
                    large = true,
                    shape = RoundedCornerShape(16.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(nowPlaying.title, Modifier.basicMarquee(), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, maxLines = 1)
                Text(nowPlaying.subtitle, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            }
            PlayerControls(nowPlaying, actions)
            extra()
            Spacer(Modifier.size(8.dp))
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
        LargeIconButton(R.drawable.ic_skip_previous, "Previous", nowPlaying.canPrevious, actions.previous)
        FilledIconButton(
            onClick = actions.playPause,
            enabled = nowPlaying.canPlayPause,
            modifier = Modifier.size(72.dp),
            shape = RoundedCornerShape(24.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            PlayPauseIcon(nowPlaying.playing, Modifier.size(36.dp))
        }
        LargeIconButton(R.drawable.ic_skip_next, "Next", nowPlaying.canNext, actions.next)
        // Keeps play/pause centered; Samsung has no repeat mode to put here.
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun LargeIconButton(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier) {
    Icon(
        painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
        contentDescription = if (playing) "Pause" else "Play",
        modifier = modifier,
    )
}
