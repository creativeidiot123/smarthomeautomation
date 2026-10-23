package dev.homedj.app

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

internal enum class PlaylistSort(val label: String) {
    ORIGINAL("Playlist order"),
    REVERSE("Reverse playlist order"),
    TITLE_ASC("Title A–Z"),
    TITLE_DESC("Title Z–A"),
    ARTIST_ASC("Artist A–Z"),
    ARTIST_DESC("Artist Z–A"),
    SHORTEST("Shortest first"),
    LONGEST("Longest first"),
    DOWNLOADED("Downloaded first"),
}

/** View-only ordering; equal values keep source order and unknown artist/length sorts last. */
internal fun sortedPlaylistSongs(songs: List<PageSong>, sort: PlaylistSort, query: String = ""): List<PageSong> {
    val matches = if (query.isBlank()) songs else songs.filter {
        it.title.contains(query, ignoreCase = true) || it.details.contains(query, ignoreCase = true)
    }
    return when (sort) {
        PlaylistSort.ORIGINAL -> matches
        PlaylistSort.REVERSE -> matches.reversed()
        PlaylistSort.TITLE_ASC -> matches.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title.trim() })
        PlaylistSort.TITLE_DESC -> matches.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title.trim() })
        PlaylistSort.ARTIST_ASC -> matches.sortedWith(compareBy<PageSong> { it.artist.isBlank() }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist.trim() })
        PlaylistSort.ARTIST_DESC -> matches.sortedWith(compareBy<PageSong> { it.artist.isBlank() }
            .thenByDescending(String.CASE_INSENSITIVE_ORDER) { it.artist.trim() })
        PlaylistSort.SHORTEST -> matches.sortedWith(compareBy<PageSong> { it.durationSeconds <= 0 }
            .thenBy { it.durationSeconds })
        PlaylistSort.LONGEST -> matches.sortedWith(compareBy<PageSong> { it.durationSeconds <= 0 }
            .thenByDescending { it.durationSeconds })
        PlaylistSort.DOWNLOADED -> matches.sortedByDescending { it.saved }
    }
}

@Composable
internal fun PlaylistSortMenu(sort: PlaylistSort, onSelect: (PlaylistSort) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) {
            Text("Sort: ${sort.label}")
            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PlaylistSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    modifier = Modifier.semantics { selected = option == sort },
                    trailingIcon = { if (option == sort) Text("✓") },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
}
