package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
internal fun LightsGroupsSection(state: LightsUiState, actions: LightsActions, onSchedule: (String) -> Unit) {
    val groups = state.snapshot.configuration.groups
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.busy, waiting) {
        if (waiting && !state.busy) { if (state.actionSucceeded) editing = null; waiting = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Groups", style = MaterialTheme.typography.titleLarge)
        if (groups.isEmpty()) Text("Group lights by room to control them together. Each light belongs to one group.",
            style = MaterialTheme.typography.bodySmall)
        groups.forEach { group -> key(group.id) {
            GroupCard(group, state, actions, onEdit = { editing = group.id }, onSchedule = { onSchedule(group.id) })
        } }
        OutlinedButton(onClick = { editing = "" }, modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy && groups.size < LightsGroups.MAX_GROUPS) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null)
            Text("Create group", Modifier.padding(start = 8.dp))
        }
    }
    editing?.let { id -> key(id) {
        GroupEditor(groups.firstOrNull { it.id == id }, state.snapshot, state.busy,
            error = state.message.takeIf { !state.busy && !state.actionSucceeded },
            onDismiss = { editing = null }, onSave = { waiting = true; actions.saveGroup(it) },
            onDelete = { waiting = true; actions.deleteGroup(id) })
    } }
}

@Composable
private fun GroupCard(group: LightsGroup, state: LightsUiState, actions: LightsActions, onEdit: () -> Unit, onSchedule: () -> Unit) {
    val busy = state.busy
    val members = state.snapshot.bulbs.filter { it.id in group.bulbs }
    val on = groupOn(members)
    val level = groupLevel(members)
    val color = groupColor(members)
    var controls by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LightTile(group.name, lightsSummary(members) + (level?.let { " · $it%" } ?: ""), on, level, color,
        enabled = !busy && members.isNotEmpty(), dimmable = members.any { it.reading?.dimmable != false },
        onPower = { actions.changeGroup(group.id, LightChange(on = it)) },
        onLevel = { actions.changeGroup(group.id, LightChange(level = it)) }, onOpen = { controls = true }) {
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "${group.name} options")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Edit group") }, onClick = { menu = false; onEdit() })
                if (members.isNotEmpty()) DropdownMenuItem(text = { Text("Add schedule") }, onClick = { menu = false; onSchedule() })
            }
        }
    }
    if (controls) LightControlsDialog(group.name, LightChange(on == true, level, color), state,
        onDismiss = { controls = false }, onChange = { actions.changeGroup(group.id, it) },
        status = lightsSummary(members) + " · changing the group resets their own settings") {
        if (state.groupVariationSupported) {
            LightVariationControls(group.variation ?: state.snapshot.variation.all, !busy && members.isNotEmpty(),
                { actions.groupVariation(group.id, it) }, subtitle = if (group.variation != null)
                    "Own variation until all lights' variation changes · resets its lights' own" else "Follows all lights")
            if (group.variation != null) TextButton(onClick = { actions.groupVariation(group.id, null) }, enabled = !busy) {
                Text("Use all-light variation")
            }
        }
    }
}

@Composable
private fun GroupEditor(group: LightsGroup?, snapshot: LightsSnapshot, busy: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: (LightsGroup) -> Unit, onDelete: () -> Unit) {
    val id by rememberSaveable { mutableStateOf(group?.id ?: UUID.randomUUID().toString()) }
    var name by rememberSaveable { mutableStateOf(group?.name.orEmpty()) }
    var selected by rememberSaveable { mutableStateOf(group?.bulbs.orEmpty().joinToString(",")) }
    var away by rememberSaveable { mutableStateOf(group?.preferences?.awayOff?.toString().orEmpty()) }
    var home by rememberSaveable { mutableStateOf(group?.preferences?.homeOn?.toString().orEmpty()) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val members = selected.split(',').mapNotNull(String::toLongOrNull)
    val draft = LightsGroup(id, name, members, (group?.preferences ?: LightPreferences()).copy(
        awayOff = away.toBooleanStrictOrNull(), homeOn = home.toBooleanStrictOrNull()))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (group == null) "Create group" else "Edit group") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(LightsPolicy.MAX_NAME) }, label = { Text("Group name") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Lights", style = MaterialTheme.typography.titleMedium)
                snapshot.bulbs.forEach { bulb ->
                    val other = LightsGroups.group(snapshot.configuration, bulb.id)?.takeIf { it.id != id }
                    LightCheckRow(bulb.name + (other?.let { " · in ${it.name}" } ?: ""), bulb.id in members,
                        enabled = !busy && other == null) { checked ->
                        selected = (if (checked) members + bulb.id else members - bulb.id).joinToString(",")
                    }
                }
                PresencePreference("When away", away, { away = it }, enabled = !busy)
                PresencePreference("When home again", home, { home = it }, enabled = !busy)
                Text("Presence needs Home detection on the OnePlus and Remote control on the Samsung.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onSave(draft) },
            enabled = !busy && LightsGroups.valid(draft, snapshot.configuration, snapshot.bulbs)) { Text("Save") } },
        dismissButton = { Row {
            if (group != null) TextButton(onClick = { deleting = true }) { Text("Delete") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        } },
    )
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete group?") },
        text = { Text("Removes this group's schedules. Its lights and their individual preferences are kept.") },
        confirmButton = { TextButton(onClick = onDelete, enabled = !busy) { Text("Delete group") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

@Composable
internal fun LightCheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.padding(start = 12.dp))
    }
}
