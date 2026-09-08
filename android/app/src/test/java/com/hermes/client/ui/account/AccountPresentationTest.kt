package com.hermes.client.ui.account

import com.hermes.client.data.network.AccountDeviceDto
import com.hermes.client.data.network.AccountEndToEndHealthDto
import com.hermes.client.data.network.AccountConnectorHealthDto
import com.hermes.client.data.network.AccountHermesHealthDto
import com.hermes.client.data.auth.PendingEmailChallenge
import com.hermes.client.ui.localization.AppLanguage
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPresentationTest {
    @Test fun challenge_deadlines_round_up_and_fail_closed_when_invalid_or_expired() {
        val now = Instant.parse("2026-09-08T08:00:00Z")
        val pending = PendingEmailChallenge(
            baseUrl = "https://relay.example",
            email = "person@example.com",
            challengeId = "challenge-1",
            expiresAt = "2026-09-08T08:10:00.500Z",
            resendAfter = "2026-09-08T08:00:59.100Z",
            exchangeIdempotencyKey = "key-1",
        )

        assertEquals(601, emailChallengeTiming(pending, now).expiresInSeconds)
        assertEquals(60, emailChallengeTiming(pending, now).resendInSeconds)
        assertTrue(emailChallengeTiming(pending, now).expired.not())
        assertTrue(emailChallengeTiming(pending, Instant.parse(pending.expiresAt)).expired)
        assertTrue(emailChallengeTiming(pending.copy(expiresAt = "invalid"), now).expired)
        assertEquals("9:02", formatOtpCountdown(542))
    }

    @Test fun email_validation_is_bounded_and_requires_mailbox_domain() {
        assertTrue(AccountDevicesViewModel.looksLikeEmail("person@example.com"))
        assertFalse(AccountDevicesViewModel.looksLikeEmail("person@example"))
        assertFalse(AccountDevicesViewModel.looksLikeEmail("@example.com"))
        assertFalse(AccountDevicesViewModel.looksLikeEmail("a@${"x".repeat(252)}.com"))
    }

    @Test fun otp_and_device_errors_have_bilingual_safe_copy() {
        assertEquals(
            "邮箱验证码无效或已过期，请重新获取。",
            accountErrorText("HR-AUTH-009", AppLanguage.ZH),
        )
        assertEquals(
            "The email code is invalid or expired. Request a new code.",
            accountErrorText("HR-AUTH-009", AppLanguage.EN),
        )
        assertEquals(
            "That Mac is no longer available to this account. Choose another device.",
            accountErrorText("HR-BIND-011", AppLanguage.EN),
        )
        assertEquals(
            "这台 Mac 已无法由当前账号使用，请选择其他设备。",
            accountErrorText("HR-BIND-011", AppLanguage.ZH),
        )
        assertEquals(
            "为确认是你本人，请重新验证当前账号的登录方式。",
            accountErrorText("HR-AUTH-006", AppLanguage.ZH),
        )
        assertEquals(
            "Verify your sign-in identity again to confirm it's you.",
            accountErrorText("HR-AUTH-006", AppLanguage.EN),
        )
    }

    @Test fun shared_healthy_device_reports_end_to_end_health_without_identity_data() {
        val device = AccountDeviceDto(
            id = "binding-1",
            generation = 1,
            deviceId = "mac-1",
            desktopDisplayName = "Family Mac",
            connector = AccountConnectorHealthDto(online = true),
            hermes = AccountHermesHealthDto(reachable = true),
            endToEnd = AccountEndToEndHealthDto(healthy = true),
            access = "operator",
        )

        assertEquals("端到端连接正常", deviceStatusText(device, AppLanguage.ZH))
        assertEquals("End-to-end connection healthy", deviceStatusText(device, AppLanguage.EN))
    }
}
