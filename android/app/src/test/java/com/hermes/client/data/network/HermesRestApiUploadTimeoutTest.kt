package com.hermes.client.data.network

import com.hermes.client.data.network.testAccountConnection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * HG-208: an attachment upload on 0.1.170 died of `SocketTimeoutException: timeout` at 10040ms
 * although the upload client declares connect=30s / write=120s / call=10min and the shared client
 * sets readTimeout(0). None of the stated budgets is ten seconds, yet the wire said ten seconds.
 *
 * These tests drive [HermesRestApi.uploadArtifact] through a base client shaped exactly like the
 * production one in AppModule — `readTimeout(0)`, everything else OkHttp's default — because the
 * per-call budgets are derived from it and a default that survives the derivation is precisely
 * what HG-208 is about. The shared `testHttpClient()` cannot be used here: its explicit 30s
 * read/write/connect would mask whatever default the derivation fails to override.
 */
class HermesRestApiUploadTimeoutTest {
    @get:Rule val serverRule = MockWebServerRule()

    /** The AppModule shape: WebSocket-friendly infinite read, OkHttp defaults everywhere else. */
    private fun productionShapedClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private fun api(server: MockWebServer) = HermesRestApi(
        productionShapedClient(),
        Json { ignoreUnknownKeys = true },
    ) { testAccountConnection(server.url("/").toString().trimEnd('/'), "secret") }

    /**
     * The gateway accepted the body but the answer (over the VPN, through the connector tunnel)
     * took longer than ten seconds to arrive. The upload must wait for it within the declared
     * call budget — not die at OkHttp's silent 10s default.
     */
    @Test fun upload_outlives_a_response_that_takes_longer_than_ten_seconds() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().code(201)
                .body("""{"path":"/tmp/x.jpg","name":"x.jpg","size":3}""")
                .headersDelay(12, TimeUnit.SECONDS)
                .build(),
        )

        val result = api(serverRule.server).uploadArtifact("abc".toByteArray(), "x.jpg", "image/jpeg")

        assertEquals("/tmp/x.jpg", result.path)
    }
}
