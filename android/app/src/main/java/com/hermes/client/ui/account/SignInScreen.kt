package com.hermes.client.ui.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.R
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * The full-screen sign-in page (`DESIGN.md` §5.19). It does one thing — email, then the 6-digit
 * code — and deliberately does not host device selection or the compatibility connection, which
 * used to share the scroll with it.
 *
 * [reasonCode] is the `HR-*` code explaining why an existing session ended. It is set only when the
 * SERVER ended it; an explicit sign-out passes null, because a user who just tapped "sign out"
 * does not need to be told why they are signed out.
 */
@Composable
fun SignInScreen(
    reasonCode: String? = null,
    onOpenLegacy: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    vm: AccountDevicesViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val state by vm.state.collectAsStateWithLifecycle()
    val codeSent = state.stage == AccountStage.CODE_SENT
    // The brand lockup yields the whole screen to the keyboard rather than shrinking: on a short
    // phone the code field, its countdown and the two resend actions together need more height
    // than a scaled-down lockup would leave.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        bottomBar = {
            // §5.12: one clear submit action, always reachable, riding above the keyboard. The
            // compatibility entry rides with it rather than sitting under the email field, where
            // it read as one more step of the form.
            Column(
                Modifier.navigationBarsPadding().imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OtherConnectionOptions(
                    onOpenLegacy = { vm.allowExplicitLegacyFallback(); onOpenLegacy() },
                    onOpenDiagnostics = onOpenDiagnostics,
                )
                Button(
                    onClick = { if (codeSent) vm.verifyCode() else vm.sendCode() },
                    enabled = if (codeSent) {
                        state.code.length == 6 && !state.busy
                    } else {
                        AccountDevicesViewModel.looksLikeEmail(state.email) && !state.busy &&
                            state.stage != AccountStage.UNAVAILABLE
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
                        .height(52.dp)
                        .testTag("signin-submit"),
                ) {
                    Text(
                        if (codeSent) localized(language, "验证并登录", "Verify and sign in")
                        else localized(language, "发送验证码", "Send verification code"),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedVisibility(visible = !imeVisible) { SignInBrandLockup() }

            reasonCode?.let { SessionEndedBanner(it) }

            if (state.stage == AccountStage.DISCOVERING) {
                LoadingRow(localized(language, "正在检查账号服务…", "Checking account service…"))
            } else {

            Text(
                localized(language, "使用邮箱登录", "Sign in with email"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                localized(
                    language,
                    if (state.supportsDeviceSharing) {
                        "验证码会发送到你的邮箱。登录后，可以选择自己或他人共享给你的 Mac。"
                    } else {
                        "验证码会发送到你的邮箱。与 Mac 上的 Hermes Go Desktop 登录同一账号即可连接。"
                    },
                    if (state.supportsDeviceSharing) {
                        "We'll email you a verification code. Sign in to choose a Mac you own or one shared with you."
                    } else {
                        "We'll email you a verification code. Use the same account in Hermes Go Desktop on your Mac to connect."
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.email,
                onValueChange = vm::onEmailChange,
                label = { Text(localized(language, "邮箱", "Email")) },
                leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                enabled = !state.busy && !codeSent,
                modifier = Modifier.fillMaxWidth(),
            )

            if (codeSent) {
                OutlinedTextField(
                    value = state.code,
                    onValueChange = vm::onCodeChange,
                    label = { Text(localized(language, "6 位验证码", "6-digit code")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    localized(
                        language,
                        "验证码将在 ${formatOtpCountdown(state.codeExpiresInSeconds)} 后过期",
                        "Code expires in ${formatOtpCountdown(state.codeExpiresInSeconds)}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Two separate actions, never one (§5.0): resending keeps the challenge,
                    // changing the mailbox throws it away.
                    TextButton(
                        onClick = vm::resendCode,
                        // Inside the 60s cooldown the Gateway returns the SAME challenge and sends
                        // no second mail, so an enabled button here would promise a mail that never
                        // arrives.
                        enabled = !state.busy && state.resendInSeconds == 0,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (state.resendInSeconds > 0) {
                                localized(
                                    language,
                                    "${state.resendInSeconds} 秒后可重发",
                                    "Resend in ${state.resendInSeconds}s",
                                )
                            } else {
                                localized(language, "重新发送验证码", "Resend code")
                            },
                        )
                    }
                    TextButton(
                        onClick = vm::startOver,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(localized(language, "更换邮箱", "Use another email"))
                    }
                }
            }

            if (state.busy) LoadingRow(localized(language, "请稍候…", "Please wait…"))

            }

            // The operation error card is a separate thing from the banner above: this one says
            // "that last tap failed", the banner says "here is how your previous session ended".
            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("signin-error"),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            accountErrorText(error.code, language),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(error.code, style = MaterialTheme.typography.labelMedium)
                        if (error.retryable) {
                            TextButton(onClick = vm::retryError, enabled = !state.busy) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Text(
                                    localized(language, "重试", "Retry"),
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}


/**
 * Sign in, then choose a Mac. Both steps live in ONE destination and swap by stage rather than
 * being two back-stack entries: backing out of device selection into a sign-in page you have
 * already completed is not a state the user can do anything with.
 *
 * Used by the `setup` destination (first run, explicit sign-out) and by the mid-session overlay
 * (`MainActivity`), which is why it takes [onCompleted] instead of navigating itself.
 */
@Composable
fun AccountSignInFlow(
    reasonCode: String? = null,
    onCompleted: () -> Unit = {},
    onOpenLegacy: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    vm: AccountDevicesViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (state.stage) {
        AccountStage.SIGNED_IN -> DeviceSelectionScreen(
            onBack = null,
            onConnected = onCompleted,
            vm = vm,
        )
        AccountStage.ACCOUNT_DELETION_COMMITTED -> Scaffold { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { AccountDeletionCommittedContent(vm) }
        }
        else -> SignInScreen(
            reasonCode = reasonCode,
            onOpenLegacy = onOpenLegacy,
            onOpenDiagnostics = onOpenDiagnostics,
            vm = vm,
        )
    }
}

/**
 * Compact brand lockup. It is NOT §5.11's startup lockup: that one is 144dp at 22.5% of screen
 * height and assumes nothing below it, which pushes a form with a keyboard clean off the screen.
 */
@Composable
private fun SignInBrandLockup() {
    val language = LocalAppLanguage.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Column(
        Modifier.fillMaxWidth().padding(top = screenHeight * BRAND_TOP_FRACTION),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(72.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "HERMES GO", // l10n-allow: official product name is language-invariant
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.16.em,
            ),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            localized(language, "登录后即可连到你的 Mac", "Sign in to reach your Mac"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Why an existing session ended. Carries NO action on purpose: every code it can show is
 * registered non-retryable, and §5.0 forbids offering a retry that cannot work. The recovery is
 * the form below it.
 */
@Composable
private fun SessionEndedBanner(code: String) {
    val language = LocalAppLanguage.current
    // Error TEXT on a neutral container, not a filled errorContainer: this explains a state the
    // user did not cause and cannot act on here, and §5.11's failure state already settled that
    // shape for exactly this kind of message. A saturated red block shouts at someone whose only
    // job is to type their email again.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("signin-session-ended"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                accountErrorText(code, language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            // Same rule as §5.11's failure state: the code gets its own line, never a parenthesis.
            Text(
                code,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Migration-era escape hatches, kept per §5.0 but off the main path. */
@Composable
private fun OtherConnectionOptions(onOpenLegacy: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val language = LocalAppLanguage.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (!expanded) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().testTag("signin-other-options"),
        ) {
            Text(
                localized(language, "其他连接方式", "Other ways to connect"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                localized(language, "兼容连接", "Compatibility"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenLegacy, modifier = Modifier.fillMaxWidth()) {
                Text(localized(language, "旧版 Relay / App Token", "Legacy Relay / App Token"))
            }
            TextButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text(localized(language, "打开诊断", "Open diagnostics"))
            }
        }
    }
}

private const val BRAND_TOP_FRACTION = 0.12f
