package com.example.smartalarm.core.interaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClockAlarmListParserTest {
    private fun raw(
        result: Int = 1,
        ids: List<Long>? = listOf(11L, 12L),
        hours: List<Int>? = listOf(7, 23),
        minutes: List<Int>? = listOf(30, 5),
        labels: List<String?>? = listOf("相位·07:30", null),
        states: List<Boolean>? = listOf(true, false),
        repeatTexts: List<String?>? = listOf("响一次", "周一至周五"),
        repeatBits: List<Int>? = listOf(0, 31),
        nextTimes: List<Long>? = listOf(1_000L, 2_000L),
    ) = RawClockAlarmList(result, ids, hours, minutes, labels, states, repeatTexts, repeatBits, nextTimes)

    @Test
    fun `complete arrays become records in order`() {
        val read = parseClockAlarmList(raw())
        val records = assertIs<ClockAlarmListRead.Records>(read).alarms
        assertEquals(listOf(11L, 12L), records.map { it.id })
        assertEquals(7 to 30, records[0].hour to records[0].minute)
        assertTrue(records[0].enabled)
        assertEquals("响一次", records[0].repeatText)
        assertEquals(false, records[1].enabled)
        assertEquals(null, records[1].label)
        assertEquals(2_000L, records[1].nextTriggerMillis)
    }

    @Test
    fun `no arrays at all is unverified emptiness not a confirmed empty list`() {
        val read = parseClockAlarmList(raw(ids = null, hours = null, minutes = null, labels = null,
            states = null, repeatTexts = null, repeatBits = null, nextTimes = null))
        assertEquals(ClockAlarmListRead.EmptyUnverified, read)
    }

    @Test
    fun `zero length ids array is also unverified emptiness`() {
        val read = parseClockAlarmList(raw(ids = emptyList(), hours = emptyList(), minutes = emptyList(),
            labels = emptyList(), states = emptyList(), repeatTexts = emptyList(), repeatBits = emptyList(),
            nextTimes = emptyList()))
        assertEquals(ClockAlarmListRead.EmptyUnverified, read)
    }

    @Test
    fun `result other than one is rejected even if arrays exist`() {
        assertEquals(ClockAlarmListRead.Rejected(-1), parseClockAlarmList(raw(result = -1)))
    }

    @Test
    fun `ids present but another array missing is malformed`() {
        assertIs<ClockAlarmListRead.Malformed>(parseClockAlarmList(raw(hours = null)))
        assertIs<ClockAlarmListRead.Malformed>(parseClockAlarmList(raw(states = null)))
        assertIs<ClockAlarmListRead.Malformed>(parseClockAlarmList(raw(nextTimes = null)))
    }

    @Test
    fun `arrays of different length are malformed`() {
        assertIs<ClockAlarmListRead.Malformed>(parseClockAlarmList(raw(minutes = listOf(30))))
    }

    @Test
    fun `out of range time is malformed`() {
        assertIs<ClockAlarmListRead.Malformed>(parseClockAlarmList(raw(hours = listOf(24, 1))))
        assertIs<ClockAlarmListRead.Malformed>(parseClockAlarmList(raw(minutes = listOf(60, 1))))
    }

    @Test
    fun `duplicate or negative ids are malformed`() {
        assertIs<ClockAlarmListRead.Malformed>(parseClockAlarmList(raw(ids = listOf(5L, 5L))))
        assertIs<ClockAlarmListRead.Malformed>(parseClockAlarmList(raw(ids = listOf(-1L, 2L))))
    }
}
