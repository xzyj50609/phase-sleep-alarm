package com.example.smartalarm.core.schedule

import com.example.smartalarm.core.model.AlarmMode
import com.example.smartalarm.core.model.NightPlan
import com.example.smartalarm.core.model.RecommendationType
import com.example.smartalarm.core.model.SleepCalculatorInput
import com.example.smartalarm.core.model.SleepCalculatorOutput
import com.example.smartalarm.core.model.WakeQuality
import com.example.smartalarm.core.model.WakeUpRecommendation
import java.time.LocalTime
import kotlin.math.abs

/**
 * Two-segment sleep model.
 *
 * When a must-wake alarm (必醒时刻: punch-in, medication, an errand) is set,
 * the night is NOT one continuous cycle chain: that alarm cuts it into a
 * night segment plus a morning re-sleep (回笼觉). v9: the re-sleep is
 * cycle-first — full-cycle re-sleeps are the default recommendation; short
 * light naps stay available as alternatives, with the 31-74 min slow-wave
 * trap still labeled honestly:
 *
 *  - <= 30 min stays in light sleep → easy wake-up;
 *  - 31-74 min likely enters slow-wave sleep → heavy grogginess;
 *  - ~one full cycle surfaces naturally → easy but late.
 *
 * NORMAL mode ignores everything — pure "now + fall-asleep delay, then count
 * cycles one by one". Nights without the must-wake alarm use the same
 * classic full-cycle model.
 */
class SleepCalculator {

    fun calculate(input: SleepCalculatorInput): SleepCalculatorOutput {
        val sleepStart = input.bedTime.plusMinutes(input.fallAsleepMinutes.toLong())

        return when (input.mode) {
            AlarmMode.NAP -> napPlan(input, sleepStart)
            // 正常睡觉: nothing else matters, count cycles from sleep onset.
            AlarmMode.NORMAL -> continuousPlan(input, sleepStart)
            else -> {
                val punch = input.punchTime
                val minutesToPunch = punch?.let { minutesForward(sleepStart, it) }
                if (punch != null && minutesToPunch != null &&
                    minutesToPunch in MIN_NIGHT_BEFORE_PUNCH..MAX_NIGHT_BEFORE_PUNCH
                ) {
                    punchPlan(input, sleepStart, punch, minutesToPunch)
                } else {
                    continuousPlan(input, sleepStart)
                }
            }
        }
    }

    // ── Must-wake-interrupted night (两段式) ──

    private fun punchPlan(
        input: SleepCalculatorInput,
        sleepStart: LocalTime,
        punch: LocalTime,
        nightMinutes: Int,
    ): SleepCalculatorOutput {
        val cycle = input.cycleMinutes
        val punchCycleIndex = nightMinutes / cycle
        val punchMinuteInCycle = nightMinutes % cycle
        val punchQuality = punchWakeQuality(punchCycleIndex, punchMinuteInCycle, cycle)
        val resleepStart = punch.plusMinutes(input.resettleMinutes.toLong())

        val nightPlan = NightPlan(
            sleepStart = sleepStart,
            punchTime = punch,
            nightSleepMinutes = nightMinutes,
            punchCycleIndex = punchCycleIndex,
            punchMinuteInCycle = punchMinuteInCycle,
            punchQuality = punchQuality,
            punchAdvice = punchAdvice(punchQuality, punchCycleIndex, nightMinutes),
            bedtimeShiftMinutes = suggestBedtimeShift(punchQuality, punchMinuteInCycle, cycle),
            resleepStart = resleepStart,
        )

        val candidates = buildResleepCandidates(input, nightMinutes, punch, resleepStart)
            .distinctBy { it.wakeUpTime }
            .sortedBy { minutesForward(sleepStart, it.wakeUpTime) }

        val recommended = chooseResleepRecommendation(input, sleepStart, candidates)
        val explanation = buildPunchExplanation(input, sleepStart, nightPlan, recommended)

        return SleepCalculatorOutput(
            recommended = recommended,
            alternatives = candidates,
            explanation = explanation,
            nightPlan = nightPlan,
        )
    }

