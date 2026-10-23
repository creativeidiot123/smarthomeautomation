package dev.homedj.app

import java.time.ZoneId
import java.time.ZonedDateTime
import matter.tlv.AnonymousTag
import matter.tlv.TlvWriter
import org.junit.Assert.*
import org.junit.Test

class HouseRulesTest {
    private val names = HouseNames(listOf("bedside", "top", "Desk Lamp", "Desk"), listOf("Living"), listOf("Movie night"),
        listOf("Evening"))
    private val script = """
        # Bedside, at night: switch off and on at the plug
        when bedside off and on at 23:00-07:00
          if bedside was above 5%
            all 0%          # sleep time
          end
          if bedside was 0%
            all 5%          # washroom
          end

        # Top light by the door: leaving and coming back
        when top turned off for 1m
          all 0%
          schedules off
          music off
        when top turned on
          schedules on
          schedules now
          top 0%            # after schedules, so it stays off
          music on
    """.trimIndent()

    private fun parse(text: String) = HouseScript.parse(text)
    private fun error(text: String) = requireNotNull(parse(text).error) { "expected an error for: $text" }
    private fun levels(text: String) = (parse("when top turned on\n if top $text\n all on\n end")
        .rules.single().steps.single() as HouseStep.If).checks.single().test

    @Test fun parsesTheBedsideAndDoorRules() {
        val result = parse(script)
        assertNull(result.error)
        val (bedside, leaving, back) = result.rules
        val off = HouseStep.Light(null, LightChange(on = false))
        assertEquals(HouseRule(2, "bedside", HouseTrigger.OFF_AND_ON, 60, HouseWindow(23 * 60, 420), listOf(
            HouseStep.If(listOf(HouseCheck(HouseTest.Level("bedside", 6..100))), listOf(off), emptyList()),
            HouseStep.If(listOf(HouseCheck(HouseTest.Level("bedside", 0..0))),
                listOf(HouseStep.Light(null, LightChange(on = true, level = 5))), emptyList()))), bedside)
        assertEquals(HouseTrigger.TURNED_OFF, leaving.trigger)
        assertEquals(60, leaving.seconds)
        assertEquals(listOf(off, HouseStep.Schedule(null, false), HouseStep.Music(false)), leaving.steps)
        assertEquals(listOf(HouseStep.Schedule(null, true), HouseStep.SchedulesNow,
            HouseStep.Light("top", LightChange(on = false)), HouseStep.Music(true)), back.steps)
        assertNull(HouseScript.unknownName(result, names))
    }

    @Test fun bedsideBrightnessDecidesSleepOrWashroom() {
        val bedside = parse(script).rules.first()
        val sleep = listOf(HouseStep.Light(null, LightChange(on = false)))
        val washroom = listOf(HouseStep.Light(null, LightChange(on = true, level = 5)))
        assertEquals(sleep, HouseScript.steps(bedside, 60) { _, _ -> 6 })
        assertEquals(sleep, HouseScript.steps(bedside, 60) { _, _ -> 100 })
        assertEquals(washroom, HouseScript.steps(bedside, 60) { _, _ -> 0 })
        // Exactly 1-5% (e.g. still in washroom light) is neither, as written: nothing changes.
        assertTrue(HouseScript.steps(bedside, 60) { _, _ -> 5 }.isEmpty())
        // `was` is the record before the switch, never the state after it.
        assertEquals(sleep, HouseScript.steps(bedside, 60) { _, current -> if (current) 0 else 40 })
    }

    @Test fun brightnessChecksAndRelativeSteps() {
        assertEquals(HouseTest.Level("top", 1..100), levels("was on"))
        assertEquals(HouseTest.Level("top", 0..0), levels("was off"))
        assertEquals(HouseTest.Level("top", 0..49), levels("was below 50%"))
        assertEquals(HouseTest.Level("top", 20..60), levels("was 20%-60%"))
        assertEquals(HouseTest.Level("top", 51..100, current = true), levels("is above 50%"))
        listOf("was above 100%", "was below 0%", "was 60%-20%", "was 20%-160%", "is bright").forEach {
            assertEquals(it, 2, error("when top turned on\n if top $it\n all on\n end").line)
        }
        val steps = parse("when top turned on\n all +10%\n Living -25%\n all 0%").rules.single().steps
        assertEquals(listOf(HouseStep.Adjust(null, 10), HouseStep.Adjust("Living", -25),
            HouseStep.Light(null, LightChange(on = false))), steps)
        assertEquals(2, error("when top turned on\n all +0%").line)
        assertEquals(2, error("when top turned on\n all +150%").line)
        assertEquals("Line 2: No light or group called \"Hall\".",
            HouseScript.unknownName(parse("when top turned on\n Hall -10%"), names)?.message)
    }

