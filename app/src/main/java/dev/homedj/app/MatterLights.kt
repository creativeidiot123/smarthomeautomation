package dev.homedj.app

import android.content.Context
import android.util.Log
import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.ChipDeviceControllerException
import chip.devicecontroller.CommissionParameters
import chip.devicecontroller.ControllerParams
import chip.devicecontroller.GetConnectedDeviceCallbackJni.GetConnectedDeviceCallback
import chip.devicecontroller.ICDDeviceInfo
import chip.devicecontroller.InvokeCallback
import chip.devicecontroller.NetworkCredentials
import chip.devicecontroller.UnpairDeviceCallback
import chip.devicecontroller.model.InvokeElement
import chip.platform.AndroidBleManager
import chip.platform.AndroidChipPlatform
import chip.platform.AndroidNfcCommissioningManager
import chip.platform.ChipMdnsCallbackImpl
import chip.platform.DiagnosticDataProviderImpl
import chip.platform.NsdManagerServiceBrowser
import chip.platform.NsdManagerServiceResolver
import chip.platform.PreferencesConfigurationManager
import chip.platform.PreferencesKeyValueStoreManager
import java.security.SecureRandom
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Samsung's Matter controller (Project CHIP connectedhomeip, vendored in app/libs/matter): one
 * native controller per process and one Matter operation at a time. The SDK keeps its fabric
 * credentials in its own app-private preferences; [Lights] owns which node IDs are paired. Every
 * call talks to the bulb directly over the Wi-Fi LAN.
 */
internal object MatterLights {
    private const val TAG = "HomeDJLights"
    /** Matter's test vendor ID: this is a local, uncertified controller. */
    private const val VENDOR_ID = 0xFFF4
    /** Extended colour lights expose their light on endpoint 1. */
    private const val ENDPOINT = 1
    private const val ATTESTATION_FAIL_SAFE_SECONDS = 60
    private const val PAIR_TIMEOUT_MS = 120_000L
    private const val COMMAND_TIMEOUT_MS = 20_000L
    private const val IM_TIMEOUT_MS = 5_000

    class MatterFailure(val code: Long) : Exception("Matter error $code")

    private val lock = Mutex()
    /** Held for the process so the platform's Java delegates (mDNS, storage) stay reachable. */
    private var platform: AndroidChipPlatform? = null
    private var controller: ChipDeviceController? = null
    private class Commissioning {
        val result = CompletableDeferred<Long>()
        val attestation = CompletableDeferred<Long>()
    }
    /** Callbacks only complete these signals; the pairing coroutine makes further SDK calls. */
    @Volatile private var commissioning: Commissioning? = null

    /** Bounded user-initiated discovery using the same controller/platform as pairing. */
    suspend fun discover(context: Context): List<MatterLightCandidate> = lock.withLock {
        check(ModeStore(context).mode.first() == HomeDjMode.SERVER) { "Server role inactive" }
        val chip = controller(context)
        withContext(Dispatchers.IO) {
            chip.discoverCommissionableNodes()
            // The bundled browser closes its browse/multicast lock after 5 s. Allow another
            // 3 s for resolution callbacks before reading the SDK's indexed results.
            // stopDnssd() stops operational advertising, not this browse.
            delay(8_000)
            (0 until 16).mapNotNull { index ->
                chip.getDiscoveredDevice(index)?.let { device ->
                    MatterLightCandidate(device.discriminator.toInt(),
                        device.deviceName?.trim()?.take(LightsPolicy.MAX_NAME).orEmpty()
                            .ifBlank { "Matter light ${device.discriminator}" })
                }
            }.distinctBy { it.discriminator to it.name }
        }
    }

