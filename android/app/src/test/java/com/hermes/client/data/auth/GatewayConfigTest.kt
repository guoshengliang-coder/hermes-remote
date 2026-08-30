package com.hermes.client.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GatewayConfigTest {
    @Test fun publicGateway_requiresHttps() {
        assertRejected("http://example.com:8444", "HTTPS")
    }

    @Test fun privateAndLoopbackHttp_remainAvailableForLocalDevelopment() {
        assertEquals("http://127.0.0.1:9119", normalizeGatewayBaseUrl(" http://127.0.0.1:9119/ "))
        assertEquals("http://192.168.1.20:9119", normalizeGatewayBaseUrl("http://192.168.1.20:9119"))
        assertEquals("http://100.119.73.80:9119", normalizeGatewayBaseUrl("http://100.119.73.80:9119"))
        assertEquals("https://example.com", normalizeGatewayBaseUrl("HTTPS://example.com/"))
    }

    @Test fun urlCredentialsAndQueries_areRejected() {
        assertRejected("https://user:pass@example.com", "credentials")
        assertRejected("https://example.com?token=secret", "query")
    }

    private fun assertRejected(value: String, expected: String) {
        try {
            normalizeGatewayBaseUrl(value)
            fail("Expected URL to be rejected")
        } catch (error: IllegalArgumentException) {
            check(error.message.orEmpty().contains(expected, ignoreCase = true))
        }
    }
}
