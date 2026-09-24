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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

open class GatewayRpcException(val code: Int, message: String) : Exception(message)

/**
 * The Mac answered this call, but the answer was larger than the relay is willing to carry, so the
 * Connector dropped it and synthesized this error in its place (`connector/src/oversized-frame.ts`).
 *
 * Hand-copied, like every other wire constant we do not own the definition of: it is minted by the
 * Connector, in JSON-RPC's reserved implementation range so it can never collide with an upstream
 * Hermes code. Registered as `HR-SESS-017`.
 *
 * Nothing the phone does makes the next attempt smaller — the answer is the conversation's whole
 * transcript with every attachment re-inlined — so this is terminal, not retryable.
 */
const val RELAY_RESPONSE_TOO_LARGE_CODE = -32001

/**
 * The socket is up but `gateway.ready` never arrived, so the RPC was never sent. Distinct
 * from a generic transport failure because it has its own registered meaning and its own
 * copy: HR-CONN-003 says the Relay connected and the *handshake* timed out, which is a
 * different thing to tell the user than "the send failed".
 */
class GatewayReadinessTimeoutException(message: String) : GatewayRpcException(0, message)

/** The request was sent, but no answer arrived; its server-side outcome is unknown. */
class GatewayResponseTimeoutException(message: String) : GatewayRpcException(0, message)

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
    /**
     * How long the client may stay in one non-terminal state before [superviseLiveness] treats it
     * as stopped. Must exceed both [handshakeTimeoutMs] and the longest backoff, or the supervisor
     * would interrupt work that is still legitimately in progress.
     */
    private val stallDeadlineMs: Long = HANDSHAKE_TIMEOUT_MS + BackoffPolicy().maxMs + 15_000L,
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

    /**
     * Server requests received while a `session.resume` is in flight, per resume call id. Upstream
     * snapshots `open_requests` before its worker writes the answer, and registering a request does
     * not take the resume lock — so a question can reach us *before* the answer that omits it. It is
     * open all the same, and the snapshot must not prune it. See [onResumeAnswered].
     */
    private val resumeWindows = ConcurrentHashMap<Long, MutableSet<String>>()

    @Volatile private var ws: WebSocket? = null
    @Volatile protected var manuallyClosed = false

    /**
     * Serialises the compound steps that decide whether a socket may be opened. Every one of
     * them is "read [manuallyClosed], then move [_state]", and read and move used to be two
     * separate instructions on a multi-threaded scope. HG-42: `connect()` cleared
     * `manuallyClosed`, `close("app idle in the background")` landed between that and
     * `openSocket()`, and the client came to rest in Connecting with `manuallyClosed=true` and
     * no socket — a state nothing can leave, because `onSocketClosed()` needs a socket and
     * every later `connect()` is turned away by the already-Connecting guard. It sat there for
     * 13 minutes with every RPC failing on the readiness gate. Volatile gives visibility; only
     * a lock gives the pair atomicity.
     */
    private val lifecycleLock = Any()

    /**
     * Test-only scheduling seam, invoked between the lifecycle steps by name.
     *
     * A fuzz cannot find a race whose window is the few nanoseconds between releasing a lock and
     * taking it again; measured, 1,600 concurrent lifecycle calls entered that window zero times.
     * Races this narrow are not found by shaking the box harder, they are found by being able to
     * stop time at the seam — so the seams say where they are, and a test decides what lands
     * there. Null in production, where this costs one null check per connect.
     */
    @Volatile internal var lifecycleSeam: ((String) -> Unit)? = null
    @Volatile private var accountAuthorizationClassificationPending = false
    private val attempt = AtomicInteger(0)
    // Monotonic socket generation. Each openSocket() bumps it; a socket's callbacks are
    // ignored once a newer socket has been opened, so an in-flight backoff reopen can never
    // race a manual reconnectNow() into two live sockets.
    private val generation = AtomicInteger(0)
    /** Only the first unanswered RPC on a socket may replace that socket. */
    private val recoveredTimeoutGeneration = AtomicInteger(-1)

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
        superviseLiveness()
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
        return "state=${describe(_state.value)} gen=${generation.get()} conn=$connectionId attempt=${attempt.get()} " +
            "manuallyClosed=$manuallyClosed " +
            "readyGate=${if (readyGate.isCompleted) "completed" else "pending"} " +
            "watchdog=${handshakeWatchdog?.let { if (it.isActive) "active" else "finished" } ?: "none"} " +
            "socket=${if (ws == null) "none" else "present"} " +
            "connectingFor=${if (connectingSince == 0L) "-" else "${now - connectingSince}ms"} " +
            "sinceReady=${if (readyAt == 0L) "never" else "${now - readyAt}ms"} " +
            "serverRequests=$serverRequestsState"
    }

    // Readiness gate: awaited by call() before sending RPCs.
    // Recreated (uncompleted) on each openSocket(); completed when gateway.ready arrives;
    // completed exceptionally when socket closes/fails or close() is called.
    @Volatile private var readyGate: CompletableDeferred<Unit> = CompletableDeferred()

    /** Opaque socket correlation ID; the Gateway logs it beside its own tunnel UUID. */
    @Volatile private var connectionId = "none"
    @Volatile private var upgradedGeneration = -1

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

    /**
     * When the CURRENT socket became ready, or 0 if this one never did. Distinct from
     * [lastReadyAtMs], which survives the socket and therefore cannot say whether *this* connection
     * ever worked — the question [wasWorthKeeping] has to answer.
     */
    @Volatile private var readyAtMsForCurrentSocket = 0L

    /** Whether the current socket ever carried an answer to an RPC. See [wasWorthKeeping]. */
    @Volatile private var currentSocketAnsweredAnRpc = false

    /**
     * What the current socket's Hermes said to `client.capabilities`: `pending`, `advertised`, or
     * `unsupported` (a Hermes that still asks through events). Diagnostics only — which protocol a
     * card uses is decided by what arrives, never by this.
     */
    @Volatile private var serverRequestsState = "pending"

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
        val OUTCOME_RPC_METHODS = setOf("session.create", "slash.exec")

        /** A quiet RPC this slow is worth a line even though it succeeded. */
        const val SLOW_RPC_MS = 1_000L

        /**
         * How long a ready socket must survive before reaching `Connected` counts as progress.
         *
         * Comfortably above the failure this exists for — HG-65's sockets lived 4.2–4.6s, handshake
         * and all, and answered nothing — and far below anything a working session looks like.
         */
        const val STABLE_CONNECTION_MS = 10_000L
    }

    /**
     * How many connections in a row have died without proving themselves ([wasWorthKeeping]).
     *
     * Exposed so a failed operation can say which of two different things happened to it: one
     * interrupted connection (HR-CONN-004, retry now) or a far end that keeps hanging up
     * (HR-CONN-007, retrying now lands on the next doomed socket). HG-65's user tapped 新建会话
     * nine times and got a spinner and a transport code each time, with nothing anywhere saying
     * the connection had died 197 times behind it.
     */
    val consecutiveDroppedConnections: Int get() = attempt.get()

    /** State names for the log; [ConnectionState.Error] carries a reason worth printing. */
    private fun describe(state: ConnectionState): String = when (state) {
        is ConnectionState.Error -> "Error(${state.reason})"
        else -> state::class.simpleName ?: "?"
    }

    /**
     * True when the client claims to be Connecting but nothing is actually dialling: no socket, and
     * the attempt is already older than the handshake watchdog that should have torn it down.
     *
     * A healthy connect also spends time here with no socket — gated mode fetches a WS ticket over
     * HTTP before it dials — which is why this asks how long, not just whether.
     */
    private fun connectingHasStalled(): Boolean {
        if (_state.value !is ConnectionState.Connecting || ws != null) return false
        val since = connectingSinceMs
        return since != 0L && System.currentTimeMillis() - since > handshakeTimeoutMs
    }

    /**
     * What must be true of this client whenever it is at rest, named so a failure can say which
     * rule broke. Empty means every rule holds.
     *
     * These exist because HG-19, HG-27 and HG-42 were the same bug three times: the client came to
     * rest somewhere it could not leave. Each was fixed by adding another guard, and after each fix
     * nothing in the codebase actually *said* what resting states are legal — so the next way in
     * was found by a user rather than by CI. Stating the rules is what lets a test look for a
     * violation it was not told to expect.
     */
    internal fun invariantViolations(): List<String> = synchronized(lifecycleLock) {
        // Under the lock, because the whole point is that the flag and the state move together.
        // Reading them as two volatiles can observe a torn pair that no interleaving ever actually
        // produced — which would make this report failures that are not real, and, worse, make a
        // reader distrust it when it reports one that is.
        val state = _state.value
        val violations = mutableListOf<String>()
        if (manuallyClosed && state !is ConnectionState.Disconnected && state !is ConnectionState.Error) {
            violations += "a client the app closed is $state, not Disconnected"
        }
        if (state is ConnectionState.Connected && ws == null) {
            violations += "Connected with no socket"
        }
        if (connectingHasStalled()) {
            violations += "Connecting with no attempt behind it for ${System.currentTimeMillis() - connectingSinceMs}ms"
        }
        return violations
    }


    /**
     * The one place that says a non-terminal state must not become permanent.
     *
     * The handshake watchdog covers a socket that opened and went mute; the backoff covers a socket
     * that died; `connect()` covers a stalled Connecting *if somebody calls it*. Between them they
     * covered every path anyone had thought of, which is exactly what was true before HG-42 as
     * well. This one asks the only question that generalises — "is this still moving?" — and needs
     * no theory about how it stopped.
     *
     * Driven by the state itself rather than a timer: collectLatest cancels the wait the moment
     * anything changes, so an idle or a healthy client schedules nothing at all.
     */
    private fun superviseLiveness() {
        scope.launch {
            _state.collectLatest { state ->
                if (state !is ConnectionState.Connecting && state !is ConnectionState.Reconnecting) return@collectLatest
                kotlinx.coroutines.delay(stallDeadlineMs)
                // Reached only because the state has not changed for the whole deadline — which is
                // longer than the handshake watchdog and longer than the longest backoff, so by now
                // something that should have moved has not.
                if (manuallyClosed) return@collectLatest
                val snapshot = connectionSnapshot()
                DebugLog.log("error", "connection stalled in $state — repairing: $snapshot")
                com.hermes.client.data.diagnostics.ConnectionIncidents.record("stalled-$state", snapshot)
                reconnectNow()
            }
        }
    }

    fun connect() {
        // Idempotent: multiple owners (the foreground service, view models, etc.) may all call
        // connect() on this shared singleton. If a socket is already open, or a connect/backoff
        // reconnect is already in flight, this must be a no-op — otherwise a second openSocket()
        // would leak a duplicate live WebSocket that the generation check only shadows, never
        // closes. reconnectNow() intentionally bypasses this guard to force a fresh socket.
        synchronized(lifecycleLock) {
            val cur = _state.value
            // One exception to that guard, and it is the whole of HG-42's user-visible half: a
            // Connecting that no longer has an attempt behind it. The app called connect() on
            // every session open for 13 minutes and was told "already Connecting" every time,
            // while the snapshot beside it read socket=none. An idempotence guard must not be
            // able to protect a corpse, so a stalled Connecting is treated as reconnectable —
            // and says so, with the snapshot, because that line is the evidence next time.
            val stalled = cur is ConnectionState.Connecting && connectingHasStalled()
            if (!stalled && (
                    cur is ConnectionState.Connecting || cur is ConnectionState.Connected ||
                        cur is ConnectionState.Reconnecting
                    )
            ) {
                DebugLog.log("ws", "connect() no-op — already $cur")
                return
            }
            if (stalled) {
                val snapshot = connectionSnapshot()
                DebugLog.log("ws", "connect() forcing a fresh socket — stalled: $snapshot")
                com.hermes.client.data.diagnostics.ConnectionIncidents.record("stalled-on-connect", snapshot)
            }
            manuallyClosed = false
        }
        // The seam HG-42 came through: the flag says "the app wants a connection", and the state
        // does not say so yet. A close() landing here used to leave the client Connecting forever.
        lifecycleSeam?.invoke("connect:flag-cleared")
        openSocket()
    }

    protected fun openSocket() {
        val gen = synchronized(lifecycleLock) {
            // The guard that was missing. openSocket() used to enter Connecting unconditionally,
            // so a close() that landed after its caller had checked manuallyClosed still left the
            // client in Connecting — and, because close() had already failed the old readiness
            // gate, with a brand-new gate that nothing would ever complete. Refusing here is the
            // only place that covers every caller: connect(), reconnectNow() and the backoff
            // reconnect all funnel through it.
            if (manuallyClosed) {
                DebugLog.log("ws", "opening socket refused: closed by the app")
                return
            }
            val next = generation.incrementAndGet()
            // Install a fresh, uncompleted readiness gate for this new socket attempt.
            readyGate = CompletableDeferred()
            // This socket has proved nothing yet; [wasWorthKeeping] starts from no.
            readyAtMsForCurrentSocket = 0L
            currentSocketAnsweredAnRpc = false
            serverRequestsState = "pending"
            connectingSinceMs = System.currentTimeMillis()
            _state.value = ConnectionState.Connecting
            next
        }
        val socketConnectionId = UUID.randomUUID().toString()
        connectionId = socketConnectionId
        DebugLog.log("ws", "opening socket (gen=$gen, conn=$socketConnectionId)")
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
            com.hermes.client.data.diagnostics.ConnectionIncidents.record(
                "handshake-timeout", "gen=$gen conn=$socketConnectionId elapsedMs=$handshakeTimeoutMs ${connectionSnapshot()}",
            )
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
                    header("X-Hermes-Connection-Id", socketConnectionId)
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
        val old = synchronized(lifecycleLock) {
            manuallyClosed = false
            accountAuthorizationClassificationPending = false
            attempt.set(0)
            ws
        }
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
            upgradedGeneration = gen
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation.get()) return // superseded socket — drop late frames
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                when (val msg = parseInbound(json, line)) {
                    is RpcResult -> {
                        val call = pending.remove(msg.id)
                        // The capability handshake is answered by every Hermes the moment a socket
                        // opens, so it proves nothing about the socket staying useful. Counting it
                        // would reset the backoff on exactly the sockets HG-65 was about.
                        if (call?.method != ServerRequests.CAPABILITIES_METHOD) currentSocketAnsweredAnRpc = true
                        // Settled here, on the reader, not in the coroutine awaiting it: the next
                        // frame may already be a resume answer that needs to know.
                        if (call?.method == ServerRequests.CAPABILITIES_METHOD) serverRequestsState = "advertised"
                        if (call?.method == ServerRequests.RESUME_METHOD) {
                            onResumeAnswered(webSocket, msg.result, resumeWindows.remove(msg.id).orEmpty())
                        }
                        call?.deferred?.complete(msg.result)
                    }
                    is RpcErrorReply -> {
                        val call = pending.remove(msg.id)
                        // An error reply is still an answer: the far end is listening.
                        if (call?.method != ServerRequests.CAPABILITIES_METHOD) currentSocketAnsweredAnRpc = true
                        DebugLog.log("ws", "rpc#${msg.id} ${call?.method ?: "?"} ← error " +
                            "${msg.error.code}: ${msg.error.message}")
                        call?.deferred
                            ?.completeExceptionally(GatewayRpcException(msg.error.code, msg.error.message))
                    }
                    is RpcEvent -> {
                        // Handle gateway.ready: flip to Connected and open the readiness gate.
                        if (msg.event.type == "gateway.ready") {
                            accountAuthorizationClassificationPending = false
                            // Deliberately NOT attempt.set(0). Reaching Connected is not evidence
                            // that this connection works — see [wasWorthKeeping].
                            lastReadyAtMs = System.currentTimeMillis()
                            readyAtMsForCurrentSocket = lastReadyAtMs
                            handshakeWatchdog?.cancel()
                            // Before the readiness gate opens, so it is the first frame Hermes
                            // reads on this socket: it handles frames in order, and a session this
                            // socket resumes before advertising would have its questions withdrawn.
                            advertiseServerRequests(webSocket, gen)
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
                    is RpcServerRequest -> onServerRequest(webSocket, msg)
                    is RpcUnreadable -> DebugLog.log("ws", "dropped unreadable frame: ${msg.reason}")
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
            if (gen != closedGen && !manuallyClosed) {
                val stage = when {
                    upgradedGeneration != gen -> "upgrade"
                    !readyGate.isCompleted -> "ready"
                    else -> "active"
                }
                com.hermes.client.data.diagnostics.ConnectionIncidents.record(
                    "ws-failure", "gen=$gen conn=$connectionId stage=$stage exception=${t.javaClass.simpleName} http=${status ?: "-"}",
                )
            }
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
            // The code, not just the reason. A far end that closes without one leaves `reason`
            // blank, and the log then said only `closed` — which cannot tell a normal 1000 from a
            // 1006, a 1013 the gateway sends when the Mac is offline, or a 4403 revocation. HG-65's
            // 197 identical `closed` lines are what that looks like when you need to know who hung
            // up and the log cannot say.
            onSocketClosed(gen, reason.ifBlank { "closed" }, closeCode = code)
        }
    }

    /**
     * Whether the socket that just died had earned a fresh start for the backoff.
     *
     * Reaching `Connected` used to be the whole test, and it is not evidence of anything: the
     * handshake is the far end accepting a socket, not the far end working. In HG-65 every single
     * round completed the handshake and then closed 4.2–4.6 seconds later without answering one
     * RPC — 197 times, every one of them resetting `attempt` to 0, so the wait stayed at 500ms, the
     * exponential backoff never engaged, and the phone reconnected twelve times a minute for
     * twenty-four minutes while getting nothing done.
     *
     * So: an answered RPC, or a ready socket that lived long enough to be worth calling a session.
     */
    private fun wasWorthKeeping(): Boolean {
        if (currentSocketAnsweredAnRpc) return true
        val readyAt = readyAtMsForCurrentSocket
        return readyAt > 0L && System.currentTimeMillis() - readyAt >= STABLE_CONNECTION_MS
    }

    protected open fun onSocketClosed(gen: Int, reason: String, retry: Boolean = true, closeCode: Int? = null) {
        // A newer socket has superseded this one (e.g. reconnectNow()) — ignore its death.
        if (gen != generation.get()) return
        // One death per socket. The handshake watchdog both cancels the socket and reports it
        // closed, so OkHttp's onFailure for that cancellation arrives second; without this guard
        // it would schedule a SECOND backoff reconnect and leave two live sockets, which the
        // generation check only shadows and never closes.
        if (gen == closedGen) return
        closedGen = gen
        handshakeWatchdog?.cancel()
        val worthKeeping = wasWorthKeeping()
        val readyAt = readyAtMsForCurrentSocket
        val livedMs = if (readyAt > 0L) System.currentTimeMillis() - readyAt else -1L
        DebugLog.log("ws") {
            buildString {
                append("socket closed (gen=$gen")
                closeCode?.let { append(", code=$it") }
                append("): $reason")
                if (livedMs >= 0) append(" · ready for ${livedMs}ms")
                if (!worthKeeping) append(" · answered nothing")
            }
        }
        if (closeCode != null && closeCode !in setOf(1000, 1001) && !manuallyClosed) {
            com.hermes.client.data.diagnostics.ConnectionIncidents.record(
                "ws-close", "gen=$gen conn=$connectionId code=$closeCode ready=${readyGate.isCompleted} answered=$currentSocketAnsweredAnRpc",
            )
        }
        // Fail any call() that is currently awaiting readiness so it throws immediately.
        readyGate.completeExceptionally(GatewayRpcException(0, reason))
        failAllPending(reason)
        connectingSinceMs = 0L
        if (manuallyClosed || !retry) {
            _state.value = ConnectionState.Disconnected
            return
        }
        _state.value = ConnectionState.Reconnecting
        // Only a connection that did something resets the wait. Without this, a far end that keeps
        // accepting and dropping sockets holds the client at the shortest possible backoff forever.
        if (worthKeeping) attempt.set(0)
        val attemptNo = attempt.getAndIncrement()
        val delayMs = backoff.delayFor(attemptNo)
        DebugLog.log("ws", "reconnect scheduled in ${delayMs}ms (gen=$gen, attempt=$attemptNo)")
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            // The reconnect that does not happen is the shape of an unexplained permanent stall:
            // before this line the log simply stopped, and nothing said whether the app had closed
            // the socket on purpose or a newer generation had taken over. Those look identical
            // from outside and only one of them is a bug.
            lifecycleSeam?.invoke("reconnect:before-guard")
            if (manuallyClosed || gen != generation.get()) {
                DebugLog.log("ws", "reconnect dropped (gen=$gen): " +
                    if (manuallyClosed) "closed by the app" else "superseded by gen=${generation.get()}")
                return@launch
            }
            openSocket()
        }
    }

    /**
     * Tell this socket's Hermes that this client answers server→client requests. Without it a
     * newer Hermes never sends approval or clarify here — it withdraws the approval and returns an
     * empty clarify answer, and the phone sees nothing at all. An older Hermes answers -32601,
     * which is expected and only logged: that one still asks through events.
     */
    private fun advertiseServerRequests(webSocket: WebSocket, gen: Int) {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        val call = PendingCall(ServerRequests.CAPABILITIES_METHOD, deferred)
        pending[id] = call
        val params = ServerRequests.capabilityParams()
        if (!webSocket.send(RpcRequest(id, ServerRequests.CAPABILITIES_METHOD, params).encode(json))) {
            pending.remove(id, call)
            DebugLog.log("ws", "client.capabilities not sent (gen=$gen): socket closing")
            return
        }
        scope.launch {
            try {
                val result = withTimeout(rpcTimeoutMs) { deferred.await() }
                val methods = ((result as? JsonObject)?.get("server_requests") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                DebugLog.log("ws", "server requests advertised (gen=$gen); Hermes may ask: ${methods.joinToString(",")}")
            } catch (e: GatewayRpcException) {
                if (gen == generation.get()) serverRequestsState = "unsupported"
                DebugLog.log("ws", "client.capabilities unsupported (gen=$gen, code=${e.code}); questions arrive as events")
            } catch (e: TimeoutCancellationException) {
                DebugLog.log("ws", "client.capabilities unanswered (gen=$gen)")
            } finally {
                pending.remove(id, call)
            }
        }
    }

    /**
     * Hermes asked this client something. Approval and clarify become the same events the older
     * protocol sent, so every card, notification and persisted phase keeps one shape; anything else
     * is refused at once with -32601 — upstream reads that as "no handler" and moves on, instead of
     * waiting out the request's deadline (300s for a prompt) with nobody to answer.
     */
    private fun onServerRequest(webSocket: WebSocket, request: RpcServerRequest) {
        val event = ServerRequests.toEvent(request.id, request.method, request.params)
        if (event == null) {
            DebugLog.log("ws", "server request ${request.method} id=${request.id.content}: no handler, answered -32601")
            webSocket.send(
                encodeServerError(
                    json, request.id, ServerRequests.METHOD_NOT_FOUND,
                    "Hermes Remote has no handler for ${request.method}",
                ),
            )
            return
        }
        DebugLog.log("ws", "server request ${request.method} id=${request.id.content} session=${event.sessionId ?: "-"}")
        request.id.contentOrNull?.let { id -> resumeWindows.values.forEach { it += id } }
        if (eventQueue.trySend(event).isFailure) {
            DebugLog.log("ws", "event queue overflow; reconnecting for history resync")
            webSocket.close(1013, "event queue overflow")
        }
    }

    /**
     * A `session.resume` answer, handled on the reader thread so its place in the frame order is kept.
     *
     * Newer Hermes does not replay a question asked while no socket of ours was attached; it lists it
     * in `open_requests` and expects the client to re-deliver it as if it had just arrived. The same
     * list is also the only way to learn that a card went stale *without* a `request.cancel`: upstream
     * sends none when another surface answers first, and a card restored from disk may name a request
     * that timed out while no socket was attached. So on a connection that advertised server requests,
     * the answer is followed by an [ServerRequests.OPEN_SNAPSHOT_EVENT] naming every id still open, and
     * server-request cards not in it are dropped. Upstream omits the field when nothing is open
     * (`_live_session_payload` only sets non-empty values; a cold resume mints a fresh handle that owns
     * no requests), so absent means empty. Queued behind every frame that arrived before this answer
     * and ahead of every frame after it, which is why it is not done in the coroutine awaiting the call.
     */
    private fun onResumeAnswered(webSocket: WebSocket, result: JsonElement, arrivedDuringResume: Set<String>) {
        if (serverRequestsState != "advertised") return
        val obj = result as? JsonObject ?: return
        val events = ServerRequests.openRequestEvents(obj) +
            listOfNotNull(ServerRequests.openSnapshotEvent(obj, alsoOpen = arrivedDuringResume))
        events.forEach { event ->
            if (event.type != ServerRequests.OPEN_SNAPSHOT_EVENT) {
                DebugLog.log("ws", "re-delivering open ${event.type} session=${event.sessionId ?: "-"}")
            }
            if (eventQueue.trySend(event).isFailure) {
                DebugLog.log("ws", "event queue overflow; reconnecting for history resync")
                webSocket.close(1013, "event queue overflow")
                return
            }
        }
    }

    private suspend fun awaitReadiness(what: String) {
        // Bounded wait: if the server never sends gateway.ready, throw after READY_TIMEOUT_MS.
        try {
            withTimeout(READY_TIMEOUT_MS) { readyGate.await() }
        } catch (e: TimeoutCancellationException) {
            // Every feature reporting its own readiness timeout, 15s apart, with nothing naming
            // the socket, is exactly what HG-27 looked like from the outside.
            DebugLog.log("error", "rpc $what blocked: no gateway.ready in ${READY_TIMEOUT_MS}ms")
            com.hermes.client.data.diagnostics.ConnectionIncidents.record(
                "readiness-timeout", "method=$what ${connectionSnapshot()}",
            )
            throw GatewayReadinessTimeoutException("gateway readiness timeout")
        }
    }

    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.deferred?.completeExceptionally(GatewayRpcException(0, reason))
        }
    }

    suspend fun call(method: String, params: JsonObject): JsonElement {
        // Wait until gateway.ready has been received before sending any RPC.
        // The await() happens BEFORE registering in `pending`, so a timeout here never leaks
        // a pending entry.
        awaitReadiness(method)
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        val call = PendingCall(method, deferred)
        // Opened before the request leaves, so no server request can slip in unrecorded.
        if (method == ServerRequests.RESUME_METHOD) resumeWindows[id] = ConcurrentHashMap.newKeySet()
        pending[id] = call
        // A polling method stays quiet while it is quick and successful — the rule the inbox poll
        // already follows (DESIGN.md §5.15). process.list runs every 5s per active run and was 55
        // of the 500 buffered entries in the HG-27 report. Slow and failing calls still speak.
        val quiet = method in QUIET_RPC_METHODS
        val startedAt = System.currentTimeMillis()
        val sentGeneration = generation.get()
        val sentConnectionId = connectionId
        if (!quiet) DebugLog.log("ws") { "rpc#$id conn=$sentConnectionId → $method" }
        val sent = ws?.send(RpcRequest(id, method, params).encode(json)) ?: false
        if (!sent) {
            pending.remove(id)
            resumeWindows.remove(id)
            DebugLog.log("ws", "rpc#$id conn=$sentConnectionId $method failed: not connected")
            throw GatewayRpcException(0, "not connected")
        }
        return try {
            // These two foreground actions have an unknown outcome when their reply is lost.
            // Bound the spinner, but never auto-replay a possibly successful create or switch.
            val deadlineMs = if (method == "session.create" || method == "slash.exec") {
                min(rpcTimeoutMs, 20_000L)
            } else rpcTimeoutMs
            val result = withTimeout(deadlineMs) { deferred.await() }
            val elapsed = System.currentTimeMillis() - startedAt
            // A quiet method speaks when it was slow; a session-shaping method always speaks. HG-29
            // was a session that went missing two minutes after session.create, and the log could
            // not say whether the create had ever succeeded: the request line was there and nothing
            // followed it either way.
            if (method in OUTCOME_RPC_METHODS || (quiet && elapsed >= SLOW_RPC_MS)) {
                DebugLog.log("ws", "rpc#$id conn=$sentConnectionId $method ← ok (${elapsed}ms)")
            }
            result
        } catch (e: TimeoutCancellationException) {
            // Previously this threw with no line at all: an opening line and no outcome reads
            // exactly like a request that never returned.
            DebugLog.log("error", "rpc#$id conn=$sentConnectionId $method timed out; ${connectionSnapshot()}")
            com.hermes.client.data.diagnostics.ConnectionIncidents.record(
                "rpc-timeout", "rpcId=$id method=$method conn=$sentConnectionId elapsedMs=${System.currentTimeMillis() - startedAt} ${connectionSnapshot()}",
            )
            // A ready WebSocket can stay open while its RPC path has gone silent. Replacing the
            // generation heals the *next* action; retrying this action would risk duplicating it.
            if (sentGeneration == generation.get() && !manuallyClosed &&
                recoveredTimeoutGeneration.getAndSet(sentGeneration) != sentGeneration
            ) reconnectNow()
            throw GatewayResponseTimeoutException("gateway response timeout")
        } finally {
            pending.remove(id, call)
            resumeWindows.remove(id)
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
        // Held across the whole body, not just the flag: a connect() that slipped in between
        // "manuallyClosed = true" and "ws = null" would have its brand-new socket closed out from
        // under it by the lines below. Reading the flag and moving the state have to be one step
        // on both sides or neither side is safe (HG-42).
        synchronized(lifecycleLock) {
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
    }

    /** Immediately cancel the underlying socket (no graceful close handshake). */
    internal fun cancelNow() {
        DebugLog.log("ws", "cancelNow() (manuallyClosed $manuallyClosed → true)")
        synchronized(lifecycleLock) {
            manuallyClosed = true
            accountAuthorizationClassificationPending = false
            connectingSinceMs = 0L
            readyGate.completeExceptionally(GatewayRpcException(0, "client cancelled"))
            ws?.cancel()
            ws = null
            _state.value = ConnectionState.Disconnected
        }
    }
}
