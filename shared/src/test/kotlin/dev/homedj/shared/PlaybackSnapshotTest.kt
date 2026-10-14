package dev.homedj.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSnapshotTest {
    @Test fun emptyPlayerDoesNotExposePlayOrPause() {
        val empty = PlaybackSnapshot()
        assertFalse(empty.canPlay)
        assertFalse(empty.canPause)
    }

    @Test fun aSelectedTrackOffersExactlyOnePrimaryAction() {
        val paused = PlaybackSnapshot(hasTrack = true)
        val playing = paused.copy(isPlaying = true)
        assertTrue(paused.canPlay)
        assertFalse(paused.canPause)
        assertFalse(playing.canPlay)
        assertTrue(playing.canPause)
    }
}
