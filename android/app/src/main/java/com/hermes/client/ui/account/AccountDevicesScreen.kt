package com.hermes.client.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.network.AccountDeviceDto
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/** Shared account entry. [accountOnly] keeps Settings free of remote-device duplication. */
@Composable
fun AccountDevicesScreen(
    onBack: (() -> Unit)?,
    accountOnly: Boolean = false,
    onOpenLegacy: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onConnected: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    vm: AccountDevicesViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }

    DisposableEffect(vm) {
        vm.setDevicePageVisible(true)
        onDispose { vm.setDevicePageVisible(false) }
    }

    // On onboarding, both the automatic single-device path and an explicit choice continue once
    // the probed device has been durably selected. Other entries stay on the device dashboard.
    LaunchedEffect(onBack, state.session?.selectedDeviceId) {
        if (onBack == null && state.session?.selectedDeviceId != null) onConnected()
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(localized(language, "退出这台手机？", "Sign out on this phone?")) },
            text = {
                Text(
                    localized(
                        language,
                        "只会撤销这台手机的登录。其他手机、Desktop 和 Mac 连接不会受影响。",
                        "Only this phone's session will be revoked. Other phones, Desktop, and Mac connections stay signed in.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; vm.signOut(onSignedOut) }) {
                    Text(localized(language, "退出", "Sign out"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text(localized(language, "取消", "Cancel"))
                }
            },
        )
    }
    state.accountDeletion?.let { deletion ->
        AccountDeletionDialog(deletion = deletion, vm = vm)
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = when {
                    accountOnly -> localized(language, "Hermes GO 账号", "Hermes GO account")
                    onBack == null -> localized(language, "连接 Hermes GO", "Connect Hermes GO")
                    else -> localized(language, "远程设备", "Remote devices")
                },
                navigationIcon = {
                    onBack?.let { action ->
                        IconButton(onClick = action) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = localized(language, "返回", "Back"),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.stage) {
                AccountStage.DISCOVERING -> LoadingRow(localized(language, "正在检查账号服务…", "Checking account service…"))
                AccountStage.UNAVAILABLE,
                AccountStage.SIGNED_OUT,
                AccountStage.CODE_SENT -> SignInContent(state, vm)
                AccountStage.SIGNED_IN -> if (accountOnly) {
                    AccountContent(state)
                    OutlinedButton(
                        onClick = { confirmSignOut = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(localized(language, "退出这台手机", "Sign out on this phone")) }
                    if (state.accountDeletionEnabled) {
                        HorizontalDivider(Modifier.padding(top = 4.dp))
                        Text(
                            localized(language, "危险操作", "Danger zone"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            localized(
                                language,
                                "永久删除所有 Hermes GO 云端账号数据；不会删除 Mac 上的本地 Hermes 数据。",
                                "Permanently delete all Hermes GO Cloud account data. Local Hermes data on your Macs is not deleted.",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = vm::beginAccountDeletion,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(localized(language, "永久删除账号", "Permanently delete account"))
                        }
                    }
                } else {
                    DeviceContent(state, vm)
                }
                AccountStage.ACCOUNT_DELETION_COMMITTED -> AccountDeletionCommittedContent(vm)
            }

            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(accountErrorText(error.code, language), style = MaterialTheme.typography.bodyMedium)
                        Text(error.code, style = MaterialTheme.typography.labelMedium)
                        if (error.retryable) {
                            TextButton(onClick = vm::retryError, enabled = !state.busy) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Text(localized(language, "重试", "Retry"), modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }

            if (!accountOnly) {
                HorizontalDivider(Modifier.padding(top = 4.dp))
                Text(
                    localized(language, "兼容连接", "Compatibility"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { vm.allowExplicitLegacyFallback(); onOpenLegacy() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(localized(language, "旧版 Relay / App Token", "Legacy Relay / App Token"))
                }
                TextButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text(localized(language, "打开诊断", "Open diagnostics"))
                }
            }
        }
    }
}

@Composable
private fun AccountDeletionDialog(
    deletion: AccountDeletionUiState,
    vm: AccountDevicesViewModel,
) {
    val language = LocalAppLanguage.current
    val canDismiss = !deletion.busy && deletion.stage != AccountDeletionStage.RETRY_COMMIT
    AlertDialog(
        onDismissRequest = { if (canDismiss) vm.dismissAccountDeletion() },
        title = {
            Text(
                when (deletion.stage) {
                    AccountDeletionStage.CONFIRMING -> localized(language, "永久删除账号？", "Permanently delete account?")
                    AccountDeletionStage.CODE_SENT -> localized(language, "验证当前邮箱", "Verify your current email")
                    AccountDeletionStage.RETRY_COMMIT -> localized(language, "正在完成账号删除", "Finishing account deletion")
                },
            )
        },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (deletion.stage) {
                    AccountDeletionStage.CONFIRMING ->
                        AccountDeletionConfirmationContent(deletion, vm)
                    AccountDeletionStage.CODE_SENT -> {
                        Text(
                            localized(
                                language,
                                "验证码已发送到当前账号邮箱。验证成功后会立即提交永久删除。",
                                "A code was sent to your current account email. Permanent deletion is submitted immediately after verification.",
                            ),
                        )
                        OutlinedTextField(
                            value = deletion.code,
                            onValueChange = vm::onAccountDeletionCodeChange,
                            label = { Text(localized(language, "6 位验证码", "6-digit code")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            enabled = !deletion.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            localized(
                                language,
                                "验证码将在 ${formatOtpCountdown(deletion.codeExpiresInSeconds)} 后过期",
                                "Code expires in ${formatOtpCountdown(deletion.codeExpiresInSeconds)}",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = vm::resendAccountDeletionCode,
                            enabled = !deletion.busy && deletion.resendInSeconds == 0,
                        ) {
                            Text(
                                if (deletion.resendInSeconds > 0) {
                                    localized(
                                        language,
                                        "${deletion.resendInSeconds} 秒后可重新发送",
                                        "Resend in ${deletion.resendInSeconds}s",
                                    )
                                } else {
                                    localized(language, "重新发送验证码", "Resend code")
                                },
                            )
                        }
                    }
                    AccountDeletionStage.RETRY_COMMIT -> Text(
                        localized(
                            language,
                            "删除请求的结果尚未确认。请保持此恢复记录，并使用同一个请求安全重试。",
                            "The deletion result is not confirmed yet. Keep this recovery record and safely retry the same request.",
                        ),
                    )
                }
                deletion.error?.let { error ->
                    Text(
                        "${accountErrorText(error.code, language)} (${error.code})",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (deletion.busy) LoadingRow(localized(language, "请稍候…", "Please wait…"))
            }
        },
        confirmButton = {
            when (deletion.stage) {
                AccountDeletionStage.CONFIRMING -> TextButton(
                    onClick = vm::requestAccountDeletionCode,
                    enabled = !deletion.busy &&
                        deletion.typedConfirmation == AccountDevicesViewModel.ACCOUNT_DELETION_CONFIRMATION &&
                        deletion.acknowledged,
                ) { Text(localized(language, "发送确认验证码", "Send confirmation code")) }
                AccountDeletionStage.CODE_SENT -> TextButton(
                    onClick = vm::verifyAndDeleteAccount,
                    enabled = !deletion.busy && deletion.code.length == 6,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(localized(language, "永久删除账号", "Permanently delete account"))
                }
                AccountDeletionStage.RETRY_COMMIT -> TextButton(
                    onClick = vm::retryAccountDeletionCommit,
                    enabled = !deletion.busy && deletion.error?.retryable != false,
                ) { Text(localized(language, "重试完成删除", "Retry deletion")) }
            }
        },
        dismissButton = if (canDismiss) {
            {
                TextButton(onClick = vm::dismissAccountDeletion) {
                    Text(localized(language, "取消", "Cancel"))
                }
            }
        } else {
            null
        },
    )
}

@Composable
internal fun AccountDeletionConfirmationContent(
    deletion: AccountDeletionUiState,
    vm: AccountDevicesViewModel,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            localized(
                language,
                "删除提交后，所有云端会话、Mac 绑定和共享会立即失效；云端个人数据将在 30 天后清理。操作无法撤销，原账号不可恢复。",
                "Once submitted, all Cloud sessions, Mac bindings, and shares end immediately. Cloud personal data is cleaned after 30 days. This cannot be undone and the account cannot be recovered.",
            ),
        )
        Text(
            localized(
                language,
                "Mac 上的 Hermes 会话、文件、配置和模型不会被删除。",
                "Hermes conversations, files, configuration, and models on your Macs are not deleted.",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = deletion.typedConfirmation,
            onValueChange = vm::onAccountDeletionConfirmationChange,
            label = { Text(localized(language, "输入 DELETE", "Type DELETE")) },
            supportingText = {
                Text(localized(language, "必须与 DELETE 完全一致", "Must exactly match DELETE"))
            },
            singleLine = true,
            enabled = !deletion.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .testTag("account-deletion-acknowledgement")
                .toggleable(
                    value = deletion.acknowledged,
                    enabled = !deletion.busy,
                    role = Role.Checkbox,
                    onValueChange = vm::onAccountDeletionAcknowledgedChange,
                ),
        ) {
            Checkbox(
                checked = deletion.acknowledged,
                onCheckedChange = null,
                enabled = !deletion.busy,
            )
            Text(
                localized(
                    language,
                    "我理解这是永久删除且无法撤销",
                    "I understand this is permanent and cannot be undone",
                ),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun AccountDeletionCommittedContent(vm: AccountDevicesViewModel) {
    val language = LocalAppLanguage.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                localized(language, "云端账号删除已提交", "Cloud account deletion submitted"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                localized(
                    language,
                    "云端访问已立即停止，个人数据将在 30 天期限后清理。Mac 上的 Hermes 会话、文件、配置和模型仍然保留。",
                    "Cloud access stopped immediately. Personal data will be cleaned after the 30-day period. Hermes conversations, files, configuration, and models on your Macs remain.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = vm::startNewAccountSignIn, modifier = Modifier.fillMaxWidth()) {
                Text(localized(language, "使用其他邮箱账号", "Use another email account"))
            }
        }
    }
}

@Composable
private fun SignInContent(state: AccountDevicesUiState, vm: AccountDevicesViewModel) {
    val language = LocalAppLanguage.current
    Text(localized(language, "使用邮箱登录", "Sign in with email"), style = MaterialTheme.typography.headlineSmall)
    Text(
        localized(
            language,
            "验证码会发送到你的邮箱。登录同一账号后，可以选择自己或他人共享给你的 Mac。",
            "We'll email you a verification code. Sign in to choose a Mac you own or one shared with you.",
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
        enabled = !state.busy && state.stage != AccountStage.CODE_SENT,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.stage == AccountStage.CODE_SENT) {
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
        Button(
            onClick = vm::verifyCode,
            enabled = state.code.length == 6 && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(localized(language, "验证并登录", "Verify and sign in")) }
        TextButton(
            onClick = vm::resendCode,
            enabled = !state.busy && state.resendInSeconds == 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.resendInSeconds > 0) {
                    localized(
                        language,
                        "${state.resendInSeconds} 秒后可重新发送",
                        "Resend in ${state.resendInSeconds}s",
                    )
                } else {
                    localized(language, "重新发送验证码", "Resend code")
                },
            )
        }
        TextButton(onClick = vm::startOver, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(localized(language, "更换邮箱", "Use another email"))
        }
    } else {
        Button(
            onClick = vm::sendCode,
            enabled = AccountDevicesViewModel.looksLikeEmail(state.email) && !state.busy &&
                state.stage != AccountStage.UNAVAILABLE,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(localized(language, "发送验证码", "Send verification code")) }
    }
    if (state.busy) LoadingRow(localized(language, "请稍候…", "Please wait…"))
}

internal fun formatOtpCountdown(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
}

@Composable
private fun AccountContent(state: AccountDevicesUiState) {
    val language = LocalAppLanguage.current
    val account = state.session ?: return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                account.accountDisplayName ?: localized(language, "Hermes GO 账号", "Hermes GO account"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            account.accountEmail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                localized(language, "本机：${account.installationDisplayName}", "This phone: ${account.installationDisplayName}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceContent(
    state: AccountDevicesUiState,
    vm: AccountDevicesViewModel,
) {
    val language = LocalAppLanguage.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(localized(language, "选择要使用的 Mac", "Choose a Mac"), style = MaterialTheme.typography.headlineSmall)
            Text(
                localized(language, "最多 3 台自有设备；共享给你的设备会另外列出。", "Up to 3 owned Macs; Macs shared with you appear here too."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = vm::refresh, enabled = !state.busy) {
            Icon(Icons.Rounded.Refresh, contentDescription = localized(language, "刷新设备", "Refresh devices"))
        }
    }
    if (state.busy) LoadingRow(localized(language, "正在加载设备…", "Loading devices…"))
    if (!state.busy && state.devices.isEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(localized(language, "还没有可用的 Mac", "No Mac is available yet"), style = MaterialTheme.typography.titleMedium)
                Text(
                    localized(
                        language,
                        "请在 Mac 上打开 Hermes Go Desktop，并使用同一邮箱登录或让设备所有者分享给你。停留在本页时会自动检查。",
                        "Open Hermes Go Desktop on the Mac and sign in with the same email, or ask the owner to share the device. We'll keep checking while this page is open.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    state.devices.forEach { device ->
        DeviceCard(
            device = device,
            selected = state.session?.selectedDeviceId == device.deviceId,
            selecting = state.selectingDeviceId == device.deviceId,
            onUse = { vm.useDevice(device) },
        )
    }
}

@Composable
private fun DeviceCard(device: AccountDeviceDto, selected: Boolean, selecting: Boolean, onUse: () -> Unit) {
    val language = LocalAppLanguage.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(device.desktopDisplayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (device.access == "owner") localized(language, "我的设备", "Owned by me")
                        else localized(language, "共享给我", "Shared with me"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = localized(language, "当前设备", "Current device"), tint = MaterialTheme.colorScheme.primary)
            }
            Text(deviceStatusText(device, language), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onUse, enabled = !selecting, modifier = Modifier.fillMaxWidth()) {
                if (selecting) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                }
                Text(
                    if (selected) localized(language, "重新检查连接", "Recheck connection")
                    else localized(language, "使用这台 Mac", "Use this Mac"),
                )
            }
        }
    }
}

internal fun deviceStatusText(
    device: AccountDeviceDto,
    language: com.hermes.client.ui.localization.AppLanguage,
): String {
    return when {
        !device.connector.online -> localized(language, "Desktop Connector 离线", "Desktop Connector offline")
        device.hermes.reachable == false -> localized(language, "Connector 在线 · Hermes 不可达", "Connector online · Hermes unreachable")
        device.endToEnd.healthy == true -> localized(language, "端到端连接正常", "End-to-end connection healthy")
        else -> localized(language, "正在等待端到端检查", "Waiting for end-to-end check")
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun accountErrorText(
    code: String,
    language: com.hermes.client.ui.localization.AppLanguage,
): String {
    return when (code) {
        "HR-AUTH-003" -> localized(language, "登录已过期，请重新登录。", "Your session expired. Sign in again.")
        "HR-AUTH-004", "HR-BIND-004" -> localized(language, "这台手机的登录已被撤销，请重新登录。", "This phone's session was revoked. Sign in again.")
        "HR-AUTH-005" -> localized(language, "为保护账号，这台手机已安全退出。", "This phone was signed out for account safety.")
        "HR-AUTH-006" -> localized(language, "为确认是你本人，请重新验证当前账号的登录方式。", "Verify your sign-in identity again to confirm it's you.")
        "HR-AUTH-009" -> localized(language, "邮箱验证码无效或已过期，请重新获取。", "The email code is invalid or expired. Request a new code.")
        "HR-AUTH-010" -> localized(language, "登录邮件发送失败，请稍后重试。", "The sign-in email couldn't be sent. Try again shortly.")
        "HR-AUTH-011", "HR-ACCOUNT-003" -> localized(language, "此 Relay 尚未启用邮箱登录，可继续使用旧版连接。", "Email sign-in isn't enabled on this Relay yet. You can continue with the legacy connection.")
        "HR-ACCOUNT-012" -> localized(language, "此 Hermes GO 账号正在永久删除，已无法再次登录。", "This Hermes GO account is being permanently deleted and can no longer sign in.")
        "HR-BIND-001" -> localized(language, "这个账号还没有连接 Desktop，请先在 Mac 上打开 Hermes Go Desktop。", "This account has no Desktop connection yet. Open Hermes Go Desktop on the Mac.")
        "HR-BIND-009" -> localized(language, "请先选择要使用的 Mac。", "Choose which Mac to use.")
        "HR-BIND-011" -> localized(language, "这台 Mac 已无法由当前账号使用，请选择其他设备。", "That Mac is no longer available to this account. Choose another device.")
        "HR-CONN-005" -> localized(language, "Mac 当前离线，请启动 Hermes Go Desktop 后重试。", "The Mac is offline. Start Hermes Go Desktop and try again.")
        else -> localized(language, "账号服务暂时不可用，请稍后重试。", "The account service is temporarily unavailable. Try again shortly.")
    }
}
