package dev.homedj.app

import androidx.media3.common.PlaybackException
import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    private val policy = PlaybackRecoveryPolicy

    @Test fun refusedTrackIsSkippedInsteadOfHaltingTheQueue() {
        val failure = policy.classify(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            IOException("wrapped", TrackUnavailableException("PrivateContentException")),
        )
        assertEquals(PlaybackFailure.OTHER, failure)
        assertEquals(RecoveryAction.Skip, policy.decide(failure, retries = 0, consecutiveSkips = 0, hasNext = true))
    }

    @Test fun networkLossBacksOffThenSkips() {
        val failure = policy.classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, UnknownHostException())
        assertEquals(PlaybackFailure.NETWORK, failure)
        assertEquals(PlaybackFailure.NETWORK,
            policy.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
        listOf(3_000L, 6_000L, 12_000L).forEachIndexed { retries, delay ->
            assertEquals(RecoveryAction.Retry(delay, refreshLink = false),
                policy.decide(failure, retries, consecutiveSkips = 0, hasNext = true))
        }
        assertEquals(RecoveryAction.Skip, policy.decide(failure, retries = 3, consecutiveSkips = 0, hasNext = true))
    }

    @Test fun rejectedSignedLinkIsRefreshedOnceThenSkipped() {
        val failure = policy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, null)
        assertEquals(PlaybackFailure.LINK_REJECTED, failure)
        assertEquals(RecoveryAction.Retry(0L, refreshLink = true),
            policy.decide(failure, retries = 0, consecutiveSkips = 0, hasNext = true))
        assertEquals(RecoveryAction.Skip, policy.decide(failure, retries = 1, consecutiveSkips = 0, hasNext = true))
    }

    @Test fun stopsAtQueueEndOrAfterRepeatedSkips() {
        assertEquals(RecoveryAction.Stop,
            policy.decide(PlaybackFailure.OTHER, retries = 0, consecutiveSkips = 0, hasNext = false))
        assertEquals(RecoveryAction.Skip,
            policy.decide(PlaybackFailure.OTHER, retries = 0, consecutiveSkips = 4, hasNext = true))
        assertEquals(RecoveryAction.Stop, policy.decide(PlaybackFailure.OTHER, retries = 0,
            consecutiveSkips = PlaybackRecoveryPolicy.MAX_CONSECUTIVE_SKIPS, hasNext = true))
    }
}
