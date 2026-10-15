package dev.homedj.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Artist, length and cover details: parsed from YouTube, saved, and read back by older and newer builds. */
class SongDetailsTest {
    @Test fun trackParserReadsTheArtistWithoutTheTopicSuffix() {
        val page = YouTubeResponseParser.tracks(
            """
            {"items":[
              {"snippet":{"title":"Song","resourceId":{"videoId":"v1"},"videoOwnerChannelTitle":"Band - Topic"}},
              {"snippet":{"title":"Other","resourceId":{"videoId":"v2"}}}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf(YouTubeTrack("v1", "Song", "Band"), YouTubeTrack("v2", "Other")), page.items)
    }

    @Test fun videoLengthsComeFromIsoDurations() {
        val lengths = YouTubeResponseParser.durations(
            """{"items":[{"id":"a","contentDetails":{"duration":"PT3M5S"}},
                {"id":"b","contentDetails":{"duration":"P0D"}},{"id":"c"}]}"""
        )
        assertEquals(mapOf("a" to 185), lengths)
        assertEquals(3_723, isoDurationSeconds("PT1H2M3S"))
        assertEquals(60, isoDurationSeconds("PT1M"))
        assertEquals(0, isoDurationSeconds("P1DT2H"))
        assertEquals(0, isoDurationSeconds("garbage"))
        assertEquals(MAX_TRACK_SECONDS, isoDurationSeconds("PT999H"))
    }

    @Test fun playlistCoverIsKeptOnlyFromYouTubesImageHost() {
        val page = YouTubeResponseParser.playlists(
            """
            {"items":[
              {"id":"p1","snippet":{"title":"Mix","thumbnails":{"medium":{"url":"https://i.ytimg.com/vi/x/mqdefault.jpg"}}}},
              {"id":"p2","snippet":{"title":"Odd","thumbnails":{"medium":{"url":"https://evil.example/x.jpg"}}}}
            ]}
            """.trimIndent()
        )
        assertEquals("https://i.ytimg.com/vi/x/mqdefault.jpg", page.items[0].artworkUrl)
        assertEquals("", page.items[1].artworkUrl)
    }

    @Test fun onlyHttpsYouTubeImagesAreAccepted() {
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", youTubeImageUrl(" https://i.ytimg.com/vi/x/hq.jpg "))
        assertEquals("https://i9.ytimg.com/x.jpg", youTubeImageUrl("https://i9.ytimg.com/x.jpg"))
        assertNull(youTubeImageUrl("http://i.ytimg.com/x.jpg"))
        assertNull(youTubeImageUrl("https://ytimg.com.evil.example/x.jpg"))
        assertNull(youTubeImageUrl("https://user@i.ytimg.com/x.jpg"))
        assertNull(youTubeImageUrl("file:///data/x.jpg"))
        assertNull(youTubeImageUrl(null))
    }

    @Test fun detailsSurviveTheSavedSnapshotRoundTrip() {
        val playlist = YouTubePlaylist("PL1", "Mix", 1, "https://i.ytimg.com/vi/x/mqdefault.jpg")
        val catalog = SavedPlaylistCatalog(
            imported = listOf(playlist),
            tracks = mapOf("PL1" to listOf(YouTubeTrack("dQw4w9WgXcQ", "Song", "Rick Astley", 213))),
        )
        assertEquals(catalog, SavedPlaylistCodec.decode(SavedPlaylistCodec.encode(catalog)))
    }

    @Test fun aSnapshotFromBeforeDetailsStillLoads() {
        val old = """{"version":1,"ownerKey":null,"syncedAt":5,"owned":[],
            "imported":[{"id":"PL1","title":"Mix","count":1}],
            "tracks":{"PL1":[{"id":"dQw4w9WgXcQ","title":"Song"}]}}"""
        val catalog = SavedPlaylistCodec.decode(old)
        assertEquals(YouTubePlaylist("PL1", "Mix", 1), catalog.imported.single())
        assertEquals(YouTubeTrack("dQw4w9WgXcQ", "Song"), catalog.tracks["PL1"]?.single())
    }

    @Test fun unknownDetailsAreNotWrittenAndBadValuesAreBounded() {
        val json = SavedPlaylistCodec.trackJson(YouTubeTrack("dQw4w9WgXcQ", "Song"))
        assertFalse(json.has("artist"))
        assertFalse(json.has("duration"))
        val parsed = SavedPlaylistCodec.parseTrack(
            JSONObject().put("id", "dQw4w9WgXcQ").put("title", "Song").put("duration", -4).put("artist", "x".repeat(500)))
        assertEquals(0, parsed?.durationSeconds)
        assertEquals(200, parsed?.artist?.length)
    }

    @Test fun lengthsAndDetailLinesReadNaturally() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("3:05", formatDuration(185))
        assertEquals("1:02:03", formatDuration(3_723))
        assertEquals("Band • 3:05", songDetails("Band", 185))
        assertEquals("Band", songDetails("Band", 0))
        assertEquals("3:05", songDetails(" ", 185))
        assertEquals("", songDetails("", 0))
    }

    @Test fun shownPositionMovesOnlyWhilePlayingAndStaysInsideTheSong() {
        assertEquals(1_500L, displayedPosition(1_000, at = 10_000, now = 10_500, playing = true, durationMs = 5_000))
        assertEquals(1_000L, displayedPosition(1_000, at = 10_000, now = 10_500, playing = false, durationMs = 5_000))
        assertEquals(5_000L, displayedPosition(4_900, at = 0, now = 9_000, playing = true, durationMs = 5_000))
        assertEquals(1_000L, displayedPosition(1_000, at = 10_000, now = 9_000, playing = true, durationMs = 5_000))
    }

    @Test fun playlistCountMentionsPartialDownloadsOnly() {
        assertEquals("", songCountText(0, 0))
        assertEquals("1 song", songCountText(1, 1))
        assertEquals("12 songs", songCountText(12, 0))
        assertEquals("12 songs • 5 downloaded", songCountText(12, 5))
        assertEquals("12 songs", songCountText(12, 12))
    }
}
