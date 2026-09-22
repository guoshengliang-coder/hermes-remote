package com.hermes.client.notifications.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushInitializationTest {
    private val complete = FcmConfig("api", "1:2:android:3", "project", "123")
    private var gmsChecked = false
    private var initialized: FcmConfig? = null

    private fun run(config: FcmConfig, gms: Boolean) = initializePush(
        config,
        googlePlayServicesAvailable = { gmsChecked = true; gms },
        initializeFirebase = { initialized = it },
    )

    @Test fun anAbsentConfigNeverTouchesFirebaseOrGooglePlayServices() {
        assertEquals(PushAvailability.NOT_CONFIGURED, run(FcmConfig("", "", "", ""), gms = true))
        assertFalse(gmsChecked)
        assertEquals(null, initialized)
    }

    @Test fun aPartialConfigCountsAsAbsent() {
        assertEquals(PushAvailability.NOT_CONFIGURED, run(complete.copy(senderId = " "), gms = true))
        assertEquals(null, initialized)
    }

    @Test fun noGooglePlayServicesSkipsFirebase() {
        assertEquals(PushAvailability.NO_GOOGLE_PLAY_SERVICES, run(complete, gms = false))
        assertTrue(gmsChecked)
        assertEquals(null, initialized)
    }

    @Test fun aCompleteConfigOnAGmsPhoneInitializesWithThoseValues() {
        assertEquals(PushAvailability.AVAILABLE, run(complete, gms = true))
        assertEquals(complete, initialized)
    }
}