    @Test fun ifElseFollowsTheTime() {
        val timed = parse("when top turned on\n  if not at 23:00-06:00 and Desk was off\n    all on\n  else\n    all off\n  end")
            .rules.single()
        assertEquals(listOf(HouseStep.Light(null, LightChange(on = true))), HouseScript.steps(timed, 12 * 60) { _, _ -> 0 })
        assertEquals(listOf(HouseStep.Light(null, LightChange(on = false))), HouseScript.steps(timed, 23 * 60 + 30) { _, _ -> 0 })
        assertEquals(listOf(HouseStep.Light(null, LightChange(on = false))), HouseScript.steps(timed, 12 * 60) { _, _ -> 40 })
    }

    @Test fun windowsWrapPastMidnight() {
        val night = HouseWindow(23 * 60, 7 * 60)
        assertTrue(night.contains(23 * 60) && night.contains(0) && night.contains(6 * 60 + 59))
        assertFalse(night.contains(7 * 60) || night.contains(12 * 60))
        assertEquals(HouseWindow(0, 420), parse("when top turned on at 24:00 - 07:00\n all on").rules.single().window)
    }

    @Test fun scenesSchedulesAndQuotedNames() {
        val parsed = parse("""
            when "Desk Lamp" turned on for 5m
              scene "Movie night" on
              schedule Evening off
              schedules on
              Living 40%
        """.trimIndent())
        val rule = parsed.rules.single()
        assertEquals(HouseTrigger.TURNED_ON, rule.trigger)
        assertEquals(300, rule.seconds)
        assertEquals(listOf(HouseStep.Scene("Movie night", true), HouseStep.Schedule("Evening", false),
            HouseStep.Schedule(null, true), HouseStep.Light("Living", LightChange(on = true, level = 40))), rule.steps)
        assertNull(HouseScript.unknownName(parsed, names))
        assertEquals("\"Desk Lamp\"", HouseScript.quote("Desk Lamp"))
        assertEquals("\"music\"", HouseScript.quote("music"))
        assertEquals("top", HouseScript.quote("top"))
    }

    @Test fun mistakesNameTheirLine() {
        assertEquals(1, error("all off").line)
        assertEquals("Line 2: This `if` needs an `end`.", error("when top turned on\n  if top was on\n    all off").message)
        assertEquals(3, error("when top turned on\n  all off\nend").line)
        assertEquals(1, error("when top flicked\n  all off").line)
        assertEquals(2, error("when top turned on\n  all 120%").line)
        assertEquals(LightChange(on = false),
            (parse("when top turned on\n  all 0%").rules.single().steps.single() as HouseStep.Light).change)
        assertEquals(2, error("when top turned on\n  \"top off").line)
        assertEquals(1, error("when top turned off for 1s\n  all off").line)
        assertEquals(1, error("when top off and on for 1m\n  all off").line)
        assertEquals(1, error("when top turned on at 07:00-07:00\n  all off").line)
        assertEquals(1, error("when top turned on").line)
        assertEquals(2, error("when top turned on\n  music loud").line)
        assertEquals(0, error("x".repeat(HouseScript.MAX_CHARS + 1)).line)
        assertEquals("Line 2: No scene called \"Party\".",
            HouseScript.unknownName(parse("when top turned on\n  scene Party on"), names)?.message)
        assertEquals("Line 1: No light called \"Living\".",
            HouseScript.unknownName(parse("when Living turned on\n  all on"), names)?.message)
        assertEquals("Line 3: No light called \"Kitchen\".",
            HouseScript.unknownName(parse("when top turned on\n  all on\n  if Kitchen was on\n    all off\n  end"), names)?.message)
    }

