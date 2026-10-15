package com.example.phaseupgradeprobe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Independent runner: no mappings or classes from either obfuscated production APK. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @Test fun seedLegacy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION") assertEquals(14, context.packageManager.getPackageInfo(context.packageName, 0).versionCode)
        check(context.getSharedPreferences("user_prefs", 0).edit()
            .putString("last_mode", "CATCH_UP").putInt("cycle_minutes", 103).putInt("last_delay", 25).commit())
        check(context.getSharedPreferences("sleep_log", 0).edit().putString("entries",
            """[{"created":1792018800000,"date":"2026-10-14","bed":"23:40","wake":"08:30","mode":"NORMAL","sleepMin":510,"quality":"GOOD"}]""").commit())
        check(context.getSharedPreferences("alarm_inflight", 0).edit().putInt("hour", 8).putInt("minute", 30)
            .putLong("started", 1792018800000).putString("ids", "1,2").commit())
    }
    @Test fun verifyCurrent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION") assertEquals(15, context.packageManager.getPackageInfo(context.packageName, 0).versionCode)
        val prefs = context.getSharedPreferences("user_prefs", 0)
        assertEquals("CATCH_UP", prefs.getString("last_mode", null))
        assertEquals(103, prefs.getInt("cycle_minutes", 0)); assertEquals(25, prefs.getInt("last_delay", 0))
        assertTrue(context.getSharedPreferences("sleep_log", 0).getString("entries", "")!!.contains("510"))
        assertEquals("1,2", context.getSharedPreferences("alarm_inflight", 0).getString("ids", null))
        assertFalse(context.getSharedPreferences("alarm_delivery", 0).contains("mode"))
    }
}
