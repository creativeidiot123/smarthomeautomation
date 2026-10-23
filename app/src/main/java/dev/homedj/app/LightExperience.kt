package dev.homedj.app

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fixed members and settings: editing a group later never silently changes a saved scene.
 * [variation] null (scenes from older builds) leaves the lights' variation as it is; [variations]
 * are per-light overrides of it. [juggle] is the colour-swap interval in seconds (0 off, null from older builds).
 * [playlists] are saved playlist IDs Samsung plays shuffled when the scene is applied (null from older builds).
 */
internal data class LightScene(val id: String, val name: String, val changes: Map<Long, LightChange>,
    val variation: LightVariation? = null, val variations: Map<Long, LightVariation> = emptyMap(),
    val juggle: Int? = null, val playlists: List<String>? = null)
internal data class LightLocation(val latitude: Double, val longitude: Double) {
    val valid: Boolean get() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}
internal data class LightRun(val at: Long, val name: String, val result: String)

internal object LightExperience {
    const val MAX_SCENES = 16
    const val MAX_HISTORY = 12

    fun valid(scene: LightScene, bulbs: List<Bulb>): Boolean = scene.id.length in 1..40 &&
        LightsPolicy.validName(scene.name) != null && scene.changes.size in 1..LightsPolicy.MAX_BULBS &&
        scene.changes.all { (id, change) -> bulbs.any { it.id == id } && LightsPolicy.valid(change) } &&
        scene.variation?.valid != false && scene.variations.all { (id, value) -> id in scene.changes && value.valid } &&
        LightJuggles.validSeconds(scene.juggle) && validPlaylists(scene.playlists)

    fun validPlaylists(ids: List<String>?): Boolean = ids == null ||
        ids.size <= ScheduleCodec.MAX_PLAYLISTS && ids.distinct().size == ids.size && ids.all(SchedulePolicy::validPlaylistId)

    fun saveScene(snapshot: LightsSnapshot, scene: LightScene): LightsSnapshot? {
        if (!valid(scene, snapshot.bulbs) || (snapshot.scenes.none { it.id == scene.id } &&
                snapshot.scenes.size >= MAX_SCENES)) return null
        // Older Controllers never send variation; they cannot erase what they never received.
        val previous = snapshot.scenes.firstOrNull { it.id == scene.id }
        val old = previous?.takeIf { scene.variation == null }
        val saved = scene.copy(name = scene.name.trim(), variation = old?.variation ?: scene.variation,
            variations = old?.variations?.filterKeys { it in scene.changes } ?: scene.variations,
            juggle = scene.juggle ?: previous?.juggle, playlists = scene.playlists ?: previous?.playlists)
        return snapshot.copy(scenes = if (snapshot.scenes.any { it.id == scene.id })
            snapshot.scenes.map { if (it.id == scene.id) saved else it } else snapshot.scenes + saved)
    }

    /**
     * What applying [scene] sends: its changes for its paired lights, and off (with the scene's fade) for
     * every other paired light not recorded off, so a light left out never keeps an older scene's settings.
     * Empty when none of the scene's lights is still paired.
     */
    fun sceneChanges(snapshot: LightsSnapshot, scene: LightScene): Map<Long, LightChange> {
        val own = scene.changes.filterKeys { id -> snapshot.bulbs.any { it.id == id } }
        if (own.isEmpty()) return own
        val off = LightChange(on = false, fadeSeconds = own.values.maxOf { it.fadeSeconds })
        return own + snapshot.bulbs.filter { it.id !in own && it.on != false }.associate { it.id to off }
    }

    /**
     * The scene's changes without [skip] and permanently-off lights, and how many lights its outcome
     * counts: skipped scene lights count as not changed, permanently-off lights not at all.
     */
    fun sceneTargets(snapshot: LightsSnapshot, scene: LightScene, skip: Set<Long>): Pair<Map<Long, LightChange>, Int> {
        val off = snapshot.bulbs.permanentlyOff()
        val entries = sceneChanges(snapshot, scene).filterKeys { it !in skip && it !in off }
        return entries to scene.changes.keys.count { it !in off } + entries.keys.count { it !in scene.changes }
    }

    fun deleteScene(snapshot: LightsSnapshot, id: String) = snapshot.copy(
        scenes = snapshot.scenes.filterNot { it.id == id },
        automation = snapshot.automation.copy(rules = snapshot.automation.rules.filterNot { it.sceneId == id },
            homeScene = snapshot.automation.homeScene.takeIf { it != id }),
    )