    /**
     * v9: cycle-first candidate set. Full-cycle re-sleeps are always present;
     * the 20-minute micro-nap is gone. The 30-minute light nap survives as
     * the "must get up soon" option, and mode targets are exposed honestly
     * (trap and all) only when they are at least a light-nap away.
     */
    private fun buildResleepCandidates(
        input: SleepCalculatorInput,
        nightMinutes: Int,
        punch: LocalTime,
        resleepStart: LocalTime,
    ): List<WakeUpRecommendation> {
        val cycle = input.cycleMinutes
        val punchQuality = punchWakeQuality(nightMinutes / cycle, nightMinutes % cycle, cycle)

        val candidates = mutableListOf(
            WakeUpRecommendation(
                wakeUpTime = punch,
                type = RecommendationType.RESLEEP,
                sleepMinutes = nightMinutes,
                quality = punchQuality,
                label = "必醒后直接起",
                reason = if (punchQuality == WakeQuality.EASY) {
                    "必醒点正落在浅睡边缘，直接起最干脆，清醒得快。"
                } else {
                    "被叫醒后会有点昏，起来用洗漱和光线把它冲掉，不再回笼。"
                },
                napMinutes = 0,
            ),
            resleepCandidate(
                input, nightMinutes, resleepStart, napMinutes = LIGHT_NAP_MAX_MINUTES,
                label = "轻回笼 $LIGHT_NAP_MAX_MINUTES 分钟",
                reason = "浅睡回笼的上限，适合一会儿还有事——再睡久就要滑进深睡了。",
            ),
            fullCycleResleep(input, nightMinutes, resleepStart, cycles = 1),
            fullCycleResleep(input, nightMinutes, resleepStart, cycles = 2),
        )

        when (input.mode) {
            AlarmMode.CLASS -> {
                targetCandidate(input, nightMinutes, resleepStart, input.classTarget)
                    ?.let { candidates += it }
            }
            AlarmMode.BREAKFAST -> {
                targetCandidate(input, nightMinutes, resleepStart, input.morningTarget)
                    ?.let { candidates += it }
            }
            // Cycle candidates above already serve catch-up; NORMAL and NAP
            // never reach punchPlan.
            AlarmMode.CATCH_UP, AlarmMode.NORMAL, AlarmMode.NAP -> Unit
        }

        return candidates
    }

    private fun resleepCandidate(
        input: SleepCalculatorInput,
        nightMinutes: Int,
        resleepStart: LocalTime,
        napMinutes: Int,
        label: String,
        reason: String,
    ): WakeUpRecommendation = WakeUpRecommendation(
        wakeUpTime = resleepStart.plusMinutes(napMinutes.toLong()),
        type = RecommendationType.RESLEEP,
        sleepMinutes = nightMinutes + napMinutes,
        quality = napQuality(napMinutes, input.cycleMinutes),
        label = label,
        reason = reason,
        napMinutes = napMinutes,
    )

    /**
     * Candidate that sleeps exactly until the mode target — honest about the
     * trap. Skipped when the target is less than a light nap away — that
     * close to "直接起", the dedicated light-nap candidate covers it better.
     */
    private fun targetCandidate(
        input: SleepCalculatorInput,
        nightMinutes: Int,
        resleepStart: LocalTime,
        target: LocalTime,
    ): WakeUpRecommendation? {
        val napMinutes = minutesForward(resleepStart, target)
        if (napMinutes !in LIGHT_NAP_MAX_MINUTES..MAX_TARGET_NAP_MINUTES) return null
        val quality = napQuality(napMinutes, input.cycleMinutes)
        return WakeUpRecommendation(
            wakeUpTime = target,
            type = RecommendationType.RESLEEP,
            sleepMinutes = nightMinutes + napMinutes,
            quality = quality,
            label = "睡满到 ${format(target)}",
            reason = when (quality) {
                WakeQuality.GROGGY ->
                    "回笼 $napMinutes 分钟正踩在 30-70 分钟的深睡陷阱区，" +
                        "多半要从深睡里被拽醒，起来昏沉——时间最多，但代价是难受。"
                WakeQuality.EASY -> "回笼 $napMinutes 分钟，仍在浅睡里，按目标时间起。"
                WakeQuality.MODERATE -> "回笼 $napMinutes 分钟，可能有些昏，起床后缓一会。"
            },
            napMinutes = napMinutes,
        )
    }

