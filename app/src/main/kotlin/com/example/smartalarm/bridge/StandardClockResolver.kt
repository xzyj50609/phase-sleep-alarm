package com.example.smartalarm.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.AlarmClock
import com.example.smartalarm.core.interaction.*
import java.security.MessageDigest

data class ClockChoice(val label: String, val identity: AlarmIdentity, val component: String) {
    // ColorOS HandleApiActivity creates immediately when HOUR is supplied, even with SKIP_UI=false.
    val requiresManualTime: Boolean get() = identity.clockPackage == "com.coloros.alarmclock" &&
        component == "com.oplus.alarmclock.cts.HandleApiActivity"
}

class StandardClockResolver(private val context: Context) {
    @Suppress("DEPRECATION")
    fun candidates(): List<ClockChoice> = context.packageManager.queryIntentActivities(
        Intent(AlarmClock.ACTION_SET_ALARM), PackageManager.MATCH_DEFAULT_ONLY,
    ).mapNotNull { resolved ->
        val activity = resolved.activityInfo
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) null
        else runCatching {
            ClockChoice(resolved.loadLabel(context.packageManager).toString(),
                packageIdentity(context, activity.packageName, DeliveryMode.STANDARD, "standard-intent-v1"), activity.name)
        }.getOrNull()
    }.distinctBy { it.identity.clockPackage }.sortedBy { it.label }

    fun show(choice: ClockChoice): Boolean = showPackage(choice.identity.clockPackage)

    fun showPackage(packageName: String): Boolean {
        val show = Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(packageName)
        val intent = if (context.packageManager.resolveActivity(show, PackageManager.MATCH_DEFAULT_ONLY) != null) show
            else context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    companion object {
        internal fun createIntent(choice: ClockChoice, hour: Int, minute: Int, label: String): Intent =
            Intent(AlarmClock.ACTION_SET_ALARM)
                .setClassName(choice.identity.clockPackage, choice.component)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .apply {
                    // Keep extras non-null but omit HOUR: the known receiver takes its editor branch (-1).
                    if (!choice.requiresManualTime) {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    }
                }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun launch(context: Context, choice: ClockChoice, hour: Int, minute: Int, label: String): Boolean = try {
            context.startActivity(createIntent(choice, hour, minute, label))
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

@Suppress("DEPRECATION")
internal fun packageIdentity(context: Context, pkg: String, mode: DeliveryMode, adapter: String): AlarmIdentity {
    val flags = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val info = context.packageManager.getPackageInfo(pkg, flags)
    val signatures = (if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
        ?: error("无法读取时钟签名")
    val digest = signatures.map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
        .joinToString("") { "%02x".format(it) } }.sorted().joinToString(",")
    return AlarmIdentity(pkg, digest,
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
        Process.myUid() / 100000, mode, adapter)
}
