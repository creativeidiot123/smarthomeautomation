package dev.homedj.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import javax.crypto.spec.SecretKeySpec

/**
 * One phone's BLE presence beacon: it advertises its own pair ID and scans for the peer's.
 * The IDs are static, so callers only advertise where being recognisable is harmless: Samsung
 * at home, the OnePlus only while it is home. Owned by one service; call [update] on its main
 * thread and [stop] in onDestroy. Bluetooth off or permission missing simply means no beacon.
 */
internal class PresenceBeacon(private val context: Context, private val onPeerSeen: () -> Unit) {
    private var key: SecretKeySpec? = null
    private var advertising: AdvertiseCallback? = null
    private var scanning: ScanCallback? = null
    /** A refused start waits before retrying, so an unsupported chip cannot flood Logcat. */
    private var advertiseRetryAt = 0L
    private var scanRetryAt = 0L

    val scanningActive: Boolean get() = scanning != null

    /** Reconciles the radio with what the owner wants; cheap when nothing changed. */
    fun update(pairKey: SecretKeySpec?, advertiseRole: String?, scanRole: String?) {
        val adapter = adapter()
        if (pairKey == null || adapter == null || !adapter.isEnabled) return stop()
        if (key?.encoded?.contentEquals(pairKey.encoded) != true) {
            stop()
            key = pairKey
        }
        val now = SystemClock.elapsedRealtime()
        try {
            if (advertiseRole == null) stopAdvertising()
            else if (advertising == null && now >= advertiseRetryAt && granted(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                startAdvertising(adapter, pairKey, advertiseRole)
            }
            if (scanRole == null) stopScan()
            else if (scanning == null && now >= scanRetryAt && granted(Manifest.permission.BLUETOOTH_SCAN)) {
                startScan(adapter, pairKey, scanRole)
            }
        } catch (failure: SecurityException) {
            Log.w(TAG, "Presence beacon permission revoked")
            stop()
        } catch (failure: IllegalStateException) {
            Log.w(TAG, "Presence beacon unavailable: " + failure.javaClass.simpleName)
            stop()
        }
    }

    fun stop() {
        stopAdvertising()
        stopScan()
        key = null
    }

    private fun startAdvertising(adapter: BluetoothAdapter, pairKey: SecretKeySpec, role: String) {
        // Null when the chip cannot advertise; the phone can still scan.
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "Presence beacon advertise failed: code=$errorCode")
                if (advertising === this) advertising = null
                advertiseRetryAt = SystemClock.elapsedRealtime() + RETRY_MILLIS
            }
        }
        advertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .build(),
            AdvertiseData.Builder().addServiceData(SERVICE, PresencePolicy.beaconId(pairKey, role)).build(),
            callback,
        )
        advertising = callback
    }

    private fun startScan(adapter: BluetoothAdapter, pairKey: SecretKeySpec, role: String) {
        val scanner = adapter.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onPeerSeen()
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (results.isNotEmpty()) onPeerSeen()
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Presence beacon scan failed: code=$errorCode")
                if (scanning === this) scanning = null
                scanRetryAt = SystemClock.elapsedRealtime() + RETRY_MILLIS
            }
        }
        // A filtered scan keeps running with the screen off; batching lets the radio hold
        // results instead of waking the phone for every advertisement.
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceData(SERVICE, PresencePolicy.beaconId(pairKey, role)).build()),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(if (adapter.isOffloadedScanBatchingSupported) BATCH_MILLIS else 0)
                .build(),
            callback,
        )
        scanning = callback
    }

    private fun stopAdvertising() {
        val callback = advertising ?: return
        advertising = null
        try {
            adapter()?.bluetoothLeAdvertiser?.stopAdvertising(callback)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Presence beacon advertise stop failed: " + failure.javaClass.simpleName)
        }
    }

    private fun stopScan() {
        val callback = scanning ?: return
        scanning = null
        try {
            adapter()?.bluetoothLeScanner?.stopScan(callback)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Presence beacon scan stop failed: " + failure.javaClass.simpleName)
        }
    }

    private fun adapter(): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Android 10–11 gate BLE behind location; both HomeDJ phones run Android 12+. */
    private fun granted(permission: String): Boolean = context.checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 31) permission else Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "HomeDJRemote"
        const val BATCH_MILLIS = 20_000L
        const val RETRY_MILLIS = 5 * 60_000L
        /** HomeDJ's own 128-bit service UUID; the 8-byte payload keeps the advert under 31 bytes. */
        val SERVICE: ParcelUuid = ParcelUuid.fromString("8f1e6a52-3c4d-4b7a-9e21-5d0c7b3a64f9")
    }
}
