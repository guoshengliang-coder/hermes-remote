package com.hermes.client.ui.messaging

import com.hermes.client.data.network.MessagingPlatformDto
import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

private fun platform(
    state: String? = null, enabled: Boolean = false, configured: Boolean = false,
    gatewayRunning: Boolean = false,
) = MessagingPlatformDto(
    id = "dingtalk", enabled = enabled, configured = configured,
    gatewayRunning = gatewayRunning, state = state,
)

class MessagingRowStatusTest {
    @Test fun every_server_state_maps_to_its_own_row_status() {
        assertEquals(MessagingRowStatus.CONNECTED, messagingRowStatus(platform(state = "connected")))
        assertEquals(MessagingRowStatus.PENDING_RESTART, messagingRowStatus(platform(state = "pending_restart")))
        assertEquals(MessagingRowStatus.FAILED, messagingRowStatus(platform(state = "startup_failed")))
        assertEquals(MessagingRowStatus.GATEWAY_STOPPED, messagingRowStatus(platform(state = "gateway_stopped")))
        assertEquals(MessagingRowStatus.NOT_CONFIGURED, messagingRowStatus(platform(state = "not_configured")))
        assertEquals(MessagingRowStatus.DISABLED, messagingRowStatus(platform(state = "disabled")))
    }

    /** The bug this class exists for: enabled + a live gateway used to read as "connected", so a
     *  channel that was only saved — or whose adapter failed to start — showed a green light. */
    @Test fun pending_restart_is_not_reported_as_connected() {
        val saved = platform(state = "pending_restart", enabled = true, configured = true, gatewayRunning = true)
        assertEquals(MessagingRowStatus.PENDING_RESTART, messagingRowStatus(saved))
        assertEquals("已保存 · 重启网关后连接", messagingStatusText(messagingRowStatus(saved)).resolve(AppLanguage.ZH))
    }

    @Test fun startup_failure_is_not_reported_as_connected() {
        val broken = platform(state = "startup_failed", enabled = true, configured = true, gatewayRunning = true)
        assertEquals(MessagingRowStatus.FAILED, messagingRowStatus(broken))
    }

    /** A Hermes with no `state` field must degrade to what it can honestly say, never to connected. */
    @Test fun a_missing_state_never_degrades_to_connected() {
        assertEquals(MessagingRowStatus.NOT_CONFIGURED, messagingRowStatus(platform(enabled = true, gatewayRunning = true)))
        assertEquals(MessagingRowStatus.DISABLED, messagingRowStatus(platform(configured = true, gatewayRunning = true)))
        assertEquals(
            MessagingRowStatus.UNKNOWN,
            messagingRowStatus(platform(enabled = true, configured = true, gatewayRunning = true)),
        )
    }

    @Test fun an_unknown_upstream_state_is_unknown_not_connected() {
        assertEquals(MessagingRowStatus.UNKNOWN, messagingRowStatus(platform(state = "quarantined", enabled = true)))
    }

    @Test fun labels_exist_in_both_languages() {
        assertEquals("未连接", messagingStatusText(MessagingRowStatus.FAILED).resolve(AppLanguage.ZH))
        assertEquals("Not connected", messagingStatusText(MessagingRowStatus.FAILED).resolve(AppLanguage.EN))
    }
}
