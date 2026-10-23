package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Whether the queue came from the schedule, with a way back to it from a person's own queue. */
@Composable
internal fun ScheduleStatusCard(status: ServerPlaybackUiState, hasSlots: Boolean, onResume: () -> Unit) {
    if (!hasSlots && status.scheduleLabel == null) return
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = status.scheduleLabel
            Text(
                when {
                    label != null -> "Scheduled · $label"
                    status.snapshot.hasTrack -> "Playing your queue · the schedule resumes when it ends"
                    else -> "Schedule waiting for its next slot"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (label == null) {
                FilledTonalButton(onClick = onResume, enabled = status.connected) { Text("Resume schedule") }
            }
            status.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun UpNextSection(upNext: List<QueueEntry>, onJump: (Int) -> Unit) {
    SectionTitle("Up next")
    if (upNext.isEmpty()) {
        Text("Nothing after this song.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column {
        upNext.forEach { entry ->
            SongRow(entry.title, entry.artist, entry.artworkUrl, onClick = { onJump(entry.index) })
        }
    }
}

@Composable
internal fun ScheduleSection(
    state: ScheduleUiState,
    playlists: List<YouTubePlaylist>,
    onImportPlaylist: (String) -> Unit,
    onSave: (id: String?, start: Int, end: Int, volume: Int, playlistIds: List<String>, days: Set<Int>) -> Unit,
    onDelete: (String) -> Unit,
    onAllowExactAlarms: () -> Unit,
) {
    // "" = closed, NEW = adding, otherwise the edited slot's ID.
    var editing by rememberSaveable { mutableStateOf("") }
    // Close only after a newly committed edit, not when the screen is recreated.
    var seenRevision by rememberSaveable { mutableIntStateOf(state.revision) }
    LaunchedEffect(state.revision) {
        if (state.revision != seenRevision) {
            seenRevision = state.revision
            editing = ""
        }
    }

    Text("Schedule", style = MaterialTheme.typography.titleMedium)
    Text("Each slot plays its playlists shuffled on its selected days at its volume. Music you play yourself " +
        "finishes first; the speakers pause when a slot ends with no slot after it.",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!state.exactAlarmsAllowed) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Allow alarms so slots start on time, even while Samsung's screen is off.")
                Button(onClick = onAllowExactAlarms) { Text("Allow alarms") }
            }
        }
    }
    state.slots.forEach { slot ->
        Card(onClick = { editing = slot.id }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val days = slot.days.sorted().joinToString {
                    DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }
                Text("$days · ${slot.label}", style = MaterialTheme.typography.titleMedium)
                Text("Volume ${slot.volume}% · " + slot.playlistIds.joinToString {
                    id -> playlists.firstOrNull { it.id == id }?.title ?: "Missing playlist"
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (slot.playlistIds.sumOf { state.cachedSongs[it] ?: 0 } == 0) {
                    Text("No cached songs yet: open these playlists in Library.",
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (editing.isEmpty()) state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedButton(onClick = { editing = NEW }, enabled = state.slots.size < ScheduleCodec.MAX_SLOTS) {
        Text("Add time slot")
    }
    Text("Keep HomeDJ unrestricted in Samsung battery settings so slots are not delayed.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (editing.isNotEmpty()) {
        SlotEditor(
            slot = state.slots.firstOrNull { it.id == editing },
            state = state,
            playlists = playlists,
            onImportPlaylist = onImportPlaylist,
            onSave = onSave,
            onDelete = onDelete,
            onAllowExactAlarms = onAllowExactAlarms,
            onDismiss = { editing = "" },
        )
    }
}

private const val NEW = "new"
