package com.hermes.client.data.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Loader-thread owned window. Children only return results; they never mutate the window.
 * Initial/seek reads stay small. Completed ranges can be consumed independently of later ones.
 */
internal class VideoReadAhead(
    private val scope: CoroutineScope,
    concurrency: Int,
    maxRangeBytes: Int,
    private val read: suspend (Long, Int) -> ByteArray,
    private val nanoTime: () -> Long = System::nanoTime,
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
        val bytes = if (ready == null) read(start, 1) else {
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
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                reset()
                // Optional speculation never owns error presentation or authoritative retry.
                read(start, 1)
            }
        }
        currentCoroutineContext().ensureActive()
        previousEnd = start + bytes.size
        if (sequential) {
            var next = pending.values.lastOrNull()?.end ?: previousEnd
            while (pending.size < depth && next < end) {
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
        return bytes
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
