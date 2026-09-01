package com.hermes.client.ui.components

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.ui.theme.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusDotTest {
    @Test fun maps_states_to_labels() {
        assertEquals("Connected", connectionLabel(ConnectionState.Connected))
        assertEquals("Connecting…", connectionLabel(ConnectionState.Connecting))
        assertEquals("Reconnecting…", connectionLabel(ConnectionState.Reconnecting))
        assertEquals("Offline", connectionLabel(ConnectionState.Disconnected))
        assertEquals("Connection error", connectionLabel(ConnectionState.Error("boom")))
    }

    // The dot used to hardcode three theme-blind colours inline. The mapping is now a pure
    // function over the shared status palette, so it can be pinned here.
    @Test fun maps_states_to_traffic_light_tones() {
        assertEquals(StatusTone.GOOD, connectionTone(ConnectionState.Connected))
        assertEquals(StatusTone.WARN, connectionTone(ConnectionState.Connecting))
        assertEquals(StatusTone.WARN, connectionTone(ConnectionState.Reconnecting))
        assertEquals(StatusTone.BAD, connectionTone(ConnectionState.Disconnected))
        assertEquals(StatusTone.BAD, connectionTone(ConnectionState.Error("boom")))
    }
}
