package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeResponseParserTest {
    @Test fun playlistParserPreservesPrivatePlaylistMetadataAndPageToken() {
        val page = YouTubeResponseParser.playlists(
            """
            {"nextPageToken":"next% token","items":[
              {"id":"p1","snippet":{"title":"Private mix"},"contentDetails":{"itemCount":42}},
              {"id":"","snippet":{"title":"Bad"}}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf(YouTubePlaylist("p1", "Private mix", 42)), page.items)
        assertEquals("next% token", page.nextToken)
    }

    @Test fun trackParserSkipsUnavailableVideosAndHandlesTerminalPage() {
        val page = YouTubeResponseParser.tracks(
            """
            {"items":[
              {"snippet":{"title":"Song","resourceId":{"videoId":"v1"}}},
              {"snippet":{"title":"Deleted video"}},
              {"snippet":{"resourceId":{"videoId":"v2"}}}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf(YouTubeTrack("v1", "Song")), page.items)
        assertNull(page.nextToken)
    }

    @Test(expected = org.json.JSONException::class)
    fun malformedResponseDoesNotSilentlyLookLikeAnEmptyLibrary() {
        YouTubeResponseParser.playlists("""{"unexpected":"response"}""")
    }
}
