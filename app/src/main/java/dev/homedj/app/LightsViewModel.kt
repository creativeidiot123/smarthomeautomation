package dev.homedj.app

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class LightsUiState(
    val mode: HomeDjMode? = null,
    val snapshot: LightsSnapshot = LightsSnapshot(),
    /** Samsung: always. OnePlus: Samsung has answered with its lights at least once. */
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val actionSucceeded: Boolean = false,
    val message: String? = null,
    val exactAlarms: Boolean = true,
    val discovered: List<MatterLightCandidate> = emptyList(),
    val discoveryComplete: Boolean = false,
    val scannedCodeReady: Boolean = false,
    val scanningQr: Boolean = false,
    val remoteSetupSupported: Boolean = false,
    /** Samsung: always. OnePlus: Samsung's build understands `lightsVariation`. */
    val variationSupported: Boolean = false,
    /** Samsung: always. OnePlus: Samsung's build understands `lightsGroupVariation`. */
    val groupVariationSupported: Boolean = false,
    /** Samsung: always. OnePlus: Samsung's build saves and runs scene juggle (`lightsJuggle`). */
    val juggleSupported: Boolean = false,
    /** Samsung: always. OnePlus: Samsung's build understands `lightsPermanentlyOff`. */
    val permanentlyOffSupported: Boolean = false,
    /** Samsung: always. OnePlus: Samsung's build plays a scene's linked playlists (`lightsScenePlaylists`). */
    val scenePlaylistsSupported: Boolean = false,
    /** Samsung: always. OnePlus: Samsung's build applies a scene and plays a playlist on arrival (`lightsArrival`). */
    val arrivalSupported: Boolean = false,
    /** Samsung: always. OnePlus: Samsung's build runs the light show (`lightsDance`). */
    val danceSupported: Boolean = false,
    /** Samsung's live light-show status (as of the last reply on the OnePlus); empty when not dancing. */
    val danceStatus: String = "",
    /** Samsung: Bluetooth permissions granted, so new-bulb setup can scan for the light. */
    val bluetoothReady: Boolean = false,
    /** Samsung: last Wi-Fi name a new bulb joined, to prefill the next setup. */
    val wifiName: String = "",
    /** Scene editor: recorded bulb states when its live preview began (the lights now show the draft). */
    val sceneBase: List<Bulb>? = null,
) {
    val setupAvailable: Boolean get() = mode == HomeDjMode.SERVER ||
        (mode == HomeDjMode.CONTROLLER && loaded && remoteSetupSupported)
}

/**
 * Lights tab on both phones. Samsung calls [Lights] directly; the OnePlus sends the LAN
 * `lights*` commands and shows the snapshot Samsung replies with. Taps are first-wins; direct
 * light changes are latest-wins live sends ([sender]). The owner serializes anyway and re-checks the role.
 */
class LightsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val modeStore = ModeStore(application)
    private val prefs = RemotePrefs(application)
    private val mutableState = MutableStateFlow(LightsUiState())
    internal val state: StateFlow<LightsUiState> = mutableState.asStateFlow()
    private var job: Job? = null
    /** Never part of a data class, saved state, logs or the LAN snapshot. */
    private var scannedSetupCode: String? = null
    /** Last typed new-bulb Wi-Fi password: memory only, gone when the screen's ViewModel or role ends. */
    private var wifiPassword = ""
    private val sender = LightLiveSender<suspend (HomeDjMode?) -> Unit>(viewModelScope, ::sendLive) {
        held?.let(::showSnapshot)
        preview.settled()
        mutableState.update { it.copy(sceneBase = preview.base) }
    }
    private var preview = LightScenePreview()
    /** Owner state that arrived while a newer live value was queued; shown once the burst settles. */
    private var held: LightsSnapshot? = null

    init {
        viewModelScope.launch {
            modeStore.mode.distinctUntilChanged().collectLatest { mode ->
                job?.cancel()
                sender.cancel()
                held = null
                preview = LightScenePreview()
                scannedSetupCode = null
                wifiPassword = ""
                mutableState.value = LightsUiState(mode = mode, variationSupported = mode == HomeDjMode.SERVER,
                    groupVariationSupported = mode == HomeDjMode.SERVER,
                    juggleSupported = mode == HomeDjMode.SERVER, danceSupported = mode == HomeDjMode.SERVER,
                    permanentlyOffSupported = mode == HomeDjMode.SERVER, scenePlaylistsSupported = mode == HomeDjMode.SERVER,
                    arrivalSupported = mode == HomeDjMode.SERVER)
                if (mode == HomeDjMode.SERVER) {
                    refreshBluetooth()
                    val wifiName = Lights.wifiName(app)
                    mutableState.update { it.copy(wifiName = wifiName) }
                    // Re-arms the next rule, e.g. after the role came back to Server.
                    LightsWork.runRules(app)
                    Lights.state(app).combine(LightDanceRunner.status, ::Pair).collect { (snapshot, dance) ->
                        mutableState.update { it.copy(exactAlarms = ScheduleAlarms.canSchedule(app), danceStatus = dance) }
                        showSnapshot(snapshot)
                    }
                }
            }
        }
    }

    /** OnePlus: fetches Samsung's lights. Samsung: re-reads the alarm permission. */
    fun refresh() = run(null) { mode ->
        if (mode == HomeDjMode.SERVER) mutableState.update { it.copy(exactAlarms = ScheduleAlarms.canSchedule(app)) }
        else remote(JSONObject().put("command", "lights"))
    }

    /** [wifi] is Samsung-only new-bulb setup over Bluetooth; it is never sent to the Samsung over the LAN. */
    internal fun pair(name: String, code: String, discriminator: Int? = null, wifi: LightWifi? = null) {
        val state = mutableState.value
        if (!state.setupAvailable || (wifi != null && state.mode != HomeDjMode.SERVER)) return
        val setupCode = matterSetupCode(scannedSetupCode ?: code)
            ?: return mutableState.update { it.copy(message = "Use a valid Matter share code or QR code.") }
        if (discriminator != null && !matchesMatterCandidate(setupCode, discriminator)) {
            return mutableState.update { it.copy(message = "That code doesn't match the selected light.") }
        }
        if (wifi != null) {
            bluetoothProblem(wifi)?.let { problem -> return mutableState.update { it.copy(message = problem) } }
            wifiPassword = wifi.password
        }
        run(if (wifi == null) "Pairing… keep the light powered (up to 2 min)."
            else "Setting up over Bluetooth… keep the light near this phone (up to 2 min).", reconcileOnDisconnect = true) { mode ->
            clearScan()
            when {
                mode != HomeDjMode.SERVER -> remote(RemoteLightSetup.request(name, setupCode, discriminator),
                    RemoteLightSetup.PAIR_REPLY_MILLIS)
                wifi == null -> show(Lights.pair(app, name, setupCode, discriminator))
                else -> try {
                    val outcome = Lights.pair(app, name, setupCode, discriminator, wifi)
                    show(outcome)
                    if (outcome.ok) mutableState.update { it.copy(wifiName = wifi.ssid) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Lights.logFailure("Bluetooth light pairing", failure)
                    mutableState.update { it.copy(message = BLUETOOTH_PAIR_FAILED) }
                }
            }
        }
    }

    /** Prefills the password field for the next new bulb; never part of [LightsUiState]. */
    internal fun wifiPassword(): String = wifiPassword

    /** Re-read the grant; the SDK needs these before it scans. */
    internal fun refreshBluetooth() = mutableState.update { it.copy(bluetoothReady = bluetoothGranted()) }

    /** After the Activity's request: a permanent denial returns at once, so point to Settings. */
    internal fun bluetoothRequested() {
        refreshBluetooth()
        if (!mutableState.value.bluetoothReady) mutableState.update {
            it.copy(message = "Bluetooth is off for HomeDJ. Allow Nearby devices in Settings > Apps > HomeDJ > Permissions.")
        }
    }

    private fun bluetoothGranted() = lightBluetoothPermissions(Build.VERSION.SDK_INT)
        .all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Checked at pairing time: Android 13+ no longer lets the SDK switch Bluetooth on itself. */
    private fun bluetoothProblem(wifi: LightWifi): String? = when {
        !validLightWifi(wifi) -> WIFI_INVALID
        !bluetoothGranted() -> { refreshBluetooth(); "Allow Bluetooth for HomeDJ to set up a new light." }
        app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true -> "Turn on Bluetooth on this phone."
        else -> null
    }

    internal fun scanned(code: String?) {
        if (!mutableState.value.setupAvailable) { cancelScan(); return }
        val valid = code?.let(::matterSetupCode)
        scannedSetupCode = valid
        mutableState.update { it.copy(scannedCodeReady = valid != null, scanningQr = false,
            message = if (valid == null) "That QR code isn't a Matter pairing code." else "QR code ready. Confirm Pair light to add it.") }
    }

    internal fun clearScan() {
        scannedSetupCode = null
        mutableState.update { it.copy(scannedCodeReady = false) }
    }

    internal fun beginScan(): Boolean {
        val state = mutableState.value
        if (state.busy || state.scanningQr || !state.setupAvailable) return false
        mutableState.update { it.copy(scanningQr = true) }
        return true
    }

    internal fun cancelScan() = mutableState.update { it.copy(scanningQr = false) }

    internal fun scannerFailed(failure: Exception) {
        Lights.logFailure("QR scan", failure)
        mutableState.update { it.copy(scanningQr = false,
            message = "Scanner unavailable. Check Google Play services and internet, or enter the share code.") }
    }

    internal fun discover() = run("Searching Samsung's Wi-Fi for Matter lights…") { mode ->
        if (!mutableState.value.setupAvailable) return@run
        mutableState.update { it.copy(discovered = emptyList(), discoveryComplete = false) }
        if (mode != HomeDjMode.SERVER) {
            remote(JSONObject().put("command", "lightsDiscover"))
            return@run
        }
        val found = MatterLights.discover(app)
        if (modeStore.mode.first() != HomeDjMode.SERVER) return@run
        mutableState.update { it.copy(discovered = found, discoveryComplete = true,
            message = RemoteLightSetup.discoveryMessage(found)) }
    }

    internal fun saveGroup(group: LightsGroup) = run(null) { mode ->
        if (mode == HomeDjMode.SERVER) show(Lights.saveGroup(app, group))
        else remote(JSONObject().put("command", "lightsGroupSave").put("group", LightsGroupsCodec.groupJson(group)))
    }

    internal fun deleteGroup(id: String) = run(null) { mode ->
        if (mode == HomeDjMode.SERVER) show(Lights.deleteGroup(app, id))
        else remote(JSONObject().put("command", "lightsGroupDelete").put("groupId", id))
    }

    internal fun changeGroup(id: String, change: LightChange) = live(listOf("group", id, change.fields())) { mode ->
        if (mode == HomeDjMode.SERVER) show(Lights.changeGroup(app, id, change))
        else remote(LightsCodec.putChange(JSONObject().put("command", "lightsGroupSet").put("groupId", id), change))
    }

    internal fun preferences(id: Long, preferences: LightPreferences) = run(null) { mode ->
        if (mode == HomeDjMode.SERVER) show(Lights.preferences(app, id, preferences))
        else remote(JSONObject().put("command", "lightsPreferences").put("id", id)
            .put("preferences", LightsGroupsCodec.preferencesJson(preferences)))
    }

    internal fun change(target: Long?, change: LightChange) = live(listOf("light", target, change.fields())) { mode ->
        if (mode == HomeDjMode.SERVER) show(Lights.change(app, target, change))
        else remote(LightsCodec.putChange(JSONObject().put("command", "lightsSet"), change)
            .also { request -> target?.let { request.put("id", it) } })
    }

    internal fun rename(id: Long, name: String) = run(null) { mode ->
        if (mode == HomeDjMode.SERVER) show(Lights.rename(app, id, name))
        else remote(JSONObject().put("command", "lightsRename").put("id", id).put("name", name))
    }

    internal fun forget(id: Long) = run("Removing HomeDJ from the light…", reconcileOnDisconnect = true) { mode ->
        if (!mutableState.value.setupAvailable) return@run
        if (mode == HomeDjMode.SERVER) show(Lights.forget(app, id))
        else remote(JSONObject().put("command", "lightsForget").put("id", id))
    }

    /** Leaving and coming home; a null [homeScene]/[homePlaylist] clears it (sent as ""). */
    internal fun setPresence(awayOff: Boolean, homeOn: Boolean, homeScene: String?, homePlaylist: String?) =
        experience(JSONObject().put("command", "lightsPresence").put("awayOff", awayOff).put("homeOn", homeOn)
            .put("homeScene", homeScene.orEmpty()).put("homePlaylist", homePlaylist.orEmpty()))

    internal fun saveLightRule(rule: LightsRule) = experience(JSONObject().put("command", "lightsRuleSave")
        .put("rule", LightsCodec.ruleJson(rule)))
    internal fun removeLightRule(id: String) = experience(JSONObject().put("command", "lightsRuleDelete").put("id", id))
    internal fun enableLightRule(id: String, enabled: Boolean) = experience(JSONObject().put("command", "lightsRuleEnabled")
        .put("id", id).put("enabled", enabled))
    internal fun saveScene(scene: LightScene) = experience(JSONObject().put("command", "lightsSceneSave")
        .put("scene", LightExperienceCodec.sceneJson(scene)))
    internal fun deleteScene(id: String) = experience(JSONObject().put("command", "lightsSceneDelete").put("id", id))
    internal fun saveLocation(location: LightLocation?) = experience(JSONObject().put("command", "lightsLocation").also {
        if (location == null) it.put("clear", true) else it.put("location", LightExperienceCodec.locationJson(location))
    })
    /** [id] null is the all-light setting; [variation] null makes that light follow it again. */
    internal fun variation(id: Long?, variation: LightVariation?) = experience(JSONObject().put("command", "lightsVariation")
        .also { request ->
            id?.let { request.put("id", it) }
            if (variation == null) request.put("clear", true) else LightVariationCodec.put(request, "variation", variation)
        })
    /** [variation] null makes the group follow the all-light variation again. */
    internal fun groupVariation(id: String, variation: LightVariation?) = experience(JSONObject()
        .put("command", "lightsGroupVariation").put("groupId", id)
        .also { if (variation == null) it.put("clear", true) else LightVariationCodec.put(it, "variation", variation) })
    /** The whole light-show setting; Samsung validates and saves it. */
    internal fun dance(dance: LightDance) = experience(JSONObject().put("command", "lightsDance")
        .put("dance", LightDanceCodec.json(dance)))
    internal fun applyScene(id: String) = run(null) { mode ->
        if (mode == HomeDjMode.SERVER) show(Lights.applyScene(app, id))
        else remote(JSONObject().put("command", "lightsSceneApply").put("id", id))
    }
    /** Scene editor: the lights show [draft] at once; latest-wins, sent even while a save runs. */
    internal fun previewScene(draft: Map<Long, LightChange>) {
        preview.show(draft, mutableState.value.snapshot.bulbs)
        mutableState.update { it.copy(sceneBase = preview.base) }
        sender.offer(PREVIEW, ::sendPreview)
    }

    /** Scene editor closed: every previewed light goes back to its recorded state. */
    internal fun endScenePreview() {
        if (!preview.open) return
        preview.end()
        sender.offer(PREVIEW, ::sendPreview)
    }

    private suspend fun sendPreview(mode: HomeDjMode?) {
        val changes = preview.take()
        if (changes.isEmpty()) return
        var confirmed = false
        try {
            if (mode == HomeDjMode.SERVER) show(Lights.preview(app, changes)) else remote(LightScenePreview.request(changes))
            confirmed = mutableState.value.actionSucceeded
        } finally {
            if (!confirmed) preview.failed(changes)
        }
    }

    internal fun permanentlyOff(id: Long, off: Boolean) = run(null) { mode ->
        if (mode == HomeDjMode.SERVER) show(Lights.permanentlyOff(app, id, off))
        else remote(JSONObject().put("command", "lightsPermanentlyOff").put("id", id).put("off", off))
    }
    internal fun readLight(id: Long) = run("Checking the light…") { mode ->
        if (mode == HomeDjMode.SERVER) show(Lights.readLight(app, id))
        else remote(JSONObject().put("command", "lightsRead").put("id", id))
    }
    internal fun runLightRule(id: String) = run(null) { mode ->
        if (mode == HomeDjMode.SERVER) show(Lights.runSavedRule(app, id))
        else remote(JSONObject().put("command", "lightsRuleRun").put("id", id))
    }
    private fun experience(request: JSONObject) = run(null) { mode ->
        if (mode == HomeDjMode.SERVER) show(Lights.editExperience(app, request)) else remote(request)
    }

    private fun show(outcome: Lights.Outcome) = mutableState.update {
        it.copy(message = outcome.message, actionSucceeded = outcome.ok)
    }

    private suspend fun remote(request: JSONObject, replyTimeoutMillis: Int = REPLY_MILLIS) {
        val host = prefs.savedHost
        val key = RemoteChannel.decodeKey(prefs.savedClientKey)
        if (host.isBlank() || key == null) {
            return mutableState.update { it.copy(message = "Connect to the Samsung in Settings first.") }
        }
        val reply = withContext(Dispatchers.IO) {
            // Changing every light can take a while; Samsung sends them one after another.
            RemoteChannel.exchange(host, key, request, replyTimeoutMillis = replyTimeoutMillis)
        }
        val lights = reply.optJSONObject("lights")
        lights?.let(LightsCodec::snapshot)?.let(::showSnapshot)
        mutableState.update {
            it.copy(
                remoteSetupSupported = if (lights != null) reply.optBoolean("lightsSetup") else it.remoteSetupSupported,
                variationSupported = if (lights != null) reply.optBoolean("lightsVariation") else it.variationSupported,
                groupVariationSupported = if (lights != null) reply.optBoolean("lightsGroupVariation") else it.groupVariationSupported,
                juggleSupported = if (lights != null) reply.optBoolean("lightsJuggle") else it.juggleSupported,
                danceSupported = if (lights != null) reply.optBoolean("lightsDance") else it.danceSupported,
                permanentlyOffSupported = if (lights != null) reply.optBoolean("lightsPermanentlyOff") else it.permanentlyOffSupported,
                scenePlaylistsSupported = if (lights != null) reply.optBoolean("lightsScenePlaylists") else it.scenePlaylistsSupported,
                arrivalSupported = if (lights != null) reply.optBoolean("lightsArrival") else it.arrivalSupported,
                danceStatus = if (lights != null) reply.optString("danceStatus").take(60) else it.danceStatus,
                discovered = if (request.optString("command") == "lightsDiscover" && reply.optBoolean("ok"))
                    RemoteLightSetup.discovered(reply) else it.discovered,
                discoveryComplete = it.discoveryComplete ||
                    (request.optString("command") == "lightsDiscover" && reply.optBoolean("ok")),
                exactAlarms = reply.optBoolean("exactAlarms", it.exactAlarms),
                actionSucceeded = reply.optBoolean("ok"),
                message = when {
                    reply.optBoolean("ok") -> reply.optString("message").ifBlank { null }
                    reply.optString("error") == "Unknown command" -> "Update HomeDJ on the Samsung to control lights."
                    else -> reply.optString("error").ifBlank { COMMAND_FAILED }
                },
            )
        }
    }

    /** Holds owner state while a newer live value is queued, so controls don't jump back to an older step. */
    private fun showSnapshot(snapshot: LightsSnapshot) {
        if (sender.hasPending) { held = snapshot; return }
        held = null
        mutableState.update { it.copy(snapshot = snapshot, loaded = true) }
    }

    /**
     * Direct light changes while a control moves: latest-wins per target and field, never marked
     * busy so the control stays usable. Ignored while a first-wins operation runs, and vice versa.
     */
    private fun live(key: Any, block: suspend (HomeDjMode?) -> Unit) {
        if (job?.isCompleted == false) return
        sender.offer(key, block)
    }

    private suspend fun sendLive(block: suspend (HomeDjMode?) -> Unit) {
        val mode = modeStore.mode.first()
        try {
            block(mode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A timed-out LAN write is an unknown outcome; the next reply or refresh shows Samsung's state.
            Lights.logFailure("Live light change", failure)
            mutableState.update { it.copy(actionSucceeded = false, message = when {
                mode == HomeDjMode.SERVER -> PAIR_OR_ANSWER_FAILED
                failure is SocketTimeoutException -> "The Samsung didn't confirm the change in time. Check the latest state."
                else -> SERVER_UNAVAILABLE
            }) }
        }
    }

    /** First-wins: a tap while a Lights operation runs is ignored. */
    private fun run(progress: String?, reconcileOnDisconnect: Boolean = false, block: suspend (HomeDjMode?) -> Unit) {
        if (job?.isCompleted == false) return
        job = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, actionSucceeded = false, message = progress ?: it.message) }
            // A direct change or scene preview still being sent lands first, e.g. Save right after a drag.
            sender.idle()
            val mode = modeStore.mode.first()
            try {
                block(mode)
            } catch (failure: TimeoutException) {
                Lights.logFailure("Light pairing", failure)
                mutableState.update { it.copy(message = RemoteLightSetup.PAIR_TIMEOUT_MESSAGE) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                if (mode != HomeDjMode.CONTROLLER || (!reconcileOnDisconnect && failure !is SocketTimeoutException)) {
                    Lights.logFailure("Lights action", failure)
                    mutableState.update { it.copy(message = if (mode == HomeDjMode.SERVER) PAIR_OR_ANSWER_FAILED else SERVER_UNAVAILABLE) }
                    return@launch
                }
                // Unknown outcome: Samsung may still have changed the lights, so show its state.
                Lights.logFailure("Lights request", failure)
                mutableState.update { it.copy(message = "The Samsung didn't confirm in time. Showing its latest state.") }
                try {
                    remote(JSONObject().put("command", "lights"))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (retry: Exception) {
                    Lights.logFailure("Lights refresh", retry)
                }
                mutableState.update { it.copy(actionSucceeded = false,
                    message = "The Samsung didn't confirm the change in time. Check the latest state before trying again.") }
            } catch (failure: Exception) {
                Lights.logFailure("Lights action", failure)
                mutableState.update {
                    it.copy(message = if (mode == HomeDjMode.SERVER) PAIR_OR_ANSWER_FAILED else SERVER_UNAVAILABLE)
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun LightChange.fields() = listOf(on != null, level != null, color != null)

    private companion object {
        const val REPLY_MILLIS = 45_000
        const val PREVIEW = "scenePreview"
        const val COMMAND_FAILED = "Command failed. Check Wi-Fi connection."
        const val SERVER_UNAVAILABLE = "Server unavailable or pairing code incorrect."
        const val BLUETOOTH_PAIR_FAILED = "The light didn't join. Reset it until it blinks, keep it near this phone, " +
            "and check the 2.4 GHz Wi-Fi name and password."
        const val PAIR_OR_ANSWER_FAILED = "The light did not respond. For pairing, create a new share code and try again."
    }
}
