package com.hermes.client.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PendingCallsTest {
    /** Model a weakly consistent key view: size and iterator need not describe the same moment. */
    private fun disappearingLastCall(): ConcurrentMap<Long, String> {
        val backing = ConcurrentHashMap<Long, String>().apply { put(1L, "answered") }
        return object : ConcurrentMap<Long, String> by backing {
            override val keys: MutableSet<Long> = object : AbstractMutableSet<Long>() {
                override val size: Int
                    get() = backing.size
                override fun iterator(): MutableIterator<Long> {
                    backing.clear()
                    return backing.keys.iterator()
                }
                override fun add(element: Long): Boolean = error("not used")
            }
        }
    }

    @Test fun last_call_answered_between_size_and_iteration_does_not_crash_cleanup() {
        val failed = mutableListOf<String>()
        drainPendingCalls(disappearingLastCall(), failed::add)
        assertTrue("a reply already removed the call", failed.isEmpty())
    }

    @Test fun cleanup_settles_remaining_calls_and_is_idempotent() {
        val pending = ConcurrentHashMap<Long, String>().apply {
            put(1L, "first")
            put(2L, "second")
        }
        val failed = mutableListOf<String>()
        drainPendingCalls(pending, failed::add)
        drainPendingCalls(pending, failed::add)
        assertEquals(setOf("first", "second"), failed.toSet())
        assertEquals(2, failed.size)
        assertTrue(pending.isEmpty())
    }

    @Test(timeout = 10_000) fun reply_cancellation_and_two_cleanups_settle_each_call_once() {
        val pending = ConcurrentHashMap<Int, Int>().apply { repeat(2_000) { put(it, it) } }
        val settled = Array(2_000) { AtomicInteger() }
        val start = CountDownLatch(1)
        val done = CountDownLatch(3)
        val failures = ConcurrentHashMap.newKeySet<Throwable>()
        val workers = listOf<() -> Unit>(
            { repeat(2_000) { pending.remove(it)?.let { call -> settled[call].incrementAndGet() } } },
            { drainPendingCalls(pending) { settled[it].incrementAndGet() } },
            { drainPendingCalls(pending) { settled[it].incrementAndGet() } },
        ).map { work ->
            Thread {
                try { start.await(); work() } catch (error: Throwable) { failures.add(error) }
                finally { done.countDown() }
            }.apply { isDaemon = true; start() }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        workers.forEach { it.join(100) }
        assertTrue("cleanup must not throw: $failures", failures.isEmpty())
        assertTrue(pending.isEmpty())
        settled.forEach { assertEquals(1, it.get()) }
    }
}
