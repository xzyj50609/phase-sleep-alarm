package com.example.smartalarm.core.schedule

import com.example.smartalarm.core.model.*
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun wakeOptions(input: SleepCalculatorInput, cycles: Int, center: LocalTime, sleepMinutes: Int): List<WakeOption> {
    require(input.latencyUncertaintyMinutes >= 0 && input.cycleUncertaintyMinutes >= 0)
    val latency = input.latencyUncertaintyMinutes.toDouble()
    val cycle = input.cycleUncertaintyMinutes.toDouble()
    val offset = minOf(sqrt(latency * latency + cycles * cycle * cycle).roundToInt(), input.cycleMinutes / 4)
    return listOf(
        WakeOption(center.minusMinutes(offset.toLong()), sleepMinutes - offset, WakeScenario.FAST, -offset),
        WakeOption(center, sleepMinutes, WakeScenario.EXPECTED, 0),
        WakeOption(center.plusMinutes(offset.toLong()), sleepMinutes + offset, WakeScenario.SLOW, offset),
    )
}
