package dev.homedj.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
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
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope

class HomeDjActivity : ComponentActivity() {
    private val roleModel: RoleViewModel by viewModels()
    private val playback: ServerPlaybackViewModel by viewModels()
    private val youtube: YouTubeViewModel by viewModels()
    private var authorizationPending = false
    private var revokePending = false

    private val scopes = listOf(Scope(YOUTUBE_SCOPE))
    private val audioPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (roleModel.state.value.mode != HomeDjMode.SERVER) return@registerForActivityResult
            val selected = uris.distinct().mapNotNull { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    LocalAudio(uri, displayName(uri))
                } catch (exception: SecurityException) {
                    Log.w("HomeDJ", "Unable to persist read grant for local audio")
                    null
                }
            }
            if (selected.size < uris.distinct().size) {
                Toast.makeText(this, "Some audio files could not be accessed", Toast.LENGTH_SHORT).show()
            }
            if (selected.isNotEmpty()) playback.playPickedAudio(selected)
        }

    private val authorizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            authorizationPending = false
            if (roleModel.state.value.mode != HomeDjMode.SERVER) return@registerForActivityResult
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                youtube.authorizationError("YouTube access was not granted")
                return@registerForActivityResult
            }
            try {
                useAuthorization(Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(result.data))
            } catch (exception: Exception) {
                Log.w("HomeDJ", "Google authorization result failed: " + exception.javaClass.simpleName)
                youtube.authorizationError("Could not connect your YouTube account")
            }
        }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Selected audio"

    private fun requestYouTubeAccess() {
        if (authorizationPending || revokePending || roleModel.state.value.mode != HomeDjMode.SERVER) return
        authorizationPending = true
        val request = AuthorizationRequest.builder().setRequestedScopes(scopes).build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener(this) { result ->
                if (roleModel.state.value.mode != HomeDjMode.SERVER) {
                    authorizationPending = false
                } else if (result.hasResolution()) {
                    val pending = result.pendingIntent
                    if (pending == null) {
                        authorizationPending = false
                        youtube.authorizationError("Google authorization is unavailable")
                    } else {
                        try {
                            authorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                        } catch (failure: Exception) {
                            authorizationPending = false
                            Log.w("HomeDJ", "Unable to launch Google authorization")
                            youtube.authorizationError("Unable to start Google authorization")
                        }
                    }
                } else {
                    authorizationPending = false
                    useAuthorization(result)
                }
            }
            .addOnFailureListener(this) { failure ->
                authorizationPending = false
                Log.w("HomeDJ", "Google authorization failed: " + failure.javaClass.simpleName)
                youtube.authorizationError("YouTube authorization failed. Check Google Play services.")
            }
    }

    @Suppress("DEPRECATION")
    private fun useAuthorization(result: AuthorizationResult) {
        val token = result.accessToken
        if (roleModel.state.value.mode != HomeDjMode.SERVER) return
        if (token.isNullOrBlank() || YOUTUBE_SCOPE !in result.grantedScopes) {
            youtube.authorizationError("Google did not grant read-only YouTube access")
            return
        }
        youtube.authorized(token, result.toGoogleSignInAccount()?.account)
    }

    private fun disconnectYouTube() {
        if (revokePending || authorizationPending) return
        val account = youtube.authorizedAccount
        if (account == null) {
            youtube.clear()
            youtube.authorizationError(
                "Playlist view cleared. Revoke the app's access from Google Account settings."
            )
            return
        }
        revokePending = true
        val request = RevokeAccessRequest.builder().setAccount(account).setScopes(scopes).build()
        Identity.getAuthorizationClient(this).revokeAccess(request)
            .addOnSuccessListener(this) {
                revokePending = false
                youtube.clear()
            }
            .addOnFailureListener(this) { failure ->
                revokePending = false
                Log.w("HomeDJ", "Google revoke failed: " + failure.javaClass.simpleName)
                youtube.authorizationError("Could not revoke Google access. Please try again.")
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val role by roleModel.state.collectAsStateWithLifecycle()
            MaterialTheme {
                when {
                    !role.loaded -> RoleLoadingScreen(role.error)
                    role.mode == HomeDjMode.SERVER -> {
                        DisposableEffect(playback, youtube) {
                            playback.connect()
                            onDispose {
                                playback.disconnect()
                                youtube.clear()
                            }
                        }
                        val status by playback.status.collectAsStateWithLifecycle()
                        val youtubeState by youtube.state.collectAsStateWithLifecycle()
                        ServerScreen(
                            status = status,
                            youtubeState = youtubeState,
                            changing = role.changing,
                            onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                            onPlayPause = playback::playOrPause,
                            onNext = playback::next,
                            onPrevious = playback::previous,
                            onShuffle = playback::toggleShuffle,
                            onConnectYouTube = ::requestYouTubeAccess,
                            onDisconnectYouTube = ::disconnectYouTube,
                            onSelectPlaylist = youtube::selectPlaylist,
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

    private companion object {
        const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
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
    youtubeState: YouTubeUiState,
    changing: Boolean,
    onPickAudio: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onConnectYouTube: () -> Unit,
    onDisconnectYouTube: () -> Unit,
    onSelectPlaylist: (String) -> Unit,
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
                Text("Queue: " + status.snapshot.queueSize + " local tracks")
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
                OutlinedButton(
                    onClick = onShuffle, enabled = status.snapshot.queueSize > 1,
                ) { Text(if (status.snapshot.shuffle) "Shuffle: on" else "Shuffle: off") }
            }
        }
        Button(onClick = onPickAudio, enabled = status.connected && !changing) {
            Text("Choose local audio files")
        }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        YouTubeSection(
            state = youtubeState,
            onConnect = onConnectYouTube,
            onDisconnect = onDisconnectYouTube,
            onSelectPlaylist = onSelectPlaylist,
        )
        Text("Local playback works without YouTube. Remote Wi-Fi control is not enabled yet.")
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
