package dev.homedj.app

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.homedj.app.PlaybackSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutionException

/** UI projection of the one Media3 session; it does not own playback or a second queue. */
class ServerPlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableStatus = MutableStateFlow(ServerPlaybackUiState())
    val status = mutableStatus.asStateFlow()
    private var controller: MediaController? = null
    private var connectionVersion = 0
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
        }
    }
    fun connect() {
        if (future != null) return
        val version = ++connectionVersion
        val application = getApplication<Application>()
        val controllerFuture = MediaController.Builder(
            application,
            SessionToken(application, ComponentName(application, PlaybackService::class.java)),
        ).buildAsync()
        future = controllerFuture
        controllerFuture.addListener({
            if (version != connectionVersion) return@addListener
            try {
                val connected = controllerFuture.get()
                if (version != connectionVersion) return@addListener
                controller = connected
                connected.addListener(listener)
                refresh(connected)
            } catch (exception: ExecutionException) {
                Log.e("HomeDJ", "Media controller connection failed", exception)
                mutableStatus.value = ServerPlaybackUiState(error = "Media service unavailable")
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                mutableStatus.value = ServerPlaybackUiState(error = "Media service connection interrupted")
            }
        }, application.mainExecutor)
    }

    fun disconnect() {
        connectionVersion++
        controller?.removeListener(listener)
        controller = null
        future?.let(MediaController::releaseFuture)
        future = null
        mutableStatus.value = ServerPlaybackUiState()
    }

    fun playOrPause() {
        val active = controller ?: return
        if (active.isPlaying) active.pause() else if (active.currentMediaItem != null) active.play()
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }

    fun playPickedAudio(uri: Uri, title: String) {
        val active = controller ?: run {
            mutableStatus.value = mutableStatus.value.copy(error = "Media service not connected")
            return
        }
        active.setMediaItem(
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        )
        active.prepare()
        active.play()
    }

    private fun refresh(player: Player) {
        mutableStatus.value = ServerPlaybackUiState(
            connected = true,
            snapshot = PlaybackSnapshot(
                title = player.currentMediaItem?.mediaMetadata?.title?.toString()
                    ?: "No track selected",
                hasTrack = player.currentMediaItem != null,
                isPlaying = player.isPlaying,
                hasNext = player.hasNextMediaItem(),
                hasPrevious = player.hasPreviousMediaItem(),
            ),
            error = if (player.playerError != null) "Unable to play this audio file" else null,
        )
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}

data class ServerPlaybackUiState(
    val connected: Boolean = false,
    val snapshot: PlaybackSnapshot = PlaybackSnapshot(),
    val error: String? = null,
)
