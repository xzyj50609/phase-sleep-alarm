package com.example.smartalarm.data

import android.content.Context
import com.example.smartalarm.bridge.ClockChoice
import com.example.smartalarm.core.interaction.DeliveryMode
import org.json.JSONObject

class AlarmDeliveryPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("alarm_delivery", Context.MODE_PRIVATE)
    var mode: DeliveryMode
        get() = runCatching { DeliveryMode.valueOf(prefs.getString("mode", "STANDARD")!!) }.getOrDefault(DeliveryMode.STANDARD)
        set(value) { check(prefs.edit().putString("mode", value.name).commit()) }
    var clock: ClockChoice?
        get() = prefs.getString("clock", null)?.let { raw ->
            runCatching {
                val j = JSONObject(raw)
                ClockChoice(j.getString("label"), identityFromJson(j.getJSONObject("identity")), j.getString("component"))
            }.getOrNull()
        }
        set(value) {
            val raw = value?.let { JSONObject().put("label", it.label).put("identity", it.identity.toJson())
                .put("component", it.component).toString() }
            check(prefs.edit().putString("clock", raw).commit())
        }
}
