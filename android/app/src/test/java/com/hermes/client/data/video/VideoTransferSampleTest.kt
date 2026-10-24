package com.hermes.client.data.video

import org.junit.Assert.*
import org.junit.Test

class VideoTransferSampleTest {
    @Test fun no_body_or_a_single_burst_does_not_establish_headroom() {
        val sample = VideoTransferSample { 0 }
        assertFalse(sample.permitsEarlyRefill())
        sample.received(256 * 1024L)
        assertFalse(sample.permitsEarlyRefill())
    }

    @Test fun a_long_first_byte_wait_does_not_hide_fast_body_transfer() {
        var now = 900_000_000L
        val sample = VideoTransferSample { now }
        sample.received(8 * 1024L)
        now += 30_000_000L
        sample.received(256 * 1024L)
        assertTrue(sample.permitsEarlyRefill())
    }

    @Test fun low_body_bandwidth_keeps_the_conservative_pipeline() {
        var now = 0L
        val sample = VideoTransferSample { now }
        sample.received(8 * 1024L)
        now += 2_000_000_000L
        sample.received(256 * 1024L)
        assertFalse(sample.permitsEarlyRefill())
    }

    @Test fun a_measured_MiB_per_second_enables_early_refill_but_a_slower_sample_disables_it() {
        var now = 0L
        val sample = VideoTransferSample { now }
        sample.received(8 * 1024L)
        now += 62_500_000L
        sample.received(72 * 1024L)
        assertTrue(sample.permitsEarlyRefill())
        now += 1_000_000_000L
        sample.received(256 * 1024L)
        assertFalse(sample.permitsEarlyRefill())
    }

    @Test fun small_or_nonpositive_duration_samples_are_conservative() {
        var now = 1_000L
        val sample = VideoTransferSample { now }
        sample.received(8 * 1024L)
        now++
        sample.received(16 * 1024L)
        assertFalse(sample.permitsEarlyRefill())
        now = 0L
        sample.received(256 * 1024L)
        assertFalse(sample.permitsEarlyRefill())
    }
}
