package com.example.smartalarm

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.smartalarm.bridge.*
import com.example.smartalarm.core.interaction.DeliveryMode
import com.example.smartalarm.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class StandardFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun seen(text: String) = device.wait(Until.findObject(By.textContains(text)), 30_000)
        ?: error("Missing UI: $text")

    @Test fun ordinary_confirmation_cancel_multi_clock_and_debounce() {
        for (name in listOf("alarm_operation", "alarm_inflight", "alarm_delivery", "sleep_log"))
            check(context.getSharedPreferences(name, 0).edit().clear().commit())
        device.executeShellCommand("pm clear com.example.phasefixture.first")
        device.executeShellCommand("pm clear com.example.phasefixture.second")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        seen("相位")
        val controller = AlarmSetController.get(context)
        main { controller.selectMode(DeliveryMode.STANDARD) }
        val choices = StandardClockResolver(context).candidates()
        assertTrue(choices.count { it.identity.clockPackage.startsWith("com.example.phasefixture.") } == 2)
        val first = choices.first { it.identity.clockPackage.endsWith("phasefixture.first") }
        main { controller.chooseClock() }
        seen("测试时钟 A").click()
        val target = LocalTime.of(7, 23)
        val log = SleepLogStore.entryNow(LocalTime.of(23, 0), target, "NORMAL", 480, "GOOD")
        main { repeat(3) { controller.request(target, "相位·07:23", log) } }
        seen("target=07:23 skip=false")
        seen("received=1 saved=0")
        assertEquals(0, SleepLogStore(context).loadAll().size)
        seen("FIXTURE SAVE").click()
        device.wait(Until.findObject(By.text("保存了")), 30_000)!!.click()
        seen("你已确认保存")
        assertEquals(1, SleepLogStore(context).loadAll().size)
        assertEquals("user-confirmed", SleepLogStore(context).loadAll().single().confirmationSource)
        main { controller.showClock() }
        seen("FIXTURE LIST")
        seen("FIXTURE ${first.identity.clockPackage}")
        device.pressBack()
        main { controller.request(target, "相位·07:23", log) }
        seen("received=2 saved=1")
        seen("FIXTURE CANCEL").click()
        seen("没保存 / 已取消").click()
        assertEquals(1, SleepLogStore(context).loadAll().size)
        assertEquals(first.identity, AlarmDeliveryPrefs(context).clock!!.identity)
        assertEquals(DeliveryMode.STANDARD, AlarmDeliveryPrefs(context).mode)
        // Disabling a selected receiver must not silently send to a different clock.
        device.executeShellCommand("pm disable-user --user 0 ${first.identity.clockPackage}")
        try {
            main { controller.request(target, "相位·07:23", log) }
            seen("原时钟已卸载、禁用或身份发生变化")
            assertTrue(controller.state is SetUiState.Choosing)
        } finally {
            device.executeShellCommand("pm enable ${first.identity.clockPackage}")
            main { controller.dismiss() }
        }
        // Exercise the actual home button too; the earlier direct calls test rapid entry precisely.
        main { controller.selectClock(first) }
        seen("设为系统闹钟").click()
        seen("FIXTURE ${first.identity.clockPackage}")
        val actualTarget = (controller.state as SetUiState.AwaitingUser).target
        seen("target=%02d:%02d skip=false".format(actualTarget.hour, actualTarget.minute))
        seen("FIXTURE CANCEL").click()
        seen("没保存 / 已取消").click()
        assertEquals(1, SleepLogStore(context).loadAll().size)
    }
}
