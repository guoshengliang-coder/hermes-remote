package com.hermes.client.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [DraftSnapshot] for tests, applying the store's own rules ([putDraft]) so a test
 * exercises the real "blank clears it" semantics rather than a mock's idea of them.
 */
class FakeDraftSnapshot(
    initial: List<DraftRecord> = emptyList(),
    /** Hold [read] open, to model a disk read still in flight while the composer is empty. */
    private val readGate: kotlinx.coroutines.Deferred<Unit>? = null,
) : DraftSnapshot {
    private val records = MutableStateFlow(initial)
    var saves = 0
        private set

    override val tokens: Flow<Set<String>> =
        records.map { list -> list.mapTo(mutableSetOf()) { it.token } }

    override suspend fun read(token: String): String? {
        readGate?.await()
        return records.value.firstOrNull { it.token == token }?.text
    }

    override suspend fun save(token: String, text: String, now: Long) {
        saves++
        records.value = putDraft(records.value, token, text, now)
    }

    override suspend fun clear(token: String) = save(token, "", 0L)

    fun peek(token: String): String? = records.value.firstOrNull { it.token == token }?.text
}
