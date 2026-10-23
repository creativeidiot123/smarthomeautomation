package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** The same draft editor is used by Schedule and the playlist's Add to schedule action. */
@Composable
internal fun SlotEditor(
    slot: ScheduleSlot?,
    state: ScheduleUiState,
    playlists: List<YouTubePlaylist>,
    onImportPlaylist: (String) -> Unit,
    onSave: (String?, Int, Int, Int, List<String>, Set<Int>) -> Unit,
    onDelete: (String) -> Unit,
    onAllowExactAlarms: () -> Unit,
    onDismiss: () -> Unit,
    initialPlaylistIds: List<String> = emptyList(),
) {
    var start by rememberSaveable { mutableStateOf(scheduleTimeText(slot?.startMinutes ?: 480)) }
    var end by rememberSaveable { mutableStateOf(scheduleTimeText(slot?.endMinutes ?: 540)) }
    var volume by rememberSaveable { mutableFloatStateOf((slot?.volume ?: 50).toFloat()) }
    // Bit mask and comma-joined IDs keep the draft restorable using primitive saved state.
    var dayMask by rememberSaveable { mutableIntStateOf(slot?.days?.sumOf { 1 shl (it - 1) } ?: 127) }
    var selected by rememberSaveable {
        mutableStateOf((slot?.playlistIds ?: initialPlaylistIds).joinToString(","))
    }
    var link by rememberSaveable { mutableStateOf("") }
    val chosen = selected.split(',').filter { it.isNotEmpty() }
    val days = (1..7).filter { dayMask and (1 shl (it - 1)) != 0 }.toSet()
    val startMinutes = scheduleTimeMinutes(start)
    val endMinutes = scheduleTimeMinutes(end, allowMidnight = true)
    val timeError = when {
        startMinutes == null || endMinutes == null -> "Use HH:mm, for example 08:30. End of day is 24:00."
        endMinutes <= startMinutes -> "End time must be after start time"
        else -> null
    }

    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(if (slot == null && initialPlaylistIds.isNotEmpty()) "Add to schedule"
            else if (slot == null) "New time slot" else "Edit time slot") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (initialPlaylistIds.isNotEmpty()) {
                    Text(playlists.firstOrNull { it.id == initialPlaylistIds.first() }?.title ?: "Playlist",
                        style = MaterialTheme.typography.titleSmall)
                }
                Text("Repeat on", style = MaterialTheme.typography.titleSmall)
                (1..7).forEach { day ->
                    val name = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = day in days,
                            onCheckedChange = { dayMask = dayMask xor (1 shl (day - 1)) },
                            enabled = !state.saving,
                            modifier = Modifier.semantics { contentDescription = name })
                        Text(name)
                    }
                }
                Text("Times use Samsung's local time (24-hour clock).",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = start, onValueChange = { start = it.take(5) },
                    label = { Text("Starts (HH:mm)") }, singleLine = true,
                    enabled = !state.saving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = end, onValueChange = { end = it.take(5) },
                    label = { Text("Ends (HH:mm)") }, singleLine = true,
                    enabled = !state.saving, modifier = Modifier.fillMaxWidth())
                timeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (days.isEmpty()) Text("Choose at least one day", color = MaterialTheme.colorScheme.error)
                Text("Volume ${volume.roundToInt()}%")
                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f, steps = 19,
                    enabled = !state.saving,
                    modifier = Modifier.semantics { contentDescription = "Slot volume" })
                Text("Playlists", style = MaterialTheme.typography.titleSmall)
                if (playlists.isEmpty()) Text("No playlists yet. Add a playlist link below.")
                playlists.forEach { playlist ->
                    val checked = playlist.id in chosen
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, enabled = !state.saving,
                            onCheckedChange = {
                                selected = (if (checked) chosen - playlist.id else chosen + playlist.id)
                                    .take(ScheduleCodec.MAX_PLAYLISTS).joinToString(",")
                            }, modifier = Modifier.semantics { contentDescription = playlist.title })
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
                    enabled = !state.saving, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { onImportPlaylist(link); link = "" },
                    enabled = link.isNotBlank() && !state.saving) { Text("Add link to playlists") }
                if (!state.exactAlarmsAllowed) {
                    Text("Allow alarms on Samsung so this schedule can start on time.",
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onAllowExactAlarms) { Text("Allow alarms") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (startMinutes != null && endMinutes != null) {
                    onSave(slot?.id, startMinutes, endMinutes, volume.roundToInt(), chosen, days)
                }
            }, enabled = timeError == null && days.isNotEmpty() && chosen.isNotEmpty() && !state.saving) {
                Text(if (state.saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            Row {
                if (slot != null) {
                    TextButton(onClick = { onDelete(slot.id) }, enabled = !state.saving) { Text("Delete") }
                }
                TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Cancel") }
            }
        },
    )
}
