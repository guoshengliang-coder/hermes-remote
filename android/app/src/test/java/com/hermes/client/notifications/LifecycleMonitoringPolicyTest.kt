package com.hermes.client.notifications

import com.hermes.client.data.repository.NotificationMonitoringStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

class LifecycleMonitoringPolicyTest {
    /**
     * Regression for the "still computing, but the app says reconnecting" report. The notification
     * switch defaults to off, and it used to be evaluated before the active-run rule, so every
     * user who had not opted into notifications lost the socket 45s after switching apps — mid-run.
     */
    @Test fun notificationsOffStillFollowsARunThePhoneIsResponsibleFor() {
        assertEquals(
            LifecycleMonitoringMode.ACTIVE_BACKGROUND,
            lifecycleMonitoringMode(false, appInForeground = false, hasTrackedActiveRun = true),
        )
    }

    @Test fun notificationsOffWithNothingRunningStopsBackgroundWorkEntirely() {
        assertEquals(
            LifecycleMonitoringMode.DISABLED,
            lifecycleMonitoringMode(false, appInForeground = false, hasTrackedActiveRun = false),
        )
    }

    @Test fun foregroundUsesResponsiveInProcessPolling() {
        assertEquals(
            LifecycleMonitoringMode.FOREGROUND,
            lifecycleMonitoringMode(true, appInForeground = true, hasTrackedActiveRun = false),
        )
        assertEquals(
            LifecycleMonitoringMode.FOREGROUND,
            lifecycleMonitoringMode(false, appInForeground = true, hasTrackedActiveRun = false),
        )
    }

    @Test fun onlyTrackedActiveBackgroundRunUsesForegroundService() {
        assertEquals(
            LifecycleMonitoringMode.ACTIVE_BACKGROUND,
            lifecycleMonitoringMode(true, appInForeground = false, hasTrackedActiveRun = true),
        )
        assertEquals(
            LifecycleMonitoringMode.IDLE_BACKGROUND,
            lifecycleMonitoringMode(true, appInForeground = false, hasTrackedActiveRun = false),
        )
    }

    @Test fun explicitRealtimeAndPowerSavingOverrideAdaptiveBackgroundChoice() {
        assertEquals(
            LifecycleMonitoringMode.ACTIVE_BACKGROUND,
            lifecycleMonitoringMode(true, false, false, NotificationMonitoringStrategy.REALTIME),
        )
        assertEquals(
            LifecycleMonitoringMode.IDLE_BACKGROUND,
            lifecycleMonitoringMode(true, false, true, NotificationMonitoringStrategy.POWER_SAVING),
        )
    }

    /** Power saving is an explicit instruction to stop background work, so it outranks a run. */
    @Test fun powerSavingOutranksAnActiveRunEvenWithNotificationsOff() {
        assertEquals(
            LifecycleMonitoringMode.DISABLED,
            lifecycleMonitoringMode(false, false, true, NotificationMonitoringStrategy.POWER_SAVING),
        )
    }

    /** Real-time monitoring exists to serve notifications, so the switch still gates it. */
    @Test fun realtimeStillYieldsToTheNotificationSwitchWhenNothingIsRunning() {
        assertEquals(
            LifecycleMonitoringMode.DISABLED,
            lifecycleMonitoringMode(false, false, false, NotificationMonitoringStrategy.REALTIME),
        )
    }
}
