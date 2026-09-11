package com.hermes.client.ui.nav

import com.hermes.client.data.network.CronJobDto

/**
 * The pure rules behind the card page's remote-node row and its chip, kept out of the composable
 * so a JVM test can pin the boundaries.
 *
 * The three latency bands (优 / 普通 / 延迟) that lived here were deleted on the design's second
 * pull, which replaced the status capsule with plain milliseconds.
 */

/** "242 ms" below a second, "1.1 s" above — four-digit ms never earns its width. */
fun formatLatency(ms: Long): String =
    if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000.0)

/**
 * The chip beside the wordmark: 「DEV」on a debug build (the mock's word — "in development", not
 * the compiler's "debug"; product decision 2026-09-11), any other non-release build type in
 * capitals (BETA), nothing on a release build.
 */
fun buildBadgeFor(buildType: String): String? = when {
    buildType.equals("release", ignoreCase = true) -> null
    buildType.equals("debug", ignoreCase = true) -> "DEV"
    else -> buildType.uppercase()
}

/** Jobs that will actually fire: enabled and not paused. What the 定时任务 row counts. */
fun activeCronCount(jobs: List<CronJobDto>): Int =
    jobs.count { it.enabled && it.pausedAt == null }
