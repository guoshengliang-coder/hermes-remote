package com.hermes.client.data.repository

import com.hermes.client.data.auth.*
import com.hermes.client.data.network.*
import com.hermes.client.data.error.AppErrorCode
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InstanceModelPreferencesTest {
    private val session = AccountSession("https://fixture.invalid", "a", installationId="i",installationDisplayName="test",accessToken="synthetic",accessExpiresAt="2099-01-01T00:00:00Z",refreshToken="synthetic",refreshExpiresAt="2099-01-01T00:00:00Z",selectedDeviceId="a",modelSyncGeneration="generation")
    private val sessionFlow = MutableStateFlow<AccountSession?>(session)
    private val epoch = MutableStateFlow(0L)
    private var device = "a"
    private val manager = mockk<AccountSessionManager> {
        every { session } returns sessionFlow
        every { transportRouteEpoch } returns epoch
        every { transportRoutingContext() } answers { AccountRoutingContext("a",device) }
        coEvery { connection(any()) } answers { AccountConnection("https://fixture.invalid","synthetic",device,AccountDeviceRouteMode.EXPLICIT_DEVICE) }
    }
    private val rest = mockk<HermesRestApi>()
    private val api = mockk<AccountApi>()
    private fun snapshot(id: String = "00000000-0000-4000-8000-000000000001", favorite: Boolean = false, revision: String = "1") = InstanceModelSnapshot(1,id,revision,listOf(InstanceModelRow("p","m",favorite,null,revision,"0")))
    private fun permissions(access: String = "owner") {
        coEvery { api.devices(any(),any()) } answers { AccountDevicesResponseDto(listOf(AccountDeviceDto("binding",1,device,"Mac",access=access))) }
    }
    @Test fun conflict_keeps_authority_and_never_replays_a_cancelled_favorite() = runTest {
        permissions()
        coEvery { rest.instanceModelPreferences("a") } returns snapshot()
        coEvery { rest.setInstanceModelPreference(any(),"a") } throws HermesApiException(409,"conflict")
        val sync=InstanceModelPreferences(rest,manager,api)
        sync.refresh()
        assertFalse(sync.set("p","m","favorite",true))
        assertFalse(sync.state.value.favorites.contains(favKey("p","m")))
        assertEquals(AppErrorCode.INSTANCE_MODELS_CONFLICT,sync.state.value.error?.code)
        coVerify(exactly=1) { rest.setInstanceModelPreference(match { it.baseRevision == "1" },"a") }
    }
    @Test fun shared_reader_never_sends_a_mutation() = runTest {
        permissions("operator")
        coEvery { rest.instanceModelPreferences("a") } returns snapshot()
        val sync=InstanceModelPreferences(rest,manager,api)
        sync.refresh()
        assertTrue(sync.state.value.readOnly)
        assertFalse(sync.set("p","m","hidden",true))
        coVerify(exactly=0) { rest.setInstanceModelPreference(any(),any()) }
    }
    @Test fun switching_mac_clears_state_and_fences_late_responses() = runTest {
        permissions()
        val delayed=CompletableDeferred<InstanceModelSnapshot>()
        coEvery { rest.instanceModelPreferences("a") } coAnswers { delayed.await() }
        coEvery { rest.instanceModelPreferences("b") } returns snapshot("00000000-0000-4000-8000-000000000002")
        val sync=InstanceModelPreferences(rest,manager,api)
        val watching=backgroundScope.launch { sync.watchRoute() }
        val reading=launch { sync.refresh() }; runCurrent()
        device="b";epoch.value++;runCurrent()
        assertNull(sync.state.value.snapshot)
        delayed.complete(snapshot(favorite=true));reading.join()
        assertNull(sync.state.value.snapshot)
        sync.refresh()
        assertTrue(sync.state.value.owner.endsWith("#b"))
        assertFalse(sync.state.value.favorites.contains(favKey("p","m")))
        watching.cancel()
    }
    @Test fun validates_exact_versions_and_hidden_provider_overrides() {
        val s=snapshot().copy(rows=listOf(InstanceModelRow("p","",false,true,"0","1"),InstanceModelRow("p","m",false,false,"0","1"))).validated()
        assertTrue(s.hidden("p","new"));assertFalse(s.hidden("p","m"))
        assertTrue(runCatching { s.copy(version=2).validated() }.isFailure)
        assertTrue(runCatching { s.copy(rows=s.rows+s.rows[0]).validated() }.isFailure)
        assertTrue(runCatching { s.copy(revision="9223372036854775808").validated() }.isFailure)
    }
}