    private fun fullCycleResleep(
        input: SleepCalculatorInput,
        nightMinutes: Int,
        resleepStart: LocalTime,
        cycles: Int,
    ): WakeUpRecommendation {
        val napMinutes = input.cycleMinutes * cycles
        val wake = resleepStart.plusMinutes(napMinutes.toLong())
        return WakeUpRecommendation(
            wakeUpTime = wake,
            type = RecommendationType.RESLEEP,
            sleepMinutes = nightMinutes + napMinutes,
            quality = WakeQuality.EASY,
            label = if (cycles == 1) "回笼 1 个完整周期" else "回笼 $cycles 个周期",
            reason = "回笼按完整周期睡，自然浮回浅睡再醒，起得舒服——${format(wake)} 正式起。",
            napMinutes = napMinutes,
            wakeOptions = wakeOptions(input, cycles, wake, nightMinutes + napMinutes),
        )
    }

    private fun chooseResleepRecommendation(
        input: SleepCalculatorInput,
        sleepStart: LocalTime,
        candidates: List<WakeUpRecommendation>,
    ): WakeUpRecommendation {
        val easy = candidates.filter { it.quality == WakeQuality.EASY }
        return when (input.mode) {
            AlarmMode.CLASS -> {
                val latestSafe = easy
                    .filter { minutesForward(sleepStart, it.wakeUpTime) <= minutesForward(sleepStart, input.classTarget) }
                    .maxByOrNull { minutesForward(sleepStart, it.wakeUpTime) }
                latestSafe ?: candidates.first()
            }
            AlarmMode.BREAKFAST -> {
                val pool = easy.ifEmpty { candidates }
                pool.minByOrNull {
                    abs(
                        minutesForward(sleepStart, it.wakeUpTime) -
                            minutesForward(sleepStart, input.morningTarget),
                    )
                } ?: candidates.first()
            }
            AlarmMode.CATCH_UP -> {
                candidates.firstOrNull { it.sleepMinutes >= input.minCatchUpHours * 60 }
                    ?: candidates.maxByOrNull { it.sleepMinutes }
                    ?: candidates.first()
            }
            // NORMAL and NAP never reach punchPlan.
            AlarmMode.NORMAL, AlarmMode.NAP -> candidates.first()
        }
    }

    // ── Uninterrupted night (classic cycle model) ──

    private fun continuousPlan(
        input: SleepCalculatorInput,
        sleepStart: LocalTime,
    ): SleepCalculatorOutput {
        val candidates = (1..6).map { cycles ->
            val totalMin = input.cycleMinutes * cycles
            WakeUpRecommendation(
                wakeUpTime = sleepStart.plusMinutes(totalMin.toLong()),
                type = RecommendationType.FULL_CYCLE,
                sleepMinutes = totalMin,
                quality = WakeQuality.EASY,
                label = "$cycles 个周期 · ${formatDuration(totalMin)}",
                reason = cycleReason(cycles),
                cycles = cycles,
                wakeOptions = wakeOptions(input, cycles, sleepStart.plusMinutes(totalMin.toLong()), totalMin),
            )
        }

        val recommended = when (input.mode) {
            AlarmMode.CLASS -> closestTo(candidates, sleepStart, input.classTarget)
            AlarmMode.BREAKFAST -> closestTo(candidates, sleepStart, input.morningTarget)
            AlarmMode.CATCH_UP ->
                candidates.firstOrNull { it.sleepMinutes >= input.minCatchUpHours * 60 }
                    ?: candidates.last()
            // 正常睡觉: no target — land closest to a healthy full night.
            AlarmMode.NORMAL ->
                candidates.minByOrNull { abs(it.sleepMinutes - IDEAL_NIGHT_MINUTES) }
                    ?: candidates.last()
            AlarmMode.NAP -> candidates.first()
        }

        val explanation = if (input.mode == AlarmMode.NORMAL) {
            "正常睡觉：不设目标、不管必醒，${format(input.bedTime)} 上床、" +
                "约 ${format(sleepStart)} 睡着后按周期一个个数。" +
                "建议 ${format(recommended.wakeUpTime)} 在第 ${recommended.cycles} 个周期边缘醒" +
                "（睡 ${formatDuration(recommended.sleepMinutes)}，最接近 8 小时）。"
        } else {
            "今晚没有必醒时刻打断睡眠，按完整周期计算：" +
                "${format(input.bedTime)} 上床、约 ${format(sleepStart)} 睡着，" +
                "建议 ${format(recommended.wakeUpTime)} 在周期边缘醒来" +
                "（睡 ${formatDuration(recommended.sleepMinutes)}）。"
        }

        return SleepCalculatorOutput(
            recommended = recommended,
            alternatives = candidates,
            explanation = explanation,
        )
    }

