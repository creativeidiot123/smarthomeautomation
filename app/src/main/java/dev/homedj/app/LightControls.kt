package dev.homedj.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** White presets, warm to cool. */
internal val LIGHT_WHITES = listOf(
    "Candle" to LightColor.White(2200),
    "Warm white" to LightColor.White(2700),
    "Neutral white" to LightColor.White(4000),
    "Daylight" to LightColor.White(5000),
    "Cool white" to LightColor.White(6500),
)

/** Full-saturation colour presets around the hue circle. */
internal val LIGHT_HUES = listOf(
    "Red" to LightColor.Hue(0, 100),
    "Orange" to LightColor.Hue(30, 100),
    "Yellow" to LightColor.Hue(55, 100),
    "Green" to LightColor.Hue(120, 100),
    "Cyan" to LightColor.Hue(185, 100),
    "Blue" to LightColor.Hue(235, 100),
    "Purple" to LightColor.Hue(275, 100),
    "Pink" to LightColor.Hue(320, 100),
)

/** Colour presets offered everywhere a colour is picked. */
internal val LIGHT_COLORS: List<Pair<String, LightColor>> = LIGHT_WHITES + LIGHT_HUES

/** A preset's name, else the nearest named hue ("Pale" when washed out), else the Kelvin value. */
internal fun lightColorName(color: LightColor): String = LIGHT_COLORS.firstOrNull { it.second == color }?.first ?: when (color) {
    is LightColor.White -> "${color.kelvin} K"
    is LightColor.Hue -> {
        val nearest = LIGHT_HUES.minBy { (_, preset) ->
            val gap = abs(preset.degrees - color.degrees)
            minOf(gap, 360 - gap)
        }.first
        if (color.saturationPercent < 40) "Pale ${nearest.lowercase()}" else nearest
    }
}

/** One line under a light's name: "On · 60% · Warm white", "Off", or not yet known. */
internal fun lightStatus(bulb: Bulb): String = when (bulb.on) {
    true -> listOfNotNull("On", bulb.level?.let { "$it%" }, bulb.color?.let(::lightColorName)).joinToString(" · ")
    false -> "Off"
    null -> "Not set by HomeDJ yet"
}

/** Several lights at once; unknown lights never count as on or off. */
internal fun lightsSummary(bulbs: List<Bulb>): String {
    val on = bulbs.count { it.on == true }
    return when {
        bulbs.isEmpty() -> "No lights"
        on == bulbs.size -> "All on"
        bulbs.all { it.on == false } -> "All off"
        else -> "$on of ${bulbs.size} on"
    }
}

/** A group reads as on when any member is on, off only when every member is known off. */
internal fun groupOn(bulbs: List<Bulb>): Boolean? = when {
    bulbs.any { it.on == true } -> true
    bulbs.isNotEmpty() && bulbs.all { it.on == false } -> false
    else -> null
}

/** Shared bar position for several lights: the mean known level of the lit ones. */
internal fun groupLevel(bulbs: List<Bulb>): Int? =
    bulbs.filter { it.on == true }.mapNotNull { it.level }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

/** The colour every lit light shares, or null when they differ or none is known. */
internal fun groupColor(bulbs: List<Bulb>): LightColor? =
    bulbs.filter { it.on == true }.map { it.color }.distinct().singleOrNull()

/** Screen colour for a light colour; whites run from candle orange to daylight blue-white. */
internal fun swatchColor(color: LightColor): Color = when (color) {
    is LightColor.Hue -> Color.hsv(color.degrees.toFloat(), color.saturationPercent / 100f, 1f)
    is LightColor.White -> lerp(Color(0xFFFFB46B), Color(0xFFEEF3FF),
        (color.kelvin - LightsPolicy.KELVIN.first).toFloat() / (LightsPolicy.KELVIN.last - LightsPolicy.KELVIN.first))
}

/** What a lit light looks like on screen; theme primary when its colour is unknown or mixed. */
@Composable
internal fun lightTint(on: Boolean, color: LightColor?): Color =
    if (!on) MaterialTheme.colorScheme.outline else color?.let(::swatchColor) ?: MaterialTheme.colorScheme.primary

/** Icon/text colour that stays readable on [background] (yellow and white lights are pale). */
internal fun contentOn(background: Color): Color = if (background.luminance() > 0.5f) Color(0xDE000000) else Color.White

/**
 * Brightness of a real light: drag or tap anywhere on the bar; each whole percent is sent live as
 * the finger moves (the owner keeps only the newest). The dragged value stays while the finger is
 * down, then the bar shows the owner's confirmed [level] again.
 */
