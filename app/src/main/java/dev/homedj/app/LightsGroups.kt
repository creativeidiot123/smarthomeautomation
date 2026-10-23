package dev.homedj.app

import org.json.JSONArray
import org.json.JSONObject

/** Null preferences inherit; explicit false is an override too. */
internal data class LightPreferences(
    val change: LightChange = LightChange(),
    val awayOff: Boolean? = null,
    val homeOn: Boolean? = null,
)

/** [variation] null: its lights follow the all-light variation. Changed only by `lightsGroupVariation`. */
internal data class LightsGroup(
    val id: String,
    val name: String,
    val bulbs: List<Long>,
    val preferences: LightPreferences = LightPreferences(),
    val variation: LightVariation? = null,
)

/** Samsung owns this alongside the existing bulb/automation record. One group per bulb. */
internal data class LightsConfiguration(
    val groups: List<LightsGroup> = emptyList(),
    val individual: Map<Long, LightPreferences> = emptyMap(),
)

internal object LightsGroups {
    const val MAX_GROUPS = 16

    fun merge(base: LightChange, preferred: LightChange) = LightChange(
        on = preferred.on ?: base.on,
        level = preferred.level ?: base.level,
        color = preferred.color ?: base.color,
        fadeSeconds = base.fadeSeconds,
    )

    /** LevelControl turns on, so remember that intent as well as the percentage. */
    fun preferenceChange(change: LightChange): LightChange =
        if (change.level != null && change.on == null) change.copy(on = true) else change

    fun valid(preferences: LightPreferences): Boolean =
        preferences.change.isEmpty || LightsPolicy.valid(preferences.change)

    fun valid(group: LightsGroup, configuration: LightsConfiguration, bulbs: List<Bulb>): Boolean =
        group.id.length in 1..40 && LightsPolicy.validName(group.name) != null &&
            group.bulbs.size in 1..LightsPolicy.MAX_BULBS && group.bulbs.distinct().size == group.bulbs.size &&
            group.bulbs.all { id -> bulbs.any { it.id == id } } && valid(group.preferences) && group.variation?.valid != false &&
            configuration.groups.none { other -> other.id != group.id && other.bulbs.any { it in group.bulbs } }

    fun group(configuration: LightsConfiguration, bulb: Long): LightsGroup? =
        configuration.groups.firstOrNull { bulb in it.bulbs }

    fun effective(configuration: LightsConfiguration, bulb: Long, base: LightChange): LightChange {
        // When turning on, restore inherited colour/level too (they may have been overridden
        // while the group was off). Colour-only commands keep their ExecuteIfOff semantics.
        val defaults = if (base.on == true || (base.level != null && base.on != false))
            group(configuration, bulb)?.preferences?.change ?: LightChange() else LightChange()
        return merge(merge(defaults, preferenceChange(base)), configuration.individual[bulb]?.change ?: LightChange())
    }

    /**
     * Latest change wins: changing a group or all lights brings [members] back to it, dropping
     * their own power/brightness/colour. Their own presence choices stay.
     */
    fun rejoin(configuration: LightsConfiguration, members: Collection<Long>) = configuration.copy(
        individual = configuration.individual
            .mapValues { (id, preferences) -> if (id in members) preferences.copy(change = LightChange()) else preferences }
            .filterValues { it != LightPreferences() },
    )

    /** An all-light change is also the latest for every group: their remembered defaults take its fields. */
    fun allChanged(configuration: LightsConfiguration, bulbs: Collection<Long>, change: LightChange) = rejoin(
        configuration.copy(groups = configuration.groups.map { group -> group.copy(preferences = group.preferences.copy(
            change = merge(group.preferences.change, preferenceChange(change)))) }), bulbs)

    /** Each light's own change is its latest intent, kept over its group's; presence choices stay. */
    fun individualChanged(configuration: LightsConfiguration, changes: Map<Long, LightChange>) = configuration.copy(
        individual = configuration.individual + changes.mapValues { (id, change) ->
            val previous = configuration.individual[id] ?: LightPreferences()
            previous.copy(change = merge(previous.change, preferenceChange(change)))
        })

    fun presence(snapshot: LightsSnapshot, bulb: Long, home: Boolean): Boolean {
        val individual = snapshot.configuration.individual[bulb]
        val group = group(snapshot.configuration, bulb)?.preferences
        return if (home) individual?.homeOn ?: group?.homeOn ?: snapshot.automation.homeOn
            else individual?.awayOff ?: group?.awayOff ?: snapshot.automation.awayOff
    }

    fun presenceTargets(snapshot: LightsSnapshot, presenceOff: Set<Long>, home: Boolean): List<Long> {
        val candidates = if (home) LightsPolicy.homeTargets(snapshot.bulbs, presenceOff)
            else LightsPolicy.awayTargets(snapshot.bulbs)
        return candidates.filter { id -> presence(snapshot, id, home) &&
            effective(snapshot.configuration, id, LightChange(on = home)).on == home }
    }

