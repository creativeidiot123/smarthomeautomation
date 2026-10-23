package dev.homedj.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightSendEachTest {
    @Test fun everyLightIsSentAtOnce() = runBlocking {
        // Light 1 can only finish once light 2 was sent: one after another it would time out.
        val second = CompletableDeferred<Unit>()
        val done = acknowledgedEach(listOf(1L, 2L), 2_000L, failed = { _, _ -> }) { id ->
            if (id == 1L) second.await() else second.complete(Unit)
        }
        assertEquals(setOf(1L, 2L), done)
    }

    @Test fun anUnreachableLightCostsOnlyItsOwnTimeout() = runBlocking {
        val failures = mutableListOf<Pair<Long, Exception?>>()
        val started = System.nanoTime()
        val done = acknowledgedEach(listOf(1L, 2L, 3L), 200L, failed = { id, failure -> failures += id to failure }) { id ->
            if (id == 2L) awaitCancellation()
        }
        assertEquals(setOf(1L, 3L), done)
        assertEquals(listOf(2L to null), failures)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test fun aFailingLightIsReportedAndTheOthersStillCount() = runBlocking {
        val failures = mutableListOf<Pair<Long, Exception?>>()
        val refused = MatterLights.MatterFailure(0x87)
        val done = acknowledgedEach(listOf(1L, 2L), 1_000L, failed = { id, failure -> failures += id to failure }) { id ->
            if (id == 1L) throw refused
        }
        assertEquals(setOf(2L), done)
        assertEquals(listOf(1L to refused), failures)
    }

    @Test fun cancellingTheCallerCancelsEverySend() = runBlocking {
        val failures = mutableListOf<Long>()
        val sending = async { acknowledgedEach(listOf(1L, 2L), 60_000L, failed = { id, _ -> failures += id }) { awaitCancellation() } }
        yield()
        sending.cancel()
        val result = runCatching { sending.await() }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(emptyList<Long>(), failures)
    }

    @Test fun eachLightKeepsItsOwnSceneChangeAsItsIntent() {
        val config = LightsConfiguration(individual = mapOf(1L to LightPreferences(LightChange(level = 25), awayOff = false)))
        val changed = LightsGroups.individualChanged(config, mapOf(
            1L to LightChange(color = LightColor.Hue(320, 59)), 2L to LightChange(level = 40, fadeSeconds = 5)))
        assertEquals(LightPreferences(LightChange(level = 25, color = LightColor.Hue(320, 59)), awayOff = false), changed.individual[1L])
        assertEquals(LightPreferences(LightChange(on = true, level = 40)), changed.individual[2L])
    }
}