@Composable
internal fun LightLevelBar(level: Int?, lit: Boolean, tint: Color, enabled: Boolean, label: String,
    onLevel: (Int) -> Unit, modifier: Modifier = Modifier, height: Dp = 56.dp) {
    var draft by remember { mutableFloatStateOf((level ?: 100).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    // Last percent sent in this gesture; null at its start, so a tap always sends.
    var sent by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(level, enabled) {
        // A drag cut short by another command never finishes; drop it so the bar follows the owner again.
        if (!enabled) { dragging = false; sent = null }
        else if (!dragging) draft = (level ?: 100).toFloat()
    }
    LevelSlider(draft, onValue = {
        dragging = true
        draft = it
        val percent = it.roundToInt()
        if (percent != sent) { sent = percent; onLevel(percent) }
    }, onDone = {
        dragging = false
        sent = null
    }, fill = if (lit || dragging) tint else MaterialTheme.colorScheme.outlineVariant,
        text = if (lit || dragging) "${draft.roundToInt()}%" else "Off", enabled, label, height, modifier)
}

/** Brightness draft held by an editor (scene, schedule, preferences). */
@Composable
internal fun BrightnessSlider(value: Float, enabled: Boolean, label: String, onValue: (Float) -> Unit, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Brightness", style = MaterialTheme.typography.titleSmall)
        LevelSlider(value, onValue, onDone, MaterialTheme.colorScheme.primary, "${value.roundToInt()}%", enabled, label, 56.dp)
    }
}

/** M3 slider L (56 dp track, 16 dp corners) or XL (96 dp, 28 dp) with the fill edge as handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LevelSlider(value: Float, onValue: (Float) -> Unit, onDone: () -> Unit, fill: Color, text: String,
    enabled: Boolean, label: String, height: Dp, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(if (height >= 96.dp) 28.dp else 16.dp)
    val shown = if (enabled) fill else fill.copy(alpha = 0.38f)
    val ink = contentOn(fill)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Slider(value, onValueChange = { next ->
        // A light tick every 10 %, so the level can be felt without looking.
        if ((next / 10).toInt() != (value / 10).toInt()) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onValue(next)
    }, modifier.fillMaxWidth().semantics { contentDescription = label; stateDescription = text },
        enabled = enabled, onValueChangeFinished = onDone, valueRange = 1f..100f, thumb = {}, track = { state ->
            val fraction = state.coercedValueAsFraction
            Box(Modifier.fillMaxWidth().height(height).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(shown)) {
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(4.dp, height * 0.4f)
                        .background(ink.copy(alpha = 0.7f), CircleShape))
                }
                Icon(painterResource(R.drawable.ic_lightbulb), contentDescription = null,
                    Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(if (height >= 96.dp) 32.dp else 24.dp),
                    tint = if (fraction > 0.2f) ink else muted)
                Text(text, Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
                    color = if (fraction > 0.85f) ink else muted,
                    style = if (height >= 96.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge)
            }
        })
}

/** A slider whose 32 dp track shows the colours it picks; the knob shows the current pick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GradientSlider(value: Float, range: ClosedFloatingPointRange<Float>, colors: List<Color>, knob: Color,
    label: String, valueText: String, enabled: Boolean, onValue: (Float) -> Unit, onDone: () -> Unit) {
    val stops = if (LocalLayoutDirection.current == LayoutDirection.Rtl) colors.reversed() else colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value, onValue, Modifier.fillMaxWidth().semantics { contentDescription = label; stateDescription = valueText },
            enabled = enabled, onValueChangeFinished = onDone, valueRange = range,
            thumb = {
                Box(Modifier.size(36.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .background(Color.White, CircleShape).padding(4.dp).background(knob, CircleShape))
            },
            track = {
                Box(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(if (enabled) stops else stops.map { it.copy(alpha = 0.38f) })))
            })
    }
}

/** 48 dp round swatches; the selected one gets a ring with a gap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorSwatches(selected: LightColor?, enabled: Boolean, canPick: (LightColor) -> Boolean = { true },
    colors: List<Pair<String, LightColor>> = LIGHT_COLORS, onPick: (LightColor) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.forEach { (name, color) ->
            val chosen = color == selected
            val allowed = enabled && canPick(color)
            Box(
                Modifier.size(48.dp)
                    .selectable(chosen, enabled = allowed, role = Role.RadioButton, onClick = { onPick(color) })
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(42.dp)
                    .border(if (chosen) 3.dp else 1.dp,
                        if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .padding(if (chosen) 6.dp else 1.dp)
                    .background(swatchColor(color).copy(alpha = if (allowed) 1f else 0.38f), CircleShape))
            }
        }
    }
}
