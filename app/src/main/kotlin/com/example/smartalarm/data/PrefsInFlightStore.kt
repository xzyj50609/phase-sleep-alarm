package com.example.smartalarm.data

import android.content.Context
import com.example.smartalarm.core.interaction.InFlightCreate
import com.example.smartalarm.core.interaction.InFlightStore
import org.json.JSONObject

/**
 * 创建前先落盘的在途记录，进程被杀后重开仍在。只防“已发出、结果未知”的重复创建；
 * 原操作确认终态后即清除，不是“曾请求过”的永久历史，更不能用来证明系统里有没有闹钟。
 * 用 commit() 同步落盘：必须在创建请求发出之前确实写进去。
 */
class PrefsInFlightStore(context: Context) : InFlightStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): InFlightCreate? {
        val hour = prefs.getInt(KEY_HOUR, -1)
        val minute = prefs.getInt(KEY_MINUTE, -1)
        val started = prefs.getLong(KEY_STARTED, -1L)
        if (prefs.all.isEmpty()) return null
        check(hour in 0..23 && minute in 0..59 && started >= 0) { "未决创建记录损坏，请先核对时钟" }
        val ids = prefs.getString(KEY_IDS, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
        val identity = prefs.getString("identity", null)?.let { identityFromJson(JSONObject(it)) }
        return InFlightCreate(hour, minute, ids, started, identity,
            prefs.getString("operationId", null), prefs.getString("label", null))
    }

    override fun save(entry: InFlightCreate) {
        check(prefs.edit()
            .putInt(KEY_HOUR, entry.hour)
            .putInt(KEY_MINUTE, entry.minute)
            .putLong(KEY_STARTED, entry.startedAtMillis)
            .putString(KEY_IDS, entry.knownIdsBefore.joinToString(","))
            .putString("identity", entry.identity?.toJson()?.toString())
            .putString("operationId", entry.operationId)
            .putString("label", entry.label)
            .commit()) { "无法保存未决创建，未发出请求" }
    }

    override fun clear() {
        check(prefs.edit().clear().commit()) { "无法保存核对结果" }
    }

    private companion object {
        const val PREFS_NAME = "alarm_inflight"
        const val KEY_HOUR = "hour"
        const val KEY_MINUTE = "minute"
        const val KEY_STARTED = "started"
        const val KEY_IDS = "ids"
    }
}
