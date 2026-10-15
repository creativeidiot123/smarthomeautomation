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
            ownerKey = "a".repeat(64),
        )
        assertEquals(saved, SavedPlaylistCodec.decode(SavedPlaylistCodec.encode(saved)))
    }

    @Test fun unsupportedSchemaFailsInsteadOfSilentlyOverwriting() {
        assertThrows(IllegalArgumentException::class.java) {
            SavedPlaylistCodec.decode("""{"version":2,"owned":[]}""")
        }
    }

    @Test fun accountFingerprintIsDeterministicAcrossCase() {
        val key = SavedPlaylistStore.accountKey("USER@EXAMPLE.COM")
        assertEquals(key, SavedPlaylistStore.accountKey("user@example.com"))
        assertEquals(64, key.length)
    }

    @Test fun oldUnscopedSnapshotHasNoAccountIdentity() {
        val restored = SavedPlaylistCodec.decode("""{"version":1,"owned":[],"imported":[],"tracks":{}}""")
        assertEquals(null, restored.ownerKey)
    }

    @Test fun omittedPlaylistDoesNotRestoreOrphanTracks() {
        val parsed = SavedPlaylistCodec.decode(
            """{"version":1,"owned":[],"imported":[],"tracks":{"missing":[{"id":"abc","title":"song"}]}}"""
        )
        assertEquals(emptyMap<String, List<YouTubeTrack>>(), parsed.tracks)
    }

    @Test fun publicLinkImportIsKeptFirstAndSurvivesRestore() {
        val key = SavedPlaylistStore.accountKey("someone@example.com")
        val before = SavedPlaylistCatalog(
            owned = listOf(YouTubePlaylist("PL_Own", "Home", 1)),
            tracks = mapOf("PL_Own" to listOf(YouTubeTrack("dQw4w9WgXcQ", "Song"))),
            ownerKey = key,
        )
        val linked = LinkedPlaylist(YouTubePlaylist("PL_Link", "Party", 1),
            listOf(YouTubeTrack("kJQP7kiw5Fk", "Despacito")))
        val after = SavedPlaylistCodec.decode(SavedPlaylistCodec.encode(before.withPublic(linked)))
        assertEquals(listOf("PL_Link", "PL_Own"), after.playlists.map(YouTubePlaylist::id))
        assertEquals(linked.tracks, after.tracks["PL_Link"])
        assertEquals(before.tracks["PL_Own"], after.tracks["PL_Own"])
        assertEquals(key, after.ownerKey)

        val refreshed = after.withPublic(linked.copy(tracks = emptyList()))
        assertEquals(1, refreshed.imported.size)
        assertEquals(emptyList<YouTubeTrack>(), refreshed.tracks["PL_Link"])
    }
}
