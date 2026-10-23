package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun LightPreferencesDialog(name: String, preferences: LightPreferences,
    busy: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (LightPreferences) -> Unit) {
    var power by rememberSaveable { mutableStateOf(preferences.change.on?.toString().orEmpty()) }
    var ownLevel by rememberSaveable { mutableStateOf(preferences.change.level != null) }
    var level by rememberSaveable { mutableFloatStateOf((preferences.change.level ?: 100).toFloat()) }
    var colorIndex by rememberSaveable { mutableIntStateOf(LIGHT_COLORS.indexOfFirst { it.second == preferences.change.color }) }
    var away by rememberSaveable { mutableStateOf(preferences.awayOff?.toString().orEmpty()) }
    var home by rememberSaveable { mutableStateOf(preferences.homeOn?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name preferences") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The latest change wins: power, brightness and colour here override group and all-light settings until this light's group or all lights are changed. Away/home choices stay until cleared. Direct controls and individual schedules also save these choices.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Power choices also apply to presence automation.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PreferenceOptions("Power", power, "On", "Off", { power = it }, enabled = !busy)
                LightCheckRow("Own brightness", ownLevel, enabled = !busy) { ownLevel = it }
                if (ownLevel) BrightnessSlider(level, !busy, "$name preferred brightness", { level = it }) {}
                Text(LIGHT_COLORS.getOrNull(colorIndex)?.first ?: "Colour: use group settings")
                ColorSwatches(LIGHT_COLORS.getOrNull(colorIndex)?.second, !busy) { picked ->
                    val index = LIGHT_COLORS.indexOfFirst { it.second == picked }
                    colorIndex = if (index == colorIndex) -1 else index
                }
                if (colorIndex >= 0) TextButton(onClick = { colorIndex = -1 }, enabled = !busy) { Text("Use group colour") }
                PresencePreference("When away", away, { away = it }, enabled = !busy)
                PresencePreference("When home again", home, { home = it }, enabled = !busy)
                TextButton(onClick = { onSave(LightPreferences()) }, enabled = !busy) { Text("Use group settings for everything") }
            }
        },
        confirmButton = { Button(enabled = !busy, onClick = { onSave(LightPreferences(
            LightChange(power.toBooleanStrictOrNull(), if (ownLevel) level.roundToInt() else null,
                LIGHT_COLORS.getOrNull(colorIndex)?.second), away.toBooleanStrictOrNull(), home.toBooleanStrictOrNull()))
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun PresencePreference(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean = true) {
    PreferenceOptions(label, value, if (label == "When away") "Turn off" else "Restore", "Do nothing", onValue, enabled)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferenceOptions(label: String, value: String, yes: String, no: String, onValue: (String) -> Unit, enabled: Boolean = true) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "Inherit", "true" to yes, "false" to no).forEach { (key, text) ->
                FilterChip(selected = value == key, onClick = { onValue(key) }, enabled = enabled, label = { Text(text) })
            }
        }
    }
}
