package com.example.smartalarm.core.model

data class SleepCalculatorOutput(
    val recommended: WakeUpRecommendation,
    /** Full candidate list (recommended included), sorted by wake-up time. */
    val alternatives: List<WakeUpRecommendation>,
    /** Overall plain-language summary of the plan. */
    val explanation: String,
    /** Punch-interruption analysis; null for uninterrupted nights and naps. */
    val nightPlan: NightPlan? = null,
)
