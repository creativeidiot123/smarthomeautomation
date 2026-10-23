package dev.homedj.app

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The OnePlus's own verdict. UNKNOWN only while signals are still arriving after a start. */
enum class Presence { UNKNOWN, HOME, AWAY }

/** Last geofence transition from Play services, stored by name in PresenceStore. */
enum class GeofenceState { UNKNOWN, INSIDE, OUTSIDE }

/**
 * Pure presence rules for both phones. All times are `elapsedRealtime`, so sleep counts and
 * wall-clock changes do not.
 */
internal object PresencePolicy {
    /** A LAN reply or beacon this recent counts as a green signal. */
    const val FRESH_MILLIS = 3 * 60_000L
    /** Home needs this many of the three signals: same Wi-Fi as Samsung, its beacon, GPS. */
    const val HOME_SIGNALS = 2
    /** Samsung pauses once the OnePlus has been missing this long. */
    const val SAMSUNG_ABSENT_MILLIS = 5 * 60_000L
    const val SAMSUNG_ROLE = "samsung"
    const val ONEPLUS_ROLE = "oneplus"
    private const val BEACON_BYTES = 8

    /** Green signals: Samsung answered on Wi-Fi recently, its beacon was seen recently, inside the geofence. */
    fun homeSignals(lanAt: Long?, beaconAt: Long?, geofence: GeofenceState, now: Long): Int =
        listOf(fresh(lanAt, now), fresh(beaconAt, now), geofence == GeofenceState.INSIDE).count { it }

    /**
     * Two of three green signals mean home, fewer mean away. Right after a start the LAN probe
     * and beacon have not had time to report, so [previous] holds for one freshness window.
     */
    fun next(previous: Presence, signals: Int, startedAt: Long, now: Long): Presence = when {
        signals >= HOME_SIGNALS -> Presence.HOME
        now - startedAt < FRESH_MILLIS -> previous
        else -> Presence.AWAY
    }

    fun fresh(at: Long?, now: Long): Boolean = at != null && now - at <= FRESH_MILLIS

    /** Samsung pauses once per absence, and only for a OnePlus it heard from that asked for it. */
    fun samsungShouldPause(autoPause: Boolean, seenAt: Long?, absenceHandled: Boolean, now: Long): Boolean =
        autoPause && seenAt != null && !absenceHandled && now - seenAt >= SAMSUNG_ABSENT_MILLIS

    /**
     * Resume only once the OnePlus's own vote says home, only the song presence paused, and
     * only if nobody has pressed play since. A chosen [arrivalPlaylist] plays instead (Lights.presence).
     */
    fun samsungShouldResume(home: Boolean, pausedId: String?, playWhenReady: Boolean, currentId: String?,
                            arrivalPlaylist: Boolean = false): Boolean =
        home && !arrivalPlaylist && pausedId != null && !playWhenReady && currentId == pausedId

    /**
     * BLE payload naming one phone of a pair. An HMAC of the pairing key, so it reveals nothing
     * about the key and a different pair never matches.
     */
    fun beaconId(key: SecretKeySpec, role: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.encoded, "HmacSHA256"))
            doFinal("homedj-presence-v1:$role".toByteArray(Charsets.UTF_8))
        }.copyOf(BEACON_BYTES)
}
