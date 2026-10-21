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
}
