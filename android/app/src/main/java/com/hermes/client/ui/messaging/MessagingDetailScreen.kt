package com.hermes.client.ui.messaging

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.theme.StatusTone
import com.hermes.client.ui.theme.statusColor

/**
 * One channel, management side only.
 *
 * Its conversations are deliberately NOT listed here — they live in the 机器人 segment on the home
 * screen, and duplicating them would give the same content two homes. This page answers the three
 * questions the list cannot: is it healthy, where does it deliver, and what else depends on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagingDetailScreen(
    platformId: String,
    onBack: () -> Unit,
    onEditCredentials: (String) -> Unit,
    onOpenBots: () -> Unit,
    onOpenCron: () -> Unit,
    vm: MessagingViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val platform = state.platforms.firstOrNull { it.id == platformId }
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    val stateMessage = state.message?.resolve(language)
    androidx.compose.runtime.LaunchedEffect(stateMessage) {
        stateMessage?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = platform?.name ?: platformId,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = l10n("返回", "Back"))
                    }
                },
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
    ) { padding ->
        if (platform == null) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                com.hermes.client.ui.components.LoadingState()
            }
            return@Scaffold
        }
        val status = messagingRowStatus(platform)
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "status") {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = com.hermes.client.ui.theme.tileColor(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(10.dp).background(
                                    when (status) {
                                        MessagingRowStatus.CONNECTED -> statusColor(StatusTone.GOOD, dark)
                                        MessagingRowStatus.PENDING_RESTART -> statusColor(StatusTone.WARN, dark)
                                        MessagingRowStatus.FAILED, MessagingRowStatus.GATEWAY_STOPPED ->
                                            statusColor(StatusTone.BAD, dark)
                                        else -> MaterialTheme.colorScheme.outline
                                    },
                                    CircleShape,
                                ),
                            )
                            Text(
                                messagingStatusText(status).resolve(language),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 10.dp).weight(1f),
                            )
                        }
                        // Hermes' own words, under our summary rather than instead of it.
                        platform.errorMessage?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Row(Modifier.padding(top = 14.dp)) {
                            OutlinedButton(
                                onClick = { vm.test(platform.id) },
                                enabled = state.testing != platform.id,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    if (state.testing == platform.id) l10n("检测中…", "Testing…")
                                    else l10n("测试连接", "Test"),
                                )
                            }
                            androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                            OutlinedButton(
                                onClick = { onEditCredentials(platform.id) },
                                modifier = Modifier.weight(1f),
                            ) { Text(l10n("凭据设置", "Credentials")) }
                        }
                    }
                }
            }

            item(key = "bots") {
                DetailRow(
                    icon = Icons.Rounded.Forum,
                    label = l10n("在会话里查看", "See the conversations"),
                    onClick = onOpenBots,
                )
            }
            item(key = "cron") {
                DetailRow(
                    icon = Icons.Rounded.Schedule,
                    label = l10n("投递到此渠道的定时任务", "Scheduled jobs delivering here"),
                    onClick = onOpenCron,
                )
            }
            item(key = "home") {
                // The same value cron delivery and a session handoff both land on. Without it
                // both fail, so it is stated rather than hidden inside the credentials form.
                DetailRow(
                    icon = Icons.Rounded.Home,
                    label = l10n("默认投递落点", "Default delivery target"),
                    value = platform.homeChannel?.takeIf { it.isNotBlank() }
                        ?: l10n("未设置", "Not set"),
                    onClick = null,
                )
                if (platform.homeChannel.isNullOrBlank()) {
                    Text(
                        l10n(
                            "没设落点时，定时任务投递和「转到此渠道」都会失败。要在目标聊天里用 /sethome 设置。",
                            "Without a target, scheduled delivery and handoff both fail. Set it with /sethome in the destination chat.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
            item(key = "enabled") {
                ListItem(
                    headlineContent = { Text(l10n("启用", "Enabled")) },
                    supportingContent = {
                        Text(l10n("关闭后要重启网关才会断开。", "Disabling takes effect after a gateway restart."))
                    },
                    trailingContent = {
                        Switch(
                            checked = platform.enabled,
                            onCheckedChange = { vm.toggle(platform.id, it) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String? = null,
    onClick: (() -> Unit)?,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                value?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // "可点必有箭头" — and nothing else gets one (docs/DESIGN.md §5.1).
                if (onClick != null) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier,
    )
}
