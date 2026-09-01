package com.hermes.client.ui.startup

import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ConnectivityChecker
import com.hermes.client.data.network.GatewayProbeResult
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ModelRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.data.repository.ViewModeStore
import com.hermes.client.ui.sessions.ViewMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupViewModelTest {
    private val credentials = mockk<CredentialStore>(relaxed = true)
    private val connectivity = mockk<ConnectivityChecker>()
    private val chat = mockk<ChatRepository>(relaxed = true)
    private val sessions = mockk<SessionRepository>(relaxed = true)
    private val profiles = mockk<ProfileManager>(relaxed = true)
    private val rest = mockk<HermesRestApi>()
    private val models = mockk<ModelRepository>(relaxed = true)
    private val viewModes = mockk<ViewModeStore>()
    private val runtimes = mockk<SessionRuntimeStore>(relaxed = true)
    private val foregroundRecovery = mockk<ForegroundRecoveryCoordinator>()
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val config = GatewayConfig("https://relay.example", "token")

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { credentials.load() } returns config
        every { connectivity.isOnline() } returns true
        every { chat.connectionState } returns connection
        coEvery { sessions.listAllProfiles() } returns emptyList()
        coEvery { rest.probeStatusFor(config.baseUrl, config.token) } returns GatewayProbeResult.Reachable
        every { viewModes.mode } returns flowOf(ViewMode.SESSIONS)
        coEvery { foregroundRecovery.recoverActive() } returns null
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = StartupViewModel(
        credentials,
        connectivity,
        chat,
        sessions,
        profiles,
        rest,
        models,
        viewModes,
        runtimes,
        foregroundRecovery,
    )

    @Test fun firstLaunchWithoutConfigurationSkipsStartupScreen() = runTest {
        every { credentials.load() } returns null
        val vm = vm()

        vm.onActivityCreated(processColdStart = true)
        vm.onForeground()
        runCurrent()

        assertEquals(StartupUiState.Hidden, vm.state.value)
        verify(exactly = 0) { chat.connect() }
    }

    @Test fun configuredColdStartWaitsForGatewayReadyInitialSessionsAndMinimumBrandMoment() = runTest {
        val initialSessions = CompletableDeferred<List<com.hermes.client.domain.Session>>()
        coEvery { sessions.listAllProfiles() } coAnswers { initialSessions.await() }
        val vm = vm()

        vm.onActivityCreated(processColdStart = true)
        runCurrent()

        assertTrue(vm.state.value is StartupUiState.Loading)
        verify(exactly = 1) { chat.connect() }

        connection.value = ConnectionState.Connected
        runCurrent()
        assertEquals(StartupPhase.INITIAL_DATA, (vm.state.value as StartupUiState.Loading).phase)
        coVerify(exactly = 1) { profiles.refresh() }

        initialSessions.complete(emptyList())
        runCurrent()
        assertEquals(StartupPhase.READY, (vm.state.value as StartupUiState.Loading).phase)

        advanceTimeBy(maxOf(StartupViewModel.MINIMUM_COLD_START_MS, StartupViewModel.SUCCESS_COMPLETION_MS))
        runCurrent()
        assertEquals(StartupUiState.Hidden, vm.state.value)
    }

    @Test fun coldStartInitialSessionFailureStaysOnStartupWithRetryableCode() = runTest {
        coEvery { sessions.listAllProfiles() } throws RuntimeException("sessions unavailable")
        val vm = vm()

        vm.onActivityCreated(processColdStart = true)
        runCurrent()
        connection.value = ConnectionState.Connected
        runCurrent()

        val failed = vm.state.value as StartupUiState.Failed
        assertEquals(StartupReason.COLD_START, failed.reason)
        assertEquals(StartupFailure.INITIAL_DATA_FAILED, failed.failure)
        assertEquals("HR-RPC-001", failed.failure.code)
    }

    @Test fun retryAfterInitialSessionFailureRepeatsColdPreloadWithoutReconnectingHealthySocket() = runTest {
        connection.value = ConnectionState.Connected
        coEvery { sessions.listAllProfiles() } throws RuntimeException("first failure") andThen emptyList()
        val vm = vm()

        vm.onActivityCreated(processColdStart = true)
        runCurrent()
        assertTrue(vm.state.value is StartupUiState.Failed)

        vm.retry()
        runCurrent()

        assertEquals(StartupPhase.READY, (vm.state.value as StartupUiState.Loading).phase)
        coVerify(exactly = 2) { sessions.listAllProfiles() }
        verify(exactly = 0) { chat.reconnect() }
    }

    @Test fun healthyHotStartLeavesCurrentScreenVisible() = runTest {
        connection.value = ConnectionState.Connected
        val vm = vm()

        vm.onActivityCreated(processColdStart = false)
        vm.onForeground()
        runCurrent()

        assertEquals(StartupUiState.Hidden, vm.state.value)
        verify(exactly = 0) { chat.connect() }
        verify(exactly = 0) { chat.reconnect() }
        coVerify(exactly = 0) { sessions.listAllProfiles() }
    }

    @Test fun disconnectedHotStartUsesDebounceThenRestoresInPlace() = runTest {
        val destinationRecovery = CompletableDeferred<Boolean?>()
        coEvery { foregroundRecovery.recoverActive() } coAnswers { destinationRecovery.await() }
        val vm = vm()

        vm.onActivityCreated(processColdStart = false)
        vm.onForeground()
        assertEquals(StartupUiState.Hidden, vm.state.value)

        advanceTimeBy(StartupViewModel.HOT_START_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(StartupUiState.Hidden, vm.state.value)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(vm.state.value is StartupUiState.Loading)
        verify(exactly = 1) { chat.connect() }

        connection.value = ConnectionState.Connected
        runCurrent()
        assertEquals(StartupPhase.INITIAL_DATA, (vm.state.value as StartupUiState.Loading).phase)
        destinationRecovery.complete(true)
        runCurrent()
        assertEquals(StartupPhase.READY, (vm.state.value as StartupUiState.Loading).phase)
        advanceTimeBy(StartupViewModel.SUCCESS_COMPLETION_MS)
        runCurrent()
        assertEquals(StartupUiState.Hidden, vm.state.value)
    }

    @Test fun offlineStartupShowsRegisteredRetryableError() = runTest {
        every { connectivity.isOnline() } returns false
        val vm = vm()

        vm.onActivityCreated(processColdStart = true)
        runCurrent()

        val failed = vm.state.value as StartupUiState.Failed
        assertEquals(StartupFailure.DEVICE_OFFLINE, failed.failure)
        verify(exactly = 0) { chat.connect() }
    }

    @Test fun gatewayReadyTimeoutOffersRecoveryInsteadOfBlockingForever() = runTest {
        val vm = vm()

        vm.onActivityCreated(processColdStart = true)
        runCurrent()
        advanceTimeBy(StartupViewModel.CONNECTION_TIMEOUT_MS)
        runCurrent()

        val failed = vm.state.value as StartupUiState.Failed
        assertEquals(StartupFailure.CONNECTION_FAILED, failed.failure)
    }

    @Test fun lateAutomaticRecoveryDismissesTimeoutActions() = runTest {
        val vm = vm()

        vm.onActivityCreated(processColdStart = true)
        runCurrent()
        advanceTimeBy(StartupViewModel.CONNECTION_TIMEOUT_MS)
        runCurrent()
        assertTrue(vm.state.value is StartupUiState.Failed)

        connection.value = ConnectionState.Connected
        runCurrent()
        assertEquals(StartupPhase.READY, (vm.state.value as StartupUiState.Loading).phase)
        advanceTimeBy(maxOf(StartupViewModel.MINIMUM_COLD_START_MS, StartupViewModel.SUCCESS_COMPLETION_MS))
        runCurrent()
        assertEquals(StartupUiState.Hidden, vm.state.value)
        coVerify(exactly = 1) { sessions.listAllProfiles() }
    }

    @Test fun rejectedTokenRoutesToConfigurationRepair() = runTest {
        coEvery { rest.probeStatusFor(config.baseUrl, config.token) } returns
            GatewayProbeResult.Unauthorized(401)
        val vm = vm()

        vm.onActivityCreated(processColdStart = true)
        runCurrent()

        val repair = vm.state.value as StartupUiState.RepairRequired
        assertEquals(StartupFailure.AUTHENTICATION_FAILED, repair.failure)
        assertEquals(StartupReason.COLD_START, repair.reason)
        verify(exactly = 0) { chat.connect() }
    }

    @Test fun savedRepairRerunsStartupAndReturnsToOriginalRouteOnlyAfterReady() = runTest {
        coEvery { rest.probeStatusFor(config.baseUrl, config.token) } returns
            GatewayProbeResult.Unauthorized(401) andThen GatewayProbeResult.Reachable
        val vm = vm()
        vm.onActivityCreated(processColdStart = true)
        runCurrent()
        assertTrue(vm.state.value is StartupUiState.RepairRequired)

        vm.onConfigurationSaved()
        runCurrent()
        connection.value = ConnectionState.Connected
        runCurrent()
        assertEquals(StartupPhase.READY, (vm.state.value as StartupUiState.Loading).phase)
        assertEquals(0L, vm.repairCompletion.value)

        advanceTimeBy(maxOf(StartupViewModel.MINIMUM_COLD_START_MS, StartupViewModel.SUCCESS_COMPLETION_MS))
        runCurrent()
        assertEquals(StartupUiState.Hidden, vm.state.value)
        assertEquals(1L, vm.repairCompletion.value)
    }

    @Test fun relayServerFailureStaysOnStartupInsteadOfOpeningConfiguration() = runTest {
        coEvery { rest.probeStatusFor(config.baseUrl, config.token) } returns
            GatewayProbeResult.ServerFailure(503)
        val vm = vm()

        vm.onActivityCreated(processColdStart = true)
        runCurrent()

        val failed = vm.state.value as StartupUiState.Failed
        assertEquals(StartupFailure.CONNECTION_FAILED, failed.failure)
    }

    @Test fun invalidStoredUrlRoutesToConfigurationRepairBeforeNetworkCalls() = runTest {
        every { credentials.load() } returns GatewayConfig("not a URL", "token")
        val vm = vm()

        vm.onActivityCreated(processColdStart = true)
        runCurrent()

        val repair = vm.state.value as StartupUiState.RepairRequired
        assertEquals(StartupFailure.INVALID_URL, repair.failure)
        coVerify(exactly = 0) { rest.probeStatusFor(any(), any()) }
        verify(exactly = 0) { chat.connect() }
    }
}
