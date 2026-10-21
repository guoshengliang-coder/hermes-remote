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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Singleton
class ArtifactRangeRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val rest: HermesRestApi,
) {
    val arbiter = ArtifactTransferArbiter()
    private val cache = ArtifactRangeCache(File(context.cacheDir, "video-ranges"))

    class Source internal constructor(val owner: String, val deviceId: String?, val path: String,
        internal var descriptor: ArtifactDownloadDescriptor) {
        val size get() = descriptor.sizeBytes
        val key = ArtifactRangeCache.identity(owner, path, descriptor.etag, descriptor.sizeBytes)
    }

    suspend fun prepare(file: ChatFile, deviceId: String?): Source {
        val owner = rest.artifactOwnerKey(deviceId) ?: throw HermesApiException(401, "video owner unavailable")
        val path = file.remotePath ?: throw HermesApiException(404, "video reference unavailable")
        return Source(owner, deviceId, path, rest.prepareArtifactDownload(path, deviceId, owner))
    }

    suspend fun checkOwner(owner: String, deviceId: String?) {
        if (rest.artifactOwnerKey(deviceId) != owner) throw HermesApiException(401, "video owner changed", errorCode = "HR-AUTH-001")
    }

    /** Random reads including the tail index of MP4 files. Reference renewal cannot change generation. */
    suspend fun read(source: Source, position: Long): ByteArray {
        checkOwner(source.owner, source.deviceId)
        require(position in 0 until source.size)
        val start = position / ArtifactRangeCache.BLOCK_BYTES * ArtifactRangeCache.BLOCK_BYTES
        val count = minOf(ArtifactRangeCache.BLOCK_BYTES.toLong(), source.size - start).toInt()
        return arbiter.playback(source.owner) {
            checkOwner(source.owner, source.deviceId)
            cache.read(source.key, start, count)?.let { return@playback it }
            val bytes = try { rest.readArtifactRange(source.descriptor, start, count, source.deviceId, source.owner) }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!(error is IOException || error is HermesApiException && error.errorCode == "HR-FILE-015")) throw error
                val renewed = rest.prepareArtifactDownload(source.path, source.deviceId, source.owner)
                if (renewed.etag != source.descriptor.etag || renewed.sizeBytes != source.size) {
                    throw HermesApiException(412, "video source changed", errorCode = "HR-FILE-011")
                }
                source.descriptor = renewed
                rest.readArtifactRange(renewed, start, count, source.deviceId, source.owner)
            }
            checkOwner(source.owner, source.deviceId)
            // Cache pressure must not make otherwise playable remote video fail.
            try { cache.write(source.key, start, bytes) } catch (_: IOException) { }
            bytes
        }
    }

    fun cached(owner: String, path: String, descriptor: ArtifactDownloadDescriptor, position: Long): ByteArray? {
        if (position % ArtifactRangeCache.BLOCK_BYTES != 0L) return null
        val count = minOf(ArtifactRangeCache.BLOCK_BYTES.toLong(), descriptor.sizeBytes - position).toInt()
        return cache.read(ArtifactRangeCache.identity(owner, path, descriptor.etag, descriptor.sizeBytes), position, count)
    }

    /** Download data becomes cache-visible only after HTTP validation and the durable checkpoint. */
    fun publish(owner: String, path: String, descriptor: ArtifactDownloadDescriptor, file: File, from: Long) {
        val key = ArtifactRangeCache.identity(owner, path, descriptor.etag, descriptor.sizeBytes)
        var start = ((from + ArtifactRangeCache.BLOCK_BYTES - 1) / ArtifactRangeCache.BLOCK_BYTES) * ArtifactRangeCache.BLOCK_BYTES
        try {
            RandomAccessFile(file, "r").use { input ->
                while (start < descriptor.sizeBytes) {
                    val count = minOf(ArtifactRangeCache.BLOCK_BYTES.toLong(), descriptor.sizeBytes - start).toInt()
                    if (start + count > file.length()) break
                    val data = ByteArray(count); input.seek(start); input.readFully(data)
                    cache.write(key, start, data)
                    start += count
                }
            }
        } catch (_: IOException) { /* Optional cache, durable download remains valid. */ }
    }
}