    /**
     * Commissions a bulb with an open Matter pairing window onto HomeDJ's own fabric. With [wifi],
     * a new bulb is also searched for over Bluetooth and given those Wi-Fi credentials; without
     * it, only bulbs already on Samsung's Wi-Fi (share codes) are found.
     */
    suspend fun pair(context: Context, setupCode: String, wifi: LightWifi? = null): Long = lock.withLock {
        val chip = controller(context)
        val nodeId = (SecureRandom().nextLong() ushr 1) + 1 // operational node IDs start at 1
        val pending = Commissioning()
        commissioning = pending
        try {
            val error = awaitMatterPairing(pending.result, pending.attestation, PAIR_TIMEOUT_MS,
                startPairing = {
                    val parameters = CommissionParameters.Builder()
                    wifi?.let { parameters.setNetworkCredentials(
                        NetworkCredentials.forWiFi(NetworkCredentials.WiFiCredentials(it.ssid, it.password))) }
                    chip.pairDeviceWithCode(nodeId, setupCode, false, wifi == null, parameters.build())
                },
                stopPairing = {
                    try {
                        chip.stopDevicePairing(nodeId)
                    } catch (failure: Exception) {
                        Log.w(TAG, "Pairing cleanup failed: " + failure.javaClass.simpleName)
                    }
                },
            ) { devicePointer ->
                chip.continueCommissioning(devicePointer, true)
            }
            if (error != 0L) throw MatterFailure(error)
            nodeId
        } finally {
            commissioning = null
        }
    }

    /**
     * Sends the commands back to back on one session; returns once the bulb acknowledged them all.
     * Live variation passes a short [timeoutMillis] so an unreachable bulb cannot hold the controller.
     */
    suspend fun send(context: Context, node: Long, commands: List<MatterCommand>,
                     timeoutMillis: Long = COMMAND_TIMEOUT_MS * commands.size): Unit = lock.withLock {
        val chip = controller(context)
        withTimeout(timeoutMillis) { deliver(chip, node, commands) }
    }

    /**
     * Sends every light its commands at the same time, each on its own session and within
     * [timeoutMillis]; returns the lights that acknowledged all of theirs. One light that cannot be
     * reached costs only its own [timeoutMillis] and never delays the others.
     */
    suspend fun sendEach(context: Context, commands: Map<Long, List<MatterCommand>>, timeoutMillis: Long): Set<Long> =
        lock.withLock {
            val chip = controller(context)
            acknowledgedEach(commands.keys, timeoutMillis, failed = { _, failure ->
                if (failure == null) Log.w(TAG, "Light command failed: timeout") else Lights.logFailure("Light command", failure)
            }) { node -> deliver(chip, node, commands.getValue(node)) }
        }

    private suspend fun deliver(chip: ChipDeviceController, node: Long, commands: List<MatterCommand>) {
        val device = connect(chip, node)
        try {
            commands.forEach { invoke(chip, device, it) }
        } finally {
            chip.releaseConnectedDevicePointer(device)
        }
    }

