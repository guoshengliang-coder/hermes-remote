package com.hermes.client.update

import com.hermes.client.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight "a newer release exists" signal for the settings entry row. Refreshes are
 * throttled to once per [THROTTLE_MS] so opening the card page never hammers the index, and a
 * failed fetch just keeps the previous answer — the entry dot is a hint, not a health check.
 * The full update page remains the authority (it re-checks on every open).
 */
@Singleton
class UpdateBadge @Inject constructor(
    private val repository: UpdateRepositoryContract,
) {
    private val _available = MutableStateFlow<String?>(null)
    /** Version name of the newer release, or null when up to date / unknown. */
    val available: StateFlow<String?> = _available.asStateFlow()

    private var lastFetchMs = 0L

    suspend fun refreshIfStale(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastFetchMs < THROTTLE_MS && lastFetchMs > 0) return
        lastFetchMs = nowMs
        runCatching { repository.fetch() }
            .onSuccess { index ->
                val latest = index.versions.firstOrNull { it.versionCode == index.latestVersionCode }
                _available.value = latest
                    ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                    ?.versionName
            }
    }

    private companion object { const val THROTTLE_MS = 60L * 60 * 1000 }
}
