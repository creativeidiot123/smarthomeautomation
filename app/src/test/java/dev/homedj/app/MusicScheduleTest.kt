package dev.homedj.app

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MusicScheduleTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val morning = ScheduleSlot("morning", 7, 10, 40, listOf("PLmorning"))
    private val evening = ScheduleSlot("evening", 18, 24, 70, listOf("PLevening", "PLchill"))
    private val slots = listOf(morning, evening)

    private fun at(hour: Int, minute: Int = 0, day: Int = 15) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone)
    private fun queue(slotKey: String? = null, hasItems: Boolean = true, playing: Boolean = false, ended: Boolean = false) =
        QueueState(slotKey, hasItems, playing, ended)

    @Test fun codecRoundTripsAndDropsOnlyTheInvalidSlot() {
        assertEquals(slots, ScheduleCodec.decode(ScheduleCodec.encode(slots)))
        val overlapping = morning.copy(id = "late", startHour = 9, endHour = 11)
        assertEquals(slots, ScheduleCodec.decode(ScheduleCodec.encode(slots + overlapping)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun codecRejectsUnknownVersion() {
        ScheduleCodec.decode("""{"version":99,"slots":[]}""")
    }

    @Test fun oldDailyScheduleKeepsItsHoursDaysVolumeAndPlaylists() {
        val old = """{"version":1,"slots":[{"id":"morning","start":7,"end":10,"volume":40,"playlists":["PLmorning"]}]}"""
        assertEquals(listOf(morning), ScheduleCodec.decode(old))
        assertEquals(listOf(morning), ScheduleCodec.decode(ScheduleCodec.encode(ScheduleCodec.decode(old))))
    }

    @Test fun weeklyMinuteScheduleRoundTripsWithoutBecomingDaily() {
        val weekly = morning.copy(days = setOf(1, 3, 5), startMinute = 15, endMinute = 45)
        assertEquals(listOf(weekly), ScheduleCodec.decode(ScheduleCodec.encode(listOf(weekly))))
        assertEquals("07:15–10:45", weekly.label)
        // Version 2 prevents an old build from silently ignoring weekdays and playing every day.
        assertEquals(2, org.json.JSONObject(ScheduleCodec.encode(listOf(weekly))).getInt("version"))
    }

    @Test fun corruptWeeklyFieldsDoNotTurnIntoAnEverydaySchedule() {
        val weekly = morning.copy(days = setOf(1))
        val encoded = org.json.JSONObject(ScheduleCodec.encode(listOf(weekly, evening)))
        encoded.getJSONArray("slots").getJSONObject(0).remove("days")
        assertEquals(listOf(evening), ScheduleCodec.decode(encoded.toString()))
    }

    @Test fun dailyWholeHourSchedulesRemainReadableByOlderBuilds() {
        val encoded = org.json.JSONObject(ScheduleCodec.encode(slots))
        assertEquals(1, encoded.getInt("version"))
        assertEquals(slots, ScheduleCodec.decode(encoded.toString()))
    }

    @Test fun overlapsAreRejectedOnlyOnSharedWeekdaysIncludingNonAdjacentSlots() {
        val monday = morning.copy(days = setOf(1))
        val tuesday = morning.copy(id = "tuesday", days = setOf(2), startMinute = 30)
        assertNull(SchedulePolicy.validate(listOf(monday, tuesday)))
        val mondayLater = morning.copy(id = "later", days = setOf(1), startHour = 9)
        assertEquals("Slots overlap", SchedulePolicy.validate(listOf(monday, tuesday, mondayLater)))
        assertNull(SchedulePolicy.validate(listOf(monday.copy(endHour = 8, endMinute = 15),
            mondayLater.copy(startHour = 8, startMinute = 15))))
    }

    @Test fun validationRejectsEmptyOrInvalidDaysAndMinuteBoundaries() {
        assertEquals("Choose valid days", SchedulePolicy.validate(listOf(morning.copy(days = emptySet()))))
        assertEquals("Choose valid days", SchedulePolicy.validate(listOf(morning.copy(days = setOf(0, 8)))))
        assertEquals("End time must be after start time",
            SchedulePolicy.validate(listOf(morning.copy(startMinute = 60))))
        assertEquals("End time must be after start time",
            SchedulePolicy.validate(listOf(evening.copy(endMinute = 1))))
        assertEquals("End time must be after start time",
            SchedulePolicy.validate(listOf(morning.copy(endHour = 7))))
        assertNull(SchedulePolicy.validate(listOf(morning.copy(endHour = 7, endMinute = 1))))
    }

    @Test fun selectedDaysAndExactMinutesControlStartsAndStops() {
        // 15 October 2026 is Thursday (ISO day 4).
        val thursday = morning.copy(days = setOf(4), startMinute = 15, endMinute = 45)
        val weekly = listOf(thursday)
        assertNull(SchedulePolicy.active(weekly, at(7, 14)))
        assertEquals(thursday, SchedulePolicy.active(weekly, at(7, 15))?.first)
        assertEquals(thursday, SchedulePolicy.active(weekly, at(10, 44))?.first)
        assertNull(SchedulePolicy.active(weekly, at(10, 45)))
        assertNull(SchedulePolicy.active(weekly, at(8, day = 16)))
        assertEquals(at(7, 15), SchedulePolicy.nextBoundary(weekly, at(7, 14)))
        assertEquals(at(10, 45), SchedulePolicy.nextBoundary(weekly, at(7, 15)))
        assertEquals(at(7, 15, day = 22), SchedulePolicy.nextBoundary(weekly, at(10, 45)))
    }

    @Test fun midnightEndIsArmedEvenWhenTheFollowingDayIsNotSelected() {
        val thursday = listOf(evening.copy(days = setOf(4)))
        assertEquals(at(0, day = 16), SchedulePolicy.nextBoundary(thursday, at(23, 59)))
        assertNull(SchedulePolicy.active(thursday, at(0, day = 16)))
        assertEquals(at(18, day = 22), SchedulePolicy.nextBoundary(thursday, at(0, day = 16)))
    }

    @Test fun nextBoundaryIsSortedAcrossDifferentSlotsAndWeekends() {
        val friday = morning.copy(days = setOf(5))
        val saturday = evening.copy(days = setOf(6), startMinute = 30)
        assertEquals(at(7, day = 16), SchedulePolicy.nextBoundary(listOf(saturday, friday), at(23)))
        assertEquals(at(18, 30, day = 17), SchedulePolicy.nextBoundary(listOf(friday, saturday), at(12, day = 16)))
    }

    @Test fun timeInputAcceptsMinutesAndMidnightButRejectsInvalidAndAmbiguousValues() {
        assertEquals(495, scheduleTimeMinutes("08:15"))
        assertEquals(495, scheduleTimeMinutes("8:15"))
        assertEquals(0, scheduleTimeMinutes("00:00"))
        assertEquals(1440, scheduleTimeMinutes("24:00", allowMidnight = true))
        listOf("24:00", "23:60", "-1:00", "8:5", "08", "", "08:15x").forEach {
            assertNull(it, scheduleTimeMinutes(it))
        }
        assertNull(scheduleTimeMinutes("24:01", allowMidnight = true))
        assertEquals("24:00", scheduleTimeText(1440))
        assertEquals("08:15", scheduleTimeText(495))
    }

    @Test fun validationRejectsBadSlots() {
        assertNull(SchedulePolicy.validate(slots))
        assertEquals("Slots overlap", SchedulePolicy.validate(slots + morning.copy(id = "x", startHour = 9, endHour = 12)))
        assertEquals("End time must be after start time", SchedulePolicy.validate(listOf(morning.copy(endHour = 7))))
        assertEquals("End time must be after start time", SchedulePolicy.validate(listOf(morning.copy(endHour = 25))))
        assertEquals("Choose at least one playlist", SchedulePolicy.validate(listOf(morning.copy(playlistIds = emptyList()))))
        assertEquals("Invalid playlist", SchedulePolicy.validate(listOf(morning.copy(playlistIds = listOf("PL/../x")))))
        assertEquals("Invalid volume", SchedulePolicy.validate(listOf(morning.copy(volume = 101))))
        assertEquals("Invalid slot", SchedulePolicy.validate(listOf(morning, evening.copy(id = "morning"))))
        // Back-to-back slots are fine: the end hour is exclusive.
        assertNull(SchedulePolicy.validate(slots + morning.copy(id = "noon", startHour = 10, endHour = 12)))
    }

    @Test fun activeSlotUsesInclusiveStartExclusiveEndAndADailyKey() {
        assertNull(SchedulePolicy.active(slots, at(6, 59)))
        assertEquals(morning, SchedulePolicy.active(slots, at(7))?.first)
        assertEquals(morning, SchedulePolicy.active(slots, at(9, 59))?.first)
        assertNull(SchedulePolicy.active(slots, at(10)))
        assertEquals(evening, SchedulePolicy.active(slots, at(23, 59))?.first)
        assertNotEquals(SchedulePolicy.active(slots, at(8, day = 15))?.second,
            SchedulePolicy.active(slots, at(8, day = 16))?.second)
    }

    @Test fun nextBoundaryIsTheNextStartOrEndHour() {
        assertEquals(at(7), SchedulePolicy.nextBoundary(slots, at(3)))
        assertEquals(at(10), SchedulePolicy.nextBoundary(slots, at(7)))
        assertEquals(at(18), SchedulePolicy.nextBoundary(slots, at(12, 30)))
        // An end of 24:00 is the next midnight.
        assertEquals(at(0, day = 16), SchedulePolicy.nextBoundary(slots, at(20)))
        assertNull(SchedulePolicy.nextBoundary(emptyList(), at(20)))
    }

    @Test fun nextBoundarySurvivesADaylightSavingGap() {
        val berlin = ZoneId.of("Europe/Berlin")
        val night = listOf(morning.copy(startHour = 2, endHour = 4))
        val before = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, berlin)
        // 02:00 does not exist that night; the alarm still lands on the shifted hour, after now.
        val next = SchedulePolicy.nextBoundary(night, before)
        assertNotNull(next)
        assertEquals(3, next?.hour)
    }

    @Test fun slotStartReplacesAnIdlePausedOrOtherSlotQueue() {
        val active = SchedulePolicy.active(slots, at(7))
        val start = ScheduleAction.Start(morning, requireNotNull(active).second)
        val boundary = ScheduleTrigger.BOUNDARY
        assertEquals(start, SchedulePolicy.decide(boundary, active, queue(hasItems = false), castActive = false))
        assertEquals(start, SchedulePolicy.decide(boundary, active, queue(playing = false), castActive = false))
        assertEquals(start, SchedulePolicy.decide(boundary, active, queue("evening@1", playing = true), castActive = false))
    }

    @Test fun personsPlayingQueueFinishesBeforeTheSlotTakesOver() {
        val active = SchedulePolicy.active(slots, at(7))
        assertEquals(ScheduleAction.None,
            SchedulePolicy.decide(ScheduleTrigger.BOUNDARY, active, queue(playing = true), castActive = false))
        assertEquals(ScheduleAction.Start(morning, requireNotNull(active).second),
            SchedulePolicy.decide(ScheduleTrigger.QUEUE_ENDED, active, queue(ended = true), castActive = false))
        // A queue someone started while the end was being handled is not replaced.
        assertEquals(ScheduleAction.None,
            SchedulePolicy.decide(ScheduleTrigger.QUEUE_ENDED, active, queue(playing = true), castActive = false))
    }

    @Test fun sameSlotTodayIsKeptButYesterdaysPausedSlotRestarts() {
        val today = SchedulePolicy.active(slots, at(7, day = 16))
        val yesterday = requireNotNull(SchedulePolicy.active(slots, at(9, day = 15))).second
        assertEquals(ScheduleAction.None, SchedulePolicy.decide(
            ScheduleTrigger.BOUNDARY, today, queue(requireNotNull(today).second), castActive = false))
        assertEquals(ScheduleAction.Start(morning, today.second), SchedulePolicy.decide(
            ScheduleTrigger.BOUNDARY, today, queue(yesterday), castActive = false))
    }

    @Test fun slotEndPausesOnlyScheduledMusic() {
        val boundary = ScheduleTrigger.BOUNDARY
        assertEquals(ScheduleAction.Pause, SchedulePolicy.decide(boundary, null, queue("morning@1", playing = true), false))
        assertEquals(ScheduleAction.None, SchedulePolicy.decide(boundary, null, queue(playing = true), false))
        assertEquals(ScheduleAction.None, SchedulePolicy.decide(ScheduleTrigger.QUEUE_ENDED, null, queue(ended = true), false))
    }

    @Test fun resumeAndLiveCastRules() {
        val active = SchedulePolicy.active(slots, at(8))
        assertEquals(ScheduleAction.NoSlot, SchedulePolicy.decide(ScheduleTrigger.RESUME, null, queue(), false))
        assertEquals(ScheduleAction.Start(morning, requireNotNull(active).second),
            SchedulePolicy.decide(ScheduleTrigger.RESUME, active, queue(playing = true), false))
        assertEquals(ScheduleAction.Busy, SchedulePolicy.decide(ScheduleTrigger.RESUME, active, queue(), true))
        assertEquals(ScheduleAction.None, SchedulePolicy.decide(ScheduleTrigger.BOUNDARY, active, queue(), true))
    }

    @Test fun playNextFollowsTheCurrentSongInShuffledOrder() {
        // Play order 2,0,3,1 with item 0 playing; a song inserted at index 1 shifts 1..3 up.
        assertArrayEquals(intArrayOf(3, 0, 1, 4, 2), SchedulePolicy.playNextOrder(intArrayOf(2, 0, 3, 1), 0, 1))
        assertArrayEquals(intArrayOf(0, 1), SchedulePolicy.playNextOrder(intArrayOf(0), 0, 1))
        // The current song last in play order: the new song plays right after it.
        assertArrayEquals(intArrayOf(0, 2, 1, 3, 4), SchedulePolicy.playNextOrder(intArrayOf(0, 2, 1, 3), 3, 4))
    }
}
