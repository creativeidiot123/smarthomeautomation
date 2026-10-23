package dev.homedj.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightLiveSenderTest {
    @Test fun dragSendsOneAtATimeAndEndsOnTheReleasedValue() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<Int>()
        var settled = 0
        val sender = LightLiveSender<Int>(this, { value -> sent += value; if (value == 10) gate.await() }) { settled++ }
        sender.offer("level", 10)
        yield()
        listOf(20, 35, 60).forEach { sender.offer("level", it) }
        assertEquals(listOf(10), sent)
        assertTrue(sender.busy && sender.hasPending)
        gate.complete(Unit)
        while (sender.busy) yield()
        assertEquals(listOf(10, 60), sent)
        assertEquals(1, settled)
        assertFalse(sender.hasPending)
    }

    @Test fun lastTouchedControlIsAppliedLast() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val sender = LightLiveSender<String>(this, { value -> sent += value; if (value == "level 10") gate.await() }) {}
        sender.offer("level", "level 10")
        yield()
        sender.offer("power", "on")
        sender.offer("level", "level 60")
        sender.offer("power", "off")
        gate.complete(Unit)
        while (sender.busy) yield()
        assertEquals(listOf("level 10", "level 60", "off"), sent)
    }

    @Test fun idleWaitsForEveryQueuedValueSoASaveLandsAfterThePreview() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val sender = LightLiveSender<String>(this, { value -> sent += value; if (value == "preview 1") gate.await() }) {}
        sender.offer("preview", "preview 1")
        yield()
        sender.offer("preview", "preview 2")
        val save = launch { sender.idle(); sent += "save" }
        yield()
        assertEquals(listOf("preview 1"), sent)
        gate.complete(Unit)
        save.join()
        assertEquals(listOf("preview 1", "preview 2", "save"), sent)
    }

    @Test fun cancelDropsQueuedValues() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<Int>()
        var settled = 0
        val sender = LightLiveSender<Int>(this, { value -> sent += value; gate.await() }) { settled++ }
        sender.offer("level", 10)
        yield()
        sender.offer("level", 60)
        sender.cancel()
        yield()
        assertEquals(listOf(10), sent)
        assertFalse(sender.busy || sender.hasPending)
        assertEquals(0, settled)
    }
}
