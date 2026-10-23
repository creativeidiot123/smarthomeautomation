package dev.homedj.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** What the Lights tab can ask for; the Activity binds these to [LightsViewModel]. */
internal class LightsActions(
    val refresh: () -> Unit,
    val pair: (name: String, code: String, discriminator: Int?, wifi: LightWifi?) -> Unit,
    val change: (target: Long?, change: LightChange) -> Unit,
    val rename: (id: Long, name: String) -> Unit,
    val forget: (id: Long) -> Unit,
    val allowExactAlarms: () -> Unit,
    val scanQr: () -> Unit,
    val clearScan: () -> Unit,
    val discover: () -> Unit,
    val saveGroup: (LightsGroup) -> Unit,
    val deleteGroup: (String) -> Unit,
    val changeGroup: (String, LightChange) -> Unit,
    val preferences: (Long, LightPreferences) -> Unit,
    val saveLightRule: (LightsRule) -> Unit,
    val removeLightRule: (String) -> Unit,
    val enableLightRule: (String, Boolean) -> Unit,
    val runLightRule: (String) -> Unit,
    val saveScene: (LightScene) -> Unit,
    val deleteScene: (String) -> Unit,
    val applyScene: (String) -> Unit,
    val saveLocation: (LightLocation?) -> Unit,
    val readLight: (Long) -> Unit,
    val variation: (Long?, LightVariation?) -> Unit,
    val groupVariation: (String, LightVariation?) -> Unit,
    val allowBluetooth: () -> Unit,
    val wifiPassword: () -> String,
    val previewScene: (Map<Long, LightChange>) -> Unit,
    val endScenePreview: () -> Unit,
    val dance: (LightDance) -> Unit,
    val permanentlyOff: (Long, Boolean) -> Unit,
)

/** Both phones manage lights; Samsung owns commissioning and the Matter fabric. */
@Composable
internal fun LightsSection(state: LightsUiState, actions: LightsActions, rules: HouseRulesUiState, rulesActions: HouseRulesActions,
    playlists: Map<String, String>) {
    LaunchedEffect(state.mode) { actions.refresh() }
    val server = state.mode == HomeDjMode.SERVER
    val bulbs = state.snapshot.bulbs
    var scheduleTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf("Lights") }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        LIGHT_PAGES.forEachIndexed { index, label ->
            SegmentedButton(selected = page == label, onClick = { page = label }, icon = {},
                shape = SegmentedButtonDefaults.itemShape(index, LIGHT_PAGES.size)) { Text(label, maxLines = 1) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(state.message.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = actions.refresh, enabled = !state.busy) {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh lights")
        }
    }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (!state.loaded) {
        if (state.busy) Text("Asking the Samsung for its lights…")
        else TextButton(onClick = actions.refresh) { Text("Try again") }
        return
    }
    if (page == "Schedule") {
        LightsScheduleSection(state, actions, scheduleTarget) { scheduleTarget = null }
        return
    }
    if (page == "Automation") {
        LightsRoutinesSection(state, actions)
        return
    }
    if (page == "Rules") {
        HouseRulesSection(rules, HouseRules.names(state.snapshot), rulesActions)
        return
    }
    if (bulbs.isNotEmpty()) AllLightsCard(state, onChange = { actions.change(null, it) },
        onVariation = { actions.variation(null, it) })
    if (bulbs.isNotEmpty()) LightDanceCard(state, actions.dance)
    LightScenesSection(state, actions, playlists)
    if (bulbs.isEmpty()) {
        Text(if (state.setupAvailable) "No lights yet. Add a Matter light below." else "No lights yet.")
    } else {
        LightsGroupsSection(state, actions, onSchedule = { scheduleTarget = "group:$it"; page = "Schedule" })
        Text("Lights", style = MaterialTheme.typography.titleLarge)
        bulbs.forEach { bulb -> key(bulb.id) {
            BulbTile(bulb, state, actions, onSchedule = { scheduleTarget = "bulb:${bulb.id}"; page = "Schedule" })
        } }
    }
    if (state.setupAvailable) AddLightCard(state, actions)
    else if (!server) Text("Update HomeDJ on Samsung to add or forget lights from this phone.")
}

private val LIGHT_PAGES = listOf("Lights", "Schedule", "Automation", "Rules")

