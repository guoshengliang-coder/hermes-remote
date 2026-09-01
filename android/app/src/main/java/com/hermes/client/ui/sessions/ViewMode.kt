package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session

/** Which list the Chats screen shows: flat recency, the project tree, or archived sessions. */
enum class ViewMode { SESSIONS, PROJECTS, ARCHIVED }

/** Flat, most-recent-first order for Sessions mode. Sessions with no [Session.lastActive] sort last. */
fun sessionsByRecency(sessions: List<Session>): List<Session> =
    sessions.sortedByDescending { it.lastActive ?: Long.MIN_VALUE }

/** Phases that mean a run is blocked on the user — these sessions jump the recency order. */
private val WAITING_PHASES = setOf(
    com.hermes.client.data.progress.SessionRunPhase.WAITING_APPROVAL,
    com.hermes.client.data.progress.SessionRunPhase.WAITING_CLARIFICATION,
    com.hermes.client.data.progress.SessionRunPhase.WAITING_ATTENTION,
)

/**
 * Split [sessions] into (needs-you, rest): a session whose runtime phase is a WAITING_* state is
 * blocked on the user and belongs at the very top of the list, newest first within the group.
 * Pure — the phase lookup is passed in so it unit-tests without the runtime store.
 */
fun splitNeedsYou(
    sessions: List<Session>,
    phaseOf: (Session) -> com.hermes.client.data.progress.SessionRunPhase?,
): Pair<List<Session>, List<Session>> {
    val (needs, rest) = sessions.partition { phaseOf(it) in WAITING_PHASES }
    return sessionsByRecency(needs) to rest
}

/** The recency buckets the session list renders below Pinned. */
data class RecencyGroups(
    val today: List<Session>,
    val week: List<Session>,
    val earlier: List<Session>,
)

/**
 * Bucket [sessions] as Today / Previous 7 days / Earlier (ChatGPT's convention — a ROLLING
 * 7-day window, so nothing dumps into Earlier at a week boundary). "Today" is the device's
 * local calendar day; the week bucket is the 7 days before it, excluding today; everything
 * else — sessions with no timestamp included — is Earlier. Each bucket is newest-first.
 * Pure: [nowMs] and [zone] injected so boundaries unit-test without a clock.
 */
fun groupByRecency(
    sessions: List<Session>,
    nowMs: Long,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): RecencyGroups {
    val startOfToday = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    val weekFloor = startOfToday - 7L * 24 * 60 * 60 * 1000
    val today = ArrayList<Session>()
    val week = ArrayList<Session>()
    val earlier = ArrayList<Session>()
    for (s in sessions) {
        val t = s.lastActive
        when {
            t != null && t >= startOfToday -> today.add(s)
            t != null && t >= weekFloor -> week.add(s)
            else -> earlier.add(s)
        }
    }
    return RecencyGroups(sessionsByRecency(today), sessionsByRecency(week), sessionsByRecency(earlier))
}
