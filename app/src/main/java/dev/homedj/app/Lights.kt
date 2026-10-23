package dev.homedj.app

import android.content.Context
import android.util.Log
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Samsung's single owner of the paired bulbs, their last acknowledged state and the Lights
 * automation (SharedPreferences `homedj_lights`). Mutations re-check the Server role and run one
 * at a time; [MatterLights] does the Matter I/O. The OnePlus reaches this only through the LAN
 * `lights*` commands in [handleRemote]. Explicit reads reconcile external bulb changes.
 */
internal object Lights {
    private const val TAG = "HomeDJLights"
    private const val LEGACY_NODE = "node_id"
    private const val BULBS = "bulbs"
    private const val AUTOMATION = "automation"
    private const val CONFIGURATION = "configuration"
    private const val EXPERIENCE = "experience"
    /** Bulbs presence turned off; only these come back on when the OnePlus is home. */
    private const val PRESENCE_OFF = "presence_off"
    /** Epoch milliseconds of the last rules run; rules due after it run next. */
    private const val RULES_RAN_AT = "rules_ran_at"
    /** Last Wi-Fi name a new bulb joined through Bluetooth setup; never the password. */
    private const val WIFI_NAME = "wifi_name"
    private const val DANCE_HOLD_MILLIS = 3_000L
    /** Per-light limit for scenes and previews, sent to every light at once; lit lights answer in well under 1 s. */
    private const val LIGHT_MILLIS = 6_000L
    private const val NO_ANSWER ="The light did not respond. Check it is powered and on this Wi-Fi."
    val REMOTE_COMMANDS = setOf("lights", "lightsSet", "lightsRename", "lightsAutomation",
        "lightsGroupSave", "lightsGroupDelete", "lightsGroupSet", "lightsPreferences",
        "lightsSceneSave", "lightsSceneDelete", "lightsSceneApply", "lightsRuleSave", "lightsRuleDelete",
        "lightsRuleEnabled", "lightsRuleRun", "lightsLocation", "lightsPresence", "lightsRead", "lightsVariation", "lightsGroupVariation", "lightsPreview", "lightsDance", "lightsPermanentlyOff") +
        RemoteLightSetup.COMMANDS

