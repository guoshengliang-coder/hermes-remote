package com.hermes.client.update

import java.util.ArrayDeque

/** Volatile display data. Never serialize these optimistic bytes into the resume journal. */
internal class UpdateDownloadProgress(private val now: () -> Long) {
    private data class Sample(val time: Long, val bytes: Long)
    private val samples = ArrayDeque<Sample>()
    private var bytes = 0L

    @Synchronized fun reset(downloaded: Long) {
        bytes = downloaded
        samples.clear()
        samples.addLast(Sample(now(), bytes))
    }

    /** A different worker's checkpoint or a range retry must not rewind live progress. */
    @Synchronized fun advance(downloaded: Long) {
        bytes = maxOf(bytes, downloaded)
        sample()
    }

    @Synchronized fun snapshot(record: UpdateDownloadRecord): UpdateDownloadSnapshot {
        val running = record.status == UpdateDownloadStatus.RUNNING
        val downloaded = if (running) maxOf(bytes, record.downloadedBytes) else record.downloadedBytes
        return UpdateDownloadSnapshot(record.status, downloaded.coerceIn(0, record.totalBytes), record.totalBytes,
            record.errorCode, if (running) sample() else null)
    }

    /** Three-second moving goodput; repeated reads without new bytes decay the rate to zero. */
    private fun sample(): Long? {
        val stamp = now()
        if (samples.lastOrNull()?.time == stamp) {
            if (samples.size == 1) return null
            samples.removeLast()
        }
        samples.addLast(Sample(stamp, bytes))
        while (samples.size > 1 && samples.elementAt(1).time <= stamp - WINDOW_MS) samples.removeFirst()
        val first = samples.first
        val elapsed = stamp - first.time
        return if (elapsed < MIN_SAMPLE_MS) null else ((bytes - first.bytes) * 1_000 / elapsed).coerceAtLeast(0)
    }

    private companion object {
        const val WINDOW_MS = 3_000L
        const val MIN_SAMPLE_MS = 300L
    }
}
