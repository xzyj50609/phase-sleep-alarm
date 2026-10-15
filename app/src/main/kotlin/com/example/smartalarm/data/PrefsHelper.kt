package com.example.smartalarm.data

import android.content.Context
import com.example.smartalarm.core.model.AlarmMode
import java.time.LocalTime

/**
 * Persists user preferences: last-used inputs plus the personal calibration
 * parameters of the sleep model (cycle length, punch time, targets).
 */
class PrefsHelper(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var lastMode: AlarmMode
        get() {
            val name = prefs.getString(KEY_MODE, null) ?: return AlarmMode.NORMAL
            return try { AlarmMode.valueOf(name) } catch (_: IllegalArgumentException) { AlarmMode.NORMAL }
        }
        set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    var lastDelay: Int
        get() = prefs.getInt(KEY_DELAY, 20)
        set(value) { prefs.edit().putInt(KEY_DELAY, value).apply() }

    /** Personal sleep-cycle length; calibratable, never hardcoded to 90. */
    var cycleMinutes: Int
        get() = prefs.getInt(KEY_CYCLE, 95)
        set(value) { prefs.edit().putInt(KEY_CYCLE, value).apply() }

    /** Minutes from the punch wake-up back to sleep (回笼觉入睡). */
    var resettleMinutes: Int
        get() = prefs.getInt(KEY_RESETTLE, 10)
        set(value) { prefs.edit().putInt(KEY_RESETTLE, value).apply() }

    /** 必醒时刻 — the fixed must-wake moment mid-night. */
    var punchTime: LocalTime
        get() = readTime(KEY_PUNCH, LocalTime.of(8, 30))
        set(value) { writeTime(KEY_PUNCH, value) }

    /** Whether the must-wake moment is active tonight. Opt-in, remembered. */
    var punchEnabled: Boolean
        get() = prefs.getBoolean(KEY_PUNCH_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_PUNCH_ENABLED, value).apply() }

    /** Latest comfortable rise time on class days. */
    var classTarget: LocalTime
        get() = readTime(KEY_CLASS_TARGET, LocalTime.of(9, 30))
        set(value) { writeTime(KEY_CLASS_TARGET, value) }

    /** Preferred rise time on ordinary mornings. */
    var morningTarget: LocalTime
        get() = readTime(KEY_MORNING_TARGET, LocalTime.of(9, 0))
        set(value) { writeTime(KEY_MORNING_TARGET, value) }

    private fun readTime(key: String, default: LocalTime): LocalTime {
        val minutes = prefs.getInt(key, -1)
        if (minutes !in 0 until 24 * 60) return default
        return LocalTime.of(minutes / 60, minutes % 60)
    }

    private fun writeTime(key: String, value: LocalTime) {
        prefs.edit().putInt(key, value.hour * 60 + value.minute).apply()
    }

    companion object {
        private const val PREFS_NAME = "user_prefs"
        private const val KEY_MODE = "last_mode"
        private const val KEY_DELAY = "last_delay"
        private const val KEY_CYCLE = "cycle_minutes"
        private const val KEY_RESETTLE = "resettle_minutes"
        private const val KEY_PUNCH = "punch_time_minutes"
        private const val KEY_PUNCH_ENABLED = "punch_enabled"
        private const val KEY_CLASS_TARGET = "class_target_minutes"
        private const val KEY_MORNING_TARGET = "morning_target_minutes"
    }
}
