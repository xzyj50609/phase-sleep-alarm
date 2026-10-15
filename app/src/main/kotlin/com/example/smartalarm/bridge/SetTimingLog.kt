package com.example.smartalarm.bridge

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 设闹钟过程的计时与事件记录，写进应用私有文件（这台手机上应用自己的 logcat 输出会被系统吞掉，不能靠它）。
 * 只记事件名、毫秒时间戳、闹钟编号和时刻，不含标签或其它私人内容。超过上限就丢掉旧的一半。
 */
internal object SetTimingLog {
    private const val TAG = "PhaseSet"
    private const val FILE_NAME = "set_timing.txt"
    private const val MAX_BYTES = 64 * 1024

    @Synchronized
    fun record(context: Context, event: String) {
        Log.i(TAG, event)
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.length() > MAX_BYTES) {
                val keep = file.readLines().takeLast(200)
                file.writeText(keep.joinToString("\n", postfix = "\n"))
            }
            file.appendText("${System.currentTimeMillis()} $event\n")
        } catch (_: Exception) {
            // 记录失败不能影响设闹钟本身
        }
    }
}
