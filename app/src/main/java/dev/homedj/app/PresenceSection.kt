package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp

/** OnePlus card: Home/Away verdict, the three signals behind it, and detection settings ([HomeArrivalCard] follows it). */
@Composable
internal fun PresenceCard(
    state: PresenceUiState,
    onEnabled: (Boolean) -> Unit,
    onSetHome: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = state.status
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Home detection", style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    !state.enabled -> "Off"
                    !status.running -> "Starting…"
                    status.presence == Presence.HOME -> "You're home"
                    status.presence == Presence.AWAY -> "You're away"
                    else -> "Checking whether you're home…"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            ToggleRow("Detect when I'm home", state.enabled, onEnabled)
            if (state.enabled && status.running) {
                Text("${status.homeSignals} of 3 signals say home (2 needed)",
                    style = MaterialTheme.typography.bodyMedium)
                Signal("Wi-Fi", status.samsungReachable,
                    if (status.samsungReachable) "Samsung reachable" else "Samsung not reachable")
                Signal("Bluetooth", status.beaconSeen, when {
                    !state.nearbyGranted -> "Nearby devices permission off"
                    status.beaconSeen -> "Samsung nearby"
                    status.scanning -> "Samsung not seen"
                    else -> "Bluetooth off"
                })
                Signal("GPS", status.geofence == GeofenceState.INSIDE, when {
                    !state.homeSet -> "Home location not set"
                    !state.backgroundLocationGranted -> "Needs location \"Allow all the time\""
                    status.geofence == GeofenceState.INSIDE -> "Inside home area"
                    status.geofence == GeofenceState.OUTSIDE -> "Outside home area"
                    else -> "Waiting for location"
                })
            }
            if (status.samsungOutdated) {
                Text("Update HomeDJ on Samsung so it can pause and resume music for you.",
                    color = MaterialTheme.colorScheme.error)
            }
            FilledTonalButton(onClick = onSetHome, enabled = state.locationGranted && !state.settingHome) {
                Text(if (state.settingHome) "Finding your location…"
                    else if (state.homeSet) "Move home location here" else "Set home location here")
            }
            Text("Stored only on this phone. Used for a 150 m home area.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val hints = listOfNotNull(
                if (state.enabled && state.locationGranted && !state.backgroundLocationGranted)
                    "Set Location to \"Allow all the time\" so GPS works in the background." else null,
                if (state.enabled && !state.batteryUnrestricted)
                    "Set Battery to \"Unrestricted\" so OxygenOS keeps detection running." else null,
            )
            if (hints.isNotEmpty()) {
                hints.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = onOpenAppSettings) { Text("Open app settings") }
            }
        }
    }
}

/** Samsung card shown only while Bluetooth access is missing for presence. */
@Composable
internal fun PresenceNearbyCard(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OnePlus home detection", style = MaterialTheme.typography.titleMedium)
            Text("Allow Nearby devices so Samsung can tell when your OnePlus leaves and pause music.")
            FilledTonalButton(onClick = onAllow) { Text("Allow nearby devices") }
        }
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun Signal(name: String, green: Boolean, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (green) "✓" else "–", style = MaterialTheme.typography.labelLarge,
            color = if (green) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.labelLarge)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
