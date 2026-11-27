package com.hermes.client.data.repository

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.SendTransportOwner
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/** Keeps only the model name, never the rest of the profile configuration. */
@Singleton
class DraftDefaultModelStore internal constructor(
    private val config: ConfigRepository,
    private val accounts: AccountSessionManager,
    private val chat: ChatRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    @Inject constructor(config: ConfigRepository, accounts: AccountSessionManager, chat: ChatRepository, scope: CoroutineScope) :
        this(config, accounts, chat, scope, android.os.SystemClock::elapsedRealtime)

    private data class Owner(val base: String?, val account: String, val device: String, val login: String?)
    private data class Key(val owner: Owner, val profile: String)
    private data class Entry(val value: String, val timestamp: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry>()
    private val requests = mutableMapOf<Key, Deferred<String>>()

    private fun owner(): Owner? {
        val route = accounts.routingContext() ?: return null
        val session = accounts.session.value
        return Owner(session?.baseUrl, route.accountId, route.deviceId, session?.modelSyncGeneration)
    }

    internal fun start() {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
        scope.launch {
            combine(chat.connectionState, lifecycle) { connection, state ->
                connection is ConnectionState.Connected && state.isAtLeast(Lifecycle.State.STARTED)
            }.collectLatest { ready ->
                if (!ready || accounts.routingContext() == null) return@collectLatest
                accounts.session.collectLatest sessionUpdates@{
                    val selected = owner() ?: return@sessionUpdates
                    try {
                        val profile = DEFAULT_NEW_CHAT_PROFILE
                        if (owner() == selected) read(profile)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Optional prefetch. The draft reports failures when it has no usable value.
                    }
                }
            }
        }
    }

    suspend fun read(profile: String, force: Boolean = false): String {
        val route = accounts.routingContext() ?: error("No model read route")
        val captured = owner() ?: error("No model read owner")
        check(captured.account == route.accountId && captured.device == route.deviceId)
        val key = Key(captured, profile)
        val request = synchronized(lock) {
            entries[key]?.takeIf { !force && clock() - it.timestamp < 30_000 }?.let { return it.value }
            requests[key] ?: scope.async(start = CoroutineStart.LAZY) {
                val transport = SendTransportOwner(route) { route.takeIf { owner() == captured } }
                suspend fun fetch(): String = withTimeoutOrNull(7_000) {
                    val value = config.getDraftConfig(profile, transport)["model"] as? JsonPrimitive
                    value?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                        ?: error("Default model missing")
                } ?: throw IOException("Default model read timed out")

                val model = try {
                    fetch()
                } catch (error: Exception) {
                    if (error !is IOException &&
                        !(error is HermesApiException && error.code in setOf(502, 503, 504))) throw error
                    delay(250)
                    check(owner() == captured)
                    fetch()
                }
                check(owner() == captured)
                synchronized(lock) {
                    entries[key] = Entry(model, clock())
                    if (entries.size > 16) entries.remove(entries.keys.first())
                }
                model
            }.also { deferred ->
                requests[key] = deferred
                deferred.invokeOnCompletion {
                    synchronized(lock) { if (requests[key] === deferred) requests.remove(key) }
                }
            }
        }
        val model = request.await()
        check(owner() == captured)
        return model
    }
}
