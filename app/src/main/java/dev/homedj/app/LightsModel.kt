package dev.homedj.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * One paired Matter bulb; [id] is its node ID on HomeDJ's fabric. [on], [level] (1..100 %) and
 * [color] are the last acknowledged targets or explicit read, null when not known. External
 * changes appear after Check light; [reading] dates the last physical read and its capabilities.
 */
internal data class Bulb(
    val id: Long,
    val name: String,
    val on: Boolean? = null,
    val level: Int? = null,
    val color: LightColor? = null,
    val reading: LightReading? = null,
    /** Treated as having no power: nothing HomeDJ does sends to it until switched back. */
    val permanentlyOff: Boolean = false,
)

/** IDs of the lights set to permanently off. */
internal fun List<Bulb>.permanentlyOff(): Set<Long> = filter(Bulb::permanentlyOff).mapTo(HashSet(), Bulb::id)

/** What to change on a bulb; null fields stay as they are. */
internal data class LightChange(val on: Boolean? = null, val level: Int? = null, val color: LightColor? = null,
    val fadeSeconds: Int = 0) {
    val isEmpty: Boolean get() = on == null && level == null && color == null
}

/**
 * Weekly rule: on [days] (ISO, Monday = 1) at [minute] past midnight, Samsung local time, apply
 * [change] to [bulbs] (null means every bulb, including ones paired later).
 */
internal data class LightsRule(
    val id: String,
    val days: Set<Int>,
    val minute: Int,
    val change: LightChange,
    val bulbs: List<Long>? = null,
    val groupId: String? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val time: String = "clock",
    val offset: Int = 0,
    val sceneId: String? = null,
    val activeAfter: Long = 0,
    val onlyHome: Boolean = false,
)

/**
 * [awayOff]: off when the OnePlus leaves home; [homeOn]: back on when it is home. [homeScene] and
 * [homePlaylist] (optional, see [HomeArrival]) are applied and played shuffled when it is home.
 */
internal data class LightsAutomation(
    val awayOff: Boolean = false,
    val homeOn: Boolean = false,
    val rules: List<LightsRule> = emptyList(),
    val homeScene: String? = null,
    val homePlaylist: String? = null,
)

internal data class LightsSnapshot(
    val bulbs: List<Bulb> = emptyList(),
    val automation: LightsAutomation = LightsAutomation(),
    val configuration: LightsConfiguration = LightsConfiguration(),
    val scenes: List<LightScene> = emptyList(),
    val location: LightLocation? = null,
    val history: List<LightRun> = emptyList(),
    /** Samsung's zone, so the Controller never calculates occurrences in its own zone. */
    val zone: String = "",
    val variation: LightsVariation = LightsVariation(),
    val dance: LightDance = LightDance(),
)

/** Pure Lights rules shared by Samsung's owner, its LAN handler and the tests. */
internal object LightsPolicy {
    const val MAX_BULBS = 16
    const val MAX_RULES = 20
    const val MAX_NAME = 40
    /** A rule whose time passed longer ago than this (phone off, alarm late) is skipped. */
    const val MISSED_MILLIS = 15 * 60_000L
    val KELVIN = 2000..6500

    fun validName(name: String): String? = name.trim().takeIf { it.length in 1..MAX_NAME }

    fun validColor(color: LightColor): Boolean = when (color) {
        is LightColor.Hue -> color.degrees in 0..359 && color.saturationPercent in 0..100
        is LightColor.White -> color.kelvin in KELVIN
    }

    fun valid(change: LightChange): Boolean =
        !change.isEmpty && change.fadeSeconds in 0..3600 && (change.level == null || change.level in 1..100) &&
            (change.color == null || validColor(change.color))

    fun valid(rule: LightsRule): Boolean =
        rule.id.length in 1..40 && rule.days.isNotEmpty() && rule.days.all { it in 1..7 } &&
            rule.minute in 0 until 1440 && valid(rule.change) && rule.name.length <= MAX_NAME &&
            rule.time in setOf("clock", "sunrise", "sunset") && rule.offset in -120..120 &&
            rule.activeAfter >= 0 && (rule.sceneId == null || rule.sceneId.length in 1..40) &&
            (rule.bulbs == null || (rule.bulbs.size in 1..MAX_BULBS && rule.bulbs.all { it > 0 })) &&
            (rule.groupId == null || (rule.groupId.length in 1..40 && rule.bulbs != null))

