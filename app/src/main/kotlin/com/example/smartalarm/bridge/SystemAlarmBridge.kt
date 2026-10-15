package com.example.smartalarm.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import java.time.LocalTime
import java.util.Locale

data class BridgeResult(val success: Boolean, val path: BridgePath, val message: String)
enum class BridgePath { ASSISTING, VERIFIED, NEEDS_ACCESSIBILITY, NEEDS_CONFIRMATION, FAILED }

@Suppress("UNUSED_PARAMETER")
fun buildAlarmMessage(wakeUpTime: LocalTime, modeLabel: String, totalSleepMin: Int): String =
    "相位·" + String.format(Locale.ROOT, "%02d:%02d", wakeUpTime.hour, wakeUpTime.minute)

class SystemAlarmBridge {
    fun openSystemClock(context: Context, time: LocalTime, message: String, onVerified: () -> Unit = {}): BridgeResult =
        AlarmAssistService.request(context, time, message, onVerified)

    fun openPermissionSettings(context: Context) {
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, AlarmAssistService::class.java))
        try { context.startActivity(detail) }
        catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    fun openList(context: Context): Boolean = try {
        context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)
            .setPackage(AlarmAssistService.CLOCK_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }
}
