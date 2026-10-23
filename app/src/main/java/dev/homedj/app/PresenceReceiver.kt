package dev.homedj.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/** The single home geofence. Play services drops it on reboot, so PresenceService re-adds it. */
internal object PresenceGeofence {
    const val ACTION = "dev.homedj.app.action.PRESENCE_GEOFENCE"

    fun canRegister(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Replaces any earlier fence; the initial trigger reports at once whether we are inside. */
    fun register(context: Context, home: HomeArea) {
        if (!canRegister(context)) return
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofence(Geofence.Builder()
                .setRequestId("home")
                .setCircularRegion(home.latitude, home.longitude, home.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build())
            .build()
        try {
            LocationServices.getGeofencingClient(context).addGeofences(request, pendingIntent(context))
                .addOnFailureListener { Log.w(TAG, "Home geofence add failed: " + it.javaClass.simpleName) }
        } catch (failure: SecurityException) {
            Log.w(TAG, "Home geofence permission revoked")
        }
    }

    fun unregister(context: Context) {
        try {
            LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Home geofence remove failed: " + failure.javaClass.simpleName)
        }
    }

    // Play services fills in the event extras, which requires a mutable PendingIntent on
    // Android 12+. The explicit component keeps it from being redirected elsewhere.
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, PresenceReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    private const val TAG = "HomeDJRemote"
}

/**
 * Not exported: delivers geofence transitions and boot to PresenceService. A geofence event is
 * one of the few broadcasts Android 12+ lets start a foreground service from the background.
 */
class PresenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            PresenceGeofence.ACTION -> {
                val event = GeofencingEvent.fromIntent(intent) ?: return
                val store = PresenceStore(app)
                if (event.hasError()) {
                    Log.w("HomeDJRemote", "Home geofence error: code=" + event.errorCode)
                    // Location was turned off; PresenceService re-adds the fence later.
                    if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                        store.geofence = GeofenceState.UNKNOWN
                    }
                    return
                }
                store.geofence = when (event.geofenceTransition) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceState.INSIDE
                    Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceState.OUTSIDE
                    else -> return
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        PresenceService.startIfEnabled(app)
    }
}
