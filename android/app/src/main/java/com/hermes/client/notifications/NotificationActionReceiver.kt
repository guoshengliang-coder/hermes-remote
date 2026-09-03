package com.hermes.client.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.ui.chat.ApprovalChoice
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
 * inline Reply or a choice button → `clarify.respond`. The card shows "Working…" while the RPC
 * runs, the local session state is updated on success so the chat and the card move on together,
 * and a failure puts the buttons back with an HR-NOTIF-001 hint so a lost action is never silent.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var chat: ChatRepository
    @Inject lateinit var runtimes: SessionRuntimeStore
    @Inject lateinit var notifications: SessionNotificationCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        val storedId = intent.getStringExtra(Notif.EXTRA_STORED_SESSION_ID)
            ?: intent.getStringExtra(Notif.EXTRA_SESSION_ID) ?: return
        val key = runtimes.key(storedId, intent.getStringExtra(Notif.EXTRA_PROFILE))
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

        notifications.markActionPending(key)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    withTimeout(8_000) {
                        when (ra) {
                            is ReceiverAction.Approval -> chat.respondApproval(sid, ra.choice)
                            ReceiverAction.Reply, ReceiverAction.Choice -> chat.respondClarify(sid, requestId, answer!!, questionId)
                            else -> Unit
                        }
                    }
                }.onSuccess {
                    when (ra) {
                        is ReceiverAction.Approval -> {
                            runtimes.clearPendingApproval(key)
                            runtimes.continueAfterInput(key)
                        }
                        ReceiverAction.Reply, ReceiverAction.Choice -> {
                            runtimes.lockClarifyAnswer(key, questionId, answer!!)
                            if (runtimes.runtimes.value[key]?.chat?.pendingClarify == null) {
                                runtimes.continueAfterInput(key)
                            }
                        }
                        else -> Unit
                    }
                    notifications.clearActionState(key)
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
