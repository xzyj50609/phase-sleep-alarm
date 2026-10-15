package com.example.smartalarm.core.model

import java.time.LocalTime

/**
 * Analysis of a punch-interrupted night: how the fixed punch alarm cuts the
 * cycle chain, and whether shifting bedtime slightly would soften it.
 */
data class NightPlan(
    /** Estimated moment of falling asleep (bedTime + fallAsleepMinutes). */
    val sleepStart: LocalTime,
    val punchTime: LocalTime,
    /** Genuine sleep from sleepStart to the punch alarm. */
    val nightSleepMinutes: Int,
    /** 0-based index of the cycle the punch alarm lands in. */
    val punchCycleIndex: Int,
    /** Minutes into that cycle when the punch alarm fires. */
    val punchMinuteInCycle: Int,
    /** How rough the punch wake-up itself will feel. */
    val punchQuality: WakeQuality,
    /** Plain-language assessment of the punch wake-up. */
    val punchAdvice: String,
    /**
     * Suggested bedtime shift in minutes so the punch lands on a cycle
     * boundary (positive = go to bed later). Null when not worth adjusting.
     */
    val bedtimeShiftMinutes: Int? = null,
    /** Moment of falling back asleep for the re-sleep segment (回笼觉). */
    val resleepStart: LocalTime,
)
