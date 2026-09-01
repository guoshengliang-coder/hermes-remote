package com.hermes.client.ui.startup

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps exactly one recovery callback for the destination currently composed by Navigation.
 * StartupViewModel invokes it behind the warm-start overlay after `gateway.ready`, allowing the
 * real screen ViewModel to commit its refreshed state before the overlay is removed.
 */
@Singleton
class ForegroundRecoveryCoordinator @Inject constructor() {
    private data class Registration(
        val key: String,
        val recover: suspend () -> Boolean,
    )

    private val lock = Any()
    private var active: Registration? = null

    fun register(key: String, recover: suspend () -> Boolean) {
        synchronized(lock) { active = Registration(key, recover) }
    }

    fun unregister(key: String) {
        synchronized(lock) {
            if (active?.key == key) active = null
        }
    }

    /** Null means no dynamic screen is registered, so the caller should use its route fallback. */
    suspend fun recoverActive(): Boolean? = synchronized(lock) { active }?.recover?.invoke()
}
