package dev.homedj.app

import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MatterPairingTest {
    @Test fun nativeStartupAndFailureCleanupCannotBlockTheCallingMainThread() = runBlocking {
        val caller = Thread.currentThread()
        val calls = mutableListOf<String>()
        val code = awaitMatterPairing(CompletableDeferred(50L), CompletableDeferred(), 5_000,
            startPairing = {
                assertNotSame(caller, Thread.currentThread())
                calls.add("start")
            },
            stopPairing = {
                assertNotSame(caller, Thread.currentThread())
                calls.add("stop")
            },
        ) { error("No attestation") }
        assertEquals(50L, code)
        assertEquals(listOf("start", "stop"), calls)
    }

    @Test fun successfulPairingIsNotStoppedAndStartupFailuresStillCleanUp() = runBlocking {
        var stops = 0
        assertEquals(0L, awaitMatterPairing(CompletableDeferred(0L), CompletableDeferred(), 5_000,
            stopPairing = { stops++ }) {})
        assertEquals(0, stops)
        val expected = IllegalStateException("Startup failed")
        val failure = try {
            awaitMatterPairing(CompletableDeferred(), CompletableDeferred(), 5_000,
                startPairing = { throw expected }, stopPairing = { stops++ }) {}
            null
        } catch (failure: Exception) {
            failure
        }
        assertTrue(failure is IllegalStateException)
        assertEquals(expected.message, failure?.message)
        assertEquals(1, stops)
    }

    @Test fun cancellationStopsNativePairingOnceOffTheCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        val started = CompletableDeferred<Unit>()
        var stops = 0
        val pairing = launch {
            awaitMatterPairing(CompletableDeferred(), CompletableDeferred(), 5_000,
                startPairing = { started.complete(Unit) },
                stopPairing = {
                    assertNotSame(caller, Thread.currentThread())
                    stops++
                }) {}
        }
        started.await()
        pairing.cancel()
        pairing.join()
        assertTrue(pairing.isCancelled)
        assertEquals(1, stops)
    }

    @Test fun completedCommissioningPreservesSuccessAndSdkErrors() = runBlocking {
        listOf(0L, 50L).forEach { code ->
            assertEquals(code, awaitMatterPairing(CompletableDeferred(code), CompletableDeferred(), 5_000) {
                error("Completed commissioning must not continue again")
            })
        }
    }

    @Test fun missingBulbBecomesFailureInsteadOfSilentCancellationAndAllowsAnotherAttempt() = runBlocking {
        val missing = CompletableDeferred<Long>()
        val failure = try {
            // Zero is an already-expired deadline: no real-time wait or scheduler race.
            awaitMatterPairing(missing, CompletableDeferred(), 0) { error("No attestation received") }
            null
        } catch (failure: Exception) {
            failure
        }
        assertTrue("A pairing deadline must reach the error handler", failure is TimeoutException)
        assertFalse(failure is CancellationException)
        assertEquals(0L, awaitMatterPairing(CompletableDeferred(0L), CompletableDeferred(), 5_000) {})
    }

    @Test fun sdkSearchEndingFailsPromptlyAsTimeoutAndStopsOnce() = runBlocking {
        val result = CompletableDeferred<Long>()
        val started = CompletableDeferred<Unit>()
        var stops = 0
        val waiter = async {
            try {
                // A deadline far beyond the test proves the SDK status, not ours, ends the wait.
                awaitMatterPairing(result, CompletableDeferred(), 600_000,
                    startPairing = { started.complete(Unit) }, stopPairing = { stops++ }) { error("No attestation") }
                null
            } catch (failure: Exception) {
                failure
            }
        }
        started.await()
        failMatterPairingOnStatus(result, 0) // SecurePairingSuccess: commissioning continues
        assertFalse(result.isCompleted)
        failMatterPairingOnStatus(result, 1) // SecurePairingFailed
        assertFalse("A later SDK code cannot replace the reported failure", result.complete(0L))
        val failure = withTimeout(5_000) { waiter.await() }
        assertTrue(failure is TimeoutException)
        assertFalse(failure is CancellationException)
        assertEquals(1, stops)
    }

    @Test fun cancellingTheOwnerStillCancelsTheWait() = runBlocking {
        val result = CompletableDeferred<Long>()
        var cancelled = false
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitMatterPairing(result, CompletableDeferred(), 5_000) { error("No attestation received") }
            } catch (failure: CancellationException) {
                cancelled = true
                throw failure
            }
        }
        waiter.cancel()
        waiter.join()
        assertTrue(cancelled)
        assertTrue(waiter.isCancelled)
    }

    @Test fun cancellationFromTheCallbackRemainsCancellation() = runBlocking {
        val cancelled = CancellationException("Owner stopped")
        val result = CompletableDeferred<Long>().also { it.cancel(cancelled) }
        val failure = try {
            awaitMatterPairing(result, CompletableDeferred(), 5_000) { error("Cancelled commissioning") }
            null
        } catch (failure: Exception) {
            failure
        }
        assertTrue(failure is CancellationException)
        assertEquals(cancelled.message, failure?.message)
    }

    @Test fun attestationContinuesOnceWithoutReenteringTheNativeCallbackThread() = runBlocking {
        val result = CompletableDeferred<Long>()
        val attestation = CompletableDeferred<Long>()
        val nativeCallbackThread = Thread.currentThread()
        val stackLock = Any()
        var calls = 0
        // Unconfined deliberately exposes inline callback resumption if the IO handoff is removed.
        val waiter = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            awaitMatterPairing(result, attestation, 5_000) { pointer ->
                assertNotSame("SDK continuation must leave the native callback thread",
                    nativeCallbackThread, Thread.currentThread())
                synchronized(stackLock) {
                    assertEquals(42L, pointer)
                    calls++
                    result.complete(0L)
                }
            }
        }
        // The native SDK invokes attestation while holding its non-recursive stack lock.
        synchronized(stackLock) {
            assertTrue(attestation.complete(42L))
            assertFalse(attestation.complete(43L))
        }
        assertEquals(0L, waiter.await())
        assertEquals(1, calls)
    }

    @Test fun cancelledPairingDoesNotContinueFromALateAttestation() = runBlocking {
        val result = CompletableDeferred<Long>()
        val attestation = CompletableDeferred<Long>()
        var calls = 0
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitMatterPairing(result, attestation, 5_000) { calls++ }
        }
        waiter.cancel()
        waiter.join()
        attestation.complete(42L)
        assertEquals(0, calls)
        assertTrue(waiter.isCancelled)
    }
}
