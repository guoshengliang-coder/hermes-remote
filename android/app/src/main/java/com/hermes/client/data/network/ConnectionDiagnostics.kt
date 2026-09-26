package com.hermes.client.data.network

import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.auth.AccountTransportMode
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.DEFAULT_REMOTE_GATEWAY_URL
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.di.UpdateHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/** Conservative verdict after bounded connection attempts. It never attributes blame to a device. */
enum class ConnectionDiagnosis(val code: String) {
    UNKNOWN("HR-CONN-002"),
    ADDRESS_NOT_FOUND("HR-CONN-008"),
    CONNECTION_TIMEOUT("HR-CONN-009"),
    SERVICE_UNAVAILABLE("HR-CONN-010"),
    FLAPPING("HR-CONN-011"),
}

/**
 * Tests only the configured official origin's public paths. The update client has no Gateway
 * cookies, authenticator or bearer token; this class never receives a credential value.
 */
class ConnectionDiagnostics @Inject constructor(
    @param:UpdateHttpClient private val publicClient: OkHttpClient,
    private val credentials: CredentialStore,
    private val accountSessions: AccountSessionManager,
) {
    fun configuredBaseUrl(): String? = when (accountSessions.transportMode()) {
        AccountTransportMode.ACCOUNT -> accountSessions.session.value?.baseUrl
        else -> runCatching { credentials.load()?.baseUrl }.getOrNull()
    }

    suspend fun diagnose(
        causes: List<String?>,
        baseUrl: String? = configuredBaseUrl(),
        officialOrigin: String = DEFAULT_REMOTE_GATEWAY_URL,
    ): ConnectionDiagnosis {
        val canonical = isSameOrigin(baseUrl, officialOrigin)
        val rounds = if (canonical) {
            (1..2).map {
                mapOf(
                    "/health" to check(officialOrigin, "/health"),
                    "/relay-health" to check(officialOrigin, "/relay-health"),
                )
            }
        } else emptyList()
        val health = rounds.mapNotNull { it["/health"] }
        val relay = rounds.mapNotNull { it["/relay-health"] }
        val allChecks = health + relay
        val contradictory = listOf(health, relay).any { values -> values.distinct().size > 1 }
        val anyPublicSuccess = allChecks.any { it == Check.OK }
        val allPublicSuccess = allChecks.isNotEmpty() && allChecks.all { it == Check.OK }

        val verdict = when {
            contradictory -> ConnectionDiagnosis.FLAPPING
            anyPublicSuccess && causes.any { it == "UnknownHostException" } -> ConnectionDiagnosis.FLAPPING
            health.size == 2 && health.all { it == Check.OK } &&
                (relay.all { it == Check.HTTP_FAILURE } || allPublicSuccess) ->
                ConnectionDiagnosis.SERVICE_UNAVAILABLE
            anyPublicSuccess -> ConnectionDiagnosis.UNKNOWN
            causes.isNotEmpty() && causes.all { it == "UnknownHostException" } &&
                (!canonical || allChecks.all { it == Check.DNS_FAILURE }) ->
                ConnectionDiagnosis.ADDRESS_NOT_FOUND
            causes.isNotEmpty() && causes.all { it in CONNECT_FAILURES } &&
                (!canonical || allChecks.none { it == Check.DNS_FAILURE }) ->
                ConnectionDiagnosis.CONNECTION_TIMEOUT
            else -> ConnectionDiagnosis.UNKNOWN
        }
        // Only endpoint names and error classes are logged. Never include URL, token or response body.
        DebugLog.log(
            "startup",
            "diagnosis ${verdict.code} status=${causes.joinToString(",")} " +
                "health=${health.joinToString(",")} relay=${relay.joinToString(",")}",
        )
        return verdict
    }

    private suspend fun check(origin: String, path: String): Check {
        val request = Request.Builder().url("${origin.trimEnd('/')}$path")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = publicClient.newCall(request)
            call.timeout().timeout(PUBLIC_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(
                        if (e is java.net.UnknownHostException) Check.DNS_FAILURE else Check.IO_FAILURE,
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (continuation.isActive) continuation.resume(
                            if (it.isSuccessful) Check.OK else Check.HTTP_FAILURE,
                        )
                    }
                }
            })
        }
    }

    private fun isSameOrigin(baseUrl: String?, officialOrigin: String): Boolean = runCatching {
        val actual = URI(baseUrl ?: return false)
        val official = URI(officialOrigin)
        actual.scheme.equals(official.scheme, ignoreCase = true) &&
            actual.host.equals(official.host, ignoreCase = true) &&
            (if (actual.port == -1) 443 else actual.port) ==
                (if (official.port == -1) 443 else official.port) &&
            (actual.path.isNullOrEmpty() || actual.path == "/")
    }.getOrDefault(false)

    private enum class Check { OK, HTTP_FAILURE, DNS_FAILURE, IO_FAILURE }

    private companion object {
        const val PUBLIC_CHECK_TIMEOUT_SECONDS = 2L
        val CONNECT_FAILURES = setOf(
            "SocketTimeoutException", "ConnectException", "NoRouteToHostException",
            "InterruptedIOException", "UnknownServiceException",
        )
    }
}