    fun validRule(snapshot: LightsSnapshot, rule: LightsRule): Boolean = LightsPolicy.valid(rule) &&
        (rule.time == "clock" || snapshot.location != null) &&
        (rule.groupId == null || snapshot.configuration.groups.any { it.id == rule.groupId }) &&
        (rule.sceneId == null || (rule.groupId == null && snapshot.scenes.any { it.id == rule.sceneId })) &&
        (rule.bulbs == null || rule.bulbs.all { id -> snapshot.bulbs.any { it.id == id } })

    fun saveRule(snapshot: LightsSnapshot, rule: LightsRule, now: Long): LightsSnapshot? {
        if (!validRule(snapshot, rule)) return null
        val automation = LightsPolicy.withRule(snapshot.automation, rule.copy(activeAfter = now))
        return if (automation.rules.size > LightsPolicy.MAX_RULES) null else snapshot.copy(automation = automation)
    }

    fun enableRule(snapshot: LightsSnapshot, id: String, enabled: Boolean, now: Long): LightsSnapshot? {
        if (snapshot.automation.rules.none { it.id == id }) return null
        return snapshot.copy(automation = snapshot.automation.copy(rules = snapshot.automation.rules.map {
            // Repeated enable commands are idempotent and do not move an existing start boundary.
            if (it.id == id && it.enabled != enabled) it.copy(enabled = enabled, activeAfter = now) else it
        }))
    }

    /** Older Controllers may edit the original fields, but cannot erase fields they never received. */
    fun preserveRuleFeatures(incoming: LightsAutomation, previous: LightsAutomation) = incoming.copy(
        rules = incoming.rules.map { rule ->
            previous.rules.firstOrNull { it.id == rule.id }?.let { old ->
                rule.copy(name = old.name, enabled = old.enabled, time = old.time, offset = old.offset,
                    sceneId = old.sceneId, activeAfter = old.activeAfter, onlyHome = old.onlyHome,
                    change = rule.change.copy(fadeSeconds = old.change.fadeSeconds))
            } ?: rule
        },
        // Only `lightsPresence` edits what happens on arrival; a whole-automation save never carries it.
        homeScene = previous.homeScene, homePlaylist = previous.homePlaylist,
    )

    fun record(snapshot: LightsSnapshot, at: Long, name: String, result: String) = snapshot.copy(
        history = (listOf(LightRun(at, name.take(40), result.take(160))) + snapshot.history).take(MAX_HISTORY),
    )

    fun homeConfirmed(listenerRunning: Boolean, homeAt: Long?, elapsedNow: Long): Boolean =
        listenerRunning && homeAt != null && elapsedNow - homeAt in 0..180_000L

    /**
     * House rules' `schedules now`: the schedules whose last run in the past day is still the
     * latest for at least one light, oldest first, without fades. A scene covers every light,
     * because applying it turns the others off. A schedule just re-enabled or saved still counts:
     * its start boundary only stops the alarm from catching up, not this explicit request.
     */
    fun latest(snapshot: LightsSnapshot, now: ZonedDateTime): List<LightsRule> {
        val all = snapshot.bulbs.map(Bulb::id).toSet()
        val covered = HashSet<Long>()
        val since = now.minusDays(1)
        return (-1L..0L).flatMap { offset -> snapshot.automation.rules.mapNotNull { rule ->
            LightsPolicy.occurrence(rule.copy(activeAfter = 0), now.toLocalDate().plusDays(offset), now, snapshot.location)
                ?.let { it to rule }
        } }.filter { (at, _) -> at.isAfter(since) && !at.isAfter(now) }
            .sortedWith(compareByDescending<Pair<ZonedDateTime, LightsRule>> { it.first.toInstant() }
                .thenByDescending { LightsGroups.priority(it.second) })
            .mapNotNull { (_, rule) ->
                if (covered.containsAll(all)) return@mapNotNull null
                val targets = if (rule.sceneId != null) all else LightsGroups.targets(snapshot, rule).toSet()
                rule.takeIf { targets.any { it !in covered } }?.also { covered += targets }
            }
            .reversed()
            .map { it.copy(change = it.change.copy(fadeSeconds = 0)) }
    }

    fun next(snapshot: LightsSnapshot, now: ZonedDateTime) =
        LightsPolicy.next(snapshot.automation.rules, now, snapshot.location)
            // Reconsider polar days and missing solar events tomorrow; no polling loop.
            ?: snapshot.automation.rules.takeIf { rules -> rules.any { it.enabled && it.time != "clock" } &&
                snapshot.location != null }?.let { now.toLocalDate().plusDays(1).atStartOfDay(now.zone) }
}

/** NOAA's published fractional-year equations; approximate solar events, calculated entirely locally.
 * https://gml.noaa.gov/grad/solcalc/solareqns.PDF . No third-party implementation copied.
 * An absent polar event is null. UTC candidates are matched to the requested local date.
 */
