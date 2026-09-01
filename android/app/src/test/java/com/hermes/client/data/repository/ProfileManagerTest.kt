package com.hermes.client.data.repository

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileManagerTest {
    @Test fun cancelledSwitchIsNotConvertedIntoOrdinaryFailure() = runTest {
        val repository = mockk<ProfileRepository>()
        coEvery { repository.setActive("work") } throws CancellationException("superseded tap")
        val manager = ProfileManager(repository)

        var cancelled = false
        try {
            manager.switchTo("work")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
