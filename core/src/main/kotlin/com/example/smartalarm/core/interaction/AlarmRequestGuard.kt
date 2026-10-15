package com.example.smartalarm.core.interaction

fun isRecentAlarmRequest(
    requestedAtEpochMillis: Long,
    nowEpochMillis: Long,
    cooldownMillis: Long,
): Boolean {
    val elapsedMillis = nowEpochMillis - requestedAtEpochMillis
    return elapsedMillis in 0..cooldownMillis
}
