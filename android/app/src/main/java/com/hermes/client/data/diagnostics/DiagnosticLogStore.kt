package com.hermes.client.data.diagnostics

import java.io.File
import java.io.FileOutputStream

/**
 * Rolling on-disk mirror of [DebugLog].
 *
 * The in-memory ring buffer dies with the process, which loses exactly the runs worth reading:
 * a crash, or a silent kill by the system after the screen goes off. Entries are therefore also
 * appended to a pair of fixed-size files that the next launch reads back.
 *
 * Two files rotate: entries go to `diagnostic-log.txt` until it would pass [maxFileBytes], at
 * which point it becomes `diagnostic-log.1.txt` and a fresh current file starts. The pair holds a
 * little over 2 × [maxFileBytes], and rotation drops the oldest half rather than the newest.
 *
 * Every call blocks on file I/O and must therefore run off the caller's thread — [DebugLog] owns
 * that executor. Each append opens and closes the file: writes are bursty rather than continuous,
 * and not holding a stream open keeps rotation and [clear] free of stream bookkeeping. Nothing is
 * fsync'd; the data reaches the page cache, which survives the process being killed (the case this
 * exists for) but not the device losing power.
 *
 * Writing happens only while the user has diagnostic logging on, and the caller redacts before
 * handing an entry over — see [DebugLog.redact].
 */
internal class DiagnosticLogStore(
    private val dir: File,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
) {
    private val current = File(dir, CURRENT)
    private val previous = File(dir, PREVIOUS)

    /** Appends one entry, rotating first when it would not fit. Failures are swallowed. */
    fun append(entry: DebugLog.LogEntry) {
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val bytes = encode(entry).toByteArray()
            if (current.length() + bytes.size > maxFileBytes) rotate()
            FileOutputStream(current, true).use { it.write(bytes) }
        }
    }

    /** Everything still on disk, oldest first. Unparsable lines are skipped. */
    fun readAll(): List<DebugLog.LogEntry> =
        (readLines(previous) + readLines(current)).mapNotNull { decode(it) }

    /** Up to [limit] entries from previous runs, oldest first. Unparsable lines are skipped. */
    fun readRecent(limit: Int): List<DebugLog.LogEntry> {
        if (limit <= 0) return emptyList()
        val entries = readAll()
        return if (entries.size <= limit) entries else entries.subList(entries.size - limit, entries.size)
    }

    fun clear() {
        runCatching { current.delete() }
        runCatching { previous.delete() }
    }

    /**
     * Drops both files once the newest is older than [retentionMillis]. Logging is opt-in and easy
     * to leave on, so captured entries must not sit on disk indefinitely.
     */
    fun pruneExpired(nowMillis: Long, retentionMillis: Long = RETENTION_MILLIS) {
        val newest = maxOf(lastModifiedOrZero(current), lastModifiedOrZero(previous))
        if (newest > 0L && nowMillis - newest > retentionMillis) clear()
    }

    private fun rotate() {
        runCatching { previous.delete() }
        // A failed rename would keep appending to an oversized current file; truncate instead so
        // the cap still holds. Losing the older half is the same trade rotation already makes.
        if (!runCatching { current.renameTo(previous) }.getOrDefault(false)) {
            runCatching { current.delete() }
        }
    }

    private fun lastModifiedOrZero(file: File): Long =
        runCatching { if (file.exists()) file.lastModified() else 0L }.getOrDefault(0L)

    private fun readLines(file: File): List<String> =
        runCatching { if (file.exists()) file.readLines() else emptyList() }.getOrDefault(emptyList())

    companion object {
        const val MAX_FILE_BYTES = 256L * 1024
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val CURRENT = "diagnostic-log.txt"
        private const val PREVIOUS = "diagnostic-log.1.txt"

        /** `<millis>\t<category>\t<message>`, one line per entry, separators escaped out of the fields. */
        internal fun encode(entry: DebugLog.LogEntry): String =
            "${entry.timeMillis}\t${escape(entry.category)}\t${escape(entry.message)}\n"

        internal fun decode(line: String): DebugLog.LogEntry? {
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) return null
            val millis = parts[0].toLongOrNull() ?: return null
            return DebugLog.LogEntry(
                timeMillis = millis,
                category = unescape(parts[1]),
                message = unescape(parts[2]),
                fromPreviousRun = true,
            )
        }

        private fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

        private fun unescape(value: String): String {
            if (!value.contains('\\')) return value
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c != '\\' || i == value.length - 1) {
                    out.append(c)
                    i++
                    continue
                }
                when (val next = value[i + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    '\\' -> out.append('\\')
                    else -> out.append(c).append(next)
                }
                i += 2
            }
            return out.toString()
        }
    }
}
