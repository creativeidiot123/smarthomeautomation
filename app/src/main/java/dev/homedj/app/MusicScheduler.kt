package dev.homedj.app

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Applies the stored schedule to PlaybackService's sole player. Scheduled queues tag their items
 * with the slot instance key; anything else is a person's queue, which plays to its end first.
 * Runs on the player's main thread; each decision and its queue change happen without suspending.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MusicScheduler(
    private val app: Context,
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val breaks: MusicBreaks,
) : Player.Listener {

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) scope.launch { safely(ScheduleTrigger.QUEUE_ENDED) }
    }

    /** Only this app's own controllers (Samsung UI, LAN listener, alarm receiver) get schedule commands. */
    fun connectionResult(
        session: MediaSession, controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult? {
        if (controller.uid != Process.myUid()) return null
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(TICK).add(RESUME).add(PLAY_NEXT).build()
            ).build()
    }

    fun onCustomCommand(command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
        val result = SettableFuture.create<SessionResult>()
        scope.launch {
            result.set(when (command.customAction) {
                TICK.customAction -> safely(ScheduleTrigger.BOUNDARY)
                RESUME.customAction -> safely(ScheduleTrigger.RESUME)
                PLAY_NEXT.customAction -> playNext(args)
                else -> failure(SessionError.ERROR_NOT_SUPPORTED, "Unknown command")
            })
        }.invokeOnCompletion {
            // Service shutdown cancels the scope; the waiting controller must still get a reply.
            result.set(failure(SessionError.ERROR_SESSION_DISCONNECTED, "Playback service stopped"))
        }
        return result
    }

    /** Re-arms the next boundary alarm without touching playback (service start, role regained). */
    suspend fun arm() {
        try {
            ScheduleAlarms.arm(app, SchedulePolicy.nextBoundary(ScheduleStore.shared(app).read(), ZonedDateTime.now()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJ", "Schedule alarm failed: " + failure.javaClass.simpleName)
        }
    }

    private suspend fun safely(trigger: ScheduleTrigger): SessionResult =
        try {
            reconcile(trigger)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJ", "Schedule $trigger failed: " + failure.javaClass.simpleName)
            failure(SessionError.ERROR_UNKNOWN, "Schedule unavailable")
        }

    private suspend fun reconcile(trigger: ScheduleTrigger): SessionResult {
        if (ModeStore(app).mode.first() != HomeDjMode.SERVER) {
            ScheduleAlarms.cancel(app)
            return failure(SessionError.ERROR_INVALID_STATE, "Server role inactive")
        }
        val slots = ScheduleStore.shared(app).read()
        val now = ZonedDateTime.now()
        val active = SchedulePolicy.active(slots, now)
        val tracks = active?.let { tracksFor(it.first) }.orEmpty()
        ScheduleAlarms.arm(app, SchedulePolicy.nextBoundary(slots, now))
        // Everything below is synchronous on the player thread, so no other queue change interleaves.
        val queue = queueState()
        return when (val action = SchedulePolicy.decide(trigger, active, queue, LiveAudioSink.active)) {
            is ScheduleAction.Start -> {
                if (tracks.isEmpty()) {
                    Log.w("HomeDJ", "Scheduled slot has no cached songs")
                    return failure(SessionError.ERROR_INVALID_STATE,
                        "No cached songs for this slot. Open its playlists in Library.")
                }
                if (queue.slotKey != action.key) setVolume(action.slot.volume)
                val extras = Bundle().apply {
                    putString(SLOT_KEY, action.key)
                    putString(SLOT_LABEL, action.slot.label)
                }
                breaks.restart()
                player.shuffleModeEnabled = true
                player.setMediaItems(tracks.map { youTubeMediaItem(it, extras) })
                player.prepare()
                player.play()
                Log.i("HomeDJ", "Scheduled slot started: songs=" + tracks.size + " trigger=" + trigger)
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            ScheduleAction.Pause -> {
                breaks.cancel()
                player.pause()
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            ScheduleAction.None -> SessionResult(SessionResult.RESULT_SUCCESS)
            ScheduleAction.NoSlot -> failure(SessionError.ERROR_INVALID_STATE, "No schedule slot right now")
            ScheduleAction.Busy -> failure(SessionError.ERROR_INVALID_STATE, "OnePlus is casting live audio")
        }
    }

    /** Puts one Library song right after the current one, also in shuffled play order. */
    private fun playNext(args: Bundle): SessionResult {
        val id = args.getString(ARG_ID).orEmpty()
        if (!NativeAudioSource.validId(id)) return failure(SessionError.ERROR_BAD_VALUE, "Invalid song")
        val item = youTubeMediaItem(YouTubeTrack(id, args.getString(ARG_TITLE).orEmpty().take(300).ifBlank { id },
            args.getString(ARG_ARTIST).orEmpty().take(200)))
        if (player.mediaItemCount == 0) {
            player.shuffleModeEnabled = true
            player.setMediaItem(item)
            player.prepare()
            player.play()
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }
        val current = player.currentMediaItemIndex
        val order = if (player.shuffleModeEnabled) playOrder() else null
        player.addMediaItem(current + 1, item)
        if (order != null) {
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(
                SchedulePolicy.playNextOrder(order, current, current + 1), Random.nextLong()))
        }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun playOrder(): IntArray {
        val timeline = player.currentTimeline
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return order.toIntArray()
    }

    private fun queueState(): QueueState = QueueState(
        slotKey = queueSlotKey(player),
        hasItems = player.mediaItemCount > 0,
        // A music break is part of playing: a person's queue on a break still finishes first.
        playing = breaks.onBreak || player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING),
        ended = player.playbackState == Player.STATE_ENDED,
    )

    private suspend fun tracksFor(slot: ScheduleSlot): List<YouTubeTrack> {
        val catalog = SavedPlaylistStore.shared(app).read()
        return slot.playlistIds.flatMap { catalog.tracks[it].orEmpty() }
            .filter { NativeAudioSource.validId(it.id) }
            .distinctBy(YouTubeTrack::id)
            .take(MAX_TRACKS)
    }

    private fun setVolume(percent: Int) {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent + 50) / 100, 0)
    }

    private fun failure(code: Int, message: String) = SessionResult(SessionError(code, message))

    companion object {
        const val SLOT_KEY = "homedj_slot"
        const val SLOT_LABEL = "homedj_slot_label"
        const val ARG_ID = "id"
        const val ARG_TITLE = "title"
        const val ARG_ARTIST = "artist"
        private const val MAX_TRACKS = 500
        val TICK = SessionCommand("dev.homedj.app.SCHEDULE_TICK", Bundle.EMPTY)
        val RESUME = SessionCommand("dev.homedj.app.SCHEDULE_RESUME", Bundle.EMPTY)
        val PLAY_NEXT = SessionCommand("dev.homedj.app.PLAY_NEXT", Bundle.EMPTY)

        private fun queueSlotKey(player: Player): String? =
            if (player.mediaItemCount == 0) null
            else player.getMediaItemAt(0).mediaMetadata.extras?.getString(SLOT_KEY)

        /** A scheduled queue may only play inside its own slot instance; a person's queue always may. */
        suspend fun queueMayPlayNow(app: Context, player: Player): Boolean {
            val key = queueSlotKey(player) ?: return true
            val active = SchedulePolicy.active(ScheduleStore.shared(app).read(), ZonedDateTime.now())
            return MusicBreakPolicy.mayResume(key, active?.second)
        }
    }
}
