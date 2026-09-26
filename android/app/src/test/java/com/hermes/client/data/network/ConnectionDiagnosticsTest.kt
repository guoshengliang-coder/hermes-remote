package com.hermes.client.data.network

import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.update.createUpdateHttpClient
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class ConnectionDiagnosticsTest {
    private val credentials = mockk<CredentialStore>()
    private val accountSessions = mockk<AccountSessionManager>()

    @Test fun repeatedAddressFailureOnCustomOriginNeverContactsOfficialService() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val diagnostics = ConnectionDiagnostics(createUpdateHttpClient(), credentials, accountSessions)
            val diagnosis = diagnostics.diagnose(
                listOf("UnknownHostException", "UnknownHostException", "UnknownHostException"),
                baseUrl = "https://custom.example",
                officialOrigin = server.url("/").toString(),
            )
            assertEquals(ConnectionDiagnosis.ADDRESS_NOT_FOUND, diagnosis)
            assertEquals(0, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test fun publicChecksUseNoCredentialsAndRequireRepeatedServiceFailure() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(2) {
                server.enqueue(MockResponse.Builder().code(200).build())
                server.enqueue(MockResponse.Builder().code(503).build())
            }
            val diagnostics = ConnectionDiagnostics(createUpdateHttpClient(), credentials, accountSessions)
            val origin = server.url("/").toString()

            assertEquals(
                ConnectionDiagnosis.SERVICE_UNAVAILABLE,
                diagnostics.diagnose(listOf("SocketTimeoutException"), origin, origin),
            )
            repeat(4) {
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertNull(request.headers["Authorization"])
                assertNull(request.headers["Cookie"])
                assertNull(request.headers["X-Hermes-Session-Token"])
                assertEquals(if (it % 2 == 0) "/health" else "/relay-health", request.target)
            }
        } finally {
            server.close()
        }
    }

    @Test fun contradictoryPublicChecksProduceUncertainVerdict() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(200).build())
            server.enqueue(MockResponse.Builder().code(200).build())
            server.enqueue(MockResponse.Builder().code(503).build())
            server.enqueue(MockResponse.Builder().code(200).build())
            val diagnostics = ConnectionDiagnostics(createUpdateHttpClient(), credentials, accountSessions)
            val origin = server.url("/").toString()

            assertEquals(
                ConnectionDiagnosis.FLAPPING,
                diagnostics.diagnose(listOf("SocketTimeoutException"), origin, origin),
            )
        } finally {
            server.close()
        }
    }

    @Test fun successfulPublicChecksContradictRepeatedDnsFailure() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(4) { server.enqueue(MockResponse.Builder().code(200).build()) }
            val diagnostics = ConnectionDiagnostics(createUpdateHttpClient(), credentials, accountSessions)
            val origin = server.url("/").toString()
            assertEquals(
                ConnectionDiagnosis.FLAPPING,
                diagnostics.diagnose(listOf("UnknownHostException", "UnknownHostException"), origin, origin),
            )
        } finally {
            server.close()
        }
    }

    @Test fun cancelledDiagnosisDoesNotWaitForPublicTimeout() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(200).headersDelay(5, TimeUnit.SECONDS).build())
            val diagnostics = ConnectionDiagnostics(createUpdateHttpClient(), credentials, accountSessions)
            val origin = server.url("/").toString()
            val job = async { diagnostics.diagnose(listOf("SocketTimeoutException"), origin, origin) }
            server.takeRequest(1, TimeUnit.SECONDS)
            job.cancelAndJoin()
            assertEquals(true, job.isCancelled)
        } finally {
            server.close()
        }
    }
}
