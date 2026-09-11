package com.hermes.client.ui.nav

import com.hermes.client.data.network.CronJobDto

/**
 * The pure rules behind the card page's remote-node capsule and its chips, kept out of the
 * composable so a JVM test can pin the boundaries.
 */

/** Latency bands for the remote-node capsule (product decision 2026-09-11). */
enum class LatencyTier { GOOD, NORMAL, SLOW }

/** 优 below 50 ms, 普通 up to and including 150 ms, 延迟 above that. */
fun latencyTier(ms: Long): LatencyTier = when {
    ms < 50 -> LatencyTier.GOOD
    ms <= 150 -> LatencyTier.NORMAL
    else -> LatencyTier.SLOW
}

/** "242 ms" below a second, "1.1 s" above — four-digit ms never earns its width. */
fun formatLatency(ms: Long): String =
    if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000.0)

/**
 * The chip beside the wordmark: the build type in capitals, or nothing on a release build. The
 * mock draws 「DEV」; what a tester actually installs is the debug or beta build, and that is the
 * word they should see (the startup gate prints the same one).
 */
fun buildBadgeFor(buildType: String): String? =
    buildType.takeUnless { it.equals("release", ignoreCase = true) }?.uppercase()

/** Jobs that will actually fire: enabled and not paused. What the 定时任务 row counts. */
fun activeCronCount(jobs: List<CronJobDto>): Int =
    jobs.count { it.enabled && it.pausedAt == null }
