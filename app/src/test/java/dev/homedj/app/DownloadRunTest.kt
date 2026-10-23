package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Samsung download notification's counts across workers starting, finishing and stops. */
class DownloadRunTest {
    @Test fun countsSongsAsTheyFinishThenSummarisesTheRun() {
        var run = DownloadRun().next(setOf("a", "b", "c"), emptySet())
        assertEquals("Downloading songs" to "0 of 3 done", run.notice())
        run = run.next(setOf("b", "c"), setOf("a"))
        assertEquals("Downloading songs" to "1 of 3 done", run.notice())
        // "b" failed for good: it has no file, so it is not counted as downloaded.
        run = run.next(setOf("c"), emptySet())
        run = run.next(emptySet(), setOf("c"))
        assertEquals("Downloads finished" to "2 downloaded songs • 1 not downloaded", run.notice())
    }

    @Test fun songsQueuedMidRunJoinTheCount() {
        val run = DownloadRun().next(setOf("a"), emptySet()).next(setOf("a", "b"), emptySet())
        assertEquals("Downloading songs" to "0 of 2 done", run.notice())
    }

    @Test fun aRunStoppedWithNothingSavedHidesTheNotification() {
        val run = DownloadRun().next(setOf("a", "b"), emptySet()).next(emptySet(), emptySet())
        assertNull(run.notice())
        assertEquals(setOf("a", "b"), run.songs)
    }

    @Test fun theNextSaveStartsAFreshRunAndOlderSongsDoNotCount() {
        val finished = DownloadRun().next(setOf("a"), emptySet()).next(emptySet(), setOf("a"))
        assertEquals("Downloads finished" to "1 downloaded song", finished.notice())
        val next = finished.next(setOf("b"), emptySet())
        assertEquals("Downloading songs" to "0 of 1 done", next.notice())
        // Stopping songs saved long ago (they have files) never reads as a new download.
        val stop = DownloadRun().next(emptySet(), setOf("old"))
        assertEquals(DownloadRun(), stop)
    }

    @Test fun progressPostsAtMostOncePerSecondButTheFinishedNoticeAlwaysPosts() {
        // A playlist's workers ended ~12 times a second and Android shed the updates.
        assertTrue(DownloadNotice.postDue(downloading = true, progressPostedAt = null, now = 5_000))
        assertFalse(DownloadNotice.postDue(downloading = true, progressPostedAt = 5_000, now = 5_080))
        assertFalse(DownloadNotice.postDue(downloading = true, progressPostedAt = 5_000, now = 5_999))
        assertTrue(DownloadNotice.postDue(downloading = true, progressPostedAt = 5_000, now = 6_000))
        assertTrue(DownloadNotice.postDue(downloading = false, progressPostedAt = 5_000, now = 5_010))
    }
}
