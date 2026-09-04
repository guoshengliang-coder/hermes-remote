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
    hasTrackedActiveRun: Boolean,
    strategy: NotificationMonitoringStrategy = NotificationMonitoringStrategy.ADAPTIVE,
): LifecycleMonitoringMode = when {
    appInForeground -> LifecycleMonitoringMode.FOREGROUND
    // A run the phone is responsible for outranks the notification switch. Those two settings
    // answer different questions — "may I interrupt you?" versus "should I keep following this
    // run?" — and binding them together used to drop the socket mid-run for every user who had
    // notifications off (which is the default). Power-saving stays above this: it is an explicit
    // instruction to stop background work, not an incidental preference.
    hasTrackedActiveRun && strategy != NotificationMonitoringStrategy.POWER_SAVING ->
        LifecycleMonitoringMode.ACTIVE_BACKGROUND
    !notificationsEnabled -> LifecycleMonitoringMode.DISABLED
    strategy == NotificationMonitoringStrategy.REALTIME -> LifecycleMonitoringMode.ACTIVE_BACKGROUND
    strategy == NotificationMonitoringStrategy.POWER_SAVING -> LifecycleMonitoringMode.IDLE_BACKGROUND
    else -> LifecycleMonitoringMode.IDLE_BACKGROUND
}
