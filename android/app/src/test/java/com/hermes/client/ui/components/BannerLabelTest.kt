package com.hermes.client.ui.components

import com.hermes.client.data.network.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class BannerLabelTest {
    @Test fun disconnected_is_friendly() {
        assertEquals(
            "Connection interrupted; restoring automatically (HR-CONN-004).",
            bannerLabel(ConnectionState.Disconnected),
        )
    }
    @Test fun error_is_error_copy() {
        assertEquals("Couldn't connect to the Relay (HR-CONN-002). Retry.", bannerLabel(ConnectionState.Error("boom")))
    }
    @Test fun connecting_is_a_nonTerminal_progress_message() {
        assertEquals("Connecting to the Relay…", bannerLabel(ConnectionState.Connecting))
        assertEquals(true, connectionBannerModel(ConnectionState.Connecting).progress)
        assertEquals(null, connectionBannerModel(ConnectionState.Connecting).error)
    }
    /**
     * An interruption restores itself, so it renders as progress, not as a failure — only a Relay
     * that actually refused us gets the error colours. The code stays reachable through 详情.
     */
    @Test fun a_self_healing_interruption_is_not_styled_as_a_failure() {
        val interrupted = connectionBannerModel(ConnectionState.Disconnected)
        assertEquals(true, interrupted.progress)
        assertEquals("HR-CONN-004", interrupted.error!!.code.value)

        val failed = connectionBannerModel(ConnectionState.Error("boom"))
        assertEquals(false, failed.progress)
        assertEquals("HR-CONN-002", failed.error!!.code.value)
    }

    @Test fun errors_have_registered_codes_and_chinese_copy() {
        assertEquals(
            "HR-CONN-002",
            connectionBannerModel(ConnectionState.Error("boom"), zh = true).error!!.code.value,
        )
        assertEquals(
            "连接已中断，将自动恢复（HR-CONN-004）。",
            bannerLabel(ConnectionState.Disconnected, zh = true),
        )
    }
}
