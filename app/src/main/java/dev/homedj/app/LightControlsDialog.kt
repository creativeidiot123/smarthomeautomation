package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/** Bounded window, scrollable content, visible close action; drafts use primitive saved state. */
@Composable
internal fun LightEditorPage(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().widthIn(max = 640.dp)
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                content()
            }
        }
    }
}

/**
 * Everything for one light, group or all lights in one sheet: power, a hero brightness bar and
 * colour. Changes are sent live while a control moves; [details] adds target-specific rows.
 * [locked]: a permanently-off light, whose power, brightness and colour stay disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LightControlsDialog(name: String, change: LightChange, state: LightsUiState,
    onDismiss: () -> Unit, onChange: (LightChange) -> Unit, reading: LightReading? = null,
    status: String? = null, locked: Boolean = false, details: @Composable ColumnScope.() -> Unit = {}) {
    val enabled = !state.busy && !locked
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall)
                    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Switch(checked = change.on == true, enabled = enabled, onCheckedChange = { onChange(LightChange(on = it)) },
                    modifier = Modifier.semantics { contentDescription = "$name power" })
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                color = if (state.actionSucceeded || state.busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
            if (reading?.dimmable != false) LightLevelBar(change.level, change.on == true, lightTint(change.on == true, change.color),
                enabled, "$name brightness", onLevel = { onChange(LightChange(level = it)) }, height = 96.dp)
            LightColorPicker(change.color, enabled, reading) { onChange(LightChange(color = it)) }
            details()
        }
    }
}

/** The same colour selection is used by direct controls, scene drafts and schedule drafts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LightColorPicker(selected: LightColor?, enabled: Boolean, reading: LightReading? = null, onPick: (LightColor) -> Unit) {
    val whiteOk = reading?.white != false
    val colorOk = reading?.color != false
    if (!whiteOk && !colorOk) {
        Text("This light has no adjustable colour.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var mode by rememberSaveable { mutableStateOf(if (selected is LightColor.Hue && colorOk || !whiteOk) "Color" else "White") }
    var kelvin by rememberSaveable { mutableFloatStateOf(2700f) }
    var hue by rememberSaveable { mutableFloatStateOf(30f) }
    var saturation by rememberSaveable { mutableFloatStateOf(100f) }
    // Last colour sent by the slider being dragged; null between gestures, so a tap always sends.
    var sliding by remember { mutableStateOf<LightColor?>(null) }
    val slide = { color: LightColor -> if (color != sliding) { sliding = color; onPick(color) } }
    // Drafts follow the confirmed colour, but not under the finger or while the owner applies a pick.
    LaunchedEffect(selected, enabled) {
        if (!enabled) sliding = null
        if (!enabled || sliding != null) return@LaunchedEffect
        when (selected) {
            is LightColor.White -> kelvin = selected.kelvin.toFloat()
            is LightColor.Hue -> { hue = selected.degrees.toFloat(); saturation = selected.saturationPercent.toFloat() }
            null -> Unit
        }
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("White" to whiteOk, "Color" to colorOk).forEachIndexed { index, (label, supported) ->
            SegmentedButton(selected = mode == label, onClick = { mode = label }, enabled = supported,
                shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
        }
    }
    if (mode == "White") {
        ColorSwatches(selected, enabled, colors = LIGHT_WHITES, onPick = onPick)
        GradientSlider(kelvin, LightsPolicy.KELVIN.first.toFloat()..LightsPolicy.KELVIN.last.toFloat(),
            listOf(LightColor.White(LightsPolicy.KELVIN.first), LightColor.White(LightsPolicy.KELVIN.last)).map(::swatchColor),
            swatchColor(LightColor.White(kelvin.roundToInt())), "Warm to cool", "${kelvin.roundToInt()} K", enabled,
            { kelvin = it; slide(LightColor.White(kelvin.roundToInt())) }) { sliding = null }
    } else {
        ColorSwatches(selected, enabled, colors = LIGHT_HUES, onPick = onPick)
        val picked = LightColor.Hue(hue.roundToInt(), saturation.roundToInt())
        GradientSlider(hue, 0f..359f, (0..360 step 30).map { Color.hsv(it.toFloat(), 1f, 1f) },
            swatchColor(LightColor.Hue(hue.roundToInt(), 100)), "Hue", lightColorName(picked), enabled,
            { hue = it; slide(LightColor.Hue(hue.roundToInt(), saturation.roundToInt())) }) { sliding = null }
        GradientSlider(saturation, 0f..100f, listOf(Color.White, Color.hsv(hue, 1f, 1f)), swatchColor(picked),
            "Intensity", "${saturation.roundToInt()}%", enabled,
            { saturation = it; slide(LightColor.Hue(hue.roundToInt(), saturation.roundToInt())) }) { sliding = null }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LightFadePicker(seconds: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    Text("Fade · ${if (seconds == 0) "Instant" else "$seconds seconds"}", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Instant", 5 to "5 sec", 30 to "30 sec", 300 to "5 min", 900 to "15 min", 1800 to "30 min").forEach { (value, label) ->
            FilterChip(seconds == value, onClick = { onPick(value) }, enabled = enabled, label = { Text(label) })
        }
    }
    Text("The bulb runs the fade. Support and timing depend on the light.", style = MaterialTheme.typography.bodySmall)
}
