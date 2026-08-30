package com.hermes.client.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

data class PreparedAttachment(
    val bytes: ByteArray,
    val mimeType: String,
    val name: String,
)

private const val MAX_IMAGE_SOURCE_BYTES = 48 * 1024 * 1024
private const val MAX_IMAGE_EDGE = 2560

/** Reads a picker URI with hard bounds and normalizes oversized still images for safe RPC upload. */
fun prepareAttachment(context: Context, uri: Uri, fallbackName: String = "attachment"): PreparedAttachment {
    val resolver = context.contentResolver
    val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            }
        }
    val originalName = metadata?.first?.takeIf { it.isNotBlank() } ?: fallbackName
    val extension = originalName.substringAfterLast('.', "").lowercase()
    require(extension !in setOf("apk", "exe", "msi", "dmg", "pkg", "app", "dex", "so", "dylib")) {
        "Executable files are not supported"
    }
    val mime = resolver.getType(uri)?.ifBlank { null } ?: mimeFromName(originalName)
    val isImage = mime.startsWith("image/", ignoreCase = true)
    val announcedSize = metadata?.second
    val readLimit = if (isImage) MAX_IMAGE_SOURCE_BYTES else MAX_DIRECT_ATTACHMENT_BYTES
    if (announcedSize != null && announcedSize > readLimit) {
        error(if (isImage) "Image exceeds 48 MB" else "File exceeds 6 MB")
    }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > readLimit) error(if (isImage) "Image exceeds 48 MB" else "File exceeds 6 MB")
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: error("Unable to read the selected file")
    if (bytes.isEmpty()) error("The selected file is empty")
    if (bytes.size <= MAX_DIRECT_ATTACHMENT_BYTES) {
        return PreparedAttachment(bytes, mime, sanitizeAttachmentName(originalName))
    }
    if (!isImage || mime.equals("image/gif", ignoreCase = true)) {
        error("This attachment exceeds the 6 MB direct-upload limit")
    }
    return compressStillImage(bytes, originalName)
}

private fun compressStillImage(bytes: ByteArray, originalName: String): PreparedAttachment {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Unsupported image")
    var sample = 1
    while (bounds.outWidth / sample > MAX_IMAGE_EDGE || bounds.outHeight / sample > MAX_IMAGE_EDGE) {
        sample *= 2
    }
    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: error("Unable to decode image")
    val scale = minOf(1f, MAX_IMAGE_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
    val normalized = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== decoded) decoded.recycle() }
    } else decoded
    try {
        for (quality in listOf(88, 80, 72, 64)) {
            val output = ByteArrayOutputStream()
            check(normalized.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Unable to compress image" }
            val result = output.toByteArray()
            if (result.size <= MAX_DIRECT_ATTACHMENT_BYTES) {
                val stem = sanitizeAttachmentName(originalName).substringBeforeLast('.', "image")
                return PreparedAttachment(result, "image/jpeg", "$stem.jpg")
            }
        }
        error("Image remains larger than 6 MB after compression")
    } finally {
        normalized.recycle()
    }
}

private fun sanitizeAttachmentName(value: String): String = value
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .replace(Regex("[\\u0000-\\u001f\\u007f]"), "_")
    .take(160)
    .ifBlank { "attachment" }

private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "txt", "log" -> "text/plain"
    "md" -> "text/markdown"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/wav"
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}
