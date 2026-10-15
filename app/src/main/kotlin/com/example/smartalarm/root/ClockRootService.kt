package com.example.smartalarm.root

import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.topjohnwu.superuser.ipc.RootService

/** 运行在 root 进程里。只做本 APP 需要的、固定的时钟业务接口调用。 */
class ClockRootService : RootService() {
    override fun onBind(intent: Intent): IBinder {
        if (Build.VERSION.SDK_INT < 31) throw IllegalStateException("root 桥接需要 API31+")
        return Bridge()
    }

    @androidx.annotation.RequiresApi(31)
    private inner class Bridge : IClockBridge.Stub() {
        override fun probe(): Bundle {
            requireOwnApp()
            return Bundle().apply {
                putInt(KEY_UID, Process.myUid())
                putInt(KEY_PID, Process.myPid())
                putString(KEY_ROM, Build.DISPLAY)
                putString(KEY_ANDROID, "${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT}")
                putString(KEY_DEVICE, "${Build.MANUFACTURER} ${Build.MODEL}")
                runCatching {
                    val info = packageManager.getPackageInfo(ClockContract.PACKAGE, 0)
                    putLong(KEY_CLOCK_VERSION_CODE, info.longVersionCode)
                    putString(KEY_CLOCK_VERSION_NAME, info.versionName)
                }.onFailure { putString(KEY_CLOCK_ERROR, describe(it)) }
                for (authority in ClockContract.AUTHORITIES) {
                    val provider = runCatching { packageManager.resolveContentProvider(authority, 0) }.getOrNull()
                    putString(
                        "provider:$authority",
                        provider?.let { "exported=${it.exported} enabled=${it.enabled} permission=${it.readPermission}" }
                            ?: "not-found",
                    )
                }
            }
        }

        override fun readAlarmList(): Bundle {
            requireOwnApp()
            val out = Bundle()
            val started = SystemClock.elapsedRealtime()
            try {
                val response = ExternalProviderCaller.call(
                    ClockContract.AUTHORITY, ClockContract.METHOD_GET_ALARM_LIST, null, null,
                )
                if (response == null) out.putString(KEY_ERROR, "provider 返回 null") else out.putBundle(KEY_RESPONSE, response)
            } catch (t: Throwable) {
                out.putString(KEY_ERROR, describe(t))
            }
            out.putLong(KEY_ELAPSED_MS, SystemClock.elapsedRealtime() - started)
            return out
        }

        override fun isClockEditorOpen(): Boolean {
            requireOwnApp()
            return try {
                val process = ProcessBuilder("dumpsys", "activity", ClockContract.PACKAGE + "/" + ClockContract.LIST_ACTIVITY)
                    .redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                text.contains(ClockContract.EDITOR_MARKER)
            } catch (t: Throwable) {
                true // 读不清就当作“有”，不去破坏用户在时钟里的现场
            }
        }

        override fun addAlarm(hour: Int, minute: Int, label: String?): Bundle {
            requireOwnApp()
            require(hour in 0..23 && minute in 0..59) { "时间越界: $hour:$minute" }
            val safeLabel = (label ?: "").take(MAX_LABEL_LENGTH)
            val extras = Bundle().apply {
                putInt(ClockContract.ADD_HOUR, hour)
                putInt(ClockContract.ADD_MINUTES, minute)
                putByte(ClockContract.ADD_DAYS_OF_WEEK, 0) // 0 = 不重复，即一次性
                putString(ClockContract.ADD_LABEL, safeLabel)
                putInt(ClockContract.ADD_ENABLE, 1)
            }
            return timedCall(ClockContract.METHOD_ADD_ALARM, extras)
        }

        override fun enableAlarm(alarmId: Long): Bundle {
            requireOwnApp()
            require(alarmId >= 0) { "编号无效: $alarmId" }
            val extras = Bundle().apply { putLong(ClockContract.EXTRA_ALARM_ID, alarmId) } // 不带时分
            return timedCall(ClockContract.METHOD_ENABLE_ALARM, extras)
        }

        private fun timedCall(method: String, extras: Bundle): Bundle {
            val out = Bundle()
            val started = SystemClock.elapsedRealtime()
            try {
                val response = ExternalProviderCaller.call(ClockContract.AUTHORITY, method, null, extras)
                if (response == null) out.putString(KEY_ERROR, "provider 返回 null") else out.putBundle(KEY_RESPONSE, response)
            } catch (t: Throwable) {
                out.putString(KEY_ERROR, describe(t))
            }
            out.putLong(KEY_ELAPSED_MS, SystemClock.elapsedRealtime() - started)
            return out
        }

        override fun countAlarms(): Bundle {
            requireOwnApp()
            val out = Bundle()
            val started = SystemClock.elapsedRealtime()
            try {
                out.putInt(KEY_COUNT, ExternalProviderCaller.queryCount(
                    ClockContract.TABLE_AUTHORITY, ClockContract.TABLE_PATH, ClockContract.LIST_FILTER))
                out.putInt(KEY_COUNT_ALL, ExternalProviderCaller.queryCount(
                    ClockContract.TABLE_AUTHORITY, ClockContract.TABLE_PATH, "1=1"))
            } catch (t: Throwable) {
                out.putString(KEY_ERROR, describe(t))
            }
            out.putLong(KEY_ELAPSED_MS, SystemClock.elapsedRealtime() - started)
            return out
        }

        private fun requireOwnApp() {
            val caller = Binder.getCallingUid()
            if (caller != applicationInfo.uid) throw SecurityException("调用方 uid=$caller 不是本 APP")
            check(Build.VERSION.SDK_INT >= 31 && applicationInfo.uid / 100000 == 0 && Process.myUid() == 0) {
                "root 接口仅支持 API31+、主用户和 UID0"
            }
        }
    }

