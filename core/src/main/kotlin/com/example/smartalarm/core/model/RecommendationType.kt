package com.example.smartalarm.core.model

enum class RecommendationType {
    /** Daytime short nap (~25 min), stays in light sleep. */
    SHORT_NAP,

    /** Uninterrupted night sleep measured in full cycles. */
    FULL_CYCLE,

    /** Morning re-sleep after the fixed punch-in alarm (回笼觉). */
    RESLEEP,
}
