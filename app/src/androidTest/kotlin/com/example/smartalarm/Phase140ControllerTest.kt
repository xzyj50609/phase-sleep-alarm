package com.example.smartalarm

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartalarm.bridge.*
import com.example.smartalarm.core.interaction.*
import com.example.smartalarm.core.model.*
import com.example.smartalarm.core.schedule.SleepCalculator
import com.example.smartalarm.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fake gateway only: no su, real clock writes, or device alarm changes. */
@RunWith(AndroidJUnit4::class)
class Phase140ControllerTest {
    @Before fun emulatorOnly() { assumeTrue("Synthetic tests clear app preferences; emulator only",
        android.os.Build.HARDWARE in listOf("ranchu", "goldfish")) }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val identity = AlarmIdentity("synthetic.clock", "synthetic", 1, 0, DeliveryMode.ROOT, "fake")
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun reset() {
        for (name in listOf("alarm_operation", "alarm_inflight", "alarm_delivery", "sleep_log"))
            check(context.getSharedPreferences(name, 0).edit().clear().commit())
    }
    private fun confirmed() = AlarmOperation("previous", identity, 6, 27, "test",
        System.currentTimeMillis() - 20 * 60_000, ZoneId.systemDefault().id, null, OperationStage.VERIFIED)

    @Test fun each_scenario_dispatches_its_own_exact_time_and_duration_to_gateway_and_history() {
        val recommendation = SleepCalculator().calculate(SleepCalculatorInput(
            LocalTime.of(23, 47), mode = AlarmMode.NORMAL, punchTime = null)).recommended
        assertEquals(LocalTime.of(8, 2), recommendation.wakeUpTime)
        for (option in recommendation.wakeOptions) {
            reset()
            val done = CountDownLatch(1)
            var observed: Pair<Int, Int>? = null
            var record: ClockAlarmRecord? = null
            val gateway = object : ClockGateway {
                override fun readSnapshot() = record?.let { ClockAlarmSnapshot.Complete(listOf(it)) }
                    ?: ClockAlarmSnapshot.ConfirmedEmpty
                override fun addOneShot(hour: Int, minute: Int, label: String): AddResult {
                    observed = hour to minute
                    var next = ZonedDateTime.now().withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                    if (!next.isAfter(ZonedDateTime.now())) next = next.plusDays(1)
                    record = ClockAlarmRecord(1, hour, minute, label, true, "响一次", 0, next.toInstant().toEpochMilli())
                    return AddResult.Created(1)
                }
                override fun enable(id: Long): Boolean = error("new alarm path")
            }
            main {
                val controller = AlarmSetController(context,
                    backendFactory = { RootBackend(gateway, {}, { false }) }, rootIdentity = { identity },
                    navigateRoot = { _, _ -> true }, stateObserver = { if (it is SetUiState.Success) done.countDown() })
                controller.selectMode(DeliveryMode.ROOT)
                controller.requestRecommendation(recommendation, LocalTime.of(23, 47), AlarmMode.NORMAL, option)
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(option.time.hour to option.time.minute, observed)
            val entry = SleepLogStore(context).loadAll().single()
            assertEquals(option.time, entry.wakeTime)
            assertEquals(option.sleepMinutes, entry.sleepMinutes)
            assertEquals("verified", entry.confirmationSource)
        }
    }

    @Test fun read_failure_and_real_timeout_are_unknown_and_never_write() {
        for (hanging in listOf(false, true)) {
            reset()
            val release = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val gateway = object : ClockGateway {
                override fun readSnapshot(): ClockAlarmSnapshot {
                    assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                    try {
                        if (!hanging) error("synthetic read failure")
                        while (release.count > 0) {
                            try { release.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                        }
                        return ClockAlarmSnapshot.ConfirmedEmpty
                    } finally { exited.countDown() }
                }
                override fun addOneShot(hour: Int, minute: Int, label: String): AddResult = error("read must not write")
                override fun enable(id: Long): Boolean = error("read must not enable")
            }
            lateinit var controller: AlarmSetController
            main {
                controller = AlarmSetController(context, backendFactory = { RootBackend(gateway, {}, { false }) },
                    rootIdentity = { identity }, previousAlarmTimeoutMillis = 100)
                controller.selectMode(DeliveryMode.ROOT)
            }
            val started = System.nanoTime()
            try {
                val result = runBlocking { withContext(Dispatchers.Main) { controller.checkPreviousAlarm(confirmed()) } }
                assertEquals(PreviousAlarmStatus.UNKNOWN, result)
                assertTrue("blocked gateway must not block timeout", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000)
            } finally {
                release.countDown()
                assertTrue(exited.await(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun persisted_confirmation_survives_new_controller_and_standard_inspection_stays_root_lazy() {
        reset()
        val operation = confirmed().copy(identity = identity.copy(mode = DeliveryMode.STANDARD), stage = OperationStage.USER_CONFIRMED)
        AlarmOperationStore(context).save(operation)
        lateinit var restarted: AlarmSetController
        main {
            restarted = AlarmSetController(context, backendFactory = { error("ordinary must never load root") },
                nextAlarmMillis = { null })
            assertEquals(operation, restarted.lastOperation())
            assertTrue(shouldOfferRecalculation(restarted.lastOperation(), System.currentTimeMillis()))
        }
        assertEquals(PreviousAlarmStatus.INACTIVE,
            runBlocking { withContext(Dispatchers.Main) { restarted.checkPreviousAlarm(operation) } })
        AlarmOperationStore(context).save(operation.copy(stage = OperationStage.WORKING))
        assertEquals(PreviousAlarmStatus.UNKNOWN,
            runBlocking { withContext(Dispatchers.Main) { restarted.checkPreviousAlarm(operation) } })
    }

    @Test fun standard_alarm_service_failure_is_unknown_and_never_loads_root() {
        reset()
        lateinit var controller: AlarmSetController
        main {
            controller = AlarmSetController(context, backendFactory = { error("ordinary must stay root lazy") },
                nextAlarmMillis = { error("synthetic AlarmManager failure") })
        }
        assertEquals(PreviousAlarmStatus.UNKNOWN,
            runBlocking { withContext(Dispatchers.Main) { controller.checkPreviousAlarm(confirmed()) } })
    }
}
