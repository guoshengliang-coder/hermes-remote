package com.hermes.client.update

import com.hermes.client.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the card page's 检查更新 row knows about releases.
 *
 * Three states, not two: the design (Stitch 基线-卡片页, second pull 2026-09-11) shows a GREEN dot
 * beside 「v0.1.116 (最新)」 when this build is the newest one, so "we checked and you are current"
 * has to be distinguishable from "we have not checked" — which the old `String?` flow could not do,
 * since it collapsed never-checked, throttled, failed and up-to-date all into null.
 */
sealed interface UpdateBadgeState {
    /** Never checked, throttled before the first check, or every check so far has failed. */
    data object Unknown : UpdateBadgeState

    /** Checked: the index offers nothing newer than this build. */
    data object UpToDate : UpdateBadgeState

    /** Checked: a newer release exists. */
    data class Available(val versionName: String) : UpdateBadgeState
}

/**
 * Lightweight release signal for the card page's entry row. Refreshes are throttled to once per
 * [THROTTLE_MS] so opening the drawer never hammers the index, and a failed fetch keeps the
 * previous answer — the row is a hint, not a health check. The full update page remains the
 * authority (it re-checks on every open).
 */
@Singleton
class UpdateBadge @Inject constructor(
    private val repository: UpdateRepositoryContract,
) {
    private val _state = MutableStateFlow<UpdateBadgeState>(UpdateBadgeState.Unknown)
    val state: StateFlow<UpdateBadgeState> = _state.asStateFlow()

    private var lastFetchMs = 0L

    suspend fun refreshIfStale(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastFetchMs < THROTTLE_MS && lastFetchMs > 0) return
        lastFetchMs = nowMs
        runCatching { repository.fetch() }
            .onSuccess { index ->
                val latest = index.versions.firstOrNull { it.versionCode == index.latestVersionCode }
                _state.value = when {
                    latest == null -> UpdateBadgeState.Unknown
                    latest.versionCode > BuildConfig.VERSION_CODE -> UpdateBadgeState.Available(latest.versionName)
                    // Covers the index being BEHIND this build too (a local build newer than
                    // anything published): nothing to install is nothing to install.
                    else -> UpdateBadgeState.UpToDate
                }
            }
        // Deliberately no onFailure: a failed check must not downgrade a good answer to Unknown,
        // and must not claim up-to-date either. The last successful conclusion still stands.
    }

    private companion object { const val THROTTLE_MS = 60L * 60 * 1000 }
}
