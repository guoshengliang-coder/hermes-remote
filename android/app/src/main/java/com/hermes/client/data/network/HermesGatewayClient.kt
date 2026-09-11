package com.hermes.client.data.network

import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
private const val HANDSHAKE_TIMEOUT_MS = 20_000L

open class HermesGatewayClient(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val rpcTimeoutMs: Long = 60_000L,
    private val accountOkHttp: OkHttpClient? = null,
    /** Called only for terminal account-mode WebSocket handshake responses. */
    private val onAccountHandshakeRejected: (Int, String?) -> Unit = { _, _ -> },
    /**
     * How long a socket may sit open without `gateway.ready` before it is torn down and retried
     * (HG-19). Injectable so tests do not have to wait out the real value.
     *
     * Keep it ABOVE [READY_TIMEOUT_MS] in production: an RPC waiting on the readiness gate should
     * report its own timeout first, rather than be cut short by a reconnect it cannot explain.
     */
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
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

    /**
     * An in-flight RPC. The method travels with the deferred so a failure reply can name itself:
     * once a polling method's request line is suppressed, `rpc#147 ← error 4001` would otherwise
     * have nothing to pair with.
     */
    private data class PendingCall(val method: String, val deferred: CompletableDeferred<JsonElement>)

    private val pending = ConcurrentHashMap<Long, PendingCall>()

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
        // One place that records every connection transition. `_state` is assigned at seven sites
        // and only the drop into Reconnecting was ever logged, so "when did it come back" had to
        // be inferred from `gateway.ready` plus the banner. A collector also covers assignment
        // sites added later, which seven scattered log lines would not.
        var previous = _state.value
        scope.launch {
            _state.collect { current ->
                if (current == previous) return@collect
                val from = previous
                previous = current
                DebugLog.log("ws") { "state ${describe(from)} → ${describe(current)}" }
            }
        }
        DebugLog.setStateSnapshot { connectionSnapshot() }
    }

    /**
     * Everything about the socket a reader needs at once, in one line.
     *
     * Scattered lines could not answer "why is it still Connecting" after the fact: HG-27 left a
     * generation with no ready, no close and no watchdog line, and the log could not say which of
     * the watchdog's guards had returned early. This says all of them, at the moment the user
     * first sees the problem.
     */
    fun connectionSnapshot(): String {
        val now = System.currentTimeMillis()
        val connectingSince = connectingSinceMs
        val readyAt = lastReadyAtMs
        return "state=${describe(_state.value)} gen=${generation.get()} attempt=${attempt.get()} " +
            "manuallyClosed=$manuallyClosed " +
            "readyGate=${if (readyGate.isCompleted) "completed" else "pending"} " +
            "watchdog=${handshakeWatchdog?.let { if (it.isActive) "active" else "finished" } ?: "none"} " +
            "socket=${if (ws == null) "none" else "present"} " +
            "connectingFor=${if (connectingSince == 0L) "-" else "${now - connectingSince}ms"} " +
            "sinceReady=${if (readyAt == 0L) "never" else "${now - readyAt}ms"}"
    }

    // Readiness gate: awaited by call() before sending RPCs.
    // Recreated (uncompleted) on each openSocket(); completed when gateway.ready arrives;
    // completed exceptionally when socket closes/fails or close() is called.
    @Volatile private var readyGate: CompletableDeferred<Unit> = CompletableDeferred()

    /**
     * Cancels the handshake watchdog for the socket currently being opened. See [openSocket].
     */
    @Volatile private var handshakeWatchdog: Job? = null

    /** The generation whose death has already been processed; see [onSocketClosed]. */
    @Volatile private var closedGen = -1

    /** When the current [ConnectionState.Connecting] began, or 0 while not connecting. */
    @Volatile private var connectingSinceMs = 0L

    /** When `gateway.ready` last arrived, or 0 if it never has in this process. */
    @Volatile private var lastReadyAtMs = 0L

    private companion object {
        const val READY_TIMEOUT_MS = 15_000L
        const val ACCOUNT_AUTHORIZATION_CHANGED_CLOSE_CODE = 4403

        /**
         * Streaming increments, too frequent to record: one line each would push everything else
         * out of a log whose real limit is bytes. `thinking.delta` was missing from this set and
         * accounted for 25 of the 500 buffered entries in the HG-27 report — a quarter of a
         * category that was supposed to be filtered.
         */
        val STREAMING_DELTAS = setOf("message.delta", "reasoning.delta", "thinking.delta")

        /** Polling RPCs: recorded only when they are slow or fail. See [call]. */
        val QUIET_RPC_METHODS = setOf("process.list")

        /**
         * Methods whose *outcome* is logged, not just their request. Reserved for calls that decide
         * which conversation every later line refers to: without the answer, a later "session not
         * found" cannot be told apart from a create that never worked.
         */
        val OUTCOME_RPC_METHODS = setOf("session.create")

        /** A quiet RPC this slow is worth a line even though it succeeded. */
        const val SLOW_RPC_MS = 1_000L
    }

    /** State names for the log; [ConnectionState.Error] carries a reason worth printing. */
    private fun describe(state: ConnectionState): String = when (state) {
        is ConnectionState.Error -> "Error(${state.reason})"
        else -> state::class.simpleName ?: "?"
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
        connectingSinceMs = System.currentTimeMillis()
        _state.value = ConnectionState.Connecting
        // Connecting has exactly two exits — `gateway.ready` or the socket dying — and a socket
        // that establishes but never completes the handshake takes neither. On 2026-09-07 one
        // such socket left the app on 「正在连接 Relay…」 until it was force-stopped, while every
        // RPC failed individually on READY_TIMEOUT_MS and REST health stayed green the whole time
        // (HG-19). The per-call timeout protects a call; nothing protected the socket. This does.
        handshakeWatchdog?.cancel()
        handshakeWatchdog = scope.launch {
            kotlinx.coroutines.delay(handshakeTimeoutMs)
            // Each guard says which one it was. HG-27 is the reason: the watchdog produced no line
            // at all, so the log could not distinguish "a newer socket superseded this one" from
            // "the app closed it" from "the body never ran" — three different faults with three
            // different owners, and the analysis had to stop at "cannot tell".
            val skipped = when {
                gen != generation.get() -> "superseded by gen=${generation.get()}"
                manuallyClosed -> "closed by the app"
                readyGate.isCompleted -> "readiness already settled"
                else -> null
            }
            if (skipped != null) {
                DebugLog.log("ws", "handshake watchdog skipped (gen=$gen): $skipped")
                return@launch
            }
            DebugLog.log("error", "handshake timeout (gen=$gen): no gateway.ready in ${handshakeTimeoutMs}ms")
            // Cancel AND report: cancel() normally makes OkHttp deliver onFailure, but the whole
            // point of this watchdog is a socket that has stopped behaving normally, so the
            // reconnect must not depend on that callback arriving. onSocketClosed is idempotent
            // per generation, so whichever path lands second is ignored.
            ws?.cancel()
            onSocketClosed(gen, "gateway handshake timeout")
        }
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
            // The URL carries the single-use ticket as a query parameter, so only the fact is
            // logged, never the value. Without this line a hung ticket POST and a socket that
            // never dials look identical: both are silence after "opening socket".
            DebugLog.log("ws", "ws endpoint ready (gen=$gen)")
            // A newer socket may have superseded this one — or the client was closed — while we
            // fetched the ticket. Either way, don't open a now-orphaned socket.
            if (gen != generation.get() || manuallyClosed) {
                DebugLog.log("ws", "ws endpoint discarded (gen=$gen): " +
                    if (manuallyClosed) "closed by the app" else "superseded by gen=${generation.get()}")
                return@launch
            }
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
        DebugLog.log("ws", "reconnectNow() forcing a fresh socket")
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
            //
            // It does get a line, though. Until it had one, the stretch between "opening socket"
            // and either ready or a close was completely dark, and the runbook's rule for spotting
            // a stall — the generation with neither ready nor close after it — could not tell a
            // dial that never completed from a gateway that accepted the upgrade and went mute.
            // Those are different failures with different owners.
            DebugLog.log("ws", "socket upgraded (gen=$gen)")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation.get()) return // superseded socket — drop late frames
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                when (val msg = parseInbound(json, line)) {
                    is RpcResult -> pending.remove(msg.id)?.deferred?.complete(msg.result)
                    is RpcErrorReply -> {
                        val call = pending.remove(msg.id)
                        DebugLog.log("ws", "rpc#${msg.id} ${call?.method ?: "?"} ← error " +
                            "${msg.error.code}: ${msg.error.message}")
                        call?.deferred
                            ?.completeExceptionally(GatewayRpcException(msg.error.code, msg.error.message))
                    }
                    is RpcEvent -> {
                        // Handle gateway.ready: flip to Connected and open the readiness gate.
                        if (msg.event.type == "gateway.ready") {
                            accountAuthorizationClassificationPending = false
                            attempt.set(0)
                            lastReadyAtMs = System.currentTimeMillis()
                            handshakeWatchdog?.cancel()
                            _state.value = ConnectionState.Connected
                            readyGate.complete(Unit)
                        }
                        // Log every event except the high-frequency streaming deltas, so the
                        // diagnostic trail stays readable while still capturing errors,
                        // tool calls, and lifecycle around a failure like "message not found".
                        if (msg.event.type !in STREAMING_DELTAS) {
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
        // One death per socket. The handshake watchdog both cancels the socket and reports it
        // closed, so OkHttp's onFailure for that cancellation arrives second; without this guard
        // it would schedule a SECOND backoff reconnect and leave two live sockets, which the
        // generation check only shadows and never closes.
        if (gen == closedGen) return
        closedGen = gen
        handshakeWatchdog?.cancel()
        DebugLog.log("ws", "socket closed (gen=$gen): $reason")
        // Fail any call() that is currently awaiting readiness so it throws immediately.
        readyGate.completeExceptionally(GatewayRpcException(0, reason))
        failAllPending(reason)
        connectingSinceMs = 0L
        if (manuallyClosed || !retry) {
            _state.value = ConnectionState.Disconnected
            return
        }
        _state.value = ConnectionState.Reconnecting
        val attemptNo = attempt.getAndIncrement()
        val delayMs = backoff.delayFor(attemptNo)
        DebugLog.log("ws", "reconnect scheduled in ${delayMs}ms (gen=$gen, attempt=$attemptNo)")
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            // The reconnect that does not happen is the shape of an unexplained permanent stall:
            // before this line the log simply stopped, and nothing said whether the app had closed
            // the socket on purpose or a newer generation had taken over. Those look identical
            // from outside and only one of them is a bug.
            if (manuallyClosed || gen != generation.get()) {
                DebugLog.log("ws", "reconnect dropped (gen=$gen): " +
                    if (manuallyClosed) "closed by the app" else "superseded by gen=${generation.get()}")
                return@launch
            }
            openSocket()
        }
    }

    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.deferred?.completeExceptionally(GatewayRpcException(0, reason))
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
            // Every feature reporting its own readiness timeout, 15s apart, with nothing naming
            // the socket, is exactly what HG-27 looked like from the outside.
            DebugLog.log("error", "rpc $method blocked: no gateway.ready in ${READY_TIMEOUT_MS}ms")
            throw GatewayRpcException(0, "gateway readiness timeout")
        }
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        val call = PendingCall(method, deferred)
        pending[id] = call
        // A polling method stays quiet while it is quick and successful — the rule the inbox poll
        // already follows (DESIGN.md §5.15). process.list runs every 5s per active run and was 55
        // of the 500 buffered entries in the HG-27 report. Slow and failing calls still speak.
        val quiet = method in QUIET_RPC_METHODS
        if (!quiet) DebugLog.log("ws") { "rpc#$id → $method" }
        val startedAt = System.currentTimeMillis()
        val sent = ws?.send(RpcRequest(id, method, params).encode(json)) ?: false
        if (!sent) {
            pending.remove(id)
            DebugLog.log("ws", "rpc#$id $method failed: not connected")
            throw GatewayRpcException(0, "not connected")
        }
        return try {
            val result = withTimeout(rpcTimeoutMs) { deferred.await() }
            val elapsed = System.currentTimeMillis() - startedAt
            // A quiet method speaks when it was slow; a session-shaping method always speaks. HG-29
            // was a session that went missing two minutes after session.create, and the log could
            // not say whether the create had ever succeeded: the request line was there and nothing
            // followed it either way.
            if (method in OUTCOME_RPC_METHODS || (quiet && elapsed >= SLOW_RPC_MS)) {
                DebugLog.log("ws", "rpc#$id $method ← ok (${elapsed}ms)")
            }
            result
        } catch (e: TimeoutCancellationException) {
            // Previously this threw with no line at all: an opening line and no outcome reads
            // exactly like a request that never returned.
            DebugLog.log("error", "rpc#$id $method timed out after ${rpcTimeoutMs}ms")
            throw GatewayRpcException(0, "gateway response timeout")
        } finally {
            pending.remove(id, call)
        }
    }

    /**
     * Shut the socket on purpose. [reason] names the caller, because the three call sites mean
     * three different things (notifications off, an idle background app, an expired keep-alive
     * lease) and the log used to show none of them — only the socket's own
     * `socket closed: client closing`, with no hint that the app had asked for it.
     */
    fun close(reason: String = "unspecified") {
        DebugLog.log("ws", "close() requested: $reason (manuallyClosed $manuallyClosed → true)")
        manuallyClosed = true
        accountAuthorizationClassificationPending = false
        connectingSinceMs = 0L
        // Fail any call() awaiting readiness so it throws immediately rather than hanging.
        readyGate.completeExceptionally(GatewayRpcException(0, "client closing"))
        failAllPending("client closing")
        ws?.close(1000, "client closing")
        ws = null
        _state.value = ConnectionState.Disconnected
    }

    /** Immediately cancel the underlying socket (no graceful close handshake). */
    internal fun cancelNow() {
        DebugLog.log("ws", "cancelNow() (manuallyClosed $manuallyClosed → true)")
        manuallyClosed = true
        accountAuthorizationClassificationPending = false
        connectingSinceMs = 0L
        readyGate.completeExceptionally(GatewayRpcException(0, "client cancelled"))
        ws?.cancel()
        ws = null
        _state.value = ConnectionState.Disconnected
    }
}
