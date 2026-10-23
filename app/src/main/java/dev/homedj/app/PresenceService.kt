package dev.homedj.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Projection of PresenceService for the OnePlus screen; not persisted. */
data class PresenceStatus(
    val running: Boolean = false,
    val presence: Presence = Presence.UNKNOWN,
    /** How many of Wi-Fi, Bluetooth and GPS currently say home; two or more is home. */
    val homeSignals: Int = 0,
    val samsungReachable: Boolean = false,
    val beaconSeen: Boolean = false,
    val scanning: Boolean = false,
    val geofence: GeofenceState = GeofenceState.UNKNOWN,
    /** Samsung answered `Unknown command`: its build cannot pause or resume for presence. */
    val samsungOutdated: Boolean = false,
)

/**
 * OnePlus-only foreground service that owns the Home/Away verdict while detection is on. It
 * fuses the authenticated LAN check-in, Samsung's beacon and the geofence, advertises this
 * phone's beacon only while home, and tells Samsung `autoPause`. Samsung alone decides to
 * pause or resume (PresenceWatch). One loop on Main: events only wake it, so work is serial.
 */
class PresenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val store by lazy { PresenceStore(this) }
    private val beacon by lazy {
        PresenceBeacon(this) {
            beaconAt = SystemClock.elapsedRealtime()
            wake.trySend(Unit)
        }
    }
    private val wifiNetworks = mutableSetOf<Network>()
    private var networkRegistered = false
    private var beaconAt: Long? = null
    private var lanAt: Long? = null
    private var nextProbeAt = 0L
    private var probeFailures = 0
    private var geofenceAddedAt: Long? = null
    private var startedAt = 0L

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetworks.add(network)
            probeFailures = 0
            nextProbeAt = 0L
            wake.trySend(Unit)
        }
        override fun onLost(network: Network) {
            wifiNetworks.remove(network)
            wake.trySend(Unit)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startedAt = SystemClock.elapsedRealtime()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, "Home detection", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(
            NotificationChannel(CHANGE_CHANNEL, "Arriving and leaving", NotificationManager.IMPORTANCE_DEFAULT))
        if (!enterForeground()) return
        try {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiCallback, Handler(Looper.getMainLooper()),
            )
            networkRegistered = true
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Presence Wi-Fi listener unavailable: " + failure.javaClass.simpleName)
        }
        scope.launch {
            ModeStore(this@PresenceService).mode.collect {
                if (it != HomeDjMode.CONTROLLER || !store.enabled) stopSelf()
            }
        }
        scope.launch { loop() }
    }

    // Android may restart detection after process death; the loop re-checks role and opt-in.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService must be answered with startForeground, even when running.
        if (!enterForeground()) return START_NOT_STICKY
        if (intent?.action == ACTION_HOME_CHANGED) geofenceAddedAt = null
        nextProbeAt = 0L
        wake.trySend(Unit)
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        val notification = statusNotification(mutableState.value.presence)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (failure: Exception) {
        Log.w(TAG, "Presence foreground start failed: " + failure.javaClass.simpleName)
        stopSelf()
        false
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            if (wifiNetworks.isNotEmpty() && now >= nextProbeAt) checkIn(store.autoPause)
            refreshGeofence(now)
            evaluate()
            withTimeoutOrNull(TICK_MILLIS) { wake.receive() }
        }
    }

    /** The authenticated `presence` request is both the Wi-Fi probe and Samsung's check-in. */
    private suspend fun checkIn(autoPause: Boolean) {
        // On another Wi-Fi (work, a café) Samsung never answers: back off to 16 minutes.
        // Joining a Wi-Fi network resets this, so arriving home is still checked at once.
        nextProbeAt = SystemClock.elapsedRealtime() + (PROBE_MILLIS shl probeFailures.coerceAtMost(4))
        val reply = sendPresence(this, autoPause, home = mutableState.value.presence == Presence.HOME)
        if (reply == null) {
            probeFailures++
            return
        }
        probeFailures = 0
        nextProbeAt = SystemClock.elapsedRealtime() + PROBE_MILLIS
        lanAt = SystemClock.elapsedRealtime()
        val outdated = !reply.optBoolean("ok") && reply.optString("error") == "Unknown command"
        mutableState.value = mutableState.value.copy(samsungOutdated = outdated)
    }

    /** Re-adds the fence after boot or a location outage, at most every 15 minutes. */
    private fun refreshGeofence(now: Long) {
        val home = store.home ?: return
        val added = geofenceAddedAt
        if (added != null && (store.geofence != GeofenceState.UNKNOWN || now - added < GEOFENCE_RETRY_MILLIS)) return
        geofenceAddedAt = now
        PresenceGeofence.register(this, home)
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val previous = mutableState.value
        // Without a home or "Allow all the time" the stored transition is stale, so GPS cannot vote.
        val geofence = if (store.home == null || !PresenceGeofence.canRegister(this)) GeofenceState.UNKNOWN
            else store.geofence
        val signals = PresencePolicy.homeSignals(lanAt, beaconAt, geofence, now)
        val next = PresencePolicy.next(previous.presence, signals, startedAt, now)
        val key = RemoteChannel.decodeKey(RemotePrefs(this).savedClientKey)
        beacon.update(key, if (next == Presence.HOME) PresencePolicy.ONEPLUS_ROLE else null, PresencePolicy.SAMSUNG_ROLE)
        mutableState.value = previous.copy(
            running = true, presence = next, homeSignals = signals,
            samsungReachable = PresencePolicy.fresh(lanAt, now), beaconSeen = PresencePolicy.fresh(beaconAt, now),
            scanning = beacon.scanningActive, geofence = geofence,
        )
        if (next == previous.presence) return
        // Tell Samsung the new vote now, so arriving home resumes music without waiting a minute.
        if (wifiNetworks.isNotEmpty()) {
            nextProbeAt = 0L
            wake.trySend(Unit)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, statusNotification(next))
        // The first verdict after a start is not a change of place.
        if (previous.presence != Presence.UNKNOWN && next != Presence.UNKNOWN) {
            manager.notify(CHANGE_NOTIFICATION_ID, Notification.Builder(this, CHANGE_CHANNEL)
                .setSmallIcon(R.drawable.ic_home)
                .setContentTitle(if (next == Presence.HOME) "You're home" else "You left home")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build())
        }
    }

    private fun statusNotification(presence: Presence): Notification =
        Notification.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_home)
            .setContentTitle(when (presence) {
                Presence.HOME -> "Home"
                Presence.AWAY -> "Away"
                Presence.UNKNOWN -> "Checking whether you're home"
            })
            .setContentText("HomeDJ home detection")
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, HomeDjActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        scope.cancel()
        beacon.stop()
        if (networkRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
            networkRegistered = false
        }
        // onDestroy means detection was switched off or the role changed; a process kill skips
        // it, so the fence stays armed to restart detection on the next transition.
        if (!store.enabled) PresenceGeofence.unregister(this)
        mutableState.value = PresenceStatus()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HomeDJRemote"
        private const val STATUS_CHANNEL = "presence"
        private const val CHANGE_CHANNEL = "presence_changes"
        private const val NOTIFICATION_ID = 44
        private const val CHANGE_NOTIFICATION_ID = 45
        private const val ACTION_HOME_CHANGED = "dev.homedj.app.action.PRESENCE_HOME_CHANGED"
        private const val TICK_MILLIS = 30_000L
        private const val PROBE_MILLIS = 60_000L
        private const val GEOFENCE_RETRY_MILLIS = 15 * 60_000L

        private val mutableState = MutableStateFlow(PresenceStatus())
        val state: StateFlow<PresenceStatus> = mutableState.asStateFlow()

        /** Starts or nudges detection if the user turned it on; role is re-checked by the service. */
        fun startIfEnabled(context: Context, homeChanged: Boolean = false) {
            val app = context.applicationContext
            if (!PresenceStore(app).enabled) return
            val intent = Intent(app, PresenceService::class.java)
            if (homeChanged) intent.action = ACTION_HOME_CHANGED
            try {
                app.startForegroundService(intent)
            } catch (failure: RuntimeException) {
                Log.w(TAG, "Presence service start refused: " + failure.javaClass.simpleName)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, PresenceService::class.java))
        }

        /**
         * One authenticated `presence` check-in; null when Samsung is unreachable or unpaired.
         * Older Samsung builds reply `Unknown command`, which still proves they are reachable.
         */
        internal suspend fun sendPresence(context: Context, autoPause: Boolean, home: Boolean): JSONObject? {
            val prefs = RemotePrefs(context)
            val host = prefs.savedHost
            val key = RemoteChannel.decodeKey(prefs.savedClientKey) ?: return null
            if (!RemotePrefs.validPrivateIpv4(host)) return null
            return try {
                withContext(Dispatchers.IO) { RemoteChannel.exchange(host, key, presenceRequest(autoPause, home)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Presence check-in failed: " + failure.javaClass.simpleName)
                null
            }
        }
    }
}

/**
 * Wire form of the check-in: `home` is the OnePlus's 2-of-3 vote, which Samsung needs before it
 * resumes. New optional command: older Samsung builds reply `Unknown command`.
 */
internal fun presenceRequest(autoPause: Boolean, home: Boolean): JSONObject =
    JSONObject().put("command", "presence").put("autoPause", autoPause).put("home", home)
