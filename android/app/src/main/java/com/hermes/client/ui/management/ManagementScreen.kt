package com.hermes.client.ui.management
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hermes.client.ui.localization.l10n

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagementScreen(
    onMenu: () -> Unit,
    onNavigate: (String) -> Unit = {},
) {
    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = l10n("管理", "Management"),
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = l10n("返回", "Back")) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ListItem(
                headlineContent = { Text(l10n("会话管理", "Session admin")) },
                supportingContent = { Text(l10n("搜索消息、归档会话与统计", "Search messages, archived sessions, stats")) },
                modifier = Modifier.clickable { onNavigate("session_admin") },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(l10n("智能体与工具", "Agents & tools")) },
                supportingContent = { Text(l10n("开关技能、查看工具集", "Toggle skills, view toolsets")) },
                modifier = Modifier.clickable { onNavigate("agents_tools") },
            )
        }
    }
}
