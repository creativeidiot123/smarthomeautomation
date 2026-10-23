package dev.homedj.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/** User-initiated system consent only; the listener never launches settings from the background. */
internal fun Activity.openServerBackgroundAccess() {
    val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    val intent = if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
        appSettings
    } else {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
    }
    try {
        startActivity(intent)
    } catch (missing: ActivityNotFoundException) {
        Log.w("HomeDJRemote", "Battery settings unavailable: " + missing.javaClass.simpleName)
        try {
            startActivity(appSettings)
        } catch (missingAppSettings: ActivityNotFoundException) {
            Log.w("HomeDJRemote", "App settings unavailable: " + missingAppSettings.javaClass.simpleName)
            Toast.makeText(this, "Open Settings > Apps > HomeDJ > Battery and allow background use.", Toast.LENGTH_LONG).show()
        }
    }
}
