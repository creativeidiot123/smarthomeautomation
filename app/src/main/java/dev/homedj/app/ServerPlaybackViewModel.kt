package dev.homedj.app

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ExecutionException

data class LocalAudio(val uri: Uri, val title: String)

/** UI projection of the one Media3 session; it does not own playback or a second queue. */
class ServerPlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableStatus = MutableStateFlow(ServerPlaybackUiState())
    val status = mutableStatus.asStateFlow()
    private var controller: MediaController? = null
    private var connectionVersion = 0
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val breakStore = MusicBreakStore(application)
    val breakSettings: StateFlow<MusicBreakSettings> = breakStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), breakStore.read())
    /** Wall-clock time the running break ends, for display only; PlaybackService owns the break. */
    val breakResumesAt: StateFlow<Long?> = MusicBreaks.resumeAt
        .map { at -> at?.let { System.currentTimeMillis() + it - SystemClock.elapsedRealtime() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setBreaks(settings: MusicBreakSettings) {
        if (breakStore.save(settings)) RemoteActivity.record(RemoteActivity.SAMSUNG, breaksActivityText(settings))
    }

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
                future = null // Allow the next screen entry to retry.
                mutableStatus.value = ServerPlaybackUiState(error = "Media service unavailable")
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                future = null
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

    /** Pause also cancels a buffering/stalled start; play re-prepares after an error. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun playOrPause() {
        val active = controller ?: return
        if (active.currentMediaItem == null) return
        RemoteActivity.record(RemoteActivity.SAMSUNG, 
            if (Util.shouldShowPlayButton(active)) "Play" else "Pause")
        Util.handlePlayPauseButtonAction(active)
    }

    fun next() {
        val active = controller ?: return
        active.seekToNextMediaItem()
        RemoteActivity.record(RemoteActivity.SAMSUNG, "Next song")
    }

    fun previous() {
        val active = controller ?: return
        active.seekToPreviousMediaItem()
        RemoteActivity.record(RemoteActivity.SAMSUNG, "Previous song")
    }

    fun toggleShuffle() {
        val active = controller ?: return
        active.shuffleModeEnabled = !active.shuffleModeEnabled
        RemoteActivity.record(RemoteActivity.SAMSUNG, 
            if (active.shuffleModeEnabled) "Shuffle on" else "Shuffle off")
    }

    /** A fresh user-picked queue is sent to the service owner in a single Media3 command. */
    fun playPickedAudio(selections: List<LocalAudio>, startIndex: Int? = null) {
        if (selections.isEmpty() || startIndex != null && startIndex !in selections.indices) return
        val active = controller ?: run {
            mutableStatus.value = mutableStatus.value.copy(error = "Media service not connected")
            return
        }
        val items = selections.map { selected ->
            MediaItem.Builder()
                .setUri(selected.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(selected.title)
                    .setArtworkUri(HomeDjMediaResolver.videoIdFromUri(selected.uri.toString())
                        ?.let(::youTubeArtworkUrl)?.let(Uri::parse)).build())
                .build()
        }
        if (startIndex == null) active.setMediaItems(items)
        else active.setMediaItems(items, startIndex, C.TIME_UNSET)
        active.prepare()
        active.play()
        RemoteActivity.record(RemoteActivity.SAMSUNG,
            if (startIndex != null) "Playing " + selections[startIndex].title
            else if (selections.size == 1) "Playing " + selections.first().title
            else "Playing " + selections.size + " songs")
    }

    /** Plays the current schedule slot now, ending a person's queue early. */
    fun resumeSchedule() = send(MusicScheduler.RESUME, Bundle.EMPTY, "Resume schedule")

    fun playNext(track: YouTubeTrack) = send(MusicScheduler.PLAY_NEXT, Bundle().apply {
        putString(MusicScheduler.ARG_ID, track.id)
        putString(MusicScheduler.ARG_TITLE, track.title)
    }, "Play next: " + track.title)

    fun addToQueue(track: YouTubeTrack) {
        val active = controller ?: return notConnected()
        if (!NativeAudioSource.validId(track.id)) return
        val item = MediaItem.Builder().setUri(HomeDjMediaResolver.trackUri(track.id))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title)
                .setArtworkUri(youTubeArtworkUrl(track.id)?.let(Uri::parse)).build()).build()
        RemoteActivity.record(RemoteActivity.SAMSUNG, "Add to queue: " + track.title)
        if (active.mediaItemCount > 0) {
            active.addMediaItem(item)
            return
        }
        active.setMediaItem(item)
        active.prepare()
        active.play()
    }

    fun jumpTo(index: Int) {
        val active = controller ?: return
        if (index !in 0 until active.mediaItemCount) return
        RemoteActivity.record(RemoteActivity.SAMSUNG, 
            "Jump to: " + (active.getMediaItemAt(index).mediaMetadata.title ?: "song"))
        active.seekToDefaultPosition(index)
        active.play()
    }

    /** The service owns the outcome; its fixed failure text is shown until the next command. */
    private fun send(command: SessionCommand, args: Bundle, activity: String) {
        val active = controller ?: return notConnected()
        val version = connectionVersion
        mutableStatus.value = mutableStatus.value.copy(message = null)
        val reply = active.sendCustomCommand(command, args)
        reply.addListener({
            if (version != connectionVersion) return@addListener
            val result = try {
                reply.get()
            } catch (failure: ExecutionException) {
                Log.w("HomeDJ", "Playback command failed: " + failure.javaClass.simpleName)
                null
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
            if (result?.resultCode == SessionResult.RESULT_SUCCESS) {
                RemoteActivity.record(RemoteActivity.SAMSUNG, activity)
            } else {
                mutableStatus.value = mutableStatus.value.copy(
                    message = result?.sessionError?.message ?: "Music service did not respond")
            }
        }, getApplication<Application>().mainExecutor)
    }

    private fun notConnected() {
        mutableStatus.value = mutableStatus.value.copy(error = "Media service not connected")
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun refresh(player: Player) {
        val first = if (player.mediaItemCount > 0) player.getMediaItemAt(0) else null
        mutableStatus.value = ServerPlaybackUiState(
            upNext = upNext(player),
            scheduleLabel = first?.mediaMetadata?.extras?.getString(MusicScheduler.SLOT_LABEL),
            message = mutableStatus.value.message,
            connected = true,
            snapshot = PlaybackSnapshot(
                title = player.currentMediaItem?.mediaMetadata?.title?.toString()
                    ?: "No track selected",
                hasTrack = player.currentMediaItem != null,
                isPlaying = !Util.shouldShowPlayButton(player),
                hasNext = player.hasNextMediaItem(),
                hasPrevious = player.hasPreviousMediaItem(),
                queueSize = player.mediaItemCount,
                shuffle = player.shuffleModeEnabled,
                artworkUrl = player.currentMediaItem?.mediaMetadata?.artworkUri?.toString(),
            ),
            error = if (player.playerError != null) "Unable to play this audio file" else null,
        )
    }

    /** The next songs in actual play order, which follows the shuffle order when shuffle is on. */
    private fun upNext(player: Player): List<QueueEntry> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val window = Timeline.Window()
        return buildList {
            var index = timeline.getNextWindowIndex(
                player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            while (index != C.INDEX_UNSET && size < UP_NEXT_LIMIT) {
                val metadata = timeline.getWindow(index, window).mediaItem.mediaMetadata
                add(QueueEntry(index, metadata.title?.toString() ?: "Unknown song", metadata.artworkUri?.toString()))
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            }
        }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private companion object { const val UP_NEXT_LIMIT = 30 }
}

data class QueueEntry(val index: Int, val title: String, val artworkUrl: String? = null)

data class ServerPlaybackUiState(
    val connected: Boolean = false,
    val snapshot: PlaybackSnapshot = PlaybackSnapshot(),
    val error: String? = null,
    val upNext: List<QueueEntry> = emptyList(),
    /** Set while the queue was started by a schedule slot; null for a person's own queue. */
    val scheduleLabel: String? = null,
    val message: String? = null,
)
