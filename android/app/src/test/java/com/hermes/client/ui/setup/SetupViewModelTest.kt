package com.hermes.client.ui.setup

import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.DEFAULT_REMOTE_GATEWAY_URL
import com.hermes.client.data.network.GatedAuth
import com.hermes.client.data.network.HermesRestApi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val store = mockk<CredentialStore>(relaxed = true)
    private val rest = mockk<HermesRestApi>(relaxed = true)
    private val gatedAuth = mockk<GatedAuth>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { store.load() } returns null
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SetupViewModel(store, rest, gatedAuth)

    @Test fun applyPairing_populates_fields_from_valid_payload() {
        val vm = vm()
        vm.applyPairing("""{"v":1,"url":"https://h.ts.net","username":"a","password":"p"}""")
        val s = vm.state.value
        assertEquals("https://h.ts.net", s.url)
        assertEquals("a", s.username)
        assertEquals("p", s.password)
        assertFalse(s.scanError)
    }

    @Test fun applyPairing_sets_scanError_and_keeps_default_relay_on_garbage() {
        val vm = vm()
        vm.applyPairing("not a hermes code")
        val s = vm.state.value
        assertTrue(s.scanError)
        assertEquals(DEFAULT_REMOTE_GATEWAY_URL, s.url)
        assertEquals("", s.password)
    }

    @Test fun clearScanError_clears_it() {
        val vm = vm()
        vm.applyPairing("garbage")
        vm.clearScanError()
        assertFalse(vm.state.value.scanError)
    }
}
