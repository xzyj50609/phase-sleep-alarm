package com.example.smartalarm.ui.screen.history

import com.example.smartalarm.data.SleepLogEntry
import java.time.LocalDate
import java.time.LocalTime

/**
 * One day's aggregated sleep, chart-ready. The night figure is the LAST
 * alarm actually created for that date (re-created alarms supersede, never
 * double-count); daytime naps are summed separately so charts can stack them
 * the way Huawei stacks 零星小睡.
 */
data class DayStat(
    val date: LocalDate,
    val nightMinutes: Int,
    val napMinutes: Int,
    val bedTime: LocalTime?,
    val wakeTime: LocalTime?,
) {
    val totalMinutes: Int get() = nightMinutes + napMinutes
}

private const val NAP_MODE_NAME = "NAP"

/** Collapse the raw log into one [DayStat] per date. */
fun dailyStats(entries: List<SleepLogEntry>): Map<LocalDate, DayStat> =
    entries.groupBy { it.date }.mapValues { (date, dayEntries) ->
        val night = dayEntries
            .filter { it.modeName != NAP_MODE_NAME }
            .maxByOrNull { it.createdAtMillis }
        val napSum = dayEntries
            .filter { it.modeName == NAP_MODE_NAME }
            .sumOf { it.sleepMinutes }
        DayStat(
            date = date,
            nightMinutes = night?.sleepMinutes ?: 0,
            napMinutes = napSum,
            bedTime = night?.bedTime,
            wakeTime = night?.wakeTime,
        )
    }

/**
 * Average bedtime around midnight. A naive mean of 23:50 and 00:10 is 12:00;
 * anchoring the day at 18:00 keeps night-owl times contiguous.
 */
fun averageBedTime(bedTimes: List<LocalTime>): LocalTime? {
    if (bedTimes.isEmpty()) return null
    val anchor = 18 * 60
    val avg = bedTimes
        .map { (it.hour * 60 + it.minute - anchor + 24 * 60) % (24 * 60) }
        .average()
        .toInt()
    val minutes = (avg + anchor) % (24 * 60)
    return LocalTime.of(minutes / 60, minutes % 60)
}
