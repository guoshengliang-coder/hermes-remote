package com.hermes.client.data.network

import java.util.concurrent.ConcurrentMap

/** Remove each call before settling it, so a concurrent reply/timeout can settle it only once. */
internal fun <K, V> drainPendingCalls(pending: ConcurrentMap<K, V>, fail: (V) -> Unit) {
    // HG-156: Kotlin toList() trusts Collection.size == 1 and calls next() without hasNext().
    // A reply/cancellation can remove that last key before the iterator is created. Iterate the
    // concurrent view directly instead; hasNext() handles that legal, empty view.
    for (id in pending.keys) {
        pending.remove(id)?.let(fail)
    }
}
