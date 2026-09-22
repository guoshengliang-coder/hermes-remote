package com.hermes.client.notifications.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hermes.client.data.diagnostics.DebugLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives FCM data-only wake hints (HG-94). Never posts a notification itself: the handler runs
 * the shared inbox sync, and cards are projected from SessionRuntimeStore as for every other source.
 */
@AndroidEntryPoint
class HermesMessagingService : FirebaseMessagingService() {
    @Inject lateinit var handler: PushMessageHandler
    @Inject lateinit var registration: PushRegistrationManager

    override fun onNewToken(token: String) {
        // Never log the token: it is this phone's push address.
        registration.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Already on a background thread; FCM allows ~10 s before the process may be frozen, and
        // returning ends the window, so block here on a bounded budget.
        runBlocking {
            try {
                val outcome = withTimeoutOrNull(HANDLE_BUDGET_MS) { handler.handle(message.data) }
                DebugLog.log("push", "wake hint ${outcome ?: "timed out"}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.log("push", "wake hint failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val HANDLE_BUDGET_MS = 9_000L
    }
}
