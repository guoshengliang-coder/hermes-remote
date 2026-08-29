package com.hermes.client.ui.settings
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/** Settings hub — mirrors the desktop Settings sections (built out incrementally). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onMenu: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val language = LocalAppLanguage.current
    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = localized(language, "设置", "Settings"),
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localized(language, "返回", "Back")) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Entry(localized(language, "服务器与令牌", "Server & token"), localized(language, "本应用连接的网关地址和令牌", "Gateway URL and token this app connects to")) { onNavigate("settings_connection") }
            HorizontalDivider()
            Entry(localized(language, "外观", "Appearance"), localized(language, "主题、明暗模式和工具调用显示", "Theme, light/dark, tool-call display")) { onNavigate("settings_appearance") }
            HorizontalDivider()
            Entry(localized(language, "语言", "Language"), localized(language, "简体中文或 English", "Simplified Chinese or English")) { onNavigate("settings_language") }
            HorizontalDivider()
            Entry(localized(language, "通知", "Notifications"), localized(language, "审批、定时任务和消息提醒", "Approvals, cron, and messaging alerts")) { onNavigate("settings_notifications") }
            HorizontalDivider()
            Entry(localized(language, "记忆与预算", "Memory & budgets"), localized(language, "记忆、用户资料和默认模型", "Memory, user profile & default model")) { onNavigate("settings_memory") }
            HorizontalDivider()
            Entry(localized(language, "常用提示", "Saved prompts"), localized(language, "在输入框中复用的提示词", "Reusable prompts for the composer")) { onNavigate("settings_prompts") }
            HorizontalDivider()
            Entry(localized(language, "MCP 服务器", "MCP servers"), localized(language, "查看和编辑已连接的 MCP 服务器", "View and edit connected MCP servers")) { onNavigate("settings_mcp") }
            HorizontalDivider()
            Entry(localized(language, "API 密钥与环境变量", "API keys & env"), localized(language, "模型服务密钥和工具环境变量", "Provider keys and tool env vars")) { onNavigate("settings_env") }
            HorizontalDivider()
            Entry(localized(language, "诊断", "Diagnostics"), localized(language, "生成可分享的调试日志以排查错误", "Capture a shareable debug log to troubleshoot errors")) { onNavigate("settings_diagnostics") }
            HorizontalDivider()
            Entry(localized(language, "关于", "About"), localized(language, "应用与网关版本", "App and gateway version")) { onNavigate("settings_about") }
        }
    }
}

@Composable
private fun Entry(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        modifier = Modifier.clickable { onClick() },
    )
}
