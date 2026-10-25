package com.hermes.client.ui.nav

import com.hermes.client.ui.startup.StartupFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairNavigationTest {
    @Test fun accountRepairPreservesTheCurrentNavigationStack() {
        assertTrue(isAccountSetupRepair(StartupFailure.ACCOUNT_AUTHENTICATION_FAILED))
        assertTrue(isAccountSetupRepair(StartupFailure.ACCOUNT_SERVICE_UNAVAILABLE))
        assertTrue(isAccountSetupRepair(StartupFailure.ACCOUNT_DELETION_COMMITTED))
        assertTrue(isAccountSetupRepair(StartupFailure.ACCOUNT_DEVICE_UNAVAILABLE))
        assertFalse(isAccountSetupRepair(StartupFailure.AUTHENTICATION_FAILED))

        assertFalse(
            shouldReplaceStackWithSetup(
                hasConfig = false,
                route = "chat/{id}",
                setupRepairInProgress = false,
                accountRepairRequested = true,
            ),
        )
        assertFalse(
            shouldReplaceStackWithSetup(
                hasConfig = false,
                route = "chat/{id}",
                setupRepairInProgress = true,
                accountRepairRequested = false,
            ),
        )
        assertTrue(
            shouldReplaceStackWithSetup(
                hasConfig = false,
                route = "chat/{id}",
                setupRepairInProgress = false,
                accountRepairRequested = false,
            ),
        )
    }
}
