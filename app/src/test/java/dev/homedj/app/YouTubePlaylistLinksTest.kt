package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePlaylistLinksTest {
    @Test fun importsMusicAndRegularPlaylistLinks() {
        assertEquals(
            "PL_abc-123",
            YouTubePlaylistLinks.idFromLink("https://music.youtube.com/playlist?list=PL_abc-123"),
        )
        assertEquals(
            "OLAK5uy_123",
            YouTubePlaylistLinks.idFromLink("https://www.youtube.com/watch?v=xyz&list=OLAK5uy_123"),
        )
        assertEquals(
            "PL_abc-123",
            YouTubePlaylistLinks.idFromLink("https://youtube.com/playlist?list=PL_abc%2D123"),
        )
    }

    @Test fun rejectsUntrustedDomainsAndNonPlaylistUrls() {
        for (url in listOf(
            "https://youtube.com.evil.example/playlist?list=PL_abc",
            "https://evil.example/?list=PL_abc",
            "http://youtube.com/playlist?list=PL_abc",
            "https://youtube.com:8443/playlist?list=PL_abc",
            "https://user@youtube.com/playlist?list=PL_abc",
            "https://youtube.com/channel/UC123?list=PL_abc",
            "https://youtube.com/playlist?v=abc",
            "https://youtube.com/playlist?list=wrong%2Fid",
            "https://youtube.com/playlist?list=%ZZ",
            "",
        )) {
            assertNull("Should reject: " + url, YouTubePlaylistLinks.idFromLink(url))
        }
    }

    @Test fun rejectsOversizedAndWhitespaceIdentifiers() {
        assertNull(YouTubePlaylistLinks.idFromLink("https://youtube.com/playlist?list=AB%20CD"))
        assertNull(
            YouTubePlaylistLinks.idFromLink(
                "https://youtube.com/playlist?list=" + "X".repeat(200),
            )
        )
    }
}
