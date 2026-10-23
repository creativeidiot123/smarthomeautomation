package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One restorable draft. Nothing leaves this editor until Save; Samsung validates again. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun LightsRuleEditor(rule: LightsRule?, snapshot: LightsSnapshot, busy: Boolean, error: String?,
    initialTarget: String?, onDismiss: () -> Unit, onSave: (LightsRule) -> Unit, onDelete: (() -> Unit)?) {
    val id by rememberSaveable { mutableStateOf(rule?.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()) }
    var name by rememberSaveable { mutableStateOf(rule?.name.orEmpty()) }
    var mask by rememberSaveable { mutableIntStateOf(rule?.days?.sumOf { 1 shl (it - 1) } ?: 127) }
    var minute by rememberSaveable { mutableIntStateOf(rule?.minute ?: 19 * 60) }
    var timing by rememberSaveable { mutableStateOf(rule?.time ?: "clock") }
    var offset by rememberSaveable { mutableStateOf((rule?.offset ?: 0).toString()) }
    var power by rememberSaveable { mutableStateOf(rule?.change?.on != false) }
    var level by rememberSaveable { mutableFloatStateOf((rule?.change?.level ?: 100).toFloat()) }
    var hue by rememberSaveable { mutableIntStateOf((rule?.change?.color as? LightColor.Hue)?.degrees ?: -1) }
    var saturation by rememberSaveable { mutableIntStateOf((rule?.change?.color as? LightColor.Hue)?.saturationPercent ?: 100) }
    var kelvin by rememberSaveable { mutableIntStateOf((rule?.change?.color as? LightColor.White)?.kelvin ?: -1) }
    var fade by rememberSaveable { mutableIntStateOf(rule?.change?.fadeSeconds ?: 0) }
    var onlyHome by rememberSaveable { mutableStateOf(rule?.onlyHome ?: false) }
    var target by rememberSaveable { mutableStateOf(initialTarget ?: when {
        rule?.sceneId != null -> "scene:${rule.sceneId}"
        rule?.groupId != null -> "group:${rule.groupId}"
        rule?.bulbs != null -> "selected"
        else -> "all"
    }) }
    var chosen by rememberSaveable { mutableStateOf(rule?.bulbs.orEmpty().joinToString(",")) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val days = (1..7).filter { mask and (1 shl (it - 1)) != 0 }.toSet()
    val groupId = target.takeIf { it.startsWith("group:") }?.removePrefix("group:")
    val sceneId = target.takeIf { it.startsWith("scene:") }?.removePrefix("scene:")
    val bulbId = target.takeIf { it.startsWith("bulb:") }?.removePrefix("bulb:")?.toLongOrNull()
    val targets = when {
        groupId != null -> snapshot.configuration.groups.firstOrNull { it.id == groupId }?.bulbs.orEmpty()
        sceneId != null -> snapshot.scenes.firstOrNull { it.id == sceneId }?.changes?.keys?.toList().orEmpty()
        bulbId != null -> listOf(bulbId)
        target == "selected" -> chosen.split(',').mapNotNull(String::toLongOrNull)
        else -> null
    }
    val color = if (kelvin >= 0) LightColor.White(kelvin) else if (hue >= 0) LightColor.Hue(hue, saturation) else null
    val change = if (power) LightChange(true, level.roundToInt(), color, fade) else LightChange(on = false, fadeSeconds = fade)
    val draft = LightsRule(id, days, minute, change, targets, groupId, name, rule?.enabled ?: true,
        timing, offset.toIntOrNull() ?: 1000, sceneId, onlyHome = onlyHome)
    LightEditorPage(if (onDelete == null) "New light schedule" else "Edit light schedule", onDismiss) {
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name (optional)") },
            enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("When", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("clock" to "Time", "sunrise" to "Sunrise", "sunset" to "Sunset").forEach { (value, label) ->
                FilterChip(timing == value, onClick = { timing = value }, enabled = !busy, label = { Text(label) })
            }
        }
        if (timing == "clock") {
            Button(onClick = { pickingTime = true }, enabled = !busy) { Text("Time · ${scheduleTimeText(minute)}") }
        } else {
            OutlinedTextField(offset, { offset = it.take(4) }, enabled = !busy, singleLine = true,
                label = { Text("Offset minutes (−120 to 120)") }, supportingText = { Text("Negative = before; positive = after.") })
            if (snapshot.location == null) Text("Set solar location in Automation before saving.", color = MaterialTheme.colorScheme.error)
        }
        Text("Repeat on", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(127 to "Every day", 31 to "Weekdays", 96 to "Weekends").forEach { (value, label) ->
                FilterChip(mask == value, onClick = { mask = value }, enabled = !busy, label = { Text(label) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..7).forEach { day -> FilterChip(day in days, onClick = { mask = mask xor (1 shl (day - 1)) }, enabled = !busy,
                label = { Text(DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.getDefault())) }) }
        }
        Text("Then · choose lights or a scene", style = MaterialTheme.typography.titleLarge)
        LightCheckRow("Only if OnePlus confirms Home", onlyHome, !busy) { onlyHome = it }
        if (onlyHome) Text("Requires a Home check-in within 3 minutes and Samsung Remote control. Otherwise this occurrence is skipped.",
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(target == "all", onClick = { target = "all" }, enabled = !busy, label = { Text("All lights") })
            FilterChip(target == "selected" || bulbId != null, onClick = { target = "selected" }, enabled = !busy,
                label = { Text("Choose lights") })
            snapshot.configuration.groups.forEach { group -> FilterChip(groupId == group.id,
                onClick = { target = "group:${group.id}" }, enabled = !busy, label = { Text(group.name) }) }
            snapshot.scenes.forEach { scene -> FilterChip(sceneId == scene.id, onClick = { target = "scene:${scene.id}" },
                enabled = !busy, label = { Text("Scene: ${scene.name}") }) }
        }
        if (target == "selected" || bulbId != null) snapshot.bulbs.forEach { bulb ->
            LightCheckRow(bulb.name, bulb.id in targets.orEmpty(), !busy) { checked ->
                val ids = targets.orEmpty()
                chosen = (if (checked) ids + bulb.id else ids - bulb.id).distinct().joinToString(",")
                target = "selected"
            }
        }
        if (sceneId == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(power, onClick = { power = true }, enabled = !busy, label = { Text("Turn on") })
                FilterChip(!power, onClick = { power = false }, enabled = !busy, label = { Text("Turn off") })
            }
            if (power) {
                BrightnessSlider(level, !busy, "Schedule brightness", { level = it }) {}
                LightColorPicker(color, !busy) { picked -> when (picked) {
                    is LightColor.White -> { kelvin = picked.kelvin; hue = -1 }
                    is LightColor.Hue -> { hue = picked.degrees; saturation = picked.saturationPercent; kelvin = -1 }
                } }
                TextButton(onClick = { hue = -1; kelvin = -1 }, enabled = !busy) { Text("Keep current color") }
            }
            LightFadePicker(fade, !busy) { fade = it }
        } else Text("Applies the scene's saved settings and fades to its fixed members.")
        Text("${lightRuleTime(draft)} → ${lightRuleTarget(draft, snapshot)} → ${lightRuleAction(draft)}")
        Text("Individual overrides stay in force until cleared. New and edited rules start after Save.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onSave(draft) }, enabled = !busy && LightExperience.validRule(snapshot, draft)) { Text("Save schedule") }
        onDelete?.let { TextButton(onClick = { deleting = true }, enabled = !busy) { Text("Delete schedule") } }
    }
    if (pickingTime) {
        val time = rememberTimePickerState(minute / 60, minute % 60, is24Hour = true)
        AlertDialog(onDismissRequest = { pickingTime = false }, title = { Text("Choose time") },
            text = { TimeInput(time) }, confirmButton = {
                TextButton(onClick = { minute = time.hour * 60 + time.minute; pickingTime = false }) { Text("Set time") }
            }, dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } })
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete schedule?") },
        text = { Text("The lights keep their current settings.") },
        confirmButton = { TextButton(onClick = { deleting = false; onDelete?.invoke() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}
