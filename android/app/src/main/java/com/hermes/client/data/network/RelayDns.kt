package com.hermes.client.data.network

import okhttp3.Dns
import java.net.InetAddress

/**
 * Keeps the production relay reachable on mobile networks where sslip.io is blocked or resolved
 * unreliably. OkHttp still uses the hostname for HTTPS certificate verification and SNI; only the
 * DNS result is pinned. Every other hostname continues through Android's normal DNS resolver.
 */
class RelayDns(
    private val systemDns: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname.equals(PRODUCTION_RELAY_HOST, ignoreCase = true)) {
            listOf(InetAddress.getByAddress(hostname, PRODUCTION_RELAY_ADDRESS))
        } else {
            systemDns.lookup(hostname)
        }

    private companion object {
        const val PRODUCTION_RELAY_HOST = "47.239.30.253.sslip.io"
        val PRODUCTION_RELAY_ADDRESS = byteArrayOf(47, 239.toByte(), 30, 253.toByte())
    }
}
