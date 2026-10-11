package com.hermes.client.ui.workspace

import androidx.compose.runtime.saveable.SaveableStateRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HG-207: the whole cache rides the Activity's saved instance state as one Binder parcel, which
 * died at 556KB with TransactionTooLargeException after months of accumulation. These tests pin
 * the eviction contract: LRU sessions and oversized single sessions are dropped, never the parcel.
 */
class SessionUiStateCacheTest {

    private fun session(key: String, text: String): Pair<String, Map<String, List<Any?>>> =
        key to mapOf("draft" to listOf(text))

    private fun bigText(chars: Int) = "x".repeat(chars)

    @Test
    fun restoringALegacyOversizedSnapshotKeepsOnlyTheMostRecentSessionsWithinBudget() {
        val legacy = (0 until 30).associate { session("s$it", bigText(2_600)) }
        val cache = SessionUiStateCache(legacy)
        val out = cache.snapshot()
        assertEquals(SessionUiStateCache.MAX_CACHED_SESSIONS, out.size)
        (0 until 14).forEach { assertFalse("eldest session s$it evicted", out.containsKey("s$it")) }
        (14 until 30).forEach { assertTrue("recent session s$it kept", out.containsKey("s$it")) }
        val bytes = out.values.sumOf { draft ->
            draft.values.sumOf { list -> list.sumOf { (it as String).length * 2L + 24L } }
        }
        assertTrue("snapshot stays under the parcel budget: $bytes", bytes <= SessionUiStateCache.MAX_SNAPSHOT_BYTES)
    }

    @Test
    fun anOversizedSingleSessionIsDroppedWhileSmallerOnesSurvive() {
        val cache = SessionUiStateCache(
            mapOf(
                session("small-a", bigText(100)),
                session("huge", bigText(40_000)), // ~80KB parcel estimate, past the per-session cap
                session("small-b", bigText(100)),
            ),
        )
        val out = cache.snapshot()
        assertFalse(out.containsKey("huge"))
        assertTrue(out.containsKey("small-a"))
        assertTrue(out.containsKey("small-b"))
    }

    @Test
    fun releasingMoreSessionsThanTheCapEvictsTheLeastRecentlyUsedOne() {
        val cache = SessionUiStateCache()
        (0 until 17).forEach { index ->
            val registry = cache.acquire("s$index") { true }
            registry.registerProvider("draft") { "d$index" }
            cache.release("s$index", registry)
        }
        assertEquals(SessionUiStateCache.MAX_CACHED_SESSIONS, cache.snapshot().size)
        val reopened = cache.acquire("s0") { true }
        assertNull("the very first session was evicted", reopened.consumeRestored("draft"))
        val survivor = cache.acquire("s16") { true }
        assertEquals("d16", survivor.consumeRestored("draft"))
    }

    @Test
    fun aCapturedSessionRestoresIntoTheNextAcquire() {
        val cache = SessionUiStateCache()
        val registry = cache.acquire("s1") { true }
        registry.registerProvider("draft") { "hello" }
        cache.captureBeforeNavigation()
        val next = cache.acquire("s1") { true }
        assertEquals("hello", next.consumeRestored("draft"))
        next.registerProvider("draft") { "hello" }
        cache.release("s1", next)
        assertEquals("hello", cache.snapshot().getValue("s1").getValue("draft").single())
    }

    @Test
    fun reAcquiringAnOldSessionRefreshesItsRecency() {
        val cache = SessionUiStateCache()
        // Fill to the count cap with same-sized sessions: s0 (eldest) .. s15 (newest), ~4KB each.
        (0 until 16).forEach { index ->
            val registry = cache.acquire("s$index") { true }
            registry.registerProvider("draft") { bigText(2_000) }
            cache.release("s$index", registry)
        }
        // Re-visit s0: it becomes the most recent, so s1 is now the eldest.
        cache.acquire("s0") { true }.apply {
            registerProvider("draft") { bigText(2_000) }
            cache.release("s0", this)
        }
        val seventeenth = cache.acquire("s16") { true }
        seventeenth.registerProvider("draft") { bigText(2_000) }
        cache.release("s16", seventeenth)
        val out = cache.snapshot()
        assertEquals(SessionUiStateCache.MAX_CACHED_SESSIONS, out.size)
        assertTrue("re-visited s0 survives", out.containsKey("s0"))
        assertFalse("untouched s1 is now the eldest and is evicted", out.containsKey("s1"))
    }
}
