package com.example.smartalarm.core.interaction

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

enum class PreviousAlarmStatus { ACTIVE, INACTIVE, UNKNOWN }

const val RECALCULATE_MIN_AGE_MILLIS = 15 * 60_000L
const val RECALCULATE_MAX_AGE_MILLIS = 4 * 60 * 60_000L
private const val NEXT_ALARM_TOLERANCE_MILLIS = 60_000L

fun shouldOfferRecalculation(operation: AlarmOperation?, nowMillis: Long): Boolean =
    operation?.stage?.confirmed == true &&
        nowMillis - operation.startedAtMillis in RECALCULATE_MIN_AGE_MILLIS..RECALCULATE_MAX_AGE_MILLIS

fun rootPreviousAlarmStatus(snapshot: ClockAlarmSnapshot, target: LocalTime, locked: Boolean = false): PreviousAlarmStatus {
    if (locked) return PreviousAlarmStatus.UNKNOWN
    return when (snapshot) {
        is ClockAlarmSnapshot.Complete -> if (snapshot.alarms.any {
            it.hour == target.hour && it.minute == target.minute && it.enabled && it.isOneShot
        }) PreviousAlarmStatus.ACTIVE else PreviousAlarmStatus.INACTIVE
        ClockAlarmSnapshot.ConfirmedEmpty -> PreviousAlarmStatus.INACTIVE
        is ClockAlarmSnapshot.Unknown, is ClockAlarmSnapshot.Unsupported -> PreviousAlarmStatus.UNKNOWN
    }
}

fun standardPreviousAlarmStatus(
    nextTriggerMillis: Long?, target: LocalTime, now: Instant, zone: ZoneId, locked: Boolean = false,
): PreviousAlarmStatus {
    if (locked) return PreviousAlarmStatus.UNKNOWN
    if (nextTriggerMillis == null) return PreviousAlarmStatus.INACTIVE
    val date = now.atZone(zone).toLocalDate()
    val minuteTarget = target.withSecond(0).withNano(0)
    // A fall-back wall time has two valid offsets; a spring-forward gap has none.
    val occurrence = generateSequence(date) { it.plusDays(1) }.flatMap { day ->
        val local = minuteTarget.atDate(day)
        zone.rules.getValidOffsets(local).map { local.toInstant(it) }.sorted().asSequence()
    }.first { it.isAfter(now) }
    val delta = nextTriggerMillis - occurrence.toEpochMilli()
    return when {
        abs(delta) <= NEXT_ALARM_TOLERANCE_MILLIS -> PreviousAlarmStatus.ACTIVE
        delta > 0 -> PreviousAlarmStatus.INACTIVE
        else -> PreviousAlarmStatus.UNKNOWN // An earlier alarm hides the target.
    }
}
