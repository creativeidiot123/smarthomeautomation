package dev.homedj.app

import android.Manifest

/**
 * Wi-Fi a brand-new Matter bulb joins during Samsung's Bluetooth setup. Typed on the Samsung,
 * held in memory for one pairing attempt and never saved, logged or sent over the LAN.
 * Deliberately not a data class: default string rendering must not expose the password.
 */
internal class LightWifi(val ssid: String, val password: String)

internal const val WIFI_INVALID = "Check the Wi-Fi name and password (8-63 characters, or none for an open network)."

/** Matter Wi-Fi: SSID 1..32 bytes; password empty (open), 8..63 characters, or 64 hex digits. */
internal fun validLightWifi(wifi: LightWifi): Boolean {
    val ssidBytes = wifi.ssid.toByteArray(Charsets.UTF_8).size
    val password = wifi.password
    return ssidBytes in 1..32 && (password.isEmpty() || password.length in 8..63 ||
        (password.length == 64 && password.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }))
}

/** The SDK scans and connects to the bulb itself; Android 10-11 needs location for BLE scans. */
internal fun lightBluetoothPermissions(sdk: Int): List<String> = if (sdk >= 31)
    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
