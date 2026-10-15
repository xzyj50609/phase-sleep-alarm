package com.example.smartalarm.root

import android.content.Context
import android.content.pm.ApplicationInfo
import com.example.smartalarm.core.interaction.AddResult
import com.example.smartalarm.core.interaction.ClockAlarmSnapshot
import com.example.smartalarm.core.interaction.ClockGateway
import com.example.smartalarm.core.interaction.GatewayException
import com.example.smartalarm.core.interaction.GatewayFailure
import java.io.File

/**
 * 仅供验收：让真机能跑到“读不清 / 创建回执丢失 / 一直卡住”这些自然很难制造的分支。
 * 只有应用自带 DEBUGGABLE 标志（调试包）时才会读 files/fault.txt，发布包上 [wrap] 原样返回，这里的代码不会执行。
 * fault.txt 内容（单行，调用一次后保持，直到文件被删）：
 *   hang_read        读列表前阻塞 30 秒
 *   snapshot_unknown 读列表返回“无法确认”
 *   add_lost         真的创建，但创建后抛异常（回执丢失）
 */
internal class FaultInjectingGateway(private val context: Context, private val real: ClockGateway) : ClockGateway {
    override fun readSnapshot(): ClockAlarmSnapshot {
        when (fault()) {
            "hang_read" -> Thread.sleep(HANG_MS)
            "snapshot_unknown" -> return ClockAlarmSnapshot.Unknown("【验收注入】读列表无法确认")
        }
        return real.readSnapshot()
    }

    override fun addOneShot(hour: Int, minute: Int, label: String): AddResult {
        val result = real.addOneShot(hour, minute, label)
        if (fault() == "add_lost") throw GatewayException(GatewayFailure.CALL_FAILED, "【验收注入】创建后回执丢失")
        return result
    }

    override fun enable(id: Long): Boolean = real.enable(id)

    private fun fault(): String? = runCatching { File(context.filesDir, FAULT_FILE).readText().trim() }
        .getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val FAULT_FILE = "fault.txt"
        private const val HANG_MS = 30_000L

        fun wrap(context: Context, real: ClockGateway): ClockGateway {
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            return if (debuggable) FaultInjectingGateway(context, real) else real
        }
    }
}
