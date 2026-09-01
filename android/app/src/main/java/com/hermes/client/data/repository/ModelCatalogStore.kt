package com.hermes.client.data.repository

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ConnectivityChecker
import com.hermes.client.data.network.ModelProviderDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide model catalog cache, so the model picker opens instantly instead of fetching on
 * every open. Refreshed in the background on every app start/foreground, on profile switch, on
 * the Disconnected→Connected edge, and on explicit retry. All background fetches are silent:
 * a failure keeps the previous cache and only surfaces in UI when a consumer has nothing cached.
 *
 * The cache is keyed by the profile name captured when the fetch STARTS (null — "gateway's
 * active profile, name unknown" — is a distinct legitimate key). Results only ever land under
 * their own key, so a late response from a switched-away profile can never render for another
 * profile. In-memory only by design; persisting the last catalog to DataStore is a possible
 * follow-up, not done here.
 */
@Singleton
class ModelCatalogStore @Inject constructor(
    private val models: ModelRepository,
    private val profileManager: ProfileManager,
    private val credentials: CredentialStore,
    private val connectivity: ConnectivityChecker,
    private val chat: ChatRepository,
    private val appScope: CoroutineScope,
) {
    /** What the ACTIVE profile's picker should render right now. */
    data class ActiveCatalog(
        val profile: String?,
        val providers: List<ModelProviderDto> = emptyList(),
        /** A fetch for this profile has completed successfully at least once. */
        val loaded: Boolean = false,
        /** A fetch for this profile is in flight. */
        val refreshing: Boolean = false,
        /** The last fetch for this profile failed (previous cache, if any, is retained). */
        val failed: Boolean = false,
    )

    private companion object {
        /** Collapse the start-time trigger pile-up (foreground + profile + connect) to one fetch. */
        const val MIN_REFRESH_INTERVAL_MS = 15_000L
    }

    private val cache = MutableStateFlow<Map<String?, List<ModelProviderDto>>>(emptyMap())
    private val refreshingKeys = MutableStateFlow<Set<String?>>(emptySet())
    private val failedKeys = MutableStateFlow<Set<String?>>(emptySet())
    private val lastSuccessAt = HashMap<String?, Long>()  // guarded by synchronized(lastSuccessAt)

    val state: StateFlow<ActiveCatalog> =
        combine(profileManager.active, cache, refreshingKeys, failedKeys) { active, c, refreshing, failed ->
            ActiveCatalog(
                profile = active,
                providers = c[active].orEmpty(),
                loaded = c.containsKey(active),
                refreshing = active in refreshing,
                failed = active in failed,
            )
        }.stateIn(appScope, SharingStarted.Eagerly, ActiveCatalog(profile = null))

    private val started = AtomicBoolean(false)
    private val triggersStarted = AtomicBoolean(false)

    /** Wire all refresh triggers. Idempotent; call once from Application.onCreate (main thread). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) onForeground()
            },
        )
        startTriggers()
    }

    /**
     * The non-ProcessLifecycleOwner triggers — separate so JVM tests can exercise them
     * (ProcessLifecycleOwner needs an Android runtime).
     */
    internal fun startTriggers() {
        if (!triggersStarted.compareAndSet(false, true)) return
        // Profile switch — also covers the null→name transition right after the first
        // ProfileManager.refresh(), which is the very first fetch on a fresh setup.
        appScope.launch {
            profileManager.active.drop(1).collect { refresh(force = false) }
        }
        // Disconnected→Connected edge: covers "started offline, network came back" and the
        // first connect after setup. Same shape as SessionRuntimeStore's reconnect detector.
        appScope.launch {
            var previous: ConnectionState? = null
            chat.connectionState.collect { connection ->
                val cameUp = connection is ConnectionState.Connected &&
                    previous != null && previous !is ConnectionState.Connected
                previous = connection
                if (cameUp) refresh(force = false)
            }
        }
    }

    /** ON_START (cold and warm starts). Exposed for JVM tests. */
    internal fun onForeground() = refresh(force = false)

    /**
     * Fire-and-forget refresh of the ACTIVE profile's catalog. Always a silent skip when the
     * gateway isn't configured, the device is offline, or a fetch for this profile is already
     * in flight. `force = false` is the background mode (start/foreground, profile switch,
     * reconnect, sheet open): it fetches when the cache is missing or stale and skips a fresh
     * cache, so the cold-start trigger pile-up costs one request. `force = true` (explicit
     * retry, after a default-model write) always fetches.
     */
    fun refresh(force: Boolean = false) {
        val profile = profileManager.active.value
        if (credentials.load() == null) return
        if (!connectivity.isOnline()) return
        if (!force && cache.value.containsKey(profile)) {
            val last = synchronized(lastSuccessAt) { lastSuccessAt[profile] }
            if (last != null && System.currentTimeMillis() - last < MIN_REFRESH_INTERVAL_MS) return
        }
        val alreadyInFlight = profile in refreshingKeys.getAndUpdate { it + profile }
        if (alreadyInFlight) return
        appScope.launch {
            try {
                runCatching { models.providers(profile) }
                    .onSuccess { list ->
                        cache.update { it + (profile to list) }
                        failedKeys.update { it - profile }
                        synchronized(lastSuccessAt) { lastSuccessAt[profile] = System.currentTimeMillis() }
                    }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        failedKeys.update { it + profile }
                    }
            } finally {
                refreshingKeys.update { it - profile }
            }
        }
    }
}
