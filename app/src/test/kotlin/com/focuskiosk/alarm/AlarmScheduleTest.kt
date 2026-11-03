package com.focuskiosk.alarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AlarmScheduleTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = Calendar.getInstance().apply {
        clear(); set(y, mo - 1, d, h, mi, 0)
    }.timeInMillis

    @Test
    fun oneShotRingsTodayOrTomorrow() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka"))
        val alarm = AlarmItem(hour = 5, minute = 5)
        assertEquals(at(2026, 11, 4, 5, 5), ExtremeAlarmManager.nextTrigger(alarm, at(2026, 11, 3, 22, 0)))
        assertEquals(at(2026, 11, 4, 5, 5), ExtremeAlarmManager.nextTrigger(alarm, at(2026, 11, 4, 4, 0)))
    }

    @Test
    fun repeatingSkipsUnselectedDays() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka"))
        // 3 Nov 2026 is a Tuesday; weekends only → Saturday 7 Nov.
        val alarm = AlarmItem(hour = 7, minute = 30, days = setOf(Calendar.SATURDAY, Calendar.SUNDAY))
        assertEquals(at(2026, 11, 7, 7, 30), ExtremeAlarmManager.nextTrigger(alarm, at(2026, 11, 3, 8, 0)))
    }

    @Test
    fun legacyJsonWithoutDaysRepeatsDaily() {
        val item = AlarmItem.listFromJsonString("""[{"id":"a","hour":5,"minute":5,"label":"Wake up","isEnabled":true}]""").single()
        assertEquals(AlarmItem.EVERY_DAY, item.days)
        assertEquals("Every day", item.repeatSummary())
    }
}
