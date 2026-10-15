package com.example.smartalarm.core.interaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockAlarmPolicyTest {
    @Test fun `parse validates time and rejects ambiguous text`() {
        assertEquals(7 to 30, parseClockTime("07:30"))
        assertEquals(0 to 0, parseClockTime("0：00"))
        listOf("24:00", "12:60", "下午 7:30", "7:3", "", "07:30 明天").forEach { assertNull(parseClockTime(it), it) }
    }
    @Test fun `same time ignores old labels and prefers enabled`() {
        val rows = listOf(ClockAlarmRow(7, 30, "响一次 | 相位·正常·睡8h", false), ClockAlarmRow(7, 30, "响一次 | 手动创建", true))
        assertEquals(1, reusableAlarmIndex(rows, 7, 30))
    }
    @Test fun `disabled one shot can be reused but recurring cannot`() {
        val rows = listOf(ClockAlarmRow(7, 30, "每天", true), ClockAlarmRow(7, 30, "响一次", false))
        assertEquals(1, reusableAlarmIndex(rows, 7, 30))
        assertNull(reusableAlarmIndex(rows.take(1), 7, 30))
        assertNull(reusableAlarmIndex(rows, 7, 31))
    }
    @Test fun `unknown schedule is not one shot`() {
        listOf("", "明天", "工作日", "每天 | 响一次", "响一次，稍后提醒").forEach { assertFalse(ClockAlarmRow(7, 30, it, true).isOneShot) }
    }
    @Test fun `partial scan cannot create`() {
        val guard = AlarmScanGuard()
        assertFalse(guard.authorizeCreation(true))
        guard.observedPage(false)
        assertFalse(guard.authorizeCreation(true))
        guard.reachedTop()
        assertFalse(guard.authorizeCreation(false))
    }
    @Test fun `incompatible alarm blocks creation`() {
        val guard = AlarmScanGuard()
        guard.reachedTop(); guard.observedPage(true); guard.observedPage(false)
        assertFalse(guard.authorizeCreation(true))
    }
    @Test fun `complete absence authorizes exactly one create`() {
        val guard = AlarmScanGuard()
        guard.reachedTop(); guard.observedPage(false)
        assertTrue(guard.authorizeCreation(true))
        assertFalse(guard.authorizeCreation(true))
    }
    @Test fun `new scan after deletion is not blocked by history`() {
        repeat(2) {
            val guard = AlarmScanGuard()
            guard.reachedTop(); guard.observedPage(false)
            assertTrue(guard.authorizeCreation(true))
        }
    }
}
