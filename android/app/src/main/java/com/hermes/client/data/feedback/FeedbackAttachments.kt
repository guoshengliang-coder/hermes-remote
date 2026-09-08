package com.hermes.client.data.feedback

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Snapshots handed to MissionGo as file attachments.
 *
 * Three constraints shape this, all from the SDK's own contract:
 *
 * 1. **The file must still exist when the report is submitted.** The queue path can run long after
 *    the call — a crash reported with no network is delivered whenever one appears — so the cache
 *    directory the share sheet uses is the wrong home: the system may evict it first. These live in
 *    `filesDir`, which only this app deletes.
 * 2. **The live rolling log cannot be attached directly.** It rotates: the file handed over may be
 *    renamed or replaced between the call and the upload. A snapshot is immune, and it is also the
 *    merge of the rolling pair that a reader wants anyway.
 * 3. **`.log` is the extension that files as diagnostics.** MissionGo sorts `.txt` under documents,
 *    where a log is buried among notes.
 *
 * Nothing here deletes on upload, because the SDK uploads asynchronously and does not say when it
 * finished. [pruneStale] handles it instead, on the same 24-hour horizon the SDK's own queue
 * expires on: anything older than that will never be uploaded by anyone.
 */
object FeedbackAttachments {

    const val DIRECTORY = "feedback-attachments"
    const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

    /**
     * Writes [text] as an attachable `.log` file, or returns null when there is nothing to send or
     * the write fails. A failed snapshot must never block the report: the inline log entries and
     * the description still carry a usable account of the problem.
     */
    fun snapshot(dir: File, prefix: String, text: String, nowMillis: Long = System.currentTimeMillis()): File? {
        if (text.isBlank()) return null
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val file = File(dir, "$prefix-${stamp(nowMillis)}.log")
            file.writeText(text)
            file.takeIf { it.length() > 0 }
        }.getOrNull()
    }

    /** Drops snapshots past the queue's own expiry, so a failed upload cannot leak storage forever. */
    fun pruneStale(dir: File, nowMillis: Long = System.currentTimeMillis(), maxAgeMillis: Long = MAX_AGE_MILLIS) {
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (nowMillis - file.lastModified() > maxAgeMillis) runCatching { file.delete() }
            }
        }
    }

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(millis))
}
