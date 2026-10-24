package com.hermes.client.data.video

internal const val GIB = 1024L * 1024 * 1024

enum class VideoCacheCapacity(val gib: Int, val bytes: Long, val trimTo: Long) {
    SMALL(1, GIB, GIB * 4 / 5),
    DEFAULT(3, 3 * GIB, GIB * 5 / 2),
    LARGE(5, 5 * GIB, 4 * GIB);
    companion object { fun from(value: Int) = entries.firstOrNull { it.gib == value } ?: DEFAULT }
}

data class VideoCacheStats(
    val loaded: Boolean = false,
    val usedBytes: Long = 0,
    val maxBytes: Long = VideoCacheCapacity.DEFAULT.bytes,
    val lowSpace: Boolean = false,
    val clearPending: Boolean = false,
)
