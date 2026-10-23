package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * OnePlus, under Home detection: everything that happens when you leave or come home. Music pause is
 * this phone's own setting; lights, the arrival scene and playlist are Samsung's, shown from its
 * Lights snapshot. [playlists] (ID to title) are Samsung's saved playlists.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeArrivalCard(
    lights: LightsUiState,
    playlists: Map<String, String>,
    autoPause: Boolean,
    onAutoPause: (Boolean) -> Unit,
    onLights: (awayOff: Boolean, homeOn: Boolean, homeScene: String?, homePlaylist: String?) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onRefresh() }
    val automation = lights.snapshot.automation
    val ready = lights.loaded && !lights.busy
    fun save(awayOff: Boolean = automation.awayOff, homeOn: Boolean = automation.homeOn,
             scene: String? = automation.homeScene, playlist: String? = automation.homePlaylist) =
        onLights(awayOff, homeOn, scene, playlist)
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Leaving and coming home", style = MaterialTheme.typography.titleLarge)
            Text("When you leave", style = MaterialTheme.typography.titleMedium)
            ToggleRow("Turn off all lights", automation.awayOff, { save(awayOff = it) }, enabled = ready)
            ToggleRow("Pause music", autoPause, onAutoPause)
            Text("When you come home", style = MaterialTheme.typography.titleMedium)
            Text("Lights", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Leave as they are", automation.homeScene == null && !automation.homeOn, ready) {
                    save(homeOn = false, scene = null)
                }
                Choice("Turn back on", automation.homeScene == null && automation.homeOn, ready && automation.awayOff) {
                    save(homeOn = true, scene = null)
                }
                if (lights.arrivalSupported) lights.snapshot.scenes.forEach { scene ->
                    Choice(scene.name, automation.homeScene == scene.id, ready) { save(homeOn = false, scene = scene.id) }
                }
            }
            if (lights.arrivalSupported) {
                Text("Music", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(if (autoPause) "Resume where it paused" else "Nothing", automation.homePlaylist == null, ready) {
                        save(playlist = null)
                    }
                    (playlists.keys + listOfNotNull(automation.homePlaylist)).distinct().forEach { id ->
                        Choice(playlists[id] ?: "Removed playlist", automation.homePlaylist == id, ready) { save(playlist = id) }
                    }
                }
                Text("A chosen playlist starts freshly shuffled every time you're back, unless music is already playing.",
                    style = MaterialTheme.typography.bodySmall)
            } else if (lights.loaded) {
                Text("Update HomeDJ on Samsung to pick a scene or playlist for coming home.",
                    color = MaterialTheme.colorScheme.error)
            }
            if (!lights.loaded) {
                Text(if (lights.busy) "Asking the Samsung for its lights…" else "Samsung not reached.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRefresh, enabled = !lights.busy) { Text("Try again") }
            } else {
                lights.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("You've left once the Samsung hasn't seen this phone for 5 minutes. Needs Home detection on here and Remote control on the Samsung.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) })
}
