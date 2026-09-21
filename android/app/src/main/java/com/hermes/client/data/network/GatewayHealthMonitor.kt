package com.hermes.client.data.network

import android.content.Context
import android.net.ConnectivityManager
import com.hermes.client.data.diagnostics.DebugLog
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

/** Abstraction over Android connectivity so the monitor is unit-testable. */
interface ConnectivityChecker {
    /** True when the device has a validated, internet-capable network. */
    fun isOnline(): Boolean
}

class AndroidConnectivityChecker(private val context: Context) : ConnectivityChecker {
    // Only transitions are logged. A negative reading that persists says the same thing every
    // 30 seconds, and the interesting moments are when it starts and when it stops.
    @Volatile private var lastNegativeNote: String? = null

    override fun isOnline(): Boolean {
        // If we can't read connectivity, assume online rather than false-flag DeviceOffline —
        // the /api/status probe is then the source of truth.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return online()
        val net = cm.activeNetwork ?: return offline("no active network")
        val caps = cm.getNetworkCapabilities(net) ?: return offline("no capabilities for active network")
        // Deliberately not requiring NET_CAPABILITY_VALIDATED. That flag reports whether
        // Android's own captive-portal probe reached its validation endpoint, which is a different
        // question from whether this network carries traffic, and it goes missing for whole classes
        // of otherwise working connections — a VPN in the path, a dual-SIM handover, or simply a
        // validation endpoint that is unreachable from where the device sits. The captive-portal
        // case VALIDATED was guarding is now caught where it actually shows up: the /api/status
        // probe below, which a portal fails.
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return offline("no INTERNET capability · ${describe(caps)}")
        }
        return online()
    }

    private fun online(): Boolean {
        lastNegativeNote?.let {
            lastNegativeNote = null
            DebugLog.log("net", "connectivity check recovered (was: $it)")
        }
        return true
    }

    /**
     * Records why the device was judged offline. Without this the only evidence is a user
     * screenshot of the offline strip, which cannot say which capability was missing — and
     * VALIDATED in particular reflects whether Android's own captive-portal probe succeeded,
     * not whether the network carries traffic.
     */
    private fun offline(reason: String): Boolean {
        if (lastNegativeNote != reason) {
            lastNegativeNote = reason
            DebugLog.log("net", "connectivity check says offline · $reason")
        }
        return false
    }

    private fun describe(caps: NetworkCapabilities): String {
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        }.ifEmpty { listOf("other") }
        val flags = buildList {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("INTERNET")
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("VALIDATED")
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("NOT_METERED")
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) add("NOT_VPN")
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) add("CAPTIVE_PORTAL")
        }.ifEmpty { listOf("none") }
        return "transports=${transports.joinToString("+")} caps=${flags.joinToString("+")}"
    }
}

/**
 * Proactively tracks whether the self-hosted gateway is reachable, exposed as [health].
 * Probes device connectivity first (→ [GatewayHealth.DeviceOffline]) then the public
 * `/api/status` endpoint, with one immediate retry as debounce so a transient blip does not
 * flash the down-strip. Probing runs only while the app is foregrounded (see [startForeground]).
 */
