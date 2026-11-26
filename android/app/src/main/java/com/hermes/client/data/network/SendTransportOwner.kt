package com.hermes.client.data.network

import com.hermes.client.data.auth.AccountRoutingContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Keeps a detached send on the account and Mac selected when the user submitted it. */
internal class SendTransportOwner(
    val route: AccountRoutingContext,
    private val currentRoute: () -> AccountRoutingContext?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SendTransportOwner>

    fun isCurrent(): Boolean = currentRoute() == route
}
