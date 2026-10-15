package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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
    if (upNext.isEmpty()) return
    SectionTitle("Up next")
    Column {
        upNext.forEach { entry ->
            SongRow(entry.title, entry.artworkUrl, onClick = { onJump(entry.index) })
        }
    }
}

@Composable
internal fun ScheduleSection(
    state: ScheduleUiState,
    playlists: List<YouTubePlaylist>,
    onImportPlaylist: (String) -> Unit,
    onSave: (id: String?, start: Int, end: Int, volume: Int, playlistIds: List<String>) -> Unit,
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
    Text("Each slot plays its playlists shuffled every day at its volume. Music you play yourself " +
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
                Text(slot.label, style = MaterialTheme.typography.titleMedium)
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
            onDismiss = { editing = "" },
        )
    }
}

@Composable
private fun SlotEditor(
    slot: ScheduleSlot?,
    state: ScheduleUiState,
    playlists: List<YouTubePlaylist>,
    onImportPlaylist: (String) -> Unit,
    onSave: (id: String?, start: Int, end: Int, volume: Int, playlistIds: List<String>) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var start by rememberSaveable { mutableIntStateOf(slot?.startHour ?: 8) }
    var end by rememberSaveable { mutableIntStateOf(slot?.endHour ?: 9) }
    var volume by rememberSaveable { mutableFloatStateOf((slot?.volume ?: 50).toFloat()) }
    // Comma-joined IDs: playlist IDs never contain commas and a String survives recreation.
    var selected by rememberSaveable { mutableStateOf(slot?.playlistIds.orEmpty().joinToString(",")) }
    var link by rememberSaveable { mutableStateOf("") }
    val chosen = selected.split(',').filter { it.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (slot == null) "New time slot" else "Edit time slot") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HourStepper("Starts", start, 0..23) { start = it; if (end <= it) end = it + 1 }
                HourStepper("Ends", end, 1..24) { end = it; if (start >= it) start = it - 1 }
                Text("Volume ${volume.roundToInt()}%")
                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f, steps = 19,
                    modifier = Modifier.semantics { contentDescription = "Slot volume" })
                Text("Playlists", style = MaterialTheme.typography.titleSmall)
                if (playlists.isEmpty()) Text("No playlists yet. Add a playlist link below.")
                playlists.forEach { playlist ->
                    val checked = playlist.id in chosen
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = {
                            selected = (if (checked) chosen - playlist.id else chosen + playlist.id)
                                .take(ScheduleCodec.MAX_PLAYLISTS).joinToString(",")
                        })
                        Column {
                            Text(playlist.title)
                            Text("${state.cachedSongs[playlist.id] ?: 0} cached songs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(value = link, onValueChange = { if (it.length <= 2048) link = it },
                    label = { Text("Add a YouTube playlist link") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { onImportPlaylist(link); link = "" }, enabled = link.isNotBlank()) {
                    Text("Add link to playlists")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(slot?.id, start, end, volume.roundToInt(), chosen) },
                enabled = chosen.isNotEmpty() && !state.saving) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (slot != null) {
                    TextButton(onClick = { onDelete(slot.id) }, enabled = !state.saving) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun HourStepper(label: String, hour: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label  %02d:00".format(java.util.Locale.ROOT, hour))
        Row {
            TextButton(onClick = { onChange(hour - 1) }, enabled = hour > range.first,
                modifier = Modifier.semantics { contentDescription = "$label one hour earlier" }) { Text("−") }
            TextButton(onClick = { onChange(hour + 1) }, enabled = hour < range.last,
                modifier = Modifier.semantics { contentDescription = "$label one hour later" }) { Text("+") }
        }
    }
}

private const val NEW = "new"