class GatewayHealthMonitor(
    private val api: HermesRestApi,
    private val connectivity: ConnectivityChecker,
    private val connectionState: StateFlow<ConnectionState>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _health = MutableStateFlow<GatewayHealth>(GatewayHealth.Unknown)
    val health: StateFlow<GatewayHealth> = _health.asStateFlow()

    private val _contract = MutableStateFlow<HermesContractNotice?>(null)

    /**
     * The Mac's Hermes against the app's REST contract, as the Connector reported it; null when
     * compatible, unknown, or not reported at all (an older Connector). See [refreshContract].
     */
    val contract: StateFlow<HermesContractNotice?> = _contract.asStateFlow()
    private val contractGuard = Mutex()
    private var contractTarget: String? = null
    private var contractVersion: String? = null
    private var contractFetchedAt: Long? = null

    /** Set by [recheck]; the next healthy probe re-reads the report whatever the cache says. */
    @Volatile private var contractRecheckRequested = false

    private val probeGuard = Mutex()
    private var periodicJob: Job? = null

    init {
        // A dropped/errored socket is an early hint the backend may be gone — re-probe promptly.
        // probe()'s tryLock coalesces this with any in-flight probe.
        //
        // The socket coming BACK is the same kind of hint, and it used to be ignored. HG-42: the
        // probe that ran while the network was stalling wrote GatewayUnreachable, the socket then
        // reconnected a second later and every REST call went back to 200 — and the red
        // 「Relay 暂时无法连接」 strip stayed up regardless, because nothing asked again until the
        // 30-second loop came round. The strip is meant to describe the backend, not the last bad
        // moment the app remembers. Only re-probe when something is actually being claimed: on a
        // healthy client this collector then costs nothing.
        scope.launch {
            connectionState.collect { st ->
                val recovered = st is ConnectionState.Connected && _health.value.isUnhealthy()
                if (st is ConnectionState.Error || st is ConnectionState.Disconnected || recovered) probe()
            }
        }
    }

    /** Run one health probe, coalescing with any probe already in flight. */
    suspend fun probe() {
        if (!probeGuard.tryLock()) return
        val healthy: GatewayHealth.Healthy?
        try {
            val next = evaluate()
            // Only transitions: the probe runs every 30s in the foreground and the answer is
            // usually the same one as last time.
            //
            // Comparing the values themselves never suppressed a single line, because Healthy
            // carries latencyMs and that differs on every probe. 18 of the 500 entries in the
            // HG-27 report were "healthy(201ms) → healthy(236ms)" — a state change that was not
            // one. Compare the tier; keep the latency in the line that does get written, where
            // it is still worth reading.
            if (tier(next) != tier(_health.value)) {
                DebugLog.log("health", "${describe(_health.value)} → ${describe(next)}")
            }
            _health.value = next
            healthy = next as? GatewayHealth.Healthy
        } finally {
            probeGuard.unlock()
        }
        // Outside the probe lock: a slow report must never make the monitor drop the probes a
        // dropped or restored socket asks for.
        refreshContract(healthy?.version, healthy != null)
    }

    /**
     * Ask the Connector for its contract report — only after a healthy probe, and only when the
     * target Mac changed, the Hermes version moved, the user asked ([recheck]) or
     * [CONTRACT_REFRESH_MS] passed. The Connector caches the check itself, so this is one small
     * tunnelled GET, not a schema download.
     *
     * The verdict belongs to one Mac: it is keyed on [HermesRestApi.contractTargetKey] and cleared
     * the moment that changes (another device, account, gateway, or signing out), so one Mac's red
     * strip is never shown for another.
     *
     * 401/404/405 mean "no report here" — an older Connector forwards the path to Hermes — and so
     * does a body this build cannot read: the notice clears. Anything else (a 5xx such as the
     * Relay's `device_offline`, a timeout, a dropped connection) says nothing about Hermes: the
     * last verdict stays and the next probe asks again.
     */
    private suspend fun refreshContract(version: String?, healthy: Boolean) {
        if (!contractGuard.tryLock()) return
        try {
            val target = currentContractTarget()
            if (target != contractTarget) {
                contractTarget = target
                contractVersion = null
                contractFetchedAt = null
                publishContract(null)
            }
            if (target == null || !healthy) return
            val fetchedAt = contractFetchedAt
            val now = clock()
            val forced = contractRecheckRequested
            if (!forced && fetchedAt != null && version == contractVersion && now - fetchedAt < CONTRACT_REFRESH_MS) {
                return
            }
            contractRecheckRequested = false
            val next = try {
                api.hermesContract().toNotice()
            } catch (e: HermesApiException) {
                if (e.code in NO_REPORT_STATUSES) null else return retryContractNextProbe()
            } catch (e: kotlinx.serialization.SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return retryContractNextProbe()
            }
            // The user may have switched Macs while the report was in flight.
            if (currentContractTarget() != target) return
            contractVersion = version
            contractFetchedAt = now
            publishContract(next)
        } finally {
            contractGuard.unlock()
        }
    }

    private fun retryContractNextProbe() {
        contractFetchedAt = null
    }

    private suspend fun currentContractTarget(): String? = try {
        api.contractTargetKey()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun publishContract(next: HermesContractNotice?) {
        val previous = _contract.value
        if (previous?.error?.code != next?.error?.code || previous?.features != next?.features) {
            DebugLog.log(
                "health",
                "hermes contract ${previous?.error?.code?.value ?: "ok"} → ${next?.error?.code?.value ?: "ok"}" +
                    (next?.features?.takeIf { it.isNotEmpty() }?.joinToString(",", prefix = " features=") ?: ""),
            )
        }
        _contract.value = next
    }

    /**
     * What counts as a change worth a line: the kind of health, not the millisecond it measured.
     * [GatewayHealth.Healthy.latencyMs] moves on every probe and would make every probe a
     * "transition".
     */
    private fun tier(health: GatewayHealth): String = when (health) {
        is GatewayHealth.Healthy -> "healthy(running=${health.running})"
        is GatewayHealth.GatewayUnreachable -> "unreachable(${health.detail})"
        GatewayHealth.DeviceOffline -> "device-offline"
        GatewayHealth.Unknown -> "unknown"
    }

    private fun describe(health: GatewayHealth): String = when (health) {
        is GatewayHealth.Healthy -> "healthy(${health.latencyMs}ms, running=${health.running})"
        is GatewayHealth.GatewayUnreachable -> "unreachable(${health.detail})"
        GatewayHealth.DeviceOffline -> "device-offline"
        GatewayHealth.Unknown -> "unknown"
    }

    /**
     * The probe is the source of truth; the connectivity read is a hint about how to describe a
     * failure, not a reason to skip asking.
     *
     * Returning DeviceOffline on the capability read alone meant one unlucky sample — the flag can
     * drop for a moment during a network handover, and stay dropped on networks whose validation
     * never completes — put a "your device has no network" strip in front of a user whose traffic
     * was flowing the whole time. It also skipped the one retry the comment below promises, so the
     * debounce protected the gateway probe and nothing else.
     *
     * This costs almost nothing when the device really is offline: the probe then fails on DNS or
     * connect within milliseconds rather than running out the 5s timeout, which only bites when
     * there is a network and the server is slow — a case that should never read as "device
     * offline" anyway.
     */
    private suspend fun evaluate(): GatewayHealth {
        val connectivitySaysOffline = !connectivity.isOnline()
        // First attempt; on a retryable failure (null) try once more before declaring it down.
        val status = attemptStatus() ?: attemptStatus()
        return status
            ?: if (connectivitySaysOffline) GatewayHealth.DeviceOffline
            else GatewayHealth.GatewayUnreachable("unreachable")
    }

    /** Terminal state on a definitive answer (healthy / unauthorized), or null for a retryable failure. */
    private suspend fun attemptStatus(): GatewayHealth? {
        val start = System.nanoTime()
        return try {
            val dto = withTimeout(PROBE_TIMEOUT_MS) { api.gatewayStatus() }
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            GatewayHealth.Healthy(version = dto.version, running = dto.gatewayRunning, latencyMs = latencyMs)
        } catch (e: HermesApiException) {
            when (e.code) {
                401 -> GatewayHealth.GatewayUnreachable("unauthorized") // definitive
                0 -> GatewayHealth.Unknown // no gateway configured yet — not a down state
                else -> null // retryable
            }
        } catch (e: TimeoutCancellationException) {
            null // probe timed out — retryable
        } catch (e: CancellationException) {
            throw e // genuine cancellation (e.g. stopForeground) — never swallow
        } catch (e: Exception) {
            null // IO / other — retryable
        }
    }

    /** Fire an immediate probe (the sheet's Re-check button). */
    fun recheck() {
        // The user is asking *now* — typically right after `hermes update`, which can leave the
        // version string unchanged — so the cached verdict must not answer for the Mac.
        contractRecheckRequested = true
        scope.launch { probe() }
    }

    /** Begin foreground probing: probe now, then every [PROBE_INTERVAL_MS]. Idempotent. */
    fun startForeground() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (true) {
                probe()
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    /** Stop foreground probing (app backgrounded). */
    fun stopForeground() {
        periodicJob?.cancel()
        periodicJob = null
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
        const val PROBE_INTERVAL_MS = 30_000L

        /** How long a contract report is trusted while the Hermes version stays the same. */
        const val CONTRACT_REFRESH_MS = 5 * 60_000L

        /** Answers that mean "this Connector has no report", as opposed to "could not ask". */
        private val NO_REPORT_STATUSES = setOf(401, 404, 405)
    }
}
