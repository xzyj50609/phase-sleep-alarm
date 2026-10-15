package com.example.smartalarm

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.smartalarm.bridge.*
import com.example.smartalarm.data.SleepLogStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class ClockResolutionTest {
    @Test fun T02_no_receiver_and_single_receiver_are_distinct() {
        // Package disable/enable is allowed only on our disposable emulator, never cloud or user hardware.
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val device = UiDevice.getInstance(i)
        for (name in listOf("alarm_operation", "alarm_inflight", "alarm_delivery", "sleep_log"))
            check(context.getSharedPreferences(name, 0).edit().clear().commit())
        val choices = StandardClockResolver(context).candidates()
        val first = choices.first { it.identity.clockPackage == "com.example.phasefixture.first" }
        try {
            choices.forEach { device.executeShellCommand("pm disable-user --user 0 ${it.identity.clockPackage}") }
            var sends = 0
            lateinit var c: AlarmSetController
            i.runOnMainSync {
                c = AlarmSetController(context, launchStandard = { choice, h, m, _ ->
                    assertEquals(first.identity.clockPackage, choice.identity.clockPackage)
                    assertEquals(6, h); assertEquals(42, m); sends++; true
                })
                c.request(LocalTime.of(6, 42), "synthetic", null)
                assertTrue((c.state as SetUiState.Choosing).choices.isEmpty())
            }
            assertEquals(0, sends)
            device.executeShellCommand("pm enable ${first.identity.clockPackage}")
            i.runOnMainSync {
                c.dismiss()
                c.request(LocalTime.of(6, 42), "synthetic", null)
                assertTrue(c.state is SetUiState.AwaitingUser)
                c.confirmStandard(false)
            }
            assertEquals(1, sends)
            assertEquals(0, SleepLogStore(context).loadAll().size)
        } finally {
            choices.forEach { device.executeShellCommand("pm enable ${it.identity.clockPackage}") }
        }
    }
}
