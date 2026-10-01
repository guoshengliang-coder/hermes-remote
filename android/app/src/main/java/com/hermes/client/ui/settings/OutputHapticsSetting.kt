package com.hermes.client.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

@Composable
internal fun OutputHapticsSetting(state: OutputHapticsSettingsState, onChange: (Boolean) -> Unit, onRetry: () -> Unit) {
    val language = LocalAppLanguage.current
    ListItem(
        headlineContent = { Text(localized(language, "输出触感", "Output haptics")) },
        supportingContent = { Text(localized(language, "回答文字出现时轻触反馈，遵循系统触觉设置", "Light feedback as answer text appears. Follows system haptics settings.")) },
        trailingContent = { Switch(checked = state.enabled, onCheckedChange = null, enabled = state.loaded && !state.saving) },
        modifier = Modifier.toggleable(state.enabled, enabled = state.loaded && !state.saving, role = Role.Switch, onValueChange = onChange),
    )
    state.error?.let { error ->
        Column {
            com.hermes.client.ui.components.ErrorState(error, modifier = Modifier, onRetry = onRetry)
            var details by remember(error) { mutableStateOf(false) }
            val clipboard = LocalClipboardManager.current
            TextButton(onClick = { details = !details }) { Text(localized(language, "查看详情", "View details")) }
            if (details) {
                Text(error.sanitizedDiagnostic(), Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = { clipboard.setText(AnnotatedString(error.sanitizedDiagnostic())) }) {
                    Text(localized(language, "复制诊断信息", "Copy diagnostics"))
                }
            }
        }
    }
}
