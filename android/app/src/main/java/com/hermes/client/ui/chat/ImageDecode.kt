package com.hermes.client.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Where a chat image's bytes come from. Pending attachments hold their bytes in memory; sent and
 * received images hold a path into the chat-image cache. Both feed the same thumbnails and the same
 * viewer, so the decoder takes this instead of one or the other.
 *
 * Staged bytes deliberately do **not** get written into the cache to unify the two. That directory
 * is an LRU trimmed on every write, so a staged-but-unsent attachment parked there could be evicted
 * while the user is still composing — losing an image they were about to send. The upload path needs
 * the byte array anyway, so the file would buy no memory back.
 */
sealed interface ImageSource {
    /**
     * [key] is the identity, not [bytes]. These values are used as `produceState` keys, and a
     * generated `equals` over a ByteArray either compares by reference (right by accident) or, once
     * someone "fixes" it to `contentEquals`, runs a multi-megabyte memcmp on every recomposition.
     */
    class Bytes(val key: String, val bytes: ByteArray) : ImageSource {
        override fun equals(other: Any?): Boolean = other is Bytes && other.key == key
        override fun hashCode(): Int = key.hashCode()
    }

    data class Path(val path: String) : ImageSource
}

/**
 * Decode [source] downsampled so its longest side is roughly [reqPx].
 *
 * The sample loop compares against [reqPx] itself. It used to compare against `reqPx * 2`, which let
 * every caller through at twice the size it asked for — the fullscreen path requested 4096 and so
 * accepted 8192px, which is to say it never downsampled at all.
 *
 * Returns null on any decode failure, including [OutOfMemoryError]: an image too big to hold is a
 * broken-image icon, not a crash. Callers already render that state.
 */
internal fun decodeSampled(source: ImageSource, reqPx: Int): ImageBitmap? = runCatching {
    val bounds = decodeBounds(source) ?: return@runCatching null
    var sample = 1
    val maxDim = maxOf(bounds.width, bounds.height)
    while (maxDim / sample > reqPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    when (source) {
        is ImageSource.Path -> BitmapFactory.decodeFile(source.path, opts)
        is ImageSource.Bytes -> BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size, opts)
    }?.asImageBitmap()
}.getOrNull()

/** Intrinsic pixel size without allocating the pixels, or null when [source] is not a decodable image. */
internal fun decodeBounds(source: ImageSource): ImageSize? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    when (source) {
        is ImageSource.Path -> BitmapFactory.decodeFile(source.path, bounds)
        is ImageSource.Bytes -> BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
    else ImageSize(bounds.outWidth, bounds.outHeight)
}.getOrNull()

/** Intrinsic image dimensions in pixels. */
data class ImageSize(val width: Int, val height: Int) {
    /** Width over height. Never zero — [decodeBounds] rejects non-positive dimensions. */
    val aspect: Float get() = width.toFloat() / height.toFloat()

    fun toSize(): Size = Size(width.toFloat(), height.toFloat())
}
