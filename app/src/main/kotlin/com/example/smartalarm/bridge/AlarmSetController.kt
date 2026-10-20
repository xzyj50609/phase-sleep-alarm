package com.example.smartalarm.bridge

import android.content.Context
import android.app.AlarmManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.smartalarm.core.interaction.*
import com.example.smartalarm.data.*
import com.example.smartalarm.root.FaultInjectingGateway
import com.example.smartalarm.root.RootClockGateway
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val PREVIOUS_ALARM_TIMEOUT_MILLIS = 5_000L

sealed interface SetUiState {
    data object Idle : SetUiState
    data class Working(val target: LocalTime) : SetUiState
    data class Success(val target: LocalTime, val how: SetHow, val listNote: String? = null, val late: Boolean = false) : SetUiState
    data class Failed(val target: LocalTime, val label: String, val kind: FailureKind, val detail: String) : SetUiState
    data class AwaitingUser(val target: LocalTime, val clockName: String, val manualTime: Boolean = false) : SetUiState
    data class UserConfirmed(val target: LocalTime) : SetUiState
    data class Uncertain(val target: LocalTime, val detail: String, val running: Boolean = false) : SetUiState
    data class Choosing(val choices: List<ClockChoice>, val detail: String) : SetUiState
}

/** Injectable narrow seam for instrumentation; production always uses the real libsu backend. */
internal data class RootBackend(
    val gateway: ClockGateway, val prewarm: () -> Unit, val editorOpen: () -> Boolean,
    val verifyAuthorization: () -> Unit = {}, val release: () -> Unit = {},
    val requestAuthorization: () -> Unit = verifyAuthorization,
)

