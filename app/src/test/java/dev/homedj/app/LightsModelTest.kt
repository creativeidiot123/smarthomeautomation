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

class LightsModelTest {
    private val zone = ZoneId.of("Europe/Berlin")
    // 2026-10-19 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, second, 0, zone)
    private fun millis(time: ZonedDateTime) = time.toInstant().toEpochMilli()
    private fun rule(id: String, minute: Int, change: LightChange, days: Set<Int> = setOf(1), bulbs: List<Long>? = null) =
        LightsRule(id, days, minute, change, bulbs)

    @Test fun commandsSendColourBeforePowerAndOffAlone() {
        val warm = LightColor.White(2700)
        assertEquals(listOf(MatterIds.ON_OFF to MatterIds.OFF),
            LightsPolicy.commands(LightChange(on = false, level = 50, color = warm)).map { it.cluster to it.command })
        assertEquals(listOf(MatterIds.COLOR_CONTROL to MatterIds.MOVE_TO_COLOR_TEMPERATURE,
            MatterIds.LEVEL_CONTROL to MatterIds.MOVE_TO_LEVEL_WITH_ON_OFF),
            LightsPolicy.commands(LightChange(on = true, level = 50, color = warm)).map { it.cluster to it.command })
        assertEquals(listOf(MatterIds.ON_OFF to MatterIds.ON),
            LightsPolicy.commands(LightChange(on = true)).map { it.cluster to it.command })
        assertEquals(listOf(MatterIds.COLOR_CONTROL to MatterIds.MOVE_TO_HUE_AND_SATURATION),
            LightsPolicy.commands(LightChange(color = LightColor.Hue(0, 100))).map { it.cluster to it.command })
    }

    @Test fun bulbThatMayBeOffPowersOnBeforeItsColour() {
        val warm = LightColor.White(2700)
        // Some bulbs ignore ExecuteIfOff: colour sent while off is dropped and the bulb comes on in its old colour.
        assertEquals(listOf(MatterIds.LEVEL_CONTROL to MatterIds.MOVE_TO_LEVEL_WITH_ON_OFF,
            MatterIds.COLOR_CONTROL to MatterIds.MOVE_TO_COLOR_TEMPERATURE),
            LightsPolicy.commands(LightChange(on = true, level = 50, color = warm), off = true).map { it.cluster to it.command })
        assertEquals(listOf(MatterIds.ON_OFF to MatterIds.ON, MatterIds.COLOR_CONTROL to MatterIds.MOVE_TO_HUE_AND_SATURATION),
            LightsPolicy.commands(LightChange(on = true, color = LightColor.Hue(0, 100)), off = true).map { it.cluster to it.command })
        // Colour alone never powers a bulb on; off stays a single command.
        assertEquals(listOf(MatterIds.COLOR_CONTROL to MatterIds.MOVE_TO_COLOR_TEMPERATURE),
            LightsPolicy.commands(LightChange(color = warm), off = true).map { it.cluster to it.command })
        assertEquals(listOf(MatterIds.ON_OFF to MatterIds.OFF),
            LightsPolicy.commands(LightChange(on = false, color = warm), off = true).map { it.cluster to it.command })
    }

    @Test fun recordsOnlyWhatTheChangeImplies() {
        val bulb = Bulb(7, "Desk", on = true, level = 40, color = LightColor.Hue(0, 100))
        assertEquals(bulb.copy(on = false), LightsPolicy.applied(bulb, LightChange(on = false, level = 90)))
        assertEquals(Bulb(7, "Desk", on = true, level = 80), LightsPolicy.applied(Bulb(7, "Desk", on = false),
            LightChange(level = 80)))
        // Colour alone is taken while off (ExecuteIfOff) but does not turn the bulb on.
        assertEquals(Bulb(7, "Desk", on = false, color = LightColor.White(4000)),
            LightsPolicy.applied(Bulb(7, "Desk", on = false), LightChange(color = LightColor.White(4000))))
    }

