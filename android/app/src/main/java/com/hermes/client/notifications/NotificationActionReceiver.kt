package com.hermes.client.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.progress.ShadeAnswer
import com.hermes.client.ui.localization.AppLanguageProvider
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.ui.chat.ApprovalChoice
import com.hermes.client.data.auth.AccountSessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/** What a received notification-action intent should do. Pure/testable, no Android deps. */
sealed interface ReceiverAction {
    data class Approval(val choice: ApprovalChoice) : ReceiverAction
    /** Free-text inline reply (RemoteInput) to the current clarify question. */
    data object Reply : ReceiverAction
    /** A clarify choice button; the answer text rides in the intent. */
    data object Choice : ReceiverAction
    /** The user swiped the card away. */
    data object Dismissed : ReceiverAction
    data object Unknown : ReceiverAction
}

fun receiverActionFor(action: String?): ReceiverAction = when (action) {
    Notif.ACTION_ALLOW_ONCE -> ReceiverAction.Approval(ApprovalChoice.ONCE)
    Notif.ACTION_ALLOW_SESSION -> ReceiverAction.Approval(ApprovalChoice.SESSION)
    Notif.ACTION_DENY -> ReceiverAction.Approval(ApprovalChoice.DENY)
    Notif.ACTION_REPLY -> ReceiverAction.Reply
    Notif.ACTION_CHOICE -> ReceiverAction.Choice
    Notif.ACTION_DISMISSED -> ReceiverAction.Dismissed
    else -> ReceiverAction.Unknown
}

/**
 * Handles a notification action headlessly: Allow-once/Session/Deny → `approval.respond`; an
 * inline Reply or a choice button → `clarify.respond` — or, for a card raised by a server→client
 * request (newer Hermes), the response frame / `clarify.lock` for that request id. The card shows "Working…" while the RPC
 * runs, the local session state is updated on success so the chat and the card move on together,
 * and a failure puts the buttons back with an HR-NOTIF-001 hint so a lost action is never silent.
 * An answer Hermes reports as `expired` is lost too, and leaves HR-APPROVAL-003 / HR-CLARIFY-001 in
 * the conversation (SessionRuntimeStore.settleShadeAnswer).
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var chat: ChatRepository
    @Inject lateinit var runtimes: SessionRuntimeStore
    @Inject lateinit var notifications: SessionNotificationCoordinator
    @Inject lateinit var accountSessions: AccountSessionManager
    @Inject lateinit var languages: AppLanguageProvider

    override fun onReceive(context: Context, intent: Intent) {
        val storedId = intent.getStringExtra(Notif.EXTRA_STORED_SESSION_ID)
            ?: intent.getStringExtra(Notif.EXTRA_SESSION_ID) ?: return
        val deviceId = intent.getStringExtra(Notif.EXTRA_DEVICE_ID)
        val key = runtimes.key(storedId, intent.getStringExtra(Notif.EXTRA_PROFILE), deviceId)
        val ra = receiverActionFor(intent.action)
        if (ra is ReceiverAction.Unknown) return
        if (ra is ReceiverAction.Dismissed) {
            val kind = intent.getStringExtra(Notif.EXTRA_KIND)?.let { runCatching { NotificationKind.valueOf(it) }.getOrNull() }
                ?: return
            notifications.markDismissed(key, kind)
            return
        }

        val sid = intent.getStringExtra(Notif.EXTRA_SESSION_ID) ?: storedId
        val answer = when (ra) {
            ReceiverAction.Reply -> RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(Notif.KEY_REPLY_TEXT)?.toString()?.trim()
            ReceiverAction.Choice -> intent.getStringExtra(Notif.EXTRA_ANSWER)?.trim()
            else -> null
        }
        // A blank reply → leave the card as is (retryable) and stop.
        if ((ra is ReceiverAction.Reply || ra is ReceiverAction.Choice) && answer.isNullOrBlank()) return
        val requestId = intent.getStringExtra(Notif.EXTRA_REQUEST_ID).orEmpty()
        val questionId = intent.getStringExtra(Notif.EXTRA_QUESTION_ID)?.takeIf { it.isNotBlank() }
        // Carried by the notification itself, not looked up: after process death the approval card
        // is not restored from disk, and the shade must still answer the request it showed.
        val serverRequest = intent.getBooleanExtra(Notif.EXTRA_SERVER_REQUEST, false)

        notifications.markActionPending(key)
        if (!deviceId.isNullOrBlank() && accountSessions.routeToDevice(deviceId)) {
            chat.reconnect()
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    withTimeout(8_000) {
                        when (ra) {
                            is ReceiverAction.Approval -> chat.respondApproval(
                                sid, ra.choice, requestId.takeIf { serverRequest && it.isNotBlank() },
                            )
                            ReceiverAction.Reply, ReceiverAction.Choice ->
                                chat.respondClarify(sid, requestId, answer!!, questionId, serverRequest)
                            else -> Unit
                        }
                    }
                }.onSuccess { status ->
                    val expired = status == "expired"
                    if (expired) {
                        DebugLog.log("notif", "action on an expired request session=$storedId action=${intent.action}")
                    }
                    if (ra is ReceiverAction.Approval || ra is ReceiverAction.Reply || ra is ReceiverAction.Choice) {
                        runtimes.settleShadeAnswer(
                            key,
                            ShadeAnswer(
                                approval = ra is ReceiverAction.Approval,
                                requestId = requestId.ifBlank { null },
                                serverRequest = serverRequest,
                                questionId = questionId,
                                answer = answer.orEmpty(),
                            ),
                            expired = expired,
                            language = languages.current,
                        )
                    }
                    notifications.clearActionState(key)
                    // Expired: Hermes has moved on without this answer; ask what the run is doing
                    // rather than guess. Not a failure to retry — retrying cannot land.
                    if (expired) runtimes.probe(key, force = true)
                }.onFailure { e ->
                    DebugLog.log("notif", "action failed session=$storedId action=${intent.action}: ${e.message}")
                    notifications.markActionFailed(key)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