    companion object {
        const val KEY_UID = "uid"
        const val KEY_PID = "pid"
        const val KEY_ROM = "rom"
        const val KEY_ANDROID = "android"
        const val KEY_DEVICE = "device"
        const val KEY_CLOCK_VERSION_CODE = "clockVersionCode"
        const val KEY_CLOCK_VERSION_NAME = "clockVersionName"
        const val KEY_CLOCK_ERROR = "clockError"
        const val KEY_RESPONSE = "response"
        const val KEY_ERROR = "error"
        const val KEY_ELAPSED_MS = "elapsedMs"
        const val MAX_LABEL_LENGTH = 40
        const val KEY_COUNT = "count"
        const val KEY_COUNT_ALL = "countAll"

        private fun describe(t: Throwable) = "${t.javaClass.name}: ${t.message}"
    }
}

/** 时钟业务接口的固定常量，来源：16.19.14 反编译 + 清单，手机当前版本须在 S0/S1 复核。 */
object ClockContract {
    const val PACKAGE = "com.coloros.alarmclock"
    const val AUTHORITY = "com.coloros.alarmclock.ai"
    val AUTHORITIES = listOf("com.coloros.alarmclock.ai", "com.oplus.alarmclock.ai")
    const val LIST_ACTIVITY = "com.oplus.alarmclock.AlarmClock"
    /** 新建/编辑面板打开时 dumpsys 里才会出现的 Fragment 名（16.19.14，2026-10-01 实测）。 */
    const val EDITOR_MARKER = "AddAlarmPanel"
    const val METHOD_GET_ALARM_LIST = "get_alarm_list"
    const val METHOD_ADD_ALARM = "add_alarm"
    const val METHOD_ENABLE_ALARM = "enable_alarm"
    const val EXTRA_ALARM_ID = "alarm_id"

    // add_alarm 的参数键，来自 16.19.14 的 c5.b.p smali；默认值与含义以真机实测为准
    const val ADD_HOUR = "android.intent.extra.alarm.HOUR"
    const val ADD_MINUTES = "android.intent.extra.alarm.MINUTES"
    const val ADD_DAYS_OF_WEEK = "android.intent.extra.alarm.DAYS_OF_WEEK"
    const val ADD_LABEL = "label"
    const val ADD_ENABLE = "android.intent.action.ALARM_ENABLE"

    /** 闹钟表本身（ClockProvider）。过滤条件与 get_alarm_list 内部 p2.Q 的固定 where 相同（16.19.14 smali）。 */
    const val TABLE_AUTHORITY = "com.coloros.alarmclock.alarmclock"
    const val TABLE_PATH = "alarm"
    const val LIST_FILTER = "garbSwitch = 0 and loopID = -1"
}