    @Test fun validatesChangesAndRules() {
        assertFalse(LightsPolicy.valid(LightChange()))
        assertFalse(LightsPolicy.valid(LightChange(level = 0)))
        assertFalse(LightsPolicy.valid(LightChange(color = LightColor.Hue(360, 100))))
        assertFalse(LightsPolicy.valid(LightChange(color = LightColor.White(9000))))
        assertTrue(LightsPolicy.valid(LightChange(on = true, level = 100, color = LightColor.Hue(359, 0))))
        assertTrue(LightsPolicy.valid(rule("a", 0, LightChange(on = false), (1..7).toSet())))
        assertFalse(LightsPolicy.valid(rule("a", 1440, LightChange(on = false))))
        assertFalse(LightsPolicy.valid(rule("a", 60, LightChange(on = false), emptySet())))
        assertFalse(LightsPolicy.valid(rule("a", 60, LightChange(on = false), setOf(8))))
        assertFalse(LightsPolicy.valid(rule("a", 60, LightChange(on = false), bulbs = emptyList())))
        assertFalse(LightsPolicy.valid(rule("", 60, LightChange(on = false))))
    }

    @Test fun dueRulesRunOnceInOrderOnTheirDays() {
        val off = rule("off", 19 * 60, LightChange(on = false))
        val on = rule("on", 19 * 60 + 5, LightChange(on = true))
        assertEquals(listOf(off, on), LightsPolicy.due(listOf(on, off), millis(at(19, 18, 59)), at(19, 19, 5)))
        // Exclusive start: the run that already happened at 19:00 does not repeat it.
        assertEquals(listOf(on), LightsPolicy.due(listOf(on, off), millis(at(19, 19, 0)), at(19, 19, 5)))
        // Tuesday is not a rule day.
        assertEquals(emptyList<LightsRule>(), LightsPolicy.due(listOf(off), millis(at(20, 18, 0)), at(20, 19, 1)))
    }

    @Test fun skipsRulesMissedByMoreThanFifteenMinutes() {
        val off = rule("off", 19 * 60, LightChange(on = false))
        val daysAgo = millis(at(16, 8, 0))
        assertEquals(listOf(off), LightsPolicy.due(listOf(off), daysAgo, at(19, 19, 14)))
        assertEquals(emptyList<LightsRule>(), LightsPolicy.due(listOf(off), daysAgo, at(19, 19, 16)))
    }

    @Test fun dueRulesCrossMidnight() {
        val late = rule("late", 23 * 60 + 58, LightChange(on = false), (1..7).toSet())
        assertEquals(listOf(late), LightsPolicy.due(listOf(late), millis(at(19, 23, 55)), at(20, 0, 2)))
    }

    @Test fun nextRuleIsStrictlyAfterNow() {
        val off = rule("off", 19 * 60, LightChange(on = false), setOf(1, 3))
        assertNull(LightsPolicy.next(emptyList(), at(19, 12, 0)))
        assertEquals(at(19, 19, 0), LightsPolicy.next(listOf(off), at(19, 12, 0)))
        assertEquals(at(21, 19, 0), LightsPolicy.next(listOf(off), at(19, 19, 0)))
        assertEquals(at(26, 19, 0), LightsPolicy.next(listOf(off), at(21, 19, 30)))
    }

