package dev.homedj.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One light or group: power toggle in the light's colour, name and status (tap for every
 * control), and a brightness bar right on the tile. 28 dp card, 12 dp padding, 16 dp bar corners.
 */
@Composable
internal fun LightTile(name: String, status: String, on: Boolean?, level: Int?, color: LightColor?, enabled: Boolean,
    dimmable: Boolean, onPower: (Boolean) -> Unit, onLevel: (Int) -> Unit, onOpen: () -> Unit,
    menu: (@Composable () -> Unit)? = null) {
    val tint = lightTint(on == true, color)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledIconToggleButton(checked = on == true, onCheckedChange = onPower, enabled = enabled,
                    modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp),
                    colors = IconButtonDefaults.filledIconToggleButtonColors(checkedContainerColor = tint,
                        checkedContentColor = contentOn(tint))) {
                    Icon(painterResource(R.drawable.ic_lightbulb), contentDescription = "$name power")
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = "Open $name controls", onClick = onOpen)
                    .padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onOpen) {
                    Icon(painterResource(R.drawable.ic_tune), contentDescription = "$name brightness and colour")
                }
                menu?.invoke()
            }
            if (dimmable) LightLevelBar(level, on == true, tint, enabled, "$name brightness", onLevel)
        }
    }
}

/** Hero card: every light at once. Changing it resets each light's own settings. */
@Composable
internal fun AllLightsCard(state: LightsUiState, onChange: (LightChange) -> Unit,
    onVariation: (LightVariation) -> Unit) {
    var controls by rememberSaveable { mutableStateOf(false) }
    val bulbs = state.snapshot.bulbs
    val enabled = !state.busy
    val on = groupOn(bulbs) == true
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("All lights", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(lightsSummary(bulbs), style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { controls = true }) {
                    Icon(painterResource(R.drawable.ic_tune), contentDescription = "All lights brightness and colour")
                }
            }
            LightLevelBar(groupLevel(bulbs), on, lightTint(on, groupColor(bulbs)), enabled, "All lights brightness",
                onLevel = { onChange(LightChange(level = it)) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onChange(LightChange(on = true)) }, enabled = enabled,
                    modifier = Modifier.weight(1f)) { Text("All on") }
                OutlinedButton(onClick = { onChange(LightChange(on = false)) }, enabled = enabled,
                    modifier = Modifier.weight(1f)) { Text("All off") }
            }
        }
    }
    if (controls) LightControlsDialog("All lights", LightChange(on, groupLevel(bulbs), groupColor(bulbs)), state,
        onDismiss = { controls = false }, onChange = onChange, status = lightsSummary(bulbs) + " · resets each light's own settings") {
        if (state.variationSupported) LightVariationControls(state.snapshot.variation.all, enabled, onVariation,
            subtitle = "Every light feels alive · resets each light's own variation")
    }
}

