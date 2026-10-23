package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Variation switch and its settings. Stateless: [variation] is the owner's value (or an editor
 * draft) and every choice emits a whole new value. At least one of hue/saturation/brightness stays above 0.
 */
@Composable
internal fun LightVariationControls(variation: LightVariation, enabled: Boolean, onChange: (LightVariation) -> Unit,
    title: String = "Variation", subtitle: String = "Small random changes so the light feels alive") {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().toggleable(variation.enabled, enabled = enabled, role = Role.Switch,
            onValueChange = { onChange(variation.copy(enabled = it).takeIf(LightVariation::valid) ?: LightVariation(enabled = true)) })
            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = variation.enabled, onCheckedChange = null, enabled = enabled)
        }
        if (!variation.enabled) return@Column
        // The last channel above 0 stops at 1 %, so variation that is on always moves something.
        VariationSlider("Hue", variation.hue, enabled, floor = if (variation.saturation + variation.brightness == 0) 1 else 0) {
            onChange(variation.copy(hue = it))
        }
        VariationSlider("Saturation", variation.saturation, enabled,
            floor = if (variation.hue + variation.brightness == 0) 1 else 0) { onChange(variation.copy(saturation = it)) }
        VariationSlider("Brightness", variation.brightness, enabled,
            floor = if (variation.hue + variation.saturation == 0) 1 else 0) { onChange(variation.copy(brightness = it)) }
        VariationSlider("Speed", variation.speed, enabled, floor = 0,
            text = { "${(LightVariations.stepMillis(it) + 500) / 1000} s per change" }) { onChange(variation.copy(speed = it)) }
        Text("How far each one drifts from the light's own setting; 0% keeps it still. Hue shifts colour, or warmth " +
            "on white light. Runs on the Samsung while its remote control is on; a light that is off stays off.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Scene variation subsetting: swap the lights' colours at random every [seconds] (0 off). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LightJuggleControls(seconds: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().toggleable(seconds > 0, enabled = enabled, role = Role.Switch,
            onValueChange = { onChange(if (it) 60 else 0) }).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Juggle colours", style = MaterialTheme.typography.titleSmall)
                Text("The lights swap colours at random, and keep varying around their new colour",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = seconds > 0, onCheckedChange = null, enabled = enabled)
        }
        if (seconds <= 0) return@Column
        Text("Swap every", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (LightJuggles.INTERVALS + seconds).distinct().sorted().forEach { option ->
                FilterChip(seconds == option, onClick = { onChange(option) }, enabled = enabled,
                    label = { Text(if (option % 60 == 0) "${option / 60} min" else "$option s") })
            }
        }
    }
}

/**
 * Continuous 0..100 slider sent once on release. The dragged position stays until the owner's
 * [value] or [enabled] changes (the send finished), so the thumb never springs back meanwhile.
 */
@Composable
private fun VariationSlider(label: String, value: Int, enabled: Boolean, floor: Int,
    text: (Int) -> String = { "$it%" }, onCommit: (Int) -> Unit) {
    var draft by remember { mutableFloatStateOf(value.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value, enabled) {
        if (!enabled) dragging = false else if (!dragging) draft = value.toFloat()
    }
    val shown = text(draft.roundToInt())
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(shown, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(draft, onValueChange = { dragging = true; draft = it.coerceAtLeast(floor.toFloat()) },
            Modifier.fillMaxWidth().semantics { contentDescription = label; stateDescription = shown },
            enabled = enabled, valueRange = 0f..100f, onValueChangeFinished = {
                dragging = false
                draft.roundToInt().let { if (it != value) onCommit(it) }
            })
    }
}
