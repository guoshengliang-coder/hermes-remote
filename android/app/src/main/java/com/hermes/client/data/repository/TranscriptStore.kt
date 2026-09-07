package com.hermes.client.data.repository

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * On-disk copy of the transcripts this device has already downloaded, so opening a session paints
 * from local bytes instead of holding the skeleton for a network round trip (docs/DESIGN.md §5.4
 * rule 4).
 *
 * Why this exists at all: [SessionRepository]'s in-memory history cache holds ten entries and dies
 * with the process. With ~200 sessions, and after every app update or background eviction, almost
 * every open was a cold one.
 *
 * **What is stored is the raw REST body, not mapped [com.hermes.client.domain.ChatMessage]s.** The
 * mapping (tool-result joining, `h-<i>-` id assignment, renderability filtering) changes between
 * app versions — it changed twice in the week this was written — so storing its output would pin
 * old releases' rendering into new builds and would need a migration for every domain-model edit.
 * Re-parsing the payload costs a few milliseconds against a round trip's hundreds, and keeps
 * exactly one mapping code path.
 *
 * **`filesDir`, deliberately not `cacheDir`.** `file_paths.xml` hands the whole cache directory to
 * the FileProvider (`<cache-path name="captures" path="."/>`), and a transcript is the user's
 * entire conversation. `cacheDir/transcripts` is also already the Markdown export directory.
 * Backups are not a concern here: the manifest sets `allowBackup="false"` and both extraction
 * rules exclude every domain.
 *
 * Every operation degrades to "no cache" on any failure. A cache that cannot be read or written is
 * never an error the user should see — the network path behind it is still authoritative — so
 * nothing here throws and nothing here carries an `HR-` code.
 */
class TranscriptStore(
    private val directory: File,
    /**
     * Injectable so a test can observe a write that the repository deliberately fires and forgets;
     * with a hard-coded `Dispatchers.IO` that work escapes the test scheduler entirely.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Production entry point; the [File] constructor is what makes this testable off-device. */
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY_NAME))

    /** The stored payload for [key], or null when absent, unreadable, or corrupt. */
    suspend fun read(key: String): String? = withContext(io) {
        runCatching {
            val file = fileFor(key)
            if (!file.isFile) return@runCatching null
            val text = GZIPInputStream(file.inputStream().buffered()).use { it.readBytes() }
                .toString(Charsets.UTF_8)
            // Touch on read so pruning is least-recently-USED, not least-recently-written: the
            // sessions a person keeps reopening are exactly the ones worth keeping.
            file.setLastModified(System.currentTimeMillis())
            text.ifBlank { null }
        }.getOrNull()
    }

    /**
     * Stores [payload] for [key]. Oversized transcripts are skipped rather than allowed to evict
     * everything else; a partial write is removed instead of being left to fail the next read.
     */
    suspend fun write(key: String, payload: String) {
        if (payload.length > MAX_ENTRY_RAW_CHARS) return
        withContext(io) {
            runCatching {
                if (!directory.isDirectory && !directory.mkdirs()) return@runCatching
                val file = fileFor(key)
                val temporary = File(directory, "${file.name}.tmp")
                try {
                    GZIPOutputStream(temporary.outputStream().buffered()).use {
                        it.write(payload.toByteArray(Charsets.UTF_8))
                    }
                    if (!temporary.renameTo(file)) temporary.delete()
                } catch (error: Throwable) {
                    temporary.delete()
                    throw error
                }
                prune()
            }
        }
    }

    /** Drops everything. Used when credentials change, so one account never shows another's history. */
    suspend fun clear() = withContext(io) {
        runCatching { directory.listFiles()?.forEach { it.delete() } }
        Unit
    }

    /**
     * Keeps the newest entries within both budgets. Sorted by last modification, which [read]
     * refreshes, so this evicts the least recently used rather than the oldest downloaded.
     */
    private fun prune() {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = 0L
        files.sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
            total += file.length()
            if (index >= MAX_CACHE_FILES || total > MAX_CACHE_BYTES) file.delete()
        }
    }

    /**
     * Session ids and profile names both reach this from the server, so they are hashed rather
     * than pasted into a path: a name carrying `/` or `..` must not decide where we write.
     */
    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }.take(32)
        return File(directory, "$name.json.gz")
    }

    companion object {
        const val DIRECTORY_NAME = "transcript-history"

        /**
         * A transcript this big is a pathological session, not a conversation someone reopens; it
         * would evict a large part of the cache to serve one screen. 8 MB of JSON is roughly an
         * order of magnitude past the largest transcript observed in production (1.5 MB).
         */
        const val MAX_ENTRY_RAW_CHARS = 8 * 1024 * 1024

        /**
         * ~200 sessions averaging 325 KB of JSON compress to roughly 18 MB, so 32 MB holds a full
         * working set with room to grow, and the file count stops a pathological directory listing.
         */
        const val MAX_CACHE_FILES = 300
        const val MAX_CACHE_BYTES = 32L * 1024L * 1024L
    }
}
