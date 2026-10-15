package com.example.smartalarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartalarm.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class BackupRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun export_import_and_interrupted_import_restore_exact_values() {
        val backup = LocalBackup(context)
        val original = backup.export()
        try {
            val prefs = PrefsHelper(context)
            prefs.cycleMinutes = 101
            prefs.punchTime = LocalTime.of(6, 35)
            val log = SleepLogStore(context)
            log.appendConfirmed(SleepLogStore.entryNow(LocalTime.of(23, 40), LocalTime.of(7, 30), "NORMAL", 450, "GOOD"),
                "backup-test", "user-confirmed")
            val expected = backup.export()
            prefs.cycleMinutes = 88
            backup.import(expected)
            assertEquals(expected, backup.export())
            assertEquals(101, prefs.cycleMinutes)
            assertEquals(LocalTime.of(6, 35), prefs.punchTime)
            val beforeMalformed = backup.export()
            assertTrue(runCatching { backup.import(expected.replace("phase-local-backup", "not-phase")) }.isFailure)
            assertEquals(beforeMalformed, backup.export())
            File(context.filesDir, "import-recovery.json").writeText(expected)
            prefs.cycleMinutes = 84
            backup.recoverInterruptedImport()
            assertEquals(expected, backup.export())
        } finally { backup.import(original) }
    }
}