internal fun lightSolarTime(date: LocalDate, zone: ZoneId, location: LightLocation, sunrise: Boolean): ZonedDateTime? {
    if (!location.valid) return null
    return (-1L..1L).mapNotNull { offset ->
        val utcDate = date.plusDays(offset)
        val gamma = 2 * PI / utcDate.lengthOfYear() * (utcDate.dayOfYear - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val lat = Math.toRadians(location.latitude)
        val cosine = cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (!cosine.isFinite() || cosine !in -1.0..1.0) return@mapNotNull null
        val angle = Math.toDegrees(acos(cosine)) * if (sunrise) 1 else -1
        val seconds = ((720 - 4 * (location.longitude + angle) - eq) * 60).roundToLong()
        utcDate.atStartOfDay(ZoneOffset.UTC).plusSeconds(seconds).withZoneSameInstant(zone)
            .takeIf { it.toLocalDate() == date }
    }.firstOrNull()
}

/** Additive, bounded record shared by disk and authenticated LAN snapshots. */
internal object LightExperienceCodec {
    fun sceneJson(scene: LightScene): JSONObject = JSONObject().put("id", scene.id).put("name", scene.name)
        .put("changes", JSONArray().also { array ->
            scene.changes.forEach { (id, change) -> array.put(LightsCodec.putChange(JSONObject().put("id", id), change)
                .also { entry -> scene.variations[id]?.let { LightVariationCodec.put(entry, "v", it) } }) }
        }).also { json -> scene.variation?.let { LightVariationCodec.put(json, "variation", it) } }
        .also { json -> scene.juggle?.let { json.put("j", it) } }
        .also { json -> scene.playlists?.let { json.put("playlists", JSONArray(it)) } }

    fun scene(json: JSONObject): LightScene? {
        val array = json.optJSONArray("changes") ?: return null
        if (array.length() !in 1..LightsPolicy.MAX_BULBS) return null
        val variations = mutableMapOf<Long, LightVariation>()
        val entries = (0 until array.length()).map { index ->
            val entry = array.optJSONObject(index) ?: return null
            val id = entry.optLong("id")
            val change = LightsCodec.change(entry)
            if (id <= 0 || !LightsPolicy.valid(change)) return null
            LightVariationCodec.read(entry, "v")?.let { variations[id] = it.takeIf(LightVariation::valid) ?: return null }
            id to change
        }
        if (entries.map { it.first }.distinct().size != entries.size) return null
        val variation = LightVariationCodec.read(json, "variation")?.let { it.takeIf(LightVariation::valid) ?: return null }
        val juggle = if (json.has("j")) json.optInt("j", -1) else null
        val playlists = if (!json.has("playlists")) null else json.optJSONArray("playlists")
            ?.takeIf { it.length() <= ScheduleCodec.MAX_PLAYLISTS }?.let { ids -> (0 until ids.length()).map(ids::optString) } ?: return null
        return LightScene(json.optString("id"), json.optString("name"), entries.toMap(), variation, variations, juggle, playlists).takeIf {
            it.id.length in 1..40 && LightsPolicy.validName(it.name) != null && LightJuggles.validSeconds(it.juggle) &&
                LightExperience.validPlaylists(it.playlists)
        }
    }

    fun scenes(array: JSONArray?): List<LightScene> = if (array == null) emptyList() else
        (0 until minOf(array.length(), LightExperience.MAX_SCENES)).mapNotNull { array.optJSONObject(it)?.let(::scene) }
            .distinctBy(LightScene::id)

    fun location(json: JSONObject): LightLocation? =
        LightLocation(json.optDouble("latitude", Double.NaN), json.optDouble("longitude", Double.NaN)).takeIf { it.valid }

    fun locationJson(location: LightLocation) = JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)

    fun history(array: JSONArray?): List<LightRun> = if (array == null) emptyList() else
        (0 until minOf(array.length(), LightExperience.MAX_HISTORY)).mapNotNull { index ->
            array.optJSONObject(index)?.let { json -> LightRun(json.optLong("at"), json.optString("name").take(40),
                json.optString("result").take(160)).takeIf { it.at > 0 } }
        }

    fun json(snapshot: LightsSnapshot) = JSONObject()
        .put("version", 1).put("zone", snapshot.zone)
        .put("scenes", JSONArray().also { array -> snapshot.scenes.forEach { array.put(sceneJson(it)) } })
        .put("history", JSONArray().also { array -> snapshot.history.forEach {
            array.put(JSONObject().put("at", it.at).put("name", it.name).put("result", it.result))
        } }).put("variation", LightVariationCodec.json(snapshot.variation))
        .put("dance", LightDanceCodec.json(snapshot.dance))
        .also { json -> snapshot.location?.let { json.put("location", locationJson(it)) } }
}
