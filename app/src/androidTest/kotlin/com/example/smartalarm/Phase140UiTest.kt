package com.example.smartalarm

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.smartalarm.bridge.*
import com.example.smartalarm.core.interaction.*
import com.example.smartalarm.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** UI uses an injected synthetic clock, including root UI. Never starts su or writes an OEM clock. */
@RunWith(AndroidJUnit4::class)
class Phase140UiTest {
    @Before fun emulatorOnly() { assumeTrue("Synthetic UI tests clear app preferences; emulator only",
        android.os.Build.HARDWARE in listOf("ranchu", "goldfish")) }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val identity = AlarmIdentity("synthetic.clock", "fake", 1, 0, DeliveryMode.ROOT, "fake")
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun seen(selector: BySelector): UiObject2 = device.wait(Until.findObject(selector), 10_000) ?: error("Missing $selector")
    private fun selectedMode(label: String) = seen(By.selected(true).hasDescendant(By.text(label)))
    private fun scrollPageDown() {
        // The middle/right of the settings page contains interactive number wheels.
        val x = device.displayWidth / 12
        device.swipe(x, device.displayHeight * 4 / 5, x, device.displayHeight / 4, 30)
    }
    private fun scrollTo(selector: BySelector): UiObject2 {
        val deadline = System.currentTimeMillis() + 10_000
        val settingsPage = device.hasObject(By.text("设置"))
        while (!device.hasObject(selector) && System.currentTimeMillis() < deadline) {
            if (settingsPage) scrollPageDown()
            else device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.65f)
        }
        return seen(selector)
    }
    private fun capture(name: String) {
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "phase140-$name.png")))
    }
    private fun withApp(root: Boolean, operation: AlarmOperation? = null, nextTrigger: Long? = null,
        configure: () -> Unit = {}, block: (AlarmSetController) -> Unit) {
        for (name in listOf("alarm_operation", "alarm_inflight", "alarm_delivery", "sleep_log", "user_prefs"))
            check(context.getSharedPreferences(name, 0).edit().clear().commit())
        operation?.let { AlarmOperationStore(context).save(it) }
        configure()
        val field = AlarmSetController::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val old = field.get(null)
        val records = CopyOnWriteArrayList<ClockAlarmRecord>()
        lateinit var controller: AlarmSetController
        var activity: Activity? = null
        try {
            main {
                controller = AlarmSetController(context,
                    backendFactory = { RootBackend(object : ClockGateway {
                        override fun readSnapshot() = if (records.isEmpty()) ClockAlarmSnapshot.ConfirmedEmpty else ClockAlarmSnapshot.Complete(records.toList())
                        override fun addOneShot(hour: Int, minute: Int, label: String): AddResult {
                            var next = ZonedDateTime.now().withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                            if (!next.isAfter(ZonedDateTime.now())) next = next.plusDays(1)
                            records += ClockAlarmRecord(records.size + 1L, hour, minute, label, true, "响一次", 0, next.toInstant().toEpochMilli())
                            return AddResult.Created(records.size.toLong())
                        }
                        override fun enable(id: Long): Boolean = error("unexpected enable")
                    }, {}, { false }) }, rootIdentity = { identity }, navigateRoot = { _, _ -> true },
                    nextAlarmMillis = { nextTrigger })
                controller.selectMode(if (root) DeliveryMode.ROOT else DeliveryMode.STANDARD)
                field.set(null, controller)
            }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            seen(By.text("相位"))
            block(controller)
        } catch (failure: Throwable) {
            runCatching {
                val name = "failed-${SystemClock.uptimeMillis()}"
                device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "phase140-$name.xml"))
                capture(name)
            }
            throw failure
        } finally {
            main { activity?.finish(); field.set(null, old) }
        }
    }

    @Test fun ordinary_hint_about_version_and_all_license_sources_are_visible() = withApp(false) {
        seen(By.text("正常")).click()
        seen(By.text("时间可以微调"))
        seen(By.textContains("前后大约 20 分钟内都算正常"))
        assertFalse(device.hasObject(By.descStartsWith("睡得快 ")))
        assertFalse(device.hasObject(By.textContains("v1.4.0")))
        capture("ordinary")
        seen(By.desc("设置")).click()
        scrollTo(By.text("关于相位"))
        seen(By.textContains("1.4.0 (17)"))
        capture("about")
        scrollTo(By.text("开源许可")).click()
        val content = seen(By.textContains("MIT License")).text
        for (source in listOf("THIRD-PARTY-NOTICES.md", "Apache-2.0.txt", "libsu-LICENSE.txt")) assertTrue(content.contains(source))
        capture("licenses")
        seen(By.text("关闭")).click()
    }

    @Test fun root_actual_time_button_and_text_selection_take_different_paths() = withApp(true) { controller ->
        seen(By.text("正常")).click()
        assertFalse(device.hasObject(By.text("时间可以微调")))
        capture("root")
        val button = scrollTo(By.descStartsWith("躺得久 "))
        val target = LocalTime.parse(button.contentDescription.substringAfter(' '))
        button.click()
        val deadline = System.currentTimeMillis() + 10_000
        while (controller.state !is SetUiState.Success && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals(target, (controller.state as SetUiState.Success).target)
        assertEquals(target, SleepLogStore(context).loadAll().single().wakeTime.withSecond(0).withNano(0))
        assertEquals(495, SleepLogStore(context).loadAll().single().sleepMinutes)
        val operationBeforeTextSelection = controller.lastOperation()
        scrollTo(By.textStartsWith("1 个周期 ·")).click()
        assertEquals("text selection must not even start an operation", operationBeforeTextSelection, controller.lastOperation())
        assertEquals(target, (controller.state as SetUiState.Success).target)
        assertEquals("text selection must not dispatch", 1, SleepLogStore(context).loadAll().size)
    }

    @Test fun slider_taps_drag_and_vertical_scroll_keep_selection_consistent() = withApp(false) {
        seen(By.text("上课")).click()
        seen(By.text("正常")).click()
        assertTrue(selectedMode("正常").isSelected)
        Thread.sleep(300) // Grab the actual thumb after the rapid-tap animation settles.
        val start = seen(By.text("正常")).visibleCenter
        val end = seen(By.text("小睡")).visibleCenter
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), start.y.toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        try {
            touch(MotionEvent.ACTION_DOWN, start.x)
            for (step in 1..12) { touch(MotionEvent.ACTION_MOVE, start.x + (end.x - start.x) * step / 12); Thread.sleep(15) }
            assertTrue("drag preview must not commit mode", selectedMode("正常").isSelected)
            seen(By.textContains("5 个周期"))
        } finally { touch(MotionEvent.ACTION_UP, end.x) }
        device.waitForIdle()
        assertTrue(selectedMode("小睡").isSelected)
        device.swipe(end.x, end.y, end.x, end.y + 100, 20)
        assertTrue(selectedMode("小睡").isSelected)
        seen(By.text("正常")).click()
        val oldScale = device.executeShellCommand("settings get global animator_duration_scale").trim()
        try {
            device.executeShellCommand("settings put global animator_duration_scale 0")
            seen(By.text("保早餐")).click()
            assertTrue(selectedMode("保早餐").isSelected)
            capture("no-animation")
        } finally { device.executeShellCommand("settings put global animator_duration_scale $oldScale") }
    }

    @Test fun ordinary_fixed_nap_has_no_hint_and_cycle_nap_uses_core_offset() = withApp(false) { controller ->
        seen(By.text("小睡")).click()
        seen(By.textContains("前后大约 13 分钟内都算正常"))
        scrollTo(By.text("短睡 25 分钟")).click()
        device.findObject(By.scrollable(true))?.fling(Direction.UP)
        device.waitForIdle()
        assertFalse(device.hasObject(By.text("时间可以微调")))
        assertTrue(controller.state is SetUiState.Idle)
        assertNull(AlarmOperationStore(context).load())
    }

    @Test fun ordinary_must_wake_fixed_candidate_has_no_uncertainty_hint() = withApp(false, configure = {
        PrefsHelper(context).apply {
            lastMode = com.example.smartalarm.core.model.AlarmMode.CLASS
            punchEnabled = true
            punchTime = LocalTime.now().plusHours(4)
            classTarget = LocalTime.now().plusHours(6)
        }
    }) {
        scrollTo(By.text("必醒后直接起")).click()
        device.findObject(By.scrollable(true))?.fling(Direction.UP)
        device.waitForIdle()
        assertFalse(device.hasObject(By.text("时间可以微调")))
        seen(By.text("正常")).click()
        seen(By.textContains("前后大约 20 分钟内都算正常"))
    }

    @Test fun durable_recalculation_banner_and_inactive_alarm_do_not_show_warning() {
        val op = AlarmOperation("persisted", identity.copy(mode = DeliveryMode.STANDARD), 6, 27, "test",
            System.currentTimeMillis() - 20 * 60_000, ZoneId.systemDefault().id, null, OperationStage.USER_CONFIRMED)
        withApp(false, op) {
            seen(By.text("躺了一个小时还没睡着，从现在重算。"))
            seen(By.text("从现在重算")).click()
            device.waitForIdle()
            assertFalse(device.hasObject(By.text("留意之前的闹钟")))
            assertEquals(op, AlarmOperationStore(context).load()) // Inspection never edits an alarm or the saved operation.
        }
    }

    @Test fun active_and_hidden_previous_alarms_show_different_read_only_reminders() {
        val op = AlarmOperation("persisted", identity.copy(mode = DeliveryMode.STANDARD), 6, 27, "test",
            System.currentTimeMillis() - 20 * 60_000, ZoneId.systemDefault().id, null, OperationStage.USER_CONFIRMED)
        var next = ZonedDateTime.now().withHour(6).withMinute(27).withSecond(0).withNano(0)
        if (!next.isAfter(ZonedDateTime.now())) next = next.plusDays(1)
        for (active in listOf(true, false)) {
            withApp(false, op, next.toInstant().toEpochMilli() - if (active) 0 else 120_000) {
                seen(By.text("从现在重算")).click()
                seen(By.text("留意之前的闹钟"))
                seen(By.textContains(if (active) "06:27 闹钟还开着" else "没能确认之前设的 06:27"))
                capture(if (active) "previous-active" else "previous-unknown")
                assertEquals(op, AlarmOperationStore(context).load())
                seen(By.text("知道了")).click()
            }
        }
    }

    @Test fun enlarged_fonts_keep_all_modes_and_scenario_times_available() {
        val old = device.executeShellCommand("settings get system font_scale").trim()
        try {
            for (scale in listOf("1.0", "1.3", "2.0")) {
                device.executeShellCommand("settings put system font_scale $scale")
                withApp(true) {
                    for (mode in listOf("正常", "爽睡", "小睡", "上课", "保早餐")) seen(By.text(mode))
                    capture("root-font-$scale-top")
                    scrollTo(By.descStartsWith("躺得久 "))
                    device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.15f)
                    capture("root-font-$scale-times")
                }
            }
        } finally { device.executeShellCommand("settings put system font_scale $old") }
    }

    @Test fun project_homepage_uses_browser_intent_and_missing_browser_shows_hint() = withApp(false) {
        seen(By.desc("设置")).click()
        scrollTo(By.text("项目主页"))
        val observed = AtomicReference<Intent>()
        val browser = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                observed.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(browser)
        try {
            seen(By.text("项目主页")).click()
            device.waitForIdle()
            assertEquals("https://github.com/xzyj50609/phase-sleep-alarm", observed.get()?.dataString)
        } finally { instrumentation.removeMonitor(browser) }
        val missingBrowser = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW) throw ActivityNotFoundException("synthetic browser absence")
                return null
            }
        }
        instrumentation.addMonitor(missingBrowser)
        try {
            val button = seen(By.text("项目主页")).visibleCenter
            // UiObject2.click waits for idle; by then a transient Toast may already be gone.
            val event = instrumentation.uiAutomation.executeAndWaitForEvent(
                { device.click(button.x, button.y) },
                { event -> event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                    event.text.any { it.toString().contains("没有可用的浏览器") } },
                5_000,
            )
            event.recycle()
            seen(By.text("关于相位"))
        } finally { instrumentation.removeMonitor(missingBrowser) }
    }
}