/** All public entry points and screen state changes run on the main thread. Writes share one durable gate. */
class AlarmSetController internal constructor(
    context: Context,
    private val backendFactory: (() -> RootBackend)? = null,
    private val rootIdentity: (() -> AlarmIdentity)? = null,
    private val navigateRoot: ((Long, Boolean) -> Boolean)? = null,
    private val launchStandard: ((ClockChoice, Int, Int, String) -> Boolean)? = null,
    private val watchdogMillis: Long = 10_000L,
    private val stateObserver: ((SetUiState) -> Unit)? = null,
    private val previousAlarmTimeoutMillis: Long = PREVIOUS_ALARM_TIMEOUT_MILLIS,
    private val nextAlarmMillis: (() -> Long?)? = null,
    private val rootAuthorizationTimeoutMillis: Long = 45_000L,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = AlarmDeliveryPrefs(app)
    private val inFlight = PrefsInFlightStore(app)
    private val gate = AlarmOperationCoordinator(AlarmOperationStore(app))
    private val sleepLog = SleepLogStore(app)
    private val clocks = StandardClockResolver(app)
    private val worker = Executors.newSingleThreadExecutor()
    private val warmer = Executors.newSingleThreadExecutor()
    private val inspector = Executors.newSingleThreadExecutor()
    private val authorizer = Executors.newSingleThreadExecutor()
    private var authorizationAttempt = 0
    @Volatile private var createdBackend: RootBackend? = null
    private val backend by lazy {
        (backendFactory?.invoke() ?: RootClockGateway(app).let { real ->
            RootBackend(FaultInjectingGateway.wrap(app, real), real::prewarm, real::isClockEditorOpen,
                real::verifyAuthorization, real::release, real::requestAuthorization)
        }).also { createdBackend = it }
    }
    private var requestForChoice: Request? = null
    private var lastRequest: Request? = null
    @Volatile private var running = false
    private var storageError: String? = null

    var state: SetUiState by mutableStateOf(SetUiState.Idle)
        private set
    // A saved preference is not a current grant. Restore it only after checking.
    var mode: DeliveryMode by mutableStateOf(DeliveryMode.STANDARD)
        private set
    var checkingRoot: Boolean by mutableStateOf(false)
        private set
    var rootModeError: String? by mutableStateOf(null)
        private set
    var clockName: String by mutableStateOf(prefs.clock?.label ?: "尚未选择时钟")
        private set
    val requiresManualTime: Boolean get() = mode == DeliveryMode.STANDARD && prefs.clock?.requiresManualTime == true

    val isLocked: Boolean get() = checkingRoot || running || storageError != null ||
        runCatching { gate.current()?.stage?.terminal == false || inFlight.load() != null }.getOrDefault(true)

    private data class Request(val target: LocalTime, val label: String, val log: SleepLogEntry?)

    fun lastOperation(): AlarmOperation? = runCatching { gate.current() }.getOrNull()

    /** Read-only, bounded independently of a blocking binder call; never occupies the write worker. */
    suspend fun checkPreviousAlarm(operation: AlarmOperation, now: Instant = Instant.now()): PreviousAlarmStatus {
        if (isLocked) return PreviousAlarmStatus.UNKNOWN
        val delivery = mode
        val zone = ZoneId.systemDefault()
        val target = operation.time()
        val result = withTimeoutOrNull(previousAlarmTimeoutMillis) {
            suspendCancellableCoroutine<PreviousAlarmStatus> { continuation ->
                val future = inspector.submit {
                    val status = runCatching {
                        if (delivery == DeliveryMode.ROOT) {
                            // A snapshot from a different clock cannot establish absence in the old clock.
                            if (checkedRootIdentity().clockPackage != operation.identity.clockPackage) PreviousAlarmStatus.UNKNOWN
                            else {
                                backend.verifyAuthorization()
                                rootPreviousAlarmStatus(backend.gateway.readSnapshot(), target)
                            }
                        } else {
                            val next = nextAlarmMillis?.invoke() ?: if (nextAlarmMillis == null)
                                checkNotNull(app.getSystemService(AlarmManager::class.java)).nextAlarmClock?.triggerTime else null
                            standardPreviousAlarmStatus(next, target, now, zone)
                        }
                    }.getOrDefault(PreviousAlarmStatus.UNKNOWN)
                    if (continuation.isActive) continuation.resume(status)
                }
                continuation.invokeOnCancellation { future.cancel(true) }
            }
        } ?: PreviousAlarmStatus.UNKNOWN
        return if (isLocked || mode != delivery || ZoneId.systemDefault() != zone) PreviousAlarmStatus.UNKNOWN else result
    }

    private fun publish(value: SetUiState) {
        check(Looper.myLooper() == Looper.getMainLooper())
        state = value
        stateObserver?.invoke(value)
    }

    /** Does not construct or start root in ordinary mode, including restored installations. */
    fun prepare() {
        restore()
        if (prefs.mode == DeliveryMode.ROOT && mode != DeliveryMode.ROOT && !isLocked) {
            selectMode(DeliveryMode.ROOT)
            return
        }
        if (mode == DeliveryMode.ROOT && !isLocked && runCatching { checkedRootIdentity() }.isSuccess) {
            warmer.execute {
                if (prefs.mode == DeliveryMode.ROOT) runCatching { backend.prewarm() }
            }
        }
    }

    fun restore() {
        if (running) return
        try {
            val op = gate.current()
            when {
                op?.stage == OperationStage.AWAITING_USER -> publish(SetUiState.AwaitingUser(op.time(), op.identity.clockPackage,
                    op.identity.clockPackage == "com.coloros.alarmclock"))
                op != null && !op.stage.terminal -> publish(SetUiState.Uncertain(op.time(), "上次操作结果尚未确认。请核对原时钟；相位不会重新发送。"))
                inFlight.load() != null -> publish(SetUiState.Uncertain(LocalTime.of(inFlight.load()!!.hour, inFlight.load()!!.minute),
                    "发现旧版本未决记录，无法确定其时钟身份。请先手动核对原时钟，再结束此操作。"))
                op?.stage?.confirmed == true -> saveHistory(op)
            }
        } catch (t: Exception) {
            storageError = "操作记录无法读取，已停止新建。请先导出备份并核对时钟：${t.javaClass.simpleName}"
            publish(SetUiState.Uncertain(LocalTime.now(), storageError!!))
        }
    }

    fun selectMode(value: DeliveryMode): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (value == DeliveryMode.STANDARD && checkingRoot) {
            authorizationAttempt++ // Ignore a late grant after the user cancels.
            checkingRoot = false
        }
        if (isLocked) return false
        rootModeError = null
        if (value == DeliveryMode.ROOT) {
            if (mode == DeliveryMode.ROOT) return true
            try {
                checkedRootIdentity()
            } catch (t: Exception) {
                prefs.mode = DeliveryMode.STANDARD
                rootModeError = t.message ?: "系统或时钟不兼容，请使用普通模式。"
                return false
            }
            checkingRoot = true
            val attempt = ++authorizationAttempt
            main.postDelayed({
                if (checkingRoot && authorizationAttempt == attempt) {
                    authorizationAttempt++
                    prefs.mode = DeliveryMode.STANDARD
                    rootModeError = "Root 授权等待超时，仍使用普通模式。请在超级用户管理器中找到本应用并授权，再重试。"
                    checkingRoot = false
                }
            }, rootAuthorizationTimeoutMillis)
            authorizer.execute {
                val error = runCatching { backend.requestAuthorization() }.exceptionOrNull()
                main.post {
                    if (attempt != authorizationAttempt) return@post
                    if (error == null) {
                        prefs.mode = DeliveryMode.ROOT
                        mode = DeliveryMode.ROOT
                        requestForChoice = null
                        publish(SetUiState.Idle)
                        checkingRoot = false
                        prepare()
                    } else {
                        prefs.mode = DeliveryMode.STANDARD
                        rootModeError = "未获得可用的 Root 授权，仍使用普通模式。请在超级用户管理器中找到本应用并授权，再重试。\n${error.message.orEmpty()}"
                        checkingRoot = false
                    }
                }
            }
            return true
        }
        prefs.mode = value
        mode = value
        if (value == DeliveryMode.STANDARD) worker.execute { createdBackend?.release?.invoke() }
        requestForChoice = null
        publish(SetUiState.Idle)
        return true
    }

    fun reloadPreferences() {
        authorizationAttempt++
        checkingRoot = false
        mode = DeliveryMode.STANDARD
        clockName = prefs.clock?.label ?: "尚未选择时钟"
        storageError = null
        publish(SetUiState.Idle)
        prepare()
    }

    fun chooseClock() {
        if (isLocked) { restore(); return }
        requestForChoice = null
        publish(SetUiState.Choosing(clocks.candidates(), "选择接收闹钟的时钟应用"))
    }

    fun selectClock(choice: ClockChoice) {
        if (isLocked) return
        val fresh = clocks.candidates().firstOrNull { it.identity == choice.identity && it.component == choice.component }
            ?: return publish(SetUiState.Choosing(clocks.candidates(), "时钟已变化，请重新选择"))
        prefs.clock = fresh
        clockName = fresh.label
        val request = requestForChoice
        requestForChoice = null
        publish(SetUiState.Idle)
        request?.let { dispatchStandard(it, fresh) }
    }

    fun request(target: LocalTime, label: String, logEntry: SleepLogEntry?) {
        if (state is SetUiState.Choosing) return
        if (isLocked) { restore(); return }
        val request = Request(target, label, logEntry)
        lastRequest = request
        if (Process.myUid() / 100000 != 0) {
            fail(request, FailureKind.UNSUPPORTED, "仅支持手机主用户空间，请在主用户中使用")
            return
        }
        if (mode == DeliveryMode.STANDARD) requestStandard(request) else requestRoot(request)
    }

    /** Explicit fallback still uses the exact same durable lock as the original request. */
    fun requestSystemCreate(target: LocalTime, label: String) {
        if (Process.myUid() / 100000 != 0) {
            publish(SetUiState.Failed(target, label, FailureKind.UNSUPPORTED, "仅支持手机主用户空间，请在主用户中使用"))
            return
        }
        if (isLocked) {
            publish(SetUiState.Uncertain(target, "原请求仍未确认，暂不能新建，以免同时产生两条闹钟。请查看或重新核对原操作。", running))
            return
        }
        prefs.mode = DeliveryMode.STANDARD
        mode = DeliveryMode.STANDARD
        worker.execute { createdBackend?.release?.invoke() }
        requestStandard(lastRequest?.takeIf { it.target.hour == target.hour && it.target.minute == target.minute }
            ?: Request(target, label, null))
    }

    private fun requestStandard(request: Request) {
        val candidates = clocks.candidates()
        val selected = prefs.clock
        val valid = candidates.firstOrNull { it.identity == selected?.identity && it.component == selected.component }
        when {
            valid != null -> dispatchStandard(request, valid)
            selected == null && candidates.size == 1 -> {
                prefs.clock = candidates.single(); clockName = candidates.single().label
                dispatchStandard(request, candidates.single())
            }
            else -> {
                requestForChoice = request
                publish(SetUiState.Choosing(candidates,
                    if (candidates.isEmpty()) "没有可接收闹钟的应用，请安装或启用时钟后重新选择。目标 ${request.target}"
                    else if (selected != null) "原时钟已卸载、禁用或身份发生变化，请重新选择"
                    else "这部手机有多个时钟，请选择一个"))
            }
        }
    }

    private fun begin(request: Request, identity: AlarmIdentity): AlarmOperation? = try {
        gate.begin(identity, request.target.hour, request.target.minute, request.label,
            System.currentTimeMillis(), ZoneId.systemDefault().id, request.log?.let(sleepLog::encode))
    } catch (t: Exception) {
        storageError = "操作记录保存失败，未发送新请求：${t.javaClass.simpleName}"
        publish(SetUiState.Uncertain(request.target, storageError!!))
        null
    }

    private fun dispatchStandard(request: Request, choice: ClockChoice) {
        if (isLocked) return
        val op = begin(request, choice.identity) ?: return
        // Persist before launch: a crash after startActivity must never cause an automatic resend.
        gate.finish(op.id, OperationStage.AWAITING_USER)
        val sent = try {
            launchStandard?.invoke(choice, op.hour, op.minute, op.label)
                ?: StandardClockResolver.launch(app, choice, op.hour, op.minute, op.label)
        } catch (_: Exception) {
            // A lost binder reply is not proof the other Activity was never launched.
            publish(SetUiState.AwaitingUser(request.target, choice.label + "（交接结果待核对）", choice.requiresManualTime))
            return
        }
        if (sent) publish(SetUiState.AwaitingUser(request.target, choice.label, choice.requiresManualTime))
        else {
            gate.finish(op.id, OperationStage.NOT_DISPATCHED)
            fail(request, FailureKind.REJECTED, "时钟没有接受交接，请检查时钟是否可用或重新选择。")
        }
    }

    fun confirmStandard(saved: Boolean) {
        val op = gate.current() ?: return
        if (!gate.confirmUser(op.id, saved)) return
        if (saved) {
            saveHistory(gate.current()!!)
            publish(SetUiState.UserConfirmed(op.time()))
        } else publish(SetUiState.Idle)
    }

    fun showClock(): Boolean {
        val op = runCatching { gate.current() }.getOrNull()
        val pkg = op?.takeIf { !it.stage.terminal }?.identity?.clockPackage
            ?: if (mode == DeliveryMode.ROOT) "com.coloros.alarmclock" else prefs.clock?.identity?.clockPackage
        if (pkg == null) { chooseClock(); return false }
        val shown = clocks.showPackage(pkg)
        if (!shown) {
            if (isLocked) publish(SetUiState.Uncertain(op?.time() ?: LocalTime.now(), "原时钟无法打开，请先恢复或启用该时钟；不会向另一个应用重发。", running))
            else publish(SetUiState.Choosing(clocks.candidates(), "所选时钟无法打开，请重新选择"))
        }
        return shown
    }

    private fun checkedRootIdentity(): AlarmIdentity = rootIdentity?.invoke() ?: RootCompatibility.identity(app)

    private fun requestRoot(request: Request) {
        val identity = try { checkedRootIdentity() } catch (t: Exception) {
            fail(request, FailureKind.UNSUPPORTED, t.message ?: "没有适配的系统时钟，请使用普通模式")
            return
        }
        val op = begin(request, identity) ?: return
        runRoot(op, recoveryOnly = false)
    }

    fun reconcile() {
        if (running) return
        val op = gate.current()?.takeIf { !it.stage.terminal && it.identity.mode == DeliveryMode.ROOT } ?: return
        val identity = runCatching { checkedRootIdentity() }.getOrNull()
        if (identity != op.identity) {
            publish(SetUiState.Uncertain(op.time(), "原时钟、系统或权限环境已变化，不能把旧操作归到新的目标。请手动核对原时钟。"))
            return
        }
        runRoot(op, recoveryOnly = true)
    }

    private fun runRoot(op: AlarmOperation, recoveryOnly: Boolean) {
        running = true
        publish(SetUiState.Working(op.time()))
        main.postDelayed({
            if (running && gate.current()?.id == op.id && gate.timeout(op.id)) {
                publish(SetUiState.Uncertain(op.time(), "等待超过 ${watchdogMillis / 1000} 秒，原调用可能仍在执行。请勿另外新建。", true))
            }
        }, watchdogMillis)
        worker.execute {
            var editor: java.util.concurrent.Future<Boolean>? = null
            val outcome = try {
                backend.verifyAuthorization()
                editor = warmer.submit<Boolean> { backend.editorOpen() }
                if (ZoneId.systemDefault().id != op.zoneId) {
                    SetOutcome.Failed(FailureKind.WRITE_UNKNOWN, "系统时区已变化，请在原时钟核对目标时间")
                } else {
                    val setter = AlarmSetter(backend.gateway, inFlight, System::currentTimeMillis,
                        ZoneId.systemDefault(), op.identity, op.id, ZoneId::systemDefault)
                    if (recoveryOnly) setter.reconcile(op.hour, op.minute) else setter.set(op.hour, op.minute, op.label)
                }
            } catch (e: GatewayException) {
                SetOutcome.Failed(when (e.failure) {
                    GatewayFailure.ROOT_DENIED -> FailureKind.ROOT_DENIED
                    GatewayFailure.UNAVAILABLE -> FailureKind.UNAVAILABLE
                    GatewayFailure.CALL_FAILED -> FailureKind.UNKNOWN
                }, e.message.orEmpty())
            } catch (t: Exception) {
                SetOutcome.Failed(FailureKind.WRITE_UNKNOWN, "${t.javaClass.simpleName}: ${t.message}")
            }
            val editing = runCatching { editor?.get(400, TimeUnit.MILLISECONDS) ?: true }.getOrDefault(true)
            main.post {
                running = false
                val current = gate.current()?.takeIf { it.id == op.id && !it.stage.terminal } ?: return@post
                when (outcome) {
                    is SetOutcome.Done -> {
                        gate.finish(op.id, OperationStage.VERIFIED)
                        saveHistory(gate.current()!!)
                        val note = when {
                            current.timedOut -> "原请求稍后已读回核对；期间的新建请求均被拦截，没有另发请求。请自行查看时钟。"
                            editing -> "时钟里可能有未保存的编辑，相位没有动它。请完成或取消编辑后查看。"
                            !(navigateRoot?.invoke(outcome.alarm.id, true)
                                ?: ClockLauncher.showAlarm(app, outcome.alarm.id, true)) -> "闹钟已核对开启，但未能打开时钟列表。请手动查看，无需再建。"
                            else -> null
                        }
                        publish(SetUiState.Success(op.time(), outcome.how, note, current.timedOut))
                    }
                    is SetOutcome.Failed -> {
                        if (outcome.kind == FailureKind.ROOT_DENIED) {
                            prefs.mode = DeliveryMode.STANDARD
                            mode = DeliveryMode.STANDARD
                            rootModeError = "Root 授权已失效，已回到普通模式。请在设置中重新申请授权。"
                        }
                        val unknown = recoveryOnly || current.timedOut || inFlight.load() != null ||
                            outcome.kind in setOf(FailureKind.WRITE_UNKNOWN, FailureKind.UNKNOWN, FailureKind.UNSUPPORTED)
                        gate.finish(op.id, if (unknown) OperationStage.WRITE_UNKNOWN else OperationStage.NOT_DISPATCHED)
                        if (unknown) publish(SetUiState.Uncertain(op.time(), outcome.detail))
                        else publish(SetUiState.Failed(op.time(), op.label, outcome.kind, outcome.detail))
                    }
                }
            }
        }
    }

    /** Invoked only after the UI's explicit manual-review acknowledgement. Never records success. */
    fun resolveManually() {
        if (running || storageError != null) return
        val op = gate.current()
        if (op != null && !op.stage.terminal) gate.finish(op.id, OperationStage.MANUALLY_RESOLVED)
        inFlight.clear()
        publish(SetUiState.Idle)
    }

    private fun saveHistory(op: AlarmOperation) {
        if (!op.stage.confirmed) return
        op.logPayload?.let { raw -> sleepLog.decode(raw)?.let { entry ->
            sleepLog.appendConfirmed(entry, op.id, if (op.stage == OperationStage.VERIFIED) "verified" else "user-confirmed")
        } }
    }

    fun dismiss() {
        if (isLocked) return
        requestForChoice = null
        publish(SetUiState.Idle)
    }

    private fun fail(request: Request, kind: FailureKind, detail: String) =
        publish(SetUiState.Failed(request.target, request.label, kind, detail))
    private fun AlarmOperation.time(): LocalTime = LocalTime.of(hour, minute)

    companion object {
        @Volatile private var instance: AlarmSetController? = null
        fun get(context: Context): AlarmSetController = instance ?: synchronized(this) {
            instance ?: AlarmSetController(context).also { instance = it }
        }
    }
}
