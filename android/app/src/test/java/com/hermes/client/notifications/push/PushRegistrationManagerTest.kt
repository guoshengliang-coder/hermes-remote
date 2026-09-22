package com.hermes.client.notifications.push

import com.hermes.client.data.auth.AccountControlConnection
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.AccountApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRegistrationManagerTest {
    private val identityA = PushIdentity("https://relay.example", "account-1", "installation-1")
    private val connection = AccountControlConnection("https://relay.example", "hga_bearer")

    private class FakePlatform(var availability: PushAvailability = PushAvailability.AVAILABLE) : PushPlatform {
        var token = "fcm-token-1"
        var fetches = 0
        var deletes = 0
        var initializeCalls = 0
        override fun initialize(): PushAvailability { initializeCalls++; return availability }
        override suspend fun fetchToken(): String { fetches++; return token }
        override suspend fun deleteToken() { deletes++ }
    }

    private inner class FakeAccounts : PushAccountSource {
        val flow = MutableStateFlow<PushIdentity?>(identityA)
        var reads = 0
        override val identity get() = flow.also { reads++ }
        override suspend fun controlConnection(): AccountControlConnection? = flow.value?.let { connection }
    }

    private class FakeApi : PushRegistrationApi {
        var supports = true
        var registerFailure: Exception? = null
        val registered = mutableListOf<String>()
        var unregisters = 0
        override suspend fun serverSupportsFcm(baseUrl: String) = supports
        override suspend fun register(connection: AccountControlConnection, token: String) {
            registerFailure?.let { throw it }
            registered += token
        }
        override suspend fun unregister(connection: AccountControlConnection) { unregisters++ }
    }

    private class MemoryRecord : PushRegistrationRecord {
        var value: String? = null
        override fun read() = value
        override fun write(fingerprint: String?) { value = fingerprint }
    }

    private val platform = FakePlatform()
    private val accounts = FakeAccounts()
    private val api = FakeApi()
    private val record = MemoryRecord()
    private val enabled = MutableStateFlow(true)

    private fun TestScope.started(): PushRegistrationManager =
        PushRegistrationManager(platform, accounts, api, record, enabled, backgroundScope).also {
            it.start()
            runCurrent()
        }

    @Test fun aBuildWithoutFirebaseValuesNeverReadsTheAccount() = runTest {
        platform.availability = PushAvailability.NOT_CONFIGURED
        val manager = started()
        assertEquals(PushStatus.NotConfigured, manager.status.value)
        assertEquals(0, accounts.reads)
        assertEquals(0, platform.fetches)
        assertTrue(api.registered.isEmpty())
    }

    @Test fun aPhoneWithoutGooglePlayServicesFallsBackSilently() = runTest {
        platform.availability = PushAvailability.NO_GOOGLE_PLAY_SERVICES
        val manager = started()
        assertEquals(PushStatus.NoGooglePlayServices, manager.status.value)
        assertEquals(0, accounts.reads)
        assertTrue(api.registered.isEmpty())
    }

    @Test fun signedInWithNotificationsOnRegistersOnce() = runTest {
        val manager = started()
        assertEquals(PushStatus.Enabled, manager.status.value)
        assertEquals(listOf("fcm-token-1"), api.registered)

        manager.reconcile()
        manager.onNewToken("fcm-token-1")
        runCurrent()
        assertEquals("an unchanged token is not uploaded again", 1, api.registered.size)
    }

    @Test fun aRotatedTokenIsUploaded() = runTest {
        val manager = started()
        manager.onNewToken("fcm-token-2")
        runCurrent()
        assertEquals(listOf("fcm-token-1", "fcm-token-2"), api.registered)
        assertEquals(PushStatus.Enabled, manager.status.value)
    }

    @Test fun notificationsOffNeverRegisters() = runTest {
        enabled.value = false
        val manager = started()
        assertEquals(PushStatus.Inactive, manager.status.value)
        assertTrue(api.registered.isEmpty())
        assertEquals(0, platform.fetches)
    }

    @Test fun turningNotificationsOffRemovesTheRegistration() = runTest {
        val manager = started()
        enabled.value = false
        runCurrent()
        assertEquals(1, api.unregisters)
        assertNull(record.value)
        assertEquals(PushStatus.Inactive, manager.status.value)

        enabled.value = true
        runCurrent()
        assertEquals("re-enabling registers again", 2, api.registered.size)
    }

    @Test fun signedOutNeverRegisters() = runTest {
        accounts.flow.value = null
        val manager = started()
        assertEquals(PushStatus.Inactive, manager.status.value)
        assertTrue(api.registered.isEmpty())
        assertEquals(0, platform.deletes)
    }

    @Test fun losingTheSessionDropsTheLocalToken() = runTest {
        val manager = started()
        accounts.flow.value = null
        runCurrent()
        assertEquals(1, platform.deletes)
        assertNull(record.value)
        assertEquals(PushStatus.Inactive, manager.status.value)
    }

    @Test fun anotherAccountReRegistersTheSameToken() = runTest {
        started()
        accounts.flow.value = identityA.copy(accountId = "account-2", installationId = "installation-2")
        runCurrent()
        assertEquals(listOf("fcm-token-1", "fcm-token-1"), api.registered)
    }

    @Test fun aServerWithoutThePushCapabilityIsNotAnError() = runTest {
        api.supports = false
        val manager = started()
        assertEquals(PushStatus.ServerUnsupported, manager.status.value)
        assertTrue(api.registered.isEmpty())
        assertEquals(0, platform.fetches)
    }

    @Test fun aGatewayWithoutTheRouteIsNotAnError() = runTest {
        api.registerFailure = AccountApiException(404, "HR-ACCOUNT-001", retryable = false, recoveryAction = "none")
        val manager = started()
        assertEquals(PushStatus.ServerUnsupported, manager.status.value)
        assertNull(record.value)
    }

    @Test fun aFailedRegistrationIsRetryableHrNotif002WithoutTheToken() = runTest {
        platform.token = "secret-fcm-token-abc"
        api.registerFailure = IllegalStateException("upload of secret-fcm-token-abc failed")
        val manager = started()

        val failed = manager.status.value as PushStatus.Failed
        assertEquals(AppErrorCode.PUSH_REGISTRATION_FAILED, failed.error.code)
        assertEquals("HR-NOTIF-002", failed.error.code.value)
        assertTrue(failed.error.retryable)
        assertFalse(failed.error.sanitizedDiagnostic().contains("secret-fcm-token-abc"))
        assertNull(record.value)

        api.registerFailure = null
        manager.retry()
        runCurrent()
        assertEquals(PushStatus.Enabled, manager.status.value)
        assertEquals(listOf("secret-fcm-token-abc"), api.registered)
    }

    @Test fun aTokenFetchFailureIsAlsoHrNotif002() = runTest {
        val broken = object : PushPlatform by platform {
            override suspend fun fetchToken(): String = throw java.io.IOException("SERVICE_NOT_AVAILABLE")
        }
        val manager = PushRegistrationManager(broken, accounts, api, record, enabled, backgroundScope)
        manager.start()
        runCurrent()
        assertEquals(AppErrorCode.PUSH_REGISTRATION_FAILED, (manager.status.value as PushStatus.Failed).error.code)
    }

    @Test fun aHangingTokenFetchTimesOutToHrNotif002AndReleasesTheLock() = runTest {
        // HG-102: with the app outside the VPN, getToken never completes. The row must not stay on
        // Registering, and Retry must not queue behind a lock the hung call still holds.
        var hang = true
        val hanging = object : PushPlatform by platform {
            override suspend fun fetchToken(): String {
                if (hang) awaitCancellation()
                return platform.fetchToken()
            }
        }
        val manager = PushRegistrationManager(hanging, accounts, api, record, enabled, backgroundScope)
        manager.start()
        runCurrent()
        assertEquals(PushStatus.Registering, manager.status.value)

        advanceTimeBy(FCM_TOKEN_TIMEOUT_MS + 1)
        runCurrent()
        val failed = manager.status.value as PushStatus.Failed
        assertEquals(AppErrorCode.PUSH_REGISTRATION_FAILED, failed.error.code)
        assertTrue(failed.error.retryable)
        assertTrue(failed.error.sanitizedDiagnostic().contains("stage=fcm_token"))
        assertFalse(failed.error.sanitizedDiagnostic().contains(platform.token))

        hang = false
        manager.retry()
        runCurrent()
        assertEquals(PushStatus.Enabled, manager.status.value)
        assertEquals(listOf(platform.token), api.registered)
    }

    @Test fun aHangingTokenDeleteDoesNotBlockSignOut() = runTest {
        val hanging = object : PushPlatform by platform {
            override suspend fun deleteToken() = awaitCancellation()
        }
        val hangingManager = PushRegistrationManager(hanging, accounts, api, record, enabled, backgroundScope)
        hangingManager.start()
        runCurrent()
        assertEquals(PushStatus.Enabled, hangingManager.status.value)

        hangingManager.unregisterForSignOut(connection)
        assertNull(record.value)
        assertEquals(1, api.unregisters)
        assertEquals(PushStatus.Inactive, hangingManager.status.value)
    }

    @Test fun signOutRemovesTheServerRecordAndTheToken() = runTest {
        val manager = started()
        manager.unregisterForSignOut(connection)
        assertEquals(1, api.unregisters)
        assertEquals(1, platform.deletes)
        assertNull(record.value)
        assertEquals(PushStatus.Inactive, manager.status.value)
    }

    @Test fun anEarlyTokenBeforeAccountStateIsKnownDoesNothing() = runTest {
        val manager = PushRegistrationManager(platform, accounts, api, record, enabled, backgroundScope)
        record.value = "previous"
        manager.reconcile(tokenOverride = "fcm-token-9")
        assertEquals("previous", record.value)
        assertEquals(0, platform.deletes)
        assertTrue(api.registered.isEmpty())
    }

    @Test fun theFailureCauseNeverCarriesTheToken() {
        val cause = pushFailureCause("register", RuntimeException("token=abc tok-123 rejected"), "tok-123")
        assertFalse(cause.contains("tok-123"))
        assertFalse(cause.contains("abc"))
        val serverCause = pushFailureCause("register", AccountApiException(500, "HR-ACCOUNT-001", true, "retry"), "tok")
        assertEquals("stage=register http=500 code=HR-ACCOUNT-001", serverCause)
    }
}
