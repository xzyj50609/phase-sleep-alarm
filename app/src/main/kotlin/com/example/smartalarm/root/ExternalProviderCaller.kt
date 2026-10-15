package com.example.smartalarm.root

import android.content.AttributionSource
import android.content.ContentResolver
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import java.lang.reflect.InvocationTargetException

/**
 * root 服务进程由 su 拉起，不是系统登记的“应用进程”，普通 ContentResolver 会报
 * "Unable to find app for caller"。这里走和系统 `content` 命令相同的入口：
 * AMS.getContentProviderExternal → IContentProvider.call/query → removeContentProviderExternalAsUser。
 * 都是隐藏接口，用反射调用；任何一步对不上就抛带步骤名的异常，由调用方当作“不支持/未知”处理，不猜参数。
 */
@androidx.annotation.RequiresApi(31)
internal object ExternalProviderCaller {
    private const val TAG = "phase-bridge"
    private const val CALLING_PACKAGE = "root"
    private const val USER_ID = 0

    fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? =
        withProvider(authority) { provider, source ->
            step("IContentProvider.call") {
                providerMethod("call", 5).invoke(provider, source, authority, method, arg, extras)
            } as Bundle?
        }

    /** 只读：按 selection 数行数。直接查表，出错会抛异常而不是返回空，用来给 get_alarm_list 对账。 */
    fun queryCount(authority: String, path: String, selection: String): Int =
        withProvider(authority) { provider, source ->
            val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection) }
            val cursor = step("IContentProvider.query") {
                providerMethod("query", 5).invoke(
                    provider, source, Uri.parse("content://$authority/$path"), arrayOf("_id"), args, null,
                )
            } as? android.database.Cursor ?: error("query 返回 null")
            cursor.use { it.count }
        }

    private fun <T> withProvider(authority: String, block: (provider: Any, source: AttributionSource) -> T): T {
        val activityManager = step("取得 AMS") {
            Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        } ?: error("AMS 为空")
        val iam = Class.forName("android.app.IActivityManager")
        val token = Binder()

        val holder = step("getContentProviderExternal") {
            iam.methods.first { it.name == "getContentProviderExternal" && it.parameterTypes.size == 4 }
                .invoke(activityManager, authority, USER_ID, token, TAG)
        } ?: error("getContentProviderExternal 返回空：Provider $authority 不存在或不允许")

        try {
            val provider = step("读取 holder.provider") { holder.javaClass.getField("provider").get(holder) }
                ?: error("holder.provider 为空")
            val source = AttributionSource.Builder(android.os.Process.myUid()).setPackageName(CALLING_PACKAGE).build()
            return block(provider, source)
        } finally {
            runCatching {
                iam.methods.first { it.name == "removeContentProviderExternalAsUser" && it.parameterTypes.size == 3 }
                    .invoke(activityManager, authority, token, USER_ID)
            }
        }
    }

    private fun providerMethod(name: String, paramCount: Int) =
        Class.forName("android.content.IContentProvider").methods.first {
            it.name == name && it.parameterTypes.size == paramCount && it.parameterTypes[0] == AttributionSource::class.java
        }

    private inline fun <T> step(name: String, block: () -> T): T = try {
        block()
    } catch (e: InvocationTargetException) {
        val cause = e.targetException ?: e
        throw IllegalStateException("$name 失败: ${cause.javaClass.name}: ${cause.message}", cause)
    } catch (e: NoSuchElementException) {
        throw IllegalStateException("$name 失败: 找不到匹配的方法（系统版本接口变了？）", e)
    } catch (e: ReflectiveOperationException) {
        throw IllegalStateException("$name 失败: ${e.javaClass.name}: ${e.message}", e)
    }
}
