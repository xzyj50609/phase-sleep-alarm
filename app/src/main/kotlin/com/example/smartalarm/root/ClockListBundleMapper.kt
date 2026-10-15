package com.example.smartalarm.root

import android.os.Bundle
import com.example.smartalarm.core.interaction.RawClockAlarmList

/** get_alarm_list 响应里本 APP 用到的键（16.19.14 反编译）。类型不符时 Bundle 返回 null，由解析器判为缺失。 */
private object ListKeys {
    const val RESULT = "result"
    const val IDS = "alarm_id_list"
    const val HOURS = "alarm_hour_list"
    const val MINUTES = "alarm_min_list"
    const val LABELS = "alarm_label_list"
    const val STATES = "alarm_state_list"
    const val REPEAT_TEXTS = "alarm_repeat_list"
    const val REPEAT_BITS = "alarm_repeat_set_list"
    const val NEXT_TIMES = "alarm_time_list"
}

fun Bundle.toRawClockAlarmList(): RawClockAlarmList = RawClockAlarmList(
    result = getInt(ListKeys.RESULT, Int.MIN_VALUE),
    ids = getLongArray(ListKeys.IDS)?.toList(),
    hours = getIntArray(ListKeys.HOURS)?.toList(),
    minutes = getIntArray(ListKeys.MINUTES)?.toList(),
    labels = getStringArray(ListKeys.LABELS)?.toList(),
    states = getBooleanArray(ListKeys.STATES)?.toList(),
    repeatTexts = getStringArray(ListKeys.REPEAT_TEXTS)?.toList(),
    repeatBits = getIntArray(ListKeys.REPEAT_BITS)?.toList(),
    nextTimes = getLongArray(ListKeys.NEXT_TIMES)?.toList(),
)

/** 只描述响应里有哪些键、什么类型、多长，用于 S1 判断 schema 有没有变；不含取值。 */
@Suppress("DEPRECATION")
fun Bundle.describeShape(): String = keySet().sorted().joinToString("; ") { key ->
    val value = get(key)
    val type = value?.javaClass?.simpleName ?: "null"
    val length = when (value) {
        is LongArray -> "[${value.size}]"
        is IntArray -> "[${value.size}]"
        is BooleanArray -> "[${value.size}]"
        is Array<*> -> "[${value.size}]"
        else -> ""
    }
    "$key:$type$length"
}
