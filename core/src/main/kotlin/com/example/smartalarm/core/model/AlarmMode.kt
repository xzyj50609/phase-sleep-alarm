package com.example.smartalarm.core.model

/**
 * Declaration order is the order shown in the mode selector. v9 puts the
 * everyday modes first — semester-specific ones (class, breakfast) trail.
 */
enum class AlarmMode {
    /** 正常睡觉: pure cycle counting from sleep onset, ignores everything. */
    NORMAL,

    /** 爽睡: secure a long night (>= minCatchUpHours). */
    CATCH_UP,

    /** 小睡: daytime nap, cycle-first. */
    NAP,

    /** 上课: latest comfortable wake-up before the class deadline. */
    CLASS,

    /** 保早餐: wake as close to the morning target as comfort allows. */
    BREAKFAST,
}
