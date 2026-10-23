package dev.homedj.app

import org.json.JSONObject

/**
 * What Samsung does when the OnePlus comes home, kept in Lights automation beside `awayOff`/`homeOn`:
 * [LightsAutomation.homeScene] is applied (instead of restoring lights) and [LightsAutomation.homePlaylist]
 * starts freshly shuffled (instead of resuming the paused song) unless music is already playing.
 */
internal object HomeArrival {
    /** Scene key prefix for arrival music: each arrival is a new key, so the playlist reshuffles every time. */
    const val MUSIC_KEY = "arrival"

    /**
     * LAN/local `lightsPresence`. `homeScene` and `homePlaylist` are optional: an older OnePlus never
     * sends them and so cannot erase them; an empty string clears. Null: invalid request.
     */
    fun edit(snapshot: LightsSnapshot, request: JSONObject): LightsSnapshot? {
        if (request.opt("awayOff") !is Boolean || request.opt("homeOn") !is Boolean ||
            listOf("homeScene", "homePlaylist").any { request.has(it) && request.opt(it) !is String }) return null
        val old = snapshot.automation
        val scene = if (request.has("homeScene")) request.optString("homeScene").ifEmpty { null } else old.homeScene
        val playlist = if (request.has("homePlaylist")) request.optString("homePlaylist").ifEmpty { null } else old.homePlaylist
        if (scene != null && snapshot.scenes.none { it.id == scene }) return null
        if (playlist != null && !SchedulePolicy.validPlaylistId(playlist)) return null
        val awayOff = request.optBoolean("awayOff")
        return snapshot.copy(automation = old.copy(awayOff = awayOff, homeOn = awayOff && request.optBoolean("homeOn"),
            homeScene = scene, homePlaylist = playlist))
    }
}
