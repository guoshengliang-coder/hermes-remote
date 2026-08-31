package com.hermes.client.ui.startup

import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ConnectivityChecker
import com.hermes.client.data.repository.ChatRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val config = GatewayConfig("https://relay.example", "token")

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { credentials.load() } returns config
        every { connectivity.isOnline() } returns true
        every { chat.connectionState } returns connection
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = StartupViewModel(credentials, connectivity, chat)

    @Test fun firstLaunchWithoutConfigurationSkipsStartupScreen() = runTest {
        every { credentials.load() } returns null
        val vm = vm()

        vm.onActivityCreated(processColdStart = true)
        vm.onForeground()
        runCurrent()

        assertEquals(StartupUiState.Hidden, vm.state.value)
        verify(exactly = 0) { chat.connect() }
    }

    @Test fun configuredColdStartWaitsForGatewayReadyAndMinimumBrandMoment() = runTest {
        val vm = vm()

        vm.onActivityCreated(processColdStart = true)
        runCurrent()

        assertTrue(vm.state.value is StartupUiState.Loading)
        verify(exactly = 1) { chat.connect() }

        connection.value = ConnectionState.Connected
        runCurrent()
        assertEquals(StartupPhase.READY, (vm.state.value as StartupUiState.Loading).phase)

        advanceTimeBy(StartupViewModel.MINIMUM_COLD_START_MS)
        runCurrent()
        assertEquals(StartupUiState.Hidden, vm.state.value)
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
    }

    @Test fun disconnectedHotStartUsesDebounceThenRestoresInPlace() = runTest {
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
        assertEquals(StartupUiState.Hidden, vm.state.value)
    }

    @Test fun continueOfflineHidesRecoveryUntilNextForeground() = runTest {
        val vm = vm()

        vm.onForeground()
        advanceTimeBy(StartupViewModel.HOT_START_DEBOUNCE_MS)
        runCurrent()
        assertTrue(vm.state.value is StartupUiState.Loading)

        vm.continueOffline()
        runCurrent()
        assertEquals(StartupUiState.Hidden, vm.state.value)
    }
}
