package com.hermes.client.ui.settings

import com.hermes.client.data.video.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoCacheViewModelTest {
    @Test fun failed_capacity_save_keeps_choice_and_retries_exact_operation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val ranges = mockk<ArtifactRangeRepository>()
            val state = MutableStateFlow(VideoCacheStats(loaded = true))
            every { ranges.cacheState } returns state
            coEvery { ranges.refreshCache() } returns state.value
            var fail = true
            coEvery { ranges.setCacheCapacity(VideoCacheCapacity.LARGE) } coAnswers {
                if (fail) throw java.io.IOException("token=secret")
                state.value = state.value.copy(maxBytes = VideoCacheCapacity.LARGE.bytes)
            }
            val vm = VideoCacheViewModel(ranges); runCurrent()
            vm.select(VideoCacheCapacity.LARGE); assertTrue(vm.busy.value); runCurrent()
            assertEquals(VideoCacheCapacity.DEFAULT.bytes, vm.cache.value.maxBytes)
            assertEquals("HR-CACHE-001", vm.error.value!!.code.value)
            assertFalse(vm.error.value!!.sanitizedDiagnostic().contains("secret"))
            fail = false; vm.retry(); runCurrent()
            assertNull(vm.error.value); assertFalse(vm.busy.value)
            assertEquals(VideoCacheCapacity.LARGE.bytes, vm.cache.value.maxBytes)
            coVerify(exactly = 2) { ranges.setCacheCapacity(VideoCacheCapacity.LARGE) }
        } finally { Dispatchers.resetMain() }
    }
}
