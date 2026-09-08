package com.hermes.client.data.auth

import com.hermes.client.data.network.AccountApi
import com.hermes.client.data.network.AccountDeviceDto
import com.hermes.client.data.network.AccountEndToEndHealthDto
import com.hermes.client.data.network.AccountExchangeResponseDto
import com.hermes.client.data.network.AccountDto
import com.hermes.client.data.network.AccountInstallationDto
import com.hermes.client.data.network.AccountTokensDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

class AccountSessionManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryAccountStore

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        store = MemoryAccountStore()
    }

    @After fun tearDown() = server.close()

    @Test fun selected_device_connection_uses_unexpired_access_without_refresh() = runTest {
        store.account = session(accessExpiresAt = "2099-01-01T00:00:00Z", selectedDeviceId = "mac-1")
        val manager = manager()

        val connection = manager.connection()

        assertEquals("mac-1", connection?.deviceId)
        assertEquals("hga_old", connection?.bearer)
        assertEquals(0, server.requestCount)
    }

    @Test fun account_control_connection_and_cursor_scope_do_not_requireASelectedMac() = runTest {
        store.account = session(accessExpiresAt = "2099-01-01T00:00:00Z", selectedDeviceId = null)
        val manager = manager()

        assertNull(manager.connection())
        assertEquals("hga_old", manager.accountControlConnection()?.bearer)
        val firstScope = manager.lifecycleCursorScope()

        store.account = store.account?.copy(installationId = "installation-2")
        val secondManager = manager()
        assertNotEquals(firstScope, secondManager.lifecycleCursorScope())
    }

    @Test fun expired_access_rotates_with_persisted_idempotency_key() = runTest {
        store.account = session(accessExpiresAt = "2026-01-01T00:00:00Z", selectedDeviceId = "mac-1")
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{
          "session":{"accessToken":"hga_new","accessExpiresAt":"2099-01-01T00:00:00Z",
          "refreshToken":"hgr_new","refreshExpiresAt":"2099-02-01T00:00:00Z"}
        }""").build())
        val manager = manager()

        assertEquals("hga_new", manager.accessToken())
        val request = server.takeRequest()

        assertEquals("/v2/auth/refresh", request.target)
        assertEquals(store.lastSavedPendingKey, request.headers["Idempotency-Key"])
        assertEquals(null, store.account?.pendingRefreshIdempotencyKey)
        assertEquals("hgr_new", store.account?.refreshToken)
    }

    @Test fun revoked_refresh_clears_only_account_record_but_keeps_installation_id() = runTest {
        store.account = session(accessExpiresAt = "2026-01-01T00:00:00Z", selectedDeviceId = "mac-1")
        server.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", "application/json").body("""{
          "error":{"code":"HR-AUTH-004","message":"revoked","retryable":false,"recoveryAction":"sign_in"}
        }""").build())
        val manager = manager()

        runCatching { manager.accessToken() }

        assertNull(store.account)
        assertEquals(true, store.reauthenticationRequired)
        assertEquals("00000000-0000-0000-0000-000000000099", store.clientInstallationId())
    }

    @Test fun recentReauthenticationRequirementDoesNotDestroyTheAccountSession() = runTest {
        store.account = session(accessExpiresAt = "2026-01-01T00:00:00Z", selectedDeviceId = "mac-1")
        server.enqueue(MockResponse.Builder().code(403).addHeader("Content-Type", "application/json").body("""{
          "error":{"code":"HR-AUTH-006","message":"recent authentication required","retryable":false,"recoveryAction":"reauthenticate"}
        }""").build())
        val manager = manager()

        val failure = runCatching { manager.accessToken() }.exceptionOrNull()

        assertEquals("HR-AUTH-006", (failure as com.hermes.client.data.network.AccountApiException).errorCode)
        assertEquals("mac-1", manager.session.value?.selectedDeviceId)
        assertEquals(false, manager.requiresAccountReauthentication())
    }

    @Test fun explicitLegacyFallbackClearsThePersistentReauthenticationGate() {
        store.reauthenticationRequired = true
        val manager = manager()

        manager.allowExplicitLegacyFallback()

        assertEquals(false, manager.requiresAccountReauthentication())
    }

    @Test fun committedAccountDeletionBlocksSilentLegacyFallbackUntilExplicitSelection() = runTest {
        store.account = session(accessExpiresAt = "2099-01-01T00:00:00Z", selectedDeviceId = "mac-1")
        store.deletionCommitted = true
        store.explicitLegacy = true
        val manager = manager()

        assertEquals(AccountTransportMode.ACCOUNT_DELETION_COMMITTED, manager.transportMode())
        assertNull(manager.connection())

        manager.allowExplicitLegacyFallback()

        assertEquals(AccountTransportMode.LEGACY, manager.transportMode())
        assertEquals(false, store.deletionCommitted)
    }

    @Test fun completingAccountDeletionAtomicallyClearsSecretsAndEntersTerminalGate() {
        store.account = session(accessExpiresAt = "2099-01-01T00:00:00Z", selectedDeviceId = "mac-1")
        store.pendingDeletion = PendingAccountDeletion(
            baseUrl = baseUrl(),
            accountId = "account-1",
            email = "person@example.com",
            challengeId = "challenge-1",
            expiresAt = "2099-01-01T00:10:00Z",
            resendAfter = "2099-01-01T00:01:00Z",
            reauthenticationIdempotencyKey = "reauth-1",
            grant = "hgg_delete",
            deletionIdempotencyKey = "delete-1",
        )
        val manager = manager()

        manager.completeAccountDeletion()

        assertNull(manager.session.value)
        assertNull(store.pendingDeletion)
        assertEquals(true, store.deletionCommitted)
        assertEquals(AccountTransportMode.ACCOUNT_DELETION_COMMITTED, manager.transportMode())
    }

    @Test fun explicitLegacySelectionPersistsAndOnlyADeviceSelectionReactivatesAccountTransport() = runTest {
        store.account = session(accessExpiresAt = "2099-01-01T00:00:00Z", selectedDeviceId = "mac-1")
        val manager = manager()

        manager.allowExplicitLegacyFallback()

        assertEquals(AccountTransportMode.LEGACY, manager.transportMode())
        assertNull(manager.connection())
        assertNull(manager.accountControlConnection())
        assertEquals(false, manager.routeToDevice("mac-history"))
        assertEquals(AccountTransportMode.LEGACY, manager().transportMode())

        manager.selectDevice(
            AccountDeviceDto(
                id = "binding-2",
                generation = 1,
                deviceId = "mac-2",
                desktopDisplayName = "Home Mac",
                endToEnd = AccountEndToEndHealthDto(healthy = true),
                access = "owner",
            ),
        )

        assertEquals(AccountTransportMode.ACCOUNT, manager.transportMode())
        assertEquals("mac-2", manager.connection()?.deviceId)
        assertEquals(false, store.explicitLegacy)
    }

    @Test fun terminalTransportRejectionsChooseAccountOrDeviceRecoveryWithoutCrossDamage() {
        store.account = session(accessExpiresAt = "2099-01-01T00:00:00Z", selectedDeviceId = "mac-1")
        val deviceRevoked = manager()
        deviceRevoked.routeToDevice("mac-shared-history")

        // Account-owned REST endpoints have no Mac tag. Even a malformed binding response from
        // that surface must not guess that the current/default route was rejected.
        deviceRevoked.handleRestRejection(404, "HR-BIND-011", rejectedDeviceId = null)

        assertEquals("mac-1", deviceRevoked.session.value?.selectedDeviceId)
        assertEquals("mac-shared-history", deviceRevoked.transportRoutingContext()?.deviceId)

        deviceRevoked.handleTransportHandshakeRejection(404, "mac-shared-history")

        assertEquals("account-1", deviceRevoked.session.value?.accountId)
        assertEquals("mac-1", deviceRevoked.session.value?.selectedDeviceId)
        assertEquals("mac-1", deviceRevoked.transportRoutingContext()?.deviceId)
        assertEquals(false, deviceRevoked.requiresAccountReauthentication())

        deviceRevoked.handleTransportHandshakeRejection(404, "mac-1")

        assertEquals("account-1", deviceRevoked.session.value?.accountId)
        assertNull(deviceRevoked.session.value?.selectedDeviceId)

        store.account = session(accessExpiresAt = "2099-01-01T00:00:00Z", selectedDeviceId = "mac-2")
        val sessionRevoked = manager()

        sessionRevoked.handleTransportHandshakeRejection(401, "mac-2")

        assertNull(sessionRevoked.session.value)
        assertEquals(true, sessionRevoked.requiresAccountReauthentication())
    }

    @Test fun activation_and_device_selection_are_separate_commits() {
        store.reauthenticationRequired = true
        val manager = manager()
        manager.activate(
            baseUrl(),
            AccountExchangeResponseDto(
                AccountDto("account-1", email = "person@example.com"),
                AccountInstallationDto("installation-1", "phone", "android", "Pixel"),
                AccountTokensDto("hga", "2099-01-01T00:00:00Z", "hgr", "2099-02-01T00:00:00Z"),
            ),
        )

        assertNull(manager.session.value?.selectedDeviceId)
        assertEquals(false, manager.requiresAccountReauthentication())
        assertEquals(normalizeGatewayBaseUrl(baseUrl()), store.lastAccountBaseUrl())
        manager.selectDevice(
            AccountDeviceDto(
                id = "binding-1",
                generation = 1,
                deviceId = "mac-1",
                desktopDisplayName = "Office Mac",
                endToEnd = AccountEndToEndHealthDto(healthy = true),
                access = "operator",
            ),
        )
        assertEquals("mac-1", manager.session.value?.selectedDeviceId)
        assertEquals("Office Mac", manager.session.value?.selectedDeviceName)
    }

    @Test fun conversation_route_does_not_change_selected_mac_and_can_be_restored() = runTest {
        store.account = session(accessExpiresAt = "2099-01-01T00:00:00Z", selectedDeviceId = "mac-default")
        val manager = manager()

        assertEquals(true, manager.routeToDevice("mac-history"))
        assertEquals("mac-history", manager.transportConnection()?.deviceId)
        assertEquals("mac-history", manager.transportRoutingContext()?.deviceId)
        assertEquals("mac-default", manager.routingContext()?.deviceId)
        assertEquals("mac-default", manager.connection()?.deviceId)
        assertEquals("mac-default", manager.session.value?.selectedDeviceId)

        assertEquals(true, manager.restoreSelectedDeviceRoute())
        assertEquals("mac-default", manager.transportConnection()?.deviceId)
        assertEquals("mac-default", manager.transportRoutingContext()?.deviceId)
        assertEquals("mac-default", manager.session.value?.selectedDeviceId)
    }

    private fun manager() = AccountSessionManager(
        store,
        AccountApi(OkHttpClient(), Json { ignoreUnknownKeys = true }),
        now = { Instant.parse("2026-09-08T00:00:00Z") },
    )

    private fun session(accessExpiresAt: String, selectedDeviceId: String?) = AccountSession(
        baseUrl = baseUrl(),
        accountId = "account-1",
        accountEmail = "person@example.com",
        installationId = "installation-1",
        installationDisplayName = "Pixel",
        accessToken = "hga_old",
        accessExpiresAt = accessExpiresAt,
        refreshToken = "hgr_old",
        refreshExpiresAt = "2099-02-01T00:00:00Z",
        selectedDeviceId = selectedDeviceId,
    )

    private fun baseUrl() = server.url("/").toString()

    private class MemoryAccountStore : AccountSessionStore {
        var account: AccountSession? = null
        var challenge: PendingEmailChallenge? = null
        var lastSavedPendingKey: String? = null
        var reauthenticationRequired = false
        var explicitLegacy = false
        var pendingDeletion: PendingAccountDeletion? = null
        var deletionCommitted = false
        override fun clientInstallationId() = "00000000-0000-0000-0000-000000000099"
        override fun loadAccountSession() = account
        override fun saveAccountSession(session: AccountSession) {
            account = session
            lastBaseUrl = session.baseUrl
            reauthenticationRequired = false
            deletionCommitted = false
            if (session.pendingRefreshIdempotencyKey != null) lastSavedPendingKey = session.pendingRefreshIdempotencyKey
        }
        override fun clearAccountSession() { account = null }
        override fun clearAccountSession(requireReauthentication: Boolean) {
            account = null
            reauthenticationRequired = requireReauthentication
            explicitLegacy = false
            pendingDeletion = null
            deletionCommitted = false
        }
        override fun accountReauthenticationRequired() = reauthenticationRequired
        override fun lastAccountBaseUrl() = lastBaseUrl
        override fun setAccountReauthenticationRequired(required: Boolean) {
            reauthenticationRequired = required
        }
        override fun explicitLegacyConnectionSelected() = explicitLegacy
        override fun setExplicitLegacyConnectionSelected(selected: Boolean) {
            explicitLegacy = selected
        }
        override fun loadPendingAccountDeletion() = pendingDeletion
        override fun savePendingAccountDeletion(pending: PendingAccountDeletion) {
            pendingDeletion = pending
        }
        override fun clearPendingAccountDeletion() { pendingDeletion = null }
        override fun accountDeletionCommitted() = deletionCommitted
        override fun setAccountDeletionCommitted(committed: Boolean) {
            deletionCommitted = committed
        }
        override fun completeAccountDeletion() {
            account = null
            pendingDeletion = null
            reauthenticationRequired = false
            explicitLegacy = false
            deletionCommitted = true
        }
        override fun loadPendingEmailChallenge() = challenge
        override fun savePendingEmailChallenge(challenge: PendingEmailChallenge) { this.challenge = challenge }
        override fun clearPendingEmailChallenge() { challenge = null }
        private var lastBaseUrl: String? = null
    }
}
