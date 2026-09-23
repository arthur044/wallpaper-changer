package io.github.arthur044.wallpaperchanger.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val DEFAULT_BACKOFF_BASE: Duration = 5.seconds
val DEFAULT_BACKOFF_CAP: Duration = 300.seconds

/** Exponential backoff for transient failures: base, then doubling, capped. */
fun nextBackoff(
    current: Duration,
    base: Duration = DEFAULT_BACKOFF_BASE,
    cap: Duration = DEFAULT_BACKOFF_CAP,
): Duration = if (current <= Duration.ZERO) base else minOf(current * 2, cap)
