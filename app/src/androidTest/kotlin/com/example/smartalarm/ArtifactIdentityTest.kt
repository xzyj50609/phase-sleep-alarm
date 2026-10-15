package com.example.smartalarm

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import android.os.Bundle

@RunWith(AndroidJUnit4::class)
class ArtifactIdentityTest {
    @Test fun installed_apk_is_the_exact_candidate_with_no_debug_or_network_entry() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val args = InstrumentationRegistry.getArguments()
        val digest = MessageDigest.getInstance("SHA-256")
        File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        args.getString("expectedApkSha")?.let { assertEquals("Installed APK was rewritten or differs from candidate", it, sha) }
        if (args.getString("expectedRelease") == "true") {
            assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
            assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        }
        @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_ACTIVITIES)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        assertFalse(info.activities.orEmpty().any { it.name.contains(".debug.") })
        if (args.getString("expectedRelease") == "true") assertFalse(info.services.orEmpty().any { it.name.endsWith("AlarmAssistService") })
        i.sendStatus(0, Bundle().apply { putString("phaseInstalledApkSha256", sha) })
    }
}