@Composable
internal fun BulbTile(bulb: Bulb, state: LightsUiState, actions: LightsActions, onSchedule: () -> Unit) {
    val enabled = !state.busy
    val configuration = state.snapshot.configuration
    var controls by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var forgetting by rememberSaveable { mutableStateOf(false) }
    var preferences by rememberSaveable { mutableStateOf(false) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(enabled, waiting) {
        if (waiting && enabled) { if (state.actionSucceeded) preferences = false; waiting = false }
    }
    val override = configuration.individual[bulb.id]?.takeIf { it != LightPreferences() }
    val group = LightsGroups.group(configuration, bulb.id)
    val variation = state.snapshot.variationOf(bulb.id)
    val ownVariation = bulb.id in state.snapshot.variation.individual
    val status = if (bulb.permanentlyOff) "Permanently off" else lightStatus(bulb) + if (variation.enabled && bulb.on == true) " · Alive" else ""
    LightTile(bulb.name, status, bulb.on, bulb.level, bulb.color, enabled && !bulb.permanentlyOff,
        dimmable = bulb.reading?.dimmable != false, onPower = { actions.change(bulb.id, LightChange(on = it)) },
        onLevel = { actions.change(bulb.id, LightChange(level = it)) }, onOpen = { controls = true }) {
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "${bulb.name} options")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Check light") }, onClick = { menu = false; actions.readLight(bulb.id) })
                DropdownMenuItem(text = { Text("Add schedule") }, onClick = { menu = false; onSchedule() })
                DropdownMenuItem(text = { Text("Preferences") }, onClick = { menu = false; preferences = true })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                if (state.setupAvailable) DropdownMenuItem(text = { Text("Forget light") },
                    onClick = { menu = false; forgetting = true })
            }
        }
    }
    if (controls) LightControlsDialog(bulb.name, LightChange(bulb.on, bulb.level, bulb.color), state,
        onDismiss = { controls = false }, onChange = { actions.change(bulb.id, it) }, reading = bulb.reading,
        status = status, locked = bulb.permanentlyOff) {
        if (state.permanentlyOffSupported) Row(Modifier.fillMaxWidth().toggleable(bulb.permanentlyOff, enabled = enabled,
            role = Role.Switch, onValueChange = { actions.permanentlyOff(bulb.id, it) }), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Permanently off", style = MaterialTheme.typography.titleSmall)
                Text("Treated as having no power: scenes, groups, schedules, house rules, presence, variation and the light show leave it alone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = bulb.permanentlyOff, onCheckedChange = null, enabled = enabled)
        }
        Text(if (override != null) {
            group?.let { "Own settings until ${it.name} or all lights change" } ?: "Own settings until all lights change"
        } else group?.let { "Inherits from ${it.name}" } ?: "Uses all-light defaults",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(bulb.reading?.let { "Last checked " + java.time.Instant.ofEpochMilli(it.at).atZone(lightZone(state.snapshot))
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm")) + ". Changes made in other apps show after a check." }
            ?: "Shows HomeDJ's last confirmed settings. Check light reads changes made in other apps.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { actions.readLight(bulb.id) }, enabled = enabled && !bulb.permanentlyOff) { Text("Check light") }
            if (override != null) TextButton(onClick = { actions.preferences(bulb.id, LightPreferences()) },
                enabled = enabled) { Text("Use group settings") }
        }
        if (state.variationSupported) {
            LightVariationControls(variation, enabled, { actions.variation(bulb.id, it) },
                subtitle = when {
                    ownVariation -> group?.let { "Own variation until ${it.name} or all lights change variation" }
                        ?: "Own variation until all lights change variation"
                    group?.variation != null -> "Follows ${group.name}"
                    else -> "Follows all lights"
                })
            if (ownVariation) TextButton(onClick = { actions.variation(bulb.id, null) }, enabled = enabled) {
                Text(if (group?.variation != null) "Use ${group.name} variation" else "Use all-light variation")
            }
        }
    }
    if (preferences) LightPreferencesDialog(bulb.name, configuration.individual[bulb.id] ?: LightPreferences(),
        busy = !enabled, error = state.message.takeIf { enabled && !state.actionSucceeded },
        onDismiss = { preferences = false }, onSave = { waiting = true; actions.preferences(bulb.id, it) })
    if (renaming) RenameDialog(bulb.name, onDismiss = { renaming = false }) {
        renaming = false
        actions.rename(bulb.id, it)
    }
    if (forgetting) AlertDialog(
        onDismissRequest = { forgetting = false },
        title = { Text("Forget ${bulb.name}?") },
        text = { Text("HomeDJ removes itself from the light. AiDot and Google Home keep working. " +
            "To add it again, open Matter pairing mode and use its setup or share code.") },
        confirmButton = {
            TextButton(onClick = { forgetting = false; actions.forget(bulb.id) }) { Text("Forget") }
        },
        dismissButton = { TextButton(onClick = { forgetting = false }) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    val valid = LightsPolicy.validName(name) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename light") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(LightsPolicy.MAX_NAME) },
                label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
