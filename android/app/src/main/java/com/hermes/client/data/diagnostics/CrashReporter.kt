package com.hermes.client.data.diagnostics

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Captures otherwise-invisible crashes. On an uncaught exception the full stack trace (plus device
 * + OS info) is written to a file; on the next launch [MainActivity] reads it and shows it on a
 * screen the user can share. This turns a silent "app keeps crashing" into a copyable trace without
 * needing adb/logcat. The token is redacted so a shared trace can't leak credentials.
 *
 * When the user had diagnostic logging on, the captured [DebugLog] entries are attached too: the
 * stack trace says where the process died, the log says what led there.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    private const val MAX_BREADCRUMBS = 40
    private val breadcrumbLock = Any()
    private val breadcrumbs = ArrayDeque<String>(MAX_BREADCRUMBS)

    /** Always-on, redacted, content-free lifecycle trail included only if the process crashes. */
    fun breadcrumb(category: String, message: String) {
        val entry = "${Instant.now()} [$category] ${DebugLog.redact(message)}"
        synchronized(breadcrumbLock) {
            if (breadcrumbs.size >= MAX_BREADCRUMBS) breadcrumbs.removeFirst()
            breadcrumbs.addLast(entry)
        }
    }

    /** The trail as it stands, oldest first. */
    internal fun snapshotBreadcrumbs(): List<String> =
        synchronized(breadcrumbLock) { breadcrumbs.toList() }

    /** Drops the trail. Used between tests; the running app keeps one trail for its lifetime. */
    internal fun resetBreadcrumbs() {
        synchronized(breadcrumbLock) { breadcrumbs.clear() }
    }

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val trace = DebugLog.redact(sw.toString())
                val version = runCatching {
                    val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pi.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        pi.versionCode.toLong()
                    }
                    "${pi.versionName} ($code)"
                }.getOrDefault("?")
                val report = composeReport(
                    app = "${app.packageName} $version",
                    android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    thread = thread.name,
                    breadcrumbs = snapshotBreadcrumbs(),
                    diagnostics = runCatching { DebugLog.exportIfAny() }.getOrNull(),
                    trace = trace,
                )
                app.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(report.toByteArray()) }
            }
            // Let the platform still terminate the process (and run any prior handler).
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Assembles the shareable report. Split out from [install] so the layout — and the fact that
     * empty sections are omitted rather than printed as headers with nothing under them — is
     * testable without staging a real crash.
     */
    internal fun composeReport(
        app: String,
        android: String,
        device: String,
        thread: String,
        breadcrumbs: List<String>,
        diagnostics: String?,
        trace: String,
    ): String = buildString {
        appendLine("Hermes GO — crash report")
        appendLine("app: $app")
        appendLine("android: $android")
        appendLine("device: $device")
        appendLine("thread: $thread")
        appendLine()
        if (breadcrumbs.isNotEmpty()) {
            appendLine("breadcrumbs:")
            breadcrumbs.forEach { appendLine(it) }
            appendLine()
        }
        if (!diagnostics.isNullOrBlank()) {
            appendLine("diagnostic log:")
            appendLine(diagnostics.trimEnd())
            appendLine()
        }
        append(trace)
    }

    /** The saved crash report, or null if there is none. */
    fun read(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
    }
}
