package com.hermes.client.data.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import kotlinx.coroutines.*

/** Runs on Media3's loader thread. URLs contain no Gateway address, path, or credentials. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ArtifactVideoDataSource(
    private val repository: ArtifactRangeRepository,
    private val source: ArtifactRangeRepository.Source,
) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    private var bufferedStart = -1L
    private var buffered: ByteArray? = null
    @Volatile private var request: Job? = null
    @Volatile private var closed = true
    @Volatile private var loader = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ahead: VideoReadAhead? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        loader.cancel()
        loader = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        buffered = null; bufferedStart = -1L
        ahead = VideoReadAhead(loader, source.descriptor.maxConcurrentRanges, source.descriptor.maxRangeBytes,
            { start, blocks -> repository.read(source, start, blocks) })
        closed = false
        io { repository.checkOwner(source.owner, source.deviceId) }
        if (dataSpec.position > source.size) throw IOException("video position outside source")
        position = dataSpec.position
        remaining = (source.size - position).let { if (dataSpec.length == C.LENGTH_UNSET.toLong()) it else minOf(it, dataSpec.length) }
        uri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val start = position / ArtifactRangeCache.BLOCK_BYTES * ArtifactRangeCache.BLOCK_BYTES
        val block = io {
            repository.checkOwner(source.owner, source.deviceId)
            if (buffered != null && position >= bufferedStart && position < bufferedStart + buffered!!.size) buffered!!
            else {
                val bytes = requireNotNull(ahead).load(start, position + remaining)
                buffered = bytes; bufferedStart = start
                bytes
            }
        }
        val blockOffset = (position - bufferedStart).toInt()
        val count = minOf(length.toLong(), remaining, (block.size - blockOffset).toLong()).toInt()
        block.copyInto(buffer, offset, blockOffset, blockOffset + count)
        position += count; remaining -= count
        bytesTransferred(count)
        return count
    }

    private fun <T> io(block: suspend () -> T): T {
        val job = Job(loader.coroutineContext[Job])
        request = job
        if (closed) job.cancel()
        return try { runBlocking(job + Dispatchers.IO) { block() } }
        catch (error: Exception) { throw IOException("video range read failed", error) }
        finally { job.complete(); request = null }
    }

    override fun getUri(): Uri? = uri
    override fun close() {
        closed = true
        request?.cancel()
        loader.cancel(); ahead = null
        uri = null
        buffered = null; bufferedStart = -1L
        if (opened) { opened = false; transferEnded() }
    }
}
