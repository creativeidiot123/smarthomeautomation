package dev.homedj.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.net.InetAddress
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Runs the house rules: each [BulbWatchPolicy.TICK_MILLIS] it decides per watched light whether to
 * ask it for its boot identity over Matter (every [BulbWatchPolicy.POLL_MILLIS] while it answers)
 * or, while it is unpowered, only ping its address, and runs the rules its switch matched, one
 * after another. Launched on Main inside RemoteServerService (its wake lock, its player) and ends
 * with it; watch state, addresses and the song `music off` paused are memory only, so a restart
 * only forgets an outage in progress.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class HouseRulesRunner(context: Context, private val player: () -> Player?, private val casting: () -> Boolean) {
    private val app = context.applicationContext
    private val watches = HashMap<Long, BulbWatch>()
    /** `elapsedRealtime` of each light's last Matter ask. */
    private val matterAt = HashMap<Long, Long>()
    /** Address that answered a ping while the light was up; never logged or stored. */
    private val pingable = HashMap<Long, String>()
    /** Lights whose ping was tried since they last came back; a light that never answers keeps Matter polls. */
    private val pingTried = HashSet<Long>()
    /** Outage start whose lost Matter session a background ask already retired, per light. */
    private val retired = HashMap<Long, Long>()
    /** `starts` value each light acknowledged in this service's life (null = as before), and the last try. */
    private val startedUp = HashMap<Long, Int?>()
    private val startTriedAt = HashMap<Long, Long>()
    /** Media ID `music off` paused; `music on` resumes only it, and only if nobody pressed play since. */
    private var pausedId: String? = null

    suspend fun run(): Unit = try { watchLoop() } finally { darkLights.value = emptySet() }

    private suspend fun watchLoop(): Unit = coroutineScope {
        while (true) {
            delay(tick())
            darkLights.value = dark()
            if (player()?.playWhenReady == true) pausedId = null
            val parse = HouseScript.parse(HouseRules.state(app).value)
            val rules = parse.rules
            val bulbs = Lights.state(app).value.bulbs
            val watched = rules.groupBy { rule -> bulbs.firstOrNull { it.name.equals(rule.light, ignoreCase = true) }?.id }
                .filterKeys { it != null }.mapKeys { it.key ?: 0L }
            listOf(watches.keys, matterAt.keys, pingable.keys, pingTried, retired.keys).forEach { it.retainAll(watched.keys) }
            if (watched.isEmpty()) continue
            if (!onWifi()) {
                watches.replaceAll { _, watch -> BulbWatchPolicy.offWifi(watch) }
                continue
            }
            watched.forEach { (id, own) -> watch(id, own) }
            parse.starts.forEach { start -> bulbs.firstOrNull { it.name.equals(start.light, ignoreCase = true) }?.let { startUp(it.id, start.value) } }
        }
    }

    /**
     * Puts a `starts` line into the bulb (its StartUpOnOff), in the background and at most once a
     * minute while it is not acknowledged; never while the bulb is dark. Kept by the bulb itself.
     */
    private fun CoroutineScope.startUp(id: Long, value: Int?) {
        val now = SystemClock.elapsedRealtime()
        if ((startedUp.containsKey(id) && startedUp[id] == value) || id in dark() || id in Lights.state(app).value.bulbs.permanentlyOff() ||
            startTriedAt[id]?.let { now - it < BulbWatchPolicy.START_RETRY_MILLIS } == true) return
        startTriedAt[id] = now
        launch {
            try {
                MatterLights.startUp(app, id, value)
                startedUp[id] = value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Lights.logFailure("Light power-on setting", failure)
            }
        }
    }

    /**
     * Once per outage, asks the dark bulb over Matter in the background: it fails, and that retires
     * the session the bulb lost with its power, so its return needs one fresh handshake rather than
     * a 4 s wait on the dead one. In the background so pings, and `turned off` holds, keep their pace.
     */
    private fun CoroutineScope.retire(id: Long, outage: Long) {
        if (retired[id] == outage) return
        retired[id] = outage
        launch { matter(id) }
    }

    private fun tick(): Long =
        if (watches.values.any { it.offlineSince != null }) BulbWatchPolicy.DARK_TICK_MILLIS else BulbWatchPolicy.TICK_MILLIS

    private suspend fun CoroutineScope.watch(id: Long, rules: List<HouseRule>) {
        val watch = watches[id] ?: BulbWatch()
        val address = pingable[id]
        fun holds(trigger: HouseTrigger) = rules.filter { it.trigger == trigger && it.seconds > 0 }
            .map { it.seconds * 1000L }.toSet()
        val offHolds = holds(HouseTrigger.TURNED_OFF)
        val boot = when (BulbWatchPolicy.check(SystemClock.elapsedRealtime(), matterAt[id], address != null)) {
            BulbCheck.WAIT -> return
            BulbCheck.PING -> {
                val answered = address != null && ping(address)
                val now = SystemClock.elapsedRealtime()
                when (BulbWatchPolicy.afterPing(watch, now, matterAt[id], answered, othersDark = (dark() - id).isNotEmpty())) {
                    BulbStep.NOTHING -> return
                    BulbStep.MATTER -> matter(id)
                    BulbStep.SILENT -> {
                        val outage = watch.offlineSince ?: now
                        if (now - outage >= BulbWatchPolicy.retireAfter(offHolds)) retire(id, outage)
                        null
                    }
                }
            }
            BulbCheck.MATTER -> matter(id)
        }
        if (boot != null && (id !in pingTried || watch.offlineSince != null)) learnPing(id)
        val (next, events) = BulbWatchPolicy.next(watch, boot, SystemClock.elapsedRealtime(),
            offHolds, holds(HouseTrigger.TURNED_ON))
        watches[id] = next
        if (events.isEmpty() || ModeStore(app).mode.first() != HomeDjMode.SERVER) return
        val before = Lights.state(app).value
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        var ran = false
        events.forEach { event ->
            rules.filter { HouseScript.matches(it, event) && it.window?.contains(now) != false }.forEach { rule ->
                ran = true
                RemoteActivity.record(RemoteActivity.SAMSUNG, "House rule: ${rule.light} ${rule.trigger.words}")
                val current = Lights.state(app).value
                HouseScript.steps(rule, now) { light, isNow ->
                    level((if (isNow) current else before).bulbs.firstOrNull { it.name.equals(light, ignoreCase = true) })
                }
                    .forEach { step -> safely("House rule step") { perform(step) } }
            }
        }
        // Power came back: the bulb shows its own start-up state. Read after the steps, so they are not delayed.
        if (events.any { it.trigger == HouseTrigger.TURNED_ON && it.millis == 0L }) safely("Light check") {
            outcome("Light check", Lights.readLight(app, id))
        }
        // A switch seen outside every rule's time or length still shows, so detection is visible.
        if (!ran) BulbWatchPolicy.shown(events)?.let { event ->
            val name = before.bulbs.firstOrNull { it.id == id }?.name ?: "Light"
            RemoteActivity.record(RemoteActivity.SAMSUNG, "$name ${event.trigger.words}: no house rule for now")
        }
    }

    private suspend fun matter(id: Long): BulbBoot? {
        matterAt[id] = SystemClock.elapsedRealtime()
        return try {
            MatterLights.boot(app, id, BulbWatchPolicy.ANSWER_MILLIS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null // An unreachable bulb fails to connect; that is the "off" this watch looks for.
        }
    }

    /** Trusts a ping for [id] only if its current address answers while the light is known to be up. */
    private suspend fun learnPing(id: Long) {
        pingTried += id
        pingable -= id
        val address = try {
            MatterLights.address(app, id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Lights.logFailure("Light address", failure)
            null
        } ?: return
        if (ping(address)) pingable[id] = address
    }

    private suspend fun ping(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            InetAddress.getByName(address).isReachable(BulbWatchPolicy.PING_MILLIS)
        } catch (failure: java.io.IOException) {
            false
        }
    }

    /**
     * Watched lights that are unpowered now. Steps leave them alone: a command to a dark bulb waits
     * up to 20 s for an answer that cannot come and would hold back every step after it.
     */
    private fun dark(): Set<Long> = watches.filterValues { it.offlineSince != null }.keys

    companion object {
        private val darkLights = MutableStateFlow<Set<Long>>(emptySet())
        /** Projection of [dark] as of the last tick, for scenes; empty while no runner watches. Memory only. */
        val dark: StateFlow<Set<Long>> = darkLights.asStateFlow()
    }

    private suspend fun perform(step: HouseStep) {
        val snapshot = Lights.state(app).value
        fun <T> named(items: List<T>, name: String, nameOf: (T) -> String) = items.firstOrNull { nameOf(it).equals(name, ignoreCase = true) }
        when (step) {
            is HouseStep.Light -> {
                val bulb = step.target?.let { named(snapshot.bulbs, it, Bulb::name) }
                val group = step.target?.let { named(snapshot.configuration.groups, it, LightsGroup::name) }
                when {
                    step.target == null -> outcome("All lights", Lights.change(app, null, step.change, dark() + step.except.flatMap { name ->
                        named(snapshot.bulbs, name, Bulb::name)?.let { listOf(it.id) }
                            ?: named(snapshot.configuration.groups, name, LightsGroup::name)?.bulbs.orEmpty()
                    }))
                    bulb != null -> if (bulb.id !in dark()) outcome(bulb.name, Lights.change(app, bulb.id, step.change))
                    group != null -> outcome(group.name, Lights.changeGroup(app, group.id, step.change, dark()))
                    else -> missing(step.target)
                }
            }
            is HouseStep.Adjust -> {
                val bulb = step.target?.let { named(snapshot.bulbs, it, Bulb::name) }
                val group = step.target?.let { named(snapshot.configuration.groups, it, LightsGroup::name) }
                val ids = when {
                    step.target == null -> snapshot.bulbs.map(Bulb::id)
                    bulb != null -> listOf(bulb.id)
                    group != null -> group.bulbs
                    else -> return missing(step.target)
                }
                snapshot.bulbs.filter { it.id in ids && it.id !in dark() }.forEach { light ->
                    val from = level(light)
                    if (from == 0 && step.delta < 0) return@forEach
                    outcome(light.name, Lights.change(app, light.id, LightChange(on = true, level = (from + step.delta).coerceIn(1, 100))))
                }
            }
            is HouseStep.Scene -> {
                val scene = named(snapshot.scenes, step.name, LightScene::name) ?: return missing(step.name)
                if (step.on) outcome(scene.name, Lights.applyScene(app, scene.id, dark()))
                else (scene.changes.keys - dark()).forEach { outcome(scene.name, Lights.change(app, it, LightChange(on = false))) }
            }
            is HouseStep.Schedule -> {
                val rules = if (step.name == null) snapshot.automation.rules
                    else listOf(named(snapshot.automation.rules, step.name, LightsRule::name) ?: return missing(step.name))
                rules.filter { it.enabled != step.on }.forEach { rule ->
                    outcome(rule.name.ifBlank { "Light schedule" }, Lights.editExperience(app, JSONObject()
                        .put("command", "lightsRuleEnabled").put("id", rule.id).put("enabled", step.on)))
                }
            }
            HouseStep.SchedulesNow -> outcome("Schedules", Lights.followSchedules(app))
            is HouseStep.Music -> music(step.on)
            is HouseStep.If -> Unit // HouseScript.steps already resolved it.
        }
    }

    private fun music(on: Boolean) {
        val active = player() ?: return
        if (casting()) return
        if (!on) {
            if (Util.shouldShowPlayButton(active)) return
            Util.handlePauseButtonAction(active)
            pausedId = active.currentMediaItem?.mediaId
            RemoteActivity.record(RemoteActivity.SAMSUNG, "House rule: music paused")
        } else if (PresencePolicy.samsungShouldResume(true, pausedId, active.playWhenReady, active.currentMediaItem?.mediaId)) {
            pausedId = null
            Util.handlePlayButtonAction(active)
            RemoteActivity.record(RemoteActivity.SAMSUNG, "House rule: music resumed")
        }
    }

    /** The rule's own feed line says what ran; only problems add another. */
    private fun outcome(label: String, outcome: Lights.Outcome) {
        if (!outcome.ok) RemoteActivity.record(RemoteActivity.SAMSUNG, "House rule, $label: ${outcome.message}")
    }

    /** Brightness on the record: 0 when off or unknown, 100 when on at an unknown level. */
    private fun level(bulb: Bulb?): Int = if (bulb?.on == true) bulb.level ?: 100 else 0

    /** One failing step or check never stops the steps after it or the watch loop. */
    private suspend fun safely(operation: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Lights.logFailure(operation, failure)
        }
    }

    private fun missing(name: String): Unit =
        RemoteActivity.record(RemoteActivity.SAMSUNG, "House rule: nothing called \"$name\" any more")

    private fun onWifi(): Boolean {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
