package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session

/**
 * Which list the Chats screen shows. Only the two that are CONTENT — interactive chats and the
 * read-only bot transcripts — are segments here; Projects and Archive moved to the overflow menu
 * and their own full-screen pages (docs/DESIGN.md §5.16, 2026-09-09).
 */
enum class ViewMode { SESSIONS, BOTS }

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
    val yesterday: List<Session>,
    val week: List<Session>,
    val earlier: List<Session>,
)

/**
 * Bucket [sessions] as Today / Yesterday / Previous 7 days / Earlier (ChatGPT's convention — a
 * ROLLING 7-day window, so nothing dumps into Earlier at a week boundary). "Today" and
 * "Yesterday" are the device's local calendar days; the week bucket is what remains of the
 * 7 days before today once yesterday is taken out; everything else — sessions with no timestamp
 * included — is Earlier. Each bucket is newest-first.
 * Pure: [nowMs] and [zone] injected so boundaries unit-test without a clock.
 *
 * Yesterday was split out of the week bucket in HG-52; the window itself did not move, so a
 * session that used to read "前 7 天" either says "昨天" now or stays where it was.
 *
 * [startOfYesterday] is a CALENDAR day back, not `startOfToday - 24h`. The two differ by an hour
 * on a DST changeover, and on that day the fixed-millisecond form would either leave an hour of
 * yesterday in the week bucket or pull an hour of the day before into yesterday. (The existing
 * [weekFloor] still uses fixed milliseconds. That is the shipped 7-day semantics and changing it
 * is not part of HG-52 — but the new boundary does not inherit the flaw.)
 */
fun groupByRecency(
    sessions: List<Session>,
    nowMs: Long,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): RecencyGroups {
    val localToday = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val startOfToday = localToday.atStartOfDay(zone).toInstant().toEpochMilli()
    val startOfYesterday = localToday.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val weekFloor = startOfToday - 7L * 24 * 60 * 60 * 1000
    val today = ArrayList<Session>()
    val yesterday = ArrayList<Session>()
    val week = ArrayList<Session>()
    val earlier = ArrayList<Session>()
    for (s in sessions) {
        val t = s.lastActive
        when {
            t != null && t >= startOfToday -> today.add(s)
            t != null && t >= startOfYesterday -> yesterday.add(s)
            t != null && t >= weekFloor -> week.add(s)
            else -> earlier.add(s)
        }
    }
    return RecencyGroups(
        sessionsByRecency(today),
        sessionsByRecency(yesterday),
        sessionsByRecency(week),
        sessionsByRecency(earlier),
    )
}
