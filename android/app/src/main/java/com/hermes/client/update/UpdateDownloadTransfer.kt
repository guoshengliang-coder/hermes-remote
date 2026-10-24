package com.hermes.client.update

import com.hermes.client.data.error.AppErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * A download failure that already knows how the page should behave. [retryable] means the task
 * stays resumable: the service waits, then continues from the committed offsets.
 */
class UpdateDownloadFailure(
    val code: AppErrorCode,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private class SourceChangedException : IOException("update source changed")

/**
 * Resumable, range-parallel APK transfer (HG-223).
 *
 * Contract with the release server (docs/APP_UPDATE.md): strong ETag equal to the manifest
 * SHA-256, byte ranges on GET, and `If-Range` so a changed file is never spliced into an old
 * partial download. Every committed chunk is fsynced before its offset is journaled, so a crash
 * can waste at most the chunk in flight — never bytes already counted as progress.
 */
class UpdateDownloadTransfer(
    private val callFactory: (Request) -> okhttp3.Call,
    private val directory: File,
    private val commit: (UpdateDownloadRecord) -> Unit,
    private val progress: (UpdateDownloadRecord) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    /** Download [record]'s missing ranges; returns the record with every segment committed. */
    suspend fun run(record: UpdateDownloadRecord): UpdateDownloadRecord = withContext<UpdateDownloadRecord>(Dispatchers.IO) {
        var attempt = 0
        var current = record
        while (true) {
            try {
                return@withContext transfer(current)
            } catch (changed: SourceChangedException) {
                // The origin now serves different bytes for this URL. Restart once with the new
                // validator; a second change would mean the channel is broken, not a stale file.
                if (attempt++ > 0) {
                    throw UpdateDownloadFailure(AppErrorCode.UPDATE_DOWNLOAD_FAILED, true, "update source changed twice", changed)
                }
                current = reset(current)
            }
        }
        throw IllegalStateException("unreachable")
    }

    private suspend fun transfer(input: UpdateDownloadRecord): UpdateDownloadRecord {
        val part = updateDownloadPartial(directory, input.version)
        directory.mkdirs()
        if (!part.isFile) {
            if (input.downloadedBytes > 0) throw SourceChangedException()
            part.createNewFile()
        }
        RandomAccessFile(part, "rw").use { it.setLength(input.totalBytes) }

        val etag = readValidator(input)
        var record = input.copy(etag = etag ?: input.etag, status = UpdateDownloadStatus.RUNNING, errorCode = null)
        commit(record)

        val positions = record.segments.map { it.downloaded }.toLongArray()
        // One lock orders live snapshots and journal commits from all four workers. Taking
        // separate snapshots of each position could publish stale totals after newer ones.
        val stateLock = Any()
        var lastPublished = 0L
        var lastPublishedAt = 0L
        fun publish(force: Boolean = false) {
            val total = positions.sum()
            val stamp = now()
            if (!force && stamp - lastPublishedAt < PROGRESS_INTERVAL_MS) return
            if (total == lastPublished && !force) return
            lastPublished = total
            lastPublishedAt = stamp
            val live = record.segments.mapIndexed { index, segment ->
                segment.copy(downloaded = positions[index])
            }
            progress(record.copy(segments = live, status = UpdateDownloadStatus.RUNNING))
        }

        val segments = record.segments
        coroutineScope {
            segments.indices.map { index ->
                async(Dispatchers.IO) {
                    var segment = segments[index]
                    val channel = RandomAccessFile(part, "rw").channel
                    try {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val range = segment.nextRange() ?: break
                            val received = downloadRange(input.copy(etag = etag ?: input.etag), range, channel) { live ->
                                synchronized(stateLock) {
                                    positions[index] = segment.downloaded + live
                                    publish()
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            channel.force(false)
                            currentCoroutineContext().ensureActive()
                            segment = segment.advance(range, received)
                            synchronized(stateLock) {
                                positions[index] = segment.downloaded
                                record = record.copy(segments = record.segments.toMutableList().also { it[index] = segment })
                                commit(record)
                                publish(force = true)
                            }
                        }
                    } finally {
                        channel.close()
                    }
                    Unit
                }
            }.awaitAll()
        }

        currentCoroutineContext().ensureActive()
        if (!record.complete) throw UpdateDownloadFailure(AppErrorCode.UPDATE_DOWNLOAD_FAILED, true, "incomplete ranges")
        val target = updateDownloadTarget(directory, record.version)
        if (target.exists() && !target.delete()) throw UpdateDownloadFailure(AppErrorCode.UPDATE_CLEANUP_FAILED, true, "cannot replace APK")
        if (!part.renameTo(target)) throw UpdateDownloadFailure(AppErrorCode.UPDATE_CLEANUP_FAILED, true, "cannot finalize APK")
        return record.copy(status = UpdateDownloadStatus.COMPLETE, errorCode = null).also(commit)
    }

    /**
     * HEAD pins the validator before parallel ranges start. Without it the first four requests
     * would each have to discover a changed file independently.
     */
    private fun readValidator(record: UpdateDownloadRecord): String? {
        val request = Request.Builder().url(record.version.downloadUrl).head().build()
        callFactory(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 405 || response.code == 501) return record.etag
                throw failureFor(response.code, "HEAD")
            }
            val length = response.header("Content-Length")?.toLongOrNull()
            if (length != null && length != record.totalBytes) throw SourceChangedException()
            val etag = response.header("ETag")
            if (record.etag != null && etag != null && etag != record.etag) throw SourceChangedException()
            return etag ?: record.etag
        }
    }

    /** Request one bounded range and write it at its absolute offset. */
    private suspend fun downloadRange(
        record: UpdateDownloadRecord,
        range: LongRange,
        channel: java.nio.channels.FileChannel,
        onBytes: (Long) -> Unit,
    ): Long {
        var attempt = 0
        while (true) {
            onBytes(0)
            try {
                return requestRange(record, range, channel, onBytes)
            } catch (error: SourceChangedException) {
                throw error
            } catch (error: UpdateDownloadFailure) {
                throw error
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt++ >= MAX_CHUNK_ATTEMPTS) {
                    throw UpdateDownloadFailure(AppErrorCode.UPDATE_DOWNLOAD_FAILED, true, "range download failed", error)
                }
                delay(500L * attempt)
            }
        }
    }

    private suspend fun requestRange(
        record: UpdateDownloadRecord,
        range: LongRange,
        channel: java.nio.channels.FileChannel,
        onBytes: (Long) -> Unit,
    ): Long {
        val builder = Request.Builder().url(record.version.downloadUrl).get()
            .header("Range", "bytes=${range.first}-${range.last}")
        // A validator pins the partial file. Without one a resumed download could splice two
        // different builds together, so a server that cannot prove identity forces a restart.
        record.etag?.let { builder.header("If-Range", it) }
        callFactory(builder.build()).execute().use { response ->
            when {
                response.code == 200 -> throw SourceChangedException()
                response.code == 206 -> Unit
                else -> throw failureFor(response.code, "range")
            }
            val expected = range.last - range.first + 1
            val contentRange = response.header("Content-Range")
            if (contentRange != "bytes ${range.first}-${range.last}/${record.totalBytes}") {
                throw UpdateDownloadFailure(AppErrorCode.UPDATE_DOWNLOAD_FAILED, true, "invalid content-range")
            }
            record.etag?.let { pinned ->
                response.header("ETag")?.let { if (it != pinned) throw SourceChangedException() }
            }
            var received = 0L
            val buffer = ByteBuffer.allocate(BUFFER_BYTES)
            response.body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer.array())
                    if (read < 0) break
                    if (received + read > expected) {
                        throw UpdateDownloadFailure(AppErrorCode.UPDATE_DOWNLOAD_FAILED, true, "range overshoot")
                    }
                    buffer.clear().limit(read)
                    var position = range.first + received
                    while (buffer.hasRemaining()) {
                        val written = channel.write(buffer, position)
                        if (written <= 0) throw IOException("short write")
                        position += written
                    }
                    received += read
                    onBytes(received)
                }
            }
            if (received != expected) throw IOException("incomplete range $received/$expected")
            return received
        }
    }

    private fun failureFor(code: Int, stage: String): UpdateDownloadFailure {
        val retryable = code == 408 || code == 429 || code >= 500
        return UpdateDownloadFailure(AppErrorCode.UPDATE_DOWNLOAD_FAILED, retryable, "$stage rejected with HTTP $code")
    }

    private fun reset(record: UpdateDownloadRecord): UpdateDownloadRecord {
        updateDownloadPartial(directory, record.version).delete()
        val fresh = record.copy(
            segments = planUpdateSegments(record.totalBytes),
            etag = null,
            status = UpdateDownloadStatus.WAITING,
            errorCode = null,
        )
        commit(fresh)
        return fresh
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 300L
        const val MAX_CHUNK_ATTEMPTS = 3
    }
}
