package dev.homedj.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxPlaylistDownloadsTest {
    @Test fun argumentsAreSafeAndUseFixedTermuxMusicDestination() {
        val args = TermuxPlaylistDownloads.arguments("PL_test-123").toList()
        assertEquals("https://www.youtube.com/playlist?list=PL_test-123", args.last())
        assertTrue(args.contains("--yes-playlist"))
        assertTrue(args.contains("--extract-audio"))
        assertEquals("/storage/emulated/0/Music/HomeDJ", args[args.indexOf("--paths") + 1])
        assertEquals("mp3", args[args.indexOf("--audio-format") + 1])
        assertFalse(args.contains("sh"))
        assertFalse(args.contains("-c"))
    }

    @Test fun refusesShellMetacharactersAndExternalUrls() {
        for (id in listOf(
            "", "A", "has space", "x;id", "x&whoami", "x/other", "https://evil.example",
            "x\nscript", "x%0A", "x${'$'}(id)", "X".repeat(151),
        )) {
            try {
                TermuxPlaylistDownloads.arguments(id)
                throw AssertionError("Accepted invalid ID")
            } catch (_: IllegalArgumentException) {
                // Only the intended validation failure is accepted.
            }
        }
    }

    @Test fun doesNotChangeThePlaylistUrlOrUseShell() {
        val expected = arrayOf(
            "--yes-playlist", "--ignore-errors", "--extract-audio",
            "--audio-format", "mp3", "--audio-quality", "5",
            "--no-overwrites", "--download-archive",
            "/storage/emulated/0/Music/HomeDJ/downloaded-ids.txt",
            "--paths", "/storage/emulated/0/Music/HomeDJ",
            "--output", "%(playlist_title).80B/%(playlist_index)03d - %(title).140B [%(id)s].%(ext)s",
            "https://www.youtube.com/playlist?list=OLAK5uy_123",
        )
        assertArrayEquals(expected, TermuxPlaylistDownloads.arguments("OLAK5uy_123"))
    }
}
