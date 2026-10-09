package com.hermes.client.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.workspace.*

@Composable
internal fun WorkspaceLayoutSetting(preference: WorkspacePreference, onMode: (WorkspaceMode) -> Unit, onReset: () -> Unit) {
    val language = LocalAppLanguage.current
    val choices = listOf(
        WorkspaceMode.AUTO to localized(language, "自动适应", "Automatic"),
        WorkspaceMode.SINGLE to localized(language, "始终单栏", "Always single column"),
    )
    var open by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(preference.mode) }
    var resetWidth by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(localized(language, "大屏布局", "Large-screen layout")) },
        supportingContent = { Text(choices.first { it.first == preference.mode }.second) },
        modifier = Modifier.clickable { pending = preference.mode; resetWidth = false; open = true },
    )
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(localized(language, "大屏布局", "Large-screen layout")) },
        text = { Column {
            choices.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { pending = value }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = pending == value, onClick = { pending = value })
                    Text(label)
                }
            }
            Text(localized(language, "空间足够时并排显示会话和聊天。拖动中间的分隔线调整栏宽；窗口变窄会自动收起。", "Show conversations beside the chat when space permits. Drag the divider to resize; narrow windows collapse automatically."))
            TextButton(onClick = { resetWidth = true }) { Text(localized(language, "恢复默认栏宽", "Reset list width")) }
            if (resetWidth) Text(localized(language, "保存后恢复为 300dp。", "Restores to 300dp after saving."))
        } },
        confirmButton = { TextButton(onClick = { if (resetWidth) onReset(); onMode(pending); open = false }) { Text(localized(language, "保存", "Save")) } },
        dismissButton = { TextButton(onClick = { open = false }) { Text(localized(language, "取消", "Cancel")) } },
    )
}
