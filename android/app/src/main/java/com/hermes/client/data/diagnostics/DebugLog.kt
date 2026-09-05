package com.hermes.client.data.diagnostics

import android.os.Build
import android.util.Log
import com.hermes.client.BuildConfig
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    @Volatile private var runMarked = false
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
        val wasEnabled = enabled
        enabled = value
        if (!value) {
            // A later re-enable is a new capture session and deserves its own header.
            runMarked = false
        } else if (!wasEnabled) {
            markRun()
        }
    }

    /**
     * One header per capture session. Two jobs: it states the build and device that produced
     * everything below it — the first thing anyone reading a shared log needs and the thing a
     * screenshot never carries — and it marks where a process restart cut the history, which the
     * per-entry "previous run" flag can no longer express now that [exportFull] reads the file
     * back as one stream.
     */
    private fun markRun() {
        if (runMarked) return
        runMarked = true
        log(
            "session",
            "diagnostic logging on · v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                "pid=${android.os.Process.myPid()} · Android ${Build.VERSION.SDK_INT} · " +
                "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    /**
     * User-placed marker ("it just happened"). Reading a shared log otherwise starts with guessing
     * which minute the reporter meant, and the answer is rarely in the last few lines — they are
     * usually the navigation to Settings that came after.
     */
    fun mark(note: String) {
        if (!enabled) return
        log("mark", "──────── $note ────────")
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

    /**
     * Full history for the Share sheet, oldest first: the rolling file rather than the in-memory
     * ring. The ring holds [MAX_ENTRIES] entries, which during an active session is a few minutes
     * — routinely less than the time between hitting a bug and getting to Settings → Diagnostics —
     * while the file holds days. Blocks on file I/O, so call it off the main thread.
     *
     * [export] stays in-memory and is what the crash reporter uses: that path runs on a dying
     * process where waiting on the I/O executor could hang.
     */
    fun exportFull(): String {
        awaitPendingWrites()
        val fromDisk = store
            ?.let { runCatching { it.readAll() }.getOrDefault(emptyList()) }
            .orEmpty()
        val snapshot = fromDisk.ifEmpty { synchronized(lock) { buffer.toList() } }
        if (snapshot.isEmpty()) return "(no diagnostic entries)"
        return buildString {
            append("Hermes diagnostic log — ${snapshot.size} entries\n")
            snapshot.forEach { e ->
                append("${exportFmt.format(Instant.ofEpochMilli(e.timeMillis))} [${e.category}] ${e.message}\n")
            }
        }
    }

    /**
     * Blocks until appends submitted before this call have reached the file. The executor is
     * single-threaded and ordered, so an empty task behind them is enough of a barrier. Bounded:
     * a stuck writer must not freeze the share action.
     */
    private fun awaitPendingWrites(timeoutMs: Long = 2_000L) {
        if (store == null) return
        val done = CountDownLatch(1)
        val submitted = runCatching { ioExecutor.execute { done.countDown() } }.isSuccess
        if (!submitted) return
        runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }
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
