package dev.homedj.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal class SongMenuAction(val label: String, @param:DrawableRes val icon: Int, val onClick: () -> Unit)

/**
 * One song: 56dp art, a title of up to two lines and "artist • length" under it, with a
 * download mark when saved. The ⋮ button and a long press open the same menu.
 */
@Composable
internal fun SongRow(
    title: String,
    details: String,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    saved: Boolean = false,
    saving: Boolean = false,
    failed: Boolean = false,
    enabled: Boolean = true,
    menu: List<SongMenuAction> = emptyList(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClickLabel = if (menu.isEmpty()) null else "Song options",
                onLongClick = if (menu.isEmpty()) null else ({ menuOpen = true }),
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(artworkUrl, Modifier.size(56.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val failedNow = failed && !saved && !saving
            val line = listOfNotNull("Saving…".takeIf { saving && !saved }, "Download failed".takeIf { failedNow },
                details.ifBlank { null }).joinToString(" • ")
            if (saved || line.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (saved) {
                        Icon(painterResource(R.drawable.ic_download_done), contentDescription = "Downloaded",
                            modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (failedNow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (menu.isNotEmpty()) {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options for $title")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            leadingIcon = { Icon(painterResource(action.icon), contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                action.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Lays [items] out in as many equal columns of at least 150dp as fit; not lazy, so it can sit in a scrolling column. */
@Composable
internal fun <T> AdaptiveGrid(items: List<T>, cell: @Composable (item: T, modifier: Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 150.dp).toInt().coerceAtLeast(2)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { cell(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A playlist card: square cover, one-line title and song count on a tonal container. */
@Composable
internal fun PlaylistCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkUrl: String? = null,
    @DrawableRes placeholder: Int = R.drawable.ic_queue_music,
    enabled: Boolean = true,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Artwork(artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(8.dp),
                placeholder = placeholder)
            Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
