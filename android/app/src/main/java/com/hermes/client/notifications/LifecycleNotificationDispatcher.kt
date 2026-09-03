package com.hermes.client.notifications

import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.progress.SessionRuntimeStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Folds Relay inbox observations into the shared session state and flushes the shade. The inbox
 * never posts a notification directly: the card is projected from the resulting session phase,
 * so a completion the live socket already delivered updates the same card instead of adding one.
 */
@Singleton
class LifecycleNotificationDispatcher @Inject constructor(
    private val runtimes: SessionRuntimeStore,
    private val notifications: SessionNotificationCoordinator,
) {
    suspend fun dispatch(events: List<LifecycleEventDto>) {
        events.forEach(runtimes::applyObservedLifecycle)
        notifications.refreshAfterPrefs()
    }
}
