package dev.homedj.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class HomeDjActivity : ComponentActivity() {
    private val roleModel: RoleViewModel by viewModels()
    private val playback: ServerPlaybackViewModel by viewModels()

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && roleModel.state.value.mode == HomeDjMode.SERVER) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                playback.playPickedAudio(uri, displayName(uri))
            } catch (exception: SecurityException) {
                android.util.Log.w("HomeDJ", "Audio document permission denied", exception)
                Toast.makeText(this, "Unable to access selected audio", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Selected audio"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val role by roleModel.state.collectAsStateWithLifecycle()
            MaterialTheme {
                when {
                    !role.loaded -> RoleLoadingScreen(role.error)
                    role.mode == HomeDjMode.SERVER -> {
                        DisposableEffect(playback) {
                            playback.connect()
                            onDispose { playback.disconnect() }
                        }
                        val status by playback.status.collectAsStateWithLifecycle()
                        ServerScreen(
                            status = status,
                            changing = role.changing,
                            onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                            onPlayPause = playback::playOrPause,
                            onNext = playback::next,
                            onPrevious = playback::previous,
                            onSwitch = { roleModel.select(HomeDjMode.CONTROLLER) },
                        )
                    }
                    role.mode == HomeDjMode.CONTROLLER -> ControllerScreen(
                        changing = role.changing,
                        onSwitch = { roleModel.select(HomeDjMode.SERVER) },
                    )
                    else -> RolePicker(
                        changing = role.changing,
                        onServer = { roleModel.select(HomeDjMode.SERVER) },
                        onController = { roleModel.select(HomeDjMode.CONTROLLER) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenFrame(content: @Composable () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("HomeDJ", style = MaterialTheme.typography.headlineLarge)
            content()
        }
    }
}

@Composable
private fun RoleLoadingScreen(error: String?) {
    ScreenFrame {
        Text(error ?: "Loading device role…")
    }
}

@Composable
private fun RolePicker(changing: Boolean, onServer: () -> Unit, onController: () -> Unit) {
    ScreenFrame {
        Text("How will this phone be used?", style = MaterialTheme.typography.titleLarge)
        ElevatedCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                Text("Plays music locally through this phone's audio output. Choose on Samsung.")
                Button(onClick = onServer, enabled = !changing) { Text("Use as Server") }
            }
        }
        ElevatedCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Controller", style = MaterialTheme.typography.titleMedium)
                Text("Will remotely control the server after secure pairing. Choose on OnePlus.")
                Button(onClick = onController, enabled = !changing) { Text("Use as Controller") }
            }
        }
    }
}

@Composable
private fun ServerScreen(
    status: ServerPlaybackUiState,
    changing: Boolean,
    onPickAudio: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSwitch: () -> Unit,
) {
    ScreenFrame {
        Text("Server mode", style = MaterialTheme.typography.titleLarge)
        Text(
            if (status.connected) "Playback service connected"
            else "Connecting to playback service…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Now playing", style = MaterialTheme.typography.labelLarge)
                Text(status.snapshot.title, style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPrevious, enabled = status.snapshot.hasPrevious) {
                        Text("Previous")
                    }
                    Button(onClick = onPlayPause, enabled = status.snapshot.hasTrack) {
                        Text(if (status.snapshot.isPlaying) "Pause" else "Play")
                    }
                    OutlinedButton(onClick = onNext, enabled = status.snapshot.hasNext) {
                        Text("Next")
                    }
                }
            }
        }
        Button(onClick = onPickAudio, enabled = status.connected && !changing) {
            Text("Choose local audio")
        }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Playback runs on this device. Remote control and pairing are not enabled yet.")
        TextButton(onClick = onSwitch, enabled = !changing) { Text("Switch to Controller mode") }
    }
}

@Composable
private fun ControllerScreen(changing: Boolean, onSwitch: () -> Unit) {
    ScreenFrame {
        Text("Controller mode", style = MaterialTheme.typography.titleLarge)
        ElevatedCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Not paired", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This phone will control HomeDJ Server over Wi-Fi after authenticated " +
                        "discovery and QR pairing are implemented. Remote commands are disabled for now."
                )
            }
        }
        Text("Music plays on the Samsung, not on this phone.")
        TextButton(onClick = onSwitch, enabled = !changing) { Text("Switch to Server mode") }
    }
}
