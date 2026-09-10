package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.auth.AccountSession
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.auth.AccountSessionStore
import com.hermes.client.data.auth.AccountDeviceRouteMode
import com.hermes.client.data.auth.PendingEmailChallenge
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HermesRestApiTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json { ignoreUnknownKeys = true }

    private fun api(server: MockWebServer) = HermesRestApi(testHttpClient(), json) {
        GatewayConfig(baseUrl = server.url("/").toString().trimEnd('/'), token = "secret")
    }

    @Test fun sessions_parses_list_and_sends_token() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"sessions":[{"id":"s1","title":"First","model":"opus","provider":"anthropic","message_count":3}]}"""
        ).build())

        val list = api(serverRule.server).sessions(limit = 20, offset = 0)
        assertEquals(1, list.size)
        assertEquals("First", list[0].title)

        val recorded = serverRule.server.takeRequest()
        assertTrue(recorded.target.startsWith("/api/sessions"))
        assertEquals("secret", recorded.headers["X-Hermes-Session-Token"])
    }

    @Test fun account_mode_uses_explicit_device_path_and_never_sends_legacy_token() = runTest {
        val server = serverRule.server
        server.enqueue(MockResponse.Builder().code(200).body("""{"sessions":[]}""").build())
        val accountStore = MemoryAccountStore(
            AccountSession(
                baseUrl = server.url("/").toString().trimEnd('/'),
                accountId = "account-1",
                installationId = "installation-1",
                installationDisplayName = "Pixel",
                accessToken = "hga_secret",
                accessExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "hgr_secret",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
                selectedDeviceId = "office/mac 1",
            ),
        )
        val manager = AccountSessionManager(accountStore, AccountApi(testHttpClient(), json))
        val legacyClient = testHttpClient().newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Cookie", "legacy-dashboard=session")
                        .header("X-Test-Client", "legacy")
                        .build(),
                )
            }
            .build()
        val isolatedAccountClient = testHttpClient().newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("X-Test-Client", "account")
                        .build(),
                )
            }
            .build()
        val accountApi = HermesRestApi(legacyClient, json, manager, isolatedAccountClient) {
            GatewayConfig(baseUrl = server.url("/").toString().trimEnd('/'), token = "legacy-secret")
        }

        accountApi.sessions(limit = 20, offset = 0)
        val recorded = server.takeRequest()

        assertTrue(recorded.target.startsWith("/v2/devices/office%2Fmac%201/api/sessions"))
        assertEquals("Bearer hga_secret", recorded.headers["Authorization"])
        assertEquals(null, recorded.headers["X-Hermes-Session-Token"])
        assertEquals(null, recorded.headers["Cookie"])
        assertEquals("account", recorded.headers["X-Test-Client"])

        // A foreground chat may temporarily route the WebSocket elsewhere. Ordinary REST remains
        // on the selected Mac unless the conversation supplies its explicit affinity.
        manager.routeToDevice("home/mac 2")
        server.enqueue(MockResponse.Builder().code(200).body("""{"sessions":[]}""").build())
        accountApi.sessions(limit = 20, offset = 0)
        val selectedDefault = server.takeRequest()
        assertTrue(selectedDefault.target.startsWith("/v2/devices/office%2Fmac%201/api/sessions"))

        server.enqueue(MockResponse.Builder().code(200).body("""{"messages":[]}""").build())
        accountApi.messages("session-1", "personal", deviceId = "home/mac 2")
        val historical = server.takeRequest()
        assertTrue(
            historical.target.startsWith(
                "/v2/devices/home%2Fmac%202/api/sessions/session-1/messages?profile=personal",
            ),
        )
        assertEquals("Bearer hga_secret", historical.headers["Authorization"])
        assertEquals("office/mac 1", manager.session.value?.selectedDeviceId)
    }

    @Test fun singular_account_mode_uses_compatibility_path_with_isolated_bearer_client() = runTest {
        val server = serverRule.server
        server.enqueue(MockResponse.Builder().code(200).body("""{"sessions":[]}""").build())
        val accountStore = MemoryAccountStore(
            AccountSession(
                baseUrl = server.url("/").toString().trimEnd('/'),
                accountId = "account-1",
                installationId = "installation-1",
                installationDisplayName = "Pixel",
                accessToken = "hga_secret",
                accessExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "hgr_secret",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
                selectedDeviceId = "mac-1",
                deviceRouteMode = AccountDeviceRouteMode.SINGLE_BINDING,
            ),
        )
        val manager = AccountSessionManager(accountStore, AccountApi(testHttpClient(), json))
        val accountApi = HermesRestApi(testHttpClient(), json, manager, testHttpClient()) {
            GatewayConfig(baseUrl = server.url("/").toString().trimEnd('/'), token = "legacy-secret")
        }

        accountApi.sessions(limit = 20, offset = 0)
        val recorded = server.takeRequest()

        assertTrue(recorded.target.startsWith("/api/sessions"))
        assertFalse(recorded.target.startsWith("/v2/devices/"))
        assertEquals("Bearer hga_secret", recorded.headers["Authorization"])
        assertEquals(null, recorded.headers["X-Hermes-Session-Token"])
    }

    @Test fun revokedAccountSessionCannotSilentlyFallBackToStoredLegacyCredentials() = runTest {
        val server = serverRule.server
        val accountStore = MemoryAccountStore(
            AccountSession(
                baseUrl = server.url("/").toString().trimEnd('/'),
                accountId = "account-1",
                installationId = "installation-1",
                installationDisplayName = "Pixel",
                accessToken = "hga_expired",
                accessExpiresAt = "2026-01-01T00:00:00Z",
                refreshToken = "hgr_revoked",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
                selectedDeviceId = "mac-1",
            ),
        )
        server.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", "application/json").body(
            """{"error":{"code":"HR-AUTH-004","message":"revoked","retryable":false,"recoveryAction":"sign_in"}}""",
        ).build())
        val manager = AccountSessionManager(
            accountStore,
            AccountApi(testHttpClient(), json),
            now = { java.time.Instant.parse("2026-09-08T00:00:00Z") },
        )
        val api = HermesRestApi(testHttpClient(), json, manager, testHttpClient()) {
            GatewayConfig(server.url("/").toString().trimEnd('/'), "legacy-secret")
        }

        val refreshFailure = runCatching { api.sessions(limit = 20, offset = 0) }.exceptionOrNull()
        val retryFailure = runCatching { api.sessions(limit = 20, offset = 0) }.exceptionOrNull()

        assertTrue(refreshFailure is AccountApiException)
        assertEquals("HR-AUTH-003", (retryFailure as HermesApiException).errorCode)
        assertTrue(manager.requiresAccountReauthentication())
        assertEquals(1, server.requestCount)
    }

    @Test fun accountRestInvalidSessionClearsLocalSessionButRecentAuthFailureDoesNot() = runTest {
        val server = serverRule.server
        val accountStore = MemoryAccountStore(
            AccountSession(
                baseUrl = server.url("/").toString().trimEnd('/'),
                accountId = "account-1",
                installationId = "installation-1",
                installationDisplayName = "Pixel",
                accessToken = "hga_current",
                accessExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "hgr_current",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
                selectedDeviceId = "mac-1",
            ),
        )
        val manager = AccountSessionManager(accountStore, AccountApi(testHttpClient(), json))
        val accountApi = HermesRestApi(testHttpClient(), json, manager, testHttpClient()) {
            GatewayConfig(server.url("/").toString().trimEnd('/'), "legacy-secret")
        }

        server.enqueue(MockResponse.Builder().code(403).addHeader("Content-Type", "application/json").body(
            """{"error":{"code":"HR-AUTH-006","message":"recent auth required","retryable":false,"recoveryAction":"reauthenticate"}}""",
        ).build())
        val recentAuthFailure = runCatching {
            accountApi.sessions(limit = 20, offset = 0)
        }.exceptionOrNull() as HermesApiException

        assertEquals("HR-AUTH-006", recentAuthFailure.errorCode)
        assertEquals("mac-1", manager.session.value?.selectedDeviceId)
        assertFalse(manager.requiresAccountReauthentication())

        server.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", "application/json").body(
            """{"error":{"code":"HR-AUTH-004","message":"revoked","retryable":false,"recoveryAction":"sign_in"}}""",
        ).build())
        val revokedFailure = runCatching {
            accountApi.sessions(limit = 20, offset = 0)
        }.exceptionOrNull() as HermesApiException

        assertEquals("HR-AUTH-004", revokedFailure.errorCode)
        assertEquals(null, manager.session.value)
        assertTrue(manager.requiresAccountReauthentication())
        assertEquals(2, server.requestCount)
    }

    @Test fun accountRestDeviceRevocationOnlyRepairsTheRejectedDeviceRoute() = runTest {
        val server = serverRule.server
        val accountStore = MemoryAccountStore(
            AccountSession(
                baseUrl = server.url("/").toString().trimEnd('/'),
                accountId = "account-1",
                installationId = "installation-1",
                installationDisplayName = "Pixel",
                accessToken = "hga_current",
                accessExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "hgr_current",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
                selectedDeviceId = "mac-default",
            ),
        )
        val manager = AccountSessionManager(accountStore, AccountApi(testHttpClient(), json))
        val accountApi = HermesRestApi(testHttpClient(), json, manager, testHttpClient()) {
            GatewayConfig(server.url("/").toString().trimEnd('/'), "legacy-secret")
        }
        manager.routeToDevice("mac-history")

        server.enqueue(MockResponse.Builder().code(404).body("not found").build())
        runCatching {
            accountApi.messages("session-1", deviceId = "mac-history")
        }
        assertEquals("mac-default", manager.session.value?.selectedDeviceId)
        assertEquals("mac-history", manager.transportRoutingContext()?.deviceId)

        server.enqueue(MockResponse.Builder().code(404).addHeader("Content-Type", "application/json").body(
            """{"error":{"code":"HR-BIND-011","message":"access revoked","retryable":false,"recoveryAction":"select_device"}}""",
        ).build())
        val historicalFailure = runCatching {
            accountApi.messages("session-1", deviceId = "mac-history")
        }.exceptionOrNull() as HermesApiException

        assertEquals("HR-BIND-011", historicalFailure.errorCode)
        assertEquals("mac-default", manager.session.value?.selectedDeviceId)
        assertEquals("mac-default", manager.transportRoutingContext()?.deviceId)

        server.enqueue(MockResponse.Builder().code(404).addHeader("Content-Type", "application/json").body(
            """{"error":{"code":"HR-BIND-011","message":"access revoked","retryable":false,"recoveryAction":"select_device"}}""",
        ).build())
        val defaultFailure = runCatching {
            accountApi.sessions(limit = 20, offset = 0)
        }.exceptionOrNull() as HermesApiException

        assertEquals("HR-BIND-011", defaultFailure.errorCode)
        assertEquals(null, manager.session.value?.selectedDeviceId)
        assertFalse(manager.requiresAccountReauthentication())
        assertEquals(3, server.requestCount)
    }

    @Test fun signedInAccountWithoutASelectedMacNeverFallsBackUnlessLegacyWasExplicitlyChosen() = runTest {
        val server = serverRule.server
        val accountStore = MemoryAccountStore(
            AccountSession(
                baseUrl = server.url("/").toString().trimEnd('/'),
                accountId = "account-1",
                installationId = "installation-1",
                installationDisplayName = "Pixel",
                accessToken = "hga_current",
                accessExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "hgr_current",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
                selectedDeviceId = null,
            ),
        )
        val manager = AccountSessionManager(accountStore, AccountApi(testHttpClient(), json))
        val accountApi = HermesRestApi(testHttpClient(), json, manager, testHttpClient()) {
            GatewayConfig(server.url("/").toString().trimEnd('/'), "legacy-secret")
        }

        val blocked = runCatching {
            accountApi.sessions(limit = 20, offset = 0)
        }.exceptionOrNull() as HermesApiException

        assertEquals("HR-BIND-009", blocked.errorCode)
        assertEquals(0, server.requestCount)

        manager.allowExplicitLegacyFallback()
        server.enqueue(MockResponse.Builder().code(200).body("""{"sessions":[]}""").build())
        accountApi.sessions(limit = 20, offset = 0)

        val legacyRequest = server.takeRequest()
        assertEquals("legacy-secret", legacyRequest.headers["X-Hermes-Session-Token"])
        assertEquals(null, legacyRequest.headers["Authorization"])
    }

    // T1: the cross-profile list tags each session with its true profile and carries
    // profile_totals for group headers. A default-profile session reports is_default_profile.
    @Test fun profileSessions_parses_per_session_profile_and_totals() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"sessions":[
                {"id":"s1","title":"Mine","profile":"personal","cwd":"/home/me/app","archived":false},
                {"id":"s2","title":"Default","is_default_profile":true,"archived":false}
            ],"total":2,"profile_totals":{"personal":1,"default":1},"errors":[]}"""
        ).build())

        val res = api(serverRule.server).profileSessions()
        assertEquals(2, res.sessions.size)
        assertEquals("personal", res.sessions[0].profile)
        // is_default_profile with no explicit profile name still surfaces (normalized downstream).
        assertTrue(res.sessions[1].isDefaultProfile)
        assertEquals(1, res.profileTotals["personal"])

        val recorded = serverRule.server.takeRequest()
        assertTrue(recorded.target.startsWith("/api/profiles/sessions"))
    }

    // T4: the archived cross-profile view requests archived=only on the same endpoint.
    @Test fun profileSessions_archivedOnly_appends_param() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"sessions":[],"total":0,"profile_totals":{},"errors":[]}"""
        ).build())

        api(serverRule.server).profileSessions(archivedOnly = true)

        val recorded = serverRule.server.takeRequest()
        assertTrue(recorded.target.startsWith("/api/profiles/sessions"))
        assertTrue("must request only archived sessions", recorded.target.contains("archived=only"))
    }

    @Test fun status_returns_true_on_200() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())
        assertTrue(api(serverRule.server).status())
    }

    // T10b: statusFor() uses supplied baseUrl+token directly, never touches configProvider
    @Test fun statusFor_uses_explicit_credentials_not_stored_config() = runTest {
        val server = serverRule.server
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())

        // api() is wired with token="secret", but we call statusFor with a different token
        val result = api(server).statusFor(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "explicit-token",
        )
        assertTrue(result)
        val recorded = server.takeRequest()
        assertEquals("explicit-token", recorded.headers["X-Hermes-Session-Token"])
    }

    @Test fun statusFor_returns_false_on_non_2xx() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(401).body("Unauthorized").build())
        val result = api(serverRule.server).statusFor(
            baseUrl = serverRule.server.url("/").toString().trimEnd('/'),
            token = "bad-token",
        )
        assertFalse(result)
    }

    @Test fun probeStatusFor_classifies_auth_server_and_wrong_endpoint_failures() = runTest {
        val server = serverRule.server
        server.enqueue(MockResponse.Builder().code(403).build())
        server.enqueue(MockResponse.Builder().code(503).body("{\"error\":\"device_offline\"}").build())
        server.enqueue(MockResponse.Builder().code(404).build())
        val base = server.url("/").toString().trimEnd('/')

        assertEquals(GatewayProbeResult.Unauthorized(403), api(server).probeStatusFor(base, "bad"))
        assertEquals(
            GatewayProbeResult.ServerFailure(503, "device_offline"),
            api(server).probeStatusFor(base, "token"),
        )
        assertEquals(GatewayProbeResult.InvalidEndpoint(404), api(server).probeStatusFor(base, "token"))
    }

    @Test fun setActiveProfile_posts_to_correct_endpoint_with_token_and_name() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        api(serverRule.server).setActiveProfile("personal")

        val recorded = serverRule.server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.target.startsWith("/api/profiles/active"))
        assertEquals("secret", recorded.headers["X-Hermes-Session-Token"])
        val body = recorded.body?.utf8().orEmpty()
        assertTrue("body should contain profile name", body.contains("\"personal\""))
    }

    private class MemoryAccountStore(private var account: AccountSession?) : AccountSessionStore {
        private var reauthenticationRequired = false
        private var explicitLegacy = false
        override fun clientInstallationId() = "00000000-0000-0000-0000-000000000099"
        override fun loadAccountSession() = account
        override fun saveAccountSession(session: AccountSession) {
            account = session
            reauthenticationRequired = false
        }
        override fun clearAccountSession() { account = null }
        override fun clearAccountSession(requireReauthentication: Boolean) {
            account = null
            reauthenticationRequired = requireReauthentication
            explicitLegacy = false
        }
        override fun accountReauthenticationRequired() = reauthenticationRequired
        override fun setAccountReauthenticationRequired(required: Boolean) {
            reauthenticationRequired = required
        }
        override fun explicitLegacyConnectionSelected() = explicitLegacy
        override fun setExplicitLegacyConnectionSelected(selected: Boolean) {
            explicitLegacy = selected
        }
        override fun loadPendingEmailChallenge(): PendingEmailChallenge? = null
        override fun savePendingEmailChallenge(challenge: PendingEmailChallenge) = Unit
        override fun clearPendingEmailChallenge() = Unit
    }
}
