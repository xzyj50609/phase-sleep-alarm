package com.example.smartalarm.core.interaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClockAlarmSnapshotTest {
    private fun record(id: Long) = ClockAlarmRecord(id, 7, 30, null, false, "响一次", 0, 0L)
    private val two = ClockAlarmListRead.Records(listOf(record(1), record(2)))

    @Test
    fun `list length equal to independent count is complete`() {
        val snap = classifyAlarmSnapshot(two, independentCount = 2)
        assertEquals(listOf(1L, 2L), assertIs<ClockAlarmSnapshot.Complete>(snap).alarms.map { it.id })
    }

    @Test
    fun `list shorter than independent count is unknown not complete`() {
        assertIs<ClockAlarmSnapshot.Unknown>(classifyAlarmSnapshot(two, independentCount = 3))
    }

    @Test
    fun `list longer than independent count is unknown`() {
        assertIs<ClockAlarmSnapshot.Unknown>(classifyAlarmSnapshot(two, independentCount = 1))
    }

    @Test
    fun `empty list with independent zero is confirmed empty`() {
        assertEquals(ClockAlarmSnapshot.ConfirmedEmpty, classifyAlarmSnapshot(ClockAlarmListRead.EmptyUnverified, 0))
    }

    @Test
    fun `empty list while independent count is positive is unknown`() {
        assertIs<ClockAlarmSnapshot.Unknown>(classifyAlarmSnapshot(ClockAlarmListRead.EmptyUnverified, 5))
    }

    @Test
    fun `missing independent count is always unknown`() {
        assertIs<ClockAlarmSnapshot.Unknown>(classifyAlarmSnapshot(two, null))
        assertIs<ClockAlarmSnapshot.Unknown>(classifyAlarmSnapshot(ClockAlarmListRead.EmptyUnverified, null))
    }

    @Test
    fun `rejected result is unknown and malformed is unsupported`() {
        assertIs<ClockAlarmSnapshot.Unknown>(classifyAlarmSnapshot(ClockAlarmListRead.Rejected(-1), 2))
        assertIs<ClockAlarmSnapshot.Unsupported>(classifyAlarmSnapshot(ClockAlarmListRead.Malformed("缺少 x"), 2))
    }
}
