package com.hermes.client.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Pure geometry for the avatar pipeline — unit-tested without Bitmaps. */
object AvatarPhotoGeometry {
    /** Stored edge length: enough for the 96dp preview at 4x density, small on disk. */
    const val TARGET_EDGE = 512

    /** Largest power-of-two subsampling that keeps the SHORT edge at or above [target]. */
    fun sampleSize(width: Int, height: Int, target: Int = TARGET_EDGE): Int {
        val shortEdge = minOf(width, height)
        if (shortEdge <= 0) return 1
        var sample = 1
        while (shortEdge / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Centred square crop: `[left, top, side]` for a `width`×`height` image. */
    fun squareCrop(width: Int, height: Int): IntArray {
        val side = minOf(width, height)
        return intArrayOf((width - side) / 2, (height - side) / 2, side)
    }
}

/**
 * Turns a picked photo into the avatar file the store names: decode with subsampling, centre
 * crop to a square, scale to [AvatarPhotoGeometry.TARGET_EDGE], encode WebP into [dir]. Runs
 * on [io]; the result is the file's basename. Every failure comes back as a [Result] failure
 * for the caller to map onto `HR-MEDIA-002`.
 */
open class AvatarPhotoImporter(
    private val context: Context,
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    open suspend fun import(uri: Uri, profile: String): Result<String> = withContext(io) {
        runCatching {
            val decoded = decode(uri)
            try {
                val square = squareOf(decoded)
                try {
                    dir.mkdirs()
                    val name = "avatar-${Integer.toHexString(profile.hashCode())}-${System.currentTimeMillis()}.webp"
                    val file = File(dir, name)
                    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                    }
                    file.outputStream().use { out ->
                        if (!square.compress(format, 85, out)) throw IOException("webp encode failed")
                    }
                    name
                } finally {
                    if (square !== decoded) square.recycle()
                }
            } finally {
                decoded.recycle()
            }
        }
    }

    private fun decode(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF orientation itself; BitmapFactory does not.
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setTargetSampleSize(AvatarPhotoGeometry.sampleSize(info.size.width, info.size.height))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("cannot open $uri")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")
        val opts = BitmapFactory.Options().apply {
            inSampleSize = AvatarPhotoGeometry.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("decode failed")
    }

    private fun squareOf(src: Bitmap): Bitmap {
        val crop = AvatarPhotoGeometry.squareCrop(src.width, src.height)
        val left = crop[0]; val top = crop[1]; val side = crop[2]
        if (side <= 0) throw IOException("empty image")
        val cropped = if (left == 0 && top == 0 && side == src.width && side == src.height) src
        else Bitmap.createBitmap(src, left, top, side, side)
        if (cropped.width == AvatarPhotoGeometry.TARGET_EDGE) return cropped
        val scaled = Bitmap.createScaledBitmap(cropped, AvatarPhotoGeometry.TARGET_EDGE, AvatarPhotoGeometry.TARGET_EDGE, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        return scaled
    }
}
