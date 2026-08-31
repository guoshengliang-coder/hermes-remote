package com.hermes.client.notifications

import com.hermes.client.data.repository.NotificationMonitoringStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

class LifecycleMonitoringPolicyTest {
    @Test fun disabledNeverKeepsBackgroundMonitoringAlive() {
        assertEquals(
            LifecycleMonitoringMode.DISABLED,
            lifecycleMonitoringMode(false, appInForeground = false, hasLocallyStartedRun = true),
        )
    }

    @Test fun foregroundUsesResponsiveInProcessPolling() {
        assertEquals(
            LifecycleMonitoringMode.FOREGROUND,
            lifecycleMonitoringMode(true, appInForeground = true, hasLocallyStartedRun = false),
        )
        assertEquals(
            LifecycleMonitoringMode.FOREGROUND,
            lifecycleMonitoringMode(false, appInForeground = true, hasLocallyStartedRun = false),
        )
    }

    @Test fun onlyLocallyActiveBackgroundRunUsesForegroundService() {
        assertEquals(
            LifecycleMonitoringMode.ACTIVE_BACKGROUND,
            lifecycleMonitoringMode(true, appInForeground = false, hasLocallyStartedRun = true),
        )
        assertEquals(
            LifecycleMonitoringMode.IDLE_BACKGROUND,
            lifecycleMonitoringMode(true, appInForeground = false, hasLocallyStartedRun = false),
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
}
