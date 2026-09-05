package com.hermes.client.data.diagnostics

import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide diagnostic log, toggled by the user in Settings → Diagnostics.
 *
 * It is a plain object (not DI) so it can be called from non-injected code such as the
 * WebSocket listener. When [enabled] is false, [log] is a cheap no-op and nothing is
 * retained, so there is zero overhead in normal use. When enabled, entries are kept in a
 * bounded in-memory ring buffer, exposed as [entries] for the live in-app view and mirrored
 * to logcat under [TAG].
 *
 * The buffer alone would die with the process, losing the runs most worth reading — a crash, or
 * a silent kill by the system after the screen goes off. So once [init] has been called, entries
 * are also appended to a rolling file and the next launch reads them back (flagged
 * [LogEntry.fromPreviousRun]). File I/O runs on a dedicated executor, never on the caller.
 *
 * A registered session token is masked in every message so the log is safe to share.
 */
object DebugLog {
    const val MAX_ENTRIES = 500
    private const val TAG = "HermesDebug"

    data class LogEntry(
        val timeMillis: Long,
        val category: String,
        val message: String,
        /** True for entries restored from an earlier process, not captured in this one. */
        val fromPreviousRun: Boolean = false,
    )

    @Volatile private var enabled = false
    @Volatile private var tokenToRedact: String? = null
    @Volatile private var store: DiagnosticLogStore? = null
    @Volatile private var io: Executor? = null

    // Created on first use: a launch that never turns logging on never starts the thread.
    private val defaultIo: Executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hermes-diagnostic-log").apply { isDaemon = true }
        }
    }
    private val ioExecutor: Executor get() = io ?: defaultIo

    // Thread-safe (unlike SimpleDateFormat); the log is written from background threads.
    private val exportFmt =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /**
     * Attaches the rolling file in [dir] and restores what earlier runs left there. Called once
     * from the Application; both the prune and the restore happen on the I/O executor, so this
     * returns immediately and touches no file on the caller's thread. Pass [io] to run the file
     * work somewhere else (tests run it inline).
     */
    fun init(dir: File, io: Executor? = null) {
        val attached = DiagnosticLogStore(dir)
        this.io = io
        store = attached
        ioExecutor.execute {
            runCatching { attached.pruneExpired(System.currentTimeMillis()) }
            val restored = runCatching { attached.readRecent(MAX_ENTRIES) }.getOrDefault(emptyList())
            if (restored.isEmpty()) return@execute
            synchronized(lock) {
                // Entries logged while the restore was in flight are newer, so restored ones go in
                // front of them and the cap trims from the oldest end as usual.
                val live = buffer.toList()
                buffer.clear()
                restored.forEach { buffer.addLast(it) }
                live.forEach { buffer.addLast(it) }
                while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
                _entries.value = buffer.toList()
            }
        }
    }

    /** Register the session token so it is never written to the log in plain text. */
    fun setTokenToRedact(token: String?) {
        tokenToRedact = token?.takeIf { it.isNotBlank() }
    }

    /**
     * Lazy form for anything on a hot path: the message is neither built nor allocated unless
     * logging is on. Use it for per-event or per-update lines; the eager overload is fine for
     * lines that fire a few times per run.
     */
    inline fun log(category: String, message: () -> String) {
        if (!isEnabled()) return
        log(category, message())
    }

    fun log(category: String, message: String) {
        if (!enabled) return
        val safe = redact(message)
        val entry = LogEntry(System.currentTimeMillis(), category, safe)
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
        store?.let { target -> ioExecutor.execute { target.append(entry) } }
        Log.d(TAG, "[$category] $safe")
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
        store?.let { target -> ioExecutor.execute { target.clear() } }
    }

    /** Plain-text dump for the Share sheet, oldest first. */
    fun export(): String {
        val snapshot = synchronized(lock) { buffer.toList() }
        if (snapshot.isEmpty()) return "(no diagnostic entries)"
        return buildString {
            append("Hermes diagnostic log — ${snapshot.size} entries\n")
            snapshot.forEach { e ->
                val origin = if (e.fromPreviousRun) " (previous run)" else ""
                append("${exportFmt.format(Instant.ofEpochMilli(e.timeMillis))} [${e.category}]$origin ${e.message}\n")
            }
        }
    }

    /** [export] when anything was captured, else null — a crash report omits an empty section. */
    fun exportIfAny(): String? = if (_entries.value.isEmpty()) null else export()

    fun redact(message: String): String {
        val token = tokenToRedact ?: return message
        return message.replace(token, "***")
    }

    /** Test seam: drops the on-disk mirror so a test can exercise the in-memory path alone. */
    internal fun detachStore() {
        store = null
    }
}
