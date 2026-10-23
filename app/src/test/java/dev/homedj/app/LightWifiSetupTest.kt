package dev.homedj.app

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightWifiSetupTest {
    @Test fun acceptsWpaPassphrasesHexKeysAndOpenNetworks() {
        assertTrue(validLightWifi(LightWifi("Home", "12345678")))
        assertTrue(validLightWifi(LightWifi("Home 2.4G", "p".repeat(63))))
        assertTrue(validLightWifi(LightWifi("Home", "0123456789abcdefABCDEF".padEnd(64, '0'))))
        assertTrue(validLightWifi(LightWifi("Guest", "")))
        assertTrue(validLightWifi(LightWifi("s".repeat(32), "12345678")))
    }

    @Test fun rejectsWifiThatMatterCannotCarry() {
        assertFalse(validLightWifi(LightWifi("", "12345678")))
        assertFalse(validLightWifi(LightWifi("s".repeat(33), "12345678")))
        // 11 characters but 33 UTF-8 bytes.
        assertFalse(validLightWifi(LightWifi("ホームネットワークです", "12345678")))
        assertFalse(validLightWifi(LightWifi("Home", "1234567")))
        assertFalse(validLightWifi(LightWifi("Home", "g".repeat(64))))
        assertFalse(validLightWifi(LightWifi("Home", "p".repeat(65))))
    }

    @Test fun passwordNeverAppearsInDefaultRendering() {
        assertFalse(LightWifi("Home", "secret-pass").toString().contains("secret-pass"))
    }

    @Test fun asksForTheBluetoothPermissionsTheSdkScanAndConnectNeed() {
        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            lightBluetoothPermissions(31))
        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            lightBluetoothPermissions(35))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), lightBluetoothPermissions(29))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), lightBluetoothPermissions(30))
    }
}
