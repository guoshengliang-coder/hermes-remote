package com.hermes.client.data.network

import okhttp3.Dns
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class RelayDnsTest {
    @Test
    fun productionRelay_bypassesSystemDns() {
        val system = RecordingDns()

        val result = RelayDns(system).lookup("47.239.30.253.sslip.io")

        assertEquals(0, system.calls)
        assertArrayEquals(byteArrayOf(47, 239.toByte(), 30, 253.toByte()), result.single().address)
    }

    @Test
    fun productionRelay_matchIsCaseInsensitive() {
        val system = RecordingDns()

        RelayDns(system).lookup("47.239.30.253.SSLIP.IO")

        assertEquals(0, system.calls)
    }

    @Test
    fun otherHosts_useAndroidDns() {
        val expected = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 8))
        val system = RecordingDns(listOf(expected))

        val result = RelayDns(system).lookup("example.com")

        assertEquals(1, system.calls)
        assertEquals(listOf(expected), result)
    }

    private class RecordingDns(
        private val result: List<InetAddress> = emptyList(),
    ) : Dns {
        var calls: Int = 0

        override fun lookup(hostname: String): List<InetAddress> {
            calls += 1
            return result
        }
    }
}
