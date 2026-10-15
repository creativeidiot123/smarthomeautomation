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
        ScheduleCodec.decode("""{"version":2,"slots":[]}""")
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