/** Either phone supplies a code; Samsung performs the commissioning. */
@Composable
private fun AddLightCard(state: LightsUiState, actions: LightsActions) {
    val busy = state.busy
    val room = state.snapshot.bulbs.size < LightsPolicy.MAX_BULBS
    var name by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var newLight by rememberSaveable { mutableStateOf(false) }
    // Samsung remembers the last Wi-Fi name that worked; it arrives after the Lights tab loads.
    var wifiName by rememberSaveable(state.wifiName) { mutableStateOf(state.wifiName) }
    // Never saved: the ViewModel keeps the last password in memory so several bulbs can be added in a row.
    var wifiPassword by remember { mutableStateOf(actions.wifiPassword()) }
    val bluetooth = newLight && state.mode == HomeDjMode.SERVER
    var open by rememberSaveable { mutableStateOf(state.snapshot.bulbs.isEmpty()) }
    if (!open) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null)
            Text("Add a light", Modifier.padding(start = 8.dp))
        }
        return
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add a light", style = MaterialTheme.typography.titleLarge)
            if (!room) {
                Text("HomeDJ supports up to ${LightsPolicy.MAX_BULBS} lights. Forget one to add another.")
                return@Column
            }
            Text("Keep Samsung on the light's Wi-Fi and open the light's Matter pairing mode. " +
                "A brand-new light uses its printed Matter code with New light on; a light already in another " +
                "Matter app uses a share code from that app.",
                style = MaterialTheme.typography.bodyMedium)
            if (state.mode == HomeDjMode.CONTROLLER) Text("This phone sends the code securely to Samsung to add the light. " +
                "To set up a brand-new light from its printed code, use the Samsung.", style = MaterialTheme.typography.bodySmall)
            if (state.mode == HomeDjMode.SERVER) {
                Row(Modifier.fillMaxWidth().selectable(selected = newLight, enabled = !busy, role = Role.Switch,
                    onClick = { newLight = !newLight }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("New light (never set up)", style = MaterialTheme.typography.titleSmall)
                        Text("Samsung finds it over Bluetooth and gives it your Wi-Fi.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = newLight, onCheckedChange = null, enabled = !busy)
                }
            }
            if (bluetooth) {
                if (!state.bluetoothReady) OutlinedButton(onClick = actions.allowBluetooth, enabled = !busy) { Text("Allow Bluetooth") }
                OutlinedTextField(value = wifiName, onValueChange = { wifiName = it.take(32) },
                    label = { Text("2.4 GHz Wi-Fi name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = wifiPassword, onValueChange = { wifiPassword = it.take(64) },
                    label = { Text("Wi-Fi password") }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    supportingText = { Text("Kept only while HomeDJ is open; never saved.") }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = actions.scanQr, enabled = !busy) { Text("Scan QR") }
                OutlinedButton(onClick = { selected = null; actions.discover() }, enabled = !busy) { Text("Scan Wi-Fi") }
            }
            state.discovered.forEach { candidate ->
                TextButton(onClick = { selected = candidate.discriminator; name = candidate.name }, enabled = !busy) {
                    Text((if (selected == candidate.discriminator) "Selected: " else "") + candidate.name +
                        " · ${candidate.discriminator}")
                }
            }
            if (selected != null) TextButton(onClick = { selected = null }, enabled = !busy) { Text("Clear selection") }
            if (state.discoveryComplete && state.discovered.isEmpty()) Text("No lights found on Samsung's Wi-Fi.")
            OutlinedTextField(value = name, onValueChange = { name = it.take(LightsPolicy.MAX_NAME) },
                label = { Text("Name (optional)") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth())
            if (state.scannedCodeReady) {
                Text("Matter QR code ready")
                TextButton(onClick = actions.clearScan, enabled = !busy) { Text("Clear QR code") }
            }
            OutlinedTextField(value = code, onValueChange = { code = it.take(512); actions.clearScan() },
                label = { Text("Pairing code") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth())
            FilledTonalButton(onClick = {
                actions.pair(name, code, selected, if (bluetooth) LightWifi(wifiName, wifiPassword) else null)
                code = ""
            }, enabled = !busy && (state.scannedCodeReady || code.isNotBlank()) &&
                (!bluetooth || (state.bluetoothReady && wifiName.isNotEmpty()))) {
                Text("Pair light")
            }
        }
    }
}
