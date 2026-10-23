package dev.homedj.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

data class PresenceUiState(
    val enabled: Boolean = false,
    val autoPause: Boolean = true,
    val homeSet: Boolean = false,
    val settingHome: Boolean = false,
    val nearbyGranted: Boolean = false,
    val locationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val message: String? = null,
    val status: PresenceStatus = PresenceStatus(),
)

/** Bluetooth beacon permissions; Android 10–11 cover BLE with location instead. */
internal fun presenceNearbyPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
    else emptyList()

/** Asked together first; "Allow all the time" can only be asked once location is granted. */
internal fun presenceControllerPermissions(): List<String> = presenceNearbyPermissions() +
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
    listOfNotNull(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null)

/** Settings and permission state for home detection; PresenceService owns the verdict. */
class PresenceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = PresenceStore(application)
    private val settings = MutableStateFlow(read(PresenceUiState()))
    val state: StateFlow<PresenceUiState> = combine(settings, PresenceService.state) { current, status ->
        current.copy(status = status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.value)
    private var homeJob: Job? = null

    /** Re-reads permissions and battery state, e.g. after a permission dialog or Settings. */
    fun refresh() = settings.update(::read)

    fun setEnabled(on: Boolean) {
        store.enabled = on
        settings.update { read(it).copy(message = null) }
        if (on) return PresenceService.startIfEnabled(app)
        // Stop the beacon first so the service cannot send a later check-in, then tell Samsung
        // not to pause for this phone; otherwise it would pause once it stops hearing from us.
        PresenceService.stop(app)
        viewModelScope.launch {
            if (PresenceService.sendPresence(app, autoPause = false, home = false) == null) {
                settings.update {
                    it.copy(message = "Samsung was not reachable, so it may still pause once when it stops seeing this phone.")
                }
            }
        }
    }

    fun setAutoPause(on: Boolean) {
        store.autoPause = on
        settings.update(::read)
        // A nudge makes the running service check in with the new choice right away.
        PresenceService.startIfEnabled(app)
    }

    fun setHomeHere() {
        if (homeJob?.isActive == true) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            settings.update { it.copy(message = "Allow precise location to set your home.") }
            return
        }
        settings.update { it.copy(settingHome = true, message = null) }
        homeJob = viewModelScope.launch {
            val location = try {
                currentLocation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJRemote", "Home location fix failed: " + failure.javaClass.simpleName)
                null
            }
            val message = when {
                location == null -> "Could not get your location. Turn on Location and try again."
                location.accuracy > MAX_ACCURACY_METERS ->
                    "Location is only accurate to ${location.accuracy.toInt()} m. Try again near a window."
                else -> {
                    store.saveHome(location.latitude, location.longitude)
                    PresenceService.startIfEnabled(app, homeChanged = true)
                    "Home location saved."
                }
            }
            settings.update { read(it).copy(settingHome = false, message = message) }
        }
    }

    private suspend fun currentLocation(): Location? = suspendCancellableCoroutine { continuation ->
        val cancel = CancellationTokenSource()
        continuation.invokeOnCancellation { cancel.cancel() }
        LocationServices.getFusedLocationProviderClient(app)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token)
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    private fun read(previous: PresenceUiState) = previous.copy(
        enabled = store.enabled,
        autoPause = store.autoPause,
        homeSet = store.home != null,
        nearbyGranted = presenceNearbyPermissions().all(::granted),
        locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        backgroundLocationGranted = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        batteryUnrestricted = app.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(app.packageName),
    )

    private fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MAX_ACCURACY_METERS = 100f
    }
}
