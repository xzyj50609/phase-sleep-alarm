package com.example.smartalarm.core.interaction

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

private fun at(hour: Int, minute: Int, day: Int = 1): ZonedDateTime =
    ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ZONE)

/** 记录调用次数的假网关；闹钟列表可变，模拟时钟真实行为。 */
private class FakeGateway(
    val zone: ZoneId = ZONE,
    var now: ZonedDateTime = at(14, 40),
) : ClockGateway {
    val alarms = mutableListOf<ClockAlarmRecord>()
    var nextId = 1000L
    var adds = 0
    var enables = 0
    var reads = 0
    var snapshotOverride: ClockAlarmSnapshot? = null
    var readFailure: GatewayException? = null
    var failureAfterEnable: GatewayException? = null
    var addFailure: GatewayException? = null
    var addResultOverride: AddResult? = null
    /** true: 抛异常前其实已经创建成功（回执丢失）。 */
    var addCreatesBeforeFailing = false
    var enableResult = true
    var corruptNextTriggerOnAdd = false
    var dropRecordOnAdd = false

    fun expectedNext(hour: Int, minute: Int): Long {
        var t = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }

    fun put(hour: Int, minute: Int, enabled: Boolean, repeat: String = "响一次", bits: Int = 0, id: Long = nextId++): ClockAlarmRecord {
        val r = ClockAlarmRecord(id, hour, minute, null, enabled, repeat, bits, expectedNext(hour, minute))
        alarms += r
        return r
    }

    override fun readSnapshot(): ClockAlarmSnapshot {
        reads++
        readFailure?.let { throw it }
        snapshotOverride?.let { return it }
        return if (alarms.isEmpty()) ClockAlarmSnapshot.ConfirmedEmpty else ClockAlarmSnapshot.Complete(alarms.toList())
    }

    override fun addOneShot(hour: Int, minute: Int, label: String): AddResult {
        adds++
        addResultOverride?.let { return it }
        if (addFailure != null && !addCreatesBeforeFailing) throw addFailure!!
        val next = if (corruptNextTriggerOnAdd) expectedNext(hour, minute) - 3_600_000 else expectedNext(hour, minute)
        val record = ClockAlarmRecord(nextId++, hour, minute, label, true, "响一次", 0, next)
        if (!dropRecordOnAdd) alarms += record
        addFailure?.let { throw it }
        return AddResult.Created(record.id)
    }

    override fun enable(id: Long): Boolean {
        enables++
        if (!enableResult) return false
        val i = alarms.indexOfFirst { it.id == id }
        if (i >= 0) alarms[i] = alarms[i].copy(enabled = true, nextTriggerMillis = expectedNext(alarms[i].hour, alarms[i].minute))
        failureAfterEnable?.let { readFailure = it }
        return true
    }
}

private class MemoryInFlight : InFlightStore {
    var value: InFlightCreate? = null
    override fun load() = value
    override fun save(entry: InFlightCreate) { value = entry }
    override fun clear() { value = null }
}

class AlarmSetterTest {
    private val gw = FakeGateway()
    private val inflight = MemoryInFlight()
    private var clockMillis = at(14, 40).toInstant().toEpochMilli()
    private fun setter() = AlarmSetter(gw, inflight, nowMillis = { clockMillis }, zone = ZONE)
    private fun set(h: Int = 7, m: Int = 30) = setter().set(h, m, "相位·%02d:%02d".format(h, m))

    @Test
    fun `existing enabled one shot is reused without any write`() {
        val existing = gw.put(7, 30, enabled = true)
        val out = assertIs<SetOutcome.Done>(set())
        assertEquals(existing.id, out.alarm.id)
        assertEquals(SetHow.REUSED_ENABLED, out.how)
        assertEquals(0, gw.adds); assertEquals(0, gw.enables)
    }

    @Test
    fun `existing disabled one shot is enabled by id and count does not grow`() {
        val existing = gw.put(7, 30, enabled = false)
        gw.put(8, 0, enabled = false)
        val out = assertIs<SetOutcome.Done>(set())
        assertEquals(existing.id, out.alarm.id)
        assertEquals(SetHow.ENABLED_EXISTING, out.how)
        assertEquals(1, gw.enables); assertEquals(0, gw.adds); assertEquals(2, gw.alarms.size)
        assertTrue(gw.alarms.first { it.id == existing.id }.enabled)
    }