    private fun cycleReason(cycles: Int): String = when (cycles) {
        1 -> "只够应急提神，别当正经觉睡。"
        2 -> "损失不小，明天中午记得补。"
        3 -> "最低健康线，能维持一天基本清醒。"
        4 -> "够用的一晚，多数人的日常区间。"
        5 -> "睡得扎实，起来状态应该不错。"
        else -> "睡到饱，适合彻底恢复。"
    }

    // ── Daytime nap ──

    /**
     * v9: cycle-first naps. One full cycle is the default; the 25-minute
     * short nap stays for tight schedules, two cycles for real deficits.
     */
    private fun napPlan(
        input: SleepCalculatorInput,
        sleepStart: LocalTime,
    ): SleepCalculatorOutput {
        val shortNap = WakeUpRecommendation(
            wakeUpTime = sleepStart.plusMinutes(input.shortNapMinutes.toLong()),
            type = RecommendationType.SHORT_NAP,
            sleepMinutes = input.shortNapMinutes,
            quality = WakeQuality.EASY,
            label = "短睡 ${input.shortNapMinutes} 分钟",
            reason = "赶时间用的快速充电，停在浅睡，醒来不昏。",
            napMinutes = input.shortNapMinutes,
        )
        val oneCycle = WakeUpRecommendation(
            wakeUpTime = sleepStart.plusMinutes(input.cycleMinutes.toLong()),
            type = RecommendationType.FULL_CYCLE,
            sleepMinutes = input.cycleMinutes,
            quality = WakeQuality.EASY,
            label = "1 个完整周期",
            reason = "睡完一整个周期自然浮回浅睡，小睡的正经睡法。",
            cycles = 1,
            wakeOptions = wakeOptions(input, 1, sleepStart.plusMinutes(input.cycleMinutes.toLong()), input.cycleMinutes),
        )
        val twoCycles = WakeUpRecommendation(
            wakeUpTime = sleepStart.plusMinutes(input.cycleMinutes * 2L),
            type = RecommendationType.FULL_CYCLE,
            sleepMinutes = input.cycleMinutes * 2,
            quality = WakeQuality.EASY,
            label = "2 个完整周期",
            reason = "缺觉狠补用：两个周期睡到位，注意别影响晚上入睡。",
            cycles = 2,
            wakeOptions = wakeOptions(input, 2, sleepStart.plusMinutes(input.cycleMinutes * 2L), input.cycleMinutes * 2),
        )
        val candidates = listOf(shortNap, oneCycle, twoCycles)
            .distinctBy { it.wakeUpTime }
            .sortedBy { minutesForward(sleepStart, it.wakeUpTime) }

        return SleepCalculatorOutput(
            recommended = oneCycle,
            alternatives = candidates,
            explanation = "小睡也按周期睡：${format(input.bedTime)} 躺下、约 ${format(sleepStart)} 睡着，" +
                "默认睡满 1 个周期再醒。赶时间选短睡；中间时长最容易睡昏，别选。",
        )
    }

    // ── Wake-quality heuristics ──

    /**
     * How waking at an arbitrary point of the cycle chain feels.
     * Early cycles are SWS-heavy; from the 5th cycle on, sleep is mostly
     * light/REM, so mid-cycle wake-ups soften to MODERATE.
     */
    private fun punchWakeQuality(cycleIndex: Int, minuteInCycle: Int, cycle: Int): WakeQuality {
        return when {
            minuteInCycle <= CYCLE_EDGE_MINUTES || minuteInCycle >= cycle - CYCLE_EDGE_MINUTES ->
                WakeQuality.EASY
            cycleIndex >= LIGHT_MORNING_CYCLE_INDEX -> WakeQuality.MODERATE
            else -> WakeQuality.GROGGY
        }
    }

    /** Nap-length rule: <=30 light, 31-74 slow-wave trap, near a full cycle easy. */
    private fun napQuality(napMinutes: Int, cycleMinutes: Int): WakeQuality = when {
        napMinutes <= LIGHT_NAP_MAX_MINUTES -> WakeQuality.EASY
        napMinutes <= SWS_TRAP_MAX_MINUTES -> WakeQuality.GROGGY
        abs(napMinutes - cycleMinutes) <= CYCLE_EDGE_MINUTES -> WakeQuality.EASY
        else -> WakeQuality.MODERATE
    }

