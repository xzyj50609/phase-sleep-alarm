package com.example.smartalarm.core.interaction

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

enum class GatewayFailure { ROOT_DENIED, UNAVAILABLE, CALL_FAILED }

/** 网关层的“调用本身没成功/结果未知”。区别于时钟明确回了失败码。 */
class GatewayException(val failure: GatewayFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface AddResult {
    data class Created(val id: Long) : AddResult
    /** 时钟明确回了失败码，没有创建。-2 = 满 450 条。 */
    data class Failed(val code: Int) : AddResult
}

/** 对系统时钟的三个动作。实现在 app 层（root 桥接）；这里只依赖接口，便于用假实现测每个分支。 */
interface ClockGateway {
    /** 读列表并独立对账后的可信快照。调用本身失败抛 [GatewayException]。 */
    fun readSnapshot(): ClockAlarmSnapshot
    /** 新建一条一次性、开启的闹钟。结果未知（超时/断开）抛 [GatewayException]。 */
    fun addOneShot(hour: Int, minute: Int, label: String): AddResult
    /** 按真实编号启用；时钟回 result=1 才返回 true。 */
    fun enable(id: Long): Boolean
}

/** 创建前先落盘的在途记录，只用来防重复创建，不是“曾请求过”的历史。 */
data class InFlightCreate(
    val hour: Int, val minute: Int, val knownIdsBefore: Set<Long>, val startedAtMillis: Long,
    val identity: AlarmIdentity? = null,
    val operationId: String? = null,
    val label: String? = null,
)

interface InFlightStore {
    fun load(): InFlightCreate?
    fun save(entry: InFlightCreate)
    fun clear()
}

enum class SetHow { REUSED_ENABLED, ENABLED_EXISTING, CREATED }

enum class FailureKind { ROOT_DENIED, UNAVAILABLE, UNKNOWN, UNSUPPORTED, WRITE_UNKNOWN, REJECTED, LIMIT_REACHED }

sealed interface SetOutcome {
    data class Done(val alarm: ClockAlarmRecord, val how: SetHow) : SetOutcome
    data class Failed(val kind: FailureKind, val detail: String) : SetOutcome
}

sealed interface AlarmMatch {
    data class Existing(val record: ClockAlarmRecord) : AlarmMatch
    data object None : AlarmMatch
    data object Unknown : AlarmMatch
}

/**
 * 同时间的一次性闹钟里，先选已开启的，否则 get_alarm_list 返回顺序里的第一条。
 * 该顺序与时钟界面一致，同一时间按编号升序（2026-10-01 真机实测）；规则只依赖“以返回顺序为准”，不依赖这个升降序。
 */
fun matchOneShot(alarms: List<ClockAlarmRecord>, hour: Int, minute: Int): AlarmMatch {
    val atTime = alarms.filter { it.hour == hour && it.minute == minute }
    val candidates = atTime.filter { it.isOneShot }
    val chosen = candidates.firstOrNull { it.enabled } ?: candidates.firstOrNull()
    return when {
        chosen != null -> AlarmMatch.Existing(chosen)
        atTime.any { it.repeatKind == ClockRepeatKind.UNKNOWN } -> AlarmMatch.Unknown
        else -> AlarmMatch.None
    }
}

/**
 * 一次设置的完整流程。每一步成功的依据是时钟的真实状态读回，不是回执、缓存或“以前设过”：
 * 读可信快照 → 匹配 → 复用/启用/新建 → 读回核对（编号、开启、时间、下次触发时刻）。
 * 查不清就失败，绝不新建；创建前先落在途记录，回执丢失时只核对不重发。
 */
class AlarmSetter(
    private val gateway: ClockGateway,
    private val inFlight: InFlightStore,
    private val nowMillis: () -> Long,
    private val zone: ZoneId,
    private val identity: AlarmIdentity? = null,
    private val operationId: String? = null,
    private val zoneProvider: () -> ZoneId = { zone },
) {
    fun set(hour: Int, minute: Int, label: String): SetOutcome = try {
        run(hour, minute, label)
    } catch (e: GatewayException) {
        SetOutcome.Failed(e.failure.toKind(), e.message.orEmpty())
    }

    /** Recovery after process death is read-only: absence is never permission to create. */
    fun reconcile(hour: Int, minute: Int): SetOutcome = try {
        val recovery = recoverInFlight(hour, minute)
        if (recovery is Recovery.Blocked) recovery.outcome else {
            val snapshot = (recovery as Recovery.Proceed).snapshot ?: gateway.readSnapshot()
            val records = (snapshot as? ClockAlarmSnapshot.Complete)?.alarms.orEmpty()
            val record = records.firstOrNull { matchesExpectation(it, hour, minute) }
            if (record != null) SetOutcome.Done(record, SetHow.REUSED_ENABLED)
            else SetOutcome.Failed(FailureKind.WRITE_UNKNOWN, "仍未找到与原操作一致且已开启的闹钟，请到原时钟核对")
        }
    } catch (e: GatewayException) {
        SetOutcome.Failed(FailureKind.WRITE_UNKNOWN, "原操作尚未核对：${e.message}")
    }

    private fun run(hour: Int, minute: Int, label: String): SetOutcome {
        val recovery = when (val r = recoverInFlight(hour, minute)) {
            is Recovery.Blocked -> return r.outcome
            is Recovery.Proceed -> r
        }
        val snapshot = recovery.snapshot ?: gateway.readSnapshot()
        return when (snapshot) {
            is ClockAlarmSnapshot.Unknown -> SetOutcome.Failed(FailureKind.UNKNOWN, snapshot.reason)
            is ClockAlarmSnapshot.Unsupported -> SetOutcome.Failed(FailureKind.UNSUPPORTED, snapshot.reason)
            ClockAlarmSnapshot.ConfirmedEmpty -> create(hour, minute, label, emptySet())
            is ClockAlarmSnapshot.Complete -> when (val match = matchOneShot(snapshot.alarms, hour, minute)) {
                AlarmMatch.None -> create(hour, minute, label, snapshot.alarms.map { it.id }.toSet())
                AlarmMatch.Unknown -> SetOutcome.Failed(FailureKind.UNSUPPORTED, "同一时间的闹钟含未识别的重复规则，不能按不存在处理；本次未新建")
                is AlarmMatch.Existing -> useExisting(match.record, hour, minute)
            }
        }
    }

    private sealed interface Recovery {
        /** 没有在途创建，或已核对清楚；[snapshot] 非空时可直接沿用，省一次读取。 */
        class Proceed(val snapshot: ClockAlarmSnapshot?) : Recovery
        /** 上一次创建仍不能确定终态，必须停止，不另发创建。 */
        class Blocked(val outcome: SetOutcome.Failed) : Recovery
    }

    private fun recoverInFlight(hour: Int, minute: Int): Recovery {
        val pending = inFlight.load() ?: return Recovery.Proceed(null)
        if (pending.identity != identity || pending.hour != hour || pending.minute != minute ||
            (pending.operationId != null && pending.operationId != operationId)) {
            return blocked(FailureKind.WRITE_UNKNOWN, "存在另一个时钟、模式或时间的未决操作，请先核对原操作")
        }
        val snapshot = gateway.readSnapshot()
        val alarms = when (snapshot) {
            is ClockAlarmSnapshot.Complete -> snapshot.alarms
            ClockAlarmSnapshot.ConfirmedEmpty -> emptyList()
            is ClockAlarmSnapshot.Unknown ->
                return blocked(FailureKind.UNKNOWN, "上一次创建尚未核对清楚：${snapshot.reason}")
            is ClockAlarmSnapshot.Unsupported -> return blocked(FailureKind.UNSUPPORTED, snapshot.reason)
        }
        val landed = alarms.filter {
            it.id !in pending.knownIdsBefore && matchesExpectation(it, pending.hour, pending.minute) &&
                (pending.label == null || it.label == pending.label)
        }
        if (landed.size != 1) return blocked(FailureKind.WRITE_UNKNOWN,
            "上一次创建仍未确认终态；等待时间不能证明请求已取消。请查看时钟或重新核对")
        inFlight.clear()
        return Recovery.Proceed(snapshot)
    }

    private fun blocked(kind: FailureKind, detail: String) = Recovery.Blocked(SetOutcome.Failed(kind, detail))

    private fun useExisting(record: ClockAlarmRecord, hour: Int, minute: Int): SetOutcome {
        if (record.enabled) {
            return if (matchesExpectation(record, hour, minute)) SetOutcome.Done(record, SetHow.REUSED_ENABLED)
            else SetOutcome.Failed(FailureKind.UNKNOWN, "已有闹钟 ${fmt(hour, minute)} 的下次触发时间与预期不符")
        }
        return try {
            if (!gateway.enable(record.id)) SetOutcome.Failed(FailureKind.REJECTED, "时钟拒绝启用编号 ${record.id}")
            else readBack(record.id, hour, minute, SetHow.ENABLED_EXISTING)
        } catch (e: GatewayException) {
            SetOutcome.Failed(FailureKind.WRITE_UNKNOWN, "启用可能已经生效，但读回尚未完成：${e.message}")
        }
    }

    private fun create(hour: Int, minute: Int, label: String, knownIds: Set<Long>): SetOutcome {
        inFlight.save(InFlightCreate(hour, minute, knownIds, nowMillis(), identity, operationId, label))
        val added = try {
            gateway.addOneShot(hour, minute, label)
        } catch (e: GatewayException) {
            return SetOutcome.Failed(FailureKind.WRITE_UNKNOWN, "创建请求发出后没有拿到结果：${e.message}")
        }
        return when (added) {
            is AddResult.Failed -> {
                inFlight.clear()
                if (added.code == LIMIT_CODE) SetOutcome.Failed(FailureKind.LIMIT_REACHED, "时钟里闹钟已满")
                else SetOutcome.Failed(FailureKind.REJECTED, "时钟拒绝创建，返回码 ${added.code}")
            }
            is AddResult.Created -> readBack(added.id, hour, minute, SetHow.CREATED).also {
                if (it is SetOutcome.Done) inFlight.clear()
            }
        }
    }

    private fun readBack(id: Long, hour: Int, minute: Int, how: SetHow): SetOutcome {
        val alarms = when (val snapshot = gateway.readSnapshot()) {
            is ClockAlarmSnapshot.Complete -> snapshot.alarms
            is ClockAlarmSnapshot.Unknown -> return SetOutcome.Failed(FailureKind.UNKNOWN, "读回核对失败：${snapshot.reason}")
            is ClockAlarmSnapshot.Unsupported -> return SetOutcome.Failed(FailureKind.UNSUPPORTED, snapshot.reason)
            ClockAlarmSnapshot.ConfirmedEmpty -> emptyList()
        }
        val record = alarms.firstOrNull { it.id == id }
            ?: return SetOutcome.Failed(FailureKind.UNKNOWN, "读回时找不到编号 $id")
        return if (matchesExpectation(record, hour, minute)) SetOutcome.Done(record, how)
        else SetOutcome.Failed(FailureKind.UNKNOWN, "读回的闹钟与预期不符（编号 $id）")
    }

    /** 已开启、一次性、时分正确，且下次触发正是“现在之后的第一个 HH:mm”（跨午夜也成立）。 */
    private fun matchesExpectation(record: ClockAlarmRecord, hour: Int, minute: Int): Boolean =
        record.enabled && record.isOneShot && record.hour == hour && record.minute == minute && record.nextTriggerMillis > 0 &&
            abs(record.nextTriggerMillis - expectedNextTrigger(hour, minute)) <= TRIGGER_TOLERANCE_MS

    private fun expectedNextTrigger(hour: Int, minute: Int): Long {
        val now = Instant.ofEpochMilli(nowMillis()).atZone(zoneProvider())
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.toInstant().toEpochMilli()
    }

    private fun fmt(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

    private fun GatewayFailure.toKind() = when (this) {
        GatewayFailure.ROOT_DENIED -> FailureKind.ROOT_DENIED
        GatewayFailure.UNAVAILABLE -> FailureKind.UNAVAILABLE
        GatewayFailure.CALL_FAILED -> FailureKind.UNKNOWN
    }

    private companion object {
        const val TRIGGER_TOLERANCE_MS = 1_000L
        const val LIMIT_CODE = -2
    }
}
