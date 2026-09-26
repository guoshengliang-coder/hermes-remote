package com.hermes.client.ui.components

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.GatewayHealth
import com.hermes.client.data.network.HermesContractNotice
import com.hermes.client.data.network.HermesContractSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthStripTest {
    @Test fun style_is_none_when_healthy_or_unknown() {
        assertEquals(HealthStripStyle.NONE, healthStripStyle(GatewayHealth.Unknown))
        assertEquals(HealthStripStyle.NONE, healthStripStyle(GatewayHealth.Healthy("1", true, 10)))
    }

    @Test fun style_error_for_gateway_unreachable_neutral_for_device_offline() {
        assertEquals(HealthStripStyle.ERROR, healthStripStyle(GatewayHealth.GatewayUnreachable("x")))
        assertEquals(HealthStripStyle.NEUTRAL, healthStripStyle(GatewayHealth.DeviceOffline))
    }

    @Test fun label_null_when_healthy() {
        assertNull(healthStripLabel(GatewayHealth.Healthy("1", true, 10)))
        assertNull(healthStripLabel(GatewayHealth.Unknown))
    }

    @Test fun label_distinguishes_offline_unreachable_unauthorized() {
        assertEquals("You're offline", healthStripLabel(GatewayHealth.DeviceOffline))
        assertEquals("Couldn't reach the service", healthStripLabel(GatewayHealth.GatewayUnreachable("unreachable")))
        assertEquals("Connection credentials invalid", healthStripLabel(GatewayHealth.GatewayUnreachable("unauthorized")))
    }

    @Test fun sheet_body_healthy_includes_version_and_latency() {
        val body = healthSheetBody(GatewayHealth.Healthy(version = "1.2.3", running = true, latencyMs = 42))
        assertTrue(body.contains("running"))
        assertTrue(body.contains("1.2.3"))
        assertTrue(body.contains("42"))
    }

    @Test fun sheet_body_reachable_not_running_when_running_false() {
        val body = healthSheetBody(GatewayHealth.Healthy(version = null, running = false, latencyMs = null))
        assertTrue(body.contains("not running"))
    }

    private val healthy = GatewayHealth.Healthy("0.22.0", true, 10)

    private fun notice(severity: HermesContractSeverity, code: AppErrorCode, vararg features: String) =
        HermesContractNotice(severity, AppError(code, retryable = false), features.toList(), "0.22.0")

    @Test fun a_breaking_hermes_lights_the_strip_as_an_error_while_the_relay_is_healthy() {
        val contract = notice(HermesContractSeverity.BREAKING, AppErrorCode.HERMES_INCOMPATIBLE, "history")
        assertEquals(HealthStripStyle.ERROR, healthStripStyle(healthy, contract))
        assertTrue(healthStripVisible(healthy, contract))
        assertEquals("Hermes on the Mac is incompatible", healthStripLabel(healthy, false, contract))
        assertEquals("Mac 上的 Hermes 不兼容", healthStripLabel(healthy, true, contract))
    }

    @Test fun a_degraded_hermes_is_neutral_and_names_the_features() {
        val contract = notice(HermesContractSeverity.DEGRADED, AppErrorCode.HERMES_FEATURES_MISSING, "cron", "skills")
        assertEquals(HealthStripStyle.NEUTRAL, healthStripStyle(healthy, contract))
        val zh = healthSheetBody(healthy, true, contract)
        assertTrue(zh, zh.contains("受影响：定时任务、技能"))
        assertTrue(zh, zh.contains("0.22.0"))
        val en = healthSheetBody(healthy, false, contract)
        assertTrue(en, en.contains("Affected: scheduled tasks, skills"))
        // The code is rendered on its own line by the sheet, not repeated inside the body.
        assertTrue(en, !en.contains("HR-COMPAT-002"))
    }

    @Test fun an_unknown_feature_key_is_named_generically_not_shown_raw() {
        assertEquals("other features", hermesContractFeatureLabel("teleport", zh = false))
        assertEquals("其他功能", hermesContractFeatureLabel("teleport", zh = true))
    }

    @Test fun no_contract_notice_means_no_strip_on_a_healthy_relay() {
        assertEquals(HealthStripStyle.NONE, healthStripStyle(healthy, null))
        assertTrue(!healthStripVisible(healthy, null))
    }

    /** A down Relay is the more basic problem; a stale contract report must not relabel it. */
    @Test fun gateway_trouble_outranks_the_contract_notice() {
        val contract = notice(HermesContractSeverity.DEGRADED, AppErrorCode.HERMES_FEATURES_MISSING, "cron")
        val down = GatewayHealth.GatewayUnreachable("unreachable")
        assertEquals(HealthStripStyle.ERROR, healthStripStyle(down, contract))
        assertEquals("Couldn't reach the service", healthStripLabel(down, false, contract))
        assertTrue(healthSheetBody(down, false, contract).contains("cause is unclear"))
        assertEquals(HealthStripStyle.NEUTRAL, healthStripStyle(GatewayHealth.DeviceOffline, contract))
        assertEquals("You're offline", healthStripLabel(GatewayHealth.DeviceOffline, false, contract))
    }

    @Test fun sheet_body_offline_and_unauthorized_copy() {
        assertTrue(healthSheetBody(GatewayHealth.DeviceOffline).contains("offline"))
        assertTrue(healthSheetBody(GatewayHealth.GatewayUnreachable("unauthorized")).contains("credentials"))
    }

    @Test fun diagnosedFailuresShowPlainCopyAndTheirStableCodes() {
        val cases = mapOf(
            "HR-CONN-008" to "找不到服务地址",
            "HR-CONN-009" to "暂时连不上服务",
            "HR-CONN-010" to "服务暂时无法响应",
            "HR-CONN-011" to "连接时好时坏",
        )
        for ((code, label) in cases) {
            val health = GatewayHealth.GatewayUnreachable(code)
            assertEquals(label, healthStripLabel(health, zh = true))
            assertEquals(code, healthErrorCode(health))
            assertTrue(healthSheetBody(health, zh = true).isNotBlank())
        }
    }
}
