package com.example.smartalarm.core.interaction

data class ClockAlarmRow(val hour: Int, val minute: Int, val summary: String, val enabled: Boolean) {
    fun matches(hour: Int, minute: Int) = this.hour == hour && this.minute == minute
    val isOneShot: Boolean get() = summary.substringBefore('|').trim() == "响一次"
}

fun parseClockTime(text: String): Pair<Int, Int>? {
    val match = Regex("^(\\d{1,2})[:：](\\d{2})$").matchEntire(text.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour to minute else null
}

fun reusableAlarmIndex(rows: List<ClockAlarmRow>, hour: Int, minute: Int): Int? {
    val matches = rows.indices.filter { rows[it].matches(hour, minute) && rows[it].isOneShot }
    return matches.firstOrNull { rows[it].enabled } ?: matches.firstOrNull()
}

/** Historical requests are deliberately not evidence of existence or absence. */
class AlarmScanGuard {
    var startedAtTop = false
        private set
    var sawTarget = false
        private set
    private var pageCount = 0
    private var dispatched = false
    fun reachedTop() { startedAtTop = true }
    fun observedPage(targetPresent: Boolean) { pageCount++; sawTarget = sawTarget || targetPresent }
    fun authorizeCreation(reachedBottom: Boolean): Boolean {
        if (!startedAtTop || pageCount == 0 || sawTarget || !reachedBottom || dispatched) return false
        dispatched = true
        return true
    }
}
