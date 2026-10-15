package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicArtworkTest {
    @Test fun artworkComesFromValidVideoIdsOnly() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", youTubeArtworkUrl("dQw4w9WgXcQ"))
        assertNull(youTubeArtworkUrl("../unsafe-ID"))
        assertNull(youTubeArtworkUrl("dQw4w9WgXcQ?x=1"))
        assertNull(youTubeArtworkUrl(""))
    }

    @Test fun largeArtworkOnlyUpgradesYouTubeThumbnails() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            largeArtworkUrl("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"))
        assertEquals("https://example.com/vi/x/mqdefault.jpg", largeArtworkUrl("https://example.com/vi/x/mqdefault.jpg"))
        assertEquals("content://media/1", largeArtworkUrl("content://media/1"))
    }
}
