package com.example.smartalarm.core.interaction

import java.time.*
import kotlin.test.*

class PreviousAlarmTest {
    private val target = LocalTime.of(6, 27)
    private val now = Instant.parse("2026-10-19T23:50:00Z")
    private val zone = ZoneId.of("UTC")
    private fun record(enabled: Boolean = true, hour: Int = 6, repeat: String = "响一次") =
        ClockAlarmRecord(1, hour, 27, null, enabled, repeat, 0, 0)

    @Test fun `root distinguishes enabled one shot absent disabled repeating and unreadable`() {
        assertEquals(PreviousAlarmStatus.ACTIVE, rootPreviousAlarmStatus(ClockAlarmSnapshot.Complete(listOf(record())), target))
        for (rows in listOf(emptyList(), listOf(record(false)), listOf(record(hour = 7)), listOf(record(repeat = "单休制（仅休周日）")))) {
            assertEquals(PreviousAlarmStatus.INACTIVE, rootPreviousAlarmStatus(ClockAlarmSnapshot.Complete(rows), target))
        }
        assertEquals(PreviousAlarmStatus.INACTIVE, rootPreviousAlarmStatus(ClockAlarmSnapshot.ConfirmedEmpty, target))
        assertEquals(PreviousAlarmStatus.UNKNOWN, rootPreviousAlarmStatus(ClockAlarmSnapshot.Unknown("read failed"), target))
        assertEquals(PreviousAlarmStatus.UNKNOWN, rootPreviousAlarmStatus(ClockAlarmSnapshot.Unsupported("schema"), target))
        assertEquals(PreviousAlarmStatus.UNKNOWN, rootPreviousAlarmStatus(ClockAlarmSnapshot.Complete(listOf(record())), target, locked = true))
    }

    @Test fun `ordinary next alarm tolerance absence later and blocked by earlier alarm`() {
        val next = Instant.parse("2026-10-20T06:27:00Z").toEpochMilli()
        for (delta in listOf(-60_000L, 0L, 60_000L)) {
            assertEquals(PreviousAlarmStatus.ACTIVE, standardPreviousAlarmStatus(next + delta, target, now, zone))
        }
        assertEquals(PreviousAlarmStatus.INACTIVE, standardPreviousAlarmStatus(null, target, now, zone))
        assertEquals(PreviousAlarmStatus.INACTIVE, standardPreviousAlarmStatus(next + 60_001, target, now, zone))
        assertEquals(PreviousAlarmStatus.UNKNOWN, standardPreviousAlarmStatus(next - 60_001, target, now, zone))
        assertEquals(PreviousAlarmStatus.UNKNOWN, standardPreviousAlarmStatus(null, target, now, zone, locked = true))
    }

    @Test fun `next occurrence respects midnight local zone and daylight saving`() {
        val china = ZoneId.of("Asia/Shanghai")
        val time = Instant.parse("2026-10-19T23:50:00Z") // 07:50 local: target is tomorrow
        val next = Instant.parse("2026-10-20T22:27:00Z").toEpochMilli()
        assertEquals(PreviousAlarmStatus.ACTIVE, standardPreviousAlarmStatus(next, target, time, china))
        val la = ZoneId.of("America/Los_Angeles")
        val dstNow = ZonedDateTime.of(2026, 3, 7, 23, 0, 0, 0, la).toInstant()
        val dstTarget = LocalTime.of(6, 27).atDate(LocalDate.of(2026, 3, 8)).atZone(la).toInstant().toEpochMilli()
        assertEquals(PreviousAlarmStatus.ACTIVE, standardPreviousAlarmStatus(dstTarget, target, dstNow, la))
    }

    @Test fun `banner requires durable confirmed operation aged 15 minutes through 4 hours`() {
        val identity = AlarmIdentity("clock", "cert", 1, 0, DeliveryMode.STANDARD, "test")
        for (stage in OperationStage.entries) {
            val op = AlarmOperation("id", identity, 6, 27, "", 1000, "UTC", null, stage)
            assertEquals(stage.confirmed, shouldOfferRecalculation(op, 1000 + RECALCULATE_MIN_AGE_MILLIS))
            assertEquals(stage.confirmed, shouldOfferRecalculation(op, 1000 + RECALCULATE_MAX_AGE_MILLIS))
            for (age in listOf(-1L, RECALCULATE_MIN_AGE_MILLIS - 1, RECALCULATE_MAX_AGE_MILLIS + 1)) {
                assertFalse(shouldOfferRecalculation(op, 1000 + age))
            }
        }
        assertFalse(shouldOfferRecalculation(null, now.toEpochMilli()))
    }

    @Test fun `next occurrence includes the second fall back time and skips nonexistent wall time`() {
        val la = ZoneId.of("America/Los_Angeles")
        // 01:30 appears at both 08:30Z and 09:30Z. At 09:15Z the second one is still ahead.
        assertEquals(PreviousAlarmStatus.ACTIVE, standardPreviousAlarmStatus(
            Instant.parse("2026-11-01T09:30:00Z").toEpochMilli(), LocalTime.of(1, 30),
            Instant.parse("2026-11-01T09:15:00Z"), la))
        // 02:30 does not occur on the spring-forward date; the next actual 02:30 is tomorrow.
        assertEquals(PreviousAlarmStatus.ACTIVE, standardPreviousAlarmStatus(
            Instant.parse("2026-03-09T09:30:00Z").toEpochMilli(), LocalTime.of(2, 30),
            Instant.parse("2026-03-08T09:00:00Z"), la))
    }
}
