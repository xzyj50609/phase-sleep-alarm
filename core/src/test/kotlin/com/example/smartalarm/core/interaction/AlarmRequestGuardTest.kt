package com.example.smartalarm.core.interaction

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmRequestGuardTest {

    @Test
    fun `request inside cooldown is treated as duplicate`() {
        assertTrue(
            isRecentAlarmRequest(
                requestedAtEpochMillis = 10_000,
                nowEpochMillis = 11_000,
                cooldownMillis = 2_000,
            ),
        )
    }

    @Test
    fun `request after cooldown may be recreated`() {
        assertFalse(
            isRecentAlarmRequest(
                requestedAtEpochMillis = 10_000,
                nowEpochMillis = 12_001,
                cooldownMillis = 2_000,
            ),
        )
    }

    @Test
    fun `future timestamp is ignored`() {
        assertFalse(
            isRecentAlarmRequest(
                requestedAtEpochMillis = 12_000,
                nowEpochMillis = 10_000,
                cooldownMillis = 2_000,
            ),
        )
    }
}
