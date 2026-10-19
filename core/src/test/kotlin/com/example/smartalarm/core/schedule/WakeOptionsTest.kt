package com.example.smartalarm.core.schedule

import com.example.smartalarm.core.model.*
import java.time.LocalTime
import kotlin.test.*

class WakeOptionsTest {
    private val calculator = SleepCalculator()
    private fun input(cycle: Int = 95) = SleepCalculatorInput(
        bedTime = LocalTime.of(23, 40), mode = AlarmMode.NORMAL, punchTime = null, cycleMinutes = cycle,
    )

    @Test fun `default offsets are 13 15 17 19 20 22 with unchanged centers`() {
        val candidates = calculator.calculate(input()).alternatives
        assertEquals(listOf(13, 15, 17, 19, 20, 22), candidates.map { it.wakeOptions.last().offsetMinutes })
        candidates.forEach { candidate ->
            val options = candidate.wakeOptions
            assertEquals(listOf(WakeScenario.FAST, WakeScenario.EXPECTED, WakeScenario.SLOW), options.map { it.scenario })
            val offset = options.last().offsetMinutes
            assertEquals(listOf(-offset, 0, offset), options.map { it.offsetMinutes })
            assertEquals(candidate.wakeUpTime, options[1].time)
            assertEquals(candidate.sleepMinutes, options[1].sleepMinutes)
            options.forEach { option ->
                assertEquals(candidate.wakeUpTime.plusMinutes(option.offsetMinutes.toLong()), option.time)
                assertEquals(candidate.sleepMinutes + option.offsetMinutes, option.sleepMinutes)
            }
        }
    }

    @Test fun `quarter cycle cap and configurable uncertainties`() {
        assertEquals(20, calculator.calculate(input(80)).alternatives.last().wakeOptions.last().offsetMinutes)
        assertEquals(30, calculator.calculate(input(120).copy(latencyUncertaintyMinutes = 100, cycleUncertaintyMinutes = 100))
            .alternatives.first().wakeOptions.last().offsetMinutes)
        assertEquals(5, calculator.calculate(input().copy(latencyUncertaintyMinutes = 3, cycleUncertaintyMinutes = 4))
            .alternatives.first().wakeOptions.last().offsetMinutes)
        assertEquals(listOf(0, 0, 0), calculator.calculate(input().copy(latencyUncertaintyMinutes = 0, cycleUncertaintyMinutes = 0))
            .recommended.wakeOptions.map { it.offsetMinutes })
    }

    @Test fun `midnight wrap retains scenario order and exact minute`() {
        val one = calculator.calculate(input().copy(bedTime = LocalTime.of(22, 10))).alternatives.first()
        assertEquals(LocalTime.of(0, 5), one.wakeUpTime)
        assertEquals(listOf(LocalTime.of(23, 52), LocalTime.of(0, 5), LocalTime.of(0, 18)), one.wakeOptions.map { it.time })
    }

    @Test fun `resleep uses only its own cycles and fixed candidates stay fixed`() {
        val output = calculator.calculate(input().copy(mode = AlarmMode.CLASS, punchTime = LocalTime.of(8, 30)))
        output.alternatives.forEach { candidate ->
            when (candidate.label) {
                "回笼 1 个完整周期" -> assertEquals(13, candidate.wakeOptions.last().offsetMinutes)
                "回笼 2 个周期" -> assertEquals(15, candidate.wakeOptions.last().offsetMinutes)
                else -> assertTrue(candidate.wakeOptions.isEmpty(), candidate.label)
            }
        }
        val naps = calculator.calculate(input().copy(mode = AlarmMode.NAP)).alternatives
        assertTrue(naps.first().wakeOptions.isEmpty())
        assertEquals(listOf(13, 15), naps.drop(1).map { it.wakeOptions.last().offsetMinutes })
    }

    @Test fun `neighbor cycle windows never cross at any supported length`() {
        for (cycle in 80..120) {
            val candidates = calculator.calculate(input(cycle)).alternatives
            candidates.zipWithNext().forEach { (a, b) ->
                assertTrue(a.wakeOptions.last().sleepMinutes < b.wakeOptions.first().sleepMinutes)
            }
        }
    }

    @Test fun `expected preserves original seconds too rather than rounding`() {
        val bed = LocalTime.of(23, 47, 23, 123)
        val candidate = calculator.calculate(input().copy(bedTime = bed)).recommended
        assertEquals(bed.plusMinutes(20 + 5 * 95L), candidate.wakeUpTime)
        assertEquals(candidate.wakeUpTime, candidate.wakeOptions[1].time)
    }
}
