package com.hermes.client.data.auth

import java.net.URI

const val DEFAULT_REMOTE_GATEWAY_URL = "https://mrlgs.net:8444"

data class GatewayConfig(
    val baseUrl: String,
    val token: String = "",
    // Set for a network-exposed (gated) dashboard that requires a password provider. When a
    // username is present the app authenticates via POST /auth/password-login (session cookies)
    // plus a per-socket WS ticket, instead of the loopback session token.
    val username: String = "",
    val password: String = "",
) {
    /** True when this targets a gated dashboard (basic-auth); false for a loopback/token setup. */
    val isGated: Boolean get() = username.isNotBlank()

    /** Base WS endpoint with no auth query. Authentication is carried in a header or short-lived ticket. */
    val wsBase: String
        get() {
            val ws = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            return "${ws.trimEnd('/')}/api/ws"
        }

}

/**
 * Validate and normalize a user-controlled gateway URL before it is persisted or contacted.
 * Public gateways must use TLS. Plain HTTP remains available for literal loopback/private
 * addresses so local Mac development continues to work without weakening remote connections.
 */
fun normalizeGatewayBaseUrl(raw: String): String = runCatching {
    val trimmed = raw.trim().trimEnd('/')
    val uri = URI(trimmed)
    val scheme = uri.scheme?.lowercase()
    require(scheme == "https" || scheme == "http") { "Only HTTP(S) gateway URLs are supported" }
    require(!uri.host.isNullOrBlank()) { "Gateway URL must include a host" }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "Gateway URL cannot include credentials, query parameters, or fragments"
    }
    require(scheme == "https" || isLocalAddress(uri.host)) {
        "Remote gateways must use HTTPS"
    }
    scheme + trimmed.substring(uri.scheme.length)
}.getOrElse { throw IllegalArgumentException(it.message ?: "Invalid gateway URL") }

private fun isLocalAddress(host: String): Boolean {
    val normalized = host.removePrefix("[").removeSuffix("]").lowercase()
    if (normalized == "localhost" || normalized == "::1") return true
    val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 127 || octets[0] == 10 ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 100 && octets[1] in 64..127) // CGNAT, including Tailscale addresses
}

interface CredentialStore {
    fun load(): GatewayConfig?
    fun save(config: GatewayConfig)
    fun clear()
}
