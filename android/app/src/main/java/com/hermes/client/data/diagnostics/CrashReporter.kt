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
                val report = buildString {
                    appendLine("Hermes Beta — crash report")
                    appendLine("app: ${app.packageName} $version")
                    appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("thread: ${thread.name}")
                    appendLine()
                    val trail = synchronized(breadcrumbLock) { breadcrumbs.toList() }
                    if (trail.isNotEmpty()) {
                        appendLine("breadcrumbs:")
                        trail.forEach { appendLine(it) }
                        appendLine()
                    }
                    append(trace)
                }
                app.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(report.toByteArray()) }
            }
            // Let the platform still terminate the process (and run any prior handler).
            previous?.uncaughtException(thread, error)
        }
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
