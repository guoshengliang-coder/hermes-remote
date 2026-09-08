package com.hermes.client.data.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AccountApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = AccountApi(OkHttpClient(), Json { ignoreUnknownKeys = true })
    }

    @After fun tearDown() = server.close()

    @Test fun capabilities_are_public_and_parse_email_first_flags() = runTest {
        server.enqueue(jsonResponse("""{
          "version":1,
          "accountAuth":{"enabled":true,"providers":["email_otp"],"android":true,"accountDeletion":true},
          "binding":{"enabled":true,"maxActiveConnectorsPerAccount":3,"supportsDeviceSelection":true},
          "legacy":{"appTokenAccepted":true}
        }"""))

        val result = api.capabilities(baseUrl())
        val request = server.takeRequest()

        assertEquals("/v2/capabilities", request.target)
        assertEquals(null, request.headers["Authorization"])
        assertEquals(listOf("email_otp"), result.accountAuth.providers)
        assertTrue(result.accountAuth.accountDeletion)
        assertTrue(result.binding.supportsDeviceSelection)
    }

    @Test fun omittedAccountDeletionCapabilityFailsClosed() = runTest {
        server.enqueue(jsonResponse("""{
          "version":1,
          "accountAuth":{"enabled":true,"providers":["email_otp"],"android":true}
        }"""))

        val result = api.capabilities(baseUrl())

        assertEquals(false, result.accountAuth.accountDeletion)
    }

    @Test fun email_exchange_binds_android_installation_and_idempotency_key() = runTest {
        server.enqueue(jsonResponse(exchangeResponse()))

        api.exchangeEmailCode(
            baseUrl(),
            challengeId = "00000000-0000-0000-0000-000000000001",
            email = "person@example.com",
            code = "012345",
            clientInstallationId = "00000000-0000-0000-0000-000000000002",
            displayName = "Pixel Test",
            appVersion = "0.1.89",
            idempotencyKey = "00000000-0000-0000-0000-000000000003",
        )
        val request = server.takeRequest()

        assertEquals("/v2/auth/email/exchange", request.target)
        assertEquals("00000000-0000-0000-0000-000000000003", request.headers["Idempotency-Key"])
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains("\"platform\":\"android\""))
        assertTrue(body.contains("\"code\":\"012345\""))
        assertTrue(body.contains("\"clientInstallationId\":\"00000000-0000-0000-0000-000000000002\""))
    }

    @Test fun device_list_uses_bearer_and_parses_shared_access() = runTest {
        server.enqueue(jsonResponse("""{
          "items":[{
            "id":"binding-1","generation":1,"deviceId":"office/mac 1",
            "desktopDisplayName":"Office Mac mini","publicKeyFingerprint":"ignored",
            "connector":{"online":true},"hermes":{"reachable":true},
            "gateway":{"latencyMs":8},"endToEnd":{"healthy":true},
            "access":"operator","isDefault":false
          }],
          "maxOwnedDevices":3
        }"""))

        val result = api.devices(baseUrl(), "hga_secret")
        val request = server.takeRequest()

        assertEquals("Bearer hga_secret", request.headers["Authorization"])
        assertEquals("operator", result.items.single().access)
        assertEquals("Office Mac mini", result.items.single().desktopDisplayName)
    }

    @Test fun structured_server_error_exposes_only_stable_contract_fields() = runTest {
        server.enqueue(
            jsonResponse("""{
              "error":{"code":"HR-AUTH-009","message":"raw detail","retryable":false,
              "recoveryAction":"request_new_code","correlationId":"corr-1"}
            }""", 401),
        )

        val error = runCatching { api.devices(baseUrl(), "expired") }.exceptionOrNull() as AccountApiException

        assertEquals("HR-AUTH-009", error.errorCode)
        assertEquals(false, error.retryable)
        assertEquals("corr-1", error.correlationId)
        assertEquals("HR-AUTH-009", error.message)
    }

    @Test fun explicit_device_probe_preserves_revocation_error_for_recovery() = runTest {
        server.enqueue(
            jsonResponse("""{
              "error":{"code":"HR-AUTH-004","message":"revoked","retryable":false,
              "recoveryAction":"sign_in"}
            }""", 401),
        )

        val error = runCatching {
            api.probeDevice(baseUrl(), "expired", "office/mac 1")
        }.exceptionOrNull() as AccountApiException
        val request = server.takeRequest()

        assertEquals("/v2/devices/office%2Fmac%201/api/status", request.target)
        assertEquals("HR-AUTH-004", error.errorCode)
        assertEquals(401, error.statusCode)
    }

    @Test fun signOut_revokes_only_the_current_phone_installation() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())

        api.signOut(
            baseUrl(),
            bearer = "hga_secret",
            idempotencyKey = "00000000-0000-0000-0000-000000000004",
        )
        val request = server.takeRequest()

        assertEquals("DELETE", request.method)
        assertEquals("/v2/installations/current", request.target)
        assertEquals("Bearer hga_secret", request.headers["Authorization"])
        assertEquals("00000000-0000-0000-0000-000000000004", request.headers["Idempotency-Key"])
    }

    @Test fun accountDeletionUsesAuthenticatedEmailReverificationAndExactAcknowledgement() = runTest {
        server.enqueue(jsonResponse("""{
          "challenge":{"challengeId":"00000000-0000-0000-0000-000000000010",
          "expiresAt":"2099-01-01T00:10:00Z","resendAfter":"2099-01-01T00:01:00Z"}
        }"""))
        server.enqueue(jsonResponse("""{
          "grant":"hgg_delete","scope":"account.delete","expiresAt":"2099-01-01T00:10:00Z"
        }"""))
        server.enqueue(MockResponse.Builder().code(204).build())

        val challenge = api.requestEmailReauthenticationChallenge(
            baseUrl(),
            email = "person@example.com",
            bearer = "hga_secret",
        )
        val challengeRequest = server.takeRequest()
        assertEquals("/v2/auth/reauth/email/challenges", challengeRequest.target)
        assertEquals("Bearer hga_secret", challengeRequest.headers["Authorization"])
        assertEquals("{\"email\":\"person@example.com\"}", challengeRequest.body?.utf8())

        val proof = api.reauthenticateEmail(
            baseUrl(),
            challengeId = challenge.challengeId,
            email = "person@example.com",
            code = "012345",
            scope = "account.delete",
            bearer = "hga_secret",
            idempotencyKey = "00000000-0000-0000-0000-000000000011",
        )
        val proofRequest = server.takeRequest()
        assertEquals("account.delete", proof.scope)
        assertEquals("/v2/auth/reauth/email", proofRequest.target)
        assertEquals("00000000-0000-0000-0000-000000000011", proofRequest.headers["Idempotency-Key"])
        assertTrue(proofRequest.body?.utf8().orEmpty().contains("\"scope\":\"account.delete\""))

        api.deleteAccount(
            baseUrl(),
            bearer = "hga_secret",
            grant = proof.grant,
            idempotencyKey = "00000000-0000-0000-0000-000000000012",
        )
        val deletionRequest = server.takeRequest()
        assertEquals("DELETE", deletionRequest.method)
        assertEquals("/v2/account", deletionRequest.target)
        assertEquals("Bearer hga_secret", deletionRequest.headers["Authorization"])
        assertEquals("00000000-0000-0000-0000-000000000012", deletionRequest.headers["Idempotency-Key"])
        assertEquals(
            "{\"grant\":\"hgg_delete\",\"acknowledgedPermanentCloudDeletion\":true}",
            deletionRequest.body?.utf8(),
        )
    }

    private fun baseUrl() = server.url("/").toString()

    private fun jsonResponse(body: String, status: Int = 200) = MockResponse.Builder()
        .code(status)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun exchangeResponse() = """{
      "account":{"id":"account-1","displayName":"Person","email":"person@example.com","avatarUrl":null},
      "installation":{"id":"installation-1","kind":"phone","platform":"android","displayName":"Pixel Test"},
      "session":{"accessToken":"hga_access","accessExpiresAt":"2099-01-01T00:00:00Z",
      "refreshToken":"hgr_refresh","refreshExpiresAt":"2099-02-01T00:00:00Z"}
    }"""
}
