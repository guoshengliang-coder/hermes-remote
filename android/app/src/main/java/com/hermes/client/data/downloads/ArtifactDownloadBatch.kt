package com.hermes.client.data.downloads

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.video.ArtifactRangeCache
import com.hermes.client.data.video.ArtifactRangeRepository
import com.hermes.client.domain.ArtifactDownloadDescriptor
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Parallel network reads, but only a fully verified, fsynced sequential prefix is committed.
 * At most four 1 MiB ranges are held in memory. No sparse progress or new journal format.
 */
internal class ArtifactDownloadBatch(private val rest: HermesRestApi, private val ranges: ArtifactRangeRepository) {
    private data class Piece(val start: Long, val count: Int, val cached: ByteArray?)

    suspend fun append(descriptor: ArtifactDownloadDescriptor, file: File, device: String?, owner: String,
        path: String, rangeBytes: Int = ArtifactRangeCache.BLOCK_BYTES, progress: (Long) -> Unit) = coroutineScope {
        ranges.checkOwner(owner, device)
        val start = file.length()
        // A small first batch avoids a large request before the link has delivered any data.
        val width = minOf(rangeBytes, descriptor.maxRangeBytes).coerceIn(256 * 1024, 1024 * 1024)
        val parallel = descriptor.maxConcurrentRanges.coerceIn(1, 4)
        val end = minOf(descriptor.sizeBytes, start + width.toLong() * parallel)
        val pieces = mutableListOf<Piece>()
        var cursor = start
        while (cursor < end) {
            val cached = ranges.cached(owner, path, descriptor, cursor)
            if (cached != null) {
                pieces += Piece(cursor, cached.size, cached); cursor += cached.size
            } else {
                val from = cursor
                // Stop at a cached block instead of downloading already verified playback bytes.
                cursor = minOf(end, cursor + ArtifactRangeCache.BLOCK_BYTES)
                while (cursor < end && cursor - from < width && ranges.cached(owner, path, descriptor, cursor) == null) {
                    cursor = minOf(end, from + width, cursor + ArtifactRangeCache.BLOCK_BYTES)
                }
                pieces += Piece(from, (cursor - from).toInt(), null)
            }
        }
        val slots = Semaphore(parallel)
        val progressLock = Any(); val received = LongArray(pieces.size)
        fun report(index: Int, bytes: Long) = synchronized(progressLock) {
            received[index] = bytes; progress(start + received.sum())
        }
        val data = pieces.mapIndexed { index, piece -> async {
            val bytes = piece.cached ?: slots.withPermit {
                rest.readArtifactRangeWithProgress(descriptor, piece.start, piece.count, device, owner) { report(index, it) }
            }
            check(bytes.size == piece.count) { "incomplete artifact batch" }
            report(index, bytes.size.toLong())
            bytes
        } }.awaitAll()
        ensureActive(); ranges.checkOwner(owner, device)
        FileOutputStream(file, true).use { output -> data.forEach(output::write); output.fd.sync() }
    }
}
