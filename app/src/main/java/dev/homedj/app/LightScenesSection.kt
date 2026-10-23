package dev.homedj.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlin.math.roundToInt

@Composable
internal fun LightScenesSection(state: LightsUiState, actions: LightsActions, playlists: Map<String, String>) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.busy, waiting) {
        if (waiting && !state.busy) { if (state.actionSucceeded) editing = null; waiting = false }
    }
    val canCreate = !state.busy && state.snapshot.bulbs.isNotEmpty() && state.snapshot.scenes.size < LightExperience.MAX_SCENES
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Scenes", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        if (state.snapshot.scenes.isNotEmpty()) TextButton(onClick = { managing = !managing }) {
            Text(if (managing) "Done" else "Edit")
        }
    }
    Text(when {
        state.snapshot.scenes.isEmpty() -> "Save a scene for reading, movies or winding down."
        managing -> "Tap a scene to change it."
        else -> "One tap applies a scene to its lights."
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.snapshot.scenes.forEach { scene -> key(scene.id) {
            SceneCard(scene, enabled = !state.busy, editing = managing,
                onClick = { if (managing) editing = scene.id else actions.applyScene(scene.id) })
        } }
        OutlinedCard(onClick = { editing = "" }, enabled = canCreate, modifier = Modifier.size(width = 120.dp, height = 96.dp),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Text("New scene", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
    editing?.let { id -> key(id) {
        LightSceneEditor(state.snapshot.scenes.firstOrNull { it.id == id }, state, playlists, onDismiss = { editing = null },
            onSave = { waiting = true; actions.saveScene(it) },
            onDelete = if (id.isNotBlank()) ({ waiting = true; actions.deleteScene(id) }) else null,
            onPreview = actions.previewScene, onEndPreview = actions.endScenePreview)
    } }
}

/** 120 x 96 dp tonal card: up to four dots preview the scene's colours ("off" when it switches off). */
@Composable
private fun SceneCard(scene: LightScene, enabled: Boolean, editing: Boolean, onClick: () -> Unit) {
    val changes = scene.changes.values
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.size(width = 120.dp, height = 96.dp),
        shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val colors = changes.filter { it.on != false }.map { it.color?.let(::swatchColor) }.distinct().take(4)
                if (colors.isEmpty()) Text("Off", style = MaterialTheme.typography.labelMedium)
                colors.forEach { color ->
                    Box(Modifier.size(20.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .background(color ?: MaterialTheme.colorScheme.primary, CircleShape))
                }
                if (!scene.playlists.isNullOrEmpty()) {
                    Icon(painterResource(R.drawable.ic_music_note), contentDescription = "Plays music", Modifier.size(20.dp))
                }
            }
            Text((if (editing) "Edit · " else "") + scene.name, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightSceneEditor(scene: LightScene?, state: LightsUiState, playlists: Map<String, String>, onDismiss: () -> Unit,
    onSave: (LightScene) -> Unit, onDelete: (() -> Unit)?,
    onPreview: (Map<Long, LightChange>) -> Unit, onEndPreview: () -> Unit) {
    val id by rememberSaveable { mutableStateOf(scene?.id ?: UUID.randomUUID().toString()) }
    var name by rememberSaveable { mutableStateOf(scene?.name.orEmpty()) }
    var chosen by rememberSaveable { mutableStateOf((scene?.changes?.keys ?: state.snapshot.bulbs.map(Bulb::id)).joinToString(",")) }
    var source by rememberSaveable { mutableStateOf(if (scene == null) "custom" else "saved") }
    var power by rememberSaveable { mutableStateOf(true) }
    var level by rememberSaveable { mutableFloatStateOf(40f) }
    var kelvin by rememberSaveable { mutableIntStateOf(2700) }
    var hue by rememberSaveable { mutableIntStateOf(-1) }
    var saturation by rememberSaveable { mutableIntStateOf(100) }
    var fade by rememberSaveable { mutableIntStateOf(5) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    // "Set each light": per-light power/brightness/colour as scene change JSON; the shared fade applies.
    var eachCodes by rememberSaveable { mutableStateOf("") }
    var expandedLight by rememberSaveable { mutableLongStateOf(-1L) }
    val each = SceneEachLight.decode(eachCodes)
    var juggle by rememberSaveable { mutableIntStateOf(scene?.juggle ?: 0) }
    // Linked playlist IDs, comma-separated (IDs never contain commas).
    var linked by rememberSaveable { mutableStateOf(scene?.playlists.orEmpty().joinToString(",")) }
    val linkedIds = linked.split(',').filter(String::isNotEmpty)
    var variationCode by rememberSaveable { mutableIntStateOf(LightVariationCodec.code(scene?.variation ?: LightVariation())) }
    // "id=code;id=code": per-light overrides of the scene variation, kept as a saveable primitive.
    var ownCodes by rememberSaveable { mutableStateOf(scene?.variations.orEmpty().entries
        .joinToString(";") { "${it.key}=${LightVariationCodec.code(it.value)}" }) }
    val members = chosen.split(',').mapNotNull(String::toLongOrNull)
        .filter { id -> state.snapshot.bulbs.any { it.id == id } }.distinct()
    val sceneVariation = LightVariationCodec.read(variationCode) ?: LightVariation()
    val own = ownCodes.split(';').mapNotNull { entry ->
        val parts = entry.split('=', limit = 2)
        parts.getOrNull(0)?.toLongOrNull()?.let { bulb -> parts.getOrNull(1)?.toIntOrNull()?.let(LightVariationCodec::read)?.let { bulb to it } }
    }.toMap().filterKeys { it in members }
    val setOwn = { next: Map<Long, LightVariation> ->
        ownCodes = next.entries.joinToString(";") { "${it.key}=${LightVariationCodec.code(it.value)}" }
    }
    val color = if (kelvin >= 0) LightColor.White(kelvin) else LightColor.Hue(hue, saturation)
    val custom = if (power) LightChange(true, level.roundToInt(), color, fade) else LightChange(on = false, fadeSeconds = fade)
    val entries = members.associateWith { bulb -> when (source) {
        "saved" -> scene?.changes?.get(bulb) ?: LightChange()
        // The lights already show the draft, so capture the states recorded before the preview.
        "current" -> (state.sceneBase ?: state.snapshot.bulbs).firstOrNull { it.id == bulb }?.let { LightChange(it.on, it.level, it.color, fade) } ?: LightChange()
        "each" -> each[bulb]?.copy(fadeSeconds = fade) ?: custom
        else -> custom
    } }
    // Live preview on the lights; closing, saving or deleting puts them back (not on rotation).
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(entries) { onPreview(entries.filterValues { !it.isEmpty }) }
    DisposableEffect(Unit) { onDispose { if (activity?.isChangingConfigurations != true) onEndPreview() } }
    // Juggle needs different colours to swap; otherwise the saved scene turns it off.
    val canJuggle = state.juggleSupported && sceneVariation.enabled &&
        entries.values.filter { it.on != false }.mapNotNull { it.color }.distinct().size >= 2
    val draft = (if (state.variationSupported) LightScene(id, name, entries, sceneVariation, own,
        juggle = if (!state.juggleSupported) null else if (canJuggle) juggle else 0) else LightScene(id, name, entries))
        .copy(playlists = if (state.scenePlaylistsSupported) linkedIds else null)
    LightEditorPage(if (scene == null) "Create scene" else "Edit scene", onDismiss) {
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Scene name") }, enabled = !state.busy,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        state.message?.takeIf { !state.actionSucceeded }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Lights", style = MaterialTheme.typography.titleMedium)
        state.snapshot.bulbs.forEach { bulb ->
            LightCheckRow(bulb.name, bulb.id in members, !state.busy) { checked ->
                chosen = (if (checked) members + bulb.id else members - bulb.id).joinToString(",")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("custom" to "Choose settings", "current" to "Capture HomeDJ settings").forEach { (value, label) ->
                FilterChip(source == value, onClick = { source = value }, enabled = !state.busy, label = { Text(label) })
            }
            FilterChip(source == "each", enabled = !state.busy, label = { Text("Set each light") }, onClick = {
                // Starts from what the editor shows now, so a captured or saved scene can be fine-tuned.
                if (source != "each") eachCodes = SceneEachLight.encode(SceneEachLight.seed(members, entries, custom))
                source = "each"
            })
            if (scene != null) FilterChip(source == "saved", onClick = { source = "saved" }, enabled = !state.busy,
                label = { Text("Keep saved settings") })
        }
        if (source == "custom") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Reading", "Movie", "Night").forEach { preset ->
                    TextButton(onClick = {
                        name = name.ifBlank { preset }; power = true; hue = -1; kelvin = 2700
                        level = when (preset) { "Reading" -> 100f; "Movie" -> 20f; else -> 5f }
                    }, enabled = !state.busy) { Text(preset) }
                }
            }
            LightCheckRow("Lights on", power, !state.busy) { power = it }
            if (power) {
                BrightnessSlider(level, !state.busy, "Scene brightness", { level = it }) {}
                LightColorPicker(color, !state.busy) { picked -> when (picked) {
                    is LightColor.White -> { kelvin = picked.kelvin; hue = -1 }
                    is LightColor.Hue -> { hue = picked.degrees; saturation = picked.saturationPercent; kelvin = -1 }
                } }
            }
            LightFadePicker(fade, !state.busy) { fade = it }
        } else if (source == "current") {
            Text("Captures each light's last recorded HomeDJ settings, including different colors. A light with no recorded settings must be excluded.")
            LightFadePicker(fade, !state.busy) { fade = it }
        } else if (source == "each") {
            Text("Tap a light to give it its own power, brightness and colour.", style = MaterialTheme.typography.bodySmall)
            members.forEach { bulb -> key(bulb) {
                val light = state.snapshot.bulbs.firstOrNull { it.id == bulb }
                SceneEachLightCard(light?.name.orEmpty(), entries.getValue(bulb), light?.reading, expandedLight == bulb,
                    !state.busy, onExpand = { expandedLight = if (expandedLight == bulb) -1L else bulb }) { changed ->
                    eachCodes = SceneEachLight.encode(members.associateWith { entries.getValue(it) } + (bulb to changed))
                }
            } }
            LightFadePicker(fade, !state.busy) { fade = it }
        }
        if (state.variationSupported) {
            LightVariationControls(sceneVariation, !state.busy, { variationCode = LightVariationCodec.code(it) },
                title = "Scene variation", subtitle = "Set on the scene's lights each time it is applied")
            if (canJuggle) LightJuggleControls(juggle, !state.busy) { juggle = it }
            members.forEach { bulb -> key(bulb) {
                val bulbName = state.snapshot.bulbs.firstOrNull { it.id == bulb }?.name.orEmpty()
                val mine = own[bulb]
                LightCheckRow("$bulbName: own variation", mine != null, !state.busy) { checked ->
                    setOwn(if (checked) own + (bulb to sceneVariation) else own - bulb)
                }
                if (mine != null) LightVariationControls(mine, !state.busy, { setOwn(own + (bulb to it)) },
                    title = "$bulbName variation", subtitle = "Used instead of the scene variation")
            } }
        }
        if (state.scenePlaylistsSupported) {
            Text("Music", style = MaterialTheme.typography.titleMedium)
            Text("Applying this scene also plays these playlists shuffled on Samsung, in place of what is playing.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (playlists.isEmpty() && linkedIds.isEmpty()) Text("No playlists yet. Add one in Library.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (playlists.keys + linkedIds).distinct().forEach { playlist -> key(playlist) {
                    val selected = playlist in linkedIds
                    FilterChip(selected, enabled = !state.busy && (selected || linkedIds.size < ScheduleCodec.MAX_PLAYLISTS),
                        onClick = { linked = (if (selected) linkedIds - playlist else linkedIds + playlist).joinToString(",") },
                        label = { Text(playlists[playlist] ?: "Missing playlist", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                } }
            }
        }
        Text("The lights preview this scene while you edit and go back when you close it. Lights left out turn off when it is applied. " +
            "Applying a scene saves individual settings, just like controlling its lights directly.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onSave(draft) }, enabled = !state.busy && LightExperience.valid(draft, state.snapshot.bulbs)) { Text("Save scene") }
        onDelete?.let { TextButton(onClick = { deleting = true }, enabled = !state.busy) { Text("Delete scene") } }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete scene?") },
        text = { Text("Also removes schedules that use this scene. Lights keep their current settings.") },
        confirmButton = { TextButton(onClick = { deleting = false; onDelete?.invoke() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}
