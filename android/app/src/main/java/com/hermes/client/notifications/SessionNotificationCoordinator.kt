package com.hermes.client.notifications

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.NotificationSettings
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.ui.localization.AppLanguageProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the notification shade for sessions: observes [SessionRuntimeStore] and keeps exactly one
 * card per session in sync with its phase. WebSocket events, Relay inbox events, and a future push
 * all reach the shade only through the store, so there is one id scheme and one dedup rule.
 *
 * [refresh] is synchronous and idempotent so headless entry points (the JobScheduler fallback)
 * can flush the shade before they finish, without waiting for the flow collector.
 */
@Singleton
class SessionNotificationCoordinator @Inject constructor(
    private val runtimes: SessionRuntimeStore,
    private val settings: NotificationSettings,
    private val notifier: HermesNotifier,
    private val languages: AppLanguageProvider,
    private val profiles: ProfileManager,
    private val sessions: SessionRepository,
    private val appScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val foreground = MutableStateFlow(false)
    private val actionStates = MutableStateFlow<Map<SessionRuntimeKey, NotificationActionState>>(emptyMap())
    private val dismissed = ConcurrentHashMap<SessionRuntimeKey, NotificationKind>()
    @Volatile private var prefs = NotificationPrefs()
    @Volatile private var prefsLoaded = false

    private val lock = Any()
    private val posted = HashMap<SessionRuntimeKey, NotificationSpec>()
    private var postedSummary: NotificationSummary? = null

    val appInForeground: Boolean get() = foreground.value

    /** Must be called from the main thread (Application.onCreate). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { runtimes.setAppInForeground(true); foreground.value = true }
                Lifecycle.Event.ON_STOP -> { runtimes.setAppInForeground(false); foreground.value = false }
                else -> Unit
            }
        })
        // Session state is in-memory, so cards left by a previous process (an ongoing "running"
        // card after an OOM kill, an approval already answered elsewhere) can never be updated
        // again; clear them and let the live state repost what is still true.
        runCatching { notifier.cancelSessionCards() }
        appScope.launch {
            merge(
                runtimes.runtimes,
                runtimes.visibleSessions,
                foreground,
                settings.prefs.onEach { prefs = it; prefsLoaded = true },
                actionStates,
                profiles.list,
                languages.language,
            ).conflate().collect { refresh() }
        }
    }

    /**
     * Headless flush for entry points that may run before the prefs collector has delivered
     * (a JobScheduler cold start): load the preferences first, then project.
     */
    suspend fun refreshAfterPrefs() {
        if (!prefsLoaded) {
            prefs = settings.prefs.first()
            prefsLoaded = true
        }
        refresh()
    }

    /**
     * Re-project every session and apply the difference to the shade. Safe from any thread: the
     * whole plan/apply cycle is serialized so a slower caller can never re-post a stale plan over a
     * newer one.
     */
    fun refresh() = synchronized(lock) {
        val snapshot = runtimes.runtimes.value
        val plan = planNotifications(
            runtimes = snapshot.values,
            visible = runtimes.visibleSessions.value,
            appInForeground = foreground.value,
            prefs = prefs,
            language = languages.current,
            showProfile = profiles.list.value.size > 1,
            titleOf = { key -> sessions.cachedSession(key.sessionId, key.profile)?.title },
            actionStates = actionStates.value,
            dismissed = dismissed,
        )
        // A card the user looked at in the open chat must not return when they navigate away.
        plan.seen.forEach { (key, kind) -> dismissed[key] = kind }
        // A dismissal only covers the kind that was swiped; a new state is a new card.
        dismissed.entries.removeIf { (key, kind) ->
            val runtime = snapshot[key] ?: return@removeIf true
            val current = kindFor(runtime.toNotificationInput(null, false, NotificationActionState.NONE))
            current != kind && key !in plan.seen
        }
        // Action feedback lives only while the card still waits for the user.
        actionStates.update { states ->
            states.filterKeys { key -> plan.cards[key]?.kind?.needsUser == true }
        }
        run {
            val ops = diffPlan(posted, postedSummary, plan)
            ops.forEach { op ->
                runCatching {
                    when (op) {
                        is NotificationOp.Post -> notifier.post(op.spec)
                        is NotificationOp.Cancel -> notifier.cancel(op.id)
                        is NotificationOp.Summary -> op.summary?.let { notifier.postSummary(it) } ?: notifier.cancelSummary()
                    }
                }.onFailure { DebugLog.log("notif", "apply $op failed: ${it.message}") }
            }
            posted.clear()
            posted.putAll(plan.cards)
            postedSummary = plan.summary
        }
    }

    /** The user swiped the session card away; do not repost it until its state changes. */
    fun markDismissed(key: SessionRuntimeKey, kind: NotificationKind) {
        dismissed[key] = kind
        synchronized(lock) { posted.remove(key) }
        refresh()
    }

    fun markActionPending(key: SessionRuntimeKey) = setActionState(key, NotificationActionState.PENDING)
    fun markActionFailed(key: SessionRuntimeKey) = setActionState(key, NotificationActionState.FAILED)
    fun clearActionState(key: SessionRuntimeKey) = setActionState(key, NotificationActionState.NONE)

    private fun setActionState(key: SessionRuntimeKey, state: NotificationActionState) {
        actionStates.update { states ->
            if (state == NotificationActionState.NONE) states - key else states + (key to state)
        }
        refresh()
    }
}
