package com.example.smartalarm.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.example.smartalarm.core.interaction.AddResult
import com.example.smartalarm.core.interaction.ClockAlarmSnapshot
import com.example.smartalarm.core.interaction.ClockGateway
import com.example.smartalarm.core.interaction.GatewayException
import com.example.smartalarm.core.interaction.GatewayFailure
import com.example.smartalarm.core.interaction.classifyAlarmSnapshot
import com.example.smartalarm.core.interaction.parseClockAlarmList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 通过 libsu RootService 调时钟业务接口。所有方法阻塞，只能在后台线程调用。
 * 任何“调用本身没成功”都抛 [GatewayException]，由上层决定是失败还是“结果未知”；
 * 这里不重试写操作，也不回落到无障碍。
 */
class RootClockGateway(context: Context) : ClockGateway {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var bridge: IClockBridge? = null
    private var connection: ServiceConnection? = null
    private var rootConfirmed = false

    /** 首页出现时预先绑定，点击后省掉启动 root 服务的等待。失败静默，点击时会如实报错。 */
    fun prewarm() {
        try {
            verifyAuthorization()
            val b = requireBridge()
            // 只读一次对账计数：顺带把时钟进程提前拉起来。时钟进程不在时，点击后的第一次读取要多花约 450 ms（2026-10-01 实测）
            runCatching { b.countAlarms() }
        } catch (_: GatewayException) { /* 点击时再报告 */ }
    }

    /** A resident UID-0 Binder does not prove the manager still grants this app root. */
    fun verifyAuthorization() {
        try {
            Shell.Builder.create().setTimeout(6).build().use { fresh ->
                if (!fresh.isRoot) {
                    release()
                    rootConfirmed = false
                    throw GatewayException(GatewayFailure.ROOT_DENIED, "相位当前没有 root 授权")
                }
            }
        } catch (e: GatewayException) {
            throw e
        } catch (e: Exception) {
            throw GatewayException(GatewayFailure.UNAVAILABLE, "无法核对当前 root 授权：${e.message}", e)
        }
    }

    fun release() = synchronized(lock) {
        connection?.let { conn -> main.post { runCatching { RootService.unbind(conn) } } }
        connection = null
        bridge = null
    }

    override fun readSnapshot(): ClockAlarmSnapshot {
        val first = readSnapshotOnce()
        if (first !is ClockAlarmSnapshot.Unknown) return first
        Thread.sleep(RETRY_DELAY_MS) // 读列表与独立计数之间恰好有人改了闹钟时会对不上；只重试一次
        return readSnapshotOnce()
    }

    private fun readSnapshotOnce(): ClockAlarmSnapshot {
        val (list, count) = call { it.readAlarmList() to it.countAlarms() }
        list.getString(ClockRootService.KEY_ERROR)?.let { throw GatewayException(GatewayFailure.CALL_FAILED, "读列表失败：$it") }
        val response = list.getBundle(ClockRootService.KEY_RESPONSE)
            ?: throw GatewayException(GatewayFailure.CALL_FAILED, "时钟没有返回列表内容")
        val independent = count.takeIf { it.containsKey(ClockRootService.KEY_COUNT) }?.getInt(ClockRootService.KEY_COUNT)
        return classifyAlarmSnapshot(parseClockAlarmList(response.toRawClockAlarmList()), independent)
    }

    override fun addOneShot(hour: Int, minute: Int, label: String): AddResult {
        val response = writeResponse(call { it.addAlarm(hour, minute, label) }, "创建")
        if (!response.containsKey("result")) throw GatewayException(GatewayFailure.CALL_FAILED, "创建回执缺少结果码")
        val code = response.getInt("result", Int.MIN_VALUE)
        if (code != 1) return AddResult.Failed(code)
        val id = response.getLongArray("alarm_id_list")?.singleOrNull()
            ?: throw GatewayException(GatewayFailure.CALL_FAILED, "创建回执里没有编号")
        return AddResult.Created(id)
    }

    /** 时钟里是否有未保存的新建/编辑面板。调用失败按“有”处理，宁可不动用户的现场。 */
    fun isClockEditorOpen(): Boolean = try {
        call { it.isClockEditorOpen() }
    } catch (_: GatewayException) {
        true
    }

    override fun enable(id: Long): Boolean {
        val response = writeResponse(call { it.enableAlarm(id) }, "启用")
        return response.getInt("result", Int.MIN_VALUE) == 1
    }

    private fun writeResponse(out: Bundle, what: String): Bundle {
        out.getString(ClockRootService.KEY_ERROR)?.let { throw GatewayException(GatewayFailure.CALL_FAILED, "$what 调用出错：$it") }
        return out.getBundle(ClockRootService.KEY_RESPONSE)
            ?: throw GatewayException(GatewayFailure.CALL_FAILED, "$what 没有回执")
    }

    private inline fun <T> call(block: (IClockBridge) -> T): T {
        val b = requireBridge()
        try {
            return block(b)
        } catch (e: RemoteException) {
            bridge = null
            throw GatewayException(GatewayFailure.CALL_FAILED, "root 服务通信中断：${e.message}", e)
        } catch (e: RuntimeException) {
            throw GatewayException(GatewayFailure.CALL_FAILED, "${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun requireBridge(): IClockBridge = synchronized(lock) {
        if (android.os.Build.VERSION.SDK_INT < 31) throw GatewayException(GatewayFailure.UNAVAILABLE, "此系统不支持 root 直连，请使用普通模式")
        bridge?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        confirmRoot()
        val holder = AtomicReference<IClockBridge?>()
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                IClockBridge.Stub.asInterface(binder).also { holder.set(it); bridge = it }
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { bridge = null }
        }
        connection?.let { old -> main.post { runCatching { RootService.unbind(old) } } }
        main.post { RootService.bind(Intent(appContext, ClockRootService::class.java), conn) }
        if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            main.post { runCatching { RootService.unbind(conn) } }
            rootConfirmed = false
            throw GatewayException(GatewayFailure.UNAVAILABLE, "root 服务 ${BIND_TIMEOUT_MS / 1000} 秒内没有连上")
        }
        connection = conn
        holder.get() ?: throw GatewayException(GatewayFailure.UNAVAILABLE, "root 服务连上后没有返回接口")
    }

    private fun confirmRoot() {
        if (rootConfirmed) return
        val shell = try {
            Shell.getShell()
        } catch (t: Throwable) {
            throw GatewayException(GatewayFailure.UNAVAILABLE, "无法启动 su：${t.message}", t)
        }
        if (!shell.isRoot) throw GatewayException(GatewayFailure.ROOT_DENIED, "相位没有拿到 root 授权")
        rootConfirmed = true
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 6_000L
        const val RETRY_DELAY_MS = 120L
    }
}
