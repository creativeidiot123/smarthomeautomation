package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

internal fun lightRuleTime(rule: LightsRule): String = when (rule.time) {
    "sunrise", "sunset" -> rule.time.replaceFirstChar(Char::uppercaseChar) + when {
        rule.offset == 0 -> ""
        rule.offset < 0 -> " · ${-rule.offset} min before"
        else -> " · ${rule.offset} min after"
    }
    else -> scheduleTimeText(rule.minute)
}

internal fun lightRuleTarget(rule: LightsRule, snapshot: LightsSnapshot): String = when {
    rule.sceneId != null -> snapshot.scenes.firstOrNull { it.id == rule.sceneId }?.name ?: "Scene removed"
    rule.groupId != null -> snapshot.configuration.groups.firstOrNull { it.id == rule.groupId }?.name ?: "Group removed"
    rule.bulbs != null -> snapshot.bulbs.filter { it.id in rule.bulbs }.joinToString { it.name }.ifBlank { "Lights removed" }
    else -> "All lights"
}

internal fun lightRuleAction(rule: LightsRule): String = if (rule.sceneId != null) "Apply scene" else
    if (rule.change.on == false) "Turn off" else listOfNotNull("Turn on", rule.change.level?.let { "$it%" },
        when (val color = rule.change.color) {
            is LightColor.White -> "${color.kelvin} K"
            is LightColor.Hue -> "Color ${color.degrees}°"
            null -> null
        }).joinToString(" · ")

internal fun lightZone(snapshot: LightsSnapshot): ZoneId =
    runCatching { ZoneId.of(snapshot.zone) }.getOrDefault(ZoneId.systemDefault())

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LightsScheduleSection(state: LightsUiState, actions: LightsActions,
    target: String? = null, onTargetUsed: () -> Unit = {}) {
    val snapshot = state.snapshot
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var initialTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    var weekly by rememberSaveable { mutableStateOf(false) }
    var day by rememberSaveable { mutableIntStateOf(ZonedDateTime.now(lightZone(snapshot)).dayOfWeek.value) }
    var filter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(target) {
        if (target != null) { initialTarget = target; editing = ""; onTargetUsed() }
    }
    LaunchedEffect(state.busy, waiting) {
        if (waiting && !state.busy) { if (state.actionSucceeded) editing = null; waiting = false }
    }
    Text("Light schedules", style = MaterialTheme.typography.headlineSmall)
    Text("Samsung time · ${snapshot.zone.ifBlank { "check the Samsung" }}. Missed by more than 15 minutes: skipped.",
        style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!weekly, onClick = { weekly = false }, label = { Text("Upcoming") })
        FilterChip(weekly, onClick = { weekly = true }, label = { Text("Week") })
        Button(onClick = { initialTarget = null; editing = "" }, enabled = !state.busy && snapshot.bulbs.isNotEmpty() &&
            snapshot.automation.rules.size < LightsPolicy.MAX_RULES) { Text("Add schedule") }
    }
    OutlinedTextField(filter, { filter = it }, label = { Text("Find a schedule or light") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    if (weekly) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..7).forEach { value -> FilterChip(day == value, onClick = { day = value },
            label = { Text(DayOfWeek.of(value).getDisplayName(TextStyle.SHORT, Locale.getDefault())) }) }
    }
    val now = ZonedDateTime.now(lightZone(snapshot))
    val rules = snapshot.automation.rules.filter { rule -> (!weekly || day in rule.days) &&
        (filter.isBlank() || "${rule.name} ${lightRuleTarget(rule, snapshot)}".contains(filter, ignoreCase = true))
    }.sortedBy { LightsPolicy.next(listOf(it), now, snapshot.location)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE }
    if (rules.isEmpty()) Text("No schedules here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    rules.forEach { rule -> key(rule.id) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.name.ifBlank { lightRuleTime(rule) }, style = MaterialTheme.typography.titleMedium)
                        Text("${lightRuleTime(rule)} · ${lightRuleTarget(rule, snapshot)}")
                    }
                    Switch(rule.enabled, onCheckedChange = { actions.enableLightRule(rule.id, it) }, enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = "${rule.name.ifBlank { "Light schedule" }} enabled" })
                }
                Text(lightRuleAction(rule) + if (rule.change.fadeSeconds > 0 && rule.sceneId == null) " · fade ${rule.change.fadeSeconds} sec" else "",
                    style = MaterialTheme.typography.bodySmall)
                Text(rule.days.sorted().joinToString { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) },
                    style = MaterialTheme.typography.bodySmall)
                val next = LightsPolicy.next(listOf(rule), now, snapshot.location)
                Text(if (!rule.enabled) "Paused" else next?.let { "Next: ${it.format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm"))}" }
                    ?: "No solar event in the next week", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { initialTarget = null; editing = rule.id }, enabled = !state.busy) { Text("Edit") }
                    TextButton(onClick = { initialTarget = null; editing = "copy:${rule.id}" }, enabled = !state.busy &&
                        snapshot.automation.rules.size < LightsPolicy.MAX_RULES) { Text("Duplicate") }
                    TextButton(onClick = { actions.runLightRule(rule.id) }, enabled = !state.busy) { Text("Run now") }
                }
            }
        }
    } }
    if (!state.exactAlarms) {
        Text("Allow alarms on the Samsung for on-time schedules.", color = MaterialTheme.colorScheme.error)
        if (state.mode == HomeDjMode.SERVER) TextButton(onClick = actions.allowExactAlarms) { Text("Allow alarms") }
    }
    editing?.let { id -> key(id, initialTarget) {
        val original = snapshot.automation.rules.firstOrNull { it.id == id.removePrefix("copy:") }
        LightsRuleEditor(if (id.startsWith("copy:")) original?.copy(id = "", name = (original.name + " copy").take(40)) else original,
            snapshot, state.busy, state.message.takeIf { !state.actionSucceeded }, initialTarget,
            onDismiss = { editing = null }, onSave = { waiting = true; actions.saveLightRule(it) },
            onDelete = if (original != null && !id.startsWith("copy:")) ({ waiting = true; actions.removeLightRule(id) }) else null)
    } }
}
