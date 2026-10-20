package com.example.smartalarm

import android.os.Looper
import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartalarm.bridge.*
import com.example.smartalarm.core.interaction.*
import com.example.smartalarm.data.AlarmDeliveryPrefs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun awaitRootSelection(controller: AlarmSetController) {
    check(Looper.myLooper() != Looper.getMainLooper())
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while ((controller.checkingRoot || (controller.mode != DeliveryMode.ROOT && controller.rootModeError == null)) &&
        System.nanoTime() < deadline) Thread.sleep(10)
    assertFalse("authorization must settle", controller.checkingRoot)
    assertEquals(controller.rootModeError, DeliveryMode.ROOT, controller.mode)
}

/** Only injected permission checks on owned emulators; no su or real clock operations. */
@RunWith(AndroidJUnit4::class)
class RootModePermissionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val identity = AlarmIdentity("synthetic.clock", "fake", 1, 0, DeliveryMode.ROOT, "fake")
    private val forbiddenClock = object : ClockGateway {
        override fun readSnapshot(): ClockAlarmSnapshot = error("mode selection must not read alarms")
        override fun addOneShot(hour: Int, minute: Int, label: String): AddResult = error("must not create")
        override fun enable(id: Long): Boolean = error("must not enable")
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun settled(c: AlarmSetController) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (c.checkingRoot && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(c.checkingRoot)
    }
    private fun controller(timeout: Long = 45_000, check: () -> Unit) = AlarmSetController(context,
        backendFactory = { RootBackend(forbiddenClock, {}, { false }, requestAuthorization = check) },
        rootIdentity = { identity }, rootAuthorizationTimeoutMillis = timeout)

    @Before fun reset() {
        assumeTrue(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        for (name in listOf("alarm_operation", "alarm_inflight", "alarm_delivery", "sleep_log"))
            check(context.getSharedPreferences(name, 0).edit().clear().commit())
    }

    @Test fun root_is_committed_only_after_permission_check_completes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var c: AlarmSetController
        main { c = controller { assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); entered.countDown(); release.await() }
            assertTrue(c.selectMode(DeliveryMode.ROOT)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(c.checkingRoot)
            assertTrue(c.isLocked)
            assertEquals(DeliveryMode.STANDARD, c.mode)
            assertEquals(DeliveryMode.STANDARD, AlarmDeliveryPrefs(context).mode)
            assertNull(c.lastOperation())
        } finally { release.countDown() }
        awaitRootSelection(c)
        assertEquals(DeliveryMode.ROOT, AlarmDeliveryPrefs(context).mode)
    }

    @Test fun denial_keeps_standard_and_explains_manager_retry() {
        lateinit var c: AlarmSetController
        main { c = controller { throw GatewayException(GatewayFailure.ROOT_DENIED, "synthetic denial") }
            c.selectMode(DeliveryMode.ROOT) }
        settled(c)
        assertEquals(DeliveryMode.STANDARD, c.mode)
        assertEquals(DeliveryMode.STANDARD, AlarmDeliveryPrefs(context).mode)
        assertTrue(c.rootModeError.orEmpty().contains("超级用户管理器"))
        assertNull(c.lastOperation())
    }

    @Test fun cancellation_and_timeout_ignore_late_grants() {
        for (timeout in listOf(false, true)) {
            reset()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val exited = CountDownLatch(1)
            lateinit var c: AlarmSetController
            main { c = controller(if (timeout) 100 else 45_000) { entered.countDown(); release.await(); exited.countDown() }
                c.selectMode(DeliveryMode.ROOT) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (!timeout) main { assertTrue(c.selectMode(DeliveryMode.STANDARD)) }
                settled(c)
            } finally { release.countDown() }
            assertTrue(exited.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            Thread.sleep(100)
            assertEquals(DeliveryMode.STANDARD, c.mode)
            assertEquals(DeliveryMode.STANDARD, AlarmDeliveryPrefs(context).mode)
            if (timeout) assertTrue(c.rootModeError.orEmpty().contains("超时"))
        }
    }

    @Test fun restored_root_preference_is_rechecked_not_trusted() {
        AlarmDeliveryPrefs(context).mode = DeliveryMode.ROOT
        lateinit var c: AlarmSetController
        main { c = controller { error("previous grant revoked") }
            assertEquals(DeliveryMode.STANDARD, c.mode)
            c.prepare() }
        settled(c)
        assertEquals(DeliveryMode.STANDARD, c.mode)
        assertNotNull(c.rootModeError)
    }

    @Test fun coloros_editor_request_omits_time_while_other_clocks_keep_prefill() {
        val coloros = ClockChoice("clock", identity.copy(clockPackage = "com.coloros.alarmclock", mode = DeliveryMode.STANDARD),
            "com.oplus.alarmclock.cts.HandleApiActivity")
        for (manual in listOf(true, false)) {
            val choice = if (manual) coloros else coloros.copy(identity = coloros.identity.copy(clockPackage = "synthetic.clock"))
            val intent = StandardClockResolver.createIntent(choice, 7, 42, "test")
            assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
            assertFalse(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
            assertNotNull(intent.extras)
            assertEquals(!manual, intent.hasExtra(AlarmClock.EXTRA_HOUR))
            assertEquals(!manual, intent.hasExtra(AlarmClock.EXTRA_MINUTES))
            if (!manual) assertEquals(7, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        }
    }
}
