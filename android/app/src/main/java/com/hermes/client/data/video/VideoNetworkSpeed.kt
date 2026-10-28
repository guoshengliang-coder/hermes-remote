package com.hermes.client.data.video

/** Null means the initial measurement is still pending, rather than an invented rate. */
data class VideoNetworkSpeed(val bytesPerSecond: Long? = null)

/** Per-source network body throughput. Cached bytes never enter this meter.
 * Fixed 100 ms buckets bound memory regardless of callback frequency or viewing duration.
 * Request-local cumulative counters become deltas before concurrent ranges are summed.
 */
internal class VideoNetworkSpeedMeter(private val millis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Bucket(val at: Long, var bytes: Long)
    private val buckets = ArrayDeque<Bucket>()
    private var started = millis()
    private var generation = 0L
    private var received = false

    @Synchronized fun request(): Request = Request(generation)

    inner class Request internal constructor(private val epoch: Long) : AutoCloseable {
        private var previous = 0L
        private var closed = false

        fun received(total: Long) = synchronized(this@VideoNetworkSpeedMeter) {
            if (closed || epoch != generation || total <= previous) return@synchronized
            val delta = total - previous
            previous = total
            val now = millis()
            prune(now)
            val at = now / 100 * 100
            if (buckets.lastOrNull()?.at == at) buckets.last().bytes += delta
            else buckets.addLast(Bucket(at, delta))
            received = true
        }

        override fun close() = synchronized(this@VideoNetworkSpeedMeter) { closed = true }
    }

    @Synchronized fun snapshot(): VideoNetworkSpeed {
        val now = millis()
        prune(now)
        val elapsed = (now - started).coerceIn(1_000, 2_000)
        return VideoNetworkSpeed(if (!received && now - started < 1_000) null
            else buckets.sumOf { it.bytes } * 1_000 / elapsed)
    }

    @Synchronized fun reset() {
        generation++ // Ignore callbacks belonging to requests cancelled by the old viewer.
        buckets.clear()
        received = false
        started = millis()
    }

    private fun prune(now: Long) {
        while (buckets.firstOrNull()?.let { now - it.at >= 2_000 } == true) buckets.removeFirst()
    }
}
