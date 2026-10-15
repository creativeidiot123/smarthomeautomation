package dev.homedj.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One song: 48dp art, bold title, small status line and an overflow menu, like a music app list. */
@Composable
internal fun SongRow(
    title: String,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    saved: Boolean = false,
    enabled: Boolean = true,
    onMenu: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(artworkUrl, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (saved || subtitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (saved) {
                        Icon(painterResource(R.drawable.ic_download_done), contentDescription = "Saved offline",
                            modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (onMenu != null) {
            IconButton(onClick = onMenu, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options for $title")
            }
        }
    }
}

/** Lays [items] out in as many equal columns as fit, at least two; not lazy, so it can sit in a scrolling column. */
@Composable
internal fun <T> AdaptiveGrid(items: List<T>, cell: @Composable (item: T, modifier: Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 150.dp).toInt().coerceAtLeast(2)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { cell(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A playlist grid tile: square art, bold title and song count. */
@Composable
internal fun PlaylistTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkUrl: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Artwork(artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f), placeholder = R.drawable.ic_queue_music)
        Spacer(Modifier.size(4.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A playlist's big art, title and count, followed by its main actions. */
@Composable
internal fun PlaylistHeader(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Artwork(artworkUrl, Modifier.size(144.dp), shape = RoundedCornerShape(12.dp),
                placeholder = R.drawable.ic_queue_music)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Bold section heading in a music page. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

/** Icon and label inside a Material button. */
@Composable
internal fun RowScope.IconLabel(@DrawableRes icon: Int, label: String) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
    Text(label, maxLines = 1)
}

/** A tappable shortcut row with a tonal icon tile, for Home actions. */
@Composable
internal fun ShortcutRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).clip(ThumbnailShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal class SongMenuAction(val label: String, @param:DrawableRes val icon: Int, val onClick: () -> Unit)

/** The overflow menu of one song as a bottom sheet: the song on top, then its actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongMenuSheet(title: String, artworkUrl: String?, actions: List<SongMenuAction>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(artworkUrl, Modifier.size(48.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        actions.forEach { action ->
            ListItem(
                headlineContent = { Text(action.label) },
                leadingContent = { Icon(painterResource(action.icon), contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.clickable {
                    onDismiss()
                    action.onClick()
                }.padding(horizontal = 8.dp),
            )
        }
        Spacer(Modifier.navigationBarsPadding().size(16.dp))
    }
}