    @Test
    fun `no match creates exactly one alarm`() {
        gw.put(8, 0, enabled = true)
        val out = assertIs<SetOutcome.Done>(set())
        assertEquals(SetHow.CREATED, out.how)
        assertEquals(1, gw.adds); assertEquals(2, gw.alarms.size)
        assertNull(inflight.value)
    }

    @Test
    fun `confirmed empty list creates the first alarm`() {
        assertIs<SetOutcome.Done>(set())
        assertEquals(1, gw.adds)
    }

    @Test
    fun `unknown snapshot never writes`() {
        gw.snapshotOverride = ClockAlarmSnapshot.Unknown("接口返回空，独立计数为 5")
        val out = assertIs<SetOutcome.Failed>(set())
        assertEquals(FailureKind.UNKNOWN, out.kind)
        assertEquals(0, gw.adds); assertEquals(0, gw.enables)
    }

    @Test
    fun `unsupported snapshot never writes`() {
        gw.snapshotOverride = ClockAlarmSnapshot.Unsupported("缺少 alarm_hour_list")
        assertEquals(FailureKind.UNSUPPORTED, assertIs<SetOutcome.Failed>(set()).kind)
        assertEquals(0, gw.adds)
    }

    @Test
    fun `root denied is reported as its own failure and does not write`() {
        gw.readFailure = GatewayException(GatewayFailure.ROOT_DENIED, "isRoot=false")
        assertEquals(FailureKind.ROOT_DENIED, assertIs<SetOutcome.Failed>(set()).kind)
        assertEquals(0, gw.adds)
    }

    @Test
    fun `service unavailable is its own failure`() {
        gw.readFailure = GatewayException(GatewayFailure.UNAVAILABLE, "绑定超时")
        assertEquals(FailureKind.UNAVAILABLE, assertIs<SetOutcome.Failed>(set()).kind)
    }

    @Test
    fun `several same time candidates prefer the enabled one and touch nothing else`() {
        gw.put(7, 30, enabled = false); val on = gw.put(7, 30, enabled = true); gw.put(7, 30, enabled = false)
        val out = assertIs<SetOutcome.Done>(set())
        assertEquals(on.id, out.alarm.id)
        assertEquals(0, gw.adds); assertEquals(0, gw.enables)
    }

    @Test
    fun `all disabled duplicates enable only the first in list order`() {
        val first = gw.put(7, 30, enabled = false); gw.put(7, 30, enabled = false)
        val out = assertIs<SetOutcome.Done>(set())
        assertEquals(first.id, out.alarm.id)
        assertEquals(1, gw.enables)
        assertEquals(1, gw.alarms.count { it.enabled })
    }

    @Test
    fun `repeating alarm at the same time is not reused`() {
        gw.put(7, 30, enabled = true, repeat = "每天", bits = 127)
        gw.put(7, 30, enabled = true, repeat = "单休制（仅休周日）", bits = 0)
        val out = assertIs<SetOutcome.Done>(set())
        assertEquals(SetHow.CREATED, out.how)
        assertEquals(3, gw.alarms.size)
    }

    @Test
    fun `lost receipt after creation is recovered without a second create`() {
        gw.addFailure = GatewayException(GatewayFailure.CALL_FAILED, "binder died")
        gw.addCreatesBeforeFailing = true
        val first = assertIs<SetOutcome.Failed>(set())
        assertEquals(FailureKind.WRITE_UNKNOWN, first.kind)
        assertEquals(1, gw.alarms.size)
        assertTrue(inflight.value != null, "未确认的创建必须留下在途记录")

        gw.addFailure = null
        val second = assertIs<SetOutcome.Done>(set())
        assertEquals(1, gw.adds)
        assertEquals(1, gw.alarms.size)
        assertEquals(gw.alarms[0].id, second.alarm.id)
        assertNull(inflight.value)
    }

    @Test
    fun `young in flight create that is not visible yet blocks a second create`() {
        gw.addFailure = GatewayException(GatewayFailure.CALL_FAILED, "timeout")
        assertEquals(FailureKind.WRITE_UNKNOWN, assertIs<SetOutcome.Failed>(set()).kind)
        assertEquals(0, gw.alarms.size)

        clockMillis += 1_000
        gw.addFailure = null
        assertEquals(FailureKind.WRITE_UNKNOWN, assertIs<SetOutcome.Failed>(set()).kind)
        assertEquals(1, gw.adds)
    }

