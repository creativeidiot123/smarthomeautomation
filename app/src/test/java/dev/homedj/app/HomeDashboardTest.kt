package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDashboardTest {
    private val now = 10 * HOUR
    private val wallNow = 1_700_000_000_000L
    private val playing = ServerPlaybackUiState(connected = true,
        snapshot = PlaybackSnapshot(title = "Song", artist = "Artist", isPlaying = true, hasTrack = true))
    private val on = MusicBreakSettings(enabled = true)

    private fun server(seen: OnePlusSeen, remoteRunning: Boolean = true, nextBreak: NextBreak? = null,
                       breakResumesAt: Long? = null) =
        serverDashboard(playing, on, breakResumesAt, nextBreak, remoteRunning, seen, now, wallNow)

    @Test fun samsungSeesOnePlusOnlineOnlyWhileItsRequestsAreRecent() {
        assertEquals(DashboardRow("Online", "Connected over Wi-Fi", true),
            server(OnePlusSeen(lanAt = now - ONEPLUS_ONLINE_MILLIS)).onePlus)
        assertEquals(DashboardRow("Offline", "Last connected 2 min ago", false),
            server(OnePlusSeen(lanAt = now - 2 * MINUTE)).onePlus)
        assertEquals(DashboardRow("Offline", "Not connected yet", false), server(OnePlusSeen()).onePlus)
        assertEquals("Unknown", server(OnePlusSeen(lanAt = now), remoteRunning = false).onePlus.value)
    }

    @Test fun samsungCallsOnePlusAwayOnlyWhenItsHomeDetectionIsOn() {
        // A beacon counts as home even when the LAN went quiet.
        assertEquals(DashboardRow("Home", "Seen 4 min ago", true),
            server(OnePlusSeen(lanAt = now - HOUR, nearAt = now - 4 * MINUTE, detecting = true)).home)
        assertEquals(DashboardRow("Away", "Last seen 5 min ago"),
            server(OnePlusSeen(nearAt = now - PresencePolicy.SAMSUNG_ABSENT_MILLIS, detecting = true)).home)
        // Without detection a quiet OnePlus may just have its app closed.
        assertEquals("Unknown", server(OnePlusSeen(lanAt = now - HOUR)).home.value)
        assertEquals("Unknown", server(OnePlusSeen()).home.value)
        assertEquals("Unknown", server(OnePlusSeen(lanAt = now), remoteRunning = false).home.value)
    }

    @Test fun samsungShowsBreakCountdownsFromItsScheduler() {
        assertEquals(DashboardRow("On a break", "Music returns in 2:05"),
            server(OnePlusSeen(), breakResumesAt = wallNow + 125_000).breaks)
        assertEquals(DashboardRow("Not on a break", "Next break in 9:00, after that song ends"),
            server(OnePlusSeen(), nextBreak = NextBreak(10 * MINUTE, now - MINUTE, counting = true)).breaks)
        assertEquals(DashboardRow("Not on a break", "Next break when this song ends"),
            server(OnePlusSeen(), nextBreak = NextBreak(0, now, counting = false)).breaks)
    }

    @Test fun breakRowCoversEveryState() {
        assertEquals("Update HomeDJ on Samsung to use music breaks", breakRow(null, 0, null, true).detail)
        assertEquals("Off", breakRow(false, 0, 60, true).value)
        assertEquals("On a break", breakRow(true, 1, 60, false).value)
        assertEquals("The next break is timed once music plays", breakRow(true, 0, null, false).detail)
        assertEquals("1:00 of music left before the next break", breakRow(true, 0, 60, false).detail)
    }

    @Test fun onePlusCountsDownBetweenPollsOnlyWhileSamsungPlays() {
        val polled = RemoteUiState(host = "192.168.1.2", connected = true, playing = true, hasTrack = true,
            title = "Song", breaks = on, nextBreakSeconds = 600, positionAt = now - 30_000)
        val presenceOff = PresenceUiState()
        assertEquals("Next break in 9:30, after that song ends",
            controllerDashboard(polled, presenceOff, now, wallNow).breaks.detail)
        assertEquals("10:00 of music left before the next break",
            controllerDashboard(polled.copy(playing = false), presenceOff, now, wallNow).breaks.detail)
        // Only Samsung decides the session ran out; a late poll never shows "this song" early.
        assertEquals("Next break in 0:01, after that song ends",
            controllerDashboard(polled.copy(positionAt = now - HOUR), presenceOff, now, wallNow).breaks.detail)
    }

    @Test fun onePlusShowsSamsungLinkAndItsOwnPresence() {
        val connected = RemoteUiState(host = "192.168.1.2", connected = true)
        val detecting = PresenceUiState(enabled = true,
            status = PresenceStatus(running = true, presence = Presence.AWAY))
        assertEquals(DashboardRow("Online", "Connected over Wi-Fi", true),
            controllerDashboard(connected, PresenceUiState(), now, wallNow).samsung)
        assertEquals(DashboardRow("Offline", "Not reachable on this Wi-Fi", false),
            controllerDashboard(connected.copy(connected = false), PresenceUiState(), now, wallNow).samsung)
        assertEquals("Not paired", controllerDashboard(RemoteUiState(), PresenceUiState(), now, wallNow).samsung.value)
        assertEquals("Away", controllerDashboard(connected, detecting, now, wallNow).home.value)
        assertEquals("Home", controllerDashboard(connected, PresenceUiState(), now, wallNow).home.value)
        assertEquals("Unknown",
            controllerDashboard(connected.copy(connected = false), PresenceUiState(), now, wallNow).home.value)
        assertEquals("Samsung is offline",
            controllerDashboard(connected.copy(connected = false), PresenceUiState(), now, wallNow).song.detail)
    }

    @Test fun songRowShowsPlayStateAndArtist() {
        assertEquals(DashboardRow("Song", "Playing · Artist"), server(OnePlusSeen()).song)
        assertEquals(DashboardRow("Nothing playing"),
            serverDashboard(ServerPlaybackUiState(connected = true), on, null, null, true, OnePlusSeen(), now, wallNow).song)
    }

    @Test fun agoRoundsDown() {
        assertEquals("just now", ago(59_999))
        assertEquals("1 min ago", ago(MINUTE))
        assertEquals("2 h ago", ago(2 * HOUR + 59 * MINUTE))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
