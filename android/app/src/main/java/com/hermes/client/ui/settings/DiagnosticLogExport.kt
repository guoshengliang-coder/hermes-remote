package com.hermes.client.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.hermes.client.data.diagnostics.DebugLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shares the diagnostic log as a file rather than as `EXTRA_TEXT`.
 *
 * The full history runs to a few hundred kilobytes. An Intent extra that large risks
 * `TransactionTooLargeException` on the ~1MB Binder limit, and share targets that survive it
 * frequently truncate long text — which would silently drop exactly the older half that made
 * reading the file worthwhile. A content URI moves the same bytes with neither problem, and lands
 * as an attachment in a tracker rather than as an unreadable wall of pasted text.
 */
object DiagnosticLogExport {

    suspend fun share(context: Context, chooserTitle: String, subject: String) {
        val uri = withContext(Dispatchers.IO) {
            val text = DebugLog.exportFull()
            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            // Only the newest export is ever useful, and these are large; keep exactly one.
            runCatching { dir.listFiles()?.forEach { it.delete() } }
            val file = File(dir, "hermes-diagnostic-${stamp()}.txt")
            runCatching {
                file.writeText(text)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
        } ?: return

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            runCatching { context.startActivity(Intent.createChooser(intent, chooserTitle)) }
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private const val EXPORT_DIR = "diagnostic-exports"
}
