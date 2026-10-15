package com.example.smartalarm.core.model

import java.time.LocalTime

data class WakeUpRecommendation(
    val wakeUpTime: LocalTime,
    val type: RecommendationType,
    /** Total genuine sleep across the whole night (night segment + re-sleep). */
    val sleepMinutes: Int,
    val quality: WakeQuality,
    /** Short name shown in candidate lists, e.g. "轻回笼 20 分钟". */
    val label: String,
    /** One plain-language sentence explaining why / what to expect. */
    val reason: String,
    /** Number of full cycles, for FULL_CYCLE candidates. */
    val cycles: Int? = null,
    /** Length of the morning re-sleep or daytime nap, for RESLEEP / SHORT_NAP. */
    val napMinutes: Int? = null,
)
