package com.hermes.client.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LifecycleEventCursorStoreTest {
    @Test fun legacyScopeKeepsTheExistingPreferenceForMigration() {
        assertEquals("relay_cursor", lifecycleCursorPreferenceName("legacy"))
    }

    @Test fun accountInstallationScopesAreDistinctAndDoNotExposeTheirIdentifiers() {
        val first = lifecycleCursorPreferenceName("https://relay.example\u0000account-a\u0000phone-a")
        val second = lifecycleCursorPreferenceName("https://relay.example\u0000account-a\u0000phone-b")

        assertNotEquals(first, second)
        assertFalse(first.contains("account-a"))
        assertFalse(first.contains("phone-a"))
        assertEquals(79, first.length)
    }
}
