package com.hermes.client.notifications

import com.hermes.client.data.repository.NotificationMonitoringStrategy

enum class LifecycleMonitoringMode {
    DISABLED,
    FOREGROUND,
    ACTIVE_BACKGROUND,
    IDLE_BACKGROUND,
}

/** Pure policy kept separate from Android services so battery behavior has regression coverage. */
fun lifecycleMonitoringMode(
    notificationsEnabled: Boolean,
    appInForeground: Boolean,
    hasLocallyStartedRun: Boolean,
    strategy: NotificationMonitoringStrategy = NotificationMonitoringStrategy.ADAPTIVE,
): LifecycleMonitoringMode = when {
    appInForeground -> LifecycleMonitoringMode.FOREGROUND
    !notificationsEnabled -> LifecycleMonitoringMode.DISABLED
    strategy == NotificationMonitoringStrategy.REALTIME -> LifecycleMonitoringMode.ACTIVE_BACKGROUND
    strategy == NotificationMonitoringStrategy.POWER_SAVING -> LifecycleMonitoringMode.IDLE_BACKGROUND
    hasLocallyStartedRun -> LifecycleMonitoringMode.ACTIVE_BACKGROUND
    else -> LifecycleMonitoringMode.IDLE_BACKGROUND
}
