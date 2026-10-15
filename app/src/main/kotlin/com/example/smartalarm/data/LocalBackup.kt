package com.example.smartalarm.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Explicit local export/import. No network, no storage permission, no system clock data. */
class LocalBackup(private val context: Context) {
    private val recovery = File(context.filesDir, "import-recovery.json")
    private val names = listOf("user_prefs", "sleep_log", "alarm_delivery", "alarm_operation", "alarm_inflight")

    fun export(): String {
        val data = JSONObject()
        for (name in names) {
            val values = JSONObject()
            context.getSharedPreferences(name, 0).all.forEach { (key, value) ->
                val type = when (value) {
                    is Int -> "int"
                    is Long -> "long"
                    is Boolean -> "boolean"
                    is String -> "string"
                    else -> error("不支持的设置类型")
                }
                values.put(key, JSONObject().put("type", type).put("value", value))
            }
            data.put(name, values)
        }
        val payload = data.toString()
        return JSONObject().put("format", "phase-local-backup").put("version", 1)
            .put("payload", payload).put("sha256", sha256(payload)).toString(2)
    }

    fun validate(raw: String): JSONObject {
        require(raw.toByteArray().size <= 2_000_000) { "备份文件过大" }
        val j = JSONObject(raw)
        require(j.getString("format") == "phase-local-backup" && j.getInt("version") == 1) { "不是支持的相位备份" }
        val payload = j.getString("payload")
        require(sha256(payload) == j.getString("sha256")) { "备份校验失败，文件可能不完整" }
        val data = JSONObject(payload)
        require(data.keys().asSequence().toSet() == names.toSet()) { "备份内容不完整" }
        val allowed = mapOf(
            "user_prefs" to setOf("last_mode", "last_delay", "cycle_minutes", "resettle_minutes", "punch_time_minutes", "punch_enabled", "class_target_minutes", "morning_target_minutes"),
            "sleep_log" to setOf("entries"), "alarm_delivery" to setOf("mode", "clock"),
            "alarm_operation" to setOf("current"),
            "alarm_inflight" to setOf("hour", "minute", "started", "ids", "identity", "operationId", "label"),
        )
        for (name in names) {
            val values = data.getJSONObject(name)
            require(values.keys().asSequence().all { it in allowed.getValue(name) }) { "备份包含未知设置" }
            for (key in values.keys()) {
                val value = values.getJSONObject(key)
                when (value.getString("type")) {
                    "int" -> require(value.get("value") is Number && value.getLong("value") in Int.MIN_VALUE..Int.MAX_VALUE)
                    "long" -> require(value.get("value") is Number)
                    "boolean" -> require(value.get("value") is Boolean)
                    "string" -> require(value.get("value") is String)
                    else -> error("未知备份字段类型")
                }
            }
        }
        fun item(group: String, key: String) = data.getJSONObject(group).optJSONObject(key)
        val prefs = data.getJSONObject("user_prefs")
        for (key in prefs.keys()) {
            val value = prefs.getJSONObject(key)
            when (key) {
                "last_mode" -> com.example.smartalarm.core.model.AlarmMode.valueOf(value.getString("value"))
                "punch_enabled" -> require(value.getString("type") == "boolean")
                else -> {
                    require(value.getString("type") == "int")
                    val range = when (key) {
                        "cycle_minutes" -> 80..120
                        "last_delay", "resettle_minutes" -> 0..120
                        else -> 0..1439
                    }
                    require(value.getInt("value") in range) { "睡眠参数越界" }
                }
            }
        }
        item("sleep_log", "entries")?.let {
            require(it.getString("type") == "string")
            val entries = JSONArray(it.getString("value"))
            require(entries.length() <= 500)
            for (i in 0 until entries.length()) {
                val entry = SleepLogStore(context).decode(JSONArray().put(entries.getJSONObject(i)).toString())
                require(entry != null && entry.sleepMinutes in 0..2880) { "历史记录不完整" }
            }
        }
        item("alarm_delivery", "mode")?.let { com.example.smartalarm.core.interaction.DeliveryMode.valueOf(it.getString("value")) }
        item("alarm_delivery", "clock")?.let {
            val clock = JSONObject(it.getString("value"))
            identityFromJson(clock.getJSONObject("identity")); clock.getString("label"); clock.getString("component")
        }
        // Preserve unresolved journals verbatim. They will block writes after restoration, never replay automatically.
        item("alarm_operation", "current")?.let {
            val op = JSONObject(it.getString("value"))
            identityFromJson(op.getJSONObject("identity"))
            com.example.smartalarm.core.interaction.OperationStage.valueOf(op.getString("stage"))
            require(op.getInt("hour") in 0..23 && op.getInt("minute") in 0..59)
            op.getString("id"); op.getLong("started"); java.time.ZoneId.of(op.getString("zone"))
        }
        return data
    }

    fun import(raw: String) {
        val data = validate(raw) // Nothing is changed until every field is checked.
        val original = export()
        File(context.filesDir, "before-last-import.json").writeText(original)
        recovery.outputStream().use { it.write(original.toByteArray()); it.fd.sync() }
        try {
            apply(data)
            check(recovery.delete()) { "导入完成但恢复标记无法清除" }
        } catch (t: Exception) {
            apply(validate(original))
            recovery.delete()
            throw t
        }
    }

    /** An interrupted multi-file import restores its pre-import snapshot on the next launch. */
    fun recoverInterruptedImport() {
        if (!recovery.exists()) return
        apply(validate(recovery.readText()))
        check(recovery.delete())
    }

    private fun apply(data: JSONObject) {
        for (name in names) {
            val edit = context.getSharedPreferences(name, 0).edit().clear()
            val values = data.getJSONObject(name)
            for (key in values.keys()) {
                val value = values.getJSONObject(key)
                when (value.getString("type")) {
                    "int" -> edit.putInt(key, value.getInt("value"))
                    "long" -> edit.putLong(key, value.getLong("value"))
                    "boolean" -> edit.putBoolean(key, value.getBoolean("value"))
                    "string" -> edit.putString(key, value.getString("value"))
                }
            }
            check(edit.commit()) { "无法恢复 $name" }
        }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
