package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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

/** Light show switch, style and speaker sync. Stateless: every choice emits the whole new setting. */
@Composable
internal fun LightDanceCard(state: LightsUiState, onChange: (LightDance) -> Unit) {
    val dance = state.snapshot.dance
    val enabled = !state.busy && state.danceSupported
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().toggleable(dance.enabled, enabled = enabled, role = Role.Switch,
                onValueChange = { onChange(dance.copy(enabled = it)) }), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dance to the music", style = MaterialTheme.typography.titleLarge)
                    Text(when {
                        !state.danceSupported -> "Update HomeDJ on the Samsung to make the lights dance."
                        dance.enabled && state.danceStatus.isNotEmpty() -> state.danceStatus
                        else -> "Lights that are on move with the Samsung's music, in the song's own colours"
                    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = dance.enabled, onCheckedChange = null, enabled = enabled)
            }
            if (!dance.enabled) return@Column
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DANCE_STYLES.forEachIndexed { index, (style, label) ->
                    SegmentedButton(selected = dance.style == style, enabled = enabled,
                        onClick = { if (dance.style != style) onChange(dance.copy(style = style)) },
                        shape = SegmentedButtonDefaults.itemShape(index, DANCE_STYLES.size)) { Text(label) }
                }
            }
            Text(if (dance.style == LightDanceStyle.PARTY) "Punchy hits on every beat, chasing from light to light"
                else "Colours breathe and flow with the beat", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SyncSlider(dance.offsetMillis, enabled) { onChange(dance.copy(offsetMillis = it)) }
        }
    }
}

private val DANCE_STYLES = listOf(LightDanceStyle.PARTY to "Party", LightDanceStyle.CHILL to "Chill")

/** Sent once on release; the dragged value stays until the owner's value or [enabled] changes. */
@Composable
private fun SyncSlider(value: Int, enabled: Boolean, onCommit: (Int) -> Unit) {
    var draft by remember { mutableFloatStateOf(value.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value, enabled) {
        if (!enabled) dragging = false else if (!dragging) draft = value.toFloat()
    }
    val shown = (draft / 10).roundToInt() * 10
    val text = when {
        shown == 0 -> "In time"
        shown > 0 -> "$shown ms later"
        else -> "${-shown} ms earlier"
    }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Light timing", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(draft, onValueChange = { dragging = true; draft = it },
            Modifier.fillMaxWidth().semantics { contentDescription = "Light timing"; stateDescription = text },
            enabled = enabled, valueRange = -LightDances.MAX_OFFSET.toFloat()..LightDances.MAX_OFFSET.toFloat(),
            onValueChangeFinished = {
                dragging = false
                if (shown != value) onCommit(shown)
            })
        Text("If the lights flash before the beat (common with Bluetooth speakers), slide later.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
