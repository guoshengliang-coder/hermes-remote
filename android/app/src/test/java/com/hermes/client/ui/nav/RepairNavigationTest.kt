package com.hermes.client.ui.nav

import com.hermes.client.ui.startup.StartupFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairNavigationTest {
    @Test fun ordinaryDestinationsNeverConsumeRepairCompletion() {
        val routes = listOf(
            "sessions",
            "chat/{id}?profile={profile}&title={title}&new={new}",
            "search",
            "models",
            "cron",
            "settings",
            "app_update",
            "profiles",
        )
        routes.forEach { route ->
            assertFalse(route, shouldPopCompletedRepair(route, expectedCompletion = 0L, actualCompletion = 0L))
        }
    }

    @Test fun onlyCompletedConnectionRepairPops() {
        val route = "settings_connection?repair={repair}&completion={completion}"
        assertFalse(shouldPopCompletedRepair(route, expectedCompletion = -1L, actualCompletion = 0L))
        assertFalse(shouldPopCompletedRepair(route, expectedCompletion = 2L, actualCompletion = 1L))
        assertTrue(shouldPopCompletedRepair(route, expectedCompletion = 2L, actualCompletion = 2L))
    }

    @Test fun accountRepairPreservesTheCurrentNavigationStack() {
        assertTrue(isAccountSetupRepair(StartupFailure.ACCOUNT_AUTHENTICATION_FAILED))
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
