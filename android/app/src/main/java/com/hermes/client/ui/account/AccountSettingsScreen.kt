package com.hermes.client.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * Settings > Hermes GO account. Manages the account and THIS phone's session only: §5.0 keeps
 * remote devices on the card page's "remote node" detail and forbids duplicating them here.
 *
 * [onSignIn] exists because this page is reachable while signed out — a phone still holding a
 * working legacy Relay connection has a configuration, so nothing forces it through the sign-in
 * gate first.
 */
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    vm: AccountDevicesViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingExit by remember { mutableStateOf<AccountExit?>(null) }

    pendingExit?.let { exit ->
        AlertDialog(
            onDismissRequest = { pendingExit = null },
            title = {
                Text(
                    when (exit) {
                        AccountExit.SIGN_OUT -> localized(language, "退出这台手机？", "Sign out on this phone?")
                        AccountExit.SWITCH -> localized(language, "切换账号？", "Switch account?")
                    },
                )
            },
            text = {
                Text(
                    when (exit) {
                        AccountExit.SIGN_OUT -> localized(
                            language,
                            "只会撤销这台手机的登录。其他手机、Desktop 和 Mac 连接不会受影响。",
                            "Only this phone's session will be revoked. Other phones, Desktop, and Mac connections stay signed in.",
                        )
                        // Say the destructive half out loud: switching is a sign-out first, not a
                        // lossless hop between two accounts this phone keeps side by side.
                        AccountExit.SWITCH -> localized(
                            language,
                            "会先退出当前账号，再用另一个邮箱登录。这台手机一次只保留一个账号。",
                            "This signs out of the current account first, then signs in with another email. This phone keeps one account at a time.",
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingExit = null; vm.signOut(onSignedOut) }) {
                    Text(
                        when (exit) {
                            AccountExit.SIGN_OUT -> localized(language, "退出", "Sign out")
                            AccountExit.SWITCH -> localized(language, "退出并切换", "Sign out and switch")
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingExit = null }) {
                    Text(localized(language, "取消", "Cancel"))
                }
            },
        )
    }
    state.accountDeletion?.let { deletion -> AccountDeletionDialog(deletion = deletion, vm = vm) }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = localized(language, "Hermes GO 账号", "Hermes GO account"),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = localized(language, "返回", "Back"),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.stage) {
                AccountStage.DISCOVERING ->
                    LoadingRow(localized(language, "正在检查账号服务…", "Checking account service…"))
                AccountStage.UNAVAILABLE,
                AccountStage.SIGNED_OUT,
                AccountStage.CODE_SENT -> SignedOutPrompt(onSignIn)
                AccountStage.SIGNED_IN -> {
                    AccountContent(state)
                    OutlinedButton(
                        onClick = { pendingExit = AccountExit.SWITCH },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("account-switch"),
                    ) { Text(localized(language, "切换账号", "Switch account")) }
                    OutlinedButton(
                        onClick = { pendingExit = AccountExit.SIGN_OUT },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("account-sign-out"),
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
                        Text(
                            accountErrorText(error.code, language),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(error.code, style = MaterialTheme.typography.labelMedium)
                        if (error.retryable) {
                            TextButton(onClick = vm::retryError, enabled = !state.busy) {
                                Text(localized(language, "重试", "Retry"))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Which of the two exits the confirmation dialog is confirming; both end in the same sign-out. */
private enum class AccountExit { SIGN_OUT, SWITCH }

@Composable
private fun SignedOutPrompt(onSignIn: () -> Unit) {
    val language = LocalAppLanguage.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                localized(language, "还没有登录 Hermes GO 账号", "Not signed in to Hermes GO"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                localized(
                    language,
                    "登录后即可用邮箱连接你的 Mac，不再需要填写服务器地址。",
                    "Sign in with your email to reach your Mac — no server address to type.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth().testTag("account-sign-in"),
            ) { Text(localized(language, "登录", "Sign in")) }
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
internal fun AccountDeletionCommittedContent(vm: AccountDevicesViewModel) {
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