    private fun punchAdvice(
        quality: WakeQuality,
        cycleIndex: Int,
        nightMinutes: Int,
    ): String {
        val slept = formatDuration(nightMinutes)
        val nth = cycleIndex + 1
        return when (quality) {
            WakeQuality.EASY ->
                "必醒时你已睡 $slept，正好在第 $nth 个周期的浅睡边缘——被叫醒不算痛苦。"
            WakeQuality.MODERATE ->
                "必醒落在第 $nth 个周期中段，好在后半夜深睡很少，被叫醒会有点昏但不至于难受。"
            WakeQuality.GROGGY ->
                "必醒正踩进第 $nth 个周期的深睡窗（入睡后第 $nightMinutes 分钟）——被叫醒会比较难受。"
        }
    }

    /**
     * Positive = go to bed later. Only proposed when the must-wake moment is
     * GROGGY and a small shift (<= 35 min) would land it on a cycle boundary.
     */
    private fun suggestBedtimeShift(
        quality: WakeQuality,
        minuteInCycle: Int,
        cycle: Int,
    ): Int? {
        if (quality != WakeQuality.GROGGY) return null
        val later = minuteInCycle
        val earlier = cycle - minuteInCycle
        return when {
            later in MIN_SHIFT_MINUTES..MAX_SHIFT_MINUTES -> later
            earlier in MIN_SHIFT_MINUTES..MAX_SHIFT_MINUTES -> -earlier
            else -> null
        }
    }

    private fun buildPunchExplanation(
        input: SleepCalculatorInput,
        sleepStart: LocalTime,
        plan: NightPlan,
        recommended: WakeUpRecommendation,
    ): String {
        val night = formatDuration(plan.nightSleepMinutes)
        val total = formatDuration(recommended.sleepMinutes)
        val rise = format(recommended.wakeUpTime)
        val base = "${format(input.bedTime)} 上床、约 ${format(sleepStart)} 睡着，" +
            "夜里睡 $night 到 ${format(plan.punchTime)} 必醒。"
        val tail = if ((recommended.napMinutes ?: 0) > 0) {
            "回笼后 $rise 正式起床，全晚实际睡 $total。"
        } else {
            "必醒后直接起床，全晚实际睡 $total。"
        }
        return base + tail
    }

    // ── Helpers ──

    private fun closestTo(
        candidates: List<WakeUpRecommendation>,
        sleepStart: LocalTime,
        target: LocalTime,
    ): WakeUpRecommendation {
        val targetRel = minutesForward(sleepStart, target)
        return candidates.minByOrNull {
            abs(minutesForward(sleepStart, it.wakeUpTime) - targetRel)
        } ?: candidates.first()
    }

    /** Forward distance in minutes from a to b, wrapping across midnight. */
    private fun minutesForward(a: LocalTime, b: LocalTime): Int {
        val diff = b.toSecondOfDay() - a.toSecondOfDay()
        return (((diff % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY) / 60
    }

    private fun format(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)

    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m 分钟"
            m == 0 -> "$h 小时"
            else -> "$h 小时 $m 分"
        }
    }

    companion object {
        /** Must-wake closer to sleep onset than this → model as uninterrupted. */
        private const val MIN_NIGHT_BEFORE_PUNCH = 60

        /** Must-wake further away than this → it isn't tonight's. */
        private const val MAX_NIGHT_BEFORE_PUNCH = 11 * 60

        /** Within this many minutes of a cycle boundary counts as light sleep. */
        private const val CYCLE_EDGE_MINUTES = 15

        /** From this 0-based cycle on, mid-cycle wake-ups are only MODERATE. */
        private const val LIGHT_MORNING_CYCLE_INDEX = 4

        /** Naps up to this length stay in light sleep. */
        private const val LIGHT_NAP_MAX_MINUTES = 30

        /** Naps up to this length risk waking from slow-wave sleep. */
        private const val SWS_TRAP_MAX_MINUTES = 74

        /** Longest re-sleep a mode target may ask for. */
        private const val MAX_TARGET_NAP_MINUTES = 150

        /** NORMAL mode aims the recommendation at a rough 8-hour night. */
        private const val IDEAL_NIGHT_MINUTES = 8 * 60

        private const val MIN_SHIFT_MINUTES = 10
        private const val MAX_SHIFT_MINUTES = 35

        private const val SECONDS_PER_DAY = 24 * 3600
    }
}
