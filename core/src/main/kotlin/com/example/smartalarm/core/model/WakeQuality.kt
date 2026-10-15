package com.example.smartalarm.core.model

/**
 * How rough waking up at a given moment is likely to feel.
 *
 * Based on nap/sleep-inertia research: waking from light sleep (N1/N2, or a
 * cycle boundary) feels easy; being pulled out of slow-wave sleep — typically
 * 30-70 minutes into a sleep episode during the first cycles — causes the
 * worst grogginess (sleep inertia).
 */
enum class WakeQuality {
    /** Light-sleep window — waking should feel easy. */
    EASY,

    /** In-between — some grogginess possible. */
    MODERATE,

    /** Likely mid slow-wave sleep — expect heavy grogginess. */
    GROGGY,
}