    /** [message] is a fixed, safe line for the screen, the LAN reply and the activity feed. */
    class Outcome(val ok: Boolean, val message: String)

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LightsSnapshot())
    @Volatile private var loaded = false
    /** Guarded by [mutex] after [load]. */
    private var presenceOff: Set<Long> = emptySet()
    /** Guarded by [mutex]: elapsed-realtime end of a bulb's native fade or dance step; variation waits for it. */
    private val fadeHolds = HashMap<Long, Long>()

    fun state(context: Context): StateFlow<LightsSnapshot> {
        load(context)
        return mutableState.asStateFlow()
    }

    /** Commissions a bulb (up to 2 min); [wifi] is Samsung-only Bluetooth setup. Recorded only once commissioning succeeded. */
    suspend fun pair(context: Context, name: String, setupCode: String, discriminator: Int? = null,
                     wifi: LightWifi? = null): Outcome = serverOnly(context) {
        val input = RemoteLightSetup.pairing(name, setupCode, discriminator)
            ?: return@serverOnly Outcome(false, "Invalid Matter code, name or light selection")
        if (wifi != null && !validLightWifi(wifi)) return@serverOnly Outcome(false, WIFI_INVALID)
        val bulbs = mutableState.value.bulbs
        if (bulbs.size >= LightsPolicy.MAX_BULBS) return@serverOnly Outcome(false, "HomeDJ supports up to 16 lights.")
        val node = MatterLights.pair(context, input.setupCode, wifi)
        val bulb = Bulb(node, LightsPolicy.validName(input.name) ?: LightsPolicy.defaultName(bulbs))
        commit(context, mutableState.value.let { it.copy(bulbs = it.bulbs + bulb) })
        if (wifi != null) prefs(context).edit().putString(WIFI_NAME, wifi.ssid).apply()
        Outcome(true, "${bulb.name} paired.")
    }

    suspend fun wifiName(context: Context): String =
        withContext(Dispatchers.IO) { prefs(context).getString(WIFI_NAME, null).orEmpty() }

    /** [target] null changes every bulb, one after another. [skip]: unpowered bulbs house rules leave alone. */
    suspend fun change(context: Context, target: Long?, change: LightChange, skip: Set<Long> = emptySet()): Outcome = serverOnly(context) {
        if (!LightsPolicy.valid(change)) return@serverOnly Outcome(false, "Invalid light change")
        val bulbs = LightsPolicy.targets(mutableState.value.bulbs, target?.let(::listOf)).filterNot { it.id in skip }
        if (bulbs.isEmpty()) return@serverOnly Outcome(false, if (target == null) "No lights paired" else "Light not found")
        if (target != null && bulbs.single().permanentlyOff) return@serverOnly Outcome(false, offMessage(bulbs.single()))
        if (target == null) rememberAll(context, change)
        val done = apply(context, bulbs.map(Bulb::id), change, individual = target != null) { off, changed -> off - changed.toSet() }
        outcome(done, bulbs.size, if (target == null) "All-light settings applied"
            else describe(change, bulbs.single().name))
    }

    suspend fun rename(context: Context, id: Long, name: String): Outcome = serverOnly(context) {
        val valid = LightsPolicy.validName(name) ?: return@serverOnly Outcome(false, "Invalid light name")
        val snapshot = mutableState.value
        if (snapshot.bulbs.none { it.id == id }) return@serverOnly Outcome(false, "Light not found")
        commit(context, snapshot.copy(bulbs = snapshot.bulbs.map { if (it.id == id) it.copy(name = valid) else it }))
        Outcome(true, "Renamed to $valid")
    }

    /** Only the flag changes; the light gets no command either way, so it stays as it is until something next applies. */
    suspend fun permanentlyOff(context: Context, id: Long, off: Boolean): Outcome = serverOnly(context) {
        val snapshot = mutableState.value
        if (snapshot.bulbs.none { it.id == id }) return@serverOnly Outcome(false, "Light not found")
        commit(context, snapshot.copy(bulbs = snapshot.bulbs.map { if (it.id == id) it.copy(permanentlyOff = off) else it }))
        Outcome(true, if (off) "Light set to permanently off; nothing will change it" else "Light is back under HomeDJ control")
    }

    suspend fun saveGroup(context: Context, group: LightsGroup): Outcome = serverOnly(context) {
        val snapshot = mutableState.value
        val config = snapshot.configuration
        if (!LightsGroups.valid(group, config, snapshot.bulbs) ||
            (config.groups.none { it.id == group.id } && config.groups.size >= LightsGroups.MAX_GROUPS)
        ) return@serverOnly Outcome(false, "Choose a name and lights that aren't in another group.")
        val previous = config.groups.firstOrNull { it.id == group.id }
        // Variation is edited only by lightsGroupVariation, so a group save never replaces it.
        val saved = group.copy(name = group.name.trim(), variation = previous?.variation)
        val added = saved.bulbs - previous?.bulbs.orEmpty().toSet()
        commit(context, snapshot.copy(configuration = LightsGroups.rejoin(config.copy(groups =
            if (previous != null) config.groups.map { if (it.id == group.id) saved else it }
            else config.groups + saved), added), variation = LightVariations.joined(snapshot.variation, added)))
        if (added.isEmpty() || saved.preferences.change.isEmpty) Outcome(true, "Group saved")
        else {
            val done = apply(context, added, saved.preferences.change) { off, changed -> off - changed.toSet() }
            outcome(done, added.size, "Group saved and applied to added lights")
        }
    }

    suspend fun deleteGroup(context: Context, id: String): Outcome = serverOnly(context) {
        val snapshot = mutableState.value
        commit(context, snapshot.copy(
            configuration = snapshot.configuration.copy(groups = snapshot.configuration.groups.filterNot { it.id == id }),
            automation = snapshot.automation.copy(rules = snapshot.automation.rules.filterNot { it.groupId == id }),
        ))
        LightsWork.arm(context, LightExperience.next(mutableState.value, ZonedDateTime.now()))
        Outcome(true, "Group removed; its lights and individual settings are kept")
    }

    suspend fun changeGroup(context: Context, id: String, change: LightChange, skip: Set<Long> = emptySet()): Outcome = serverOnly(context) {
        if (!LightsPolicy.valid(change)) return@serverOnly Outcome(false, "Invalid light change")
        val group = mutableState.value.configuration.groups.firstOrNull { it.id == id }
            ?: return@serverOnly Outcome(false, "Group not found")
        if (group.bulbs.isEmpty()) return@serverOnly Outcome(false, "No lights in this group")
        rememberGroup(context, id, change)
        val ids = group.bulbs - skip
        val done = apply(context, ids, change) { off, changed -> off - changed.toSet() }
        outcome(done, ids.size, "Group settings applied")
    }

    /** Explicit preferences are durable intent; last acknowledged bulb state remains separate. */
    suspend fun preferences(context: Context, id: Long, preferences: LightPreferences): Outcome = serverOnly(context) {
        val snapshot = mutableState.value
        if (snapshot.bulbs.none { it.id == id } || !LightsGroups.valid(preferences)) {
            return@serverOnly Outcome(false, "Invalid light preferences")
        }
        commit(context, snapshot.copy(configuration = snapshot.configuration.copy(
            individual = snapshot.configuration.individual + (id to preferences))))
        val inherited = LightsGroups.group(snapshot.configuration, id)?.preferences?.change ?: LightChange()
        val next = LightsGroups.merge(inherited, preferences.change)
        if (next.isEmpty) Outcome(true, "Light preferences saved") else {
            val done = apply(context, listOf(id), next) { off, changed -> off - changed.toSet() }
            outcome(done, 1, "Light preferences saved and applied")
        }
    }

    /** Asks the bulb to drop HomeDJ's fabric, and forgets it here even if the bulb does not answer. */
    suspend fun forget(context: Context, id: Long): Outcome = serverOnly(context) {
        if (mutableState.value.bulbs.none { it.id == id }) return@serverOnly Outcome(false, "Light not found")
        var answered = false
        try {
            MatterLights.forget(context, id)
            answered = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure("Light forget", failure)
        } finally {
            withContext(NonCancellable) {
                commit(context, mutableState.value.let { s -> s.copy(bulbs = s.bulbs.filterNot { it.id == id },
                    configuration = LightsGroups.withoutBulb(s.configuration, id),
                    variation = LightVariations.withoutBulb(s.variation, id)) },
                    presenceOff - id)
            }
        }
        Outcome(true, if (answered) "Light forgotten." else "The light did not answer; HomeDJ forgot it anyway.")
    }

    suspend fun saveAutomation(context: Context, automation: LightsAutomation, legacy: Boolean = false): Outcome = serverOnly(context) {
        val compatible = LightExperience.preserveRuleFeatures(automation, mutableState.value.automation)
        val next = if (legacy) LightsGroups.preserveRuleTargets(compatible, mutableState.value.automation) else compatible
        if (next.rules.size > LightsPolicy.MAX_RULES || !next.rules.all(LightsPolicy::valid) ||
            next.rules.map(LightsRule::id).distinct().size != next.rules.size ||
            next.rules.any { rule -> rule.groupId != null &&
                mutableState.value.configuration.groups.none { it.id == rule.groupId } }
        ) return@serverOnly Outcome(false, "Invalid light automation")
        val now = ZonedDateTime.now()
        // Rules saved now start from now; a time that already passed today waits for its next day.
        commit(context, mutableState.value.copy(automation = next), ranAt = now.toInstant().toEpochMilli())
        LightsWork.arm(context, LightExperience.next(mutableState.value, now))
        Outcome(true, "Light automation saved")
    }

    /** Alarm, boot or clock change: applies the rules that came due since the last run, then re-arms. */
    suspend fun runRules(context: Context) {
        val outcome = serverOnly(context) {
            val now = ZonedDateTime.now()
            val nowMillis = now.toInstant().toEpochMilli()
            val ranAt = withContext(Dispatchers.IO) { prefs(context).getLong(RULES_RAN_AT, nowMillis) }
            val rules = mutableState.value.automation.rules
            val due = LightsPolicy.due(rules, ranAt, now, mutableState.value.location)
            // Recorded first: a crash mid-run skips the rest rather than repeating it later.
            commit(context, mutableState.value, ranAt = nowMillis)
            LightsWork.arm(context, LightExperience.next(mutableState.value, now))
            due.forEach { rule ->
                runRule(context, rule)
            }
            Outcome(true, "")
        }
        if (!outcome.ok) LightsWork.arm(context, null)
    }

    /** OnePlus left ([home] false) or came back; Samsung's own Lights settings decide what happens. */
    suspend fun presence(context: Context, home: Boolean): Outcome = serverOnly(context) {
        val snapshot = mutableState.value
        if (!home) {
            val ids = LightsGroups.presenceTargets(snapshot, presenceOff, home = false)
            val done = apply(context, ids, LightChange(on = false)) { off, changed -> off + changed }
            if (ids.isNotEmpty()) RemoteActivity.record(RemoteActivity.SAMSUNG,
                outcome(done, ids.size, "Lights off: OnePlus left home").message)
        } else if (home) {
            val arrival = snapshot.automation
            // The arrival scene replaces restoring; arrival music comes only from the arrival playlist.
            if (arrival.homeScene != null) RemoteActivity.record(RemoteActivity.SAMSUNG,
                "OnePlus is home: " + applySceneLocked(context, arrival.homeScene, music = false).message)
            else {
                val ids = LightsGroups.presenceTargets(snapshot, presenceOff, home = true)
                apply(context, ids, LightChange(on = true)) { _, _ -> emptySet() }
                if (ids.isNotEmpty()) RemoteActivity.record(RemoteActivity.SAMSUNG, "Lights on: OnePlus is home")
            }
            if (presenceOff.isNotEmpty()) commit(context, mutableState.value, emptySet())
            arrival.homePlaylist?.let { RemoteActivity.record(RemoteActivity.SAMSUNG, "OnePlus is home: " + ScenePlayback.start(
                context, HomeArrival.MUSIC_KEY + System.currentTimeMillis(), listOf(it), ifIdle = true)) }
        }
        Outcome(true, "")
    }

    /** One LAN `lights*` request; the reply always carries the current snapshot. */
    suspend fun handleRemote(context: Context, request: JSONObject): JSONObject {
        if (request.optString("command") in RemoteLightSetup.COMMANDS) {
            val reply = try {
                RemoteLightSetup.handle(request,
                    pair = { pair(context, it.name, it.setupCode, it.discriminator) },
                    forget = { forget(context, it) }, discover = { MatterLights.discover(context) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: java.util.concurrent.TimeoutException) {
                logFailure("Remote light pairing", failure)
                JSONObject().put("ok", false).put("error", RemoteLightSetup.PAIR_TIMEOUT_MESSAGE)
            } catch (failure: Exception) {
                logFailure("Remote light setup", failure)
                JSONObject().put("ok", false).put("error", NO_ANSWER)
            }
            return remoteSnapshot(context, reply)
        }
        val outcome = try {
            when (request.optString("command")) {
                "lights" -> null
                "lightsRead" -> readLight(context, request.optLong("id"))
                "lightsSceneSave", "lightsSceneDelete", "lightsRuleSave", "lightsRuleDelete", "lightsRuleEnabled", "lightsLocation", "lightsPresence", "lightsVariation", "lightsGroupVariation", "lightsDance" ->
                    editExperience(context, request)
                "lightsSceneApply" -> applyScene(context, request.optString("id"))
                "lightsPreview" -> LightScenePreview.changes(request.optJSONArray("changes"), state(context).value.bulbs)
                    ?.let { preview(context, it) } ?: Outcome(false, "Invalid light preview")
                "lightsRuleRun" -> serverOnly(context) {
                    mutableState.value.automation.rules.firstOrNull { it.id == request.optString("id") }
                        ?.let { runRule(context, it) } ?: Outcome(false, "Rule not found")
                }
                "lightsSet" -> change(context, if (request.has("id")) request.optLong("id") else null,
                    LightsCodec.change(request))
                "lightsRename" -> rename(context, request.optLong("id"), request.optString("name"))
                "lightsPermanentlyOff" -> if (request.opt("off") !is Boolean) Outcome(false, "Invalid light setting")
                    else permanentlyOff(context, request.optLong("id"), request.optBoolean("off"))
                "lightsGroupSave" -> request.optJSONObject("group")?.let(LightsGroupsCodec::group)
                    ?.let { saveGroup(context, it) } ?: Outcome(false, "Invalid group")
                "lightsGroupDelete" -> deleteGroup(context, request.optString("groupId"))
                "lightsGroupSet" -> changeGroup(context, request.optString("groupId"), LightsCodec.change(request))
                "lightsPreferences" -> request.optJSONObject("preferences")?.let(LightsGroupsCodec::preferences)
                    ?.let { preferences(context, request.optLong("id"), it) } ?: Outcome(false, "Invalid light preferences")
                "lightsAutomation" -> request.optJSONObject("automation")
                    ?.let { LightsCodec.automation(it, strict = true) }
                    ?.let { saveAutomation(context, it, legacy = request.optInt("groupsVersion") < 1) }
                    ?: Outcome(false, "Invalid light automation")
                else -> Outcome(false, "Unknown command")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure("Remote light command", failure)
            Outcome(false, NO_ANSWER)
        }
        val reply = JSONObject().put("ok", outcome?.ok ?: true)
        if (outcome != null) reply.put(if (outcome.ok) "message" else "error", outcome.message)
        return remoteSnapshot(context, reply)
    }

    private fun remoteSnapshot(context: Context, reply: JSONObject): JSONObject =
        reply.put("lights", LightsCodec.snapshotJson(state(context).value)).put("lightsVersion", 1)
            .put("lightsSetup", true).put("lightsVariation", true).put("lightsJuggle", true).put("lightsGroupVariation", true).put("exactAlarms", ScheduleAlarms.canSchedule(context))
            .put("lightsDance", true).put("lightsPermanentlyOff", true).put("lightsScenePlaylists", true).put("lightsArrival", true).put("danceStatus", LightDanceRunner.status.value)

    /** Small independent edits are serialized here; saving one rule never replaces another phone's rules. */
    suspend fun editExperience(context: Context, request: JSONObject): Outcome = serverOnly(context) {
        val s = mutableState.value
        val id = request.optString("id")
        val next = when (request.optString("command")) {
            "lightsSceneSave" -> request.optJSONObject("scene")?.let(LightExperienceCodec::scene)?.let { LightExperience.saveScene(s, it) }
            "lightsSceneDelete" -> LightExperience.deleteScene(s, id)
            "lightsRuleSave" -> request.optJSONObject("rule")?.let(LightsCodec::rule)
                ?.let { LightExperience.saveRule(s, it, System.currentTimeMillis()) }
            "lightsVariation", "lightsGroupVariation" -> LightVariations.edit(s, request)
            "lightsDance" -> LightDances.edit(s, request)
            "lightsRuleDelete" -> s.copy(automation = s.automation.copy(rules = s.automation.rules.filterNot { it.id == id }))
            "lightsRuleEnabled" -> if (request.opt("enabled") !is Boolean) null else
                LightExperience.enableRule(s, id, request.optBoolean("enabled"), System.currentTimeMillis())
            "lightsPresence" -> HomeArrival.edit(s, request)
            "lightsLocation" -> if (request.optBoolean("clear")) {
                if (s.automation.rules.any { it.time != "clock" }) null else s.copy(location = null)
            } else request.optJSONObject("location")?.let(LightExperienceCodec::location)?.let { s.copy(location = it) }
            else -> null
        } ?: return@serverOnly Outcome(false, "Invalid light settings. Check names, targets and solar location.")
        // A remotely created first rule must have a checkpoint before its first alarm fires.
        val firstRun = withContext(Dispatchers.IO) { if (prefs(context).contains(RULES_RAN_AT)) null else System.currentTimeMillis() }
        commit(context, next, ranAt = firstRun)
        LightsWork.arm(context, LightExperience.next(next, ZonedDateTime.now()))
        Outcome(true, "Light settings saved")
    }

    suspend fun applyScene(context: Context, id: String, skip: Set<Long> = emptySet()): Outcome =
        serverOnly(context) { applySceneLocked(context, id, skip) }

    /**
     * Scene editor preview: sends each change as given and records only the bulbs' new states;
     * preferences, groups, presence and variation stay untouched so restoring leaves no trace.
     */
    suspend fun preview(context: Context, changes: Map<Long, LightChange>): Outcome = serverOnly(context) {
        val off = mutableState.value.bulbs.permanentlyOff()
        val sent = changes.filterKeys { it !in off }
        val acknowledged = MatterLights.sendEach(context, commandsFor(sent.filterKeys { it !in HouseRulesRunner.dark.value }), LIGHT_MILLIS)
        val shown = sent.filterKeys(acknowledged::contains)
        if (shown.isNotEmpty()) withContext(NonCancellable) { commit(context, mutableState.value.let { s ->
            s.copy(bulbs = s.bulbs.map { bulb -> shown[bulb.id]?.let { LightsPolicy.applied(bulb, it) } ?: bulb })
        }) }
        outcome(shown.size, sent.size, "Light preview updated")
    }

    suspend fun readLight(context: Context, id: Long): Outcome = serverOnly(context) {
        val previous = mutableState.value.bulbs.firstOrNull { it.id == id } ?: return@serverOnly Outcome(false, "Light not found")
        if (previous.permanentlyOff) return@serverOnly Outcome(false, offMessage(previous))
        val values = MatterLights.read(context, id)
        if (ModeStore(context).mode.first() != HomeDjMode.SERVER) return@serverOnly Outcome(false, "Server role changed")
        val observed = lightFromReading(previous, values, System.currentTimeMillis())
        commit(context, mutableState.value.copy(bulbs = mutableState.value.bulbs.map { if (it.id == id) observed else it }))
        Outcome(true, "Light state checked")
    }

    /** House rules' `schedules now`: each light goes where its latest schedule of the past day left it. */
    suspend fun followSchedules(context: Context): Outcome = serverOnly(context) {
        val rules = LightExperience.latest(mutableState.value, ZonedDateTime.now())
        // Putting lights back is not a new scene start: linked music stays as it is.
        rules.forEach { runRule(context, it, music = false) }
        Outcome(true, if (rules.isEmpty()) "No light schedule ran in the past day" else "Lights follow their schedules")
    }

    suspend fun runSavedRule(context: Context, id: String): Outcome = serverOnly(context) {
        mutableState.value.automation.rules.firstOrNull { it.id == id }?.let { runRule(context, it) }
            ?: Outcome(false, "Rule not found")
    }

    private suspend fun applySceneLocked(context: Context, id: String, skip: Set<Long> = emptySet(), music: Boolean = true): Outcome {
        val scene = mutableState.value.scenes.firstOrNull { it.id == id } ?: return Outcome(false, "Scene not found")
        val (entries, total) = LightExperience.sceneTargets(mutableState.value, scene, skip + HouseRulesRunner.dark.value)
        if (entries.isEmpty()) return Outcome(false, "No paired lights remain in this scene")
        if (scene.variation != null) commit(context, mutableState.value.let {
            it.copy(variation = LightVariations.applyScene(it.variation, scene, entries.keys.filter(scene.changes::containsKey))) })
        if (ModeStore(context).mode.first() != HomeDjMode.SERVER) return outcome(0, total, "${scene.name} applied")
        val lights = outcome(applyEach(context, entries), total, "${scene.name} applied")
        // Linked music follows the lights, whichever way the scene was applied.
        val played = scene.playlists?.takeIf { music && it.isNotEmpty() }?.let { ScenePlayback.start(context, scene.id, it) }
        return if (played == null) lights else Outcome(lights.ok, lights.message + "; " + played)
    }

    /** [apply] with `individual` for many lights, each its own change, all sent at once; returns how many acknowledged. */
    private suspend fun applyEach(context: Context, changes: Map<Long, LightChange>): Int {
        commit(context, mutableState.value.let { s -> s.copy(configuration = LightsGroups.individualChanged(s.configuration, changes)) })
        val effective = changes.mapValues { (id, change) -> LightsGroups.effective(mutableState.value.configuration, id, change) }
        var acknowledged = emptySet<Long>()
        try {
            acknowledged = MatterLights.sendEach(context, commandsFor(effective), LIGHT_MILLIS)
            val now = android.os.SystemClock.elapsedRealtime()
            acknowledged.forEach { fadeHolds[it] = now + effective.getValue(it).fadeSeconds * 1000L }
        } finally {
            withContext(NonCancellable) { commit(context, mutableState.value.let { s -> s.copy(bulbs = s.bulbs.map { bulb ->
                if (bulb.id in acknowledged) LightsPolicy.applied(bulb, effective.getValue(bulb.id)) else bulb }) }, presenceOff - acknowledged) }
        }
        return acknowledged.size
    }

    private fun commandsFor(changes: Map<Long, LightChange>): Map<Long, List<MatterCommand>> =
        changes.mapValues { (id, change) -> LightsPolicy.commands(change, off = mutableState.value.bulbs.firstOrNull { it.id == id }?.on != true) }

    private suspend fun runRule(context: Context, rule: LightsRule, music: Boolean = true): Outcome {
        val result = if (rule.onlyHome && !LightExperience.homeConfirmed(RemoteServerService.running.value,
                RemoteServerService.onePlus.value.confirmedHomeAt, android.os.SystemClock.elapsedRealtime())) {
            Outcome(false, "Skipped: no recent confirmed Home check-in")
        } else if (rule.sceneId != null) applySceneLocked(context, rule.sceneId, music = music) else {
            val ids = LightsGroups.targets(mutableState.value, rule)
            if (ids.isEmpty()) Outcome(false, "Skipped: no paired lights in this target") else {
                if (rule.groupId != null) rememberGroup(context, rule.groupId, rule.change)
                else if (rule.bulbs == null) rememberAll(context, rule.change)
                val done = apply(context, ids, rule.change, individual = rule.groupId == null && rule.bulbs != null) { off, changed -> off - changed.toSet() }
                outcome(done, ids.size, describe(rule.change, "Scheduled lights"))
            }
        }
        val name = rule.name.ifBlank { "Light schedule" }
        commit(context, LightExperience.record(mutableState.value, System.currentTimeMillis(), name, result.message))
        RemoteActivity.record(RemoteActivity.SAMSUNG, "$name: ${result.message}")
        return result
    }

    /** An all-light change is the latest change for every bulb and group. */
    private suspend fun rememberAll(context: Context, change: LightChange) {
        val snapshot = mutableState.value
        commit(context, snapshot.copy(configuration = LightsGroups.allChanged(snapshot.configuration,
            snapshot.bulbs.map(Bulb::id), change)))
    }

    /** A group change is the latest change for its members, so they follow the group again. */
    private suspend fun rememberGroup(context: Context, id: String, change: LightChange) {
        val snapshot = mutableState.value
        val members = snapshot.configuration.groups.firstOrNull { it.id == id }?.bulbs.orEmpty()
        commit(context, snapshot.copy(configuration = LightsGroups.rejoin(snapshot.configuration.copy(groups =
            snapshot.configuration.groups.map { group ->
                if (group.id != id) group else group.copy(preferences = group.preferences.copy(
                    change = LightsGroups.merge(group.preferences.change, LightsGroups.preferenceChange(change))))
            }), members)))
    }

    /**
     * One live-variation step for [id] around its record, never committed. [source] is the light
     * whose colour it juggles, read here under the lock so a scene applied meanwhile wins. Null:
     * nothing sent (not Server, off, fading, or nothing to vary). Throws when the bulb did not acknowledge.
     */
    internal suspend fun vary(context: Context, id: Long, varied: Boolean, now: Long, random: kotlin.random.Random,
        source: Long? = null, swapped: Boolean = false, drift: VariationDrift = VariationDrift()): VariedLight? =
        mutex.withLock {
            load(context)
            if ((fadeHolds[id] ?: 0L) > now || id in mutableState.value.bulbs.permanentlyOff() ||
                ModeStore(context).mode.first() != HomeDjMode.SERVER) return@withLock null
            LightVariations.next(mutableState.value, id, varied, random, source, swapped, drift)
                ?.also { MatterLights.send(context, id, it.commands(), timeoutMillis = 6_000L) }
        }

    /**
     * One light-show send for [id], planned by [step] from its record and never committed; variation
     * leaves the light alone meanwhile. False: nothing sent (not Server, unknown, or nothing to show).
     */
    internal suspend fun dance(context: Context, id: Long, step: (Bulb) -> VariedLight?): Boolean = mutex.withLock {
        load(context)
        if (ModeStore(context).mode.first() != HomeDjMode.SERVER) return@withLock false
        val light = mutableState.value.bulbs.firstOrNull { it.id == id }?.takeUnless(Bulb::permanentlyOff)?.let(step) ?: return@withLock false
        val now = android.os.SystemClock.elapsedRealtime()
        fadeHolds[id] = maxOf(fadeHolds[id] ?: 0L, now + maxOf(DANCE_HOLD_MILLIS, light.millis.toLong()))
        MatterLights.send(context, id, light.commands(), timeoutMillis = 1_500L)
        true
    }

    internal fun logFailure(operation: String, failure: Exception) {
        val code = (failure as? MatterLights.MatterFailure)?.code
        Log.w(TAG, "$operation failed: " + failure.javaClass.simpleName + (code?.let { " $it" } ?: ""))
    }

    /**
     * Sends [change] to each bulb in turn and records each one as soon as it acknowledged; a
     * bulb that fails keeps its previous record. Returns how many acknowledged; a permanently-off
     * light gets nothing and counts as done, so it never reads as a failure. Callers hold [mutex].
     */
    private suspend fun apply(
        context: Context, ids: List<Long>, change: LightChange, individual: Boolean = false,
        presence: (Set<Long>, List<Long>) -> Set<Long>,
    ): Int {
        val changed = mutableListOf<Long>()
        val off = mutableState.value.bulbs.permanentlyOff()
        try {
            ids.forEach { id ->
                if (id in off || ModeStore(context).mode.first() != HomeDjMode.SERVER) return@forEach
                if (individual) commit(context, mutableState.value.let { s ->
                    s.copy(configuration = LightsGroups.individualChanged(s.configuration, mapOf(id to change))) })
                val effective = LightsGroups.effective(mutableState.value.configuration, id, change)
                val acknowledged = try {
                    MatterLights.send(context, id, LightsPolicy.commands(effective, off = mutableState.value.bulbs
                        .firstOrNull { it.id == id }?.on != true))
                    fadeHolds[id] = android.os.SystemClock.elapsedRealtime() + effective.fadeSeconds * 1000L
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logFailure("Light command", failure)
                    false
                }
                if (acknowledged) withContext(NonCancellable) {
                    commit(context, mutableState.value.let { s ->
                        s.copy(bulbs = s.bulbs.map { if (it.id == id) LightsPolicy.applied(it, effective) else it })
                    })
                    changed += id
                }
            }
        } finally {
            // Also when a newer presence run cancels this one: lights already off stay remembered.
            if (ids.isNotEmpty()) withContext(NonCancellable) { commit(context, mutableState.value, presence(presenceOff, changed)) }
        }
        return changed.size + ids.count { it in off }
    }

    private fun outcome(done: Int, total: Int, success: String): Outcome = when (done) {
        total -> Outcome(true, success)
        0 -> Outcome(false, if (total == 1) NO_ANSWER else "The lights did not respond. Check they are powered and on this Wi-Fi.")
        else -> Outcome(false, "$done of $total lights changed. The others did not respond.")
    }

    private fun offMessage(bulb: Bulb) = "${bulb.name} is set to permanently off. Switch that off in its settings first."

    private fun describe(change: LightChange, label: String): String = when {
        change.on == false -> "$label off"
        change.level != null -> "$label at ${change.level}%"
        change.color != null -> "$label colour changed"
        else -> "$label on"
    }

    private suspend fun serverOnly(context: Context, block: suspend () -> Outcome): Outcome = mutex.withLock {
        load(context)
        if (ModeStore(context).mode.first() != HomeDjMode.SERVER) {
            Outcome(false, "Lights are controlled by the Samsung in Server mode.")
        } else {
            block()
        }
    }

    /** Writes the whole record in one edit; publish success only after disk accepts it. */
    private suspend fun commit(
        context: Context, snapshot: LightsSnapshot, presence: Set<Long> = presenceOff, ranAt: Long? = null,
    ) {
        val saved = withContext(Dispatchers.IO) {
            prefs(context).edit()
                .putString(BULBS, LightsCodec.bulbsJson(snapshot.bulbs).toString())
                .putString(AUTOMATION, LightsCodec.automationJson(snapshot.automation).toString())
                .putString(CONFIGURATION, LightsGroupsCodec.json(snapshot.configuration).toString())
                .putString(EXPERIENCE, LightExperienceCodec.json(snapshot).toString())
                .putString(PRESENCE_OFF, JSONArray(presence.toList()).toString())
                .also { editor -> ranAt?.let { editor.putLong(RULES_RAN_AT, it) } }
                .commit()
        }
        if (!saved) {
            Log.w(TAG, "Lights save failed")
            throw java.io.IOException("Lights save failed")
        }
        presenceOff = presence
        mutableState.value = snapshot.copy(zone = java.time.ZoneId.systemDefault().id)
    }

    /** Reads the record once per process, migrating the one-bulb test's `node_id` into the list. */
    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = prefs(context)
            val bulbsJson = prefs.getString(BULBS, null)
            val migrated = LightsCodec.migrate(bulbsJson, prefs.getLong(LEGACY_NODE, 0L))
            if (migrated != null) {
                prefs.edit().putString(BULBS, LightsCodec.bulbsJson(migrated).toString()).remove(LEGACY_NODE).apply()
            }
            val bulbs = migrated ?: bulbsJson?.let { runCatching { LightsCodec.bulbs(it) }.getOrNull() }.orEmpty()
            val automation = prefs.getString(AUTOMATION, null)
                ?.let { runCatching { LightsCodec.automation(JSONObject(it), strict = false) }.getOrNull() }
            presenceOff = prefs.getString(PRESENCE_OFF, null)?.let { runCatching { LightsCodec.ids(it) }.getOrNull() }.orEmpty()
            val config = prefs.getString(CONFIGURATION, null)?.let {
                runCatching { LightsGroupsCodec.read(JSONObject(it), bulbs) }.getOrNull()
            } ?: LightsConfiguration()
            val extra = prefs.getString(EXPERIENCE, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            mutableState.value = LightsSnapshot(bulbs, automation ?: LightsAutomation(), config,
                LightExperienceCodec.scenes(extra?.optJSONArray("scenes")),
                extra?.optJSONObject("location")?.let(LightExperienceCodec::location),
                LightExperienceCodec.history(extra?.optJSONArray("history")), java.time.ZoneId.systemDefault().id,
                LightVariationCodec.read(extra?.optJSONObject("variation"), bulbs),
                LightDanceCodec.read(extra?.optJSONObject("dance")) ?: LightDance())
            loaded = true
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("homedj_lights", Context.MODE_PRIVATE)
}
