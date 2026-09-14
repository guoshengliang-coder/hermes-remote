package com.hermes.client.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.network.AccountDeviceDto
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * Choosing which Mac to talk to. Split out of the sign-in page (`DESIGN.md` §5.19) so that logging
 * in and picking a machine are two screens instead of one long scroll.
 *
 * This is not a third entry point: §5.0 keeps the card page's "remote node" card as the single
 * binding entry, and this composable is that detail page as well as the step right after sign-in.
 * [onBack] is null in the sign-in flow, where there is nothing behind it to go back to.
 */
@Composable
fun DeviceSelectionScreen(
    onBack: (() -> Unit)?,
    onConnected: () -> Unit = {},
    onOpenLegacy: (() -> Unit)? = null,
    onOpenDiagnostics: (() -> Unit)? = null,
    vm: AccountDevicesViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val state by vm.state.collectAsStateWithLifecycle()

    DisposableEffect(vm) {
        vm.setDevicePageVisible(true)
        onDispose { vm.setDevicePageVisible(false) }
    }

    // Both the automatic single-device path and an explicit choice continue once the probed device
    // has been durably selected; the dashboard host stays put.
    LaunchedEffect(onBack, state.session?.selectedDeviceId) {
        if (onBack == null && state.session?.selectedDeviceId != null) onConnected()
    }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = if (onBack == null) {
                    localized(language, "连接 Hermes GO", "Connect Hermes GO")
                } else {
                    localized(language, "远程设备", "Remote devices")
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
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceContent(state, vm)

            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("device-error"),
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

            if (onOpenLegacy != null || onOpenDiagnostics != null) {
                Text(
                    localized(language, "兼容连接", "Compatibility"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onOpenLegacy?.let { action ->
                    TextButton(
                        onClick = { vm.allowExplicitLegacyFallback(); action() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(localized(language, "旧版 Relay / App Token", "Legacy Relay / App Token"))
                    }
                }
                onOpenDiagnostics?.let { action ->
                    TextButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                        Text(localized(language, "打开诊断", "Open diagnostics"))
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeviceContent(
    state: AccountDevicesUiState,
    vm: AccountDevicesViewModel,
) {
    val language = LocalAppLanguage.current
    if (state.session?.activationPending == true) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                localized(
                    language,
                    "账号已登录，当前仍使用旧版连接。检测到账号 Mac 并通过端到端验证后才会切换。",
                    "Your account is signed in, but the Legacy connection remains active until an account Mac passes the end-to-end check.",
                ),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(localized(language, "选择要使用的 Mac", "Choose a Mac"), style = MaterialTheme.typography.headlineSmall)
            Text(
                localized(
                    language,
                    when {
                        state.maxOwnedDevices == 1 -> "当前账号可连接 1 台 Mac。"
                        state.supportsDeviceSharing ->
                            "最多 ${state.maxOwnedDevices} 台自有设备；共享给你的设备会另外列出。"
                        else -> "最多可连接 ${state.maxOwnedDevices} 台 Mac。"
                    },
                    when {
                        state.maxOwnedDevices == 1 -> "This account can connect to 1 Mac."
                        state.supportsDeviceSharing ->
                            "Up to ${state.maxOwnedDevices} owned Macs; Macs shared with you appear here too."
                        else -> "Connect up to ${state.maxOwnedDevices} Macs."
                    },
                ),
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
                        if (state.supportsDeviceSharing) {
                            "请在 Mac 上打开 Hermes Go Desktop，并使用同一邮箱登录或让设备所有者分享给你。停留在本页时会自动检查。"
                        } else {
                            "请在 Mac 上打开 Hermes Go Desktop，并使用同一邮箱登录。停留在本页时会自动检查。"
                        },
                        if (state.supportsDeviceSharing) {
                            "Open Hermes Go Desktop on the Mac and sign in with the same email, or ask the owner to share the device. We'll keep checking while this page is open."
                        } else {
                            "Open Hermes Go Desktop on the Mac and sign in with the same email. We'll keep checking while this page is open."
                        },
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