    /** Stable draft IDs make a retry after an unknown LAN outcome replace the same rule. */
    fun withRule(automation: LightsAutomation, rule: LightsRule): LightsAutomation = automation.copy(
        rules = if (automation.rules.any { it.id == rule.id }) automation.rules.map { if (it.id == rule.id) rule else it }
            else automation.rules + rule,
    )

    /**
     * One bulb's commands: colour (ExecuteIfOff) and brightness, which also turns it on, or plain
     * power. A bulb that is lit takes colour first so it never flashes its old colour; one that is
     * [off] or unknown ([Bulb.on] not true) powers on first, because some bulbs ignore ExecuteIfOff
     * and would come on in their old colour. Off ignores colour and brightness.
     */
    fun commands(change: LightChange, off: Boolean = false): List<MatterCommand> = buildList {
        if (change.on == false) {
            add(if (change.fadeSeconds == 0) powerCommand(false) else levelCommand(0, change.fadeSeconds * 10))
            return@buildList
        }
        val color = change.color?.let { colorCommand(it, transitionTenths = change.fadeSeconds * 10) }
        val power = when {
            change.level != null -> levelCommand(change.level, change.fadeSeconds * 10)
            change.on == true -> powerCommand(true)
            else -> null
        }
        addAll(if (off) listOfNotNull(power, color) else listOfNotNull(color, power))
    }

    /** The bulb as recorded after the bulb acknowledged every command of [change]. */
    fun applied(bulb: Bulb, change: LightChange): Bulb =
        if (change.on == false) bulb.copy(on = false)
        else bulb.copy(
            on = if (change.on == true || change.level != null) true else bulb.on,
            level = change.level ?: bulb.level,
            color = change.color ?: bulb.color,
        )

    fun targets(bulbs: List<Bulb>, ids: List<Long>?): List<Bulb> =
        if (ids == null) bulbs else bulbs.filter { it.id in ids }

    /** Bulbs that are on or not known to be off; presence turns these off and remembers them. */
    fun awayTargets(bulbs: List<Bulb>): List<Long> = bulbs.filter { it.on != false }.map(Bulb::id)

    /** Back home: only bulbs presence turned off that HomeDJ still has off. */
    fun homeTargets(bulbs: List<Bulb>, presenceOff: Set<Long>): List<Long> =
        bulbs.filter { it.id in presenceOff && it.on == false }.map(Bulb::id)

    fun defaultName(bulbs: List<Bulb>): String =
        generateSequence(1) { it + 1 }.map { "Light $it" }.first { name -> bulbs.none { it.name == name } }

    /**
     * Rules whose time came in (`ranAt`, `now`], oldest first so the later rule wins. Times
     * missed by more than [MISSED_MILLIS] are skipped. [ranAt] is epoch milliseconds.
     */
    fun due(rules: List<LightsRule>, ranAt: Long, now: ZonedDateTime, location: LightLocation? = null): List<LightsRule> {
        val since = Instant.ofEpochMilli(ranAt).atZone(now.zone)
        val cutoff = now.minusNanos(MISSED_MILLIS * 1_000_000)
        val from = if (since.isAfter(cutoff)) since else cutoff
        val days = generateSequence(from.toLocalDate().minusDays(1)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(now.toLocalDate().plusDays(1)) }
        return days.flatMap { date -> rules.mapNotNull { rule -> occurrence(rule, date, now, location)?.let { it to rule } } }
            .filter { (at, _) -> at.isAfter(since) && !at.isBefore(cutoff) && !at.isAfter(now) }
            .sortedWith(compareBy({ it.first.toInstant() }, { LightsGroups.priority(it.second) }))
            .map { it.second }
            .toList()
    }

