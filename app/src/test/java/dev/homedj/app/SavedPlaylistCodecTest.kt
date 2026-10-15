package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SavedPlaylistCodecTest {
    @Test fun roundTripPreservesImportedAndOwnedMetadata() {
        val old = YouTubePlaylist("PL_Own", "Home", 2)
        val imported = YouTubePlaylist("PL_Share", "Guest", 1)
        val saved = SavedPlaylistCatalog(
            owned = listOf(old),
            imported = listOf(imported),
            tracks = mapOf(
                old.id to listOf(YouTubeTrack("abc", "My song")),
                imported.id to listOf(YouTubeTrack("def", "Guest song")),
            ),
            syncedAt = 123L,
        )
        assertEquals(saved, SavedPlaylistCodec.decode(SavedPlaylistCodec.encode(saved)))
    }

    @Test fun unsupportedSchemaFailsInsteadOfSilentlyOverwriting() {
        assertThrows(IllegalArgumentException::class.java) {
            SavedPlaylistCodec.decode("""{"version":2,"owned":[]}""")
        }
    }

    @Test fun omittedPlaylistDoesNotRestoreOrphanTracks() {
        val parsed = SavedPlaylistCodec.decode(
            """{"version":1,"owned":[],"imported":[],"tracks":{"missing":[{"id":"abc","title":"song"}]}}"""
        )
        assertEquals(emptyMap<String, List<YouTubeTrack>>(), parsed.tracks)
    }
}
