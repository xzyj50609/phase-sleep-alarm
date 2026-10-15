package com.example.smartalarm.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * One planned-sleep record, with user-confirmed / machine-verified / legacy provenance.
 * `date` is the calendar date of the tap — for a 00:30 bedtime that is
 * already the morning of the night in question.
 */
data class SleepLogEntry(
    val createdAtMillis: Long,
    val date: LocalDate,
    val bedTime: LocalTime,
    val wakeTime: LocalTime,
    val modeName: String,
    val sleepMinutes: Int,
    val qualityName: String,
    val confirmationSource: String = "legacy",
    val operationId: String? = null,
)

/**
 * Local sleep diary persisted as a JSON array in SharedPreferences —
 * platform `org.json` only, no new dependencies. Capped so it can never
 * grow unbounded (~1.5 records/day → years of headroom).
 */
class SleepLogStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Append one entry, trimming the oldest beyond [MAX_ENTRIES]. */
    fun append(entry: SleepLogEntry) {
        val entries = loadAll().toMutableList()
        entries.add(0, entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.lastIndex)
        }
        prefs.edit().putString(KEY_ENTRIES, toJson(entries)).apply()
    }

    /** Idempotent across late callbacks and process death between log commit and UI update. */
    @Synchronized fun appendConfirmed(entry: SleepLogEntry, operationId: String, source: String) {
        val entries = loadAll()
        if (entries.any { it.operationId == operationId }) return
        val updated = listOf(entry.copy(confirmationSource = source, operationId = operationId)) + entries
        check(prefs.edit().putString(KEY_ENTRIES, toJson(updated.take(MAX_ENTRIES))).commit()) { "历史保存失败，请重开后核对" }
    }

    fun encode(entry: SleepLogEntry): String = toJson(listOf(entry))
    fun decode(raw: String): SleepLogEntry? = parseEntry(JSONArray(raw).optJSONObject(0))

    /** All entries, newest first. Corrupt records are skipped, not fatal. */
    fun loadAll(): List<SleepLogEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                parseEntry(array.optJSONObject(index))
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Sleep log unreadable, starting fresh", e)
            emptyList()
        }
    }

    private fun parseEntry(json: JSONObject?): SleepLogEntry? {
        if (json == null) return null
        return try {
            SleepLogEntry(
                createdAtMillis = json.getLong(FIELD_CREATED),
                date = LocalDate.parse(json.getString(FIELD_DATE)),
                bedTime = LocalTime.parse(json.getString(FIELD_BED)),
                wakeTime = LocalTime.parse(json.getString(FIELD_WAKE)),
                modeName = json.getString(FIELD_MODE),
                sleepMinutes = json.getInt(FIELD_SLEEP_MIN),
                qualityName = json.optString(FIELD_QUALITY, ""),
                confirmationSource = json.optString("confirmationSource", "legacy"),
                operationId = if (json.isNull("operationId")) null else json.optString("operationId").takeIf { it.isNotBlank() },
            )
        } catch (e: JSONException) {
            Log.w(TAG, "Skipping corrupt sleep log entry", e)
            null
        } catch (e: java.time.format.DateTimeParseException) {
            Log.w(TAG, "Skipping sleep log entry with bad date", e)
            null
        }
    }

    private fun toJson(entries: List<SleepLogEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put(FIELD_CREATED, entry.createdAtMillis)
                    .put(FIELD_DATE, entry.date.toString())
                    .put(FIELD_BED, entry.bedTime.toString())
                    .put(FIELD_WAKE, entry.wakeTime.toString())
                    .put(FIELD_MODE, entry.modeName)
                    .put(FIELD_SLEEP_MIN, entry.sleepMinutes)
                    .put(FIELD_QUALITY, entry.qualityName)
                    .put("confirmationSource", entry.confirmationSource)
                    .put("operationId", entry.operationId ?: JSONObject.NULL),
            )
        }
        return array.toString()
    }

    companion object {
        fun entryNow(
            bedTime: LocalTime,
            wakeTime: LocalTime,
            modeName: String,
            sleepMinutes: Int,
            qualityName: String,
        ): SleepLogEntry {
            val now = Instant.now()
            return SleepLogEntry(
                createdAtMillis = now.toEpochMilli(),
                date = now.atZone(ZoneId.systemDefault()).toLocalDate(),
                bedTime = bedTime,
                wakeTime = wakeTime,
                modeName = modeName,
                sleepMinutes = sleepMinutes,
                qualityName = qualityName,
            )
        }

        private const val TAG = "SleepLogStore"
        private const val PREFS_NAME = "sleep_log"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 500

        private const val FIELD_CREATED = "created"
        private const val FIELD_DATE = "date"
        private const val FIELD_BED = "bed"
        private const val FIELD_WAKE = "wake"
        private const val FIELD_MODE = "mode"
        private const val FIELD_SLEEP_MIN = "sleepMin"
        private const val FIELD_QUALITY = "quality"
    }
}
