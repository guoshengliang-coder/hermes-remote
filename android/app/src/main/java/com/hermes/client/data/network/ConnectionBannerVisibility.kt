package com.hermes.client.data.network

import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan

/**
 * How long an outage must last before the chat banner announces it. Short enough that a genuine
 * problem is still reported promptly, long enough that the socket churn around an app switch —
 * which the run itself never notices — stays invisible.
 */
const val CONNECTION_BANNER_GRACE_MS = 2_500L

/** The current state plus when the outage it belongs to began; `startedAt` is null while connected. */
internal data class ConnectionOutage(val state: ConnectionState, val startedAt: Long?)

/**
 * Connection state for the chat banner: `null` means "say nothing".
 *
 * An outage is announced only once it has lasted [graceMs], and the clock runs from the moment the
 * connection was first lost — not from the latest state change. That distinction matters: reconnect
 * backoff cycles through Reconnecting → Connecting → Reconnecting, and restarting the timer on each
 * step would keep a real, lengthening outage permanently hidden. Once the banner is up, later
 * states within the same outage replace it immediately; recovery hides it immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<ConnectionState>.connectionBanner(
    graceMs: Long = CONNECTION_BANNER_GRACE_MS,
    now: () -> Long = System::currentTimeMillis,
): Flow<ConnectionState?> = distinctUntilChanged()
    .scan(ConnectionOutage(ConnectionState.Connected, startedAt = null)) { previous, state ->
        ConnectionOutage(
            state = state,
            startedAt = when {
                state is ConnectionState.Connected -> null
                previous.startedAt != null -> previous.startedAt
                else -> now()
            },
        )
    }
    .flatMapLatest<ConnectionOutage, ConnectionState?> { outage ->
        val startedAt = outage.startedAt
        if (startedAt == null) {
            flowOf(null)
        } else {
            flow {
                val remaining = graceMs - (now() - startedAt)
                if (remaining > 0) delay(remaining)
                emit(outage.state)
            }
        }
    }
    .distinctUntilChanged()
    .onEach { banner ->
        // Whether the grace swallowed an outage or none happened is invisible from the outside,
        // and those two look identical in a bug report ("it reconnected but said nothing").
        DebugLog.log(
            "banner",
            if (banner == null) "hidden" else "showing ${banner::class.simpleName}",
        )
    }
