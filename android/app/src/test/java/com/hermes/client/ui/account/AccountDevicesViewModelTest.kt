package com.hermes.client.ui.account

import com.hermes.client.data.auth.AccountSession
import com.hermes.client.data.auth.AccountClock
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.auth.AccountSessionStore
import com.hermes.client.data.auth.AccountTransportMode
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.auth.PendingEmailChallenge
import com.hermes.client.data.auth.PendingAccountDeletion
import com.hermes.client.data.network.AccountApi
import com.hermes.client.data.network.AccountApiException
import com.hermes.client.data.network.AccountDeviceDto
import com.hermes.client.data.network.AccountAuthCapabilityDto
import com.hermes.client.data.network.AccountCapabilitiesDto
import com.hermes.client.data.network.AccountDevicesResponseDto
import com.hermes.client.data.network.AccountEndToEndHealthDto
import com.hermes.client.data.network.AccountReauthenticationGrantDto
import com.hermes.client.data.network.EmailChallengeDto
import com.hermes.client.data.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDevicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun oneAvailableDeviceIsSelectedAndProbedAutomatically() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession())
        val manager = AccountSessionManager(store, api)
        val chat = mockk<ChatRepository>(relaxed = true)
        val device = device("mac-1")
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(listOf(device), 3)
        coEvery { api.selectDefaultDevice(any(), any(), "mac-1", any()) } returns
            device.copy(isDefault = true)
        coEvery { api.probeDevice(any(), any(), "mac-1") } returns Unit

        AccountDevicesViewModel(
            api,
            store,
            manager,
            mockk<CredentialStore>(relaxed = true),
            chat,
        )
        runCurrent()

        assertEquals("mac-1", manager.session.value?.selectedDeviceId)
        coVerify(exactly = 1) { api.probeDevice(any(), any(), "mac-1") }
        verify(exactly = 1) { chat.reconnect() }
    }

    @Test fun multipleAvailableDevicesRequireAnExplicitChoice() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession())
        val manager = AccountSessionManager(store, api)
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(
            listOf(device("mac-1"), device("mac-2")),
            3,
        )

        AccountDevicesViewModel(
            api,
            store,
            manager,
            mockk<CredentialStore>(relaxed = true),
            mockk<ChatRepository>(relaxed = true),
        )
        runCurrent()

        assertNull(manager.session.value?.selectedDeviceId)
        coVerify(exactly = 0) { api.selectDefaultDevice(any(), any(), any(), any()) }
    }

    @Test fun emptyDevicePageDiscoversAndConnectsTheFirstDesktopWithoutManualRefresh() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession())
        val manager = AccountSessionManager(store, api)
        val chat = mockk<ChatRepository>(relaxed = true)
        val device = device("mac-later")
        coEvery { api.devices(any(), any()) } returnsMany listOf(
            AccountDevicesResponseDto(emptyList(), 3),
            AccountDevicesResponseDto(listOf(device), 3),
        )
        coEvery { api.selectDefaultDevice(any(), any(), "mac-later", any()) } returns
            device.copy(isDefault = true)
        coEvery { api.probeDevice(any(), any(), "mac-later") } returns Unit
        val vm = AccountDevicesViewModel(
            api,
            store,
            manager,
            mockk<CredentialStore>(relaxed = true),
            chat,
        )
        runCurrent()

        vm.setDevicePageVisible(true)
        advanceTimeBy(AccountDevicesViewModel.EMPTY_DEVICE_POLL_MS)
        runCurrent()

        assertEquals("mac-later", manager.session.value?.selectedDeviceId)
        coVerify(exactly = 2) { api.devices(any(), any()) }
        coVerify(exactly = 1) { api.probeDevice(any(), any(), "mac-later") }
        verify(exactly = 1) { chat.reconnect() }
        vm.setDevicePageVisible(false)
    }

    @Test fun leavingAnEmptyDevicePageStopsAutomaticDiscovery() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession())
        val manager = AccountSessionManager(store, api)
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(emptyList(), 3)
        val vm = AccountDevicesViewModel(
            api,
            store,
            manager,
            mockk<CredentialStore>(relaxed = true),
            mockk<ChatRepository>(relaxed = true),
        )
        runCurrent()

        vm.setDevicePageVisible(true)
        vm.setDevicePageVisible(false)
        advanceTimeBy(AccountDevicesViewModel.EMPTY_DEVICE_POLL_MS * 2)
        runCurrent()

        coVerify(exactly = 1) { api.devices(any(), any()) }
    }

    @Test fun removedSelectionAutomaticallyHandsOffToTheSoleRemainingMac() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-removed"))
        val manager = AccountSessionManager(store, api)
        val chat = mockk<ChatRepository>(relaxed = true)
        val remaining = device("mac-remaining")
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(listOf(remaining), 3)
        coEvery { api.selectDefaultDevice(any(), any(), "mac-remaining", any()) } returns
            remaining.copy(isDefault = true)
        coEvery { api.probeDevice(any(), any(), "mac-remaining") } returns Unit

        AccountDevicesViewModel(api, store, manager, mockk(relaxed = true), chat)
        runCurrent()

        assertEquals("mac-remaining", manager.session.value?.selectedDeviceId)
        coVerify(exactly = 1) { api.selectDefaultDevice(any(), any(), "mac-remaining", any()) }
        coVerify(exactly = 1) { api.probeDevice(any(), any(), "mac-remaining") }
        verify(exactly = 1) { chat.reconnect() }
    }

    @Test fun removedSelectionWithMultipleRemainingMacsRequiresAChoiceAndRetiresOldRoute() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-removed"))
        val manager = AccountSessionManager(store, api)
        val chat = mockk<ChatRepository>(relaxed = true)
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(
            listOf(device("mac-a"), device("mac-b")),
            3,
        )

        AccountDevicesViewModel(api, store, manager, mockk(relaxed = true), chat)
        runCurrent()

        assertNull(manager.session.value?.selectedDeviceId)
        coVerify(exactly = 0) { api.selectDefaultDevice(any(), any(), any(), any()) }
        verify(exactly = 1) { chat.reconnect() }
    }

    @Test fun stillAccessibleSelectionIsPreservedWithoutReconnect() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-current"))
        val manager = AccountSessionManager(store, api)
        val chat = mockk<ChatRepository>(relaxed = true)
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(
            listOf(device("mac-current"), device("mac-other")),
            3,
        )

        AccountDevicesViewModel(api, store, manager, mockk(relaxed = true), chat)
        runCurrent()

        assertEquals("mac-current", manager.session.value?.selectedDeviceId)
        coVerify(exactly = 0) { api.selectDefaultDevice(any(), any(), any(), any()) }
        verify(exactly = 0) { chat.reconnect() }
    }

    @Test fun failedSoleReplacementProbeLeavesNoFalseSelectionAndRetiresRemovedRoute() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-removed"))
        val manager = AccountSessionManager(store, api)
        val chat = mockk<ChatRepository>(relaxed = true)
        val remaining = device("mac-unavailable")
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(listOf(remaining), 3)
        coEvery { api.selectDefaultDevice(any(), any(), "mac-unavailable", any()) } returns
            remaining.copy(isDefault = true)
        coEvery { api.probeDevice(any(), any(), "mac-unavailable") } throws AccountApiException(
            statusCode = 404,
            errorCode = "HR-BIND-011",
            retryable = false,
            recoveryAction = "select_device",
        )
        val vm = AccountDevicesViewModel(api, store, manager, mockk(relaxed = true), chat)
        runCurrent()

        assertNull(manager.session.value?.selectedDeviceId)
        assertEquals("HR-BIND-011", vm.state.value.error?.code)
        assertEquals(AccountRetryAction.SELECT_DEVICE, vm.state.value.error?.retryAction)
        coVerify(exactly = 1) { api.probeDevice(any(), any(), "mac-unavailable") }
        verify(exactly = 1) { chat.reconnect() }
    }

    @Test fun failedManualSwitchProbePreservesTheStillValidCurrentMac() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-current"))
        val manager = AccountSessionManager(store, api)
        val chat = mockk<ChatRepository>(relaxed = true)
        val current = device("mac-current")
        val target = device("mac-target")
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(listOf(current, target), 3)
        coEvery { api.selectDefaultDevice(any(), any(), "mac-target", any()) } returns
            target.copy(isDefault = true)
        coEvery { api.probeDevice(any(), any(), "mac-target") } throws AccountApiException(
            statusCode = 503,
            errorCode = "HR-CONN-005",
            retryable = true,
            recoveryAction = "retry",
        )
        val vm = AccountDevicesViewModel(api, store, manager, mockk(relaxed = true), chat)
        runCurrent()

        vm.useDevice(target)
        runCurrent()

        assertEquals("mac-current", manager.session.value?.selectedDeviceId)
        assertEquals("HR-CONN-005", vm.state.value.error?.code)
        assertEquals(AccountRetryAction.SELECT_DEVICE, vm.state.value.error?.retryAction)

        vm.retryError()
        runCurrent()

        coVerify(exactly = 2) { api.probeDevice(any(), any(), "mac-target") }
        verify(exactly = 0) { chat.reconnect() }
    }

    @Test fun deviceListSessionFamilyRejectionRequiresSignInAndStopsCurrentTransport() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-current"))
        val manager = AccountSessionManager(store, api)
        val chat = mockk<ChatRepository>(relaxed = true)
        coEvery { api.devices(any(), any()) } throws AccountApiException(
            statusCode = 403,
            errorCode = "HR-AUTH-004",
            retryable = false,
            recoveryAction = "sign_in",
        )

        val vm = AccountDevicesViewModel(api, store, manager, mockk(relaxed = true), chat)
        runCurrent()

        assertNull(manager.session.value)
        assertTrue(manager.requiresAccountReauthentication())
        assertEquals(AccountStage.SIGNED_OUT, vm.state.value.stage)
        assertEquals("HR-AUTH-004", vm.state.value.error?.code)
        verify(exactly = 1) { chat.disconnect() }
    }

    @Test fun deviceListRecentAuthFailurePreservesTheCurrentAccountTransport() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-current"))
        val manager = AccountSessionManager(store, api)
        val chat = mockk<ChatRepository>(relaxed = true)
        coEvery { api.devices(any(), any()) } throws AccountApiException(
            statusCode = 403,
            errorCode = "HR-AUTH-006",
            retryable = false,
            recoveryAction = "reauthenticate",
        )

        val vm = AccountDevicesViewModel(api, store, manager, mockk(relaxed = true), chat)
        runCurrent()

        assertEquals("mac-current", manager.session.value?.selectedDeviceId)
        assertFalse(manager.requiresAccountReauthentication())
        assertEquals(AccountStage.SIGNED_IN, vm.state.value.stage)
        assertEquals("HR-AUTH-006", vm.state.value.error?.code)
        verify(exactly = 0) { chat.disconnect() }
        verify(exactly = 0) { chat.reconnect() }
    }

    @Test fun currentDeviceAuthorizationLossClearsOnlyItsSelectionAndStopsTransport() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-current"))
        val manager = AccountSessionManager(store, api)
        val chat = mockk<ChatRepository>(relaxed = true)
        val current = device("mac-current")
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(listOf(current), 3)
        coEvery { api.selectDefaultDevice(any(), any(), "mac-current", any()) } throws
            AccountApiException(
                statusCode = 404,
                errorCode = "HR-BIND-011",
                retryable = false,
                recoveryAction = "select_device",
            )
        val vm = AccountDevicesViewModel(api, store, manager, mockk(relaxed = true), chat)
        runCurrent()

        vm.useDevice(current)
        runCurrent()

        assertNull(manager.session.value?.selectedDeviceId)
        assertFalse(manager.requiresAccountReauthentication())
        assertEquals("HR-BIND-011", vm.state.value.error?.code)
        verify(exactly = 1) { chat.disconnect() }
    }

    @Test fun validPendingChallengeRestoresCodeEntryAndItsDeadlinesAfterProcessDeath() = runTest(dispatcher) {
        val now = Instant.parse("2026-09-08T08:00:00Z")
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(null, pendingChallenge(now))
        coEvery { api.capabilities(any()) } returns emailCapabilities()
        val vm = AccountDevicesViewModel(
            api,
            store,
            AccountSessionManager(store, api),
            mockk(relaxed = true),
            mockk(relaxed = true),
            AccountClock { now },
        )
        runCurrent()

        assertEquals(AccountStage.CODE_SENT, vm.state.value.stage)
        assertEquals("person@example.com", vm.state.value.email)
        assertEquals(600, vm.state.value.codeExpiresInSeconds)
        assertEquals(60, vm.state.value.resendInSeconds)
        assertNull(vm.state.value.error)
    }

    @Test fun reauthenticationUsesTheLastAccountGatewayInsteadOfARetainedLegacyRelay() = runTest(dispatcher) {
        val accountGateway = "https://account.example"
        val legacyGateway = "https://legacy.example"
        val now = Instant.parse("2026-09-08T08:00:00Z")
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(
            account = null,
            lastBaseUrl = accountGateway,
            reauthenticationRequired = true,
        )
        val legacy = mockk<CredentialStore>()
        every { legacy.load() } returns GatewayConfig(legacyGateway, "legacy-token")
        coEvery { api.capabilities(accountGateway) } returns emailCapabilities()
        coEvery { api.requestEmailChallenge(accountGateway, any(), any()) } returns EmailChallengeDto(
            challengeId = "challenge-account-origin",
            expiresAt = now.plusSeconds(600).toString(),
            resendAfter = now.plusSeconds(60).toString(),
        )
        val vm = AccountDevicesViewModel(
            api,
            store,
            AccountSessionManager(store, api),
            legacy,
            mockk(relaxed = true),
            AccountClock { now },
        )
        runCurrent()

        vm.onEmailChange("person@example.com")
        vm.sendCode()
        runCurrent()

        coVerify(exactly = 2) { api.capabilities(accountGateway) }
        coVerify(exactly = 1) {
            api.requestEmailChallenge(accountGateway, "person@example.com", any())
        }
        coVerify(exactly = 0) { api.capabilities(legacyGateway) }
        assertEquals("challenge-account-origin", store.pending?.challengeId)
        assertTrue(store.reauthenticationRequired)
    }

    @Test fun expiredPendingChallengeIsClearedDuringProcessRecovery() = runTest(dispatcher) {
        val now = Instant.parse("2026-09-08T08:20:00Z")
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(
            null,
            pendingChallenge(Instant.parse("2026-09-08T08:00:00Z")),
        )
        coEvery { api.capabilities(any()) } returns emailCapabilities()
        val vm = AccountDevicesViewModel(
            api,
            store,
            AccountSessionManager(store, api),
            mockk(relaxed = true),
            mockk(relaxed = true),
            AccountClock { now },
        )
        runCurrent()

        assertEquals(AccountStage.SIGNED_OUT, vm.state.value.stage)
        assertEquals("HR-AUTH-009", vm.state.value.error?.code)
        assertNull(store.pending)
    }

    @Test fun resendIsBlockedUntilServerDeadlineThenPersistsANewChallenge() = runTest(dispatcher) {
        val started = Instant.parse("2026-09-08T08:00:00Z")
        var now = started
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(null, pendingChallenge(started))
        coEvery { api.capabilities(any()) } returns emailCapabilities()
        coEvery { api.requestEmailChallenge(any(), any(), any()) } returns EmailChallengeDto(
            challengeId = "challenge-2",
            expiresAt = "2026-09-08T08:11:00Z",
            resendAfter = "2026-09-08T08:02:00Z",
        )
        val vm = AccountDevicesViewModel(
            api,
            store,
            AccountSessionManager(store, api),
            mockk(relaxed = true),
            mockk(relaxed = true),
            AccountClock { now },
        )
        runCurrent()

        vm.resendCode()
        runCurrent()
        coVerify(exactly = 0) { api.requestEmailChallenge(any(), any(), any()) }

        now = started.plusSeconds(60)
        vm.resendCode()
        runCurrent()

        coVerify(exactly = 1) { api.requestEmailChallenge(any(), "person@example.com", any()) }
        assertEquals("challenge-2", store.pending?.challengeId)
        assertEquals(AccountStage.CODE_SENT, vm.state.value.stage)
        assertTrue(vm.state.value.resendInSeconds > 0)
    }

    @Test fun retryAfterEmailDeliveryFailureActuallyRequestsANewCode() = runTest(dispatcher) {
        val now = Instant.parse("2026-09-08T08:00:00Z")
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(null)
        var attempts = 0
        coEvery { api.capabilities(any()) } returns emailCapabilities()
        coEvery { api.requestEmailChallenge(any(), any(), any()) } answers {
            attempts += 1
            if (attempts == 1) {
                throw AccountApiException(
                    statusCode = 503,
                    errorCode = "HR-AUTH-010",
                    retryable = true,
                    recoveryAction = "retry",
                )
            }
            EmailChallengeDto(
                challengeId = "challenge-retried",
                expiresAt = now.plusSeconds(600).toString(),
                resendAfter = now.plusSeconds(60).toString(),
            )
        }
        val vm = AccountDevicesViewModel(
            api,
            store,
            AccountSessionManager(store, api),
            mockk(relaxed = true),
            mockk(relaxed = true),
            AccountClock { now },
        )
        runCurrent()
        vm.onEmailChange("person@example.com")

        vm.sendCode()
        runCurrent()

        assertEquals("HR-AUTH-010", vm.state.value.error?.code)
        assertEquals(AccountRetryAction.SEND_CODE, vm.state.value.error?.retryAction)
        assertNull(store.pending)

        vm.retryError()
        runCurrent()

        coVerify(exactly = 2) {
            api.requestEmailChallenge(any(), "person@example.com", any())
        }
        assertEquals("challenge-retried", store.pending?.challengeId)
        assertEquals(AccountStage.CODE_SENT, vm.state.value.stage)
        assertNull(vm.state.value.error)
    }

    @Test fun locallyExpiredChallengeNeverSubmitsTheTypedCode() = runTest(dispatcher) {
        val started = Instant.parse("2026-09-08T08:00:00Z")
        var now = started
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(null, pendingChallenge(started))
        coEvery { api.capabilities(any()) } returns emailCapabilities()
        val vm = AccountDevicesViewModel(
            api,
            store,
            AccountSessionManager(store, api),
            mockk(relaxed = true),
            mockk(relaxed = true),
            AccountClock { now },
        )
        runCurrent()
        vm.onCodeChange("123456")

        now = started.plusSeconds(600)
        vm.verifyCode()
        runCurrent()

        assertEquals(AccountStage.SIGNED_OUT, vm.state.value.stage)
        assertEquals("HR-AUTH-009", vm.state.value.error?.code)
        assertFalse(store.pending != null)
        coVerify(exactly = 0) {
            api.exchangeEmailCode(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun permanentDeletionRequiresCapabilityAndPersistsReplayMaterialBeforeDelete() = runTest(dispatcher) {
        val now = Instant.parse("2026-09-08T08:00:00Z")
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-current"))
        val manager = AccountSessionManager(store, api)
        val chat = mockk<ChatRepository>(relaxed = true)
        coEvery { api.capabilities(any()) } returns emailCapabilities(accountDeletion = true)
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(
            listOf(device("mac-current")),
            3,
        )
        coEvery { api.requestEmailReauthenticationChallenge(any(), any(), any()) } returns
            EmailChallengeDto(
                challengeId = "delete-challenge",
                expiresAt = now.plusSeconds(600).toString(),
                resendAfter = now.plusSeconds(60).toString(),
            )
        coEvery { api.reauthenticateEmail(any(), any(), any(), any(), any(), any(), any()) } returns
            AccountReauthenticationGrantDto(
                grant = "hgg_delete",
                scope = "account.delete",
                expiresAt = now.plusSeconds(600).toString(),
            )
        coEvery { api.deleteAccount(any(), any(), any(), any()) } answers {
            assertTrue(store.pendingDeletion?.isReadyToCommit == true)
            assertEquals("hgg_delete", store.pendingDeletion?.grant)
        }
        val vm = AccountDevicesViewModel(
            api,
            store,
            manager,
            mockk(relaxed = true),
            chat,
            AccountClock { now },
        )
        runCurrent()

        assertTrue(vm.state.value.accountDeletionEnabled)
        vm.beginAccountDeletion()
        vm.onAccountDeletionConfirmationChange("DELETE")
        vm.onAccountDeletionAcknowledgedChange(true)
        vm.requestAccountDeletionCode()
        runCurrent()
        vm.onAccountDeletionCodeChange("123456")
        vm.verifyAndDeleteAccount()
        runCurrent()

        assertEquals(AccountStage.ACCOUNT_DELETION_COMMITTED, vm.state.value.stage)
        assertEquals(AccountTransportMode.ACCOUNT_DELETION_COMMITTED, manager.transportMode())
        assertNull(store.pendingDeletion)
        coVerify(exactly = 1) {
            api.reauthenticateEmail(
                any(),
                "delete-challenge",
                "person@example.com",
                "123456",
                "account.delete",
                any(),
                any(),
            )
        }
        verify(exactly = 1) { chat.disconnect() }
    }

    @Test fun permanentDeletionChallengeRequiresExactTypedConfirmationAndAcknowledgement() = runTest(dispatcher) {
        val now = Instant.parse("2026-09-08T08:00:00Z")
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-current"))
        coEvery { api.capabilities(any()) } returns emailCapabilities(accountDeletion = true)
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(
            listOf(device("mac-current")),
            3,
        )
        coEvery { api.requestEmailReauthenticationChallenge(any(), any(), any()) } returns
            EmailChallengeDto("delete-challenge", now.plusSeconds(600).toString(), now.plusSeconds(60).toString())
        val vm = AccountDevicesViewModel(
            api,
            store,
            AccountSessionManager(store, api),
            mockk(relaxed = true),
            mockk(relaxed = true),
            AccountClock { now },
        )
        runCurrent()
        vm.beginAccountDeletion()

        vm.onAccountDeletionConfirmationChange("delete")
        vm.onAccountDeletionAcknowledgedChange(true)
        vm.requestAccountDeletionCode()
        runCurrent()
        vm.onAccountDeletionConfirmationChange("DELETE")
        vm.onAccountDeletionAcknowledgedChange(false)
        vm.requestAccountDeletionCode()
        runCurrent()
        coVerify(exactly = 0) { api.requestEmailReauthenticationChallenge(any(), any(), any()) }

        vm.onAccountDeletionAcknowledgedChange(true)
        vm.requestAccountDeletionCode()
        runCurrent()

        coVerify(exactly = 1) {
            api.requestEmailReauthenticationChallenge(any(), "person@example.com", any())
        }
    }

    @Test fun ambiguousDeletionResponseRetainsAndReusesTheExactMutation() = runTest(dispatcher) {
        val now = Instant.parse("2026-09-08T08:00:00Z")
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-current"))
        val manager = AccountSessionManager(store, api)
        coEvery { api.capabilities(any()) } returns emailCapabilities(accountDeletion = true)
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(
            listOf(device("mac-current")),
            3,
        )
        coEvery { api.requestEmailReauthenticationChallenge(any(), any(), any()) } returns
            EmailChallengeDto("delete-challenge", now.plusSeconds(600).toString(), now.toString())
        coEvery { api.reauthenticateEmail(any(), any(), any(), any(), any(), any(), any()) } returns
            AccountReauthenticationGrantDto("hgg_delete", "account.delete", now.plusSeconds(600).toString())
        coEvery { api.deleteAccount(any(), any(), any(), any()) } throws java.io.IOException("lost response")
        val vm = AccountDevicesViewModel(
            api,
            store,
            manager,
            mockk(relaxed = true),
            mockk(relaxed = true),
            AccountClock { now },
        )
        runCurrent()
        vm.beginAccountDeletion()
        vm.onAccountDeletionConfirmationChange("DELETE")
        vm.onAccountDeletionAcknowledgedChange(true)
        vm.requestAccountDeletionCode()
        runCurrent()
        vm.onAccountDeletionCodeChange("123456")
        vm.verifyAndDeleteAccount()
        runCurrent()

        val retained = store.pendingDeletion!!
        assertTrue(retained.isReadyToCommit)
        assertEquals(AccountDeletionStage.RETRY_COMMIT, vm.state.value.accountDeletion?.stage)
        assertEquals("HR-ACCOUNT-002", vm.state.value.accountDeletion?.error?.code)

        coEvery { api.deleteAccount(any(), any(), any(), any()) } returns Unit
        vm.retryAccountDeletionCommit()
        runCurrent()

        coVerify(exactly = 2) {
            api.deleteAccount(
                retained.baseUrl,
                "hga_access",
                retained.grant!!,
                retained.deletionIdempotencyKey!!,
            )
        }
        assertEquals(AccountStage.ACCOUNT_DELETION_COMMITTED, vm.state.value.stage)
    }

    @Test fun processRecoveryTreatsAlreadyDeletingAccountAsCommitted() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-current"))
        store.pendingDeletion = readyDeletion()
        coEvery { api.deleteAccount(any(), any(), any(), any()) } throws AccountApiException(
            statusCode = 403,
            errorCode = "HR-ACCOUNT-012",
            retryable = false,
            recoveryAction = "none",
        )
        val manager = AccountSessionManager(store, api)

        val vm = AccountDevicesViewModel(
            api,
            store,
            manager,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        runCurrent()

        assertEquals(AccountStage.ACCOUNT_DELETION_COMMITTED, vm.state.value.stage)
        assertTrue(store.deletionCommitted)
        assertNull(manager.session.value)
        coVerify(exactly = 0) { api.devices(any(), any()) }
    }

    @Test fun invalidDeletionOtpClearsOnlyDeletionProofAndPreservesAccountSession() = runTest(dispatcher) {
        val now = Instant.parse("2026-09-08T08:00:00Z")
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-current"))
        val manager = AccountSessionManager(store, api)
        coEvery { api.capabilities(any()) } returns emailCapabilities(accountDeletion = true)
        coEvery { api.devices(any(), any()) } returns AccountDevicesResponseDto(
            listOf(device("mac-current")),
            3,
        )
        coEvery { api.requestEmailReauthenticationChallenge(any(), any(), any()) } returns
            EmailChallengeDto("delete-challenge", now.plusSeconds(600).toString(), now.toString())
        coEvery { api.reauthenticateEmail(any(), any(), any(), any(), any(), any(), any()) } throws
            AccountApiException(401, "HR-AUTH-009", false, "request_new_code")
        val vm = AccountDevicesViewModel(
            api,
            store,
            manager,
            mockk(relaxed = true),
            mockk(relaxed = true),
            AccountClock { now },
        )
        runCurrent()
        vm.beginAccountDeletion()
        vm.onAccountDeletionConfirmationChange("DELETE")
        vm.onAccountDeletionAcknowledgedChange(true)
        vm.requestAccountDeletionCode()
        runCurrent()
        vm.onAccountDeletionCodeChange("123456")
        vm.verifyAndDeleteAccount()
        runCurrent()

        assertEquals("account-1", manager.session.value?.accountId)
        assertNull(store.pendingDeletion)
        assertEquals(AccountDeletionStage.CONFIRMING, vm.state.value.accountDeletion?.stage)
        assertEquals("HR-AUTH-009", vm.state.value.accountDeletion?.error?.code)
    }

    @Test fun accountDeletionCapabilityRemainsAvailableWhenDeviceListingFails() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(accountSession(selectedDeviceId = "mac-current"))
        coEvery { api.capabilities(any()) } returns emailCapabilities(accountDeletion = true)
        coEvery { api.devices(any(), any()) } throws AccountApiException(
            503,
            "HR-ACCOUNT-002",
            true,
            "retry",
        )
        val vm = AccountDevicesViewModel(
            api,
            store,
            AccountSessionManager(store, api),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        runCurrent()

        assertTrue(vm.state.value.accountDeletionEnabled)
        assertEquals("HR-ACCOUNT-002", vm.state.value.error?.code)
    }

    @Test fun choosingAnotherAccountAfterDeletionStaysClosedToRetainedLegacyCredentials() = runTest(dispatcher) {
        val api = mockk<AccountApi>()
        val store = MemoryAccountStore(null, lastBaseUrl = "https://relay.example").apply {
            deletionCommitted = true
        }
        coEvery { api.capabilities(any()) } returns emailCapabilities()
        val manager = AccountSessionManager(store, api)
        val vm = AccountDevicesViewModel(
            api,
            store,
            manager,
            mockk<CredentialStore> {
                every { load() } returns GatewayConfig("https://legacy.example", "legacy-token")
            },
            mockk(relaxed = true),
        )
        runCurrent()
        assertEquals(AccountStage.ACCOUNT_DELETION_COMMITTED, vm.state.value.stage)

        vm.startNewAccountSignIn()
        runCurrent()

        assertEquals(AccountStage.SIGNED_OUT, vm.state.value.stage)
        assertEquals(AccountTransportMode.REAUTHENTICATION_REQUIRED, manager.transportMode())
        coVerify(exactly = 1) { api.capabilities("https://relay.example") }
        coVerify(exactly = 0) { api.capabilities("https://legacy.example") }
    }

    private fun accountSession(selectedDeviceId: String? = null) = AccountSession(
        baseUrl = "https://relay.example",
        accountId = "account-1",
        accountEmail = "person@example.com",
        installationId = "installation-1",
        installationDisplayName = "Pixel",
        accessToken = "hga_access",
        accessExpiresAt = "2099-01-01T00:00:00Z",
        refreshToken = "hgr_refresh",
        refreshExpiresAt = "2099-02-01T00:00:00Z",
        selectedDeviceId = selectedDeviceId,
    )

    private fun pendingChallenge(issuedAt: Instant) = PendingEmailChallenge(
        baseUrl = "https://relay.example",
        email = "person@example.com",
        challengeId = "challenge-1",
        expiresAt = issuedAt.plusSeconds(600).toString(),
        resendAfter = issuedAt.plusSeconds(60).toString(),
        exchangeIdempotencyKey = "exchange-key-1",
    )

    private fun emailCapabilities(accountDeletion: Boolean = false) = AccountCapabilitiesDto(
        accountAuth = AccountAuthCapabilityDto(
            enabled = true,
            providers = listOf("email_otp"),
            android = true,
            accountDeletion = accountDeletion,
        ),
    )

    private fun readyDeletion() = PendingAccountDeletion(
        baseUrl = "https://relay.example",
        accountId = "account-1",
        email = "person@example.com",
        challengeId = "delete-challenge",
        expiresAt = "2099-01-01T00:10:00Z",
        resendAfter = "2099-01-01T00:01:00Z",
        reauthenticationIdempotencyKey = "reauth-key",
        grant = "hgg_delete",
        deletionIdempotencyKey = "delete-key",
    )

    private fun device(id: String) = AccountDeviceDto(
        id = "binding-$id",
        generation = 1,
        deviceId = id,
        desktopDisplayName = "Mac $id",
        endToEnd = AccountEndToEndHealthDto(healthy = true),
        access = "owner",
    )

    private class MemoryAccountStore(
        private var account: AccountSession?,
        var pending: PendingEmailChallenge? = null,
        private var lastBaseUrl: String? = account?.baseUrl,
        var reauthenticationRequired: Boolean = false,
    ) : AccountSessionStore {
        var pendingDeletion: PendingAccountDeletion? = null
        var deletionCommitted: Boolean = false
        var explicitLegacy: Boolean = false
        override fun clientInstallationId() = "00000000-0000-0000-0000-000000000099"
        override fun loadAccountSession() = account
        override fun saveAccountSession(session: AccountSession) {
            account = session
            lastBaseUrl = session.baseUrl
            reauthenticationRequired = false
            deletionCommitted = false
        }
        override fun clearAccountSession() { account = null }
        override fun clearAccountSession(requireReauthentication: Boolean) {
            account = null
            reauthenticationRequired = requireReauthentication
            pendingDeletion = null
            deletionCommitted = false
            explicitLegacy = false
        }
        override fun lastAccountBaseUrl() = lastBaseUrl
        override fun accountReauthenticationRequired() = reauthenticationRequired
        override fun setAccountReauthenticationRequired(required: Boolean) {
            reauthenticationRequired = required
        }
        override fun loadPendingEmailChallenge(): PendingEmailChallenge? = pending
        override fun savePendingEmailChallenge(challenge: PendingEmailChallenge) { pending = challenge }
        override fun clearPendingEmailChallenge() { pending = null }
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
    }
}
