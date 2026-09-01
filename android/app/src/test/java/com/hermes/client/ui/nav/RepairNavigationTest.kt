package com.hermes.client.ui.nav

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
}
