package com.hermes.client.data.network

import okhttp3.Dns
import java.net.InetAddress

/**
 * Keeps existing installations that still use the legacy sslip.io relay reachable on mobile
 * networks where sslip.io is blocked or resolved unreliably. New installations use mrlgs.net and
 * Android's normal DNS resolver. OkHttp still verifies HTTPS certificates and SNI by hostname.
 */
class RelayDns(
    private val systemDns: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname.equals(LEGACY_RELAY_HOST, ignoreCase = true)) {
            listOf(InetAddress.getByAddress(hostname, PRODUCTION_RELAY_ADDRESS))
        } else {
            systemDns.lookup(hostname)
        }

    private companion object {
        const val LEGACY_RELAY_HOST = "47.239.30.253.sslip.io"
        val PRODUCTION_RELAY_ADDRESS = byteArrayOf(47, 239.toByte(), 30, 253.toByte())
    }
}
