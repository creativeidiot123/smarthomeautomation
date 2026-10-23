package dev.homedj.app

import android.content.Context

/** Centre and radius of the home geofence. Never leaves the OnePlus and never reaches Logcat. */
data class HomeArea(val latitude: Double, val longitude: Double, val radiusMeters: Float)

/**
 * OnePlus-only home-detection settings (SharedPreferences `homedj_presence`). Samsung keeps no
 * copy: each authenticated check-in carries `autoPause`. Not backed up (`allowBackup=false`).
 */
internal class PresenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("homedj_presence", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    /** Whether Samsung should pause when this phone leaves and resume when it is back. */
    var autoPause: Boolean
        get() = prefs.getBoolean("auto_pause", true)
        set(value) { prefs.edit().putBoolean("auto_pause", value).apply() }

    val home: HomeArea?
        get() {
            if (!prefs.contains("home_lat") || !prefs.contains("home_lng")) return null
            val latitude = Double.fromBits(prefs.getLong("home_lat", 0))
            val longitude = Double.fromBits(prefs.getLong("home_lng", 0))
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            return HomeArea(latitude, longitude, HOME_RADIUS_METERS)
        }

    /** A new centre makes the stored transition meaningless until Play services re-reports it. */
    fun saveHome(latitude: Double, longitude: Double) {
        prefs.edit()
            .putLong("home_lat", latitude.toRawBits())
            .putLong("home_lng", longitude.toRawBits())
            .putString("geofence", GeofenceState.UNKNOWN.name)
            .apply()
    }

    var geofence: GeofenceState
        get() = GeofenceState.entries.firstOrNull { it.name == prefs.getString("geofence", null) }
            ?: GeofenceState.UNKNOWN
        set(value) { prefs.edit().putString("geofence", value.name).apply() }

    companion object {
        /** Play services recommends at least 100–150 m; Wi-Fi fixes are typically 20–50 m off. */
        const val HOME_RADIUS_METERS = 150f
    }
}
