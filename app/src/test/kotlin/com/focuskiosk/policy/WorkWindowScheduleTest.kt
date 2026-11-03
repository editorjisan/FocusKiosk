package com.focuskiosk.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class WorkWindowScheduleTest {

    private val zone = ZoneId.of("Asia/Dhaka")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun dailyWindowOpensAndCloses() {
        val cfg = WorkWindowScheduler.Config(hour = 20, minute = 0, intervalDays = 1, durationMins = 60)
        val anchor = LocalDate.of(2026, 11, 3)
        val inside = WorkWindowScheduler.computeSchedule(cfg, anchor, "", ms(2026, 11, 3, 20, 30), zone)
        assertTrue(inside.isCurrentlyActive)
        assertEquals(ms(2026, 11, 3, 21, 0), inside.currentWindowEndMs)
        assertEquals(ms(2026, 11, 4, 20, 0), inside.nextWindowStartMs)

        val after = WorkWindowScheduler.computeSchedule(cfg, anchor, "", ms(2026, 11, 3, 21, 0), zone)
        assertFalse(after.isCurrentlyActive)
        assertEquals(ms(2026, 11, 4, 20, 0), after.nextWindowStartMs)
    }

    @Test
    fun everyThreeDaysFollowsAnchor() {
        val cfg = WorkWindowScheduler.Config(hour = 9, minute = 0, intervalDays = 3, durationMins = 120)
        val anchor = LocalDate.of(2026, 11, 3)
        val s = WorkWindowScheduler.computeSchedule(cfg, anchor, "", ms(2026, 11, 3, 12, 0), zone)
        assertFalse(s.isCurrentlyActive)
        assertEquals(ms(2026, 11, 6, 9, 0), s.nextWindowStartMs)
        val onDay = WorkWindowScheduler.computeSchedule(cfg, anchor, "", ms(2026, 11, 6, 10, 59), zone)
        assertTrue(onDay.isCurrentlyActive)
    }

    @Test
    fun windowCrossingMidnightStaysOpen() {
        val cfg = WorkWindowScheduler.Config(hour = 23, minute = 30, intervalDays = 1, durationMins = 90)
        val anchor = LocalDate.of(2026, 11, 3)
        val s = WorkWindowScheduler.computeSchedule(cfg, anchor, "", ms(2026, 11, 4, 0, 30), zone)
        assertTrue(s.isCurrentlyActive)
        assertEquals(ms(2026, 11, 4, 1, 0), s.currentWindowEndMs)
    }

    @Test
    fun finishedWindowDoesNotReopen() {
        val cfg = WorkWindowScheduler.Config(hour = 20, minute = 0, intervalDays = 1, durationMins = 60)
        val anchor = LocalDate.of(2026, 11, 3)
        val start = ms(2026, 11, 3, 20, 0)
        val s = WorkWindowScheduler.computeSchedule(cfg, anchor, "cycle_$start", ms(2026, 11, 3, 20, 10), zone)
        assertFalse(s.isCurrentlyActive)
        assertEquals(ms(2026, 11, 4, 20, 0), s.nextWindowStartMs)
    }
}
