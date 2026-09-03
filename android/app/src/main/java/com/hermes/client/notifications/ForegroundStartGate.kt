package com.hermes.client.notifications

/**
 * Process-local mirror of the system's pending-foreground promise for one foreground service.
 *
 * `Context.startForegroundService()` makes Android expect a matching `Service.startForeground()`.
 * Stopping the service before that call lands (for example ON_STOP → ON_START within a warm
 * start, while the main thread is still busy and the service's `onCreate` has not run) makes
 * ActivityManager crash the whole process with `ForegroundServiceDidNotStartInTimeException`.
 *
 * This gate turns every stop that would land inside that window into a deferred stop: the
 * service enters the foreground first, then stops itself. All calls happen on the main thread in
 * practice, but the state is guarded anyway because the callers are not required to be.
 */
class ForegroundStartGate {
    private val lock = Any()
    private var startPending = false
    private var stopDeferred = false
    private var inForeground = false

    /** Record a `startForegroundService()` request. A later start cancels an earlier deferred stop. */
    fun onStartRequested() = synchronized(lock) {
        stopDeferred = false
        if (!inForeground) startPending = true
    }

    /** The start request threw before reaching the system, so no foreground promise exists. */
    fun onStartFailed() = synchronized(lock) {
        startPending = false
        stopDeferred = false
    }

    /**
     * Record a stop request. Returns true when the caller may call `stopService()` right now;
     * false when the stop is deferred until the service has entered the foreground.
     */
    fun onStopRequested(): Boolean = synchronized(lock) {
        inForeground = false
        if (startPending) {
            stopDeferred = true
            false
        } else true
    }

    /**
     * The service has called `startForeground()`. Returns true when a stop arrived while the start
     * was pending, in which case the service must now stop itself.
     */
    fun onForegroundEntered(): Boolean = synchronized(lock) {
        startPending = false
        inForeground = true
        val stopNow = stopDeferred
        stopDeferred = false
        if (stopNow) inForeground = false
        stopNow
    }

    fun onDestroyed() = synchronized(lock) {
        inForeground = false
    }

    /** Visible for tests and diagnostics. */
    val isStartPending: Boolean get() = synchronized(lock) { startPending }
    val isInForeground: Boolean get() = synchronized(lock) { inForeground }
}
