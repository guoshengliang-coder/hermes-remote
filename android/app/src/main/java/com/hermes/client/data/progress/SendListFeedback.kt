package com.hermes.client.data.progress

import com.hermes.client.data.repository.PendingSendConversation
import com.hermes.client.domain.Session

/** A payload-free list preview. The durable journal still controls upload and delivery. */
internal data class SendListFeedback(
    val id: String,
    val owner: PendingSendConversation,
    val previewTitle: String,
    val startedAt: Long,
    val accepted: Boolean = false,
    val newRow: Boolean = owner.localCreation,
    val baselineCount: Int? = null,
    val synchronized: Boolean = false,
    val rowKey: String = id,
) {
    fun matches(row: Session): Boolean =
        row.id == owner.sessionId && row.deviceId == owner.deviceId &&
            (row.profile ?: "default") == (owner.profile ?: "default")

    fun caughtUp(row: Session): Boolean =
        accepted && matches(row) && (synchronized || newRow ||
            (row.lastActive ?: 0L) >= startedAt || baselineCount?.let { row.messageCount > it } == true)
}

internal fun mergeSendListFeedback(rows: List<Session>, feedback: List<SendListFeedback>): List<Session> {
    val result = rows.toMutableList()
    val seen = mutableSetOf<Triple<String, String?, String>>()
    feedback.sortedByDescending { it.startedAt }.forEach { entry ->
        val owner = entry.owner
        val identity = Triple(owner.sessionId, owner.profile ?: "default", owner.deviceId)
        if (!seen.add(identity)) return@forEach
        val alias = feedback.firstOrNull {
            it.newRow && it.owner.sessionId == owner.sessionId && it.owner.deviceId == owner.deviceId &&
                (it.owner.profile ?: "default") == (owner.profile ?: "default")
        }?.rowKey
        val index = result.indexOfFirst(entry::matches)
        val server = result.getOrNull(index)
        if (server != null && entry.caughtUp(server)) {
            result[index] = server.copy(
                localListKey = alias ?: server.localListKey,
                lastActive = maxOf(entry.startedAt, server.lastActive ?: 0L),
            )
            return@forEach
        }
        if (entry.synchronized) return@forEach
        val item = (server ?: Session(
            id = owner.sessionId, title = entry.previewTitle, profile = owner.profile ?: "default",
            model = null, provider = null, messageCount = 0, deviceId = owner.deviceId,
            cwd = owner.cwd, pendingLocalCreation = owner.localCreation,
        )).copy(
            lastActive = maxOf(entry.startedAt, server?.lastActive ?: 0L),
            localSendInProgress = !entry.accepted,
            localListKey = alias ?: entry.rowKey.takeIf { server == null },
        )
        if (index < 0) result += item else result[index] = item
    }
    return result
}
