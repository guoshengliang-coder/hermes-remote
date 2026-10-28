package com.hermes.client.data.video

import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class VideoNetworkSpeedTest {
    private var now = 0L
    private val meter = VideoNetworkSpeedMeter { now }

    @Test fun pending_stalled_and_idle_rates_use_a_bounded_wall_time_window() {
        assertNull(meter.snapshot().bytesPerSecond)
        val transfer = meter.request()
        now = 1_000
        assertEquals(0L, meter.snapshot().bytesPerSecond)
        transfer.received(200_000)
        assertEquals(200_000L, meter.snapshot().bytesPerSecond)
        now = 2_000
        assertEquals(100_000L, meter.snapshot().bytesPerSecond)
        now = 3_000
        assertEquals(0L, meter.snapshot().bytesPerSecond)
        transfer.close()
        transfer.received(400_000)
        assertEquals(0L, meter.snapshot().bytesPerSecond)
    }

    @Test fun cumulative_callbacks_parallel_ranges_and_retries_count_each_actual_byte_once() {
        val first = meter.request(); val second = meter.request()
        now = 1_000
        first.received(50_000); first.received(100_000)
        first.received(100_000); first.received(40_000) // duplicates/out-of-order ignored
        second.received(60_000); second.received(100_000)
        assertEquals(200_000L, meter.snapshot().bytesPerSecond)
        first.close()
        meter.request().use { retry -> retry.received(50_000) }
        assertEquals(250_000L, meter.snapshot().bytesPerSecond) // re-fetched bytes really used the link
    }

    @Test fun background_reset_ignores_late_callbacks_and_reopen_does_not_inherit_samples() {
        val old = meter.request(); old.received(2_000_000)
        meter.reset()
        old.received(4_000_000)
        assertNull(meter.snapshot().bytesPerSecond)
        now = 1_000
        assertEquals(0L, meter.snapshot().bytesPerSecond)
        meter.request().use { it.received(100_000) }
        assertEquals(100_000L, meter.snapshot().bytesPerSecond)
        assertNull(VideoNetworkSpeedMeter { now }.snapshot().bytesPerSecond)
    }

    @Test fun concurrent_callbacks_are_summed_without_loss_and_do_not_keep_old_buckets() {
        now = 1_000
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll((1..4).map { java.util.concurrent.Callable {
                meter.request().use { transfer -> repeat(1_000) { transfer.received((it + 1L) * 1_000) } }
            } }).forEach { it.get() }
            assertEquals(4_000_000L, meter.snapshot().bytesPerSecond)
            now = 60_000
            assertEquals(0L, meter.snapshot().bytesPerSecond)
            meter.request().use { it.received(2_000) }
            assertEquals(1_000L, meter.snapshot().bytesPerSecond)
        } finally { pool.shutdownNow() }
    }
}
