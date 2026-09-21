package com.hermes.client.data.network

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Conflated hint that a Connector-routed REST request received a successful response.
 *
 * This is not itself a health verdict. [GatewayHealthMonitor] consumes it only while unhealthy and
 * verifies recovery through `/api/status`, so one unrelated 2xx can never hide a real outage.
 */
class RoutedRestRecoverySignal {
    private val channel = Channel<Unit>(Channel.CONFLATED)
    val successes: Flow<Unit> = channel.receiveAsFlow()

    fun reportSuccess() {
        channel.trySend(Unit)
    }
}
