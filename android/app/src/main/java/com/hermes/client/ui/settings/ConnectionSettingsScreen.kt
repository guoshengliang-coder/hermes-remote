package com.hermes.client.ui.settings
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.startup.StartupFailure

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    onBack: (() -> Unit)?,
    repairFailure: StartupFailure? = null,
    onSaved: () -> Unit = {},
    vm: ConnectionSettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = localized(language, "Relay 连接", "Relay connection"),
                navigationIcon = {
                    onBack?.let { action ->
                        IconButton(onClick = action) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localized(language, "返回", "Back")) }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repairFailure?.let { failure ->
                Text(
                    text = repairMessage(failure, language),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                localized(language, "Hermes GO 只使用独立的 App Token。Mac 上的 Hermes 用户名和密码不会发送到手机。", "Hermes GO only uses a dedicated App Token. Your Mac's Hermes username and password are never sent to the phone."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.url,
                onValueChange = vm::onUrlChange,
                label = { Text(localized(language, "Relay 地址", "Relay URL")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.token,
                onValueChange = vm::onTokenChange,
                label = { Text("App Token") }, // l10n-allow: protocol credential name
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.test() }) { Text(localized(language, "测试连接", "Test connection")) }
                Button(onClick = { vm.save(reconnect = repairFailure == null) }) {
                    Text(localized(language, "保存并重连", "Save and reconnect"))
                }
            }
            state.testResult?.let { Text(it.resolve(language), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private fun repairMessage(failure: StartupFailure, language: com.hermes.client.ui.localization.AppLanguage): String =
    when (failure) {
        StartupFailure.AUTHENTICATION_FAILED -> localized(
            language,
            "App Token 或登录凭据无效，请修改后测试连接。（${failure.code}）",
            "The App Token or login credentials were rejected. Update them and test the connection. (${failure.code})",
        )
        StartupFailure.INVALID_URL -> localized(
            language,
            "Relay 地址无效或不是兼容的服务，请修改后测试连接。（${failure.code}）",
            "The Relay URL is invalid or doesn't point to a compatible service. Update it and test the connection. (${failure.code})",
        )
        StartupFailure.CONFIGURATION_FAILED -> localized(
            language,
            "连接配置无法读取，请重新填写并测试。（${failure.code}）",
            "The connection configuration couldn't be read. Enter it again and test it. (${failure.code})",
        )
        StartupFailure.CONNECTION_FAILED -> localized(
            language,
            "Relay 暂时无法连接；你也可以检查地址和凭据。（${failure.code}）",
            "The Relay couldn't be reached. You can also check the address and credentials. (${failure.code})",
        )
        else -> localized(
            language,
            "启动检查未通过，请检查连接配置。（${failure.code}）",
            "The startup check didn't complete. Check the connection configuration. (${failure.code})",
        )
    }
