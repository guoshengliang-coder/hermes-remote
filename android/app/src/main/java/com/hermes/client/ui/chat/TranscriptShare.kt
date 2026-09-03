package com.hermes.client.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File-based transcript sharing. Plain text goes straight into EXTRA_TEXT elsewhere; these two
 * write a real file into the cache and hand out a FileProvider URI, so the receiving app gets a
 * document (Markdown) or a picture instead of a wall of pasted characters.
 *
 * The cache dir is already declared to the provider (`file_paths.xml` grants all of cache-path),
 * so no manifest change is needed. Files are disposable: the OS reclaims the cache, and each
 * export overwrites its own timestamped name.
 */
internal object TranscriptShare {
    private fun exportDir(context: Context): File =
        File(context.cacheDir, "transcripts").apply { mkdirs() }

    /** Writes [markdown] as `<baseName>.md` and opens the system share sheet. Returns false on failure. */
    suspend fun shareMarkdown(
        context: Context,
        baseName: String,
        markdown: String,
        chooserTitle: String,
        subject: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(exportDir(context), "$baseName.md")
            file.writeText(markdown)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                // text/markdown is the registered type; editors and cloud drives accept it, and
                // chat apps fall back to "a file" rather than inlining a truncated body.
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                clipData = android.content.ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, chooserTitle))
            }
        }.isSuccess
    }

    /** Writes [bitmap] as `<baseName>.png` and opens the system share sheet. Returns false on failure. */
    suspend fun shareImage(
        context: Context,
        baseName: String,
        bitmap: Bitmap,
        chooserTitle: String,
        subject: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(exportDir(context), "$baseName.png")
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                clipData = android.content.ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, chooserTitle))
            }
        }.isSuccess
    }
}
