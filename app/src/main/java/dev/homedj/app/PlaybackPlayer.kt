package dev.homedj.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** One session/queue facade, two service-owned decks, and one audio-focus request for both. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PlaybackPlayer(
    private val app: Context,
    first: ExoPlayer,
    second: ExoPlayer,
    private val scope: CoroutineScope,
) : ForwardingSimpleBasePlayer(first) {
    private val fades = PlaybackFades(first, second, scope, changed = { next ->
        stagedIndex = C.INDEX_UNSET
        setPlayer(next)
    })
    private val audio = app.getSystemService(AudioManager::class.java)
    private var focusHeld = false
    private var focusRequested = false
    private var resumeAfterFocus = false
    private var released = false
    private var ticker: Job? = null
    private var stagedIndex = C.INDEX_UNSET
    var onSafetyPause: () -> Unit = {}
    private val deck: ExoPlayer get() = fades.active as ExoPlayer
    private val standby: ExoPlayer get() = fades.spare as ExoPlayer
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change ->
            if (!released && focusRequested) when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    focusHeld = true
                    if (resumeAfterFocus) { resumeAfterFocus = false; fades.play(); watchEnd() }
                }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeAfterFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT &&
                        deck.playWhenReady && !fades.pausing
                    focusHeld = false
                    stagedIndex = C.INDEX_UNSET
                    fades.silence()
                    onSafetyPause()
                    if (change == AudioManager.AUDIOFOCUS_LOSS) abandonFocus()
                }
            }
        }, Handler(first.applicationLooper)).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseImmediately()
        }
    }

    init {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (player !== fades.active && events.contains(Player.EVENT_PLAYER_ERROR)) {
                    Log.w("HomeDJ", "Crossfade preload failed: code=" + player.playerError?.errorCodeName)
                }
                if (player === fades.active) {
                    if (stagedIndex != C.INDEX_UNSET && (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                            events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                            events.contains(Player.EVENT_REPEAT_MODE_CHANGED))) {
                        fades.discardSpare()
                        stagedIndex = C.INDEX_UNSET
                    }
                    if (player.playbackState == Player.STATE_ENDED && !fades.transitioning) abandonFocus()
                    watchEnd()
                }
            }
        }
        first.addListener(listener)
        second.addListener(listener)
        // A system broadcast; API 29 is supported, without introducing an exported component.
        androidx.core.content.ContextCompat.registerReceiver(app, noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    var pauseAtEndOfMediaItems: Boolean
        get() = deck.pauseAtEndOfMediaItems
        set(value) {
            deck.pauseAtEndOfMediaItems = value
            if (value && !fades.transitioning) { fades.discardSpare(); stagedIndex = C.INDEX_UNSET }
            if (!value && deck.playWhenReady) fades.cancelEndFade()
        }

    fun setShuffleOrder(order: ShuffleOrder) { deck.setShuffleOrder(order) }

    override fun getState(): State {
        val state = super.getState()
        return state.buildUpon().setVolume(fades.volume).apply {
            if (fades.pausing) setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        }.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        resumeAfterFocus = false
        if (playWhenReady) {
            if (requestFocus()) { fades.play(); watchEnd() }
        } else {
            stagedIndex = C.INDEX_UNSET
            fades.pause()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    fun playWithFade(millis: Long) {
        resumeAfterFocus = false
        if (requestFocus()) { fades.play(millis); watchEnd() }
    }

    /** Casting and safety stops must silence both decks before returning. */
    fun pauseImmediately() {
        resumeAfterFocus = false
        stagedIndex = C.INDEX_UNSET
        fades.silence()
        onSafetyPause()
        abandonFocus()
        invalidateState()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        fades.volume = volume
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(items: List<MediaItem>, startIndex: Int, positionMs: Long): ListenableFuture<*> {
        stagedIndex = C.INDEX_UNSET
        if (items.isEmpty() || !deck.isPlaying || fades.pausing) {
            if (fades.pausing || items.isEmpty()) fades.silence()
            fades.discardSpare()
            return super.handleSetMediaItems(items, startIndex, positionMs)
        }
        fades.discardSpare()
        standby.shuffleModeEnabled = true
        standby.repeatMode = deck.repeatMode
        standby.playbackParameters = deck.playbackParameters
        standby.pauseAtEndOfMediaItems = deck.pauseAtEndOfMediaItems // A due music break moves to the picked song.
        if (startIndex == C.INDEX_UNSET) standby.setMediaItems(items)
        else standby.setMediaItems(items, startIndex, positionMs)
        deck.pauseAtEndOfMediaItems = true // The outgoing tail never advances to another song.
        fades.switchToPrepared()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(index: Int, positionMs: Long, command: Int): ListenableFuture<*> {
        if (index != C.INDEX_UNSET && index != deck.currentMediaItemIndex && deck.isPlaying && !fades.pausing) {
            val breakDue = deck.pauseAtEndOfMediaItems
            stage(index, positionMs)
            standby.pauseAtEndOfMediaItems = breakDue // A due music break moves to the skipped-to song.
            deck.pauseAtEndOfMediaItems = true
            fades.switchToPrepared()
            deck.prepare()
        } else {
            if (!fades.transitioning) { fades.discardSpare(); stagedIndex = C.INDEX_UNSET }
            super.handleSeek(index, positionMs, command)
        }
        if (deck.playerError != null) deck.prepare()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        resumeAfterFocus = false
        stagedIndex = C.INDEX_UNSET
        fades.stop()
        abandonFocus()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        ticker?.cancel()
        app.unregisterReceiver(noisy)
        abandonFocus()
        fades.release()
        return Futures.immediateVoidFuture()
    }

    private fun stage(index: Int, positionMs: Long = C.TIME_UNSET) {
        fades.discardSpare()
        val source = deck
        val target = standby
        target.pauseAtEndOfMediaItems = false
        target.repeatMode = source.repeatMode
        target.shuffleModeEnabled = source.shuffleModeEnabled
        target.playbackParameters = source.playbackParameters
        target.setMediaItems((0 until source.mediaItemCount).map(source::getMediaItemAt), index, positionMs)
        val order = mutableListOf<Int>()
        var next = source.currentTimeline.getFirstWindowIndex(true)
        while (next != C.INDEX_UNSET) {
            order += next
            next = source.currentTimeline.getNextWindowIndex(next, Player.REPEAT_MODE_OFF, true)
        }
        target.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
        target.volume = 0f
        target.prepare()
        stagedIndex = index
    }

    private fun watchEnd() {
        if (released || ticker != null || !deck.isPlaying || !deck.playWhenReady || fades.pausing) return
        ticker = scope.launch {
            try {
                while (deck.isPlaying && deck.playWhenReady && !fades.pausing) {
                    delay(200)
                    if (!deck.isPlaying || fades.transitioning) continue
                    val duration = deck.duration
                    val left = ((duration - deck.currentPosition) / deck.playbackParameters.speed).toLong()
                    val next = deck.nextMediaItemIndex
                    if (duration <= 0 || left <= 0) continue
                    if (deck.pauseAtEndOfMediaItems) {
                        if (left <= PlaybackFades.CONTROL_MILLIS) fades.fadeAtEnd(left)
                        continue
                    }
                    if (next == C.INDEX_UNSET) continue
                    if (left <= 20_000 && stagedIndex != next) stage(next)
                    if (left <= PlaybackFades.SONG_MILLIS && stagedIndex == next &&
                        standby.playbackState == Player.STATE_READY && standby.playerError == null
                    ) {
                        deck.pauseAtEndOfMediaItems = true
                        fades.switchToPrepared(left)
                    }
                }
            } finally { ticker = null }
        }
    }

    private fun requestFocus(): Boolean {
        if (focusHeld) return true
        focusRequested = true
        focusHeld = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusHeld) {
            focusRequested = false
            stagedIndex = C.INDEX_UNSET
            fades.silence()
            Log.w("HomeDJ", "Playback audio focus unavailable")
        }
        return focusHeld
    }

    private fun abandonFocus() {
        focusRequested = false
        audio.abandonAudioFocusRequest(focus)
        focusHeld = false
    }
}
