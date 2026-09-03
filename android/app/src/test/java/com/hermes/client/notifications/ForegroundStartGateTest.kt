package com.hermes.client.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the ForegroundServiceDidNotStartInTimeException crash: a stop that
 * reaches the system between startForegroundService() and startForeground() kills the process.
 * The gate must turn such a stop into a self-stop after the service enters the foreground.
 */
class ForegroundStartGateTest {
    @Test fun stop_while_start_pending_is_deferred_then_self_stops() {
        val gate = ForegroundStartGate()
        gate.onStartRequested()
        // Old behaviour: stopService() here → crash. New behaviour: defer.
        assertFalse(gate.onStopRequested())
        assertTrue(gate.isStartPending)
        // Service reaches startForeground(): the promise is kept, now honour the stop.
        assertTrue(gate.onForegroundEntered())
        assertFalse(gate.isInForeground)
        gate.onDestroyed()
        // Nothing pending afterwards: a plain stop goes straight through.
        assertTrue(gate.onStopRequested())
    }

    @Test fun stop_after_foreground_entered_goes_through_immediately() {
        val gate = ForegroundStartGate()
        gate.onStartRequested()
        assertFalse(gate.onForegroundEntered())
        assertTrue(gate.isInForeground)
        assertTrue(gate.onStopRequested())
    }

    @Test fun start_after_deferred_stop_cancels_the_stop() {
        val gate = ForegroundStartGate()
        gate.onStartRequested()
        assertFalse(gate.onStopRequested())
        gate.onStartRequested()
        // Latest intent wins: the service should keep running.
        assertFalse(gate.onForegroundEntered())
        assertTrue(gate.isInForeground)
    }

    @Test fun restart_while_running_does_not_mark_start_pending() {
        val gate = ForegroundStartGate()
        gate.onStartRequested()
        gate.onForegroundEntered()
        // Android clears the foreground requirement for an already-foreground service, so a
        // stop right after this start is safe and must not be deferred.
        gate.onStartRequested()
        assertFalse(gate.isStartPending)
        assertTrue(gate.onStopRequested())
    }

    @Test fun stop_then_start_before_destroy_treats_new_start_as_pending() {
        val gate = ForegroundStartGate()
        gate.onStartRequested()
        gate.onForegroundEntered()
        assertTrue(gate.onStopRequested()) // stopService() issued; onDestroy not yet delivered
        gate.onStartRequested() // system will recreate the service with a fresh promise
        assertTrue(gate.isStartPending)
        assertFalse(gate.onStopRequested()) // must defer again
        gate.onDestroyed() // old instance
        assertTrue(gate.onForegroundEntered()) // new instance self-stops
    }

    @Test fun failed_start_leaves_no_pending_promise() {
        val gate = ForegroundStartGate()
        gate.onStartRequested()
        gate.onStartFailed()
        assertFalse(gate.isStartPending)
        assertTrue(gate.onStopRequested())
    }

    @Test fun stop_without_any_start_goes_through() {
        assertTrue(ForegroundStartGate().onStopRequested())
    }
}
