package com.example.smartalarm.bridge

import android.content.Context
import android.os.Build
import android.os.Process
import com.example.smartalarm.core.interaction.*
import com.example.smartalarm.root.ClockContract
import java.util.Locale

/** No su calls here. The only shipped adapter is the exact historically verified contract. */
object RootCompatibility {
    fun identity(context: Context): AlarmIdentity {
        require(Process.myUid() / 100000 == 0) { "仅支持手机主用户空间，请在主用户中使用" }
        require(Build.VERSION.SDK_INT == 36) { "root 直连目前仅适配 Android 16；此系统请使用普通模式" }
        val locale = Locale.getDefault()
        require(locale.language == "zh" && locale.country == "CN" && locale.script != "Hant") {
            "此 root 适配仅核实过简体中文（中国）的时钟；请使用普通模式"
        }
        val identity = packageIdentity(context, ClockContract.PACKAGE, DeliveryMode.ROOT, "coloros-16.19.14-v1")
        require(identity.versionCode == 16019014L && identity.certificateSha256 == CLOCK_CERTIFICATE) {
            "时钟版本或签名与已核实适配不一致，请使用普通模式"
        }
        val provider = context.packageManager.resolveContentProvider(ClockContract.AUTHORITY, 0)
        require(provider?.packageName == identity.clockPackage && provider.enabled) { "缺少适配的时钟业务接口" }
        return identity
    }

    // apksigner on the archived clock APK; APK hash matches the historical device acquisition.
    private const val CLOCK_CERTIFICATE = "64aafaf1d5bc9155a9e417a849e4f8eda1d0d1341667c28ed7c443c76f820b9a"
}
