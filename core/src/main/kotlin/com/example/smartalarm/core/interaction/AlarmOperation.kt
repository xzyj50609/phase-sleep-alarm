package com.example.smartalarm.core.interaction

import java.util.UUID

/** Execution permission is independent of the sleep recommendation mode. Stable persisted names. */
enum class DeliveryMode { STANDARD, ROOT }

data class AlarmIdentity(
    val clockPackage: String,
    val certificateSha256: String,
    val versionCode: Long,
    val androidUser: Int,
    val mode: DeliveryMode,
    val adapter: String,
)

enum class OperationStage(val terminal: Boolean = false, val confirmed: Boolean = false) {
    WORKING, AWAITING_USER, WRITE_UNKNOWN,
    VERIFIED(true, true), USER_CONFIRMED(true, true), NOT_DISPATCHED(true), CANCELLED(true),
    /** An explicit acknowledgement, never machine verification. */
    MANUALLY_RESOLVED(true),
}

data class AlarmOperation(
    val id: String,
    val identity: AlarmIdentity,
    val hour: Int,
    val minute: Int,
    val label: String,
    val startedAtMillis: Long,
    val zoneId: String,
    val logPayload: String?,
    val stage: OperationStage = OperationStage.WORKING,
    val timedOut: Boolean = false,
)

interface OperationStore {
    fun load(): AlarmOperation?
    /** Must persist synchronously or throw before an external effect is allowed. */
    fun save(operation: AlarmOperation)
}

/** One durable gate for BOTH modes. A timeout does not cancel an external write. */
class AlarmOperationCoordinator(private val store: OperationStore) {
    @Synchronized fun current(): AlarmOperation? = store.load()

    @Synchronized fun begin(
        identity: AlarmIdentity, hour: Int, minute: Int, label: String,
        startedAtMillis: Long, zoneId: String, logPayload: String?,
    ): AlarmOperation? {
        if (current()?.stage?.terminal == false) return null
        require(hour in 0..23 && minute in 0..59)
        return AlarmOperation(UUID.randomUUID().toString(), identity, hour, minute, label,
            startedAtMillis, zoneId, logPayload).also(store::save)
    }

    @Synchronized fun timeout(id: String): Boolean {
        val op = current()?.takeIf { it.id == id && !it.stage.terminal } ?: return false
        store.save(op.copy(stage = OperationStage.WRITE_UNKNOWN, timedOut = true))
        return true
    }

    @Synchronized fun finish(id: String, stage: OperationStage): Boolean {
        val op = current()?.takeIf { it.id == id && !it.stage.terminal } ?: return false
        store.save(op.copy(stage = stage))
        return true
    }

    @Synchronized fun confirmUser(id: String, saved: Boolean): Boolean {
        val op = current()?.takeIf { it.id == id && it.stage == OperationStage.AWAITING_USER } ?: return false
        store.save(op.copy(stage = if (saved) OperationStage.USER_CONFIRMED else OperationStage.CANCELLED))
        return true
    }
}
