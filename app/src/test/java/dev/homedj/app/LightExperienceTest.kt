package dev.homedj.app

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import matter.tlv.AnonymousTag
import matter.tlv.ContextSpecificTag
import matter.tlv.TlvReader
import matter.tlv.TlvWriter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LightExperienceTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 10, 19, 19, 5, 0, 0, zone)
    private val rule = LightsRule("r", setOf(1), 19 * 60, LightChange(on = true, level = 40))
    private val bulbs = listOf(Bulb(1, "Desk"), Bulb(2, "Floor"))
    private val scene = LightScene("movie", "Movie", mapOf(1L to LightChange(true, 20, LightColor.White(2700), 30)))

    @Test fun disabledRulesNeverRunAndEnablingDoesNotCatchUp() {
        val previous = now.minusMinutes(10).toInstant().toEpochMilli()
        assertTrue(LightsPolicy.due(listOf(rule.copy(enabled = false)), previous, now).isEmpty())
        assertNull(LightsPolicy.next(listOf(rule.copy(enabled = false)), now))
        val newlySaved = rule.copy(activeAfter = now.minusMinutes(1).toInstant().toEpochMilli())
        assertEquals(listOf(rule), LightsPolicy.due(listOf(newlySaved, rule), previous, now))
        assertEquals(now.plusWeeks(1).withMinute(0), LightsPolicy.next(listOf(newlySaved), now))
    }

    @Test fun independentEditsKeepOtherRulesAndRepeatedEnablingIsIdempotent() {
        val other = rule.copy(id = "other", minute = 18 * 60)
        val snapshot = LightsSnapshot(bulbs, LightsAutomation(rules = listOf(rule, other)))
        val paused = requireNotNull(LightExperience.enableRule(snapshot, "r", false, 100))
        val updated = requireNotNull(LightExperience.saveRule(paused, other.copy(name = "Earlier"), 200))
        assertEquals(paused.automation.rules.first(), updated.automation.rules.first())
        val repeated = requireNotNull(LightExperience.enableRule(updated, "r", false, 300))
        assertEquals(updated, repeated)
        assertNull(LightExperience.enableRule(updated, "missing", true, 300))
        assertEquals(200L, updated.automation.rules.last().activeAfter)
    }

    @Test fun exactlyFifteenMinutesLateIsAllowedAndCheckpointStillPreventsRepeats() {
        val dueAt = now.withMinute(0)
        val late = dueAt.plusMinutes(15)
        assertEquals(listOf(rule), LightsPolicy.due(listOf(rule), dueAt.minusDays(1).toInstant().toEpochMilli(), late))
        assertTrue(LightsPolicy.due(listOf(rule), dueAt.toInstant().toEpochMilli(), late).isEmpty())
        assertTrue(LightsPolicy.due(listOf(rule), dueAt.minusDays(1).toInstant().toEpochMilli(), late.plusNanos(1)).isEmpty())
    }

    @Test fun applyingASceneTurnsOffLightsLeftOutOfIt() {
        val lit = listOf(Bulb(1, "Desk", on = true), Bulb(2, "Floor", on = true), Bulb(3, "Lamp", on = true),
            Bulb(4, "Shelf", on = false), Bulb(5, "Porch"))
        val two = scene.copy(changes = mapOf(1L to LightChange(true, 20, LightColor.White(2700), 30),
            2L to LightChange(true, 50, LightColor.Hue(200, 80), 30), 9L to LightChange(on = true)))
        val changes = LightExperience.sceneChanges(LightsSnapshot(lit), two)
        assertEquals(two.changes - 9L, changes.filterKeys { it in two.changes })
        // A light recorded off stays untouched; one never read may still be on.
        assertEquals(mapOf(3L to LightChange(on = false, fadeSeconds = 30), 5L to LightChange(on = false, fadeSeconds = 30)),
            changes.filterKeys { it !in two.changes })
        assertTrue(LightExperience.sceneChanges(LightsSnapshot(lit), scene.copy(changes = mapOf(9L to LightChange(on = true)))).isEmpty())
    }

    @Test fun permanentlyOffLightsGetNothingAndNeverCountAsFailed() {
        val lights = listOf(Bulb(1, "Desk", on = true), Bulb(2, "Top", on = true, permanentlyOff = true),
            Bulb(3, "Lamp", on = true), Bulb(4, "Hall", on = true, permanentlyOff = true))
        val study = scene.copy(changes = mapOf(1L to LightChange(true, 20), 2L to LightChange(true, 50), 3L to LightChange(true, 70)))
        val (entries, total) = LightExperience.sceneTargets(LightsSnapshot(lights), study, skip = setOf(3L))
        // Top is in the scene and Hall would be turned off as left out; neither gets anything.
        assertEquals(mapOf(1L to LightChange(true, 20)), entries)
        // The skipped (dark) Lamp still counts as not changed; the permanently-off Top does not count.
        assertEquals(2, total)
    }

    @Test fun scenesReplaceByStableIdAndRejectUnknownMembersOrOverflow() {
        val base = LightsSnapshot(bulbs)
        val saved = requireNotNull(LightExperience.saveScene(base, scene))
        val renamed = requireNotNull(LightExperience.saveScene(saved, scene.copy(name = "Cinema")))
        assertEquals(listOf(scene.copy(name = "Cinema")), renamed.scenes)
        assertNull(LightExperience.saveScene(base, scene.copy(changes = mapOf(9L to LightChange(on = true)))))
        assertNull(LightExperience.saveScene(base, scene.copy(changes = emptyMap())))
        val full = base.copy(scenes = (1..16).map { scene.copy(id = "$it") })
        assertNull(LightExperience.saveScene(full, scene))
        assertNotNull(LightExperience.saveScene(full, scene.copy(id = "1")))
    }

    @Test fun linkedPlaylistsRoundTripAndOlderBuildsCannotEraseThem() {
        val linked = scene.copy(playlists = listOf("PLchill-1", "PL_evening"))
        assertEquals(linked, LightExperienceCodec.scene(JSONObject(LightExperienceCodec.sceneJson(linked).toString())))
        // A scene saved by an older build has no field; an older Controller's save keeps the links.
        assertNull(requireNotNull(LightExperienceCodec.scene(LightExperienceCodec.sceneJson(scene))).playlists)
        val saved = requireNotNull(LightExperience.saveScene(LightsSnapshot(bulbs), linked))
        assertEquals(linked.playlists, requireNotNull(LightExperience.saveScene(saved, scene.copy(name = "Cinema"))).scenes.single().playlists)
        // A newer phone unlinking every playlist sends an empty list, which clears them.
        val cleared = requireNotNull(LightExperience.saveScene(saved, linked.copy(playlists = emptyList())))
        assertEquals(emptyList<String>(), cleared.scenes.single().playlists)
        assertEquals(emptyList<String>(), requireNotNull(LightExperienceCodec.scene(LightExperienceCodec.sceneJson(cleared.scenes.single()))).playlists)
    }

    @Test fun linkedPlaylistsAreBoundedDistinctAndValid() {
        val ten = (1..10).map { "PL$it" }
        assertNotNull(LightExperience.saveScene(LightsSnapshot(bulbs), scene.copy(playlists = ten)))
        listOf(ten + "PL11", listOf("PL1", "PL1"), listOf("PL 1"), listOf("x"), listOf("PLé1")).forEach { bad ->
            assertNull(bad.toString(), LightExperience.saveScene(LightsSnapshot(bulbs), scene.copy(playlists = bad)))
            assertNull(bad.toString(), LightExperienceCodec.scene(LightExperienceCodec.sceneJson(scene.copy(playlists = bad))))
        }
        assertNull(LightExperienceCodec.scene(LightExperienceCodec.sceneJson(scene).put("playlists", "PL1")))
    }

    @Test fun deletingSceneRemovesOnlyItsRulesAndKeepsBulbsPreferencesAndOtherScenes() {
        val snapshot = LightsSnapshot(bulbs, LightsAutomation(rules = listOf(rule, rule.copy(id = "s", sceneId = "movie"))),
            scenes = listOf(scene, scene.copy(id = "reading")))
        val removed = LightExperience.deleteScene(snapshot, "movie")
        assertEquals(bulbs, removed.bulbs)
        assertEquals(listOf(rule), removed.automation.rules)
        assertEquals(listOf(scene.copy(id = "reading")), removed.scenes)
        assertEquals(snapshot.configuration, removed.configuration)
    }

    @Test fun oldControllerCannotErasePausedSolarSceneOrFadeFields() {
        val extended = rule.copy(name = "Evening", enabled = false, time = "sunset", offset = -30, sceneId = "movie",
            activeAfter = 123, change = rule.change.copy(fadeSeconds = 300))
        val incoming = LightsAutomation(awayOff = true, rules = listOf(rule.copy(change = LightChange(on = false))))
        val result = LightExperience.preserveRuleFeatures(incoming, LightsAutomation(rules = listOf(extended)))
        assertTrue(result.awayOff)
        assertEquals(listOf(extended.copy(change = LightChange(on = false, fadeSeconds = 300))), result.rules)
    }

    @Test fun oldJsonReadsAndNewDiskAndWireRecordsRoundTrip() {
        val old = JSONObject("""{"bulbs":[{"id":1,"name":"Desk"}],"automation":{"rules":[{"id":"r","days":[1],"minute":60,"on":false}]}}""")
        val decoded = LightsCodec.snapshot(old)
        assertEquals(1, decoded.bulbs.size)
        assertTrue(decoded.automation.rules.single().enabled)
        assertEquals("clock", decoded.automation.rules.single().time)
        assertTrue(decoded.scenes.isEmpty())
        val snapshot = LightsSnapshot(bulbs, LightsAutomation(rules = listOf(rule.copy(time = "sunset", offset = -30,
            enabled = false, sceneId = "movie", activeAfter = 42))), scenes = listOf(scene),
            location = LightLocation(52.52, 13.405), history = listOf(LightRun(100, "Evening", "1 of 2 lights changed")), zone = zone.id)
        assertEquals(snapshot, LightsCodec.snapshot(JSONObject(LightsCodec.snapshotJson(snapshot).toString())))
    }

    @Test fun malformedSceneAndRuleFieldsAreRejected() {
        val json = LightExperienceCodec.sceneJson(scene)
        json.getJSONArray("changes").put(JSONObject().put("id", 1).put("on", true))
        assertNull(LightExperienceCodec.scene(json))
        assertNull(LightsCodec.rule(LightsCodec.ruleJson(rule).put("enabled", "yes")))
        assertNull(LightsCodec.rule(LightsCodec.ruleJson(rule).put("time", "unknown")))
        assertFalse(LightsPolicy.valid(rule.change.copy(fadeSeconds = -1)))
        assertFalse(LightsPolicy.valid(rule.change.copy(fadeSeconds = 3601)))
        assertNull(LightExperienceCodec.location(JSONObject().put("latitude", 91).put("longitude", 0)))
        assertFalse(LightLocation(Double.NaN, 0.0).valid)
    }

    @Test fun ruleValidationChecksLocationScenesAndRealTargets() {
        val snapshot = LightsSnapshot(bulbs)
        assertFalse(LightExperience.validRule(snapshot, rule.copy(time = "sunset")))
        assertFalse(LightExperience.validRule(snapshot, rule.copy(sceneId = "missing")))
        assertFalse(LightExperience.validRule(snapshot, rule.copy(bulbs = listOf(99))))
        assertTrue(LightExperience.validRule(snapshot.copy(location = LightLocation(52.52, 13.405)), rule.copy(time = "sunset")))
    }

    @Test fun solarTimesMatchNoaaReferenceWithinFiveMinutesAndRespectLocalDate() {
        // NOAA 2026 Denver sunrise table: June 1 at 05:34 MDT.
        val denver = ZoneId.of("America/Denver")
        val sunrise = requireNotNull(lightSolarTime(LocalDate.of(2026, 6, 1), denver, LightLocation(39.74, -104.99), true))
        assertTrue("$sunrise", abs(sunrise.hour * 60 + sunrise.minute - (5 * 60 + 34)) <= 5)
        listOf("Pacific/Auckland" to LightLocation(-36.85, 174.76), "Pacific/Honolulu" to LightLocation(21.31, -157.86)).forEach { (id, location) ->
            val date = LocalDate.of(2026, 6, 1)
            assertEquals(date, requireNotNull(lightSolarTime(date, ZoneId.of(id), location, false)).toLocalDate())
        }
    }

    @Test fun polarEventsAreAbsentAndRulesReconsiderTomorrow() {
        val location = LightLocation(89.0, 0.0)
        val summer = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, ZoneId.of("UTC"))
        assertNull(lightSolarTime(summer.toLocalDate(), summer.zone, location, true))
        val snapshot = LightsSnapshot(automation = LightsAutomation(rules = listOf(rule.copy(time = "sunrise", days = (1..7).toSet()))), location = location)
        assertEquals(summer.toLocalDate().plusDays(1).atStartOfDay(summer.zone), LightExperience.next(snapshot, summer))
    }

    @Test fun solarOffsetDueRunsOnceAndPreservesPriorityAndMissedWindow() {
        val location = LightLocation(52.52, 13.405)
        val date = LocalDate.of(2026, 10, 19)
        val sunset = requireNotNull(lightSolarTime(date, zone, location, false)).minusMinutes(30)
        val solar = rule.copy(time = "sunset", offset = -30)
        assertEquals(listOf(solar), LightsPolicy.due(listOf(solar), sunset.minusMinutes(1).toInstant().toEpochMilli(), sunset, location))
        assertTrue(LightsPolicy.due(listOf(solar), sunset.toInstant().toEpochMilli(), sunset.plusMinutes(1), location).isEmpty())
        assertTrue(LightsPolicy.due(listOf(solar), sunset.minusDays(1).toInstant().toEpochMilli(), sunset.plusMinutes(16), location).isEmpty())
    }

    @Test fun fadesUseNativeTlvAndOffCanFadeWithoutLeavingLightOn() {
        val command = LightsPolicy.commands(LightChange(on = false, fadeSeconds = 30)).single()
        assertEquals(MatterIds.MOVE_TO_LEVEL_WITH_ON_OFF, command.command)
        val reader = TlvReader(command.tlv)
        reader.enterStructure(AnonymousTag)
        assertEquals(0u, reader.getUInt(ContextSpecificTag(0)))
        assertEquals(300u, reader.getUInt(ContextSpecificTag(1)))
        val effective = LightsGroups.merge(LightChange(on = true, fadeSeconds = 900), LightChange(level = 40))
        assertEquals(900, effective.fadeSeconds)
    }

    @Test fun historyIsBoundedAndDoesNotInventSuccessfulResults() {
        var snapshot = LightsSnapshot()
        repeat(20) { snapshot = LightExperience.record(snapshot, it + 1L, "Night", "The light did not respond") }
        assertEquals(12, snapshot.history.size)
        assertEquals(20L, snapshot.history.first().at)
        assertTrue(snapshot.history.all { it.result == "The light did not respond" })
    }

    @Test fun homeConditionRequiresFreshAuthenticatedVoteAndLiveListener() {
        assertFalse(LightExperience.homeConfirmed(true, null, 1_000))
        assertFalse(LightExperience.homeConfirmed(false, 1_000, 1_000))
        assertTrue(LightExperience.homeConfirmed(true, 1_000, 181_000))
        assertFalse(LightExperience.homeConfirmed(true, 1_000, 181_001))
        assertFalse(LightExperience.homeConfirmed(true, 2_000, 1_000))
    }

    @Test fun explicitReadReconcilesExternalStateWithoutChangingPreferences() {
        fun uint(value: UInt) = TlvWriter().put(AnonymousTag, value).getEncoded()
        val values = mapOf((6L to 0L) to TlvWriter().put(AnonymousTag, false).getEncoded(),
            (8L to 0L) to uint(127u), (8L to 0xFFFCL) to uint(1u), (0x300L to 0xFFFCL) to uint(17u),
            (0x300L to 8L) to uint(2u), (0x300L to 7L) to uint(370u))
        val read = lightFromReading(Bulb(1, "Desk", on = true, level = 100), values, 100)
        assertEquals(false, read.on)
        assertEquals(50, read.level)
        assertEquals(LightColor.White(2703), read.color)
        assertEquals(LightReading(100, true, true, true), read.reading)
        val snapshot = LightsSnapshot(listOf(read))
        assertEquals(snapshot, LightsCodec.snapshot(JSONObject(LightsCodec.snapshotJson(snapshot).toString())))
    }

    @Test(expected = java.io.IOException::class)
    fun readWithoutRequiredPowerDoesNotInventAState() {
        lightFromReading(Bulb(1, "Desk", on = true), emptyMap(), 100)
    }

    @Test fun maximumSnapshotFitsExistingEncryptedFrame() {
        val name = "x\u0001".repeat(20)
        val allBulbs = (1..16).map { Bulb(Long.MAX_VALUE - it, name, true, 100, LightColor.Hue(359, 100),
            LightReading(Long.MAX_VALUE, true, true, true)) }
        val variation = LightVariation(false, hue = 100, saturation = 100, brightness = 100, speed = 100)
        val scenes = (1..16).map { LightScene("s".repeat(38) + it, name,
            allBulbs.associate { it.id to LightChange(true, 100, LightColor.Hue(359, 100), 3600) },
            variation, allBulbs.associate { it.id to variation }, juggle = 3600,
            playlists = (0 until 10).map { index -> "P$index".padEnd(150, 'x') }) }
        val groups = allBulbs.mapIndexed { index, bulb -> LightsGroup("g$index", name, listOf(bulb.id),
            LightPreferences(LightChange(true, 100, LightColor.Hue(359, 100)), true, true), variation) }
        val allRules = (1..20).map { rule.copy(id = "r$it", name = name, bulbs = allBulbs.map(Bulb::id),
            days = (1..7).toSet(), sceneId = scenes.first().id, activeAfter = Long.MAX_VALUE, onlyHome = true) }
        val snapshot = LightsSnapshot(allBulbs, LightsAutomation(true, true, allRules),
            LightsConfiguration(groups, allBulbs.associate { it.id to groups.first().preferences }), scenes,
            LightLocation(52.52, 13.405), List(12) { LightRun(Long.MAX_VALUE, name, "x\u0001".repeat(80)) }, zone.id,
            LightsVariation(variation, allBulbs.associate { it.id to variation }, LightJuggle(3600, allBulbs.map(Bulb::id).toSet())))
        // The whole reply, as Lights.remoteSnapshot builds it with a worst-case message (an escaped
        // 40-character name plus the longest fixed text), in one encrypted frame (12-byte nonce +
        // 16-byte tag, 131,072 bytes) with at least 2 KB to spare. Builds before custom variation amounts cap
        // frames at 65,536 bytes, which this worst case (every variation custom) passes.
        val reply = JSONObject().put("ok", false).put("error", "\u0001".repeat(40) + "x".repeat(160))
            .put("lights", LightsCodec.snapshotJson(snapshot)).put("lightsVersion", 1).put("lightsSetup", true)
            .put("lightsVariation", true).put("lightsJuggle", true).put("exactAlarms", true)
            .put("lightsGroupVariation", true).put("lightsDance", true).put("lightsPermanentlyOff", true)
            .put("lightsScenePlaylists", true).put("danceStatus", "x".repeat(60))
        val bytes = reply.toString().toByteArray(Charsets.UTF_8).size + 28
        assertTrue("Reply frame was $bytes bytes", bytes <= 131_072 - 2_048)
    }
}
