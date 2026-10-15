package com.example.smartalarm.core.model

import java.time.LocalTime

data class SleepCalculatorInput(
    val bedTime: LocalTime,
    val fallAsleepMinutes: Int = 20,
    val mode: AlarmMode,
    /** Rough personal sleep-cycle length; varies 85-110 min between people. */
    val cycleMinutes: Int = 95,
    val shortNapMinutes: Int = 25,
    /**
     * 必醒时刻 — a fixed moment the sleeper must wake at mid-night (punch-in,
     * medication, an errand), after which they may re-sleep. Null = none set
     * tonight, the night runs uninterrupted. NORMAL and NAP modes ignore it.
     */
    val punchTime: LocalTime? = LocalTime.of(8, 30),
    /** Minutes from the must-wake moment back to being asleep again (回笼觉入睡). */
    val resettleMinutes: Int = 10,
    /** Latest comfortable rise time for class days (leave in time for 10:10 class). */
    val classTarget: LocalTime = LocalTime.of(9, 30),
    /** Preferred rise time for breakfast / no-class mornings. */
    val morningTarget: LocalTime = LocalTime.of(9, 0),
    /** Minimum hours of sleep the catch-up (爽睡) mode should secure. */
    val minCatchUpHours: Int = 9,
)
