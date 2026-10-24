package com.hermes.client.update

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Pure state and range planning for the in-app update downloader (HG-223).
 *
 * The page used to hand the APK to Android's DownloadManager. That downloader deletes the
 * partial file when it gives up, and it refuses to resume a transfer whose server sent no ETag
 * (AOSP DownloadThread: "To resume an interrupted download, we need an ETag"). Our release
 * server sent none, so every interruption after the first byte became the terminal
 * STATUS_CANNOT_RESUME the report shows as HR-UPDATE-004.
 *
 * The replacement keeps its own bytes and its own checkpoints, so leaving the page, losing the
 * network, or a process death only pauses work: the next attempt continues at the committed
 * offset instead of starting over. Ranges are split four ways because a single stream to the
 * Hong Kong origin is what made a 32 MB APK feel slow on a fast home connection.
 *
 * This file deliberately holds no Android types: the planner and the checkpoint math are unit
 * tested on the JVM.
 */

const val UPDATE_DOWNLOAD_CONNECTIONS = 4

/** Bytes requested per HTTP call. Bounds how much work a single dropped connection can waste. */
const val UPDATE_DOWNLOAD_CHUNK_BYTES = 4L * 1024 * 1024

@Serializable
data class UpdateDownloadSegment(
    val start: Long,
    val end: Long,
    /** Bytes committed to disk for this segment, always counted from [start]. */
    val downloaded: Long = 0,
) {
    val length: Long get() = end - start + 1
    val complete: Boolean get() = downloaded >= length
}

@Serializable
enum class UpdateDownloadStatus { WAITING, RUNNING, PAUSED, FAILED, COMPLETE }

/**
 * Everything needed to continue a download after the process dies. `etag` pins the bytes: a
 * changed validator restarts the file rather than splicing two releases together.
 */
@Serializable
data class UpdateDownloadRecord(
    val version: UpdateVersion,
    val totalBytes: Long,
    val segments: List<UpdateDownloadSegment>,
    val etag: String? = null,
    val status: UpdateDownloadStatus = UpdateDownloadStatus.WAITING,
    /** AppErrorCode value (for example HR-UPDATE-010) for a failed or paused task. */
    val errorCode: String? = null,
) {
    val downloadedBytes: Long get() = segments.sumOf { it.downloaded }
    val complete: Boolean get() = segments.all { it.complete }
}

/**
 * Split [sizeBytes] into [connections] contiguous ranges. Extra bytes go to the earliest
 * segments so the lengths differ by at most one.
 */
fun planUpdateSegments(sizeBytes: Long, connections: Int = UPDATE_DOWNLOAD_CONNECTIONS): List<UpdateDownloadSegment> {
    require(sizeBytes > 0) { "size must be positive" }
    val count = connections.coerceIn(1, 8)
    val base = sizeBytes / count
    val extra = sizeBytes % count
    var cursor = 0L
    return (0 until count).mapNotNull { index ->
        val length = base + if (index < extra) 1 else 0
        if (length <= 0) return@mapNotNull null
        UpdateDownloadSegment(cursor, cursor + length - 1).also { cursor += length }
    }.also { require(it.sumOf(UpdateDownloadSegment::length) == sizeBytes) }
}

/** The next inclusive byte range to request for [this] segment, or null when it is done. */
fun UpdateDownloadSegment.nextRange(chunkBytes: Long = UPDATE_DOWNLOAD_CHUNK_BYTES): LongRange? {
    if (complete) return null
    val from = start + downloaded
    return from..minOf(end, from + chunkBytes - 1)
}

/** Mark [received] fresh bytes for a segment that requested [range]. */
fun UpdateDownloadSegment.advance(range: LongRange, received: Long): UpdateDownloadSegment {
    require(range.first == start + downloaded) { "range does not continue this segment" }
    require(received in 0..(range.last - range.first + 1)) { "received exceeds the requested range" }
    return copy(downloaded = downloaded + received)
}

fun updateDownloadTarget(directory: File, version: UpdateVersion): File = File(directory, version.fileName)

/** Half-downloaded APK. Never renamed to the target until every byte passed SHA-256. */
fun updateDownloadPartial(directory: File, version: UpdateVersion): File = File(directory, "${version.fileName}.part")
