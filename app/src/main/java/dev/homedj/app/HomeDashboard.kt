package dev.homedj.app

/**
 * When Samsung last heard from the paired OnePlus (`elapsedRealtime`): [lanAt] for any
 * authenticated LAN request, [nearAt] for its beacon or `presence` check-in. [detecting] is true
 * once a OnePlus with home detection on checked in during this listener's life. Memory only.
 */
internal data class OnePlusSeen(val lanAt: Long? = null, val nearAt: Long? = null, val detecting: Boolean = false,
    val confirmedHomeAt: Long? = null)

/** One dashboard line. [good] colours its dot: true online/home, false offline, null neither. */
internal data class DashboardRow(val value: String, val detail: String = "", val good: Boolean? = null)

/** The live dashboard on both Home tabs; a projection rebuilt every second, never stored. */
internal data class DashboardUi(
    val samsung: DashboardRow,
    val onePlus: DashboardRow,
    val song: DashboardRow,
    val breaks: DashboardRow,
    val home: DashboardRow,
)

/** A OnePlus request this recent means online; its presence check-in runs every minute. */
internal const val ONEPLUS_ONLINE_MILLIS = 90_000L

/** Samsung's dashboard. [now] is `elapsedRealtime`; [wallNow] is only for [breakResumesAt]. */
internal fun serverDashboard(
    playback: ServerPlaybackUiState,
    breaks: MusicBreakSettings,
    breakResumesAt: Long?,
    nextBreak: NextBreak?,
    remoteRunning: Boolean,
    seen: OnePlusSeen,
    now: Long,
    wallNow: Long,
): DashboardUi {
    val snapshot = playback.snapshot
    val lanAt = seen.lanAt
    val nearAt = listOfNotNull(seen.lanAt, seen.nearAt).maxOrNull()
    return DashboardUi(
        samsung = DashboardRow("Online",
            if (remoteRunning) "This phone · remote control on" else "This phone · remote control off", true),
        onePlus = when {
            !remoteRunning -> DashboardRow("Unknown", "Turn on remote control in Settings")
            lanAt != null && now - lanAt <= ONEPLUS_ONLINE_MILLIS -> DashboardRow("Online", "Connected over Wi-Fi", true)
            lanAt != null -> DashboardRow("Offline", "Last connected " + ago(now - lanAt), false)
            else -> DashboardRow("Offline", "Not connected yet", false)
        },
        song = songRow(playback.connected, snapshot.hasTrack, snapshot.title, snapshot.artist, snapshot.isPlaying),
        breaks = breakRow(breaks.enabled, MusicBreakPolicy.secondsLeft(breakResumesAt, wallNow),
            nextBreak?.let { MusicBreakPolicy.secondsUntilBreak(it, now) }, snapshot.isPlaying),
        home = when {
            !remoteRunning -> DashboardRow("Unknown", "Turn on remote control in Settings")
            nearAt != null && now - nearAt < PresencePolicy.SAMSUNG_ABSENT_MILLIS ->
                DashboardRow("Home", "Seen " + ago(now - nearAt), true)
            nearAt != null && seen.detecting -> DashboardRow("Away", "Last seen " + ago(now - nearAt))
            nearAt != null -> DashboardRow("Unknown", "Last seen " + ago(now - nearAt) +
                " · turn on home detection on OnePlus")
            else -> DashboardRow("Unknown", "Turn on home detection on OnePlus")
        },
    )
}

/**
 * The OnePlus dashboard. Samsung's values are from the last poll at [RemoteUiState.positionAt];
 * the next-break countdown keeps moving between polls while music plays.
 */
internal fun controllerDashboard(remote: RemoteUiState, presence: PresenceUiState, now: Long, wallNow: Long): DashboardUi {
    val offline = DashboardRow("Unknown", "Samsung is offline")
    val nextBreak = remote.nextBreakSeconds?.let { seconds ->
        // Never reaches zero locally: only Samsung knows when the music session has run out.
        if (remote.playing && seconds > 0) (seconds - (now - remote.positionAt) / 1_000).coerceAtLeast(1)
        else seconds
    }
    val status = presence.status
    return DashboardUi(
        samsung = when {
            remote.connected -> DashboardRow("Online", "Connected over Wi-Fi", true)
            remote.host.isBlank() -> DashboardRow("Not paired", "Pair with Samsung in Settings")
            else -> DashboardRow("Offline", "Not reachable on this Wi-Fi", false)
        },
        onePlus = DashboardRow("Online", "This phone", true),
        song = if (!remote.connected) offline else
            songRow(true, remote.hasTrack, remote.title, remote.artist, remote.playing, remote.casting),
        breaks = if (!remote.connected) offline else breakRow(remote.breaks?.enabled,
            MusicBreakPolicy.secondsLeft(remote.breakResumesAt, wallNow), nextBreak, remote.playing),
        home = when {
            presence.enabled && status.running -> when (status.presence) {
                Presence.HOME -> DashboardRow("Home", "Home detection", true)
                Presence.AWAY -> DashboardRow("Away", "Home detection")
                Presence.UNKNOWN -> DashboardRow("Checking…", "Home detection")
            }
            remote.connected -> DashboardRow("Home", "On the same Wi-Fi as Samsung", true)
            else -> DashboardRow("Unknown", "Turn on home detection in Settings")
        },
    )
}

private fun songRow(
    connected: Boolean, hasTrack: Boolean, title: String, artist: String, playing: Boolean, casting: Boolean = false,
): DashboardRow = when {
    !connected -> DashboardRow("Unknown", "Connecting to the music player…")
    casting -> DashboardRow("Live audio from OnePlus", if (playing) "Playing" else "Paused")
    !hasTrack -> DashboardRow("Nothing playing")
    else -> DashboardRow(title, listOf(if (playing) "Playing" else "Paused", artist)
        .filter { it.isNotBlank() }.joinToString(" · "))
}

/**
 * [enabled] null means a Samsung build without breaks; [breakLeftSeconds] above zero means on a
 * break; [nextBreakSeconds] is music left, 0 meaning once the current song ends.
 */
internal fun breakRow(enabled: Boolean?, breakLeftSeconds: Long, nextBreakSeconds: Long?, playing: Boolean): DashboardRow =
    when {
        enabled == null -> DashboardRow("Unknown", "Update HomeDJ on Samsung to use music breaks")
        breakLeftSeconds > 0 -> DashboardRow("On a break", "Music returns in " + formatDuration(breakLeftSeconds))
        !enabled -> DashboardRow("Off", "Music plays without breaks")
        nextBreakSeconds == null -> DashboardRow("Not on a break", "The next break is timed once music plays")
        nextBreakSeconds == 0L -> DashboardRow("Not on a break", "Next break when this song ends")
        playing -> DashboardRow("Not on a break",
            "Next break in " + formatDuration(nextBreakSeconds) + ", after that song ends")
        else -> DashboardRow("Not on a break",
            formatDuration(nextBreakSeconds) + " of music left before the next break")
    }

/** "just now", "12 min ago" or "3 h ago". */
internal fun ago(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        else -> "${minutes / 60} h ago"
    }
}
