package dev.homedj.app

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the queue does after Media3 gives up on loading the current item. */
internal sealed interface RecoveryAction {
    data class Retry(val delayMillis: Long, val refreshLink: Boolean) : RecoveryAction
    data object Skip : RecoveryAction
    data object Stop : RecoveryAction
}

internal enum class PlaybackFailure { NETWORK, LINK_REJECTED, OTHER }

/**
 * Metrolist-style queue recovery: back off on network loss (3, 6, 12 s), refresh a rejected
 * signed link once, otherwise skip the track; stop after repeated skips so an outage cannot
 * burn through a long queue.
 */
internal object PlaybackRecoveryPolicy {
    const val MAX_NETWORK_RETRIES = 3
    const val MAX_CONSECUTIVE_SKIPS = 5

    fun classify(errorCode: Int, cause: Throwable?): PlaybackFailure {
        if (generateSequence(cause) { it.cause }.any { it is TrackUnavailableException }) {
            return PlaybackFailure.OTHER
        }
        return when {
            errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackFailure.LINK_REJECTED
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                generateSequence(cause) { it.cause }.any {
                    it is UnknownHostException || it is ConnectException ||
                        it is NoRouteToHostException || it is SocketTimeoutException
                } -> PlaybackFailure.NETWORK
            else -> PlaybackFailure.OTHER
        }
    }

    fun decide(failure: PlaybackFailure, retries: Int, consecutiveSkips: Int, hasNext: Boolean): RecoveryAction =
        when {
            failure == PlaybackFailure.NETWORK && retries < MAX_NETWORK_RETRIES ->
                RecoveryAction.Retry(minOf(3_000L shl retries, 30_000L), refreshLink = false)
            failure == PlaybackFailure.LINK_REJECTED && retries == 0 ->
                RecoveryAction.Retry(0L, refreshLink = true)
            hasNext && consecutiveSkips < MAX_CONSECUTIVE_SKIPS -> RecoveryAction.Skip
            else -> RecoveryAction.Stop
        }
}

/** Applies [PlaybackRecoveryPolicy] to the service's sole player; runs on the player's main thread. */
internal class PlaybackRecovery(
    private val player: Player,
    private val resolver: HomeDjMediaResolver,
    private val scope: CoroutineScope,
) : Player.Listener {
    private var retries = 0
    private var consecutiveSkips = 0
    private var pending: Job? = null

    override fun onPlayerError(error: PlaybackException) {
        val failure = PlaybackRecoveryPolicy.classify(error.errorCode, error.cause)
        val item = player.currentMediaItemIndex
        val action = PlaybackRecoveryPolicy.decide(failure, retries, consecutiveSkips, player.hasNextMediaItem())
        Log.w("HomeDJPlayback", "Track failed: code=" + error.errorCodeName + " failure=" + failure +
            " retries=" + retries + " skips=" + consecutiveSkips + " action=" + action)
        pending?.cancel()
        pending = when (action) {
            is RecoveryAction.Retry -> scope.launch {
                delay(action.delayMillis)
                if (player.playerError == null || player.currentMediaItemIndex != item) return@launch
                if (action.refreshLink) currentVideoId()?.let(resolver::invalidate)
                retries++
                player.prepare()
            }
            RecoveryAction.Skip -> scope.launch {
                if (player.playerError == null || player.currentMediaItemIndex != item) return@launch
                consecutiveSkips++
                player.seekToNextMediaItem()
                player.prepare()
            }
            RecoveryAction.Stop -> null
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        retries = 0
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            retries = 0
            consecutiveSkips = 0
        }
    }

    private fun currentVideoId(): String? =
        player.currentMediaItem?.localConfiguration?.uri?.toString()?.let(HomeDjMediaResolver::videoIdFromUri)
}
