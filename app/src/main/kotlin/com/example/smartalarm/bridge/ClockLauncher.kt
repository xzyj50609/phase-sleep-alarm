package com.example.smartalarm.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.util.Log

/**
 * 只负责“把用户带到时钟列表”，不创建、不启用任何闹钟，不模拟点击。
 * 定位用时钟自己的入口：action enter_apk + long 型 alarm_id（16.19.14 的 AlarmClock.q()）。
 */
object ClockLauncher {
    private const val TAG = "PhaseSet"
    private const val CLOCK_PACKAGE = "com.coloros.alarmclock"
    private const val CLOCK_LIST_ACTIVITY = "com.oplus.alarmclock.AlarmClock"
    private const val ACTION_ENTER = "com.oplus.alarmclock.alarmclock.enter_apk"
    private const val EXTRA_ALARM_ID = "alarm_id"

    /**
     * 首次创建时钟界面时列表数据还没加载，时钟算不出位置，不会滚动（2026-10-01 真机实测）；
     * 界面已存在时再投递一次就能滚到位，所以隔一小会儿补发同一个意图。
     */
    private const val REDELIVER_DELAY_MS = 500L

    /**
     * 时钟定位是方向相关的：它总是把“目标之后第二条”滚到屏幕边缘，向下滚时目标还在屏幕里，
     * 向上滚时目标会被挤出屏幕（2026-10-01 真机实测）。所以界面已存在并停在目标下方就会失败。
     * [freshList] = true 时先重建时钟界面（列表回到顶部），之后只会向下滚。
     * 但重建会丢掉时钟里没保存的新建/编辑面板，所以只在确认没有未保存编辑时才这样做。
     */
    fun showAlarm(context: Context, alarmId: Long, freshList: Boolean): Boolean {
        val app = context.applicationContext
        val first = enterIntent(alarmId).apply { if (freshList) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK) }
        if (!start(app, first)) return false
        Handler(Looper.getMainLooper()).postDelayed({ start(app, enterIntent(alarmId)) }, REDELIVER_DELAY_MS)
        return true
    }

    /** 只打开列表（不定位）。兜底新建之后用。 */
    fun showList(context: Context): Boolean = start(
        context.applicationContext,
        Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(CLOCK_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /** 兜底：用 Android 标准方式请求系统新建。可能在时钟里多出一条，也无法在这里核对结果。 */
    fun requestSystemCreate(context: Context, hour: Int, minute: Int, label: String): Boolean {
        val app = context.applicationContext
        val set = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .setPackage(CLOCK_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(app, set)
    }

    private fun enterIntent(alarmId: Long) = Intent(ACTION_ENTER)
        .setComponent(ComponentName(CLOCK_PACKAGE, CLOCK_LIST_ACTIVITY))
        .putExtra(EXTRA_ALARM_ID, alarmId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "启动时钟失败: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}
