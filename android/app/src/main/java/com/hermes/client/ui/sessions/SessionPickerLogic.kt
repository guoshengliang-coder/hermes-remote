package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session

/**
 * Pure rules for the session picker (docs/SESSION_EXCHANGE_REQUIREMENTS.md §3).
 *
 * They live apart from the screen because this is the part of the feature that is easy to get
 * quietly wrong — a conversation belonging to another identity showing up in the list is a
 * scope-boundary breach (DESIGN.md §1 principle 1), not a cosmetic bug, and it is invisible on a
 * device with one identity.
 */

/**
 * The conversations that may be referenced right now.
 *
 * [sessions] is expected to come from `SessionRepository.listAllProfiles()`, which has already
 * dropped archived, empty and non-interactive (cron / sub-agent / messaging) conversations. This
 * adds the two rules that source cannot know about:
 *
 * - **the active identity only** — the same filter `SessionsViewModel` applies to the Chats list,
 *   so the picker offers exactly what the list shows and nothing from another workspace;
 * - **not the conversation you are standing in** — referencing it into itself would put half of
 *   itself in its own attachment.
 */
fun sessionPickerCandidates(
    sessions: List<Session>,
    activeProfile: String?,
    excludeSessionId: String?,
): List<Session> = sessions.filter { s ->
    (activeProfile.isNullOrBlank() || s.profile == activeProfile) && s.id != excludeSessionId
}

/**
 * What the picker is being opened for.
 *
 * The modes differ in exactly two ways, both of them consequences of direction:
 *
 * - [Reference] takes conversations OUT and may take several, so it needs a cap and offers
 *   archived conversations through search — an archived conversation is still a record worth
 *   quoting.
 * - [Deliver] puts something IN and takes exactly one, so archived conversations are never
 *   offered: delivering into one would revive it somewhere the list does not show (§6.4). It also
 *   carries the "start a new conversation" row.
 */
sealed interface SessionPickerMode {
    /** HG-38: pick up to [remainingSlots] conversations to attach. */
    data class Reference(val remainingSlots: Int) : SessionPickerMode
    /** HG-40: pick one conversation to deliver into, or start a new one. */
    data object Deliver : SessionPickerMode
}

/**
 * Title match for the picker's search box. Title only — finding *which* conversation is the whole
 * job here, and message-level search belongs to the search screen (§3.4).
 */
fun matchesPickerQuery(session: Session, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return session.title.contains(q, ignoreCase = true)
}

/**
 * What the picker may still be selected, given what is already staged on the composer.
 * Zero means every unselected row is disabled — decided up front, not reported after the fact.
 */
fun pickerSelectableCount(remainingSlots: Int, selected: Int): Int =
    (remainingSlots - selected).coerceAtLeast(0)

/** Whether [id] may be toggled on right now. Already-selected rows always stay toggleable off. */
fun pickerRowEnabled(id: String, selected: Set<String>, remainingSlots: Int): Boolean =
    id in selected || selected.size < remainingSlots
