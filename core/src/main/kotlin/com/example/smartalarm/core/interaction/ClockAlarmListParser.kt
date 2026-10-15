package com.example.smartalarm.core.interaction

enum class ClockRepeatKind { ONE_SHOT, REPEATING, UNKNOWN }

/** 系统时钟业务接口 get_alarm_list 返回的一条闹钟。字段含义以真机实测为准，这里只做忠实搬运。 */
data class ClockAlarmRecord(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String?,
    val enabled: Boolean,
    val repeatText: String?,
    val repeatBits: Int,
    val nextTriggerMillis: Long,
) {
    /** 一次性：文本为“响一次”且重复位为 0（“单休制”位也是 0 但文本不同，不算）。2026-10-01 真机实测一致。 */
    val repeatKind: ClockRepeatKind get() = when {
        repeatBits !in 0..127 -> ClockRepeatKind.UNKNOWN
        repeatBits != 0 -> ClockRepeatKind.REPEATING
        repeatText?.trim() == "响一次" -> ClockRepeatKind.ONE_SHOT
        repeatText?.trim() == "单休制（仅休周日）" -> ClockRepeatKind.REPEATING
        else -> ClockRepeatKind.UNKNOWN
    }
    val isOneShot: Boolean get() = repeatKind == ClockRepeatKind.ONE_SHOT
}

/** 接口返回的原始数组（缺失为 null）。由 app 层从 Bundle 搬来，保持本模块不依赖 Android。 */
data class RawClockAlarmList(
    val result: Int,
    val ids: List<Long>?,
    val hours: List<Int>?,
    val minutes: List<Int>?,
    val labels: List<String?>?,
    val states: List<Boolean>?,
    val repeatTexts: List<String?>?,
    val repeatBits: List<Int>?,
    val nextTimes: List<Long>?,
)

sealed interface ClockAlarmListRead {
    data class Records(val alarms: List<ClockAlarmRecord>) : ClockAlarmListRead

    /**
     * result=1 但没有任何数组。时钟在“确实没有闹钟”“查询抛异常”“只读了一半后异常”时都是这个样子，
     * 所以它不能当作“没有”，调用方需要另一路证据才能确认。
     */
    data object EmptyUnverified : ClockAlarmListRead

    data class Rejected(val result: Int) : ClockAlarmListRead

    data class Malformed(val reason: String) : ClockAlarmListRead
}

fun parseClockAlarmList(raw: RawClockAlarmList): ClockAlarmListRead {
    if (raw.result != 1) return ClockAlarmListRead.Rejected(raw.result)
    val ids = raw.ids
    if (ids == null || ids.isEmpty()) return ClockAlarmListRead.EmptyUnverified

    val size = ids.size
    val hours = raw.hours ?: return malformed("缺少 alarm_hour_list")
    val minutes = raw.minutes ?: return malformed("缺少 alarm_min_list")
    val labels = raw.labels ?: return malformed("缺少 alarm_label_list")
    val states = raw.states ?: return malformed("缺少 alarm_state_list")
    val repeatTexts = raw.repeatTexts ?: return malformed("缺少 alarm_repeat_list")
    val repeatBits = raw.repeatBits ?: return malformed("缺少 alarm_repeat_set_list")
    val nextTimes = raw.nextTimes ?: return malformed("缺少 alarm_time_list")

    val lengths = listOf(hours.size, minutes.size, labels.size, states.size, repeatTexts.size, repeatBits.size, nextTimes.size)
    if (lengths.any { it != size }) return malformed("数组长度不一致: ids=$size, 其余=$lengths")
    if (ids.any { it < 0 }) return malformed("出现负数编号")
    if (ids.toSet().size != size) return malformed("编号重复")
    if (hours.any { it !in 0..23 }) return malformed("小时越界")
    if (minutes.any { it !in 0..59 }) return malformed("分钟越界")

    return ClockAlarmListRead.Records(
        List(size) { i ->
            ClockAlarmRecord(ids[i], hours[i], minutes[i], labels[i], states[i], repeatTexts[i], repeatBits[i], nextTimes[i])
        },
    )
}

private fun malformed(reason: String) = ClockAlarmListRead.Malformed(reason)
