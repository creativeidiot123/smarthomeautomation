package dev.homedj.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** "Set each light" drafts, kept in `rememberSaveable` as the scene change JSON (id plus change fields). */
internal object SceneEachLight {
    fun encode(changes: Map<Long, LightChange>): String = JSONArray().also { array ->
        changes.forEach { (id, change) -> array.put(LightsCodec.putChange(JSONObject().put("id", id), change)) }
    }.toString()

    fun decode(text: String): Map<Long, LightChange> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).associate { index ->
            array.getJSONObject(index).let { it.getLong("id") to LightsCodec.change(it).copy(fadeSeconds = 0) }
        }
    }.getOrDefault(emptyMap())

    /** Starting point: what each light would get right now, or [fallback] when that sets nothing. */
    fun seed(members: List<Long>, shown: Map<Long, LightChange>, fallback: LightChange): Map<Long, LightChange> =
        members.associateWith { id -> (shown[id]?.takeUnless { it.isEmpty } ?: fallback).copy(fadeSeconds = 0) }
}

/** One light's own scene settings: a summary card that expands into power, brightness and colour. */
@Composable
internal fun SceneEachLightCard(name: String, change: LightChange, reading: LightReading?, expanded: Boolean,
    enabled: Boolean, onExpand: () -> Unit, onChange: (LightChange) -> Unit) {
    val lit = change.on != false
    OutlinedCard(onClick = onExpand, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(24.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .background(if (lit) change.color?.let(::swatchColor) ?: MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant, CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(if (!lit) "Off" else listOfNotNull(change.level?.let { "$it%" }, change.color?.let(::lightColorName))
                        .joinToString(" · ").ifBlank { "On" }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "Done" else "Edit", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (!expanded) return@Column
            LightCheckRow("On", lit, enabled) { onChange(change.copy(on = it, level = change.level ?: 40)) }
            if (lit) {
                BrightnessSlider((change.level ?: 40).toFloat(), enabled, "$name brightness",
                    { onChange(change.copy(on = true, level = it.roundToInt().coerceIn(1, 100))) }) {}
                LightColorPicker(change.color, enabled, reading) { onChange(change.copy(color = it)) }
            }
        }
    }
}
