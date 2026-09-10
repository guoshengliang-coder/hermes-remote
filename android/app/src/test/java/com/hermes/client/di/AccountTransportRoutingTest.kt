package com.hermes.client.di

import com.hermes.client.data.auth.AccountConnection
import com.hermes.client.data.auth.AccountDeviceRouteMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountTransportRoutingTest {
    @Test fun singularBindingUsesCompatibilityWebSocketPath() {
        val endpoint = accountWebSocketEndpoint(
            AccountConnection(
                baseUrl = "https://relay.example/",
                bearer = "hga_secret",
                deviceId = "mac-1",
                deviceRouteMode = AccountDeviceRouteMode.SINGLE_BINDING,
            ),
        )

        assertEquals("https://relay.example/api/ws", endpoint.url)
        assertEquals("hga_secret", endpoint.bearerToken)
        assertEquals("mac-1", endpoint.accountDeviceId)
    }

    @Test fun multiDeviceModeKeepsOpaqueDeviceWebSocketPath() {
        val endpoint = accountWebSocketEndpoint(
            AccountConnection(
                baseUrl = "https://relay.example",
                bearer = "hga_secret",
                deviceId = "office/mac 1",
                deviceRouteMode = AccountDeviceRouteMode.EXPLICIT_DEVICE,
            ),
        )

        assertEquals("https://relay.example/v2/devices/office%2Fmac%201/ws", endpoint.url)
    }
}
