package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LightsRoutinesSection(state: LightsUiState, actions: LightsActions) {
    var location by rememberSaveable { mutableStateOf(false) }
    var template by rememberSaveable { mutableStateOf<String?>(null) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.busy, waiting) {
        if (waiting && !state.busy) { if (state.actionSucceeded) template = null; waiting = false }
    }
    Text("Automation", style = MaterialTheme.typography.headlineSmall)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Leaving and coming home", style = MaterialTheme.typography.titleMedium)
            Text("Lights and music for leaving and coming home are set in one place: Settings → Home detection on the OnePlus.")
        }
    }
    Text("Start with a routine", style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Evening", "Bedtime", "Wake up").forEach { name ->
            Button(onClick = { template = name }, enabled = !state.busy && state.snapshot.bulbs.isNotEmpty() &&
                state.snapshot.automation.rules.size < LightsPolicy.MAX_RULES) { Text(name) }
        }
    }
    Text("Templates create schedules you can review before saving. Presence automation is controlled separately.",
        style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { location = true }, enabled = !state.busy) {
        Text(if (state.snapshot.location == null) "Set solar location" else "Edit solar location")
    }
    Text("Solar times are approximate and calculated locally. Location is shared only with the paired phone.", style = MaterialTheme.typography.bodySmall)
    Text("Recent runs", style = MaterialTheme.typography.titleLarge)
    if (state.snapshot.history.isEmpty()) Text("Results appear here after a schedule runs.")
    state.snapshot.history.forEach { run ->
        Text("${Instant.ofEpochMilli(run.at).atZone(lightZone(state.snapshot)).format(DateTimeFormatter.ofPattern("d MMM HH:mm"))} · ${run.name}",
            style = MaterialTheme.typography.titleSmall)
        Text(run.result, style = MaterialTheme.typography.bodySmall)
    }
    template?.let { name -> key(name) {
        val rule = when (name) {
            "Evening" -> LightsRule("", (1..7).toSet(), 19 * 60, LightChange(true, 40, LightColor.White(2700), 5),
                name = name, time = "sunset", offset = -30, onlyHome = true)
            "Bedtime" -> LightsRule("", (1..7).toSet(), 23 * 60, LightChange(on = false, fadeSeconds = 30), name = name)
            else -> LightsRule("", (1..5).toSet(), 7 * 60, LightChange(true, 100, LightColor.White(2700), 900), name = name)
        }
        LightsRuleEditor(rule, state.snapshot, state.busy, state.message.takeIf { !state.actionSucceeded }, null,
            onDismiss = { template = null }, onSave = { waiting = true; actions.saveLightRule(it) }, onDelete = null)
    } }
    if (location) LightLocationEditor(state, actions, onDismiss = { location = false })
}

@Composable
private fun LightLocationEditor(state: LightsUiState, actions: LightsActions, onDismiss: () -> Unit) {
    var latitude by rememberSaveable { mutableStateOf(state.snapshot.location?.latitude?.toString().orEmpty()) }
    var longitude by rememberSaveable { mutableStateOf(state.snapshot.location?.longitude?.toString().orEmpty()) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.busy, waiting) {
        if (waiting && !state.busy) { if (state.actionSucceeded) onDismiss(); waiting = false }
    }
    LightEditorPage("Solar location", onDismiss) {
        Text("Enter your home coordinates. No GPS access or online lookup is used. Rules use Samsung's time zone.")
        OutlinedTextField(latitude, { latitude = it.take(24) }, label = { Text("Latitude (−90 to 90)") }, singleLine = true)
        OutlinedTextField(longitude, { longitude = it.take(24) }, label = { Text("Longitude (−180 to 180)") }, singleLine = true)
        state.message?.takeIf { !state.actionSucceeded }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val draft = LightLocation(latitude.toDoubleOrNull() ?: Double.NaN, longitude.toDoubleOrNull() ?: Double.NaN)
        Button(onClick = { waiting = true; actions.saveLocation(draft) }, enabled = !state.busy && draft.valid) { Text("Save location") }
        TextButton(onClick = { waiting = true; actions.saveLocation(null) }, enabled = !state.busy &&
            state.snapshot.automation.rules.none { it.time != "clock" }) { Text("Remove location") }
        Text("Remove solar schedules before removing their location.", style = MaterialTheme.typography.bodySmall)
    }
}
