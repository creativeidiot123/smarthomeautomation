package dev.homedj.app

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SongSubstituteTest {
    @get:Rule val temporary = TemporaryFolder()

    private val topic = YouTubeTrack("5DF9aRuiOZo", "Animals", "Maroon 5", 231)
    private fun hit(id: String, title: String, uploader: String = "Maroon5VEVO", seconds: Int = 231) =
        SearchHit(id, title, uploader, seconds)
    private fun link(id: String) = NativeAudioSource.Link("https://test.googlevideo.com/$id", "m4a", 140)

    @Test fun theSameSongUploadedElsewhereIsAcceptedAndOtherVersionsAreNot() {
        val hits = listOf(
            hit("cover000001", "Animals - Maroon 5 (Acoustic Cover)", "Some Singer"),
            hit("live0000001", "Maroon 5 - Animals (Live at Wembley)"),
            hit("long0000001", "Maroon 5 - Animals (Official Music Video)", seconds = 271),
            hit("5DF9aRuiOZo", "Animals"),
            hit("topic000001", "Animals", "Maroon 5 - Topic"),
            hit("other000001", "Maroon 5 - Sugar"),
            hit("lyrics00001", "Maroon 5 - Animals (Lyrics)", "Lyric Hub", seconds = 233),
            hit("audio000001", "Maroon 5 - Animals (Official Audio)", seconds = 230),
        )
        // Artist named in title or channel first, then the closest length.
        assertEquals(listOf("audio000001", "lyrics00001"), SongSubstitute.pick(topic, hits).map { it.id })
    }

    @Test fun aVersionWordInTheSongItselfIsNotARejection() {
        val acoustic = YouTubeTrack("aaaaaaaaaaa", "Photograph (Acoustic)", "Ed Sheeran", 250)
        assertEquals(listOf("bbbbbbbbbbb"), SongSubstitute.pick(acoustic,
            listOf(hit("bbbbbbbbbbb", "Ed Sheeran - Photograph Acoustic", "Ed Sheeran", 252))).map { it.id })
    }

    @Test fun songsWithoutAKnownLengthOrTitleAreNeverSubstituted() {
        val hits = listOf(hit("audio000001", "Maroon 5 - Animals (Official Audio)"))
        assertTrue(SongSubstitute.pick(topic.copy(durationSeconds = 0), hits).isEmpty())
        assertTrue(SongSubstitute.pick(topic.copy(title = "(Official)"), hits).isEmpty())
    }

    @Test fun lengthToleranceGrowsWithLongSongs() {
        assertEquals(10, SongSubstitute.toleranceSeconds(120))
        assertEquals(36, SongSubstitute.toleranceSeconds(600))
    }

    @Test fun queriesUseTheBareTitleAndArtist() {
        assertEquals("Pani Da Rang Ayushmann Khurrana",
            SongSubstitute.query(YouTubeTrack("aaaaaaaaaaa", "Pani Da Rang (Male Vocals)", "Ayushmann Khurrana - Topic", 260)))
        assertEquals("Animals", SongSubstitute.cleanTitle("Animals feat. Someone [Remastered]"))
        assertEquals(listOf("tum", "hi", "ho", "bandhu"), SongSubstitute.words("Tum Hi Ho, Bandhu!"))
    }

    @Test fun aRefusedSongResolvesTheFirstMatchThatYouTubeServes() = runBlocking {
        val resolved = mutableListOf<String>()
        var searches = 0
        val substitutes = mutableListOf<String>()
        val resolve = SongSubstitute.resolver(topic,
            resolve = { id ->
                resolved += id
                if (id == topic.id || id == "audio000001") throw TrackUnavailableException("ContentNotAvailableException")
                listOf(link(id))
            },
            search = { query ->
                searches++
                assertEquals("Animals Maroon 5", query)
                listOf(hit("audio000001", "Maroon 5 - Animals (Official Audio)"),
                    hit("lyrics00001", "Maroon 5 - Animals (Lyrics)", "Lyric Hub"))
            },
            onSubstitute = { substitutes += it })
        assertEquals(listOf(link("lyrics00001")), resolve(topic.id))
        // A link refresh in the same attempt re-resolves the chosen upload without searching again.
        assertEquals(listOf(link("lyrics00001")), resolve(topic.id))
        assertEquals(listOf(topic.id, "audio000001", "lyrics00001", "lyrics00001"), resolved)
        assertEquals(1, searches)
        assertEquals(listOf("lyrics00001"), substitutes)
    }

    @Test fun botChecksAndUnmatchedSongsFailAsBefore() = runBlocking {
        val bot = SongSubstitute.resolver(topic, resolve = { throw BotCheckException("SignInConfirmNotBotException") },
            search = { throw AssertionError("A bot check searched") })
        try {
            bot(topic.id)
            throw AssertionError("A bot check was substituted")
        } catch (_: BotCheckException) {
        }
        val refusal = TrackUnavailableException("ContentNotAvailableException")
        val unmatched = SongSubstitute.resolver(topic, resolve = { throw refusal },
            search = { listOf(hit("other000001", "Maroon 5 - Sugar")) })
        try {
            unmatched(topic.id)
            throw AssertionError("An unrelated upload was used")
        } catch (failure: TrackUnavailableException) {
            assertTrue(failure === refusal)
        }
        val offline = SongSubstitute.resolver(topic, resolve = { throw refusal },
            search = { throw IOException("Search failed: ParsingException") })
        try {
            offline(topic.id)
            throw AssertionError("A failed search reported success")
        } catch (failure: IOException) {
            assertEquals("ParsingException", OfflineAudioLibrary.failureDetail(failure))
            assertTrue(DownloadBackup.wantsLater(failure, automatic = false, backup = false))
        }
    }

    @Test fun theSubstituteIsSavedUnderTheRefusedSongsId() = runBlocking {
        val folder = temporary.newFolder()
        val file = OfflineAudioLibrary.transfer(folder, topic.id, false, authorized = { true },
            resolve = SongSubstitute.resolver(topic,
                resolve = { id ->
                    if (id == topic.id) throw TrackUnavailableException("ContentNotAvailableException")
                    listOf(link(id))
                },
                search = { listOf(hit("audio000001", "Maroon 5 - Animals (Official Audio)")) }),
            fetch = { url, partial, _ ->
                assertTrue(url.endsWith("audio000001"))
                partial.writeBytes(byteArrayOf(5))
                1L
            })
        assertEquals(File(folder, "5DF9aRuiOZo.m4a"), file)
        assertArrayEquals(byteArrayOf(5), file.readBytes())
    }
}
