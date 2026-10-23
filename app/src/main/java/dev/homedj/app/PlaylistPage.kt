package dev.homedj.app

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** One song as a playlist page shows it, from Samsung's own library or the OnePlus's LAN view. */
internal class PageSong(
    val id: String,
    val title: String,
    val details: String,
    val artworkUrl: String?,
    val saved: Boolean,
    val saving: Boolean,
    val artist: String = "",
    val durationSeconds: Int = 0,
    /** Its last save failed after every retry; "Download the rest" tries it again. */
    val failed: Boolean = false,
)

/**
 * A playlist screen: back and search on top, a header card (cover, title, count, download state,
 * options, Play and Shuffle) and the songs. Renders state and emits actions only; the owners
 * decide what a download, cancel or removal means. A null [download] hides download controls;
 * a null [onRefresh] hides Refresh, which shows a spinner while [refreshing].
 */
@Composable
internal fun PlaylistPage(
    title: String,
    countText: String,
    artworkUrl: String?,
    songs: List<PageSong>,
    sortKey: String,
    sortState: PlaylistSortUiState,
    onSort: (playlist: String, PlaylistSort) -> Unit,
    loading: Boolean,
    enabled: Boolean,
    onBack: () -> Unit,
    onPlaySong: (PageSong) -> Unit,
    songMenu: (PageSong) -> List<SongMenuAction>,
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    download: DownloadState? = null,
    onDownload: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onRemoveDownload: () -> Unit = {},
    onAddToSchedule: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
    @DrawableRes placeholder: Int = R.drawable.ic_queue_music,
    below: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    // Keyed by title, so opening another playlist starts without the previous search.
    var searching by rememberSaveable(title) { mutableStateOf(false) }
    var query by rememberSaveable(title) { mutableStateOf("") }
    val sort = sortState.sortFor(sortKey)
    // "" = no dialog, else "cancel" or "remove".
    var confirm by rememberSaveable { mutableStateOf("") }
    val shown = remember(songs, query, sort) { sortedPlaylistSongs(songs, sort, query) }
    val rows = remember(shown) { keyedPlaylistSongs(shown) }
    val listState = rememberSaveable(title, saver = LazyListState.Saver) { LazyListState() }

    LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "header", contentType = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                    if (searching) {
                        OutlinedTextField(value = query, onValueChange = { query = it.take(100) }, singleLine = true,
                            placeholder = { Text("Search songs") }, modifier = Modifier.weight(1f))
                    } else {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh, enabled = enabled && !refreshing && !loading) {
                            if (refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh playlist")
                        }
                    }
                    FilledTonalIconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(painterResource(if (searching) R.drawable.ic_close else R.drawable.ic_search),
                            contentDescription = if (searching) "Close search" else "Search songs")
                    }
                    if (download != null || onAddToSchedule != null) {
                        var optionsOpen by rememberSaveable(title) { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { optionsOpen = true }, enabled = enabled) {
                                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Playlist settings")
                            }
                            DropdownMenu(expanded = optionsOpen, onDismissRequest = { optionsOpen = false }) {
                                if (onAddToSchedule != null) {
                                    MenuItem("Add to schedule", R.drawable.ic_schedule) {
                                        optionsOpen = false
                                        onAddToSchedule()
                                    }
                                }
                                if (download != null && !loading && songs.isNotEmpty()) {
                                    if (download == DownloadState.DOWNLOADING) {
                                        MenuItem("Cancel download", R.drawable.ic_close) { optionsOpen = false; confirm = "cancel" }
                                    } else if (download != DownloadState.DONE) {
                                        MenuItem(if (download == DownloadState.PARTIAL) "Download the rest" else "Download",
                                            R.drawable.ic_download) { optionsOpen = false; onDownload() }
                                    }
                                    MenuItem("Remove download", R.drawable.ic_delete) { optionsOpen = false; confirm = "remove" }
                                }
                            }
                        }
                    }
                }

                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(artworkUrl, Modifier.size(150.dp), shape = RoundedCornerShape(12.dp), placeholder = placeholder)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                AnimatedVisibility(countText.isNotBlank()) {
                                    Text(countText, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (download != null) {
                                    DownloadControls(download, enabled && !loading && songs.isNotEmpty(),
                                        onDownload, onConfirm = { confirm = it })
                                }
                            }
                        }
                        if (onPlay != null && onShuffle != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val ready = enabled && songs.isNotEmpty()
                                FilledTonalButton(onClick = onPlay, enabled = ready, modifier = Modifier.weight(1f)) {
                                    IconLabel(R.drawable.ic_play, "Play")
                                }
                                FilledTonalButton(onClick = onShuffle, enabled = ready, modifier = Modifier.weight(1f)) {
                                    IconLabel(R.drawable.ic_shuffle, "Shuffle")
                                }
                            }
                        }
                    }
                }
                below()

                PlaylistSortMenu(sort, onSelect = { onSort(sortKey, it) }, enabled = sortState.loaded)
                sortState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    loading && songs.isEmpty() -> Text("Loading songs…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    songs.isEmpty() -> Text("No songs here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    shown.isEmpty() -> Text("No matching songs.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(rows, key = { it.first }, contentType = { "song" }) { (_, song) ->
            SongRow(song.title, song.details, song.artworkUrl, onClick = { onPlaySong(song) },
                saved = song.saved, saving = song.saving, failed = song.failed, enabled = enabled, menu = songMenu(song))
        }
        item(key = "footer", contentType = "footer") { footer() }
    }

    when (confirm) {
        "cancel" -> ConfirmDialog("Stop downloading?",
            "Songs that are already downloaded stay. The rest stop downloading.", "Stop",
            onConfirm = onCancelDownload, onDone = { confirm = "" })
        "remove" -> ConfirmDialog("Remove download?",
            "This deletes the downloaded audio of this playlist's songs from Samsung, also for songs " +
                "that are in other playlists. Auto-save for this playlist turns off.", "Remove",
            onConfirm = onRemoveDownload, onDone = { confirm = "" })
    }
}

@Composable
private fun DownloadControls(
    state: DownloadState,
    enabled: Boolean,
    onDownload: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(
            onClick = when (state) {
                DownloadState.DONE -> ({ onConfirm("remove") })
                DownloadState.DOWNLOADING -> ({ onConfirm("cancel") })
                else -> onDownload
            },
            enabled = enabled,
        ) {
            when (state) {
                DownloadState.DOWNLOADING -> CircularProgressIndicator(Modifier.size(22.dp),
                    color = LocalContentColor.current, strokeWidth = 2.dp)
                DownloadState.DONE -> Icon(painterResource(R.drawable.ic_download_done),
                    contentDescription = "Downloaded. Remove download")
                else -> Icon(painterResource(R.drawable.ic_download), contentDescription = "Download playlist")
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, @DrawableRes icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick,
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) })
}

/** A confirmation that only a button closes, so a stray tap outside never deletes anything. */
@Composable
internal fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = {
                onDone()
                onConfirm()
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** Live playlist counts, including queued/running saves; completed audio wins over pending work. */
internal fun songCountText(total: Int, saved: Int, saving: Int = 0): String {
    if (total <= 0) return ""
    val songs = if (total == 1) "1 song" else "$total songs"
    return listOfNotNull(songs, "$saved downloaded".takeIf { saved > 0 },
        "$saving downloading".takeIf { saving > 0 }).joinToString(" • ")
}
