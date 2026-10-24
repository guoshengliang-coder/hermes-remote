package com.hermes.client.update

import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadProgressTest {
    private var time = 0L
    private val progress = UpdateDownloadProgress { time }
    private val version = UpdateVersion("test", 9999, "com.hermes.remote", "internal", "2026-10-24T00:00:00Z",
        "test.apk", "https://mrlgs.net/releases/test.apk", 16L * 1024 * 1024,
        "a".repeat(64), "b".repeat(64), 26, listOf("test"), "abcdef1")
    private fun record(downloaded: Long = 0, status: UpdateDownloadStatus = UpdateDownloadStatus.RUNNING) =
        UpdateDownloadRecord(version, version.sizeBytes, listOf(UpdateDownloadSegment(0, version.sizeBytes - 1, downloaded)), status = status)

    @Test fun `live bytes do not change durable resume positions`() {
        progress.reset(0)
        val checkpoint = record()
        time = 500
        progress.advance(64 * 1024)
        assertEquals(64L * 1024, progress.snapshot(checkpoint).downloaded)
        assertEquals(0L, checkpoint.downloadedBytes)
    }

    @Test fun `another workers checkpoint and retry do not rewind display or double count bytes`() {
        progress.reset(0)
        time = 1_000
        progress.advance(1024 * 1024)
        progress.advance(512 * 1024)
        assertEquals(1024L * 1024, progress.snapshot(record(512 * 1024)).downloaded)
        assertEquals(1024L * 1024, progress.snapshot(record()).bytesPerSecond)
    }

    @Test fun `restored bytes are excluded from speed and the first sample is unknown`() {
        val saved = 4L * 1024 * 1024
        progress.reset(saved)
        assertNull(progress.snapshot(record(saved)).bytesPerSecond)
        time = 1_000
        progress.advance(saved + 1024 * 1024)
        assertEquals(1024L * 1024, progress.snapshot(record(saved)).bytesPerSecond)
    }

    @Test fun `speed decays to zero when no new bytes arrive`() {
        progress.reset(0)
        time = 1_000; progress.advance(1024 * 1024)
        time = 4_100
        assertEquals(0L, progress.snapshot(record()).bytesPerSecond)
    }

    @Test fun `pause and retry reset optimistic bytes and the speed window`() {
        progress.reset(0)
        time = 1_000; progress.advance(1024 * 1024)
        val paused = record(512 * 1024, UpdateDownloadStatus.PAUSED)
        assertEquals(512L * 1024, progress.snapshot(paused).downloaded)
        assertNull(progress.snapshot(paused).bytesPerSecond)
        progress.reset(paused.downloadedBytes)
        assertEquals(paused.downloadedBytes, progress.snapshot(record(paused.downloadedBytes)).downloaded)
        assertNull(progress.snapshot(record()).bytesPerSecond)
    }

    @Test fun `a fresh task or source restart discards the old progress and rate`() {
        progress.reset(0)
        time = 1_000; progress.advance(1024 * 1024)
        progress.reset(0)
        assertEquals(0L, progress.snapshot(record()).downloaded)
        assertNull(progress.snapshot(record()).bytesPerSecond)
    }

    @Test fun `progress never exceeds the declared file length`() {
        progress.reset(0)
        progress.advance(version.sizeBytes + 1)
        assertEquals(version.sizeBytes, progress.snapshot(record()).downloaded)
    }
}