    @Test fun eventsMatchHoldsAndQuickLimits() {
        val (quick, off, on) = parse("when top off and on within 30s\n all off\nwhen top turned off for 2m\n all off\n" +
            "when top turned on\n all on").rules
        assertTrue(HouseScript.matches(quick, BulbEvent(HouseTrigger.OFF_AND_ON, 29_000)))
        assertFalse(HouseScript.matches(quick, BulbEvent(HouseTrigger.OFF_AND_ON, 30_000)))
        assertTrue(HouseScript.matches(off, BulbEvent(HouseTrigger.TURNED_OFF, 120_000)))
        assertFalse(HouseScript.matches(off, BulbEvent(HouseTrigger.TURNED_OFF, 30_000)))
        assertTrue(HouseScript.matches(on, BulbEvent(HouseTrigger.TURNED_ON, 0)))
        assertFalse(HouseScript.matches(on, BulbEvent(HouseTrigger.TURNED_ON, 300_000)))
    }

    private fun step(watch: BulbWatch, boot: BulbBoot?, now: Long, off: Set<Long> = emptySet(), on: Set<Long> = emptySet()) =
        BulbWatchPolicy.next(watch, boot, now, off, on)

    @Test fun aFlickTooQuickToSeeIsCaughtByTheRebootCount() {
        val (seen, none) = step(BulbWatch(), BulbBoot(4, 1000), 0)
        assertTrue(none.isEmpty())
        val (_, events) = step(seen, BulbBoot(5, 3), 6_000)
        assertEquals(listOf(BulbEvent(HouseTrigger.TURNED_ON, 0), BulbEvent(HouseTrigger.OFF_AND_ON, 0)), events)
        // A bulb without a working RebootCount still shows a reboot through UpTime.
        val (_, upTime) = step(BulbWatch(BulbBoot(-1, 900)), BulbBoot(-1, 4), 6_000)
        assertTrue(BulbEvent(HouseTrigger.OFF_AND_ON, 0) in upTime)
    }

    /** Samsung trace 2026-10-18: Bedside RebootCount 1 -> 3 at 23:27 with no missed poll; the night rule must skip it, visibly. */
    @Test fun bedsideFlickIsSeenAndOnlyTheNightRuleTakesIt() {
        val seen = step(BulbWatch(), BulbBoot(1, 26_768), 0).first
        val (_, events) = step(seen, BulbBoot(3, 16), 9_000)
        assertEquals(BulbEvent(HouseTrigger.OFF_AND_ON, 0), BulbWatchPolicy.shown(events))
        val bedside = parse(script).rules.first()
        fun takes(minute: Int) = events.any { HouseScript.matches(bedside, it) && bedside.window?.contains(minute) != false }
        assertTrue(takes(23 * 60 + 27))
        assertTrue(takes(30))
        assertFalse(takes(22 * 60 + 59))
        assertFalse(takes(7 * 60))
        assertNull(BulbWatchPolicy.shown(emptyList()))
    }

    /** Samsung trace 2026-10-18: a Matter handshake sent to the dark bedside hung 11 s and delayed its return. */
    @Test fun aDarkLightIsPingedNotAskedOverMatter() {
        val up = BulbWatch(BulbBoot(1, 100))
        val dark = up.copy(offlineSince = 0)
        // A trusted ping is sent every tick, lit or dark.
        assertEquals(BulbCheck.PING, BulbWatchPolicy.check(1_500, 1_000, pingable = true))
        // Lit: the first silent ping starts the outage at once (no 3 s Matter wait first);
        // answers wait for the 6 s Matter turn that catches flicks too short for a ping.
        assertEquals(BulbStep.SILENT, BulbWatchPolicy.afterPing(up, 1_500, 1_000, answered = false))
        assertEquals(BulbStep.NOTHING, BulbWatchPolicy.afterPing(up, 1_500, 1_000, answered = true))
        assertEquals(BulbStep.MATTER, BulbWatchPolicy.afterPing(up, 7_000, 1_000, answered = true))
        assertEquals(BulbStep.MATTER, BulbWatchPolicy.afterPing(up, 1_500, null, answered = true))
        // Dark: never a Matter handshake into the dark; the first answer asks at once.
        assertEquals(BulbStep.SILENT, BulbWatchPolicy.afterPing(dark, 60_000, 1_000, answered = false))
        assertEquals(BulbStep.MATTER, BulbWatchPolicy.afterPing(dark, 1_500, 1_000, answered = true))
        // No trusted ping for this light: the old 6 s Matter asks stay.
        assertEquals(BulbCheck.WAIT, BulbWatchPolicy.check(1_500, 1_000, pingable = false))
        assertEquals(BulbCheck.MATTER, BulbWatchPolicy.check(7_000, 1_000, pingable = false))
        assertEquals(BulbCheck.MATTER, BulbWatchPolicy.check(0, null, pingable = false))
    }

