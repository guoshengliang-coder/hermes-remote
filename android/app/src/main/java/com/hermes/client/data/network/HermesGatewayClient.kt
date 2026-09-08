package com.hermes.client.data.network

import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

class GatewayRpcException(val code: Int, message: String) : Exception(message)

/** Endpoint resolution can fail terminally on a local account-repair gate, without network retry. */
class GatewayEndpointException(message: String, val retryable: Boolean) : Exception(message)

/** WebSocket URL plus optional header authentication. Long-lived tokens never enter URLs/logs. */
data class GatewayWebSocketEndpoint(
    val url: String,
    val sessionToken: String? = null,
    val bearerToken: String? = null,
    val accountDeviceId: String? = null,
)

data class BackoffPolicy(
    val baseMs: Long = 500,
    val factor: Double = 2.0,
    val maxMs: Long = 10_000,
) {
    fun delayFor(attempt: Int): Long =
        min(maxMs, (baseMs * factor.pow(attempt)).toLong())
}

internal fun isTerminalAccountHandshakeStatus(status: Int?): Boolean = status == 401 || status == 404

open class HermesGatewayClient(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val rpcTimeoutMs: Long = 60_000L,
    private val accountOkHttp: OkHttpClient? = null,
    /** Called only for terminal account-mode WebSocket handshake responses. */
    private val onAccountHandshakeRejected: (Int, String?) -> Unit = { _, _ -> },
    // suspend so gated mode can fetch a fresh single-use WS ticket (an HTTP round trip) before
    // each connect; loopback mode returns immediately.
    private val wsEndpointProvider: suspend () -> GatewayWebSocketEndpoint,
) {
    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()
    // OkHttp callbacks must never block. A bounded actor preserves event order while the
    // SharedFlow fans events out to multiple collectors. Overflow forces a reconnect so normal
    // history resync can recover, instead of silently dropping lifecycle events.
    private val eventQueue = Channel<ServerEvent>(capacity = 2_048)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    @Volatile private var ws: WebSocket? = null
    @Volatile protected var manuallyClosed = false
    @Volatile private var accountAuthorizationClassificationPending = false
    private val attempt = AtomicInteger(0)
    // Monotonic socket generation. Each openSocket() bumps it; a socket's callbacks are
    // ignored once a newer socket has been opened, so an in-flight backoff reopen can never
    // race a manual reconnectNow() into two live sockets.
    private val generation = AtomicInteger(0)

    init {
        scope.launch {
            for (event in eventQueue) _events.emit(event)
        }
    }

    // Readiness gate: awaited by call() before sending RPCs.
    // Recreated (uncompleted) on each openSocket(); completed when gateway.ready arrives;
    // completed exceptionally when socket closes/fails or close() is called.
    @Volatile private var readyGate: CompletableDeferred<Unit> = CompletableDeferred()

    private companion object {
        const val READY_TIMEOUT_MS = 15_000L
        const val ACCOUNT_AUTHORIZATION_CHANGED_CLOSE_CODE = 4403
    }

    fun connect() {
        // Idempotent: multiple owners (the foreground service, view models, etc.) may all call
        // connect() on this shared singleton. If a socket is already open, or a connect/backoff
        // reconnect is already in flight, this must be a no-op — otherwise a second openSocket()
        // would leak a duplicate live WebSocket that the generation check only shadows, never
        // closes. reconnectNow() intentionally bypasses this guard to force a fresh socket.
        val cur = _state.value
        if (cur is ConnectionState.Connecting || cur is ConnectionState.Connected ||
            cur is ConnectionState.Reconnecting
        ) {
            DebugLog.log("ws", "connect() no-op — already $cur")
            return
        }
        manuallyClosed = false
        openSocket()
    }

    protected fun openSocket() {
        val gen = generation.incrementAndGet()
        DebugLog.log("ws", "opening socket (gen=$gen)")
        // Install a fresh, uncompleted readiness gate for this new socket attempt.
        readyGate = CompletableDeferred()
        _state.value = ConnectionState.Connecting
        // Resolve the URL off the calling thread: gated mode mints a WS ticket (HTTP) here. A
        // failure (e.g. login/ticket error) routes through onSocketClosed so backoff retries.
        scope.launch {
            val endpoint = try {
                wsEndpointProvider()
            } catch (e: Exception) {
                DebugLog.log("ws", "ws url/ticket failed (gen=$gen): ${e.message}")
                onSocketClosed(
                    gen,
                    e.message ?: "ws url failed",
                    retry = (e as? GatewayEndpointException)?.retryable ?: true,
                )
                return@launch
            }
            // A newer socket may have superseded this one — or the client was closed — while we
            // fetched the ticket. Either way, don't open a now-orphaned socket.
            if (gen != generation.get() || manuallyClosed) return@launch
            val hasLegacyToken = !endpoint.sessionToken.isNullOrBlank()
            val hasBearerToken = !endpoint.bearerToken.isNullOrBlank()
            if (hasLegacyToken && hasBearerToken) {
                DebugLog.log("ws", "refusing ambiguous websocket authentication (gen=$gen)")
                onSocketClosed(gen, "ambiguous websocket authentication")
                return@launch
            }
            val request = Request.Builder()
                .url(endpoint.url)
                .apply {
                    endpoint.sessionToken?.takeIf { it.isNotBlank() }?.let {
                        header("X-Hermes-Session-Token", it)
                    }
                    endpoint.bearerToken?.takeIf { it.isNotBlank() }?.let {
                        header("Authorization", "Bearer $it")
                    }
                }
                .build()
            val socketClient = if (hasBearerToken) {
                accountOkHttp ?: run {
                    DebugLog.log("ws", "account transport client unavailable (gen=$gen)")
                    onSocketClosed(gen, "account transport unavailable")
                    return@launch
                }
            } else {
                okHttp
            }
            ws = socketClient.newWebSocket(
                request,
                makeListener(gen, hasBearerToken, endpoint.accountDeviceId),
            )
        }
    }

    /**
     * Force an immediate reconnect, bypassing any pending backoff wait. The previous socket is
     * cancelled; because openSocket() bumps the generation first, the old socket's close callback
     * is ignored and cannot schedule a competing reopen.
     */
    fun reconnectNow() {
        manuallyClosed = false
        accountAuthorizationClassificationPending = false
        attempt.set(0)
        val old = ws
        openSocket()
        old?.cancel()
    }

    private fun makeListener(
        gen: Int,
        accountTransport: Boolean,
        accountDeviceId: String?,
    ) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Do NOT set state to Connected here. Wait for gateway.ready event.
            // Do NOT reset the attempt counter here either.
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation.get()) return // superseded socket — drop late frames
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                when (val msg = parseInbound(json, line)) {
                    is RpcResult -> pending.remove(msg.id)?.complete(msg.result)
                    is RpcErrorReply -> {
                        DebugLog.log("ws", "rpc#${msg.id} ← error ${msg.error.code}: ${msg.error.message}")
                        pending.remove(msg.id)
                            ?.completeExceptionally(GatewayRpcException(msg.error.code, msg.error.message))
                    }
                    is RpcEvent -> {
                        // Handle gateway.ready: flip to Connected and open the readiness gate.
                        if (msg.event.type == "gateway.ready") {
                            accountAuthorizationClassificationPending = false
                            attempt.set(0)
                            _state.value = ConnectionState.Connected
                            readyGate.complete(Unit)
                        }
                        // Log every event except the high-frequency streaming deltas, so the
                        // diagnostic trail stays readable while still capturing errors,
                        // tool calls, and lifecycle around a failure like "message not found".
                        if (msg.event.type != "message.delta" && msg.event.type != "reasoning.delta") {
                            DebugLog.log("ws", "event ${msg.event.type} session=${msg.event.sessionId ?: "-"}")
                        }
                        if (eventQueue.trySend(msg.event).isFailure) {
                            DebugLog.log("ws", "event queue overflow; reconnecting for history resync")
                            webSocket.close(1013, "event queue overflow")
                        }
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation.get()) {
                response?.close()
                return
            }
            val status = response?.code
            response?.close()
            val terminalAccountRejection = accountTransport && isTerminalAccountHandshakeStatus(status)
            if (terminalAccountRejection) {
                accountAuthorizationClassificationPending = false
                runCatching { onAccountHandshakeRejected(requireNotNull(status), accountDeviceId) }
                    .onFailure { DebugLog.log("ws", "account rejection handler failed: ${it.javaClass.simpleName}") }
                onSocketClosed(gen, "account handshake rejected ($status)", retry = false)
            } else if (accountTransport && accountAuthorizationClassificationPending) {
                accountAuthorizationClassificationPending = false
                onSocketClosed(gen, "account authorization classification failed", retry = false)
            } else {
                onSocketClosed(gen, t.message ?: "connection failed")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // OkHttp requires the peer receiving a graceful close to acknowledge it before
            // onClosed is delivered. Without this, Gateway-initiated revocation could remain
            // half-closed and never enter the classification/recovery path below.
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen == generation.get() && accountTransport && code == ACCOUNT_AUTHORIZATION_CHANGED_CLOSE_CODE) {
                accountAuthorizationClassificationPending = true
            }
            onSocketClosed(gen, reason.ifBlank { "closed" })
        }
    }

    protected open fun onSocketClosed(gen: Int, reason: String, retry: Boolean = true) {
        // A newer socket has superseded this one (e.g. reconnectNow()) — ignore its death.
        if (gen != generation.get()) return
        DebugLog.log("ws", "socket closed (gen=$gen): $reason")
        // Fail any call() that is currently awaiting readiness so it throws immediately.
        readyGate.completeExceptionally(GatewayRpcException(0, reason))
        failAllPending(reason)
        if (manuallyClosed || !retry) {
            _state.value = ConnectionState.Disconnected
            return
        }
        _state.value = ConnectionState.Reconnecting
        val delayMs = backoff.delayFor(attempt.getAndIncrement())
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (!manuallyClosed && gen == generation.get()) openSocket()
        }
    }

    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(GatewayRpcException(0, reason))
        }
    }

    suspend fun call(method: String, params: JsonObject): JsonElement {
        // Wait until gateway.ready has been received before sending any RPC.
        // Bounded wait: if the server never sends gateway.ready, throw after READY_TIMEOUT_MS.
        // The await() happens BEFORE registering in `pending`, so a timeout here never leaks
        // a pending entry.
        try {
            withTimeout(READY_TIMEOUT_MS) { readyGate.await() }
        } catch (e: TimeoutCancellationException) {
            throw GatewayRpcException(0, "gateway readiness timeout")
        }
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        DebugLog.log("ws", "rpc#$id → $method")
        val sent = ws?.send(RpcRequest(id, method, params).encode(json)) ?: false
        if (!sent) {
            pending.remove(id)
            DebugLog.log("ws", "rpc#$id $method failed: not connected")
            throw GatewayRpcException(0, "not connected")
        }
        return try {
            withTimeout(rpcTimeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw GatewayRpcException(0, "gateway response timeout")
        } finally {
            pending.remove(id, deferred)
        }
    }

    fun close() {
        manuallyClosed = true
        accountAuthorizationClassificationPending = false
        // Fail any call() awaiting readiness so it throws immediately rather than hanging.
        readyGate.completeExceptionally(GatewayRpcException(0, "client closing"))
        failAllPending("client closing")
        ws?.close(1000, "client closing")
        ws = null
        _state.value = ConnectionState.Disconnected
    }

    /** Immediately cancel the underlying socket (no graceful close handshake). */
    internal fun cancelNow() {
        manuallyClosed = true
        accountAuthorizationClassificationPending = false
        readyGate.completeExceptionally(GatewayRpcException(0, "client cancelled"))
        ws?.cancel()
        ws = null
        _state.value = ConnectionState.Disconnected
    }
}
