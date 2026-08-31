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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    onBack: () -> Unit,
    vm: ConnectionSettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = localized(language, "Relay 连接", "Relay connection"),
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localized(language, "返回", "Back")) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                localized(language, "Hermes Remote 只使用独立的 App Token。Mac 上的 Hermes 用户名和密码不会发送到手机。", "Hermes Remote only uses a dedicated App Token. Your Mac's Hermes username and password are never sent to the phone."),
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
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.test() }) { Text(localized(language, "测试连接", "Test connection")) }
                Button(onClick = { vm.save() }) { Text(localized(language, "保存并重连", "Save and reconnect")) }
            }
            state.testResult?.let { Text(it.resolve(language), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