    /** Top by the door: plain `turned off` is 2 s of silence, counted from the first missed ping. */
    @Test fun plainTurnedOffFiresTwoSecondsAfterTheFirstMissedPing() {
        val rule = parse("when top turned off\n  all 0%\n  music off\nwhen top turned on\n  all on\n  music on").rules.first()
        assertEquals(2, rule.seconds)
        var watch = BulbWatch(BulbBoot(16, 500))
        val fired = mutableListOf<Pair<Long, BulbEvent>>()
        (0L..3_000L step 250).forEach { now ->
            val (next, events) = step(watch, null, now, off = setOf(2_000))
            watch = next; events.forEach { fired += now to it }
        }
        assertEquals(listOf(2_000L to BulbEvent(HouseTrigger.TURNED_OFF, 2_000)), fired)
        assertTrue(HouseScript.matches(rule, fired.single().second))
    }

    /** Samsung trace 2026-10-18 23:58: Bedside's routine ask queued in front of Top's rule while Top was dark. */
    @Test fun nothingQueuesInFrontOfAShortTurnedOff() {
        val lit = BulbWatch(BulbBoot(8, 400))
        assertEquals(BulbStep.MATTER, BulbWatchPolicy.afterPing(lit, 7_000, 1_000, answered = true))
        assertEquals(BulbStep.NOTHING, BulbWatchPolicy.afterPing(lit, 7_000, 1_000, answered = true, othersDark = true))
        // A light never asked yet is still asked, so its watch can start.
        assertEquals(BulbStep.MATTER, BulbWatchPolicy.afterPing(lit, 7_000, null, answered = true, othersDark = true))
        // The lost session is retired just after a short hold has fired, at once otherwise.
        assertEquals(2_250L, BulbWatchPolicy.retireAfter(setOf(2_000, 60_000)))
        assertEquals(0L, BulbWatchPolicy.retireAfter(setOf(60_000)))
        assertEquals(0L, BulbWatchPolicy.retireAfter(emptySet()))
    }

    /** Top by the door stays dark at its switch; coming home turns on everything but it. */
    @Test fun startsLinesAndAllExcept() {
        val parsed = parse("""
            # Top light by the door
            top starts off
            when top turned off
              all 0%
              music off
            when top turned on
              all on except top Living
              music on
            "Desk Lamp" starts as before
        """.trimIndent())
        assertNull(parsed.error)
        assertEquals(listOf(HouseStart(2, "top", 0), HouseStart(9, "Desk Lamp", null)), parsed.starts)
        assertEquals(HouseStep.Light(null, LightChange(on = true), listOf("top", "Living")), parsed.rules[1].steps.first())
        assertEquals(2, parsed.rules.size)
        assertNull(HouseScript.unknownName(parsed, names))
        assertEquals(1, parse("top starts on").starts.single().value)
        assertEquals(3, error("when top turned on\n all on\ntop starts dim").line)
        assertEquals(2, error("top starts off\ntop starts on").line)
        assertEquals(2, error("when top turned on\n all on except").line)
        assertEquals("Line 1: No light called \"Hall\".", HouseScript.unknownName(parse("Hall starts off"), names)?.message)
        assertEquals("Line 2: No light or group called \"Hall\".",
            HouseScript.unknownName(parse("when top turned on\n all on except Hall"), names)?.message)
        // StartUpOnOff TLV: enum8 0 for off, Matter null for as before.
        assertEquals(0, matter.tlv.TlvReader(startUpTlv(0)).getUByte(AnonymousTag).toInt())
        assertTrue(matter.tlv.TlvReader(startUpTlv(null)).isNull())
    }

