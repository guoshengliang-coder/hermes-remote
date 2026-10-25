package com.hermes.client.data.network

import com.hermes.client.data.auth.AccountConnection
import com.hermes.client.data.auth.AccountDeviceRouteMode

/** Local tests exercise the Bearer path too; no production legacy authentication seam. */
fun testAccountConnection(baseUrl: String, token: String = "test-bearer") = AccountConnection(
    baseUrl = baseUrl, bearer = token, deviceId = "test-mac", deviceRouteMode = AccountDeviceRouteMode.SINGLE_BINDING,
)
