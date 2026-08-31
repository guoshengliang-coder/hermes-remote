package com.hermes.client.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

@Composable
fun SetupScreen(vm: SetupViewModel = hiltViewModel(), onSaved: () -> Unit) {
    val language = LocalAppLanguage.current
    val state by vm.state.collectAsStateWithLifecycle()
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.applyPairing(it) }
    }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = CircleShape,
        ) {
            Text(
                "HERMES REMOTE",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(localized(language, "连接你的 Mac", "Connect your Mac"), style = MaterialTheme.typography.headlineSmall)
        Text(
            localized(language, "手机通过香港 Relay 安全访问 Hermes，无需开启 VPN，也不会暴露 Mac 的登录密码。", "Securely access Hermes through the Hong Kong relay without a VPN or exposing your Mac password."),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 4.dp,
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.padding(10.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column {
                        Text(localized(language, "安全连接", "Secure connection"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            localized(language, "只需要 Relay 地址和 App Token", "Only the relay address and App Token are required"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = state.url,
                    onValueChange = vm::onUrlChange,
                    label = { Text(localized(language, "Relay 地址", "Relay URL")) },
                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = state.token,
                    onValueChange = vm::onTokenChange,
                    label = { Text("App Token") }, // l10n-allow: protocol credential name
                    leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )

                state.testResult?.let { result ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.CloudDone,
                            contentDescription = null,
                            tint = if (result == SetupResult.CONNECTED) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            when (result) {
                                SetupResult.CONNECTED -> localized(language, "Relay 与 Mac 已连接", "Relay and Mac are connected")
                                SetupResult.INVALID_URL -> localized(language, "Relay 地址无效（HR-CONFIG-003）", "Invalid Relay URL (HR-CONFIG-003)")
                                SetupResult.UNREACHABLE -> localized(language, "暂时无法连接，请检查地址和 Token（HR-CONN-002）", "Couldn't connect. Check the URL and token (HR-CONN-002).")
                            },
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // One action: probe first, persist only on success — no more saving an unverified
                // config and landing in a dead chat screen (the two equal-weight buttons let that
                // happen). The QR path already worked this way.
                Button(
                    onClick = { vm.connect() },
                    enabled = state.url.isNotBlank() && state.token.isNotBlank() && !state.connecting,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        if (state.connecting) localized(language, "正在连接…", "Connecting…")
                        else localized(language, "连接并进入 Hermes", "Connect and open Hermes"),
                    )
                }
            }
        }

        OutlinedButton(
            onClick = {
                scanLauncher.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt(localized(language, "扫描 Hermes Remote 配对二维码", "Scan the Hermes Remote pairing QR code"))
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    },
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(50.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
            Text(localized(language, "扫描配对二维码", "Scan pairing QR code"), modifier = Modifier.padding(start = 8.dp))
        }
        if (state.scanError) {
            Text(
                localized(language, "二维码不是有效的 Hermes Remote 配置", "This QR code isn't a valid Hermes Remote configuration"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
