package com.hermes.client.data.video

import java.io.File
import java.nio.file.Files
import org.junit.*
import org.junit.Assert.*

class ArtifactRangeCacheTest {
    private lateinit var directory: File
    @Before fun setup() { directory = Files.createTempDirectory("video-cache").toFile() }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun key(owner: String = "owner", etag: String = "etag", size: Long = 8) = ArtifactRangeCache.identity(owner, "/video.mp4", etag, size)

    @Test fun cache_survives_recreation_but_isolates_owner_generation_and_size() {
        ArtifactRangeCache(directory).write(key(), 0, byteArrayOf(1, 2, 3))
        val restored = ArtifactRangeCache(directory)
        assertArrayEquals(byteArrayOf(1, 2, 3), restored.read(key(), 0, 3))
        assertNull(restored.read(key(owner = "other-Mac"), 0, 3))
        assertNull(restored.read(key(etag = "changed"), 0, 3))
        assertNull(restored.read(key(size = 9), 0, 3))
    }

    @Test fun eviction_respects_limit_and_corruption_is_a_miss() {
        val cache = ArtifactRangeCache(directory, 80)
        cache.write(key(), 0, ByteArray(4) { 1 })
        Thread.sleep(5)
        cache.write(key(), ArtifactRangeCache.BLOCK_BYTES.toLong(), ByteArray(4) { 2 })
        Thread.sleep(5)
        cache.write(key(), 2L * ArtifactRangeCache.BLOCK_BYTES, ByteArray(4) { 3 })
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 80)
        assertNull(cache.read(key(), 0, 4))
        val file = directory.listFiles()!!.first { it.name.endsWith("-${2L * ArtifactRangeCache.BLOCK_BYTES}.block") }
        java.io.RandomAccessFile(file, "rw").use { it.seek(39); it.write(9) }
        assertNull(cache.read(key(), 2L * ArtifactRangeCache.BLOCK_BYTES, 4))
        assertFalse(file.exists())
    }

    @Test fun truncated_cache_and_uncommitted_parts_never_look_complete() {
        val cache = ArtifactRangeCache(directory)
        cache.write(key(), 0, ByteArray(8))
        assertNull(cache.read(key(), 0, 7))
        File(directory, "crash.part").writeText("partial")
        assertNull(cache.read(key(), 0, 8))
    }
    @Test fun shared_capacity_uses_last_access_and_trims_to_low_watermark() {
        var now = 1_000_000L
        var stats = VideoCacheStats()
        val cache = ArtifactRangeCache(directory, 200, 120, clock = { now }, changed = { stats = it })
        repeat(5) { cache.write(key(), it.toLong() * ArtifactRangeCache.BLOCK_BYTES, ByteArray(4)); now++ }
        assertNotNull(cache.read(key(), 0, 4)) // The first-created block is recently used.
        cache.write(key(owner = "second-video"), 0, ByteArray(4))
        assertEquals(120L, stats.usedBytes)
        assertNotNull(cache.read(key(), 0, 4))
        assertNull(cache.read(key(), ArtifactRangeCache.BLOCK_BYTES.toLong(), 4))
        assertNotNull(cache.read(key(owner = "second-video"), 0, 4))
    }

    @Test fun thirty_day_expiry_is_based_on_use_and_survives_restart() {
        var now = 40L * 86_400_000
        val cache = ArtifactRangeCache(directory, clock = { now })
        cache.write(key(), 0, ByteArray(4)); cache.write(key(), ArtifactRangeCache.BLOCK_BYTES.toLong(), ByteArray(4))
        now += 20L * 86_400_000
        assertNotNull(cache.read(key(), 0, 4))
        now += 11L * 86_400_000
        val restored = ArtifactRangeCache(directory, clock = { now })
        restored.maintain()
        assertNotNull(restored.read(key(), 0, 4))
        assertNull(restored.read(key(), ArtifactRangeCache.BLOCK_BYTES.toLong(), 4))
    }

    @Test fun manual_clear_preserves_hot_blocks_defers_completion_and_prevents_refill() {
        var stats = VideoCacheStats()
        val cache = ArtifactRangeCache(directory, changed = { stats = it })
        cache.write(key(owner = "cold"), 0, ByteArray(4))
        val first = cache.protect(key()); val second = cache.protect(key())
        cache.write(key(), 0, ByteArray(4))
        cache.clear()
        assertTrue(stats.clearPending); assertEquals(40L, stats.usedBytes)
        assertNull(cache.read(key(owner = "cold"), 0, 4))
        assertNotNull(cache.read(key(), 0, 4))
        cache.write(key(), ArtifactRangeCache.BLOCK_BYTES.toLong(), ByteArray(4))
        assertEquals(40L, stats.usedBytes)
        first.close(); first.close(); cache.maintain(force = true)
        assertTrue(stats.clearPending)
        second.close(); cache.maintain(force = true)
        assertEquals(0L, stats.usedBytes); assertFalse(stats.clearPending)
    }

    @Test fun low_storage_evicts_then_bypasses_writes_until_recovery() {
        var available = 500L
        var stats = VideoCacheStats()
        val cache = ArtifactRangeCache(directory, freeBytes = { available }, lowSpaceBytes = 100,
            resumeSpaceBytes = 200, changed = { stats = it })
        cache.write(key(), 0, ByteArray(4))
        available = 50; cache.maintain(force = true)
        assertEquals(0L, stats.usedBytes); assertTrue(stats.lowSpace)
        cache.write(key(), 0, ByteArray(4)); assertEquals(0L, stats.usedBytes)
        available = 150
        cache.write(key(), 0, ByteArray(4)); assertEquals(0L, stats.usedBytes)
        available = 300
        cache.write(key(), 0, ByteArray(4)); assertEquals(40L, stats.usedBytes); assertFalse(stats.lowSpace)
    }

    @Test fun bounded_protection_cannot_defeat_capacity_limit() {
        var stats = VideoCacheStats()
        val cache = ArtifactRangeCache(directory, 80, 40, changed = { stats = it })
        val lease = cache.protect(key())
        repeat(10) { cache.write(key(), it.toLong() * ArtifactRangeCache.BLOCK_BYTES, ByteArray(4)) }
        assertTrue(stats.usedBytes <= 80)
        assertNotNull(cache.read(key(), 0, 4))
        lease.close(); cache.maintain(force = true)
        cache.write(key(owner = "other"), 0, ByteArray(4))
        assertTrue(stats.usedBytes <= 80)
    }

    @Test fun warm_access_does_not_rescan_directory_and_external_reclamation_is_reconciled() {
        val cache = ArtifactRangeCache(directory)
        repeat(1000) { cache.write(key(), it.toLong() * ArtifactRangeCache.BLOCK_BYTES, ByteArray(4)) }
        repeat(1000) { assertNotNull(cache.read(key(), it.toLong() * ArtifactRangeCache.BLOCK_BYTES, 4)) }
        assertEquals(1, cache.directoryScans)
        directory.listFiles()!!.forEach { it.delete() }
        assertEquals(0L, cache.refresh().usedBytes)
        assertEquals(2, cache.directoryScans)
    }

    @Test fun capacity_defaults_and_ranges_are_long_and_initialization_is_lazy() {
        var loaded = false
        var stats = VideoCacheStats()
        val cache = ArtifactRangeCache(directory, loadCapacity = { loaded = true; VideoCacheCapacity.LARGE }, changed = { stats = it })
        val lease = cache.protect(key()); assertFalse(loaded); assertEquals(0, cache.directoryScans)
        cache.write(key(), 0, ByteArray(4))
        assertEquals(5L * GIB, stats.maxBytes)
        assertEquals(3L * GIB, VideoCacheCapacity.from(-1).bytes)
        assertEquals(GIB * 5 / 2, VideoCacheCapacity.DEFAULT.trimTo)
        lease.close()
    }

    @Test fun cleanup_generation_prevents_late_network_results_from_refilling_cache() {
        val cache = ArtifactRangeCache(directory)
        val before = cache.generation()
        cache.clear()
        cache.writeBlocks(key(), sequenceOf(0L to ByteArray(4)), before)
        assertEquals(0L, cache.refresh().usedBytes)
        cache.writeBlocks(key(), sequenceOf(0L to ByteArray(4)), cache.generation())
        assertNotNull(cache.read(key(), 0, 4))
    }
    @Test fun removed_cache_directory_is_recreated_without_restarting_app() {
        val cache = ArtifactRangeCache(directory)
        cache.write(key(), 0, ByteArray(4))
        directory.deleteRecursively()
        cache.write(key(), 0, ByteArray(4))
        assertNotNull(cache.read(key(), 0, 4))
    }

    @Test fun manual_clear_removes_zero_length_legacy_blocks_even_after_positive_bytes_are_gone() {
        val cache = ArtifactRangeCache(directory)
        cache.write(key(), 0, ByteArray(4))
        File(directory, "${key()}-${ArtifactRangeCache.BLOCK_BYTES}.block").createNewFile()
        val restored = ArtifactRangeCache(directory)
        assertEquals(0L, restored.clear().usedBytes)
        assertTrue(directory.listFiles()!!.isEmpty())

        File(directory, "${key()}-0.block").createNewFile()
        assertEquals(0L, ArtifactRangeCache(directory).clear().usedBytes)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun precache_can_evict_old_videos_but_never_cycles_its_own_retained_blocks() {
        val block = ArtifactRangeCache.BLOCK_BYTES
        val limit = 2L * (block + 36)
        val cache = ArtifactRangeCache(directory, limit, limit)
        cache.write(key(owner = "old"), 0, ByteArray(block))
        val video = key(size = 3L * block)
        val epoch = cache.generation()
        repeat(3) { cache.writeBlocks(video, sequenceOf(it.toLong() * block to ByteArray(block)), epoch, preserveSource = true) }
        val coverage = cache.coverage(video, 3L * block)
        assertEquals(2L * block, coverage.bytes)
        assertEquals(setOf(0L, block.toLong()), coverage.starts)
        assertFalse(coverage.complete)
        assertNull(cache.read(key(owner = "old"), 0, block))
        assertEquals(66, VideoPrecacheState(coverage.bytes, coverage.size).percent)
    }
    @Test fun coverage_checks_entire_file_including_head_holes_tail_and_reclaimed_or_corrupt_files() {
        val block = ArtifactRangeCache.BLOCK_BYTES
        val size = 2L * block + 17
        val video = key(size = size)
        val cache = ArtifactRangeCache(directory)
        cache.write(video, block.toLong(), ByteArray(block))
        cache.write(video, 2L * block, ByteArray(17))
        assertFalse(cache.coverage(video, size).complete)
        assertEquals(0L, cache.coverage(video, size).next(2L * block, emptySet()))
        cache.write(video, 0, ByteArray(block))
        assertTrue(cache.coverage(video, size).complete)
        val tail = directory.listFiles()!!.first { it.name.endsWith("-${2L * block}.block") }
        java.io.RandomAccessFile(tail, "rw").use { it.seek(36); it.write(9) }
        val restored = ArtifactRangeCache(directory)
        assertEquals(2L * block, restored.coverage(video, size).bytes)
        assertFalse(restored.coverage(video, size).complete)
        directory.listFiles()!!.first { it.name.endsWith("-0.block") }.delete()
        assertEquals(block.toLong(), restored.coverage(video, size).bytes)
        assertEquals(99, VideoPrecacheState(size - 1, size).percent)
    }

    @Test fun background_coverage_validation_keeps_the_actual_playback_block_protected() {
        val block = ArtifactRangeCache.BLOCK_BYTES
        val size = 69L * block
        val video = key(size = size)
        val cache = ArtifactRangeCache(directory)
        cache.writeBlocks(video, (0..68).asSequence().map { it.toLong() * block to ByteArray(block) },
            preserveSource = true)
        val lease = cache.protect(video)
        try {
            assertNotNull(cache.read(video, 0, block))
            cache.refresh() // Startup-style unverified entries, while actual playback protects block zero.
            repeat(18) { cache.coverage(video, size) }
            val cleared = cache.clear()
            assertEquals("background verification replaced playback's hot set", block + 36L, cleared.usedBytes)
            assertNotNull(cache.read(video, 0, block))
        } finally { lease.close(); cache.maintain(force = true) }
    }

}
