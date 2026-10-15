package com.example.smartalarm.core.interaction

/**
 * 一次“读时钟闹钟”的可信结论。get_alarm_list 自己分不清“真没有”和“读失败/读一半”，
 * 所以必须拿另一路独立计数（同一过滤条件、直接查表，出错会抛异常）来对账。
 */
sealed interface ClockAlarmSnapshot {
    data class Complete(val alarms: List<ClockAlarmRecord>) : ClockAlarmSnapshot
    data object ConfirmedEmpty : ClockAlarmSnapshot
    data class Unknown(val reason: String) : ClockAlarmSnapshot
    data class Unsupported(val reason: String) : ClockAlarmSnapshot
}

fun classifyAlarmSnapshot(read: ClockAlarmListRead, independentCount: Int?): ClockAlarmSnapshot = when (read) {
    is ClockAlarmListRead.Malformed -> ClockAlarmSnapshot.Unsupported("返回结构不符: ${read.reason}")
    is ClockAlarmListRead.Rejected -> ClockAlarmSnapshot.Unknown("接口返回码 ${read.result}")
    ClockAlarmListRead.EmptyUnverified -> when (independentCount) {
        null -> ClockAlarmSnapshot.Unknown("接口返回空，独立计数失败")
        0 -> ClockAlarmSnapshot.ConfirmedEmpty
        else -> ClockAlarmSnapshot.Unknown("接口返回空，但独立计数为 $independentCount")
    }
    is ClockAlarmListRead.Records -> when {
        independentCount == null -> ClockAlarmSnapshot.Unknown("独立计数失败，无法确认 ${read.alarms.size} 条是否完整")
        independentCount == read.alarms.size -> ClockAlarmSnapshot.Complete(read.alarms)
        else -> ClockAlarmSnapshot.Unknown("接口返回 ${read.alarms.size} 条，独立计数 $independentCount 条")
    }
}
