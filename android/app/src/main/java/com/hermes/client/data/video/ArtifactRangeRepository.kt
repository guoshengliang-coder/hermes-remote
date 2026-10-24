package com.hermes.client.data.video

import android.content.Context
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ArtifactDownloadDescriptor
import com.hermes.client.domain.ChatFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

@Singleton
class ArtifactRangeRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val rest: HermesRestApi,
) {
    val arbiter = ArtifactTransferArbiter()
    private val preferences by lazy { context.getSharedPreferences("video_cache_preferences", Context.MODE_PRIVATE) }
    private val _cacheState = kotlinx.coroutines.flow.MutableStateFlow(VideoCacheStats())
    val cacheState: kotlinx.coroutines.flow.StateFlow<VideoCacheStats> = _cacheState
    private val cache = ArtifactRangeCache(File(context.cacheDir, "video-ranges"),
        loadCapacity = { VideoCacheCapacity.from(runCatching { preferences.getInt("capacity_gib", 3) }.getOrDefault(3)) },
        changed = { _cacheState.value = it })
    private val cleanupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    suspend fun maintainCache() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cache.maintain() }
    suspend fun refreshCache(): VideoCacheStats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cache.refresh() }
    suspend fun clearCache(): VideoCacheStats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cache.clear() }
    suspend fun setCacheCapacity(value: VideoCacheCapacity) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!preferences.edit().putInt("capacity_gib", value.gib).commit()) throw IOException("cache preference write failed")
        cache.capacity(value)
    }

    /** Priority and bounded hot-block protection share the exact playback lifetime. */
    fun playbackLease(source: Source): AutoCloseable {
        val priority = arbiter.playbackLease(source.owner)
        val protection = cache.protect(source.key)
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                protection.close(); priority.close()
                if (cacheState.value.loaded) cleanupScope.launch {
                    try { cache.maintain(force = true) } catch (_: IOException) { /* Optional playback cache. */ }
                }
            }
        }
    }

    class Source internal constructor(val owner: String, val deviceId: String?, val path: String,
        @Volatile internal var descriptor: ArtifactDownloadDescriptor) {
        val size get() = descriptor.sizeBytes
        val key = ArtifactRangeCache.identity(owner, path, descriptor.etag, descriptor.sizeBytes)
        internal val parallel = descriptor.maxConcurrentRanges.coerceIn(1, 4)
        internal val slots = Semaphore(parallel)
        internal val renewal = Mutex()
        @Volatile internal var permitsEarlyRefill = false
    }

    suspend fun prepare(file: ChatFile, deviceId: String?): Source {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { cache.maintain() } catch (_: IOException) { /* A cache failure must not prevent streaming. */ }
        }
        val owner = rest.artifactOwnerKey(deviceId) ?: throw HermesApiException(401, "video owner unavailable")
        val path = file.remotePath ?: throw HermesApiException(404, "video reference unavailable")
        return Source(owner, deviceId, path, rest.prepareArtifactDownload(path, deviceId, owner))
    }

    suspend fun checkOwner(owner: String, deviceId: String?) {
        if (rest.artifactOwnerKey(deviceId) != owner) throw HermesApiException(401, "video owner changed", errorCode = "HR-AUTH-001")
    }

    /** Random reads including the tail index of MP4 files. Reference renewal cannot change generation. */
    suspend fun read(source: Source, position: Long, blocks: Int = 1): ByteArray {
        checkOwner(source.owner, source.deviceId)
        val cacheGeneration = cache.generation()
        require(position in 0 until source.size)
        val start = position / ArtifactRangeCache.BLOCK_BYTES * ArtifactRangeCache.BLOCK_BYTES
        val block = ArtifactRangeCache.BLOCK_BYTES
        require(blocks in 1..(source.descriptor.maxRangeBytes / block).coerceIn(1, 4))
        val count = minOf(blocks.toLong() * block, source.size - start).toInt()
        return arbiter.playback(source.owner) {
            checkOwner(source.owner, source.deviceId)
            val result = ByteArray(count)
            var offset = 0
            while (offset < count) {
                currentCoroutineContext().ensureActive()
                val at = start + offset
                val cached = cachedBlock(source.key, at, minOf(block, count - offset))
                if (cached != null) {
                    cached.copyInto(result, offset); offset += cached.size
                    continue
                }
                var missing = minOf(block, count - offset)
                // Stop at cached blocks instead of re-downloading bytes from a previous preview.
                while (offset + missing < count && cachedBlock(source.key, at + missing,
                        minOf(block, count - offset - missing)) == null) {
                    missing += minOf(block, count - offset - missing)
                }
                val bytes = fetch(source, at, missing)
                check(bytes.size == missing) { "incomplete video range" }
                currentCoroutineContext().ensureActive()
                checkOwner(source.owner, source.deviceId)
                // Publish only complete, validated blocks. Cache pressure is non-fatal.
                try {
                    cache.writeBlocks(source.key, sequence {
                        var cursor = 0
                        while (cursor < bytes.size) {
                            val end = minOf(bytes.size, cursor + block)
                            yield(at + cursor to bytes.copyOfRange(cursor, end)); cursor = end
                        }
                    }, expectedGeneration = cacheGeneration)
                } catch (_: IOException) { }
                bytes.copyInto(result, offset); offset += bytes.size
            }
            currentCoroutineContext().ensureActive()
            checkOwner(source.owner, source.deviceId)
            result
        }
    }

    private suspend fun fetch(source: Source, start: Long, count: Int): ByteArray {
        var attempted = source.descriptor
        suspend fun request(): ByteArray = source.slots.withPermit {
            checkOwner(source.owner, source.deviceId)
            attempted = source.descriptor
            val sample = VideoTransferSample()
            rest.readArtifactRangeWithProgress(attempted, start, count, source.deviceId, source.owner,
                sample::received).also { source.permitsEarlyRefill = sample.permitsEarlyRefill() }
        }
        return try { request() }
        catch (error: Exception) {
            source.permitsEarlyRefill = false
            currentCoroutineContext().ensureActive()
            if (!(error is IOException || error is HermesApiException && error.errorCode == "HR-FILE-015")) throw error
            source.renewal.withLock {
                // Drain old-reference requests before replacing it: the server permits only one
                // opaque reference per Mac. Concurrent failures share one generation-checked renewal.
                var acquired = 0
                try {
                    repeat(source.parallel) { source.slots.acquire(); acquired++ }
                    checkOwner(source.owner, source.deviceId)
                    if (source.descriptor === attempted) {
                        val renewed = rest.prepareArtifactDownload(source.path, source.deviceId, source.owner)
                        if (renewed.etag != attempted.etag || renewed.sizeBytes != attempted.sizeBytes) {
                            throw HermesApiException(412, "video source changed", errorCode = "HR-FILE-011")
                        }
                        source.descriptor = renewed
                    }
                } finally { repeat(acquired) { source.slots.release() } }
            }
            request()
        }
    }

    fun cached(owner: String, path: String, descriptor: ArtifactDownloadDescriptor, position: Long): ByteArray? {
        if (position % ArtifactRangeCache.BLOCK_BYTES != 0L) return null
        val count = minOf(ArtifactRangeCache.BLOCK_BYTES.toLong(), descriptor.sizeBytes - position).toInt()
        return cachedBlock(ArtifactRangeCache.identity(owner, path, descriptor.etag, descriptor.sizeBytes), position, count)
    }

    private fun cachedBlock(key: String, start: Long, count: Int): ByteArray? =
        try { cache.read(key, start, count) } catch (_: IOException) { null }

    /** Download data becomes cache-visible only after HTTP validation and the durable checkpoint. */
    fun publish(owner: String, path: String, descriptor: ArtifactDownloadDescriptor, file: File, from: Long) {
        // Other complete attachments have their own durable storage; don't fill the video pool.
        if (!descriptor.contentType.startsWith("video/", true) &&
            path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) !in setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi", "mpeg", "mpg")) return
        val key = ArtifactRangeCache.identity(owner, path, descriptor.etag, descriptor.sizeBytes)
        val cacheGeneration = cache.generation()
        var start = ((from + ArtifactRangeCache.BLOCK_BYTES - 1) / ArtifactRangeCache.BLOCK_BYTES) * ArtifactRangeCache.BLOCK_BYTES
        try {
            RandomAccessFile(file, "r").use { input ->
                cache.writeBlocks(key, sequence {
                    while (start < descriptor.sizeBytes) {
                        val count = minOf(ArtifactRangeCache.BLOCK_BYTES.toLong(), descriptor.sizeBytes - start).toInt()
                        if (start + count > file.length()) break
                        // Cached playback blocks need no second write into the same cache.
                        if (cachedBlock(key, start, count) == null) {
                            val data = ByteArray(count); input.seek(start); input.readFully(data)
                            yield(start to data)
                        }
                        start += count
                    }
                }, expectedGeneration = cacheGeneration)
            }
        } catch (_: IOException) { /* Optional cache, durable download remains valid. */ }
    }
}
