package com.example.smartalarm

import android.app.AlarmManager
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.smartalarm.bridge.*
import com.example.smartalarm.core.interaction.DeliveryMode
import com.example.smartalarm.data.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.regex.Pattern
import kotlin.math.abs

/** A real installed clock. No database access and no root. Cloud evidence covers ringing UI, not physical sound. */
@RunWith(AndroidJUnit4::class)
class NativeClockTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(milliseconds: Long, check: () -> Boolean): Boolean {
        val until = android.os.SystemClock.elapsedRealtime() + milliseconds
        while (android.os.SystemClock.elapsedRealtime() < until) {
            if (check()) return true
            Thread.sleep(300)
        }
        return check()
    }
    private fun launchPhase() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 30_000))
    }

    @Test fun ordinary_native_clock_schedules_and_displays_screen_off_alarm() {
        val args = InstrumentationRegistry.getArguments()
        for (name in listOf("alarm_operation", "alarm_inflight", "alarm_delivery", "sleep_log"))
            check(context.getSharedPreferences(name, 0).edit().clear().commit())
        val out = File(context.getExternalFilesDir(null), "native-clock").apply { mkdirs() }
        var clocks = StandardClockResolver(context).candidates().filterNot { it.identity.clockPackage.startsWith("com.example.phasefixture.") }
        val disabled = device.executeShellCommand("pm list packages -d").lineSequence().map { it.removePrefix("package:").trim() }
            .filter { it in setOf("com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock",
                "com.coloros.alarmclock", "com.oneplus.deskclock", "com.oplus.alarmclock") }.toList()
        val setup = JSONObject().put("disabledClockPackages", JSONArray(disabled))
        // Test Lab may ship a stock clock disabled. Preparing a clean test device is
        // separate from production permissions; this never runs in the delivered APK.
        if (clocks.isEmpty() && disabled.isNotEmpty()) {
            val actions = JSONArray()
            for (pkg in disabled) actions.put(JSONObject().put("package", pkg)
                .put("result", device.executeShellCommand("pm enable --user 0 $pkg")))
            setup.put("testDevicePreparation", actions)
            clocks = StandardClockResolver(context).candidates().filterNot { it.identity.clockPackage.startsWith("com.example.phasefixture.") }
        }
        @Suppress("DEPRECATION") val raw = context.packageManager.queryIntentActivities(Intent(android.provider.AlarmClock.ACTION_SET_ALARM), 0)
        setup.put("rawHandlers", JSONArray().apply { raw.forEach { r ->
            put(JSONObject().put("package", r.activityInfo.packageName).put("activity", r.activityInfo.name)
                .put("enabled", r.activityInfo.enabled).put("exported", r.activityInfo.exported)
                .put("identityError", runCatching {
                    @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(r.activityInfo.packageName,
                        if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                        else android.content.pm.PackageManager.GET_SIGNATURES)
                    @Suppress("DEPRECATION") val signers = if (android.os.Build.VERSION.SDK_INT >= 28)
                        info.signingInfo?.apkContentsSigners else info.signatures
                    check(!signers.isNullOrEmpty()) { "Signing identity unavailable" }
                }.exceptionOrNull()?.toString() ?: JSONObject.NULL))
        } }).put("selectedCandidates", JSONArray(clocks.map { it.identity.clockPackage }))
        setup.put("installedClockPackages", device.executeShellCommand("pm list packages").lineSequence()
            .filter { it.contains("clock", ignoreCase = true) || it.contains("alarm", ignoreCase = true) }.joinToString("\n"))
        File(out, "preflight.json").writeText(setup.toString(2))
        assertTrue("No real SET_ALARM receiver in this image", clocks.isNotEmpty())
        val clock = clocks.firstOrNull { it.identity.clockPackage == args.getString("clockPackage") }
            ?: clocks.first()
        val next = ZonedDateTime.now().plusMinutes(3).withSecond(0).withNano(0)
        val target = next.toLocalTime()
        val expectedLabel = "相位·%02d:%02d".format(target.hour, target.minute)
        val expected = next.toInstant().toEpochMilli()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val report = JSONObject().put("clock", clock.identity.clockPackage).put("version", clock.identity.versionCode)
            .put("certificate", clock.identity.certificateSha256).put("expectedMillis", expected)
            .put("target", target.toString()).put("physicalSound", "NOT_RUN")
        File(out, "result.json").writeText(report.toString(2))
        launchPhase()
        val c = AlarmSetController.get(context)
        main {
            c.reloadPreferences()
            c.selectMode(DeliveryMode.STANDARD)
            c.selectClock(clock)
            c.request(target, expectedLabel,
                SleepLogStore.entryNow(LocalTime.now(), target, "NAP", 3, "GOOD"))
        }
        val matches = { alarmManager.nextAlarmClock?.triggerTime?.let { abs(it - expected) < 1000 } == true }
        if (!waitFor(10_000, matches)) {
            assertTrue("Clock did not open", device.currentPackageName == clock.identity.clockPackage)
            val save = device.findObject(By.text(Pattern.compile("(?i)^(save|done|ok|保存|完成|确定)$")))
                ?: device.findObject(By.res(Pattern.compile(".*(save|done).*")))
            if (save != null) {
                save.click()
                report.put("clockConfirmation", "save-button")
            } else {
                // Older Google/AOSP clocks expose a prefilled row with its enable
                // switch off instead of a Save button. Only touch this exact label's row.
                var parent = device.findObject(By.text(expectedLabel))?.parent
                var toggle: androidx.test.uiautomator.UiObject2? = null
                while (parent != null) {
                    val switches = parent.findObjects(By.res(clock.identity.clockPackage, "onoff"))
                    if (switches.size == 1) { toggle = switches.single(); break }
                    if (switches.size > 1) break
                    parent = parent.parent
                }
                assertNotNull("Clock requires confirmation but no target-row switch or save action is visible", toggle)
                if (!toggle!!.isChecked) toggle.click()
                report.put("clockConfirmation", "target-row-enable")
            }
        }
        assertTrue("Expected alarm is not registered as next alarm", waitFor(15_000, matches))
        report.put("nextAlarmVerified", true)
        assertEquals("dispatch is not confirmation", 0, SleepLogStore(context).loadAll().size)
        device.takeScreenshot(File(out, "clock-after-dispatch.png"))
        launchPhase()
        device.waitForIdle()
        val confirm = device.wait(Until.findObject(By.text("保存了")), 30_000)
        assertNotNull("Phase must request explicit confirmation", confirm)
        confirm!!.click()
        assertTrue("Confirmation did not persist one history record", waitFor(5_000) { SleepLogStore(context).loadAll().size == 1 })
        assertEquals("user-confirmed", SleepLogStore(context).loadAll().single().confirmationSource)
        report.put("userConfirmation", true)
        File(out, "result.json").writeText(report.toString(2))
        instrumentation.addResults(Bundle().apply { putString("nativeClockDispatch", report.toString()) })
        if (args.getString("stopAfterDispatch") == "true") return
        device.pressHome()
        device.sleep()
        assertTrue("Device did not actually turn its screen off", waitFor(5_000) { !device.isScreenOn })
        report.put("screenOffObserved", true)
        val dismissPattern = Pattern.compile("(?i).*(snooze|稍后提醒|贪睡|稍后再响).*")
        assertTrue("Clock ringing UI did not appear after screen-off", waitFor((expected - System.currentTimeMillis()).coerceAtLeast(0) + 90_000) {
            val activeClock = device.hasObject(By.pkg(clock.identity.clockPackage))
            val ownAlarmNotification = device.hasObject(By.pkg("com.android.systemui")) &&
                (device.hasObject(By.textContains(expectedLabel)) || device.hasObject(By.descContains(expectedLabel)))
            System.currentTimeMillis() >= expected && device.isScreenOn && (activeClock || ownAlarmNotification) &&
                (device.hasObject(By.text(dismissPattern)) || device.hasObject(By.desc(dismissPattern)) ||
                    device.hasObject(By.res(Pattern.compile(Pattern.quote(clock.identity.clockPackage) + ":id/snooze.*"))))
        })
        report.put("screenOffRingUi", true).put("observedMillis", System.currentTimeMillis())
            .put("ringUiForm", if (device.hasObject(By.pkg(clock.identity.clockPackage))) "clock-activity" else "system-alarm-notification")
        device.takeScreenshot(File(out, "ringing.png"))
        device.dumpWindowHierarchy(File(out, "ringing.xml"))
        File(out, "result.json").writeText(report.toString(2))
        val dismiss = device.findObject(By.text(Pattern.compile("(?i)^(dismiss|stop|关闭|停止|关闭闹钟)$")))
            ?: device.findObject(By.desc(Pattern.compile("(?i)^(dismiss|stop|关闭|停止|关闭闹钟)$")))
        dismiss?.click()
        instrumentation.sendStatus(0, Bundle().apply { putString("nativeClockEvidence", report.toString()) })
    }
}
