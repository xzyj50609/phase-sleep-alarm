package com.example.smartalarm.core.interaction

import kotlin.test.*

class AlarmOperationTest {
    private class Store : OperationStore {
        var value: AlarmOperation? = null
        override fun load() = value
        override fun save(operation: AlarmOperation) { value = operation }
    }
    private val store = Store()
    private val identity = AlarmIdentity("clock.a", "cert-a", 1, 0, DeliveryMode.ROOT, "adapter-v1")
    private val gate = AlarmOperationCoordinator(store)

    private fun begin(mode: DeliveryMode = DeliveryMode.ROOT) = gate.begin(
        identity.copy(mode = mode), 7, 30, "Phase test", 1000, "Asia/Shanghai", null,
    )

    @Test fun `B02 B08 timeout never releases dispatch lock and late success keeps its warning`() {
        val first = begin()!!
        assertTrue(gate.timeout(first.id))
        assertNull(begin(DeliveryMode.STANDARD))
        assertTrue(gate.finish(first.id, OperationStage.VERIFIED))
        assertTrue(gate.current()!!.timedOut)
        assertEquals(first.id, gate.current()!!.id)
    }

    @Test fun `B04 B10 restart preserves clock user mode and unresolved operation identity`() {
        val first = begin()!!
        val restarted = AlarmOperationCoordinator(store)
        assertEquals(identity, restarted.current()!!.identity)
        assertNull(restarted.begin(identity.copy(clockPackage = "clock.b", androidUser = 10), 7, 30, "test", 2000, "UTC", null))
        assertEquals(first.id, store.value!!.id)
    }

    @Test fun `B08 stale results cannot change a later operation`() {
        val first = begin()!!
        assertTrue(gate.finish(first.id, OperationStage.NOT_DISPATCHED))
        val second = begin()!!
        assertFalse(gate.finish(first.id, OperationStage.VERIFIED))
        assertEquals(second.id, gate.current()!!.id)
        assertEquals(OperationStage.WORKING, gate.current()!!.stage)
    }

    @Test fun `T03 T07 ordinary dispatch remains unconfirmed and prevents three taps`() {
        val op = begin(DeliveryMode.STANDARD)!!
        assertTrue(gate.finish(op.id, OperationStage.AWAITING_USER))
        repeat(3) { assertNull(begin()) }
        assertFalse(gate.current()!!.stage.confirmed)
        assertTrue(gate.confirmUser(op.id, false))
        assertEquals(OperationStage.CANCELLED, gate.current()!!.stage)
        assertNotNull(begin())
    }

    @Test fun `T03 user confirmation is distinct from machine verification and only accepted once`() {
        val op = begin(DeliveryMode.STANDARD)!!
        gate.finish(op.id, OperationStage.AWAITING_USER)
        assertTrue(gate.confirmUser(op.id, true))
        assertEquals(OperationStage.USER_CONFIRMED, gate.current()!!.stage)
        assertFalse(gate.confirmUser(op.id, true))
    }
}
