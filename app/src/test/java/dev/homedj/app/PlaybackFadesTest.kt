package dev.homedj.app

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFadesTest {
    @Test fun manualCrossfadeBringsTheIncomingSongUpQuicklyWithSteadyCombinedPower() = fixture {
        switch()
        var previousIncoming = 0f
        var previousOutgoing = 1f
        for (step in 1..60) {
            advance(50)
            assertTrue(b.volume >= previousIncoming)
            assertTrue(a.volume <= previousOutgoing)
            assertEquals(1f, a.volume * a.volume + b.volume * b.volume, 0.00001f)
            if (step == 1) assertTrue("Incoming song must ease in gently", b.volume < 0.021f)
            if (step == 10) assertTrue("Incoming song must be audible within half a second", b.volume >= 0.22f)
            if (step == 30) assertEquals(a.volume, b.volume, 0.00001f)
            if (step == 59) assertTrue("Outgoing song must ease out gently", a.volume < 0.021f)
            previousIncoming = b.volume
            previousOutgoing = a.volume
        }
        assertEquals(1f, b.volume, 0f)
        assertEquals(0f, a.volume, 0f)
    }

    @Test fun automaticCrossfadeIsAudibleEarlyAndFinishesWithinFourSeconds() = fixture {
        fades.discardSpare()
        fades.switchToPrepared(PlaybackFades.SONG_MILLIS)
        advance(500)
        assertTrue("Incoming song must be audible within half a second", b.volume >= 0.16f)
        assertTrue(a.playing && b.playing)
        advance(500)
        assertTrue("Incoming song must not linger near silence", b.volume >= 0.34f)
        advance(3_000)
        assertEquals(1f, b.volume, 0f)
        assertFalse(a.playing)
        assertFalse(fades.transitioning)
    }

    @Test fun shortAutomaticOverlapStillReachesFullVolumeAtTheSongEnd() = fixture {
        fades.discardSpare()
        fades.switchToPrepared(1_000)
        advance(500)
        assertEquals(0.707f, b.volume, 0.002f)
        advance(500)
        assertEquals(1f, b.volume, 0f)
        assertFalse(a.playing)
        assertFalse(fades.transitioning)
    }

    @Test fun songsReallyOverlapAndRetireTheOldDeck() = fixture {
        switch()
        assertSame(b.player, fades.active)
        assertTrue(a.playing)
        assertEquals(0f, b.volume, 0f)
        advance(1_500)
        assertTrue(a.playing && b.playing)
        assertEquals(0.707f, a.volume, 0.002f)
        assertEquals(0.707f, b.volume, 0.002f)
        advance(1_500)
        assertFalse(a.playing)
        assertTrue(a.cleared)
        assertEquals(1f, b.volume, 0f)
        assertFalse(fades.transitioning)
    }

    @Test fun bufferingKeepsTheOldSongAudibleUntilTheNewOneStarts() = fixture {
        b.ready = false
        switch()
        advance(3_000)
        assertEquals(1f, a.volume, 0f)
        assertEquals(0f, b.volume, 0f)
        b.ready = true
        advance(1_550)
        assertTrue(a.volume in 0.6f..0.8f && b.volume in 0.6f..0.8f)
    }

    @Test fun failedOrStalledIncomingCannotLeaveAnUnboundedOldQueuePlaying() = fixture {
        b.ready = false
        switch()
        advance(15_000)
        assertFalse(a.playing)
        assertTrue(a.cleared)
        assertFalse(fades.transitioning)
        b.ready = true
        assertEquals(1f, b.volume, 0f)
    }

    @Test fun pauseDuringOverlapFadesBothAndNeverRestartsThem() = fixture {
        switch()
        advance(2_000)
        val beforeA = a.volume
        val beforeB = b.volume
        fades.pause()
        assertTrue(fades.pausing)
        advance(2_500)
        assertEquals(beforeA / 2, a.volume, 0.002f)
        assertEquals(beforeB / 2, b.volume, 0.002f)
        advance(30_000)
        assertFalse(a.playing || b.playing)
        assertEquals(0f, a.volume, 0f)
        assertEquals(0f, b.volume, 0f)
    }

    @Test fun playDuringPauseReversesSmoothlyFromCurrentVolume() = fixture {
        fades.pause()
        advance(2_500)
        assertEquals(0.5f, a.volume, 0.001f)
        fades.play()
        assertEquals(0.5f, a.volume, 0.001f)
        advance(5_000)
        assertTrue(a.playing)
        assertFalse(fades.pausing)
        assertEquals(1f, a.volume, 0f)
        advance(20_000)
        assertTrue(a.playing)
    }

    @Test fun duplicatePauseDoesNotExtendTheFade() = fixture {
        fades.pause()
        advance(4_000)
        fades.pause()
        advance(1_000)
        assertFalse(a.playing)
    }

    @Test fun rapidSongChangesKeepOnlyTheLatestTwoDecks() = fixture {
        switch()
        advance(2_000)
        fades.discardSpare()
        a.cleared = false // A third song is now configured on the retired deck.
        fades.switchToPrepared()
        advance(3_000)
        assertSame(a.player, fades.active)
        assertFalse(a.cleared)
        assertTrue(a.playing)
        assertFalse(b.playing)
        assertTrue(b.cleared)
    }

    @Test fun castingFocusLossAndRoleStopSilenceBothWithoutDelayedResume() = fixture {
        switch()
        advance(1_000)
        fades.silence()
        advance(30_000)
        assertFalse(a.playing || b.playing)
        assertEquals(0f, a.volume, 0f)
        assertEquals(0f, b.volume, 0f)
        fades.play(1_000) // The same seam applies the configured music-break fade.
        advance(500)
        assertEquals(0.5f, b.volume, 0.001f)
        advance(500)
        assertEquals(1f, b.volume, 0f)
    }

    @Test fun preloadingDoesNotCancelTheCurrentFadeIn() = fixture {
        fades.silence()
        fades.play()
        advance(1_000)
        fades.discardSpare()
        advance(4_000)
        assertEquals(1f, a.volume, 0f)
    }

    @Test fun breakFadeLetsTheWholeSongFinishAndCanBeCancelled() = fixture {
        fades.fadeAtEnd(5_000)
        advance(2_500)
        assertTrue(a.playing)
        assertEquals(0.5f, a.volume, 0.001f)
        fades.cancelEndFade()
        advance(5_000)
        assertTrue(a.playing)
        assertEquals(1f, a.volume, 0f)
        fades.fadeAtEnd(1_000)
        advance(1_000)
        assertTrue(a.playing) // Media3, rather than a timer, decides when the song has ended.
        assertEquals(0f, a.volume, 0f)
        fades.pause()
        advance(5_000)
        assertFalse(a.playing)
    }

    @Test fun serviceReleaseCancelsBothDecksAndAllEnvelopes() = fixture {
        switch()
        advance(1_000)
        fades.release()
        val volumes = a.volume to b.volume
        advance(30_000)
        assertTrue(a.released && b.released)
        assertEquals(volumes, a.volume to b.volume)
    }

    @Test fun aPausedSongChangeWaitsForExplicitPlayAndThenFadesIn() = fixture {
        fades.silence()
        switch()
        advance(20_000)
        assertFalse(a.playing || b.playing)
        fades.play()
        assertEquals(0f, b.volume, 0f)
        advance(5_000)
        assertTrue(b.playing)
        assertEquals(1f, b.volume, 0f)
    }

    @Test fun duplicatePlayDoesNotResetAnOverlapOrItsVolumes() = fixture {
        switch()
        advance(1_500)
        val volumes = a.volume to b.volume
        fades.play()
        assertEquals(volumes, a.volume to b.volume)
        advance(1_500)
        assertFalse(a.playing)
        assertEquals(1f, b.volume, 0f)
    }

    @Test fun masterVolumeScalesBothDecksWithoutChangingTheEnvelope() = fixture {
        fades.volume = 0.4f
        switch()
        advance(1_500)
        assertEquals(0.283f, a.volume, 0.002f)
        assertEquals(0.283f, b.volume, 0.002f)
        advance(1_500)
        assertEquals(0.4f, b.volume, 0.001f)
    }

    private fun fixture(test: Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.test() } finally { fixture.close() }
    }

    private class Deck {
        var ready = true
        var requested = false
        val playing: Boolean get() = ready && requested && !released
        var volume = 1f
        var cleared = false
        var released = false
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                "isPlaying" -> playing
                "getPlayWhenReady" -> requested
                "setPlayWhenReady" -> { requested = args[0] as Boolean; null }
                "getPlayerError" -> null
                "play" -> { requested = true; null }
                "pause", "stop" -> { requested = false; null }
                "setVolume" -> { check(!released); volume = args[0] as Float; null }
                "clearMediaItems" -> { cleared = true; null }
                "release" -> { released = true; null }
                else -> error("Unexpected call: " + method.name)
            }
        } as Player
    }

    private class Fixture {
        private data class Wait(val due: Long, val continuation: CancellableContinuation<Unit>)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val waits = mutableListOf<Wait>()
        private var now = 0L
        val a = Deck().apply { requested = true }
        val b = Deck()
        val fades = PlaybackFades(a.player, b.player, scope, changed = {}, waitMillis = { millis ->
            suspendCancellableCoroutine { waits += Wait(now + millis, it) }
        })

        fun switch() { fades.discardSpare(); b.cleared = false; fades.switchToPrepared() }

        fun advance(millis: Long) {
            val end = now + millis
            while (true) {
                val next = waits.filter { it.continuation.isActive && it.due <= end }.minByOrNull { it.due } ?: break
                waits.remove(next)
                now = next.due
                next.continuation.resume(Unit)
            }
            now = end
        }

        fun close() { scope.cancel() }
    }
}
