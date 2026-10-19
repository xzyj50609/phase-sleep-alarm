package com.example.smartalarm.core.schedule

import com.example.smartalarm.core.model.*
import java.time.LocalTime
import kotlin.test.*

class RecommendationRegressionTest {
    @Test fun `every original output field equals frozen 1_3_1 across input matrix`() {
        val old = LegacySleepCalculator131()
        val current = SleepCalculator()
        var cases = 0
        for (mode in AlarmMode.entries) for (punch in listOf(null, LocalTime.of(8, 30)))
            for (bed in listOf("00:30", "01:17", "02:40", "12:33", "22:10", "23:58"))
                for (cycle in listOf(80, 95, 120)) for (latency in listOf(5, 20, 60)) {
                    val input = SleepCalculatorInput(LocalTime.parse(bed), latency, mode, cycleMinutes = cycle, punchTime = punch)
                    val result = current.calculate(input)
                    val originalFields = result.copy(
                        recommended = result.recommended.copy(wakeOptions = emptyList()),
                        alternatives = result.alternatives.map { it.copy(wakeOptions = emptyList()) },
                    )
                    assertEquals(old.calculate(input), originalFields, input.toString())
                    cases++
                }
        assertEquals(540, cases)
    }
}
