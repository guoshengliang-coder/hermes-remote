package com.hermes.client.ui.nav

import com.hermes.client.data.auth.AccountSession
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.auth.AccountSessionStore
import com.hermes.client.data.network.AccountApi
import com.hermes.client.data.network.AccountDeviceDto
import com.hermes.client.data.network.AccountEndToEndHealthDto
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.network.RelayDeviceDto
import com.hermes.client.data.network.RelayHealthDto
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.ConfigRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SettingsStore
import com.hermes.client.data.repository.ThemeMode
import com.hermes.client.data.repository.ToolsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The drawer's tiles against a Mac switch (HG-48).
 *
 * There was no test for this ViewModel at all, which is the short version of why the bug existed:
 * `refresh()` had exactly one trigger, `profileManager.active`, and switching Macs does not change
 * the active profile. The device name on screen was whatever the process had been told at startup.
 *
 * The ViewModel is Activity-scoped (the card page is the drawer's content, outside the NavHost), so
 * walking to the device page and back does not rebuild it — which is why nothing hid the staleness
 * in ordinary use either.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardPageViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun device(id: String) = AccountDeviceDto(
        id = "binding-$id",
        generation = 1,
        deviceId = id,
        desktopDisplayName = "Mac $id",
        endToEnd = AccountEndToEndHealthDto(healthy = true),
        access = "owner",
    )

    private fun accountSession(selectedDeviceId: String?) = AccountSession(
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

    /** Only the session StateFlow matters here; the rest of the store just has to not throw. */
    private fun accounts(selectedDeviceId: String?): AccountSessionManager {
        val store = mockk<AccountSessionStore>(relaxed = true)
        every { store.loadAccountSession() } returns accountSession(selectedDeviceId)
        return AccountSessionManager(store, mockk<AccountApi>())
    }

    private fun viewModel(rest: HermesRestApi, accounts: AccountSessionManager): CardPageViewModel {
        val profiles = mockk<ProfileManager>()
        every { profiles.active } returns MutableStateFlow<String?>("default")
        every { profiles.list } returns MutableStateFlow<List<ProfileDto>>(emptyList())

        val settings = mockk<SettingsStore>()
        every { settings.themeMode } returns flowOf(ThemeMode.SYSTEM)

        val runtimes = mockk<SessionRuntimeStore>()
        every { runtimes.runtimes } returns MutableStateFlow(emptyMap())

        val tools = mockk<ToolsRepository>()
        coEvery { tools.cronJobs(any()) } returns emptyList()

        val config = mockk<ConfigRepository>()
        coEvery { config.get(any()) } returns JsonObject(emptyMap())

        return CardPageViewModel(
            profileManager = profiles,
            tools = tools,
            configRepo = config,
            settingsStore = settings,
            rest = rest,
            accountSessions = accounts,
            healthMonitor = mockk(relaxed = true),
            runtimeStore = runtimes,
            updateBadge = mockk(relaxed = true),
            feedbackReporter = mockk(relaxed = true),
        )
    }

    @Test fun switching_macs_refreshes_the_tile_that_names_the_mac() = runTest(dispatcher) {
        val accounts = accounts("mac-mini")
        val rest = mockk<HermesRestApi>()
        // Whatever the current Mac is, that is what the relay reports. The point of the test is
        // that the tile asks again at all — before HG-48 it asked once, at process start.
        coEvery { rest.relayHealth() } answers {
            val current = accounts.session.value?.selectedDeviceId ?: "unknown"
            RelayHealthDto(ok = true, connectors = 1, devices = listOf(RelayDeviceDto(current, online = true)))
        }

        val vm = viewModel(rest, accounts)
        runCurrent()
        assertEquals("mac-mini", vm.state.value.deviceId)

        accounts.selectDevice(device("macbook-m5"))
        runCurrent()

        assertEquals("macbook-m5", vm.state.value.deviceId)
    }

    /**
     * Re-selecting the Mac already in use is the 「重新检查连接」 button, and it must not set the
     * tiles refetching on every press. `distinctUntilChanged` is what stops it; the `drop(1)`
     * beside it is what stops the very first emission from doubling the startup fetch.
     */
    @Test fun re_selecting_the_same_mac_does_not_refetch() = runTest(dispatcher) {
        val accounts = accounts("mac-mini")
        val rest = mockk<HermesRestApi>()
        var calls = 0
        coEvery { rest.relayHealth() } answers {
            calls++
            RelayHealthDto(ok = true, connectors = 1, devices = listOf(RelayDeviceDto("mac-mini", online = true)))
        }

        viewModel(rest, accounts)
        runCurrent()
        val afterStartup = calls
        assertEquals(1, afterStartup)

        accounts.selectDevice(device("mac-mini"))
        runCurrent()

        assertEquals(afterStartup, calls)
    }
}
