package com.hermes.client.ui.account

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.data.auth.AccountSession
import com.hermes.client.data.network.AccountConnectorHealthDto
import com.hermes.client.data.network.AccountDeviceDto
import com.hermes.client.data.network.AccountEndToEndHealthDto
import com.hermes.client.data.network.AccountHermesHealthDto
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The three screens that came out of the old single `AccountDevicesScreen`: sign-in, device
 * selection, and account settings. They share one ViewModel and these fixtures, so they share a
 * test class rather than duplicating the session/device builders three times.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class AccountScreensTest {
    @get:Rule val compose = createComposeRule()

    // ---- Sign-in page ----

    @Test fun signedOutPageLeadsWithTheBrandAndOffersTheLegacyEntryOnlyOnRequest() {
        show(AccountDevicesUiState(stage = AccountStage.SIGNED_OUT)) { vm ->
            SignInScreen(vm = vm)
        }

        compose.onNodeWithText("HERMES GO").assertIsDisplayed()
        compose.onNodeWithText("使用邮箱登录").assertIsDisplayed()
        compose.onNodeWithText("发送验证码").assertIsDisplayed()
        // The compatibility connection is off the main path now; it appears only after asking.
        compose.onNodeWithText("旧版 Relay / App Token").assertDoesNotExist()
        compose.onRoot().captureRoboImage("/tmp/hermes-signin-light.png")
    }

    @Test fun legacyEntryIsReachableAndExplicitlyReleasesTheAccountRecoveryGate() {
        var opened = false
        val vm = show(AccountDevicesUiState(stage = AccountStage.SIGNED_OUT)) { vm ->
            SignInScreen(onOpenLegacy = { opened = true }, vm = vm)
        }

        compose.onNodeWithTag("signin-other-options").performClick()
        compose.onNodeWithText("旧版 Relay / App Token").performClick()

        verify(exactly = 1) { vm.allowExplicitLegacyFallback() }
        assertEquals(true, opened)
    }

    @Test fun anExpiredSessionIsExplainedWithItsCodeAndNoUnusableRetry() {
        show(AccountDevicesUiState(stage = AccountStage.SIGNED_OUT)) { vm ->
            SignInScreen(reasonCode = "HR-AUTH-003", vm = vm)
        }

        compose.onNodeWithTag("signin-session-ended").assertIsDisplayed()
        compose.onNodeWithText("登录已过期，请重新登录。").assertIsDisplayed()
        // Registered non-retryable, so §5.0 forbids showing an action that cannot work.
        compose.onNodeWithText("HR-AUTH-003").assertIsDisplayed()
        compose.onNodeWithText("重试").assertDoesNotExist()
        compose.onRoot().captureRoboImage("/tmp/hermes-signin-expired-banner-light.png")
    }

    @Test fun aRevokedSessionNamesRevocationRatherThanExpiry() {
        show(AccountDevicesUiState(stage = AccountStage.SIGNED_OUT)) { vm ->
            SignInScreen(reasonCode = "HR-AUTH-004", vm = vm)
        }

        compose.onNodeWithText("这台手机的登录已被撤销，请重新登录。").assertIsDisplayed()
    }

    @Test fun anExplicitSignOutIsNeverExplained() {
        show(AccountDevicesUiState(stage = AccountStage.SIGNED_OUT)) { vm ->
            SignInScreen(reasonCode = null, vm = vm)
        }

        compose.onNodeWithTag("signin-session-ended").assertDoesNotExist()
    }

    @Test fun codeEntryShowsExpiryAndDisablesResendDuringCooldown() {
        show(
            AccountDevicesUiState(
                stage = AccountStage.CODE_SENT,
                email = "person@example.com",
                codeExpiresInSeconds = 542,
                resendInSeconds = 42,
            ),
        ) { vm -> SignInScreen(vm = vm) }

        compose.onNodeWithText("验证码将在 9:02 后过期").assertIsDisplayed()
        compose.onNodeWithText("42 秒后可重发").assertIsNotEnabled()
        compose.onNodeWithText("更换邮箱").assertIsDisplayed()
        compose.onNodeWithText("验证并登录").assertIsDisplayed()
        compose.onRoot().captureRoboImage("/tmp/hermes-signin-code-cooldown-light.png")
    }

    @Test fun theBannerAndAnOperationErrorCoexistBecauseTheySayDifferentThings() {
        show(
            AccountDevicesUiState(
                stage = AccountStage.CODE_SENT,
                email = "person@example.com",
                error = AccountUiError("HR-AUTH-009", retryable = false, AccountRetryAction.VERIFY_CODE),
            ),
        ) { vm -> SignInScreen(reasonCode = "HR-AUTH-005", vm = vm) }

        compose.onNodeWithTag("signin-session-ended").assertExists()
        compose.onNodeWithTag("signin-error").assertExists()
    }

    @Test fun signInRemainsReadableInDarkLargeFont() {
        show(
            state = AccountDevicesUiState(
                stage = AccountStage.CODE_SENT,
                email = "person@example.com",
                codeExpiresInSeconds = 542,
                resendInSeconds = 42,
            ),
            dark = true,
            fontScale = 1.3f,
        ) { vm -> SignInScreen(reasonCode = "HR-AUTH-004", vm = vm) }

        compose.onNodeWithText("更换邮箱").assertIsDisplayed()
        compose.onRoot().captureRoboImage("/tmp/hermes-signin-dark-large.png")
    }

    @Test fun anUnavailableRelayStillOffersTheCompatibilityPath() {
        show(
            AccountDevicesUiState(
                stage = AccountStage.UNAVAILABLE,
                error = AccountUiError("HR-AUTH-011", retryable = false, AccountRetryAction.REFRESH),
            ),
        ) { vm -> SignInScreen(vm = vm) }

        compose.onNodeWithText("发送验证码").assertIsNotEnabled()
        compose.onNodeWithTag("signin-other-options").assertIsDisplayed()
        compose.onRoot().captureRoboImage("/tmp/hermes-signin-unavailable-light.png")
    }

    // ---- Device selection ----

    @Test fun ownedAndSharedDevicesRemainReadableInDarkLargeFont() {
        show(
            state = AccountDevicesUiState(
                stage = AccountStage.SIGNED_IN,
                session = session(selectedDeviceId = "office"),
                devices = listOf(
                    device("office", "Office Mac mini with a deliberately long device name", "owner", true),
                    device("family", "Family Mac mini", "operator", false),
                ),
            ),
            dark = true,
            fontScale = 1.3f,
        ) { vm -> DeviceSelectionScreen(onBack = {}, vm = vm) }

        compose.onNodeWithText("我的设备").assertExists()
        compose.onNodeWithText("共享给我").assertExists()
        compose.onNodeWithContentDescription("当前设备", useUnmergedTree = true).assertExists()
        compose.onRoot().captureRoboImage("/tmp/hermes-account-devices-dark-large.png")
    }

    @Test fun emptyDeviceStateExplainsAutomaticDiscovery() {
        show(
            AccountDevicesUiState(
                stage = AccountStage.SIGNED_IN,
                session = session(selectedDeviceId = null),
            ),
        ) { vm -> DeviceSelectionScreen(onBack = {}, vm = vm) }

        compose.onNodeWithText("还没有可用的 Mac").assertIsDisplayed()
        compose.onNodeWithText("停留在本页时会自动检查", substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("/tmp/hermes-account-no-device-light.png")
    }

    @Test fun singularPendingMigrationExplainsThatLegacyStaysActive() {
        show(
            AccountDevicesUiState(
                stage = AccountStage.SIGNED_IN,
                session = session(selectedDeviceId = null).copy(activationPending = true),
                maxOwnedDevices = 1,
            ),
        ) { vm -> DeviceSelectionScreen(onBack = {}, vm = vm) }

        compose.onNodeWithText("账号已登录，当前仍使用旧版连接", substring = true).assertIsDisplayed()
        compose.onNodeWithText("当前账号可连接 1 台 Mac。").assertIsDisplayed()
        compose.onNodeWithText("让设备所有者分享给你", substring = true).assertDoesNotExist()
    }

    // ---- Account settings ----

    @Test fun switchingAccountsSaysOutLoudThatItSignsOutFirst() {
        val vm = show(
            AccountDevicesUiState(
                stage = AccountStage.SIGNED_IN,
                session = session(selectedDeviceId = "office"),
            ),
        ) { vm -> AccountSettingsScreen(onBack = {}, vm = vm) }

        compose.onNodeWithTag("account-switch").performClick()

        compose.onNodeWithText("会先退出当前账号", substring = true).assertIsDisplayed()
        compose.onNodeWithText("退出并切换").performClick()
        verify(exactly = 1) { vm.signOut(any()) }
    }

    @Test fun signingOutOnThisPhoneStillSaysOtherDevicesAreUntouched() {
        show(
            AccountDevicesUiState(
                stage = AccountStage.SIGNED_IN,
                session = session(selectedDeviceId = "office"),
            ),
        ) { vm -> AccountSettingsScreen(onBack = {}, vm = vm) }

        compose.onNodeWithTag("account-sign-out").performClick()

        compose.onNodeWithText("其他手机、Desktop 和 Mac 连接不会受影响", substring = true).assertIsDisplayed()
    }

    @Test fun settingsReachedWhileSignedOutOffersTheSignInPageRatherThanAFormOfItsOwn() {
        var requested = false
        show(AccountDevicesUiState(stage = AccountStage.SIGNED_OUT)) { vm ->
            AccountSettingsScreen(onBack = {}, onSignIn = { requested = true }, vm = vm)
        }

        compose.onNodeWithText("还没有登录 Hermes GO 账号").assertIsDisplayed()
        // Settings must not grow a second email form; there is one sign-in page.
        compose.onNodeWithText("使用邮箱登录").assertDoesNotExist()
        compose.onNodeWithTag("account-sign-in").performClick()
        assertEquals(true, requested)
    }

    @Test fun permanentDeletionEntryIsAbsentWithoutCapability() {
        show(
            AccountDevicesUiState(
                stage = AccountStage.SIGNED_IN,
                session = session(selectedDeviceId = "office"),
                accountDeletionEnabled = false,
            ),
        ) { vm -> AccountSettingsScreen(onBack = {}, vm = vm) }

        compose.onNodeWithText("永久删除账号").assertDoesNotExist()
    }

    @Test fun permanentDeletionEntryIsVisibleInAccountSettingsWhenEnabled() {
        show(
            state = AccountDevicesUiState(
                stage = AccountStage.SIGNED_IN,
                session = session(selectedDeviceId = "office"),
                accountDeletionEnabled = true,
            ),
            dark = true,
            fontScale = 1.3f,
        ) { vm -> AccountSettingsScreen(onBack = {}, vm = vm) }

        compose.onNodeWithText("危险操作").assertIsDisplayed()
        compose.onNodeWithText("永久删除账号").assertIsDisplayed()
        compose.onRoot().captureRoboImage("/tmp/hermes-account-deletion-entry-dark-large.png")
    }

    @Test fun committedDeletionShowsCloudTerminalStateAndPreservedLocalData() {
        show(AccountDevicesUiState(stage = AccountStage.ACCOUNT_DELETION_COMMITTED)) { vm ->
            AccountSignInFlow(vm = vm)
        }

        compose.onNodeWithText("云端账号删除已提交").assertIsDisplayed()
        compose.onNodeWithText("Mac 上的 Hermes 会话", substring = true).assertIsDisplayed()
        compose.onNodeWithText("使用其他邮箱账号").assertIsDisplayed()
        compose.onRoot().captureRoboImage("/tmp/hermes-account-deletion-committed-light.png")
    }

    // ---- Flow ----

    @Test fun theFlowShowsDeviceSelectionOnceSignedInWithoutASecondBackStackEntry() {
        show(
            AccountDevicesUiState(
                stage = AccountStage.SIGNED_IN,
                session = session(selectedDeviceId = null),
            ),
        ) { vm -> AccountSignInFlow(vm = vm) }

        compose.onNodeWithText("选择要使用的 Mac").assertIsDisplayed()
        compose.onNodeWithText("使用邮箱登录").assertDoesNotExist()
    }

    @Test fun pendingDeletionErrorHasRegisteredChineseAndEnglishCopy() {
        assertEquals(
            "此 Hermes GO 账号正在永久删除，已无法再次登录。",
            accountErrorText("HR-ACCOUNT-012", AppLanguage.ZH),
        )
        assertEquals(
            "This Hermes GO account is being permanently deleted and can no longer sign in.",
            accountErrorText("HR-ACCOUNT-012", AppLanguage.EN),
        )
    }

    @Test fun deletionConfirmationContentIsReadableAndAcknowledgementIsOneCheckboxTarget() {
        val vm = mockk<AccountDevicesViewModel>(relaxed = true)
        compose.setContent {
            HermesTheme(darkTheme = true) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ZH,
                    androidx.compose.ui.platform.LocalDensity provides Density(density.density, 1.3f),
                ) {
                    Surface {
                        AccountDeletionConfirmationContent(
                            deletion = AccountDeletionUiState(),
                            vm = vm,
                            modifier = androidx.compose.ui.Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("云端个人数据将在 30 天后清理", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Mac 上的 Hermes 会话", substring = true).assertIsDisplayed()
        val acknowledgement = compose.onNodeWithTag("account-deletion-acknowledgement")
        val semantics = acknowledgement.fetchSemanticsNode().config
        assertEquals(ToggleableState.Off, semantics[SemanticsProperties.ToggleableState])
        assertEquals(Role.Checkbox, semantics[SemanticsProperties.Role])

        acknowledgement.performClick()

        verify(exactly = 1) { vm.onAccountDeletionAcknowledgedChange(true) }
        compose.onRoot().captureRoboImage("/tmp/hermes-account-deletion-confirm-dark-large.png")
    }

    private fun show(
        state: AccountDevicesUiState,
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable (AccountDevicesViewModel) -> Unit,
    ): AccountDevicesViewModel {
        val vm = mockk<AccountDevicesViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(state)
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ZH,
                    androidx.compose.ui.platform.LocalDensity provides Density(density.density, fontScale),
                ) {
                    Surface { content(vm) }
                }
            }
        }
        compose.waitForIdle()
        return vm
    }

    private fun session(selectedDeviceId: String?) = AccountSession(
        baseUrl = "https://relay.example",
        accountId = "account-1",
        installationId = "installation-1",
        installationDisplayName = "Pixel",
        accessToken = "redacted",
        accessExpiresAt = "2099-01-01T00:00:00Z",
        refreshToken = "redacted",
        refreshExpiresAt = "2099-02-01T00:00:00Z",
        selectedDeviceId = selectedDeviceId,
    )

    private fun device(id: String, name: String, access: String, isDefault: Boolean) = AccountDeviceDto(
        id = "binding-$id",
        generation = 1,
        deviceId = id,
        desktopDisplayName = name,
        connector = AccountConnectorHealthDto(online = true),
        hermes = AccountHermesHealthDto(reachable = true),
        endToEnd = AccountEndToEndHealthDto(healthy = true),
        access = access,
        isDefault = isDefault,
    )
}
