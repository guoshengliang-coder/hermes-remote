package com.hermes.client.data.repository

import com.hermes.client.data.network.ProfileDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide source of truth for the active profile and the profile list.
 *
 * A singleton so the navigation drawer (which switches profiles) and the Sessions screen
 * (which must reload when the tenant changes) share one state. [changed] is bumped on every
 * successful switch so observers can react without polling.
 */
@Singleton
class ProfileManager @Inject constructor(private val profiles: ProfileRepository) {
    private val switchMutex = Mutex()
    private val _list = MutableStateFlow<List<ProfileDto>>(emptyList())
    val list: StateFlow<List<ProfileDto>> = _list.asStateFlow()

    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active.asStateFlow()

    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed.asStateFlow()

    suspend fun refresh() {
        runCatching { _list.value = profiles.list() }
        runCatching { _active.value = profiles.active() }
    }

    /** Switches the gateway's active profile and reports whether the target is ready to use. */
    suspend fun switchTo(name: String): Boolean = switchMutex.withLock {
        if (name == _active.value) return@withLock true
        try {
            profiles.setActive(name)
            _active.value = name
            _changed.value = _changed.value + 1
            true
        } catch (cancelled: CancellationException) {
            // A superseded chat-open request must actually stop here. Swallowing cancellation via
            // runCatching allowed an older tap to complete after a newer one and leave the gateway
            // on the wrong profile before navigation.
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
