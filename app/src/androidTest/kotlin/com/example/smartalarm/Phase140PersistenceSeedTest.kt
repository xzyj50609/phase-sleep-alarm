package com.example.smartalarm

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartalarm.core.interaction.*
import com.example.smartalarm.data.AlarmDeliveryPrefs
import com.example.smartalarm.data.AlarmOperationStore
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/** Fixture for the host-side process-death check. It never dispatches an alarm. */
@RunWith(AndroidJUnit4::class)
class Phase140PersistenceSeedTest {
    @Test fun seed_confirmed_operation_twenty_minutes_ago() {
        assumeTrue("Emulator-only fixture", Build.HARDWARE in listOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (name in listOf("alarm_operation", "alarm_inflight", "alarm_delivery", "sleep_log", "user_prefs"))
            check(context.getSharedPreferences(name, 0).edit().clear().commit())
        AlarmDeliveryPrefs(context).mode = DeliveryMode.STANDARD
        AlarmOperationStore(context).save(AlarmOperation("persisted-restart-fixture",
            AlarmIdentity("synthetic.clock", "fake", 1, 0, DeliveryMode.STANDARD, "fixture"),
            6, 27, "synthetic", System.currentTimeMillis() - 20 * 60_000,
            ZoneId.systemDefault().id, null, OperationStage.USER_CONFIRMED))
    }
}