    /** The next rule time strictly after [now], or null with no rules. */
    fun next(rules: List<LightsRule>, now: ZonedDateTime, location: LightLocation? = null): ZonedDateTime? =
        (-1L..8L).flatMap { offset -> rules.mapNotNull { occurrence(it, now.toLocalDate().plusDays(offset), now, location) } }
            .filter { it.isAfter(now) }
            .minByOrNull { it.toInstant() }

    /** A daylight-saving gap moves the time forward, as `ZonedDateTime.of` resolves it. */
    internal fun occurrence(rule: LightsRule, date: LocalDate, now: ZonedDateTime, location: LightLocation?): ZonedDateTime? = (
        if (!rule.enabled || date.dayOfWeek.value !in rule.days) null
        else if (rule.time == "clock") ZonedDateTime.of(date, LocalTime.of(rule.minute / 60, rule.minute % 60), now.zone)
        else location?.let { lightSolarTime(date, now.zone, it, rule.time == "sunrise") }?.plusMinutes(rule.offset.toLong())
        )?.takeIf { it.toInstant().toEpochMilli() > rule.activeAfter }
}

/**
 * JSON for SharedPreferences `homedj_lights` and the LAN `lights*` commands. The field names are
 * persisted and on the wire: add optional fields only. Invalid entries are dropped one by one.
 */