    fun targets(snapshot: LightsSnapshot, rule: LightsRule): List<Long> =
        if (rule.groupId != null) snapshot.configuration.groups.firstOrNull { it.id == rule.groupId }?.bulbs.orEmpty()
        else LightsPolicy.targets(snapshot.bulbs, rule.bulbs).map(Bulb::id)

    /** Within one scheduled minute broad defaults run first, then groups, then individual rules. */
    fun priority(rule: LightsRule): Int = when {
        rule.groupId != null -> 1
        rule.bulbs != null -> 2
        else -> 0
    }

    fun withoutBulb(configuration: LightsConfiguration, id: Long) = configuration.copy(
        groups = configuration.groups.map { it.copy(bulbs = it.bulbs - id) },
        individual = configuration.individual - id,
    )

    fun preserveRuleTargets(incoming: LightsAutomation, previous: LightsAutomation): LightsAutomation {
        val groups = previous.rules.associateBy(LightsRule::id)
        return incoming.copy(rules = incoming.rules.map { rule ->
            val old = groups[rule.id]
            if (rule.groupId == null && old?.groupId != null) rule.copy(groupId = old.groupId) else rule
        })
    }
}

/** Additive optional JSON. Old bulb records and automation keep their original fields. */
internal object LightsGroupsCodec {
    fun preferences(json: JSONObject): LightPreferences? {
        if (listOf("on", "awayOff", "homeOn").any { json.has(it) && json.opt(it) !is Boolean }) return null
        if (listOf("level", "hue", "sat", "kelvin").any {
            json.has(it) && (json.opt(it) !is Number || json.optDouble(it) != json.optInt(it).toDouble())
        }) return null
        if ((json.has("hue") != json.has("sat")) || (json.has("hue") && json.has("kelvin"))) return null
        return LightPreferences(
            LightsCodec.change(json),
            if (json.has("awayOff")) json.optBoolean("awayOff") else null,
            if (json.has("homeOn")) json.optBoolean("homeOn") else null,
        ).takeIf(LightsGroups::valid)
    }

    fun preferencesJson(preferences: LightPreferences): JSONObject =
        LightsCodec.putChange(JSONObject(), preferences.change).also {
            preferences.awayOff?.let { value -> it.put("awayOff", value) }
            preferences.homeOn?.let { value -> it.put("homeOn", value) }
        }

    fun group(json: JSONObject): LightsGroup? {
        val preferences = if (json.has("preferences")) {
            json.optJSONObject("preferences")?.let(::preferences) ?: return null
        } else LightPreferences()
        val variation = LightVariationCodec.read(json, "v")?.let { it.takeIf(LightVariation::valid) ?: return null }
        return LightsGroup(json.optString("id"), json.optString("name"),
            json.optJSONArray("bulbs")?.let { array ->
                (0 until minOf(array.length(), LightsPolicy.MAX_BULBS + 1)).map { array.optLong(it) }
            }.orEmpty(), preferences, variation)
    }

    fun groupJson(group: LightsGroup) = JSONObject().put("id", group.id).put("name", group.name)
        .put("bulbs", JSONArray(group.bulbs)).put("preferences", preferencesJson(group.preferences))
        .also { json -> group.variation?.let { LightVariationCodec.put(json, "v", it) } }

    fun json(configuration: LightsConfiguration) = JSONObject()
        .put("groups", JSONArray().also { array -> configuration.groups.forEach { array.put(groupJson(it)) } })
        .put("individual", JSONArray().also { array ->
            configuration.individual.forEach { (id, preferences) -> array.put(preferencesJson(preferences).put("id", id)) }
        })

    fun read(json: JSONObject, bulbs: List<Bulb>): LightsConfiguration {
        val groups = mutableListOf<LightsGroup>()
        val array = json.optJSONArray("groups") ?: JSONArray()
        for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
            val group = array.optJSONObject(index)?.let(::group) ?: continue
            // Empty groups can remain after forgetting their last bulb; never broaden their target.
            if (group.id.length !in 1..40 || LightsPolicy.validName(group.name) == null ||
                !LightsGroups.valid(group.preferences) || groups.any { it.id == group.id }) continue
            val members = group.bulbs.distinct().filter { id ->
                bulbs.any { it.id == id } && groups.none { id in it.bulbs }
            }
            if (groups.size < LightsGroups.MAX_GROUPS) groups += group.copy(bulbs = members)
        }
        val individual = mutableMapOf<Long, LightPreferences>()
        val entries = json.optJSONArray("individual") ?: JSONArray()
        for (index in 0 until minOf(entries.length(), MAX_ENTRIES)) {
            val entry = entries.optJSONObject(index) ?: continue
            val id = entry.optLong("id")
            val preferences = preferences(entry) ?: continue
            if (bulbs.any { it.id == id } && LightsGroups.valid(preferences)) individual[id] = preferences
        }
        return LightsConfiguration(groups, individual)
    }

    private const val MAX_ENTRIES = 64
}
