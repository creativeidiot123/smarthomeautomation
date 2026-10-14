package dev.homedj.server

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class ServerActivity : ComponentActivity() {
    private val playback: ServerPlaybackViewModel by viewModels()
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                playback.playPickedAudio(uri, displayName(uri))
            } catch (exception: SecurityException) {
                android.util.Log.w("HomeDJ", "Audio document permission denied", exception)
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
            val state by playback.status.collectAsStateWithLifecycle()
            MaterialTheme {
                ServerScreen(
                    state = state,
                    onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                    onPlayPause = playback::playOrPause,
                    onNext = playback::next,
                    onPrevious = playback::previous,
                )
            }
        }
    }
}

@Composable
private fun ServerScreen(
    state: ServerPlaybackUiState,
    onPickAudio: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("HomeDJ Server", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (state.connected) "Playback service connected"
                else "Connecting to the playback service…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Now playing", style = MaterialTheme.typography.labelLarge)
                    Text(state.snapshot.title, style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPrevious, enabled = state.snapshot.hasPrevious) {
                            Text("Previous")
                        }
                        Button(onClick = onPlayPause, enabled = state.snapshot.hasTrack) {
                            Text(if (state.snapshot.isPlaying) "Pause" else "Play")
                        }
                        OutlinedButton(onClick = onNext, enabled = state.snapshot.hasNext) {
                            Text("Next")
                        }
                    }
                }
            }
            Button(onClick = onPickAudio, enabled = state.connected) {
                Text("Choose local audio")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            Text(
                "Audio plays on this phone. Lock-screen media controls are handled by Android. " +
                    "Remote pairing and Wi-Fi commands are not enabled in this foundation build.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
