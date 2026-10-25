package com.hermes.client.data.video

import com.hermes.client.data.error.AppError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class VideoCacheCoverage(val bytes: Long, val size: Long, val starts: Set<Long>) {
    val complete get() = size > 0 && bytes == size
    fun next(near: Long, attempted: Set<Long>): Long? {
        val block = ArtifactRangeCache.BLOCK_BYTES.toLong()
        val pivot = (near.coerceIn(0, (size - 1).coerceAtLeast(0)) / block) * block
        fun missing(range: LongProgression) = range.firstOrNull { it !in starts && it !in attempted }
        return missing(pivot until size step block) ?: missing(0L until pivot step block)
    }
}

data class VideoPrecacheState(
    val bytes: Long = 0,
    val size: Long = 0,
    val cleared: Boolean = false,
    val error: AppError? = null,
) {
    // Integer floor: a partial tail/hole can never round up to 100%.
    val percent get() = if (size > 0) (bytes * 100 / size).coerceIn(0, 100).toInt() else 0
}

/** Viewing-session state survives configuration changes, but is discarded on a fresh open.
 * Attempted ranges bound work even when playback/LRU evicts previously prefetched data.
 */
class VideoPrecacheSession {
    internal val mutableState = MutableStateFlow(VideoPrecacheState())
    val state: StateFlow<VideoPrecacheState> = mutableState
    internal val runMutex = kotlinx.coroutines.sync.Mutex()
    internal var identity: String? = null
    internal var sawLowSpace = false
    internal var generation: Long? = null
    internal val attempted = mutableSetOf<Long>()
}