    @Test fun leavingFiresEachHoldOnceThenComingBackTurnsOn() {
        var watch = step(BulbWatch(), BulbBoot(1, 50), 0).first
        val fired = mutableListOf<BulbEvent>()
        listOf(6_000L, 12_000, 36_000, 66_000, 72_000).forEach { now ->
            val (next, events) = step(watch, null, now, off = setOf(30_000, 60_000))
            watch = next; fired += events
        }
        assertEquals(listOf(BulbEvent(HouseTrigger.TURNED_OFF, 30_000), BulbEvent(HouseTrigger.TURNED_OFF, 60_000)), fired)
        val (back, events) = step(watch, BulbBoot(2, 2), 600_000)
        assertEquals(listOf(BulbEvent(HouseTrigger.TURNED_ON, 0), BulbEvent(HouseTrigger.OFF_AND_ON, 594_000)), events)
        // Back and still on five minutes later, once.
        val (later, held) = step(back, BulbBoot(2, 300), 900_000, on = setOf(300_000))
        assertEquals(listOf(BulbEvent(HouseTrigger.TURNED_ON, 300_000)), held)
        assertTrue(step(later, BulbBoot(2, 306), 906_000, on = setOf(300_000)).second.isEmpty())
    }

    @Test fun aWifiBlipIsNotASwitch() {
        val up = BulbWatch(BulbBoot(3, 100), upSince = 0)
        val (missed, first) = step(up, null, 20_000, off = setOf(30_000))
        assertTrue(first.isEmpty())
        val (back, events) = step(missed, BulbBoot(3, 126), 26_000, off = setOf(30_000))
        assertTrue(events.isEmpty())
        assertEquals(0L, back.upSince)
        // Samsung's own Wi-Fi drop restarts the outage, so the hold does not fire on its return.
        val (gone, _) = step(back, null, 32_000, off = setOf(30_000))
        val (_, afterWifi) = step(BulbWatchPolicy.offWifi(gone), null, 70_000, off = setOf(30_000))
        assertTrue(afterWifi.isEmpty())
    }

    @Test fun onAndOffNeedsAConfirmedOutage() {
        val back = step(BulbWatch(BulbBoot(1, 500)), BulbBoot(2, 2), 0).first
        val (missed, none) = step(back, null, 20_000)
        assertTrue(none.isEmpty())
        val (_, events) = step(missed, null, 30_000)
        assertEquals(listOf(BulbEvent(HouseTrigger.ON_AND_OFF, 20_000)), events)
        // A light on since before the watch started has no known on-time: only turned off.
        val (_, start) = step(step(BulbWatch(BulbBoot(1, 500)), null, 0).first, null, 30_000)
        assertTrue(start.none { it.trigger == HouseTrigger.ON_AND_OFF })
    }

    @Test fun bootReadsGeneralDiagnosticsTlv() {
        assertEquals(BulbBoot(7, 1234), bulbBoot(TlvWriter().put(AnonymousTag, 7u.toUShort()).getEncoded(),
            TlvWriter().put(AnonymousTag, 1234uL).getEncoded()))
        assertEquals(BulbBoot(-1, null), bulbBoot(null, byteArrayOf(1)))
    }

    @Test fun schedulesNowKeepsOnlyTheLatestRuleForEachLight() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 10, 19, 21, 0, 0, 0, zone) // Monday
        val all = LightsRule("all", (1..7).toSet(), 18 * 60, LightChange(on = true, level = 80, fadeSeconds = 60))
        val desk = LightsRule("desk", (1..7).toSet(), 20 * 60, LightChange(on = false), bulbs = listOf(1))
        val later = LightsRule("later", (1..7).toSet(), 22 * 60, LightChange(on = false))
        val snapshot = LightsSnapshot(listOf(Bulb(1, "Desk"), Bulb(2, "Floor")),
            LightsAutomation(rules = listOf(later, desk, all)))
        assertEquals(listOf("all", "desk"), LightExperience.latest(snapshot, now).map(LightsRule::id))
        assertEquals(0, LightExperience.latest(snapshot, now).first().change.fadeSeconds)
        // Re-enabled just now (`schedules on`): its earlier time today still says where lights belong.
        val reenabled = snapshot.copy(automation = LightsAutomation(rules = listOf(later, desk,
            all.copy(activeAfter = now.toInstant().toEpochMilli()))))
        assertEquals(listOf("all", "desk"), LightExperience.latest(reenabled, now).map(LightsRule::id))
        assertTrue(LightExperience.latest(snapshot.copy(automation = LightsAutomation(rules =
            listOf(all.copy(enabled = false)))), now).isEmpty())
        // Yesterday's 22:00 all-light rule is older than today's 18:00 one, so it is not repeated.
        val night = now.withHour(1)
        assertEquals(listOf("later"), LightExperience.latest(snapshot, night).map(LightsRule::id))
    }
}
