package dev.homedj.app

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Date
import kotlin.math.roundToInt

/**
 * Samsung's random music breaks, shown on both phones. [settings] is null when the Samsung build
 * does not support breaks. Changes are sent only when a slider is released.
 */
@Composable
internal fun MusicBreaksCard(
    settings: MusicBreakSettings?,
    resumesAt: Long?,
    enabled: Boolean,
    onChange: (MusicBreakSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showTiming by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val canEdit = enabled && settings != null
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = settings?.enabled == true,
                    enabled = canEdit,
                    role = Role.Switch,
                    onValueChange = { on -> settings?.let { onChange(it.copy(enabled = on)) } },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Music breaks", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            settings == null -> "Update HomeDJ on Samsung to use music breaks."
                            resumesAt != null -> "On a break · music returns around " +
                                DateFormat.getTimeFormat(context).format(Date(resumesAt)) +
                                ". Press Play to resume now."
                            settings.enabled -> "Songs finish before each quiet break."
                            else -> "Off · music plays without breaks."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings?.enabled == true, onCheckedChange = null, enabled = canEdit)
            }
            if (settings != null) {
                TextButton(onClick = { showTiming = !showTiming }) {
                    Text(if (showTiming) "Hide timing" else "Adjust timing")
                }
            }
            if (settings != null && showTiming) {
                RangeRow("Music plays for", "min", settings.musicMin, settings.musicMax,
                    MusicBreakSettings.MUSIC, canEdit) { min, max ->
                    onChange(settings.copy(musicMin = min, musicMax = max))
                }
                RangeRow("Silence lasts", "min", settings.silenceMin, settings.silenceMax,
                    MusicBreakSettings.SILENCE, canEdit) { min, max ->
                    onChange(settings.copy(silenceMin = min, silenceMax = max))
                }
                RangeRow("Sometimes a long music session", "min", settings.longMusicMin, settings.longMusicMax,
                    MusicBreakSettings.LONG_MUSIC, canEdit) { min, max ->
                    onChange(settings.copy(longMusicMin = min, longMusicMax = max))
                }
                ChanceRow("How often long music sessions happen", settings.longMusicChance, canEdit) {
                    onChange(settings.copy(longMusicChance = it))
                }
                RangeRow("Sometimes a long silence", "min", settings.longSilenceMin, settings.longSilenceMax,
                    MusicBreakSettings.LONG_SILENCE, canEdit) { min, max ->
                    onChange(settings.copy(longSilenceMin = min, longSilenceMax = max))
                }
                ChanceRow("How often long silences happen", settings.longSilenceChance, canEdit) {
                    onChange(settings.copy(longSilenceChance = it))
                }
                RangeRow("Fade in after a break", "s", settings.fadeMin, settings.fadeMax,
                    MusicBreakSettings.FADE_SECONDS, canEdit) { min, max ->
                    onChange(settings.copy(fadeMin = min, fadeMax = max))
                }
            }
        }
    }
}

@Composable
private fun RangeRow(
    label: String,
    unit: String,
    min: Int,
    max: Int,
    bounds: IntRange,
    enabled: Boolean,
    onChange: (Int, Int) -> Unit,
) {
    var dragging by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    val shown = dragging ?: (min.toFloat()..max.toFloat())
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("${shown.start.roundToInt()}–${shown.endInclusive.roundToInt()} $unit",
                style = MaterialTheme.typography.labelLarge)
        }
        RangeSlider(
            value = shown,
            onValueChange = { dragging = it.start.roundToInt().toFloat()..it.endInclusive.roundToInt().toFloat() },
            onValueChangeFinished = {
                dragging?.let { onChange(it.start.roundToInt(), it.endInclusive.roundToInt()) }
                dragging = null
            },
            valueRange = bounds.first.toFloat()..bounds.last.toFloat(),
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun ChanceRow(label: String, percent: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: percent.toFloat()
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${shown.roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onChange(it.roundToInt()) }
                dragging = null
            },
            valueRange = 0f..100f,
            steps = 19,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
