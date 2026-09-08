package com.hermes.client.data.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedCredentialStoreTest {
    private val store = EncryptedCredentialStore(ApplicationProvider.getApplicationContext())

    @Test fun save_then_load_round_trips() {
        store.clear()
        store.save(GatewayConfig(baseUrl = "http://hermes-mac:9119", token = "abc"))
        val loaded = store.load()!!
        assertEquals("http://hermes-mac:9119", loaded.baseUrl)
        assertEquals("abc", loaded.token)
        // Authentication now travels in a header or short-lived ticket, never in a persisted URL.
        // nosemgrep: javascript.lang.security.detect-insecure-websocket.detect-insecure-websocket -- private HTTP compatibility is the behavior under test.
        assertEquals("ws://hermes-mac:9119/api/ws", loaded.wsBase)
    }

    @Test fun clear_removes_config() {
        store.save(GatewayConfig("http://x:1", "t"))
        store.clear()
        assertNull(store.load())
    }

    @Test fun unexpectedAccountInvalidationPersistsUntilAnExplicitRecovery() {
        store.clearAccountSession(requireReauthentication = false)
        store.saveAccountSession(
            AccountSession(
                baseUrl = "https://relay.example",
                accountId = "account-1",
                installationId = "installation-1",
                installationDisplayName = "Pixel",
                accessToken = "access",
                accessExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "refresh",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
            ),
        )
        store.clearAccountSession(requireReauthentication = true)

        val reopened = EncryptedCredentialStore(ApplicationProvider.getApplicationContext())
        assertTrue(reopened.accountReauthenticationRequired())
        assertEquals("https://relay.example", reopened.lastAccountBaseUrl())

        reopened.saveAccountSession(
            AccountSession(
                baseUrl = "https://relay.example",
                accountId = "account-1",
                installationId = "installation-1",
                installationDisplayName = "Pixel",
                accessToken = "access-2",
                accessExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "refresh-2",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
            ),
        )

        assertFalse(
            EncryptedCredentialStore(ApplicationProvider.getApplicationContext())
                .accountReauthenticationRequired(),
        )
        reopened.clearAccountSession(requireReauthentication = false)
    }

    @Test fun explicitLegacyConnectionSelectionPersistsAndAccountInvalidationClearsIt() {
        store.clearAccountSession(requireReauthentication = false)
        store.setExplicitLegacyConnectionSelected(true)

        val reopened = EncryptedCredentialStore(ApplicationProvider.getApplicationContext())
        assertTrue(reopened.explicitLegacyConnectionSelected())

        reopened.clearAccountSession(requireReauthentication = true)
        val afterInvalidation = EncryptedCredentialStore(ApplicationProvider.getApplicationContext())
        assertFalse(afterInvalidation.explicitLegacyConnectionSelected())
        assertTrue(afterInvalidation.accountReauthenticationRequired())
        afterInvalidation.clearAccountSession(requireReauthentication = false)
    }

    @Test fun pendingAccountDeletionRoundTripsWithoutPersistingUserConfirmationOrCode() {
        store.clearAccountSession(requireReauthentication = false)
        val pending = PendingAccountDeletion(
            baseUrl = "https://relay.example",
            accountId = "account-1",
            email = "person@example.com",
            challengeId = "challenge-1",
            expiresAt = "2099-01-01T00:10:00Z",
            resendAfter = "2099-01-01T00:01:00Z",
            reauthenticationIdempotencyKey = "reauth-key",
            grant = "hgg_delete",
            deletionIdempotencyKey = "delete-key",
        )

        store.savePendingAccountDeletion(pending)

        assertEquals(
            pending,
            EncryptedCredentialStore(ApplicationProvider.getApplicationContext())
                .loadPendingAccountDeletion(),
        )
        store.clearAccountSession(requireReauthentication = false)
    }

    @Test fun committedDeletionAtomicallyClearsAccountSecretsAndPersistsTerminalGate() {
        store.clearAccountSession(requireReauthentication = false)
        store.saveAccountSession(
            AccountSession(
                baseUrl = "https://relay.example",
                accountId = "account-1",
                installationId = "installation-1",
                installationDisplayName = "Pixel",
                accessToken = "access",
                accessExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "refresh",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
            ),
        )
        store.savePendingAccountDeletion(
            PendingAccountDeletion(
                baseUrl = "https://relay.example",
                accountId = "account-1",
                email = "person@example.com",
                challengeId = "challenge-1",
                expiresAt = "2099-01-01T00:10:00Z",
                resendAfter = "2099-01-01T00:01:00Z",
                reauthenticationIdempotencyKey = "reauth-key",
                grant = "hgg_delete",
                deletionIdempotencyKey = "delete-key",
            ),
        )

        store.completeAccountDeletion()

        val reopened = EncryptedCredentialStore(ApplicationProvider.getApplicationContext())
        assertNull(reopened.loadAccountSession())
        assertNull(reopened.loadPendingAccountDeletion())
        assertTrue(reopened.accountDeletionCommitted())
        reopened.clearAccountSession(requireReauthentication = false)
    }
}
