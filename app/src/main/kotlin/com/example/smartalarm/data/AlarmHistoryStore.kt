package com.example.smartalarm.data

import android.content.Context
import com.example.smartalarm.core.interaction.isRecentAlarmRequest

/**
 * Tracks alarm times dispatched by this app.
 *
 * This is not a mirror of the system clock state: Android does not expose a
 * public API for listing every alarm owned by the user's clock app. The store
 * gives the bridge a durable hint for alarms previously requested by us, while
 * the bridge also checks AlarmManager's next alarm when it can open that alarm
 * directly.
 */
class AlarmHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun wasRequestedRecently(timeKey: String): Boolean {
        val requestedAt = prefs.all[timeKey] as? Long ?: return false
        return isRecentAlarmRequest(requestedAt, System.currentTimeMillis(), COOLDOWN_MILLIS)
    }

    /**
     * Returns whether this app has ever dispatched a request for [timeKey].
     *
     * The timestamp is intentionally not treated as an expiry. A one-time
     * system alarm can remain in the clock list after it rings (usually
     * disabled), so forgetting the key would make the next tap create another
     * duplicate. The UI still offers an explicit force-create escape hatch.
     */
    fun hasRequested(timeKey: String): Boolean = prefs.contains(timeKey)

    fun markRequested(timeKey: String) {
        prefs.edit().putLong(timeKey, System.currentTimeMillis()).apply()
    }

    companion object {
        private const val PREFS_NAME = "alarm_history"
        private const val COOLDOWN_MILLIS = 2_500L
    }
}
