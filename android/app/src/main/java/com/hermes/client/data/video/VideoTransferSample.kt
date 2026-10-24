package com.hermes.client.data.video

/** Request-local body goodput hint, excluding time to the first received bytes. It only
 * gates earlier speculation: range sizing, validation and authoritative retry stay unchanged.
 */
internal class VideoTransferSample(private val nanoTime: () -> Long = System::nanoTime) {
    private var firstAt: Long? = null
    private var firstBytes = 0L
    private var lastAt = 0L
    private var lastBytes = 0L

    fun received(total: Long) {
        val now = nanoTime()
        if (firstAt == null) { firstAt = now; firstBytes = total }
        lastAt = now
        lastBytes = total
    }

    fun permitsEarlyRefill(): Boolean {
        val elapsed = lastAt - (firstAt ?: return false)
        val sampled = lastBytes - firstBytes
        // Too few samples and cache-only reads do not establish network headroom. One
        // MiB/s per response is deliberately conservative when several ranges share a link.
        return sampled >= 64 * 1024 && elapsed > 0 &&
            sampled * 1_000_000_000L / elapsed >= 1024 * 1024
    }
}
