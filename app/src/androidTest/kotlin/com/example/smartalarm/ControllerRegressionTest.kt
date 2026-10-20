package com.example.smartalarm

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smartalarm.bridge.*
import com.example.smartalarm.core.interaction.*
import com.example.smartalarm.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic backend only. These tests never touch an OEM clock or request su. */
@RunWith(AndroidJUnit4::class)
class ControllerRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun eventually(check: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!check() && System.nanoTime() < end) Thread.sleep(25)
        assertTrue("condition did not become true", check())
    }
    private fun reset() {
        for (name in listOf("alarm_operation", "alarm_inflight", "alarm_delivery", "sleep_log")) {
            check(context.getSharedPreferences(name, 0).edit().clear().commit())
        }
    }

    @Test fun B07_standard_construct_prepare_and_dispatch_never_construct_root() {
        reset()
        var constructed = 0
        lateinit var c: AlarmSetController
        onMain {
            c = AlarmSetController(context, backendFactory = { constructed++; error("root must stay lazy") })
            c.prepare()
        }
        Thread.sleep(250)
        assertEquals(0, constructed)
        assertEquals(DeliveryMode.STANDARD, c.mode)
    }

    @Test fun B02_B03_B06_B08_timeout_late_write_and_navigation_are_separate() {
        reset()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var adds = 0
        var standardLaunches = 0
        var offMainStateChanges = 0
        val target = LocalTime.now().plusHours(2).withSecond(0).withNano(0)
        var record: ClockAlarmRecord? = null
        val identity = AlarmIdentity("synthetic.clock", "synthetic", 1, 0, DeliveryMode.ROOT, "fake")
        val gateway = object : ClockGateway {
            override fun readSnapshot() = record?.let { ClockAlarmSnapshot.Complete(listOf(it)) }
                ?: ClockAlarmSnapshot.ConfirmedEmpty
            override fun addOneShot(hour: Int, minute: Int, label: String): AddResult {
                adds++; entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                var t = ZonedDateTime.now().withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                if (!t.isAfter(ZonedDateTime.now())) t = t.plusDays(1)
                record = ClockAlarmRecord(1, hour, minute, label, true, "响一次", 0, t.toInstant().toEpochMilli())
                completed.countDown()
                return AddResult.Created(1)
            }
            override fun enable(id: Long) = true
        }
        lateinit var c: AlarmSetController
        onMain {
            c = AlarmSetController(context,
                backendFactory = { RootBackend(gateway, {}, { false }) },
                rootIdentity = { identity },
                navigateRoot = { _, _ -> false },
                launchStandard = { _, _, _, _ -> standardLaunches++; true },
                watchdogMillis = 100,
                stateObserver = { if (Looper.myLooper() != Looper.getMainLooper()) offMainStateChanges++ },
            )
            c.selectMode(DeliveryMode.ROOT)
        }
        eventually { !c.checkingRoot }
        onMain {
            c.request(target, "synthetic", SleepLogStore.entryNow(LocalTime.now(), target, "NORMAL", 120, "GOOD"))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        eventually { c.state is SetUiState.Uncertain }
        onMain {
            c.requestSystemCreate(target, "synthetic")
            assertFalse(c.selectMode(DeliveryMode.STANDARD))
        }
        assertEquals(0, standardLaunches)
        release.countDown()
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        eventually { c.state is SetUiState.Success }
        val state = c.state as SetUiState.Success
        assertTrue(state.late)
        assertNotNull(state.listNote)
        assertEquals(1, adds)
        assertEquals(1, SleepLogStore(context).loadAll().size)
        assertEquals(0, offMainStateChanges)
        onMain { c.dismiss() }
    }

    @Test fun B03_verified_alarm_with_failed_navigation_is_not_created_again() {
        reset()
        val target = LocalTime.now().plusHours(1).withSecond(0).withNano(0)
        var t = ZonedDateTime.now().withHour(target.hour).withMinute(target.minute).withSecond(0).withNano(0)
        if (!t.isAfter(ZonedDateTime.now())) t = t.plusDays(1)
        val record = ClockAlarmRecord(42, target.hour, target.minute, "unchanged", true, "响一次", 0, t.toInstant().toEpochMilli())
        var writes = 0
        var navigation = 0
        val gateway = object : ClockGateway {
            override fun readSnapshot() = ClockAlarmSnapshot.Complete(listOf(record))
            override fun addOneShot(hour: Int, minute: Int, label: String): AddResult { writes++; error("unexpected write") }
            override fun enable(id: Long): Boolean { writes++; return true }
        }
        lateinit var c: AlarmSetController
        onMain {
            c = AlarmSetController(context, backendFactory = { RootBackend(gateway, {}, { false }) },
                rootIdentity = { AlarmIdentity("synthetic.clock", "synthetic", 1, 0, DeliveryMode.ROOT, "fake") },
                navigateRoot = { _, _ -> navigation++; false })
            c.selectMode(DeliveryMode.ROOT)
        }
        eventually { !c.checkingRoot }
        onMain {
            c.request(target, "synthetic", null)
        }
        eventually { c.state is SetUiState.Success }
        assertFalse((c.state as SetUiState.Success).late)
        assertTrue((c.state as SetUiState.Success).listNote!!.contains("未能打开"))
        assertEquals(1, navigation)
        assertEquals(0, writes)
    }

    @Test fun T12_root_denied_never_writes_or_adds_history() {
        reset()
        val target = LocalTime.of(7, 30, 17)
        val entry = SleepLogStore.entryNow(LocalTime.now(), target, "NORMAL", 480, "GOOD")
        AlarmDeliveryPrefs(context).clock = StandardClockResolver(context).candidates()
            .first { it.identity.clockPackage == "com.example.phasefixture.first" }
        var writes = 0
        val gateway = object : ClockGateway {
            override fun readSnapshot(): ClockAlarmSnapshot = throw GatewayException(GatewayFailure.ROOT_DENIED, "synthetic permission revoked")
            override fun addOneShot(hour: Int, minute: Int, label: String): AddResult { writes++; error("unexpected write") }
            override fun enable(id: Long): Boolean { writes++; return true }
        }
        lateinit var c: AlarmSetController
        onMain {
            c = AlarmSetController(context, backendFactory = { RootBackend(gateway, {}, { false }) },
                rootIdentity = { AlarmIdentity("synthetic.clock", "synthetic", 1, 0, DeliveryMode.ROOT, "fake") },
                launchStandard = { _, _, _, _ -> true })
            c.selectMode(DeliveryMode.ROOT)
        }
        eventually { !c.checkingRoot }
        onMain {
            c.request(target, "test", entry)
        }
        eventually { c.state is SetUiState.Failed }
        assertEquals(FailureKind.ROOT_DENIED, (c.state as SetUiState.Failed).kind)
        assertEquals(0, writes)
        assertEquals(0, SleepLogStore(context).loadAll().size)
        onMain {
            val failed = c.state as SetUiState.Failed
            c.requestSystemCreate(failed.target, failed.label)
            c.confirmStandard(true)
        }
        assertEquals("manual fallback must retain the planned record even when the input included seconds",
            1, SleepLogStore(context).loadAll().size)
    }

    @Test fun B05_unadapted_system_does_not_load_root_backend() {
        reset()
        var constructed = 0
        lateinit var c: AlarmSetController
        onMain {
            c = AlarmSetController(context, backendFactory = { constructed++; error("must not construct") })
            c.selectMode(DeliveryMode.ROOT)
        }
        eventually { !c.checkingRoot }
        assertEquals(0, constructed)
        assertEquals(DeliveryMode.STANDARD, c.mode)
        assertNotNull(c.rootModeError)
        assertNull(c.lastOperation())
    }

    @Test fun T12_current_authorization_is_required_even_with_a_resident_backend() {
        reset()
        var calls = 0
        val gateway = object : ClockGateway {
            override fun readSnapshot(): ClockAlarmSnapshot { calls++; return ClockAlarmSnapshot.ConfirmedEmpty }
            override fun addOneShot(hour: Int, minute: Int, label: String): AddResult { calls++; error("unexpected write") }
            override fun enable(id: Long): Boolean { calls++; return true }
        }
        lateinit var c: AlarmSetController
        onMain {
            c = AlarmSetController(context, backendFactory = {
                RootBackend(gateway, {}, { false }, verifyAuthorization = {
                    throw GatewayException(GatewayFailure.ROOT_DENIED, "grant revoked but old service still resident")
                }, requestAuthorization = {})
            }, rootIdentity = { AlarmIdentity("synthetic.clock", "synthetic", 1, 0, DeliveryMode.ROOT, "fake") })
            c.selectMode(DeliveryMode.ROOT)
        }
        eventually { !c.checkingRoot }
        onMain {
            c.request(LocalTime.of(7, 30), "test", null)
        }
        eventually { c.state is SetUiState.Failed }
        assertEquals(FailureKind.ROOT_DENIED, (c.state as SetUiState.Failed).kind)
        assertEquals(0, calls)
    }

    @Test fun B02_ordinary_unknown_dispatch_keeps_the_gate_closed() {
        reset()
        val fixture = StandardClockResolver(context).candidates().first { it.identity.clockPackage == "com.example.phasefixture.first" }
        AlarmDeliveryPrefs(context).clock = fixture
        var sends = 0
        lateinit var c: AlarmSetController
        onMain {
            c = AlarmSetController(context, launchStandard = { _, _, _, _ -> sends++; throw IllegalStateException("reply lost") })
            repeat(3) { c.request(LocalTime.of(7, 30), "test", null) }
            assertTrue(c.state is SetUiState.AwaitingUser)
            assertTrue(c.isLocked)
            val restarted = AlarmSetController(context, launchStandard = { _, _, _, _ -> sends++; true })
            restarted.restore()
            restarted.request(LocalTime.of(7, 30), "test", null)
            assertTrue(restarted.state is SetUiState.AwaitingUser)
            restarted.confirmStandard(false)
        }
        assertEquals(1, sends)
        assertEquals(0, SleepLogStore(context).loadAll().size)
    }

    @Test fun T14_existing_clock_editor_is_not_navigated_away_from() {
        reset()
        val target = LocalTime.now().plusHours(1).withSecond(0).withNano(0)
        var next = ZonedDateTime.now().withHour(target.hour).withMinute(target.minute).withSecond(0).withNano(0)
        if (!next.isAfter(ZonedDateTime.now())) next = next.plusDays(1)
        val record = ClockAlarmRecord(7, target.hour, target.minute, "original label", true, "响一次", 0, next.toInstant().toEpochMilli())
        var navigation = 0
        val gateway = object : ClockGateway {
            override fun readSnapshot() = ClockAlarmSnapshot.Complete(listOf(record))
            override fun addOneShot(hour: Int, minute: Int, label: String): AddResult = error("must not write")
            override fun enable(id: Long): Boolean = error("must not write")
        }
        lateinit var c: AlarmSetController
        onMain {
            c = AlarmSetController(context, backendFactory = { RootBackend(gateway, {}, { true }) },
                rootIdentity = { AlarmIdentity("synthetic.clock", "synthetic", 1, 0, DeliveryMode.ROOT, "fake") },
                navigateRoot = { _, _ -> navigation++; true })
            c.selectMode(DeliveryMode.ROOT)
        }
        eventually { !c.checkingRoot }
        onMain {
            c.request(target, "test", null)
        }
        eventually { c.state is SetUiState.Success }
        assertEquals(0, navigation)
        assertTrue((c.state as SetUiState.Success).listNote!!.contains("未保存"))
        assertEquals("original label", record.label)
    }
}
