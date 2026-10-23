package dev.homedj.app

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceRulesTest {
    private val minute = 60_000L
    private val start = 1_000_000L

    private val now = start + 60 * minute

    private fun signals(lanAgo: Long? = null, beaconAgo: Long? = null, geofence: GeofenceState = GeofenceState.UNKNOWN) =
        PresencePolicy.homeSignals(lanAgo?.let { now - it }, beaconAgo?.let { now - it }, geofence, now)

    @Test fun eachSignalCountsOnlyWhenGreen() {
        assertEquals(0, signals())
        assertEquals(1, signals(lanAgo = 3 * minute))
        assertEquals(0, signals(lanAgo = 4 * minute, beaconAgo = 4 * minute, geofence = GeofenceState.OUTSIDE))
        assertEquals(1, signals(geofence = GeofenceState.INSIDE))
        assertEquals(3, signals(lanAgo = 0, beaconAgo = minute, geofence = GeofenceState.INSIDE))
    }

    @Test fun anyTwoOfThreeGreenSignalsMeanHome() {
        listOf(
            signals(lanAgo = 0, beaconAgo = 0),                                    // Wi-Fi + Bluetooth
            signals(lanAgo = 0, geofence = GeofenceState.INSIDE),                  // Wi-Fi + GPS
            signals(beaconAgo = 0, geofence = GeofenceState.INSIDE),               // Bluetooth + GPS
            signals(lanAgo = 0, beaconAgo = 0, geofence = GeofenceState.INSIDE),   // all three
        ).forEach { assertEquals(Presence.HOME, PresencePolicy.next(Presence.AWAY, it, start, now)) }
    }

    @Test fun oneOrNoGreenSignalMeansAway() {
        listOf(
            signals(lanAgo = 0, geofence = GeofenceState.OUTSIDE),  // on Wi-Fi only
            signals(beaconAgo = 0),                                  // Bluetooth only
            signals(geofence = GeofenceState.INSIDE),                // GPS only
            signals(),
        ).forEach { assertEquals(Presence.AWAY, PresencePolicy.next(Presence.HOME, it, start, now)) }
    }

    @Test fun verdictHoldsWhileSignalsAreStillArrivingAfterAStart() {
        assertEquals(Presence.UNKNOWN, PresencePolicy.next(Presence.UNKNOWN, 1, start, start + 2 * minute))
        assertEquals(Presence.AWAY, PresencePolicy.next(Presence.UNKNOWN, 1, start, start + 3 * minute))
        assertEquals(Presence.HOME, PresencePolicy.next(Presence.UNKNOWN, 2, start, start))
    }

    @Test fun samsungPausesOnceAfterFiveMinutesForAPhoneThatAskedForIt() {
        val now = 100 * minute
        assertTrue(PresencePolicy.samsungShouldPause(true, now - 5 * minute, absenceHandled = false, now = now))
        assertFalse(PresencePolicy.samsungShouldPause(true, now - 4 * minute, absenceHandled = false, now = now))
        assertFalse(PresencePolicy.samsungShouldPause(true, now - 9 * minute, absenceHandled = true, now = now))
        assertFalse(PresencePolicy.samsungShouldPause(false, now - 9 * minute, absenceHandled = false, now = now))
        // Never heard from this phone since the listener started: nothing to have left.
        assertFalse(PresencePolicy.samsungShouldPause(true, null, absenceHandled = false, now = now))
    }

    @Test fun samsungResumesOnlyTheSongItPausedOnceTheOnePlusVotesHome() {
        assertTrue(PresencePolicy.samsungShouldResume(true, "song", playWhenReady = false, currentId = "song"))
        assertFalse(PresencePolicy.samsungShouldResume(false, "song", playWhenReady = false, currentId = "song"))
        assertFalse(PresencePolicy.samsungShouldResume(true, "song", playWhenReady = true, currentId = "song"))
        assertFalse(PresencePolicy.samsungShouldResume(true, "song", playWhenReady = false, currentId = "other"))
        assertFalse(PresencePolicy.samsungShouldResume(true, null, playWhenReady = false, currentId = "song"))
    }

    @Test fun beaconIdsAreStablePerPairAndRoleAndDifferAcrossPairs() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val other = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val samsung = PresencePolicy.beaconId(key, PresencePolicy.SAMSUNG_ROLE)
        assertEquals(8, samsung.size)
        assertTrue(samsung.contentEquals(PresencePolicy.beaconId(key, PresencePolicy.SAMSUNG_ROLE)))
        assertFalse(samsung.contentEquals(PresencePolicy.beaconId(key, PresencePolicy.ONEPLUS_ROLE)))
        assertFalse(samsung.contentEquals(PresencePolicy.beaconId(other, PresencePolicy.SAMSUNG_ROLE)))
        assertFalse(samsung.contentEquals(key.encoded.copyOf(8)))
    }

    @Test fun checkInCarriesOnlyTheCommandAutoPauseAndVote() {
        val request = presenceRequest(autoPause = false, home = true)
        assertEquals("presence", request.getString("command"))
        assertFalse(request.getBoolean("autoPause"))
        assertTrue(request.getBoolean("home"))
        assertEquals(setOf("command", "autoPause", "home"), request.keys().asSequence().toSet())
    }
}