    @Test
    fun `B01 old unresolved create blocks retries even after ten seconds`() {
        gw.addFailure = GatewayException(GatewayFailure.CALL_FAILED, "timeout")
        assertIs<SetOutcome.Failed>(set())

        clockMillis += 10_000
        gw.addFailure = null
        val out = assertIs<SetOutcome.Failed>(set())
        assertEquals(FailureKind.WRITE_UNKNOWN, out.kind)
        assertEquals(1, gw.adds)
        assertEquals(0, gw.alarms.size)
        assertTrue(inflight.value != null)
    }

    @Test
    fun `created id missing on readback is not reported as success`() {
        gw.dropRecordOnAdd = true
        assertEquals(FailureKind.UNKNOWN, assertIs<SetOutcome.Failed>(set()).kind)
    }

    @Test
    fun `wrong next trigger time on readback is not reported as success`() {
        gw.corruptNextTriggerOnAdd = true
        assertEquals(FailureKind.UNKNOWN, assertIs<SetOutcome.Failed>(set()).kind)
    }

    @Test
    fun `enable rejected by the clock is a failure`() {
        gw.put(7, 30, enabled = false)
        gw.enableResult = false
        assertEquals(FailureKind.REJECTED, assertIs<SetOutcome.Failed>(set()).kind)
    }

    @Test
    fun `alarm count limit is reported`() {
        gw.addResultOverride = AddResult.Failed(-2)
        assertEquals(FailureKind.LIMIT_REACHED, assertIs<SetOutcome.Failed>(set()).kind)
    }

    @Test
    fun `target after midnight verifies against the next day`() {
        gw.now = at(23, 50)
        clockMillis = gw.now.toInstant().toEpochMilli()
        val out = assertIs<SetOutcome.Done>(set(0, 30))
        assertEquals(at(0, 30, day = 2).toInstant().toEpochMilli(), out.alarm.nextTriggerMillis)
    }

    @Test fun `B04 unresolved operation belonging to another clock never reads or writes`() {
        val original = AlarmIdentity("clock.a", "sig", 1, 0, DeliveryMode.ROOT, "v1")
        inflight.value = InFlightCreate(7, 30, emptySet(), clockMillis - 60_000, original, "old")
        val other = AlarmSetter(gw, inflight, { clockMillis }, ZONE, original.copy(clockPackage = "clock.b"), "new")
        assertEquals(FailureKind.WRITE_UNKNOWN, assertIs<SetOutcome.Failed>(other.set(7, 30, "test")).kind)
        assertEquals(0, gw.reads)
        assertEquals(0, gw.adds)
        assertEquals("old", inflight.value!!.operationId)
    }

    @Test fun `B06 a live timezone change is used without rebuilding the setter`() {
        var zone = ZONE
        val setter = AlarmSetter(gw, inflight, { clockMillis }, ZONE, zoneProvider = { zone })
        zone = ZoneId.of("America/Los_Angeles")
        gw.now = java.time.Instant.ofEpochMilli(clockMillis).atZone(zone)
        val out = assertIs<SetOutcome.Done>(setter.set(7, 30, "test"))
        assertEquals(gw.expectedNext(7, 30), out.alarm.nextTriggerMillis)
    }

    @Test fun `reconciliation is read only even if no alarm is found`() {
        assertIs<SetOutcome.Failed>(setter().reconcile(7, 30))
        assertEquals(0, gw.adds)
        assertEquals(0, gw.enables)
    }

    @Test fun `B05 unknown or translated repeat semantics must not be treated as no matching alarm`() {
        gw.put(7, 30, enabled = true, repeat = "Once", bits = 0)
        assertEquals(FailureKind.UNSUPPORTED, assertIs<SetOutcome.Failed>(set()).kind)
        assertEquals(0, gw.adds)
    }

    @Test fun `T08 service loss after enabling is write unknown not a preflight failure`() {
        gw.put(7, 30, enabled = false)
        gw.failureAfterEnable = GatewayException(GatewayFailure.UNAVAILABLE, "service lost after enable acknowledgement")
        assertEquals(FailureKind.WRITE_UNKNOWN, assertIs<SetOutcome.Failed>(set()).kind)
        assertEquals(1, gw.enables)
        assertEquals(0, gw.adds)
    }
}
