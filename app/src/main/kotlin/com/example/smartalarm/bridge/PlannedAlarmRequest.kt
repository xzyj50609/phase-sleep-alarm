package com.example.smartalarm.bridge

import com.example.smartalarm.core.model.AlarmMode
import com.example.smartalarm.core.model.WakeOption
import com.example.smartalarm.core.model.WakeUpRecommendation
import com.example.smartalarm.data.SleepLogStore
import com.example.smartalarm.ui.screen.home.chineseLabel
import java.time.LocalTime

/** The time sent to the clock and the planned history always come from the same selection. */
internal fun AlarmSetController.requestRecommendation(
    recommendation: WakeUpRecommendation, bedTime: LocalTime, mode: AlarmMode, option: WakeOption? = null,
) {
    val target = option?.time ?: recommendation.wakeUpTime
    val sleepMinutes = option?.sleepMinutes ?: recommendation.sleepMinutes
    request(target, buildAlarmMessage(target, mode.chineseLabel(), sleepMinutes), SleepLogStore.entryNow(
        bedTime = bedTime, wakeTime = target, modeName = mode.name,
        sleepMinutes = sleepMinutes, qualityName = recommendation.quality.name,
    ))
}
