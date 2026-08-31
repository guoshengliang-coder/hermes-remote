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