    /** Explicit one-bulb read; SDK callbacks expire in 5 s and retain no UI/service references. */
    suspend fun read(context: Context, node: Long): Map<Pair<Long, Long>, ByteArray> = lock.withLock {
        val chip = controller(context)
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                val device = connect(chip, node)
                try { readMatterLight(chip, device) } finally { chip.releaseConnectedDevicePointer(device) }
            } ?: throw java.io.IOException("Light read timed out")
        }
    }

    /**
     * House rules' power watch. Null when the bulb did not answer within [timeoutMillis], counted
     * only once the controller is free, so waiting behind pairing or a command is never an outage.
     */
    suspend fun boot(context: Context, node: Long, timeoutMillis: Long): BulbBoot? = lock.withLock {
        val chip = controller(context)
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMillis) {
                val device = connect(chip, node)
                try { readMatterBoot(chip, device, timeoutMillis.toInt()) } finally { chip.releaseConnectedDevicePointer(device) }
            }
        }
    }

    /** Writes the bulb's power-on behaviour (StartUpOnOff: 0 off, 1 on, null as before); throws if refused. */
    suspend fun startUp(context: Context, node: Long, value: Int?): Unit = lock.withLock {
        val chip = controller(context)
        withTimeout(COMMAND_TIMEOUT_MS) {
            val device = connect(chip, node)
            try { writeMatterStartUp(chip, device, value, IM_TIMEOUT_MS) } finally { chip.releaseConnectedDevicePointer(device) }
        }
    }

    /** The bulb's LAN address from its current session, for house rules' ping; null without one. Never logged or stored. */
    suspend fun address(context: Context, node: Long): String? = lock.withLock {
        val chip = controller(context)
        withContext(Dispatchers.IO) {
            try { chip.getIpAddress(node) } catch (failure: ChipDeviceControllerException) { null }
        }
    }

    /**
     * Removes HomeDJ's fabric from the bulb (other apps keep theirs). An unreachable bulb keeps
     * a stale fabric entry until it is reset; the share code from AiDot/Google Home still works.
     */
    suspend fun forget(context: Context, node: Long): Unit = lock.withLock {
        val chip = controller(context)
        withTimeout(COMMAND_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                chip.unpairDeviceCallback(node, object : UnpairDeviceCallback {
                    override fun onError(status: Int, remoteDeviceId: Long) {
                        if (continuation.isActive) continuation.resumeWithException(MatterFailure(status.toLong()))
                    }
                    override fun onSuccess(remoteDeviceId: Long) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                })
            }
        }
    }

    private suspend fun connect(chip: ChipDeviceController, node: Long): Long =
        suspendCancellableCoroutine { continuation ->
            chip.getConnectedDevicePointer(node, object : GetConnectedDeviceCallback {
                override fun onDeviceConnected(devicePointer: Long) {
                    continuation.resume(devicePointer) { _, pointer, _ -> chip.releaseConnectedDevicePointer(pointer) }
                }
                override fun onConnectionFailure(nodeId: Long, error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            })
        }

    private suspend fun invoke(chip: ChipDeviceController, device: Long, command: MatterCommand): Unit =
        suspendCancellableCoroutine { continuation ->
            chip.invoke(object : InvokeCallback {
                override fun onError(e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(invokeElement: InvokeElement?, successCode: Long) {
                    if (continuation.isActive) {
                        if (successCode == 0L) continuation.resume(Unit)
                        else continuation.resumeWithException(MatterFailure(successCode))
                    }
                }
            }, device, InvokeElement.newInstance(ENDPOINT, command.cluster, command.command, command.tlv, null),
                0, IM_TIMEOUT_MS)
        }

    /** Loads the native stack once; callers hold [lock]. */
    private suspend fun controller(context: Context): ChipDeviceController = controller ?: withContext(Dispatchers.IO) {
        val app = context.applicationContext
        ChipDeviceController.loadJni()
        platform = AndroidChipPlatform(
            AndroidBleManager(app),
            AndroidNfcCommissioningManager(),
            PreferencesKeyValueStoreManager(app),
            PreferencesConfigurationManager(app),
            NsdManagerServiceResolver(app, NsdManagerServiceResolver.NsdManagerResolverAvailState()),
            NsdManagerServiceBrowser(app),
            ChipMdnsCallbackImpl(),
            DiagnosticDataProviderImpl(app),
        )
        ChipDeviceController(ControllerParams.newBuilder().setControllerVendorId(VENDOR_ID).build()).also { chip ->
            chip.setCompletionListener(CommissioningListener)
            // The SDK ships only test attestation roots, so a certified retail bulb fails this
            // check; the person pairs a bulb they hold using its setup or Matter sharing code.
            chip.setDeviceAttestationDelegate(ATTESTATION_FAIL_SAFE_SECONDS) { devicePointer, _, errorCode ->
                if (errorCode != 0L) Log.w(TAG, "Bulb attestation not verified: $errorCode")
                // The SDK holds its native stack lock here. Re-entering it deadlocks.
                commissioning?.attestation?.complete(devicePointer)
            }
            controller = chip
        }
    }

    private object CommissioningListener : ChipDeviceController.CompletionListener {
        override fun onCommissioningComplete(nodeId: Long, errorCode: Long) {
            commissioning?.result?.complete(errorCode)
        }
        override fun onCommissioningStatusUpdate(nodeId: Long, stage: String?, errorCode: Long) {
            if (errorCode != 0L) Log.w(TAG, "Commissioning stage $stage failed: $errorCode")
        }
        override fun onError(error: Throwable?) {
            Log.w(TAG, "Commissioning failed: " + error?.javaClass?.simpleName)
            commissioning?.result?.complete((error as? ChipDeviceControllerException)?.errorCode ?: -1L)
        }
        override fun onConnectDeviceComplete() {}
        override fun onStatusUpdate(status: Int) {
            commissioning?.result?.let { failMatterPairingOnStatus(it, status) }
        }
        override fun onPairingComplete(errorCode: Long) {
            if (errorCode != 0L) commissioning?.result?.complete(errorCode)
        }
        override fun onPairingDeleted(errorCode: Long) {}
        override fun onReadCommissioningInfo(vendorId: Int, productId: Int, wifiEndpointId: Int, threadEndpointId: Int) {}
        override fun onCommissioningStageStart(nodeId: Long, stage: String?) {}
        override fun onNotifyChipConnectionClosed() {}
        override fun onCloseBleComplete() {}
        override fun onOpCSRGenerationComplete(csr: ByteArray?) {}
        override fun onICDRegistrationInfoRequired() {}
        override fun onICDRegistrationComplete(errorCode: Long, icdDeviceInfo: ICDDeviceInfo?) {}
    }
}

/**
 * Runs [send] for every key at once, each within [timeoutMillis], and returns the keys whose send
 * finished. A failure or timeout ([failed] gets null) costs only its own key; the caller's
 * cancellation still cancels them all. connectedhomeip v1.6.1.0 gives each connect and invoke its
 * own native callback and takes its stack lock inside each call, so the sends may overlap.
 */
internal suspend fun <K> acknowledgedEach(
    keys: Collection<K>, timeoutMillis: Long, failed: (K, Exception?) -> Unit, send: suspend (K) -> Unit,
): Set<K> = coroutineScope {
    keys.map { key ->
        async {
            val done = try {
                withTimeoutOrNull(timeoutMillis) { send(key) } != null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed(key, failure)
                return@async null
            }
            if (!done) failed(key, null)
            key.takeIf { done }
        }
    }.awaitAll().filterNotNullTo(LinkedHashSet())
}

/** connectedhomeip v1.6.1.0 `DevicePairingDelegate::SecurePairingFailed`. */
private const val SECURE_PAIRING_FAILED = 1

/**
 * When the SDK's ~30 s search ends without a bulb (or after its last PASE attempt failed), it
 * reports only this status: no PASE device exists, so `onPairingComplete` never follows. Fail the
 * attempt now as the same timeout the caller already explains, instead of waiting out ours.
 */
internal fun failMatterPairingOnStatus(result: CompletableDeferred<Long>, status: Int) {
    if (status == SECURE_PAIRING_FAILED) result.completeExceptionally(TimeoutException("Matter discovery ended"))
}

/** All native pairing calls, including failure/cancellation cleanup, stay off Android Main. */
internal suspend fun awaitMatterPairing(
    result: Deferred<Long>, attestation: Deferred<Long>, timeoutMillis: Long,
    startPairing: () -> Unit = {},
    stopPairing: () -> Unit = {},
    continueCommissioning: (Long) -> Unit,
): Long = withContext(Dispatchers.IO) {
    var paired = false
    try {
        startPairing()
        val code = withTimeoutOrNull<Long>(timeoutMillis) {
            select<Long> {
                result.onAwait { it }
                attestation.onAwait { devicePointer ->
                    continueCommissioning(devicePointer)
                    result.await()
                }
            }
        } ?: throw TimeoutException("Matter pairing timed out")
        paired = code == 0L
        code
    } finally {
        if (!paired) stopPairing()
    }
}
