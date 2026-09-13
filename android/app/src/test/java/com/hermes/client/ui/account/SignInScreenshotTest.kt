package com.hermes.client.ui.account

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the sign-in page (`docs/DESIGN.md` §5.19, both themes).
 *
 * `HermesTheme(darkTheme = true)` under light Robolectric qualifiers is deliberately the "system
 * light, app dark" combination §7 requires: it is the case where borrowing `isSystemInDarkTheme()`
 * for a style branch would show up, and nothing else in this suite catches it.
 *
 * Goldens live under app/screenshots/. Not part of the release gate — pixel noise must never
 * block a release. There is no Stitch baseline for this page yet (§5.19), so these record what we
 * built rather than what a mock measured.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class SignInScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    @Test fun emptyPage() {
        capture("signin.default.light", AccountDevicesUiState(stage = AccountStage.SIGNED_OUT))
    }

    @Test fun emptyPageDark() {
        capture(
            "signin.default.dark",
            AccountDevicesUiState(stage = AccountStage.SIGNED_OUT),
            dark = true,
        )
    }

    @Test fun codeSent() {
        capture(
            "signin.code-sent.light",
            AccountDevicesUiState(
                stage = AccountStage.CODE_SENT,
                email = "person@example.com",
                codeExpiresInSeconds = 542,
                resendInSeconds = 42,
            ),
        )
    }

    @Test fun expiredSessionBanner() {
        capture(
            "signin.expired-banner.light",
            AccountDevicesUiState(stage = AccountStage.SIGNED_OUT),
            reasonCode = "HR-AUTH-003",
        )
    }

    /** The banner, the code step and 1.3x together — the three things most likely to collide. */
    @Test fun revokedBannerDarkLargeFont() {
        capture(
            "signin.revoked-banner.dark-fs13",
            AccountDevicesUiState(
                stage = AccountStage.CODE_SENT,
                email = "a-fairly-long-mailbox@example.com",
                codeExpiresInSeconds = 542,
                resendInSeconds = 42,
            ),
            reasonCode = "HR-AUTH-004",
            dark = true,
            fontScale = 1.3f,
        )
    }

    @Test fun relayWithoutEmailSignIn() {
        capture(
            "signin.unavailable.light",
            AccountDevicesUiState(
                stage = AccountStage.UNAVAILABLE,
                error = AccountUiError("HR-AUTH-011", retryable = false, AccountRetryAction.REFRESH),
            ),
        )
    }

    @Test fun englishAtLargeFont() {
        capture(
            "signin.code-sent.en-fs13",
            AccountDevicesUiState(
                stage = AccountStage.CODE_SENT,
                email = "person@example.com",
                codeExpiresInSeconds = 542,
                resendInSeconds = 42,
            ),
            reasonCode = "HR-AUTH-005",
            language = AppLanguage.EN,
            fontScale = 1.3f,
        )
    }

    private fun capture(
        name: String,
        state: AccountDevicesUiState,
        reasonCode: String? = null,
        dark: Boolean = false,
        fontScale: Float = 1f,
        language: AppLanguage = AppLanguage.ZH,
    ) {
        val vm = mockk<AccountDevicesViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(state)
        compose.setContent {
            Themed(dark, fontScale, language) { SignInScreen(reasonCode = reasonCode, vm = vm) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = options)
    }

    @Composable
    private fun Themed(
        dark: Boolean,
        fontScale: Float,
        language: AppLanguage,
        content: @Composable () -> Unit,
    ) {
        HermesTheme(darkTheme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalAppLanguage provides language,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                Surface { content() }
            }
        }
    }
}
