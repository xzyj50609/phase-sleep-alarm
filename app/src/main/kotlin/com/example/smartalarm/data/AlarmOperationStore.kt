package com.example.smartalarm.data

import android.content.Context
import com.example.smartalarm.core.interaction.*
import org.json.JSONObject

internal fun AlarmIdentity.toJson() = JSONObject()
    .put("package", clockPackage).put("certificate", certificateSha256).put("version", versionCode)
    .put("user", androidUser).put("mode", mode.name).put("adapter", adapter)

internal fun identityFromJson(j: JSONObject) = AlarmIdentity(
    j.getString("package"), j.getString("certificate"), j.getLong("version"),
    j.getInt("user"), DeliveryMode.valueOf(j.getString("mode")), j.getString("adapter"),
)

class AlarmOperationStore(context: Context) : OperationStore {
    private val prefs = context.getSharedPreferences("alarm_operation", Context.MODE_PRIVATE)
    override fun load(): AlarmOperation? {
        val raw = prefs.getString("current", null) ?: return null
        // Corruption must block writes, not silently forget a potentially issued request.
        val j = JSONObject(raw)
        return AlarmOperation(j.getString("id"), identityFromJson(j.getJSONObject("identity")),
            j.getInt("hour"), j.getInt("minute"), j.getString("label"), j.getLong("started"),
            j.getString("zone"), if (j.isNull("log")) null else j.getString("log"),
            OperationStage.valueOf(j.getString("stage")), j.optBoolean("timedOut"))
    }
    override fun save(operation: AlarmOperation) {
        val j = JSONObject().put("id", operation.id).put("identity", operation.identity.toJson())
            .put("hour", operation.hour).put("minute", operation.minute).put("label", operation.label)
            .put("started", operation.startedAtMillis).put("zone", operation.zoneId)
            .put("log", operation.logPayload ?: JSONObject.NULL).put("stage", operation.stage.name)
            .put("timedOut", operation.timedOut)
        check(prefs.edit().putString("current", j.toString()).commit()) { "无法保存操作记录，未发送新请求" }
    }
}