internal object LightsCodec {
    fun bulbs(json: String): List<Bulb> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::bulb) }
            .distinctBy(Bulb::id).take(LightsPolicy.MAX_BULBS)
    }

    fun bulbsJson(bulbs: List<Bulb>): JSONArray = JSONArray().also { array ->
        bulbs.forEach { bulb ->
            array.put(putChange(JSONObject().put("id", bulb.id).put("name", bulb.name),
                LightChange(bulb.on, bulb.level, bulb.color)).also { json ->
                bulb.reading?.let { json.put("reading", it.json()) }
                if (bulb.permanentlyOff) json.put("permanentlyOff", true)
            })
        }
    }

    /** The legacy one-bulb test kept only `node_id`; it becomes the first bulb. Null: nothing to migrate. */
    fun migrate(bulbsJson: String?, legacyNode: Long): List<Bulb>? =
        if (bulbsJson != null || legacyNode <= 0L) null else listOf(Bulb(legacyNode, "Light 1"))

    fun change(json: JSONObject): LightChange = LightChange(
        on = if (json.has("on")) json.optBoolean("on") else null,
        level = if (json.has("level")) json.optInt("level", -1) else null,
        color = when {
            json.has("kelvin") -> LightColor.White(json.optInt("kelvin", -1))
            json.has("hue") -> LightColor.Hue(json.optInt("hue", -1), json.optInt("sat", -1))
            else -> null
        },
        fadeSeconds = if (json.has("fadeSeconds")) json.optInt("fadeSeconds", -1) else 0,
    )

    fun putChange(json: JSONObject, change: LightChange): JSONObject = json.apply {
        change.on?.let { put("on", it) }
        change.level?.let { put("level", it) }
        if (change.fadeSeconds != 0) put("fadeSeconds", change.fadeSeconds)
        when (val color = change.color) {
            is LightColor.Hue -> put("hue", color.degrees).put("sat", color.saturationPercent)
            is LightColor.White -> put("kelvin", color.kelvin)
            null -> Unit
        }
    }

    /** [strict] (a LAN save) rejects the whole automation if any rule is invalid. */
    fun automation(json: JSONObject, strict: Boolean): LightsAutomation? {
        val array = json.optJSONArray("rules") ?: JSONArray()
        if (strict && array.length() > LightsPolicy.MAX_RULES) return null
        val rules = (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::rule) }
            .distinctBy(LightsRule::id)
        if (strict && (rules.size != array.length() || rules.size > LightsPolicy.MAX_RULES)) return null
        return LightsAutomation(json.optBoolean("awayOff"), json.optBoolean("homeOn"), rules.take(LightsPolicy.MAX_RULES),
            json.optString("homeScene").takeIf { it.length in 1..40 },
            json.optString("homePlaylist").takeIf(SchedulePolicy::validPlaylistId))
    }

    fun automationJson(automation: LightsAutomation): JSONObject = JSONObject()
        .put("awayOff", automation.awayOff)
        .put("homeOn", automation.homeOn)
        .put("rules", JSONArray().also { array ->
            automation.rules.forEach { rule ->
                array.put(ruleJson(rule))
            }
        })
        .also { json ->
            automation.homeScene?.let { json.put("homeScene", it) }
            automation.homePlaylist?.let { json.put("homePlaylist", it) }
        }

    fun snapshotJson(snapshot: LightsSnapshot): JSONObject = JSONObject()
        .put("bulbs", bulbsJson(snapshot.bulbs))
        .put("automation", automationJson(snapshot.automation))
        .put("configuration", LightsGroupsCodec.json(snapshot.configuration))
        .put("experience", LightExperienceCodec.json(snapshot))

    fun snapshot(json: JSONObject): LightsSnapshot = LightsSnapshot(
        bulbs = bulbs(json.optJSONArray("bulbs")?.toString() ?: "[]"),
        automation = json.optJSONObject("automation")?.let { automation(it, strict = false) } ?: LightsAutomation(),
        configuration = json.optJSONObject("configuration")?.let {
            LightsGroupsCodec.read(it, bulbs(json.optJSONArray("bulbs")?.toString() ?: "[]"))
        } ?: LightsConfiguration(),
        scenes = LightExperienceCodec.scenes(json.optJSONObject("experience")?.optJSONArray("scenes")),
        location = json.optJSONObject("experience")?.optJSONObject("location")?.let(LightExperienceCodec::location),
        history = LightExperienceCodec.history(json.optJSONObject("experience")?.optJSONArray("history")),
        zone = json.optJSONObject("experience")?.optString("zone").orEmpty(),
        variation = LightVariationCodec.read(json.optJSONObject("experience")?.optJSONObject("variation"),
            bulbs(json.optJSONArray("bulbs")?.toString() ?: "[]")),
        dance = LightDanceCodec.read(json.optJSONObject("experience")?.optJSONObject("dance")) ?: LightDance(),
    )

    fun ids(json: String): Set<Long> = JSONArray(json).let { array ->
        (0 until array.length()).map { array.optLong(it) }.filter { it > 0 }.toSet()
    }

    private fun bulb(json: JSONObject): Bulb? {
        val id = json.optLong("id")
        val name = LightsPolicy.validName(json.optString("name")) ?: return null
        if (id <= 0) return null
        val change = change(json)
        return Bulb(id, name, change.on, change.level?.takeIf { it in 1..100 },
            change.color?.takeIf(LightsPolicy::validColor), json.optJSONObject("reading")?.let(LightReading::read),
            json.optBoolean("permanentlyOff"))
    }

    fun ruleJson(rule: LightsRule): JSONObject = putChange(JSONObject().put("id", rule.id).put("minute", rule.minute)
        .put("days", JSONArray(rule.days.sorted())).put("name", rule.name).put("enabled", rule.enabled)
        .put("time", rule.time).put("offset", rule.offset).put("activeAfter", rule.activeAfter)
        .put("onlyHome", rule.onlyHome), rule.change).also { json ->
        rule.bulbs?.let { json.put("bulbs", JSONArray(it)) }
        rule.groupId?.let { json.put("groupId", it) }
        rule.sceneId?.let { json.put("sceneId", it) }
    }

    fun rule(json: JSONObject): LightsRule? {
        if (listOf("enabled", "onlyHome").any { json.has(it) && json.opt(it) !is Boolean }) return null
        val days = json.optJSONArray("days")?.let { array -> (0 until array.length()).map { array.optInt(it) }.toSet() }
        val bulbs = json.optJSONArray("bulbs")?.let { array ->
            (0 until array.length()).map { array.optLong(it) }.filter { it > 0 }.distinct()
        }
        val rule = LightsRule(json.optString("id"), days.orEmpty(), json.optInt("minute", -1), change(json), bulbs,
            if (json.has("groupId")) json.optString("groupId") else null,
            json.optString("name"), json.optBoolean("enabled", true), json.optString("time", "clock"),
            json.optInt("offset"), if (json.has("sceneId")) json.optString("sceneId") else null,
            json.optLong("activeAfter"), json.optBoolean("onlyHome"))
        return rule.takeIf(LightsPolicy::valid)
    }
}
