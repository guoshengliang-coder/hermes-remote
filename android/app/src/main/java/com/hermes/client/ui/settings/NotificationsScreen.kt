package com.hermes.client.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.repository.NotificationSettings
import com.hermes.client.data.repository.NotificationMonitoringStrategy
import com.hermes.client.data.repository.NotificationMonitoringStrategyStore
import com.hermes.client.notifications.NotificationPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage
import com.hermes.client.ui.theme.StatusTone
import com.hermes.client.ui.theme.statusColor
import com.hermes.client.notifications.push.PushRegistrationManager
import com.hermes.client.notifications.push.PushStatus
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val settings: NotificationSettings,
    private val strategyStore: NotificationMonitoringStrategyStore,
    private val push: PushRegistrationManager,
) : ViewModel() {
    val pushStatus: StateFlow<PushStatus> = push.status

    val prefs: StateFlow<NotificationPrefs> =
        settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationPrefs())
    val strategy: StateFlow<NotificationMonitoringStrategy> = strategyStore.strategy.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NotificationMonitoringStrategy.ADAPTIVE,
    )

    fun setEnabled(v: Boolean) = viewModelScope.launch { settings.setEnabled(v) }
    fun setApprovals(v: Boolean) = viewModelScope.launch { settings.setApprovals(v) }
    fun setRunFinished(v: Boolean) = viewModelScope.launch { settings.setRunFinished(v) }
    fun setRunFailed(v: Boolean) = viewModelScope.launch { settings.setRunFailed(v) }
    fun setRunProgress(v: Boolean) = viewModelScope.launch { settings.setRunProgress(v) }
    fun setStrategy(v: NotificationMonitoringStrategy) = viewModelScope.launch { strategyStore.set(v) }
    fun retryPush() = push.retry()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit, vm: NotificationsViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val strategy by vm.strategy.collectAsStateWithLifecycle()
    val pushStatus by vm.pushStatus.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.setEnabled(true)
        }
    }

    fun enable() {
        if (Build.VERSION.SDK_INT >= 33) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.setEnabled(true)
        }
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = localized(language, "通知", "Notifications"),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = localized(language, "返回", "Back"),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            ToggleRow(
                localized(language, "启用通知", "Enable notifications"),
                localized(language, "任务完成、需要处理或出现重要状态时提醒。", "Notify when tasks finish, need attention, or reach an important state."),
                prefs.enabled,
            ) { on -> if (on) enable() else vm.setEnabled(false) }
            // Turning notifications off no longer disconnects a run in flight (docs/DESIGN.md
            // §5.10), so say what the user will still see — an unannounced ongoing card would
            // read as the switch not working.
            if (!prefs.enabled) {
                Text(
                    localized(
                        language,
                        "关闭后仍会在任务运行期间显示一张静默的「后台保持连接」卡片，任务结束即消失——" +
                            "手机需要它才能跟完这次运行。选择「省电」可以连这个也一起停掉。",
                        "Even when this is off, a silent \"Connected in the background\" card appears " +
                            "while a run is in flight and disappears when it ends — the phone needs it to " +
                            "follow the run through. Choose Power saving to stop that too.",
                    ),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Text(
                localized(language, "后台监控方式", "Background monitoring"),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // Reachable even with notifications off. A running task now keeps its connection
            // regardless of the notification switch, so 省电 is the only way to opt out of that;
            // greying the group out with the switch left those users no control at all.
            Text(
                localized(
                    language,
                    "也决定任务运行时是否保持连接，关闭通知后同样有效。",
                    "Also decides whether a running task keeps its connection, including when notifications are off.",
                ),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MonitoringStrategyRow(
                title = localized(language, "智能（推荐）", "Smart (recommended)"),
                subtitle = localized(
                    language,
                    "前台实时；手机发起的任务在后台高频监控；空闲时使用系统低频检查。",
                    "Real-time in foreground, frequent while a phone-started task runs, and low-frequency system checks when idle.",
                ),
                selected = strategy == NotificationMonitoringStrategy.ADAPTIVE,
                enabled = true,
            ) { vm.setStrategy(NotificationMonitoringStrategy.ADAPTIVE) }
            MonitoringStrategyRow(
                title = localized(language, "实时", "Real-time"),
                subtitle = localized(
                    language,
                    "后台持续保持连接，提醒最快，但耗电最高并显示常驻通知。",
                    "Keeps a background connection for the fastest alerts, with higher battery use and an ongoing notification.",
                ),
                selected = strategy == NotificationMonitoringStrategy.REALTIME,
                enabled = true,
            ) { vm.setStrategy(NotificationMonitoringStrategy.REALTIME) }
            MonitoringStrategyRow(
                title = localized(language, "省电", "Power saving"),
                subtitle = localized(
                    language,
                    "后台不保持长连接（任务运行时也不保持），由系统定期检查，提醒可能延迟约 15 分钟。",
                    "No persistent background connection, not even while a task is running. " +
                        "System checks may delay alerts by about 15 minutes.",
                ),
                selected = strategy == NotificationMonitoringStrategy.POWER_SAVING,
                enabled = true,
            ) { vm.setStrategy(NotificationMonitoringStrategy.POWER_SAVING) }
            // Read-only: push is not a fourth strategy, it only makes the chosen one react sooner
            // (docs/DESIGN.md §5.10, HG-94).
            PushStatusRow(pushStatus, onRetry = vm::retryPush)
            HorizontalDivider()
            ToggleRow(
                localized(language, "审批与回答", "Approvals and questions"),
                localized(language, "智能体需要你审批操作或回答问题时提醒", "When the agent needs you to approve an action or answer a question"),
                prefs.approvals,
                enabled = prefs.enabled,
            ) { vm.setApprovals(it) }
            HorizontalDivider()
            ToggleRow(
                localized(language, "任务完成", "Task finished"),
                localized(language, "智能体运行完成后提醒（正在查看该会话时除外）", "Notify when an agent run completes (unless you are viewing that chat)"),
                prefs.runFinished,
                enabled = prefs.enabled,
            ) { vm.setRunFinished(it) }
            HorizontalDivider()
            ToggleRow(
                localized(language, "运行失败", "Run failed"),
                localized(language, "运行失败或停止后未确认完成时提醒", "Notify when a run fails or stops without a confirmed completion"),
                prefs.runFailed,
                enabled = prefs.enabled,
            ) { vm.setRunFailed(it) }
            HorizontalDivider()
            ToggleRow(
                localized(language, "实时运行进度", "Live run progress"),
                localized(language, "智能体运行时显示持续更新的进度通知", "Show an ongoing notification with live progress while an agent run is in flight"),
                prefs.runProgress,
                enabled = prefs.enabled,
            ) { vm.setRunProgress(it) }
        }
    }
}

/**
 * The 实时推送 status row (docs/DESIGN.md §5.10). One value line, coloured only when it is good
 * news or a failure; the failure carries HR-NOTIF-002 and the only action on the row, Retry.
 */
@Composable
fun PushStatusRow(status: PushStatus, onRetry: () -> Unit) {
    val language = LocalAppLanguage.current
    val (value, tone) = when (status) {
        PushStatus.Enabled -> localized(language, "已启用", "Enabled") to StatusTone.GOOD
        PushStatus.NotConfigured, PushStatus.ServerUnsupported ->
            localized(language, "未配置", "Not configured") to null
        PushStatus.NoGooglePlayServices ->
            localized(
                language,
                "本机无 Google 服务，使用定时同步",
                "No Google Play services — using periodic sync",
            ) to null
        PushStatus.Inactive ->
            localized(language, "未开启（需开启通知并登录账号）", "Off — needs notifications and an account sign-in") to null
        PushStatus.Registering -> localized(language, "正在注册…", "Registering…") to null
        is PushStatus.Failed -> status.error.localizedMessage(language) to StatusTone.BAD
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(localized(language, "实时推送", "Real-time push"), style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = tone?.let { statusColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status is PushStatus.Failed && status.error.retryable) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(localized(language, "重试", "Retry"))
            }
        }
    }
}

@Composable
private fun MonitoringStrategyRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
