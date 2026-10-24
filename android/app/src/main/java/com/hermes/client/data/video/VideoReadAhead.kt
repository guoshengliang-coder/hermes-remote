package com.hermes.client.data.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Loader-thread owned window. Children only return results; they never mutate the window.
 * Initial/seek reads stay small. On measured fast links sequential reads refill before waiting, so
 * request latency overlaps without delivering unvalidated or out-of-order bytes.
 */
internal class VideoReadAhead(
    private val scope: CoroutineScope,
    concurrency: Int,
    maxRangeBytes: Int,
    private val read: suspend (Long, Int) -> ByteArray,
    private val nanoTime: () -> Long = System::nanoTime,
    private val permitsEarlyRefill: () -> Boolean = { false },
) {
    private data class Loaded(val bytes: ByteArray, val elapsedMs: Long)
    private data class Pending(val end: Long, val task: Deferred<Loaded>)
    private val pending = linkedMapOf<Long, Pending>()
    private val parallel = concurrency.coerceIn(1, 4)
    private val maxBlocks = (maxRangeBytes / BLOCK).coerceIn(1, 4)
    private var blocks = 1
    private var depth = minOf(2, parallel)
    private var previousEnd = -1L

    suspend fun load(start: Long, end: Long): ByteArray {
        val sequential = start == previousEnd
        if (!sequential) reset()
        val ready = pending.remove(start)
        val bytes = if (ready == null) {
            if (!sequential || parallel == 1 || !permitsEarlyRefill()) read(start, 1) else {
                // The second contiguous read confirms playback rather than index discovery.
                // Include the foreground request in the negotiated concurrency budget.
                val current = scope.async { read(start, 1) }
                try {
                    fill(minOf(end, start + BLOCK), end, minOf(depth, parallel - 1))
                    current.await()
                } catch (error: Exception) {
                    reset()
                    throw error
                } finally { current.cancel() }
            }
        } else {
            // Completed work can adapt the range size immediately; only an actual wait
            // needs an early refill using the previous size/depth.
            if (depth > 1 && permitsEarlyRefill() && !ready.task.isCompleted) {
                fill(ready.end, end, minOf(depth, parallel - 1))
            }
            try {
                val loaded = ready.task.await()
                when {
                    loaded.elapsedMs < 1_500 -> {
                        blocks = (blocks * 2).coerceAtMost(maxBlocks)
                        depth = (depth + 1).coerceAtMost(parallel)
                    }
                    loaded.elapsedMs > 3_000 -> {
                        blocks = (blocks / 2).coerceAtLeast(1)
                        depth = 1
                        while (pending.size > depth) {
                            pending.remove(pending.keys.last())?.task?.cancel()
                        }
                    }
                }
                loaded.bytes
            } catch (cancelled: CancellationException) {
                ready.task.cancel()
                reset()
                throw cancelled
            }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                reset()
                // Optional speculation never owns error presentation or authoritative retry.
                read(start, 1)
            }
        }
        currentCoroutineContext().ensureActive()
        previousEnd = start + bytes.size
        if (sequential) fill(previousEnd, end, depth)
        return bytes
    }

    private fun fill(fromEnd: Long, end: Long, limit: Int) {
        var next = pending.values.lastOrNull()?.end ?: fromEnd
        while (pending.size < limit && next < end) {
            val from = next
            val count = minOf(blocks.toLong() * BLOCK, end - from).toInt()
            val requestedBlocks = (count + BLOCK - 1) / BLOCK
            next = minOf(end, from + requestedBlocks.toLong() * BLOCK)
            pending[from] = Pending(next, scope.async {
                val at = nanoTime()
                Loaded(read(from, requestedBlocks), (nanoTime() - at) / 1_000_000)
            })
        }
    }

    private fun reset() {
        pending.values.forEach { it.task.cancel() }
        pending.clear()
        blocks = 1
        depth = minOf(2, parallel)
        previousEnd = -1L
    }

    private companion object { const val BLOCK = ArtifactRangeCache.BLOCK_BYTES }
}
