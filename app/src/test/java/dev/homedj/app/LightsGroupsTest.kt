package dev.homedj.app

import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightsGroupsTest {
    private val bulbs = listOf(Bulb(1, "Desk"), Bulb(2, "Ceiling"), Bulb(3, "Hall"))
    private val group = LightsGroup("room", "Living room", listOf(1, 2))
    private val warm = LightColor.White(2700)

    @Test fun individualPreferencesWinPerFieldAndClearingRestoresInheritance() {
        val default = LightChange(on = true, level = 80, color = warm)
        val config = LightsConfiguration(listOf(group), mapOf(1L to LightPreferences(LightChange(level = 25))))
        assertEquals(default.copy(level = 25), LightsGroups.effective(config, 1, default))
        assertEquals(default, LightsGroups.effective(config, 2, default))
        val clear = config.copy(individual = config.individual + (1L to LightPreferences()))
        assertEquals(default, LightsGroups.effective(clear, 1, default))
    }

    @Test fun individualOffWinsOverGroupOnAndBrightness() {
        val config = LightsConfiguration(individual = mapOf(1L to LightPreferences(LightChange(on = false))))
        val change = LightsGroups.effective(config, 1, LightChange(on = true, level = 80, color = warm))
        assertEquals(listOf(MatterIds.OFF), LightsPolicy.commands(change).map { it.command })
        assertEquals(false, LightsPolicy.applied(bulbs[0], change).on)
    }

    @Test fun latestChangeWinsIndividualUntilItsGroupChanges() {
        val grouped = group.copy(preferences = LightPreferences(LightChange(on = true, level = 60)))
        val config = LightsConfiguration(listOf(grouped), mapOf(
            1L to LightPreferences(LightChange(on = false, level = 25, color = warm), awayOff = false),
            2L to LightPreferences(LightChange(level = 40)),
            3L to LightPreferences(LightChange(level = 10))))
        // The bulb was changed last: its own settings beat the group's.
        assertEquals(LightChange(on = false, level = 25, color = warm), LightsGroups.effective(config, 1, LightChange(on = true)))
        // Then the group is changed: its members follow the group again; presence choices and other bulbs stay.
        val after = LightsGroups.rejoin(config, grouped.bulbs)
        assertEquals(LightChange(on = true, level = 60), LightsGroups.effective(after, 1, LightChange(on = true)))
        assertEquals(LightChange(on = true, level = 60), LightsGroups.effective(after, 2, LightChange(on = true)))
        assertEquals(mapOf(1L to LightPreferences(awayOff = false), 3L to LightPreferences(LightChange(level = 10))), after.individual)
        // Changing the bulb again makes it win once more.
        val again = after.copy(individual = after.individual + (1L to LightPreferences(LightChange(on = true, level = 30))))
        assertEquals(LightChange(on = true, level = 30), LightsGroups.effective(again, 1, LightChange(on = true)))
    }

    @Test fun allOffAfterTurningOneLightOnDirectlyTurnsEveryLightOff() {
        val config = LightsConfiguration(listOf(group), mapOf(1L to LightPreferences(LightChange(on = true, level = 40)),
            3L to LightPreferences(LightChange(on = true), homeOn = false)))
        // The bug: the direct "on" survived an all-lights off.
        assertEquals(true, LightsGroups.effective(config, 1, LightChange(on = false)).on)
        val after = LightsGroups.rejoin(config, bulbs.map(Bulb::id))
        bulbs.forEach { assertEquals(LightChange(on = false), LightsGroups.effective(after, it.id, LightChange(on = false))) }
        assertEquals(mapOf(3L to LightPreferences(homeOn = false)), after.individual)
    }

    @Test fun allLightChangeAfterGroupChangeWinsAtTheNextTurnOn() {
        val config = LightsConfiguration(listOf(group.copy(preferences = LightPreferences(LightChange(level = 60, color = warm)))))
        val after = LightsGroups.allChanged(config, bulbs.map(Bulb::id), LightChange(level = 80))
        // "All on" later restores the latest brightness, keeping the group's colour it never changed.
        assertEquals(LightChange(on = true, level = 80, color = warm), LightsGroups.effective(after, 1, LightChange(on = true)))
        assertEquals(LightChange(on = true), LightsGroups.effective(after, 3, LightChange(on = true)))
    }

    @Test fun clearingAnOverrideWhileOffRestoresGroupColourAndLevelAtNextOn() {
        val config = LightsConfiguration(listOf(group.copy(preferences =
            LightPreferences(LightChange(on = false, level = 60, color = warm)))))
        val nextOn = LightsGroups.effective(config, 1, LightChange(on = true))
        assertEquals(LightChange(on = true, level = 60, color = warm), nextOn)
        assertEquals(nextOn, LightsGroups.effective(config, 2, LightChange(level = 60)))
        // Merely picking a colour still doesn't turn an inherited bulb on.
        assertEquals(LightChange(color = warm), LightsGroups.effective(config, 1, LightChange(color = warm)))
    }

    @Test fun directBrightnessRemembersPowerSoAllOffCannotUndoIt() {
        val own = LightsGroups.preferenceChange(LightChange(level = 40))
        val config = LightsConfiguration(individual = mapOf(1L to LightPreferences(own)))
        assertEquals(LightChange(on = true, level = 40), LightsGroups.effective(config, 1, LightChange(on = false)))
        // Colour alone leaves power inherited.
        assertEquals(LightChange(color = warm), LightsGroups.preferenceChange(LightChange(color = warm)))
    }

    @Test fun presenceInheritsIndividualThenGroupThenAllIncludingExplicitFalse() {
        val snapshot = LightsSnapshot(bulbs, LightsAutomation(awayOff = true, homeOn = false),
            LightsConfiguration(listOf(group.copy(preferences = LightPreferences(awayOff = false, homeOn = true))),
                mapOf(1L to LightPreferences(awayOff = true, homeOn = false))))
        assertTrue(LightsGroups.presence(snapshot, 1, home = false))
        assertFalse(LightsGroups.presence(snapshot, 1, home = true))
        assertFalse(LightsGroups.presence(snapshot, 2, home = false))
        assertTrue(LightsGroups.presence(snapshot, 2, home = true))
        assertTrue(LightsGroups.presence(snapshot, 3, home = false))
        assertFalse(LightsGroups.presence(snapshot, 3, home = true))
    }

    @Test fun groupsRejectUnknownDuplicateOrAlreadyGroupedBulbsAndBadPreferences() {
        val config = LightsConfiguration(listOf(group))
        assertTrue(LightsGroups.valid(group, config, bulbs))
        assertTrue(LightsGroups.valid(LightsGroup("hall", "Hall", listOf(3)), config, bulbs))
        assertFalse(LightsGroups.valid(group.copy(id = "other"), config, bulbs))
        assertFalse(LightsGroups.valid(group.copy(bulbs = listOf(99)), config, bulbs))
        assertFalse(LightsGroups.valid(group.copy(bulbs = listOf(1, 1)), config, bulbs))
        assertFalse(LightsGroups.valid(group.copy(bulbs = emptyList()), config, bulbs))
        assertFalse(LightsGroups.valid(group.copy(name = " "), config, bulbs))
        assertFalse(LightsGroups.valid(LightPreferences(LightChange(level = 0))))
    }

    @Test fun presenceCannotOverridePinnedPowerOrRestoreBulbsItDidNotTurnOff() {
        val snapshot = LightsSnapshot(bulbs.map { it.copy(on = true) }, LightsAutomation(awayOff = true, homeOn = true),
            LightsConfiguration(listOf(group), mapOf(1L to LightPreferences(LightChange(on = true)))))
        assertEquals(listOf(2L, 3L), LightsGroups.presenceTargets(snapshot, emptySet(), home = false))
        val returned = snapshot.copy(bulbs = bulbs.map { it.copy(on = false) },
            configuration = snapshot.configuration.copy(individual = mapOf(2L to LightPreferences(LightChange(on = false)))))
        assertEquals(listOf(3L), LightsGroups.presenceTargets(returned, setOf(2L, 3L), home = true))
    }

    @Test fun groupSchedulesFollowCurrentMembershipAndMissingGroupsNeverMeanAll() {
        val rule = LightsRule("r", setOf(1), 600, LightChange(on = false), listOf(1, 2), "room")
        val snapshot = LightsSnapshot(bulbs, configuration = LightsConfiguration(listOf(group.copy(bulbs = listOf(3)))))
        assertEquals(listOf(3L), LightsGroups.targets(snapshot, rule))
        assertEquals(emptyList<Long>(), LightsGroups.targets(snapshot.copy(configuration = LightsConfiguration()), rule))
        assertEquals(listOf(1L, 2L), LightsGroups.targets(snapshot, rule.copy(groupId = null)))
    }

    @Test fun sameTimeSchedulesApplyAllThenGroupThenIndividualRegardlessOfListOrder() {
        val all = LightsRule("all", setOf(1), 600, LightChange(on = false))
        val grouped = all.copy(id = "group", bulbs = group.bulbs, groupId = group.id)
        val individual = all.copy(id = "individual", bulbs = listOf(1), change = LightChange(on = true))
        val now = ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        val due = LightsPolicy.due(listOf(individual, grouped, all), now.minusMinutes(1).toInstant().toEpochMilli(), now)
        assertEquals(listOf(all, grouped, individual), due)
        // A later broad rule still runs later; the bulb preference protects the individual intent.
        val later = all.copy(id = "later", minute = 601)
        assertEquals(listOf(individual, later), LightsPolicy.due(listOf(later, individual),
            now.minusMinutes(1).toInstant().toEpochMilli(), now.plusMinutes(1)))
    }

    @Test fun configurationAndGroupSchedulesSurviveRealJsonRoundTripWithExplicitFalse() {
        val config = LightsConfiguration(listOf(group.copy(preferences = LightPreferences(LightChange(level = 60), awayOff = true))),
            mapOf(1L to LightPreferences(LightChange(on = false, color = warm), awayOff = false)))
        val automation = LightsAutomation(rules = listOf(LightsRule("r", setOf(1), 600,
            LightChange(on = true), group.bulbs, group.id)))
        val original = LightsSnapshot(bulbs, automation, config)
        assertEquals(original, LightsCodec.snapshot(JSONObject(LightsCodec.snapshotJson(original).toString())))
        assertEquals(config, LightsGroupsCodec.read(JSONObject(LightsGroupsCodec.json(config).toString()), bulbs))
        // Older snapshots remain readable with no migration or loss of paired bulbs.
        val old = LightsCodec.snapshotJson(original).also { it.remove("configuration") }
        assertEquals(LightsSnapshot(bulbs, automation), LightsCodec.snapshot(old))
        val legacyRule = LightsCodec.automationJson(automation).getJSONArray("rules").getJSONObject(0)
        assertEquals(group.bulbs.toSet(), LightsCodec.ids(legacyRule.getJSONArray("bulbs").toString()))
    }

    @Test fun forgettingLastBulbKeepsEmptyGroupWithoutBroadeningSchedule() {
        val config = LightsConfiguration(listOf(group.copy(bulbs = listOf(1))), mapOf(1L to LightPreferences(awayOff = false)))
        val after = LightsGroups.withoutBulb(config, 1)
        assertTrue(after.individual.isEmpty())
        assertTrue(after.groups.single().bulbs.isEmpty())
        assertEquals(after, LightsGroupsCodec.read(JSONObject(LightsGroupsCodec.json(after).toString()), bulbs.drop(1)))
    }

    @Test fun invalidSavedGroupsAreBoundedAndDoNotCaptureAnotherGroupsMembers() {
        val wire = JSONObject().put("groups", JSONArray()
            .put(LightsGroupsCodec.groupJson(group))
            .put(LightsGroupsCodec.groupJson(group.copy(id = "other", bulbs = listOf(2, 3, 99))))
            .put(LightsGroupsCodec.groupJson(group.copy(id = "bad", name = " "))))
        val config = LightsGroupsCodec.read(wire, bulbs)
        assertEquals(listOf(group, group.copy(id = "other", bulbs = listOf(3))), config.groups)
    }

    @Test fun discoveredLightMustMatchQrOrManualDiscriminator() {
        assertTrue(matchesMatterCandidate("MT:Y.K9042C00KA0648G00", 3840))
        assertFalse(matchesMatterCandidate("MT:Y.K9042C00KA0648G00", 3841))
        assertTrue(matchesMatterCandidate("34970112332", 3840))
        assertTrue(matchesMatterCandidate("34970112332", 4095))
        assertFalse(matchesMatterCandidate("34970112332", 100))
        assertFalse(matchesMatterCandidate("not a code", 3840))
    }

    @Test fun oldControllerEditsCannotSilentlyDetachGroupSchedules() {
        val rule = LightsRule("r", setOf(1), 600, LightChange(on = true), group.bulbs, group.id)
        val existing = LightsAutomation(rules = listOf(rule))
        val oldEdit = existing.copy(awayOff = true, rules = listOf(rule.copy(groupId = null, minute = 700)))
        assertEquals(oldEdit.copy(rules = listOf(rule.copy(minute = 700))), LightsGroups.preserveRuleTargets(oldEdit, existing))
        assertEquals(LightsAutomation(), LightsGroups.preserveRuleTargets(LightsAutomation(), existing))
    }

    @Test fun malformedPreferencesAreRejectedRatherThanConvertedToOverrides() {
        assertEquals(LightPreferences(), LightsGroupsCodec.preferences(JSONObject()))
        assertEquals(LightPreferences(awayOff = false), LightsGroupsCodec.preferences(JSONObject().put("awayOff", false)))
        assertNull(LightsGroupsCodec.preferences(JSONObject().put("awayOff", "false")))
        assertNull(LightsGroupsCodec.preferences(JSONObject().put("on", JSONObject.NULL)))
        assertNull(LightsGroupsCodec.preferences(JSONObject().put("level", 20.5)))
        assertNull(LightsGroupsCodec.preferences(JSONObject().put("level", "50")))
        assertNull(LightsGroupsCodec.preferences(JSONObject().put("sat", 50)))
        assertNull(LightsGroupsCodec.preferences(JSONObject().put("hue", 40).put("sat", 20).put("kelvin", 2700)))
        assertNull(LightsGroupsCodec.group(LightsGroupsCodec.groupJson(group).put("preferences", JSONObject.NULL)))
    }

    @Test fun retryAfterUnconfirmedScheduleSaveUpdatesSameRuleWithoutDuplicates() {
        val rule = LightsRule("stable-draft", setOf(1), 600, LightChange(on = true), group.bulbs, group.id)
        val saved = LightsPolicy.withRule(LightsAutomation(), rule)
        assertEquals(saved, LightsPolicy.withRule(saved, rule))
        assertEquals(listOf(rule.copy(minute = 700)), LightsPolicy.withRule(saved, rule.copy(minute = 700)).rules)
    }
}
