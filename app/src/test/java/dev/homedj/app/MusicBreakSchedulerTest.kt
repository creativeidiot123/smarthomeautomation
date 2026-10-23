package dev.homedj.app

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBreakSchedulerTest {
    @Test fun timerExpiryLetsTheEntireSongFinishBeforeTheFullBreak() = withFixture {
        advance(MUSIC)
        assertTrue(pauseAtEnd)
        advance(90_000) // The song is longer than the music interval.
        assertTrue(playing)
        assertEquals(1f, volume, 0f)
        assertEquals(0, pauses)
        assertFalse(breaks.onBreak)
        assertNull(MusicBreaks.resumeAt.value)
        assertFalse(wakeHeld)

        finishSong()
        assertTrue(breaks.onBreak)
        assertFalse(pauseAtEnd)
        assertFalse(playing)
        assertTrue(wakeHeld)
        assertEquals(now + SILENCE, MusicBreaks.resumeAt.value)
        advance(SILENCE - 1)
        assertFalse(playing)
        advance(1)
        assertTrue(playing)
        assertEquals(1, plays)
        assertFalse(breaks.onBreak)
        assertFalse(wakeHeld)
        advance(1_000)
        assertEquals(1f, volume, 0f)
    }

    @Test fun bufferingDoesNotConsumeOrResetMusicTime() = withFixture {
        advance(MUSIC / 2)
        changePlaying(false)
        advance(MUSIC * 2)
        assertFalse(pauseAtEnd)
        changePlaying(true)
        advance(MUSIC / 2)
        assertTrue(pauseAtEnd)
        changePlaying(false)
        changePlaying(true)
        advance(MUSIC)
        assertTrue(pauseAtEnd)
        assertEquals(0, pauses)
    }

    @Test fun userPauseAndCastCancelAPendingBreak() = withFixture {
        advance(MUSIC)
        changePlaying(false)
        breaks.onUserPause() // UserCommandPlayer also routes cast's pause here.
        assertFalse(pauseAtEnd)
        finishSong()
        advance(SILENCE)
        assertFalse(breaks.onBreak)
        assertEquals(0, plays)
    }

    @Test fun aPickedSongWhileABreakIsDueKeepsTheBreakForItsEnd() = withFixture {
        advance(MUSIC)
        breaks.onUserPlay() // The Play that follows a picked song while music plays.
        assertTrue(pauseAtEnd)
        advance(MUSIC * 2)
        assertEquals(0, pauses)
        finishSong()
        assertTrue(breaks.onBreak)
        assertEquals(1, pauses)
    }

    @Test fun playDuringABreakEndsItAndStartsAFreshSession() = withFixture {
        advance(MUSIC)
        finishSong()
        breaks.onUserPlay()
        changePlaying(true)
        assertFalse(breaks.onBreak)
        assertNull(MusicBreaks.resumeAt.value)
        advance(MUSIC - 1)
        assertFalse(pauseAtEnd)
        advance(1)
        assertTrue(pauseAtEnd)
    }

    @Test fun disablingBreaksAndSlotRestartClearTheSongEndPause() = withFixture {
        advance(MUSIC)
        breaks.update(settings.copy(enabled = false))
        assertFalse(pauseAtEnd)
        breaks.update(settings)
        advance(MUSIC)
        assertTrue(pauseAtEnd)
        breaks.restart()
        assertFalse(pauseAtEnd)
        finishSong()
        assertFalse(breaks.onBreak)
    }

    @Test fun repeatedEndCallbacksStartOnlyOneBreakAndPauseCancelsResume() = withFixture {
        advance(MUSIC)
        finishSong()
        val resumeAt = MusicBreaks.resumeAt.value
        finishSong()
        assertEquals(resumeAt, MusicBreaks.resumeAt.value)
        assertEquals(1, pauses)
        breaks.onUserPause()
        advance(SILENCE * 2)
        assertEquals(0, plays)
        assertFalse(wakeHeld)
        assertNull(MusicBreaks.resumeAt.value)
    }

    @Test fun changedSlotOrActiveCastPreventsAutomaticResume() = withFixture {
        advance(MUSIC)
        finishSong()
        resumeAllowed = false
        advance(SILENCE)
        assertEquals(0, plays)
        assertFalse(playing)
        assertFalse(wakeHeld)
        assertEquals(1, holdReleases)
    }

    @Test fun serviceTeardownCancelsTimersAndReleasesTheBreakHold() = withFixture {
        advance(MUSIC)
        finishSong()
        close()
        advance(SILENCE * 2)
        assertEquals(0, plays)
        assertFalse(wakeHeld)
        assertNull(MusicBreaks.resumeAt.value)
    }

    @Test fun nextBreakCountsMusicOnlyWhilePlayingAndClearsDuringTheBreak() = withFixture {
        assertEquals(NextBreak(MUSIC, 0, counting = true), MusicBreaks.nextBreak.value)
        advance(MUSIC / 2)
        changePlaying(false)
        assertEquals(NextBreak(MUSIC / 2, now, counting = false), MusicBreaks.nextBreak.value)
        advance(MUSIC)
        assertEquals(MUSIC / 2_000, MusicBreakPolicy.secondsUntilBreak(requireNotNull(MusicBreaks.nextBreak.value), now))
        changePlaying(true)
        advance(MUSIC / 2)
        assertEquals(0L, MusicBreakPolicy.secondsUntilBreak(requireNotNull(MusicBreaks.nextBreak.value), now))
        finishSong()
        assertNull(MusicBreaks.nextBreak.value)
        advance(SILENCE)
        assertEquals(NextBreak(MUSIC, now, counting = true), MusicBreaks.nextBreak.value)
        breaks.update(settings.copy(enabled = false))
        assertNull(MusicBreaks.nextBreak.value)
    }

    private fun withFixture(test: Fixture.() -> Unit) {
        val fixture = Fixture()
        try {
            fixture.test()
        } finally {
            fixture.close()
        }
    }

    /** Models Media3's documented pause before advancing to the next item, without Android I/O. */
    private class Fixture {
        private data class Wait(val due: Long, val continuation: CancellableContinuation<Unit>)
        private val waits = mutableListOf<Wait>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = 0L
        var playing = true
        var playWhenReady = true
        var pauseAtEnd = false
        var volume = 1f
        var pauses = 0
        var plays = 0
        var wakeHeld = false
        var holdReleases = 0
        var resumeAllowed = true
        val settings = MusicBreakSettings(enabled = true, musicMin = 5, musicMax = 5,
            longMusicChance = 0, silenceMin = 1, silenceMax = 1, longSilenceChance = 0,
            fadeMin = 1, fadeMax = 1)
        private val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "getPauseAtEndOfMediaItems" -> pauseAtEnd
                "setPauseAtEndOfMediaItems" -> { pauseAtEnd = args[0] as Boolean; null }
                "isPlaying" -> playing
                "getPlayWhenReady" -> playWhenReady
                "getPlaybackState" -> Player.STATE_READY
                "getCurrentMediaItem" -> MediaItem.EMPTY
                "setVolume" -> { volume = args[0] as Float; null }
                "pause" -> { pauses++; playWhenReady = false; changePlaying(false); null }
                "play" -> { plays++; playWhenReady = true; changePlaying(true); null }
                else -> error("Unexpected player call: " + method.name)
            }
        } as ExoPlayer
        val breaks = MusicBreaks(player, scope,
            acquireWakeLock = { wakeHeld = true }, releaseWakeLock = { wakeHeld = false },
            onHoldReleased = { holdReleases++ }, canResume = { resumeAllowed }, random = Random(1),
            pauseAtEnd = { pauseAtEnd }, setPauseAtEnd = { pauseAtEnd = it },
            resumeWithFade = { player.play() },
            now = { now }, waitMillis = { millis ->
                suspendCancellableCoroutine { waits += Wait(now + millis, it) }
            })

        init { breaks.update(settings) }

        fun changePlaying(value: Boolean) {
            if (playing == value) return
            playing = value
            breaks.onIsPlayingChanged(value)
        }

        fun finishSong() {
            playWhenReady = false
            changePlaying(false)
            breaks.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        }

        fun advance(millis: Long) {
            val end = now + millis
            while (true) {
                val next = waits.filter { it.continuation.isActive && it.due <= end }.minByOrNull { it.due }
                    ?: break
                waits.remove(next)
                now = next.due
                next.continuation.resume(Unit)
            }
            now = end
        }

        fun close() {
            scope.cancel()
            breaks.release()
        }
    }

    private companion object {
        const val MUSIC = 5 * 60_000L
        const val SILENCE = 60_000L
    }
}
