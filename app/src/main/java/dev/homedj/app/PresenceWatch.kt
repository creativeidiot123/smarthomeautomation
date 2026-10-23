package dev.homedj.app

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Samsung's view of whether the paired OnePlus is nearby, from the OnePlus beacon and its
 * authenticated `presence` check-ins. It pauses once when the OnePlus has been missing for
 * five minutes, and resumes only on an authenticated check-in, never on a beacon, so a
 * replayed advert can delay a pause but never start music. Memory only: owned by
 * RemoteServerService on Main and lost with it, which only delays the next pause.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PresenceWatch(context: Context) {
    private val app = context.applicationContext
    private val beacon = PresenceBeacon(context) { seenAt = SystemClock.elapsedRealtime() }
    /** Last OnePlus beacon or check-in (`elapsedRealtime`); also read by Samsung's dashboard. */
    var seenAt: Long? = null
        private set
    /** No radio use until a OnePlus with detection on has checked in during this service's life. */
    var checkedIn = false
        private set
    private var autoPause = false
    private var absenceHandled = false
    /** Media ID of the song presence paused; cleared on a home check-in, autoPause off, or play. */
    private var pausedId: String? = null
    /**
     * Lights and the arrival scene/playlist follow the same absence independently of music auto-pause;
     * Samsung's own Lights settings decide what happens. Set once per absence, cleared by a home check-in.
     */
    private var lightsAway = false
    /** Authenticated Home vote only; BLE proximity alone cannot satisfy a light-rule condition. */
    var confirmedHomeAt: Long? = null
        private set

    /** An authenticated check-in; returns the activity line to record, if any. */
    fun checkIn(request: JSONObject, player: Player, casting: Boolean): String? {
        seenAt = SystemClock.elapsedRealtime()
        checkedIn = true
        absenceHandled = false
        autoPause = request.optBoolean("autoPause", false)
        if (!autoPause) pausedId = null
        // On Wi-Fi but not yet voted home (e.g. the beacon is still being found): keep the
        // paused song so the next check-in, once the vote is home, can resume it.
        val home = request.optBoolean("home", false)
        confirmedHomeAt = if (home) SystemClock.elapsedRealtime() else null
        if (!home) return null
        if (lightsAway) {
            lightsAway = false
            LightsWork.presence(app, home = true)
        }
        val resume = !casting && PresencePolicy.samsungShouldResume(home, pausedId, player.playWhenReady,
            player.currentMediaItem?.mediaId, arrivalPlaylist = Lights.state(app).value.automation.homePlaylist != null)
        pausedId = null
        if (!resume) return null
        Util.handlePlayButtonAction(player)
        return "Resumed: OnePlus is home"
    }

    /** Runs every few seconds while the listener is up; returns the activity line to record, if any. */
    fun tick(key: SecretKeySpec?, player: Player?, casting: Boolean): String? {
        beacon.update(key.takeIf { checkedIn }, PresencePolicy.SAMSUNG_ROLE, PresencePolicy.ONEPLUS_ROLE)
        if (player?.playWhenReady == true) pausedId = null
        if (checkedIn && PresencePolicy.samsungShouldPause(true, seenAt, lightsAway, SystemClock.elapsedRealtime())) {
            lightsAway = true
            LightsWork.presence(app, home = false)
        }
        if (!PresencePolicy.samsungShouldPause(autoPause, seenAt, absenceHandled, SystemClock.elapsedRealtime())) {
            return null
        }
        absenceHandled = true
        if (player == null || casting || Util.shouldShowPlayButton(player)) return null
        Util.handlePauseButtonAction(player)
        pausedId = player.currentMediaItem?.mediaId
        return "Paused: OnePlus left home"
    }

    fun stop() = beacon.stop()
}
