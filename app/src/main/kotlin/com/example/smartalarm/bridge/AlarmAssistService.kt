package com.example.smartalarm.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.smartalarm.core.interaction.AlarmScanGuard
import com.example.smartalarm.core.interaction.ClockAlarmRow
import com.example.smartalarm.core.interaction.parseClockTime
import com.example.smartalarm.core.interaction.reusableAlarmIndex
import java.time.LocalTime
import java.util.Locale

/** Idle unless explicitly started. Reads/actions are restricted to the verified clock package. */
class AlarmAssistService : AccessibilityService() {
    private enum class Phase { REWIND, SCAN, VERIFY_ENABLED, VERIFY_DETAIL }
    private data class Session(
        val time: LocalTime, val label: String, val onVerified: () -> Unit,
        val started: Long = SystemClock.elapsedRealtime(),
        var phase: Phase = Phase.REWIND,
        var guard: AlarmScanGuard = AlarmScanGuard(),
        var created: Boolean = false, var enabledByUs: Boolean = false,
        var summary: String = "", var polls: Int = 0, var scrolls: Int = 0,
        var boundarySignature: String? = null, var boundaryObservations: Int = 0,
        var priorPage: Set<String>? = null,
        var waitingForChange: String? = null, var unchangedPolls: Int = 0,
        var firstVisibleIndex: Int = -1, var lastVisibleIndex: Int = -1, var itemCount: Int = -1,
        var primeScroll: Boolean = true, var topConfirmed: Boolean = false, var bottomConfirmed: Boolean = false,
        var lastScrollAt: Long = 0, var baselineCount: Int = -1,
    ) {
        val key: String get() = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
    }
    private data class UiRow(val node: AccessibilityNodeInfo, val toggle: AccessibilityNodeInfo?, val value: ClockAlarmRow) {
        val identity: String get() = "${value.hour}:${value.minute}|${value.summary}"
    }
    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private val step = Runnable { tickSafely() }
    private val watchdog = Runnable { if (session != null) stop("操作超时，辅助已停止；请手动确认闹钟。") }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val task = session ?: return
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.packageName?.toString() == CLOCK_PACKAGE &&
            event.source?.viewIdResourceName == "android:id/list") {
            task.firstVisibleIndex = event.fromIndex
            task.lastVisibleIndex = event.toIndex
            task.itemCount = event.itemCount
            task.lastScrollAt = SystemClock.elapsedRealtime()
        }
    }
    override fun onInterrupt() { stop("辅助被中断；请手动确认闹钟。") }
    override fun onDestroy() {
        if (session != null) stop("辅助服务已停止；请手动确认闹钟。")
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun schedule(delay: Long = 350) {
        handler.removeCallbacks(step)
        if (session != null) handler.postDelayed(step, delay)
    }
    private fun tickSafely() {
        try { tick() } catch (e: Exception) {
            Log.w(TAG, "Adapter stopped: ${e.javaClass.simpleName}")
            stop("时钟页面无法可靠识别，已停止；不会追加创建请求。")
        }
    }
    private fun tick() {
        val task = session ?: return
        if (SystemClock.elapsedRealtime() - task.started > 150_000 || task.scrolls > 160) {
            stop("查找超时，已停止；请手动确认闹钟。"); return
        }
        // ColorOS has a long elastic rebound. Never read/click or start another swipe during it.
        if (SystemClock.elapsedRealtime() - task.lastScrollAt < 300) { schedule(300); return }
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != CLOCK_PACKAGE) {
            if (++task.polls > 10) stop("已离开系统时钟或页面不可读，辅助已停止。") else schedule()
            return
        }
        if (task.phase == Phase.VERIFY_DETAIL) { verifyDetail(root, task); return }
        if (find(root, "alarm_label").any { it.isVisibleToUser }) {
            stop("时钟有未关闭的编辑页。请点“完成”或“取消”，再返回重试。", BridgePath.NEEDS_CONFIRMATION); return
        }
        val list = root.findAccessibilityNodeInfosByViewId("android:id/list").firstOrNull { it.isVisibleToUser }
        if (list == null) {
            if (++task.polls > 10) stop("未识别到闹钟列表，已停止，未创建。") else schedule()
            return
        }
        val rows = readRows(list) ?: run { stop("闹钟行结构变化，辅助已停止；未盲目创建。"); return }
        if (rows.isEmpty()) {
            // An empty parse never proves absence. Empty-list layout has not been verified.
            stop("未能读取闹钟行；请手动确认，辅助不会把空结果当成不存在。"); return
        }
        val signature = rows.joinToString("\n") {
            it.identity + "|" + it.value.enabled + "|" + Rect().also(it.node::getBoundsInScreen)
        }
        if (task.waitingForChange != null) {
            if (signature == task.waitingForChange) {
                if (++task.unchangedPolls < 3) { schedule(); return }
                when {
                    task.phase == Phase.REWIND && task.itemCount > 0 && task.firstVisibleIndex == 0 -> task.topConfirmed = true
                    task.phase == Phase.SCAN && task.itemCount > 0 && task.lastVisibleIndex == task.itemCount - 1 -> task.bottomConfirmed = true
                    else -> { stop("列表未按预期滚动，已停止。"); return }
                }
            }
            task.waitingForChange = null
            task.unchangedPolls = 0
        }
        task.polls = 0
        when (task.phase) {
            Phase.REWIND -> {
                if (task.primeScroll) {
                    // ColorOS advertises wrong scroll actions. Obtain real RecyclerView indices first.
                    task.primeScroll = false
                    scrollOverlapping(list, task)
                } else if (tryUseRow(rows, task)) {
                    // Finding an existing row needs no proof that the rest of the list is empty.
                } else if (!task.topConfirmed) {
                    task.boundarySignature = null
                    task.boundaryObservations = 0
                    task.waitingForChange = signature
                    scrollOverlapping(list, task, forward = false)
                } else if (confirmedBoundary(task, signature)) {
                    task.guard.reachedTop()
                    if (task.baselineCount < 0) task.baselineCount = task.itemCount
                    task.phase = Phase.SCAN
                    task.boundarySignature = null
                    task.boundaryObservations = 0
                    schedule(100)
                } else schedule()
            }
            Phase.SCAN -> {
                val identities = rows.map { it.identity }.toSet()
                if (task.priorPage?.intersect(identities)?.isEmpty() == true) {
                    stop("滚动覆盖不连续，无法确认不存在；已停止。"); return
                }
                task.priorPage = null
                task.guard.observedPage(rows.any { it.value.matches(task.time.hour, task.time.minute) })
                if (tryUseRow(rows, task)) {
                } else if (!task.bottomConfirmed) {
                    task.boundarySignature = null
                    task.boundaryObservations = 0
                    task.priorPage = identities
                    task.waitingForChange = signature
                    scrollOverlapping(list, task)
                } else if (confirmedBoundary(task, signature)) {
                    if (task.created) stop("创建请求已发送，但未确认新闹钟；请手动检查，勿连续创建。")
                    else if (task.guard.authorizeCreation(true)) createOnce(task)
                    else stop("同时间存在循环或无法确认规则的闹钟，请手动选择；未新建。", BridgePath.NEEDS_CONFIRMATION)
                } else schedule()
            }
            Phase.VERIFY_ENABLED -> {
                val row = rows.firstOrNull {
                    it.value.matches(task.time.hour, task.time.minute) && it.value.summary == task.summary && it.value.enabled
                }
                // Never toggle twice: that could disable the alarm again.
                if (row == null) stop("未确认闹钟已开启，请手动核对；不会重复切换开关。")
                else openRow(row, task)
            }
            Phase.VERIFY_DETAIL -> Unit
        }
    }

    private fun confirmedBoundary(task: Session, signature: String): Boolean {
        if (task.boundarySignature == signature) task.boundaryObservations++
        else { task.boundarySignature = signature; task.boundaryObservations = 1 }
        return task.boundaryObservations >= 2
    }
    private fun tryUseRow(rows: List<UiRow>, task: Session): Boolean {
        val index = reusableAlarmIndex(rows.map { it.value }, task.time.hour, task.time.minute) ?: return false
        if (task.itemCount < 0) return false
        if (task.baselineCount < 0) task.baselineCount = task.itemCount
        val row = rows[index]
        if (task.created && row.value.summary.substringAfter('|', "").trim() != task.label) {
            stop("出现同时间的其他闹钟，请手动确认；不会再次创建。"); return true
        }
        task.summary = row.value.summary
        if (row.value.enabled) openRow(row, task)
        else {
            task.phase = Phase.VERIFY_ENABLED
            // This ROM persists state on the touch path; ACTION_CLICK can change only the widget.
            val toggle = row.toggle
            if (toggle == null || !toggle.refresh() || !toggle.isVisibleToUser) {
                stop("闹钟开关位置已变化，已停止。"); return true
            }
            if (toggle.isChecked) { openRow(row, task); return true }
            val bounds = Rect().also(toggle::getBoundsInScreen)
            val rowBounds = Rect().also(row.node::getBoundsInScreen)
            if (bounds.isEmpty || !rowBounds.contains(bounds.centerX(), bounds.centerY())) {
                stop("无法安全定位闹钟开关，已停止。"); return true
            }
            val tap = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
            task.enabledByUs = true
            if (!dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(tap, 0, 80)).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (session === task) schedule(900) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { if (session === task) stop("开关操作被打断，请手动核对。") }
                }, handler)) stop("无法启用现有闹钟，请手动开启；未创建重复项。")
        }
        return true
    }
    private fun scrollOverlapping(list: AccessibilityNodeInfo, task: Session, forward: Boolean = true) {
        val bounds = Rect().also(list::getBoundsInScreen)
        if (bounds.height() < 600 || bounds.width() < 200) { stop("列表区域异常，已停止。"); return }
        val x = bounds.left + bounds.width() * 0.55f
        val endY = bounds.top + bounds.height() * if (forward) 0.20f else 0.88f
        val path = Path().apply {
            moveTo(x, bounds.top + bounds.height() * if (forward) 0.80f else 0.18f)
            lineTo(x, endY)
        }
        task.scrolls++
        val stroke = GestureDescription.StrokeDescription(path, 0, if (forward) 240 else 180, forward)
        val accepted = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (!forward) { if (session === task) schedule(350); return }
                    // Hold before releasing, otherwise ColorOS flings past unread rows.
                    val holdPath = Path().apply { moveTo(x, endY); lineTo(x, endY + 1f) }
                    val release = stroke.continueStroke(holdPath, 0, 180, false)
                    val released = dispatchGesture(GestureDescription.Builder().addStroke(release).build(),
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) { if (session === task) schedule(350) }
                            override fun onCancelled(gestureDescription: GestureDescription?) { if (session === task) stop("滚动被打断，已停止。") }
                        }, handler)
                    if (!released && session === task) stop("滚动收尾失败，辅助已停止。")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (session === task) stop("滚动被打断，已停止。") }
            }, handler)
        if (!accepted) stop("无法滚动时钟列表，已停止。")
    }
    private fun openRow(row: UiRow, task: Session) {
        var clickable: AccessibilityNodeInfo? = listOf("alarm_item_card_bg", "digital_clock", "timeDisplay")
            .flatMap { find(row.node, it) }.firstOrNull { it.isClickable } ?: row.node
        repeat(3) { if (clickable?.isClickable != true) clickable = clickable?.parent }
        if (clickable == null || clickable?.viewIdResourceName == "android:id/list" || clickable?.isClickable != true) {
            stop("已找到闹钟，但无法安全打开详情；请手动确认。"); return
        }
        task.phase = Phase.VERIFY_DETAIL
        task.polls = 0
        if (!clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) stop("无法打开闹钟详情，请手动确认。")
        else schedule(600)
    }
    private fun verifyDetail(root: AccessibilityNodeInfo, task: Session) {
        val pickers = descendants(root).filter { it.isVisibleToUser && it.className?.toString()?.endsWith("COUINumberPicker") == true }
        val correctTime = pickers.any { it.text?.toString()?.removeSuffix("时")?.toIntOrNull() == task.time.hour } &&
            pickers.any { it.text?.toString()?.removeSuffix("分")?.toIntOrNull() == task.time.minute }
        val oneShot = find(root, "alarm_type_tab_item").any { it.text?.toString() == "响一次" && it.isChecked }
        if (correctTime && oneShot && find(root, "alarm_label").any { it.isVisibleToUser }) {
            // Read only: never send showIntent (this ROM supplies a scheduling broadcast there).
            val next = getSystemService(AlarmManager::class.java)?.nextAlarmClock
            if (next == null) {
                if (++task.polls > 8) stop("系统尚未报告待响铃闹钟，未确认成功；请在时钟内手动核对。")
                else schedule()
                return
            }
            val expectedCount = task.baselineCount + if (task.created) 1 else 0
            if (task.baselineCount < 0 || task.itemCount != expectedCount) {
                stop("闹钟数量发生了额外变化，请手动确认；不会再创建。"); return
            }
            val action = if (task.created) "已创建" else if (task.enabledByUs) "已重新启用" else "已复用"
            finish(BridgeResult(true, BridgePath.VERIFIED, "$action ${task.key}，已打开对应详情。请确认铃声和音量。"), true)
        } else if (++task.polls > 8) stop("闹钟开关已核对，但详情未通过验证；请手动确认时间和铃声。")
        else schedule()
    }
    private fun createOnce(task: Session) {
        task.created = true // Before dispatch; never retry an uncertain create.
        task.phase = Phase.REWIND
        task.guard = AlarmScanGuard()
        task.boundarySignature = null
        task.boundaryObservations = 0
        task.priorPage = null
        task.waitingForChange = null
        task.firstVisibleIndex = -1
        task.lastVisibleIndex = -1
        task.itemCount = -1
        task.primeScroll = true
        task.topConfirmed = false
        task.bottomConfirmed = false
        publish(BridgeResult(true, BridgePath.ASSISTING, "列表中未找到 ${task.key}，正在创建并核对；请勿操作时钟。"))
        try {
            startActivity(Intent(AlarmClock.ACTION_SET_ALARM).setPackage(CLOCK_PACKAGE)
                .putExtra(AlarmClock.EXTRA_HOUR, task.time.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, task.time.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, task.label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            schedule(900)
        } catch (_: Exception) { stop("设置请求未能完成，请手动检查；不会自动重发。") }
    }
    private fun readRows(list: AccessibilityNodeInfo): List<UiRow>? {
        val rows = mutableListOf<UiRow>()
        for (node in find(list, "content_layout").filter { it.isVisibleToUser }) {
            val timeNode = find(node, "timeDisplay").firstOrNull() ?: continue
            val time = parseClockTime(timeNode.text?.toString().orEmpty()) ?: return null
            val summary = find(node, "daysOfWeek_label").firstOrNull()?.text?.toString().orEmpty()
            val toggle = find(node, "alarm_switch").firstOrNull()
            if (toggle != null && !toggle.isCheckable) return null
            // Keep clipped time-only rows as evidence of existence, never as a clickable candidate.
            rows += UiRow(node, toggle, ClockAlarmRow(time.first, time.second,
                if (toggle != null) summary else "", toggle?.isChecked == true))
        }
        if (rows.isNotEmpty() && rows.none { it.toggle != null && it.value.summary.isNotEmpty() }) return null
        return rows
    }
    private fun descendants(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < 800) {
            val node = queue.removeFirst()
            result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return result
    }
    private fun find(root: AccessibilityNodeInfo, id: String) = root.findAccessibilityNodeInfosByViewId("$CLOCK_PACKAGE:id/$id")
    private fun stop(message: String, path: BridgePath = BridgePath.FAILED) = finish(BridgeResult(false, path, message), false)
    private fun finish(result: BridgeResult, verified: Boolean) {
        val task = session
        session = null
        handler.removeCallbacks(step)
        handler.removeCallbacks(watchdog)
        publish(result, task)
        if (task != null) {
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            if (verified) task.onVerified()
        }
    }
    private fun publish(result: BridgeResult, task: Session? = session) {
        lastResult = result
        // Only the requested time and outcome are persisted, not the clock list.
        getSharedPreferences("alarm_assist_status", MODE_PRIVATE).edit()
            .putString("state", result.path.name).putString("message", result.message)
            .putInt("countBefore", task?.baselineCount ?: -1).putInt("countAfter", task?.itemCount ?: -1)
            .putBoolean("created", task?.created == true)
            .putLong("updatedAt", System.currentTimeMillis()).apply()
        Log.i(TAG, "${result.path}: ${result.message}")
    }
    companion object {
        const val CLOCK_PACKAGE = "com.coloros.alarmclock"
        private const val VERIFIED_CLOCK_VERSION = 16019014L
        private const val TAG = "AlarmAssist"
        private var instance: AlarmAssistService? = null
        var lastResult by mutableStateOf<BridgeResult?>(null)
            private set
        fun cancel() { instance?.takeIf { it.session != null }?.stop("已取消辅助；请自行确认已有闹钟状态。") }
        fun clockSnapshotForDebug(): String {
            val service = instance ?: return "Service not connected"
            if (service.session != null) return "Busy; do not inspect during an operation"
            val root = service.rootInActiveWindow ?: return "No active window"
            if (root.packageName?.toString() != CLOCK_PACKAGE) return "Not the clock window"
            return service.descendants(root).filter {
                it.viewIdResourceName?.let { id -> id.endsWith(":id/list") ||
                    id.substringAfterLast('/') in setOf("content_layout", "alarm_item_card_bg", "timeDisplay", "alarm_switch", "save", "cancel", "alarm_label", "alarm_type_tab_item") } == true ||
                    it.className?.toString()?.endsWith("COUINumberPicker") == true
            }.joinToString("\n") {
                val bounds = Rect().also(it::getBoundsInScreen)
                "${it.viewIdResourceName} text=${it.text} class=${it.className} clickable=${it.isClickable} checkable=${it.isCheckable} checked=${it.isChecked} visible=${it.isVisibleToUser} bounds=$bounds actions=${it.actionList.map { action -> action.id }} rows=${it.collectionInfo?.rowCount}"
            }
        }
        fun request(context: Context, time: LocalTime, label: String, onVerified: () -> Unit = {}): BridgeResult {
            fun reject(path: BridgePath, message: String) = BridgeResult(false, path, message).also { lastResult = it }
            val version = try {
                val info = context.packageManager.getPackageInfo(CLOCK_PACKAGE, 0)
                if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            } catch (_: Exception) { return reject(BridgePath.FAILED, "未找到支持的 ColorOS 系统时钟，请手动设置。") }
            if (version != VERIFIED_CLOCK_VERSION) return reject(BridgePath.NEEDS_CONFIRMATION,
                "系统时钟版本已变化，自动辅助已暂停；请复测后适配。可手动打开时钟。")
            val service = instance ?: return reject(BridgePath.NEEDS_ACCESSIBILITY,
                "请先开启“系统时钟辅助”。只在你点击设置时读取和操作时钟，不读取其他应用；开启后返回再点一次。")
            if (service.session != null) return lastResult ?: reject(BridgePath.NEEDS_CONFIRMATION, "已有操作进行中，请勿重复点击。")
            service.session = Session(time.withSecond(0).withNano(0), label, onVerified)
            service.handler.removeCallbacks(service.watchdog)
            service.handler.postDelayed(service.watchdog, 150_000)
            service.publish(BridgeResult(true, BridgePath.ASSISTING, "正在查找 ${service.session!!.key}；请等待，暂勿操作时钟。"))
            if (!SystemAlarmBridge().openList(context)) service.stop("无法打开系统时钟；未创建闹钟。")
            else service.schedule(800)
            return lastResult!!
        }
    }
}
