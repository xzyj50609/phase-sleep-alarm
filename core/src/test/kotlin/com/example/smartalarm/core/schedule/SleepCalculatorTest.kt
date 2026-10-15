package com.example.smartalarm.core.schedule

import com.example.smartalarm.core.model.AlarmMode
import com.example.smartalarm.core.model.RecommendationType
import com.example.smartalarm.core.model.SleepCalculatorInput
import com.example.smartalarm.core.model.WakeQuality
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepCalculatorTest {

    private val calculator = SleepCalculator()

    // ── Must-wake-interrupted nights (两段式) ──

    @Test
    fun `class mode recommends latest light-sleep resleep before target`() {
        // 00:30 bed + 20 → asleep 00:50, night to 8:30 must-wake = 460 min
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(0, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.CLASS,
            ),
        )

        // resleep at 8:40; 30-min light nap → 9:10 beats sleeping until 9:30
        assertEquals(LocalTime.of(9, 10), output.recommended.wakeUpTime)
        assertEquals(WakeQuality.EASY, output.recommended.quality)
        assertEquals(30, output.recommended.napMinutes)
        assertEquals(RecommendationType.RESLEEP, output.recommended.type)
        assertEquals(460 + 30, output.recommended.sleepMinutes)
    }

    @Test
    fun `class mode exposes the target time as a groggy trap candidate`() {
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(0, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.CLASS,
            ),
        )

        val target = output.alternatives.first { it.wakeUpTime == LocalTime.of(9, 30) }
        // 8:40 → 9:30 is a 50-min nap: squarely in the slow-wave trap
        assertEquals(50, target.napMinutes)
        assertEquals(WakeQuality.GROGGY, target.quality)
    }

    @Test
    fun `breakfast mode recommends the light resleep closest to morning target`() {
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(0, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.BREAKFAST,
            ),
        )

        // v9: the 20-min micro nap is gone; 30-min light resleep is the
        // closest easy wake-up to the 9:00 target.
        assertEquals(LocalTime.of(9, 10), output.recommended.wakeUpTime)
        assertEquals(30, output.recommended.napMinutes)
        assertEquals(WakeQuality.EASY, output.recommended.quality)
    }

    @Test
    fun `resleep candidates always include full cycles and never micro naps`() {
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(0, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.BREAKFAST,
            ),
        )

        // resleep 8:40 + 95 = 10:15, + 190 = 11:50
        val oneCycle = output.alternatives.first { it.napMinutes == 95 }
        assertEquals(LocalTime.of(10, 15), oneCycle.wakeUpTime)
        assertEquals(WakeQuality.EASY, oneCycle.quality)
        assertTrue(output.alternatives.any { it.napMinutes == 190 })
        // Cycle-first v9: no candidate is a sub-30-minute re-sleep.
        assertTrue(output.alternatives.none { (it.napMinutes ?: 0) in 1..29 })
    }

    @Test
    fun `punch landing near cycle boundary is assessed easy`() {
        // asleep 00:50 → must-wake at minute 460 = cycle 5, minute 80 of 95 (edge)
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(0, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.CLASS,
            ),
        )

        val plan = assertNotNull(output.nightPlan)
        assertEquals(460, plan.nightSleepMinutes)
        assertEquals(WakeQuality.EASY, plan.punchQuality)
        assertNull(plan.bedtimeShiftMinutes)
    }

    @Test
    fun `punch mid-cycle in early cycles is groggy and suggests bedtime shift`() {
        // 03:05 bed + 20 → asleep 03:25, night = 305 min = cycle 4, minute 20
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(3, 5),
                fallAsleepMinutes = 20,
                mode = AlarmMode.BREAKFAST,
            ),
        )

        val plan = assertNotNull(output.nightPlan)
        assertEquals(WakeQuality.GROGGY, plan.punchQuality)
        assertEquals(20, plan.bedtimeShiftMinutes)
    }

    @Test
    fun `catch up mode with punch reaches nine hours via full-cycle resleep`() {
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(0, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.CATCH_UP,
            ),
        )

        // night 460 + one cycle 95 = 555 ≥ 540 min
        assertEquals(LocalTime.of(10, 15), output.recommended.wakeUpTime)
        assertEquals(95, output.recommended.napMinutes)
        assertTrue(output.recommended.sleepMinutes >= 9 * 60)
    }

    @Test
    fun `custom cycle length drives the resleep cycle candidates`() {
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(0, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.CATCH_UP,
                cycleMinutes = 100,
            ),
        )

        // resleep 8:40 + 100 = 10:20; night 460 + 100 = 560 ≥ 540
        assertEquals(LocalTime.of(10, 20), output.recommended.wakeUpTime)
        assertEquals(100, output.recommended.napMinutes)
    }

    @Test
    fun `candidates are deduplicated and sorted by wake time`() {
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(0, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.BREAKFAST,
            ),
        )

        val times = output.alternatives.map { it.wakeUpTime }
        assertEquals(times.distinct().size, times.size)
        assertTrue(output.alternatives.any { it == output.recommended })
    }

    // ── NORMAL mode: pure cycle counting ──

    @Test
    fun `normal mode ignores the must-wake alarm entirely`() {
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(0, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.NORMAL,
                punchTime = LocalTime.of(8, 30),
            ),
        )

        assertNull(output.nightPlan)
        assertTrue(output.alternatives.all { it.type == RecommendationType.FULL_CYCLE })
    }

    @Test
    fun `normal mode recommends the cycle count closest to eight hours`() {
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(0, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.NORMAL,
            ),
        )

        // 5 × 95 = 475 min is nearer 480 than 4 × 95 = 380 or 6 × 95 = 570
        assertEquals(5, output.recommended.cycles)
        assertEquals(LocalTime.of(8, 45), output.recommended.wakeUpTime)
    }

    @Test
    fun `normal mode honours a custom cycle length`() {
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(0, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.NORMAL,
                cycleMinutes = 100,
            ),
        )

        // 5 × 100 = 500 (|Δ8h| = 20) beats 4 × 100 = 400 (|Δ8h| = 80)
        assertEquals(5, output.recommended.cycles)
        assertEquals(LocalTime.of(9, 10), output.recommended.wakeUpTime)
    }

    // ── Uninterrupted nights ──

    @Test
    fun `friday without punch uses continuous cycles for catch up`() {
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(0, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.CATCH_UP,
                punchTime = null,
            ),
        )

        assertNull(output.nightPlan)
        assertEquals(6, output.recommended.cycles)
        assertEquals(LocalTime.of(10, 20), output.recommended.wakeUpTime)
        assertTrue(output.alternatives.all { it.type == RecommendationType.FULL_CYCLE })
    }

    @Test
    fun `sleep starting far from punch falls back to continuous cycles`() {
        // 13:00 bed → next 8:30 is 19+ hours away, not tonight's must-wake
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(13, 0),
                fallAsleepMinutes = 20,
                mode = AlarmMode.CLASS,
            ),
        )

        assertNull(output.nightPlan)
        assertTrue(output.alternatives.all { it.type == RecommendationType.FULL_CYCLE })
        assertEquals(6, output.alternatives.size)
    }

    @Test
    fun `custom fall asleep minutes shifts continuous recommendations`() {
        val fast = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(13, 0),
                fallAsleepMinutes = 10,
                mode = AlarmMode.NORMAL,
            ),
        )
        val slow = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(13, 0),
                fallAsleepMinutes = 40,
                mode = AlarmMode.NORMAL,
            ),
        )

        assertEquals(30, java.time.Duration.between(
            fast.alternatives[0].wakeUpTime, slow.alternatives[0].wakeUpTime,
        ).toMinutes().toInt())
    }

    // ── Naps ──

    @Test
    fun `nap mode recommends one full cycle by default`() {
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(14, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.NAP,
            ),
        )

        // asleep 14:50 + 95 = 16:25
        assertEquals(LocalTime.of(16, 25), output.recommended.wakeUpTime)
        assertEquals(1, output.recommended.cycles)
        assertEquals(WakeQuality.EASY, output.recommended.quality)
        assertEquals(3, output.alternatives.size)
    }

    @Test
    fun `nap mode keeps the short nap and two cycles as alternatives`() {
        val output = calculator.calculate(
            SleepCalculatorInput(
                bedTime = LocalTime.of(14, 30),
                fallAsleepMinutes = 20,
                mode = AlarmMode.NAP,
            ),
        )

        val shortNap = output.alternatives.first { it.type == RecommendationType.SHORT_NAP }
        assertEquals(LocalTime.of(15, 15), shortNap.wakeUpTime)
        val twoCycles = output.alternatives.first { it.cycles == 2 }
        assertEquals(LocalTime.of(18, 0), twoCycles.wakeUpTime)
    }
}