    @Test fun daylightSavingGapMovesTheRuleForward() {
        // 2026-03-29 (Sunday) Berlin clocks jump from 02:00 to 03:00.
        val night = rule("night", 2 * 60 + 30, LightChange(on = false), setOf(7))
        assertEquals(ZonedDateTime.of(2026, 3, 29, 3, 30, 0, 0, zone),
            LightsPolicy.next(listOf(night), ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, zone)))
    }

    @Test fun presenceTurnsBackOnOnlyWhatItTurnedOff() {
        val bulbs = listOf(Bulb(1, "A", on = true), Bulb(2, "B", on = false), Bulb(3, "C"))
        assertEquals(listOf(1L, 3L), LightsPolicy.awayTargets(bulbs))
        val afterAway = listOf(Bulb(1, "A", on = false), Bulb(2, "B", on = false), Bulb(3, "C", on = true))
        // C was turned on again meanwhile and B was already off before leaving.
        assertEquals(listOf(1L), LightsPolicy.homeTargets(afterAway, setOf(1L, 3L)))
    }

    @Test fun namesNewBulbsWithTheFirstFreeNumber() {
        assertEquals("Light 1", LightsPolicy.defaultName(emptyList()))
        assertEquals("Light 2", LightsPolicy.defaultName(listOf(Bulb(5, "Light 1"), Bulb(6, "Light 3"))))
        assertNull(LightsPolicy.validName("   "))
        assertNull(LightsPolicy.validName("x".repeat(41)))
        assertEquals("Desk", LightsPolicy.validName("  Desk "))
    }

    @Test fun bulbsRoundTripAndDropInvalidEntries() {
        val bulbs = listOf(
            Bulb(Long.MAX_VALUE, "Desk", on = true, level = 60, color = LightColor.Hue(120, 80)),
            Bulb(2, "Hall", on = false, color = LightColor.White(2700)),
            Bulb(3, "New"),
        )
        assertEquals(bulbs, LightsCodec.bulbs(LightsCodec.bulbsJson(bulbs).toString()))
        val messy = JSONArray()
            .put(JSONObject().put("id", 4).put("name", "Ok").put("level", 500).put("kelvin", 99))
            .put(JSONObject().put("id", 0).put("name", "No id"))
            .put(JSONObject().put("id", 5).put("name", " "))
            .put(JSONObject().put("id", 4).put("name", "Duplicate"))
        assertEquals(listOf(Bulb(4, "Ok")), LightsCodec.bulbs(messy.toString()))
    }

    @Test fun migratesTheLegacyTestBulbOnce() {
        assertEquals(listOf(Bulb(42, "Light 1")), LightsCodec.migrate(null, 42))
        assertNull(LightsCodec.migrate("[]", 42))
        assertNull(LightsCodec.migrate(null, 0))
    }

    @Test fun automationRoundTripsAndStrictRejectsBadRules() {
        val automation = LightsAutomation(awayOff = true, homeOn = true, rules = listOf(
            rule("a", 7 * 60, LightChange(on = true, level = 80, color = LightColor.White(2700)), setOf(1, 2, 3, 4, 5)),
            rule("b", 23 * 60, LightChange(on = false), (1..7).toSet(), bulbs = listOf(1L, 2L)),
        ))
        val json = LightsCodec.automationJson(automation)
        assertEquals(automation, LightsCodec.automation(JSONObject(json.toString()), strict = true))
        json.getJSONArray("rules").put(JSONObject().put("id", "bad").put("minute", 5000))
        assertNull(LightsCodec.automation(json, strict = true))
        assertEquals(automation, LightsCodec.automation(json, strict = false))
        assertEquals(LightsAutomation(), LightsCodec.automation(JSONObject(), strict = true))
    }

    @Test fun snapshotRoundTripsOverTheWire() {
        val snapshot = LightsSnapshot(listOf(Bulb(9, "Lamp", on = true, level = 30)),
            LightsAutomation(awayOff = true, rules = listOf(rule("r", 60, LightChange(on = false)))))
        assertEquals(snapshot, LightsCodec.snapshot(JSONObject(LightsCodec.snapshotJson(snapshot).toString())))
        assertEquals(LightsSnapshot(), LightsCodec.snapshot(JSONObject()))
    }

    @Test fun parsesLanChangesWithoutTrustingThem() {
        val request = JSONObject().put("command", "lightsSet").put("on", true).put("hue", 30)
        val change = LightsCodec.change(request)
        assertEquals(LightChange(on = true, color = LightColor.Hue(30, -1)), change)
        assertFalse(LightsPolicy.valid(change))
        assertEquals(LightChange(level = 55),
            LightsCodec.change(LightsCodec.putChange(JSONObject(), LightChange(level = 55))))
        assertEquals(setOf(3L, 4L), LightsCodec.ids("[3,4,0,-1]"))
    }

    @Test fun permanentlyOffSurvivesSavingAndOlderRecordsReadAsControlled() {
        val bulbs = listOf(Bulb(1, "Top", on = false, permanentlyOff = true), Bulb(2, "Bedside", on = true))
        val json = LightsCodec.bulbsJson(bulbs)
        assertEquals(bulbs, LightsCodec.bulbs(json.toString()))
        assertTrue(json.getJSONObject(0).getBoolean("permanentlyOff"))
        assertFalse(json.getJSONObject(1).has("permanentlyOff"))
        assertEquals(setOf(1L), LightsCodec.bulbs(json.toString()).permanentlyOff())
        assertEquals(emptySet<Long>(), LightsCodec.bulbs("""[{"id":1,"name":"Top","on":false}]""").permanentlyOff())
    }

    @Test fun permanentlyOffIsKeptWhenTheLightsRecordChanges() {
        val top = Bulb(1, "Top", on = false, permanentlyOff = true)
        assertTrue(LightsPolicy.applied(top, LightChange(on = true, level = 50)).permanentlyOff)
        assertTrue(LightsCodec.snapshot(LightsCodec.snapshotJson(LightsSnapshot(bulbs = listOf(top)))).bulbs.single().permanentlyOff)
    }
}
