package com.example.smartalarm.core.model

import java.time.LocalTime

enum class WakeScenario { FAST, EXPECTED, SLOW }

data class WakeOption(
    val time: LocalTime,
    val sleepMinutes: Int,
    val scenario: WakeScenario,
    val offsetMinutes: Int,
)
