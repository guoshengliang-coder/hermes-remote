package com.hermes.client.data.video

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoReadAheadTest {
    private val block = ArtifactRangeCache.BLOCK_BYTES

    @Test fun initial_and_random_reads_do_not_prefetch_index_bytes() = runTest {
        val calls = mutableListOf<Long>()
        val reader = VideoReadAhead(this, 4, 4 * block, { start, _ -> calls += start; ByteArray(block) })
        reader.load(0, 100L * block)
        reader.load(50L * block, 100L * block)
        runCurrent()
        assertEquals(listOf(0L, 50L * block), calls)
    }

    @Test fun negotiated_parallelism_is_bounded_and_cancellation_drains_every_child() = runTest {
        for (parallel in listOf(1, 2, 4)) {
            val loader = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val started = mutableListOf<Long>(); val cancelled = mutableListOf<Long>()
            val reader = VideoReadAhead(loader, parallel, block, { start, _ ->
                if (start >= 2L * block) {
                    started += start
                    try { awaitCancellation() } finally { cancelled += start }
                }
                ByteArray(block)
            })
            reader.load(0, 100L * block); runCurrent()
            assertTrue(started.isEmpty())
            reader.load(block.toLong(), 100L * block); runCurrent()
            assertEquals((2 until 2 + minOf(2, parallel)).map { it.toLong() * block }, started)
            loader.cancel(); runCurrent()
            assertEquals(started.toSet(), cancelled.toSet())
        }
    }

    @Test fun an_out_of_order_window_delivers_only_requested_range_without_waiting_for_later_ranges() = runTest {
        val loader = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gates = (2..8).associateWith { CompletableDeferred<Unit>() }
        val calls = mutableListOf<Long>()
        val reader = VideoReadAhead(loader, 4, block, { start, _ ->
            calls += start
            val index = (start / block).toInt()
            gates[index]?.await()
            ByteArray(block) { index.toByte() }
        })
        reader.load(0, 9L * block); reader.load(block.toLong(), 9L * block); runCurrent()
        gates.getValue(3).complete(Unit); runCurrent()
        val foreground = async { reader.load(2L * block, 9L * block) }
        runCurrent(); assertFalse(foreground.isCompleted)
        gates.getValue(2).complete(Unit); runCurrent()
        assertEquals(2.toByte(), foreground.await()[0])
        assertEquals(3.toByte(), reader.load(3L * block, 9L * block)[0])
        assertEquals(1, calls.count { it == 2L * block })
        assertEquals(1, calls.count { it == 3L * block })
        assertFalse(gates.getValue(4).isCompleted)
        loader.cancel()
    }

    @Test fun fast_reads_grow_ranges_slow_reads_shrink_and_seek_resets_to_one_block() = runTest {
        val loader = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var latency = 100L
        val calls = mutableListOf<Pair<Long, Int>>()
        val reader = VideoReadAhead(loader, 1, 4 * block, { start, blocks ->
            calls += start to blocks
            delay(latency)
            ByteArray(blocks * block)
        }, { testScheduler.currentTime * 1_000_000 })
        reader.load(0, 100L * block); reader.load(block.toLong(), 100L * block)
        reader.load(2L * block, 100L * block)
        reader.load(3L * block, 100L * block)
        latency = 4_000
        reader.load(5L * block, 100L * block)
        reader.load(9L * block, 100L * block)
        runCurrent()
        assertEquals(listOf(1, 1, 1, 2, 4, 2, 1), calls.map { it.second })
        reader.load(70L * block, 100L * block)
        assertEquals(70L * block to 1, calls.last())
        loader.cancel()
    }

    @Test fun speculation_failure_retries_the_current_small_range_and_cancels_stale_window() = runTest {
        val loader = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var attempts = 0; var cancelled = false
        val reader = VideoReadAhead(loader, 2, block, { start, _ ->
            when (start) {
                2L * block -> if (++attempts == 1) throw IOException("synthetic failure")
                3L * block -> try { awaitCancellation() } finally { cancelled = true }
            }
            ByteArray(block)
        })
        reader.load(0, 4L * block); reader.load(block.toLong(), 4L * block); runCurrent()
        assertEquals(block, reader.load(2L * block, 3L * block).size)
        runCurrent(); assertTrue(cancelled); assertEquals(2, attempts)
        loader.cancel()
    }

    @Test fun confirmed_sequential_read_starts_future_ranges_before_waiting_for_current_bytes() = runTest {
        val loader = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val current = CompletableDeferred<Unit>()
        val started = mutableListOf<Long>()
        val reader = VideoReadAhead(loader, 4, block, { start, _ ->
            started += start
            if (start == block.toLong()) current.await()
            ByteArray(block)
        }, permitsEarlyRefill = { true })
        try {
            reader.load(0, 8L * block)
            val foreground = async { reader.load(block.toLong(), 8L * block) }
            runCurrent()
            assertFalse(foreground.isCompleted)
            assertTrue("sequential reads must overlap the current request's latency", 2L * block in started)
            current.complete(Unit)
            foreground.await()
        } finally { loader.cancel(); current.complete(Unit) }
    }

    @Test fun awaiting_a_slow_current_range_refills_the_bounded_future_window() = runTest {
        val loader = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val current = CompletableDeferred<Unit>()
        val started = mutableListOf<Long>()
        val reader = VideoReadAhead(loader, 4, block, { start, _ ->
            started += start
            if (start == 2L * block) current.await()
            ByteArray(block)
        }, permitsEarlyRefill = { true })
        try {
            reader.load(0, 8L * block); reader.load(block.toLong(), 8L * block)
            runCurrent()
            val foreground = async { reader.load(2L * block, 8L * block) }
            runCurrent()
            assertFalse(foreground.isCompleted)
            assertTrue("finished later ranges must not leave a vacant prefetch slot", 4L * block in started)
            current.complete(Unit)
            foreground.await()
            assertEquals(1, started.count { it == 2L * block })
        } finally { loader.cancel(); current.complete(Unit) }
    }

    @Test fun early_refill_keeps_foreground_and_future_reads_within_negotiated_concurrency() = runTest {
        for (parallel in listOf(1, 2, 4)) {
            val loader = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            var active = 0
            var peak = 0
            val reader = VideoReadAhead(loader, parallel, 4 * block, { _, blocks ->
                active++
                peak = maxOf(peak, active)
                assertTrue("foreground plus future requests exceeded $parallel", active <= parallel)
                try { delay(100); ByteArray(blocks * block) } finally { active-- }
            }, { testScheduler.currentTime * 1_000_000 }, permitsEarlyRefill = { true })
            try {
                var position = 0L
                while (position < 20L * block) position += reader.load(position, 20L * block).size
                assertTrue(peak <= parallel)
            } finally { loader.cancel(); runCurrent() }
            assertEquals(0, active)
        }
    }

    @Test fun cancelling_a_sequential_read_cancels_its_current_and_future_requests() = runTest {
        for (currentBlock in listOf(1L, 2L)) {
            val loader = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val started = mutableSetOf<Long>()
            val stopped = mutableSetOf<Long>()
            val reader = VideoReadAhead(loader, 4, block, { start, _ ->
                if (start >= currentBlock * block) {
                    started += start
                    try { awaitCancellation() } finally { stopped += start }
                }
                ByteArray(block)
            }, permitsEarlyRefill = { true })
            try {
                reader.load(0, 8L * block)
                if (currentBlock == 2L) reader.load(block.toLong(), 8L * block)
                val foreground = launch { reader.load(currentBlock * block, 8L * block) }
                runCurrent()
                assertTrue(started.size > 1)
                foreground.cancelAndJoin()
                runCurrent()
                assertEquals(started, stopped)
            } finally { loader.cancel() }
        }
    }

    @Test fun unknown_or_low_goodput_keeps_the_current_request_ahead_of_future_work() = runTest {
        val loader = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val current = CompletableDeferred<Unit>()
        val started = mutableListOf<Long>()
        val reader = VideoReadAhead(loader, 4, block, { start, _ ->
            started += start
            if (start == block.toLong()) current.await()
            ByteArray(block)
        })
        try {
            reader.load(0, 8L * block)
            val foreground = async { reader.load(block.toLong(), 8L * block) }
            runCurrent()
            assertEquals(listOf(0L, block.toLong()), started)
            current.complete(Unit)
            foreground.await()
            runCurrent()
            assertTrue(2L * block in started)
        } finally { loader.cancel(); current.complete(Unit) }
    }

    @Test fun slow_range_shrink_to_one_does_not_early_refill_on_a_stale_fast_hint() = runTest {
        val loader = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val started = mutableListOf<Long>()
        val reader = VideoReadAhead(loader, 4, block, { start, _ ->
            started += start
            if (start >= 2L * block) delay(4_000)
            ByteArray(block)
        }, { testScheduler.currentTime * 1_000_000 }, permitsEarlyRefill = { true })
        try {
            repeat(4) { reader.load(it.toLong() * block, 8L * block) }
            val foreground = async { reader.load(4L * block, 8L * block) }
            runCurrent()
            assertFalse(foreground.isCompleted)
            assertFalse("a shrunken one-range window must not become two in-flight requests", 5L * block in started)
            foreground.await()
        } finally { loader.cancel() }
    }

    @Test fun final_window_never_schedules_a_range_beyond_the_requested_end() = runTest {
        val calls = mutableListOf<Long>()
        val reader = VideoReadAhead(this, 4, block, { start, _ -> calls += start; ByteArray(block) })
        reader.load(0, 3L * block); reader.load(block.toLong(), 3L * block); runCurrent()
        assertEquals(listOf(0L, block.toLong(), 2L * block), calls)
    }
}
