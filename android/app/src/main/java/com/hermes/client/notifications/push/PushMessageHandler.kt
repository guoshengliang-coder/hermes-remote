package com.hermes.client.notifications.push

import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.network.LifecycleEventDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What an FCM wake hint does (HG-94): exactly what the 15-minute job does — one Relay inbox sync
 * folded through the shared dispatcher — so there is still only one card-posting path. The hint
 * itself is folded in only when that sync fails, as a title-less observation of the same event.
 */
class PushMessageHandler(
    private val notificationsEnabled: suspend () -> Boolean,
    /** The shared inbox sync: `LifecycleEventRepository.sync` feeding the dispatcher. */
    private val syncInbox: suspend () -> Unit,
    /** `LifecycleNotificationDispatcher.dispatch`. */
    private val dispatch: suspend (List<LifecycleEventDto>) -> Unit,
    private val syncBudgetMs: Long = SYNC_BUDGET_MS,
) {
    enum class Outcome { IGNORED, NOTIFICATIONS_OFF, SYNCED, FOLDED_HINT }

    suspend fun handle(data: Map<String, String>): Outcome {
        val hint = PushHint.parse(data) ?: return Outcome.IGNORED
        if (!notificationsEnabled()) return Outcome.NOTIFICATIONS_OFF
        val synced = try {
            withTimeoutOrNull(syncBudgetMs) { syncInbox(); true } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugLog.log("push", "inbox sync after ${hint.event} failed: ${error.javaClass.simpleName}")
            false
        }
        if (synced) return Outcome.SYNCED
        // At-least-once, like the inbox: when the inbox later delivers the same event it lands on
        // the same card, and applyObservedLifecycle's terminal-replay rule keeps it from re-alerting.
        dispatch(listOf(hint.toLifecycleEventDto()))
        return Outcome.FOLDED_HINT
    }

    companion object {
        /**
         * FCM gives onMessageReceived roughly 10 s before the process may be frozen; leave room
         * for the fallback fold and the shade flush after the sync gives up.
         */
        const val SYNC_BUDGET_MS = 6_000L
    }
}
