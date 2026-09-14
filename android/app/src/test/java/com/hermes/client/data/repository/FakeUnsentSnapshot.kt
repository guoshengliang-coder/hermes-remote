package com.hermes.client.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [UnsentSnapshot] for tests, applying the store's own rules ([putUnsent], [removeUnsent])
 * so a test exercises the real one-record-per-conversation semantics rather than a mock's idea of
 * them. The sibling of [FakeDraftSnapshot], deliberately shaped the same way.
 */
class FakeUnsentSnapshot(
    initial: List<UnsentRecord> = emptyList(),
    /** Hold [read] open, to model a disk read still in flight while the screen is already up. */
    private val readGate: kotlinx.coroutines.Deferred<Unit>? = null,
) : UnsentSnapshot {
    private val records = MutableStateFlow(initial)
    var saves = 0
        private set

    override val tokens: Flow<Set<String>> =
        records.map { list -> list.mapTo(mutableSetOf()) { it.token } }

    override suspend fun read(token: String): UnsentRecord? {
        readGate?.await()
        return records.value.firstOrNull { it.token == token }
    }

    override suspend fun save(record: UnsentRecord) {
        saves++
        records.value = putUnsent(records.value, record)
    }

    override suspend fun clear(token: String) {
        records.value = removeUnsent(records.value, token)
    }

    fun peek(token: String): UnsentRecord? = records.value.firstOrNull { it.token == token }
}
