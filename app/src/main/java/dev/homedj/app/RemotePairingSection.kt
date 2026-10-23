package dev.homedj.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun RemotePairingForm(
    state: RemotePairingUiState,
    connected: Boolean,
    enabled: Boolean,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onCode: (String) -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    val editable = enabled && state.ready && !state.scanning
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Pair with Samsung", style = MaterialTheme.typography.titleLarge)
            FilledTonalButton(onClick = onScan, enabled = editable) {
                Text(if (state.scanning) "Scanning…" else "Scan Samsung QR code")
            }
            Text("On Samsung, open Settings → Show pairing QR. Or enter these details manually.")
            OutlinedTextField(state.host, onHost, label = { Text("Samsung Wi-Fi IP address") },
                singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(state.port, onPort, label = { Text("Port") },
                supportingText = { Text("HomeDJ uses port 47188") },
                singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(state.code, onCode, label = { Text("Pairing code") },
                supportingText = { Text("26 letters/numbers; spaces are optional. Existing long codes also work.") },
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide code" else "Show code") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onConnect, enabled = editable && state.host.isNotBlank() && state.code.isNotBlank()) {
                Text(if (connected) "Reconnect" else "Pair and connect")
            }
            Text(if (connected) "Connected. Pairing is saved on this phone."
                else "Pairing is saved after Samsung confirms. HomeDJ reuses it when the app opens or Wi-Fi returns.")
            Text("Keep both phones on the same private Wi-Fi. If Samsung's IP changes, update its address or scan again.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ServerPairingQr(ip: String, code: String, ready: Boolean) {
    var show by remember(ip, code) { mutableStateOf(false) }
    val payload = remember(ip, code) { remotePairingPayload(ip, code) }
    TextButton(onClick = { show = !show }, enabled = ready && payload != null) {
        Text(if (show) "Hide pairing QR" else "Show pairing QR")
    }
    if (payload == null) Text("Connect Samsung to private Wi-Fi to show its pairing QR.")
    if (show && payload != null) {
        Text("Scan with HomeDJ on OnePlus. Keep this QR private: it grants remote control.")
        val matrix by produceState<BitMatrix?>(null, payload) {
            value = withContext(Dispatchers.Default) { pairingQrMatrix(payload) }
        }
        matrix?.let { qr ->
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)
                .semantics { contentDescription = "Private HomeDJ pairing QR. Scan using the controller phone." }) {
                drawRect(Color.White)
                // Integer scale and the writer's four-module quiet zone keep every module sharp.
                val unit = (size.minDimension / qr.width).toInt().coerceAtLeast(1).toFloat()
                val left = (size.width - qr.width * unit) / 2
                val top = (size.height - qr.height * unit) / 2
                for (y in 0 until qr.height) for (x in 0 until qr.width) {
                    if (qr[x, y]) drawRect(Color.Black, Offset(left + x * unit, top + y * unit), Size(unit, unit))
                }
            }
        }
    }
}

@Composable
internal fun ShorterPairingCodeButton(enabled: Boolean, onReplace: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }, enabled = enabled) { Text("Create shorter pairing code") }
    Text("Turn off remote control first. Creating a new code requires pairing your OnePlus again.",
        style = MaterialTheme.typography.bodySmall)
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Replace pairing code?") },
        text = { Text("The old code will stop working. Pair your OnePlus again using the new code or QR.") },
        confirmButton = { TextButton(onClick = { confirm = false; onReplace() }, enabled = enabled) { Text("Replace code") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
